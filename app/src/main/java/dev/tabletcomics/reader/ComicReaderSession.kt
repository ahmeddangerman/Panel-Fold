package dev.tabletcomics.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tabletcomics.archive.OpenArchive
import dev.tabletcomics.archive.ArchiveFormat
import dev.tabletcomics.archive.ArchiveLimits
import dev.tabletcomics.archive.RarArchiveReader
import dev.tabletcomics.archive.archiveCancellationRegistrar
import dev.tabletcomics.archive.detectArchiveFormat
import dev.tabletcomics.archive.ZipArchiveReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Main-thread controller. Archive work and cleanup share the same serialized IO boundary. */
class ComicReaderSession(
    context: Context,
    private val ioMutex: Mutex,
    private val onProgress: (uri: Uri, index: Int, total: Int) -> Unit
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    private var cleanup: Job? = null
    private var archive: OpenArchive? = null
    private var uri: Uri? = null
    private var format = "zip"
    private var epoch = 0L
    private var request = 0L
    private var spreads = false
    private var rtl = false
    private var requestedIndex = 0
    private val destinationRequests = DestinationRequestGate()
    private var seekState by mutableStateOf(SeekState())
    private var disposed = false
    private val thumbnailCache = object : android.util.LruCache<Int, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }
    private data class Cached(val unit: ReaderUnit, val dimension: Int)
    private val cache = linkedMapOf<Int, Cached>()

    // Reserve room for a current upgrade while its lower-resolution version is still drawn.
    private val totalBitmapBudget = min(40L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6)
    private val byteBudget = (totalBitmapBudget - 3L * 1024 * 1024).coerceAtLeast(1L * 1024 * 1024)
    private fun dimensions(solid: Boolean = false): Pair<Int, Int> {
        val pageSlots = if (spreads) 2 else 1
        val worstPixels = pageSlots * if (solid) {
            2 * 1600.0 * 1600 + 900.0 * 900
        } else {
            1600.0 * 1600 + 3 * 900.0 * 900
        }
        val factor = min(1.0, sqrt(byteBudget * 0.85 / (4.0 * worstPixels)))
        return max(256, (1600 * factor).toInt()) to max(144, (900 * factor).toInt())
    }

    var current: ReaderUnit? by mutableStateOf(null)
        private set
    var previous: ReaderUnit? by mutableStateOf(null)
        private set
    var next: ReaderUnit? by mutableStateOf(null)
        private set
    var totalPages: Int by mutableStateOf(0)
        private set
    val loading: Boolean get() = seekState.loading
    val error: String? get() = seekState.error
    val requestedPageIndex: Int? get() = seekState.requestedIndex
    val requiresFolderReselection: Boolean get() = seekState.requiresFolderReselection

    fun open(uri: Uri, format: String, restoredIndex: Int, spreads: Boolean, rtl: Boolean) {
        if (disposed) return
        detach()
        this.uri = uri
        this.format = format
        this.spreads = spreads
        this.rtl = rtl
        requestedIndex = restoredIndex
        destinationRequests.clear()
        seekState = SeekState(requestedIndex = restoredIndex, loading = true)
        val session = epoch
        val token = ++request
        val pendingCleanup = cleanup
        worker = scope.launch {
            var unpublished: OpenArchive? = null
            try {
                pendingCleanup?.join()
                coroutineContext.ensureActive()
                withContext(Dispatchers.IO) {
                    val job = coroutineContext[Job] ?: throw CancellationException("Archive open was cancelled.")
                    val cancellationCheck = { job.ensureActive() }
                    val cancellationRegistration = archiveCancellationRegistrar(job)
                    ioMutex.withLock {
                        val detectedFormat = detectArchiveFormat(
                            context, uri, format, cancellationCheck, cancellationRegistration,
                        )
                        unpublished = if (detectedFormat == ArchiveFormat.RAR) {
                            RarArchiveReader(context).open(uri, cancellationCheck, cancellationRegistration)
                        } else {
                            ZipArchiveReader(context).open(uri, cancellationCheck, cancellationRegistration)
                        }
                    }
                }
                if (!valid(session, token)) return@launch
                val opened = unpublished ?: throw IOException("Could not open comic.")
                archive = opened
                unpublished = null
                totalPages = opened.pages.size
                val index = clampRestoredPage(restoredIndex, totalPages)
                requestedIndex = index
                seekState = seekState.requesting(index)
                prepare(opened, TurnNavigation.spreadStart(index, this@ComicReaderSession.spreads), session, token, uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (valid(session, token)) {
                    seekState = seekState.failed(
                        failure.message ?: "Could not open comic.",
                        requiresFolderReselection(failure),
                    )
                }
            } finally {
                unpublished?.let { closeResource(it) }
            }
        }
    }

    /** A prefetched target is published immediately, in the same frame as gesture settling. */
    fun navigate(index: Int) = navigate(index, force = false)

    private fun navigate(index: Int, force: Boolean) {
        val opened = archive ?: return
        val bookUri = uri ?: return
        if (disposed || index !in 0 until totalPages) return
        val start = TurnNavigation.spreadStart(index, spreads)
        if (force) destinationRequests.force(start)
        else if (!destinationRequests.begin(start, current?.startIndex)) {
            if (!destinationRequests.isPending(start) && current?.startIndex == start) {
                requestedIndex = start
                seekState = seekState.returnToRenderedPage().rendered(start)
                publishNeighbors(start)
            }
            return
        }
        requestedIndex = index
        val session = epoch
        val token = ++request
        val oldWorker = worker
        oldWorker?.cancel()
        val hit = cache[start]
        if (hit != null) {
            current = hit.unit
            retainWindow(start)
            publishNeighbors(start)
            seekState = seekState.rendered(start)
            onProgress(bookUri, start, totalPages)
        } else {
            previous = null
            next = null
            seekState = seekState.requesting(start)
        }

        worker = scope.launch {
            try {
                oldWorker?.join()
                if (!valid(session, token)) return@launch
                prepare(opened, start, session, token, bookUri, committed = hit != null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (valid(session, token)) {
                    destinationRequests.complete(start)
                    seekState = seekState.failed(
                        failure.message ?: "Could not display page.",
                        requiresFolderReselection(failure),
                    )
                }
            }
        }
    }

    fun retry() {
        if (disposed) return
        val target = seekState.requestedIndex ?: requestedIndex
        if (archive == null) {
            val activeUri = uri ?: return
            open(activeUri, format, target, spreads, rtl)
        } else {
            navigate(target, force = true)
        }
    }

    fun returnToCurrentPage() {
        val rendered = current ?: return
        request++
        worker?.cancel()
        worker = null
        destinationRequests.clear()
        requestedIndex = rendered.startIndex
        seekState = seekState.returnToRenderedPage().rendered(rendered.startIndex)
        publishNeighbors(rendered.startIndex)
    }

    /** Loads a small page-strip image from the active archive without touching the turn cache. */
    suspend fun thumbnail(index: Int): Bitmap? = loadThumbnailOrNull {
        if (disposed) return@loadThumbnailOrNull null
        thumbnailCache.get(index)?.let { return@loadThumbnailOrNull it }
        val opened = archive ?: return@loadThumbnailOrNull null
        if (index !in opened.pages.indices) return@loadThumbnailOrNull null
        val session = epoch
        withContext(Dispatchers.IO) {
            val job = coroutineContext[Job] ?: throw CancellationException("Thumbnail load was cancelled.")
            val cancellationCheck = { job.ensureActive() }
            val cancellationRegistration = archiveCancellationRegistrar(job)
            ioMutex.withLock {
                cancellationCheck()
                if (disposed || epoch != session || archive !== opened) return@withLock null
                val bytes = opened.readPage(opened.pages[index], cancellationCheck, cancellationRegistration)
                cancellationCheck()
                decode(bytes, 224).also { thumbnailCache.put(index, it) }
            }
        }
    }

    fun reconfigure(spreads: Boolean, rtl: Boolean) {
        if (disposed || (this.spreads == spreads && this.rtl == rtl)) return
        val logicalPage = current?.startIndex ?: requestedIndex
        this.spreads = spreads
        this.rtl = rtl
        cache.clear()
        thumbnailCache.evictAll()
        previous = null
        next = null
        if (archive != null) navigate(logicalPage, force = true)
    }

    fun close() {
        if (disposed) return
        detach()
    }

    fun dispose() {
        if (disposed) return
        detach()
        disposed = true
        scope.cancel()
    }

    private fun detach() {
        epoch++
        request++
        val oldWorker = worker
        worker = null
        oldWorker?.cancel()
        val oldArchive = archive
        archive = null
        val earlierCleanup = cleanup
        // This standalone cleanup survives disposal of the UI/session scope.
        cleanup = CoroutineScope(Dispatchers.IO).launch {
            earlierCleanup?.join()
            oldWorker?.cancelAndJoin()
            oldArchive?.let { closeResource(it) }
        }
        uri = null
        cache.clear()
        thumbnailCache.evictAll()
        current = null
        previous = null
        next = null
        totalPages = 0
        destinationRequests.clear()
        seekState = SeekState()
    }

    private fun valid(session: Long, token: Long) = !disposed && epoch == session && request == token

    private suspend fun prepare(
        opened: OpenArchive, start: Int, session: Long, token: Long, bookUri: Uri, committed: Boolean = false
    ) {
        val (fullDimension, neighborDimension) = dimensions(opened.isSolid)
        retainWindow(start)
        val solidPlan = if (opened.isSolid) {
            solidReadPlan(
                start = start,
                previous = TurnNavigation.neighbor(start, totalPages, spreads, forward = false),
                next = TurnNavigation.neighbor(start, totalPages, spreads, forward = true),
                cachedIndices = cache.keys,
            )
        } else null
        // An old full-size current page becomes a small neighbor before another page is decoded.
        val retainedNeighborIndices = if (opened.isSolid) {
            listOfNotNull(
                TurnNavigation.neighbor(start, totalPages, spreads, forward = false),
                TurnNavigation.neighbor(start, totalPages, spreads, forward = true),
            )
        } else wantedNeighbors(start)
        for (index in retainedNeighborIndices) {
            val retained = cache[index] ?: continue
            if (retained.dimension > neighborDimension) {
                val reduced = withContext(Dispatchers.IO) { shrink(retained.unit, neighborDimension) }
                if (!valid(session, token)) return
                cache[index] = Cached(reduced, neighborDimension)
                publishNeighbors(start)
            }
        }
        publishNeighbors(start)

        if (solidPlan != null) {
            executeSolidReadPlan(solidPlan) { index ->
                if (index == start) {
                    if (!committed) {
                        val unit = readUnit(opened, start, fullDimension)
                        if (!valid(session, token)) return@executeSolidReadPlan false
                        cache[start] = Cached(unit, fullDimension)
                        current = unit
                        seekState = seekState.rendered(start)
                        onProgress(bookUri, start, totalPages)
                    }
                } else if (cache[index] == null) {
                    try {
                        val unit = readUnit(opened, index, fullDimension)
                        if (!valid(session, token)) return@executeSolidReadPlan false
                        cache[index] = Cached(unit, fullDimension)
                        publishNeighbors(start)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (valid(session, token)) seekState = seekState.failed(
                            "Could not prepare neighboring page: ${failure.message ?: "archive error"}",
                            requiresFolderReselection(failure),
                        )
                    }
                }
                true
            }
        } else {
            if (!committed) {
                val unit = readUnit(opened, start, neighborDimension)
                if (!valid(session, token)) return
                cache[start] = Cached(unit, neighborDimension)
                current = unit
                seekState = seekState.rendered(start)
                onProgress(bookUri, start, totalPages)
            }
            for (index in wantedNeighbors(start)) {
                if (cache[index] == null) {
                    try {
                        val unit = readUnit(opened, index, neighborDimension)
                        if (!valid(session, token)) return
                        cache[index] = Cached(unit, neighborDimension)
                        publishNeighbors(start)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (valid(session, token)) seekState = seekState.failed(
                            "Could not prepare neighboring page: ${failure.message ?: "archive error"}",
                            requiresFolderReselection(failure),
                        )
                    }
                }
            }
        }
        val existing = cache[start] ?: return
        if (!opened.isSolid && existing.dimension < fullDimension) {
            val full = readUnit(opened, start, fullDimension)
            if (!valid(session, token)) return
            cache[start] = Cached(full, fullDimension)
            current = full
        }
        if (valid(session, token)) {
            if (seekState.renderedIndex != start) seekState = seekState.rendered(start)
            destinationRequests.complete(start)
        }
    }

    private fun wantedNeighbors(start: Int): List<Int> = if (archive?.isSolid == true) {
        listOfNotNull(TurnNavigation.neighbor(start, totalPages, spreads, forward = true))
    } else {
        listOfNotNull(
            TurnNavigation.neighbor(start, totalPages, spreads, forward = true),
            TurnNavigation.neighbor(start, totalPages, spreads, forward = false),
        )
    }

    private fun retainWindow(start: Int) {
        val wanted = if (archive?.isSolid == true) {
            solidReadPlan(
                start = start,
                previous = TurnNavigation.neighbor(start, totalPages, spreads, forward = false),
                next = TurnNavigation.neighbor(start, totalPages, spreads, forward = true),
                cachedIndices = cache.keys,
            ).retainedIndices
        } else (wantedNeighbors(start) + start).toSet()
        cache.keys.retainAll(wanted)
    }

    private fun publishNeighbors(start: Int) {
        previous = TurnNavigation.neighbor(start, totalPages, spreads, false)?.let { cache[it]?.unit }
        next = TurnNavigation.neighbor(start, totalPages, spreads, true)?.let { cache[it]?.unit }
    }

    private suspend fun readUnit(opened: OpenArchive, start: Int, dimension: Int): ReaderUnit {
        val indices = if (spreads && start > 0) listOf(start, start + 1).filter { it < opened.pages.size }
        else listOf(start)
        return withContext(Dispatchers.IO) {
            val job = coroutineContext[Job] ?: throw CancellationException("Page read was cancelled.")
            val cancellationCheck = { job.ensureActive() }
            val cancellationRegistration = archiveCancellationRegistrar(job)
            ioMutex.withLock {
                val decoded = indices.map { index ->
                    cancellationCheck()
                    index to decode(opened.readPage(opened.pages[index], cancellationCheck, cancellationRegistration), dimension)
                }
                ReaderUnit(start, if (rtl) decoded.reversed() else decoded)
            }
        }
    }

    private fun decode(bytes: ByteArray, dimension: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!ArchiveLimits.isImageSizeAllowed(bounds.outWidth, bounds.outHeight))
            throw IOException("Unsupported or oversized image page.")
        var sample = 1
        while (bounds.outWidth / sample > dimension || bounds.outHeight / sample > dimension) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
        }) ?: throw IOException("Could not decode page image.")
    }

    private fun shrink(unit: ReaderUnit, dimension: Int): ReaderUnit = ReaderUnit(unit.startIndex,
        unit.pages.map { (index, bitmap) ->
            val scale = min(1.0, dimension.toDouble() / max(bitmap.width, bitmap.height))
            index to if (scale >= 1.0) bitmap else Bitmap.createScaledBitmap(bitmap,
                max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt()), true)
        })

    private suspend fun closeResource(opened: OpenArchive) {
        withContext(NonCancellable + Dispatchers.IO) {
            ioMutex.withLock {
                try { opened.close() }
                catch (failure: Exception) { Log.w("ComicReaderSession", "Archive cleanup failed", failure) }
            }
        }
    }
}

internal suspend fun <T> loadThumbnailOrNull(load: suspend () -> T?): T? = try {
    load()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

internal data class SolidReadPlan(val reads: List<Int>, val retainedIndices: Set<Int>)

internal suspend fun executeSolidReadPlan(plan: SolidReadPlan, read: suspend (Int) -> Boolean) {
    for (index in plan.reads) if (!read(index)) return
}

internal fun solidReadPlan(start: Int, previous: Int?, next: Int?, cachedIndices: Set<Int>): SolidReadPlan {
    val reads = buildList {
        if (start !in cachedIndices) add(start)
        if (next != null && next !in cachedIndices) add(next)
    }
    val retained = buildSet {
        add(start)
        next?.let(::add)
        if (previous != null && previous in cachedIndices) add(previous)
    }
    return SolidReadPlan(reads, retained)
}
