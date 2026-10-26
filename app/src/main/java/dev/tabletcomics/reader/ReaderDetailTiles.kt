package dev.tabletcomics.reader

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlin.math.min
import kotlin.math.ceil

/** A page's desired viewport. Lower priorities represent the page currently being read. */
internal data class DetailPageDemand(
    val source: FullResolutionPage,
    val geometry: PageGeometry,
    val viewport: Size,
    val zoom: ZoomTransform,
    val priority: Int = 0,
)

internal data class DetailTile(
    val pageIndex: Int,
    val sourcePath: String,
    val region: PixelRegion,
    val sampleSize: Int,
    val bitmap: Bitmap,
)

internal data class DetailTileKey(val sourcePath: String, val pageIndex: Int, val region: PixelRegion, val sampleSize: Int)
internal data class DetailTileRequest(val key: DetailTileKey, val source: FullResolutionPage) {
    val estimatedBytes: Long get() =
        ((key.region.width.toLong() + key.sampleSize - 1) / key.sampleSize) *
            ((key.region.height.toLong() + key.sampleSize - 1) / key.sampleSize) * 4
}
internal data class ReaderDetailPlan(val requests: List<DetailTileRequest>, val sourcePaths: Set<String>)

/** Worst-case grid alignment makes the safety sample independent of a page's pan position. */
internal fun visibleDetailBytes(demand: DetailPageDemand, sample: Int): Long {
    val source = demand.source
    val page = demand.geometry
    if (page.destination.width <= 0f || page.destination.height <= 0f || demand.zoom.scale <= 0f) return 0
    val edge = 512.0 * sample
    val visibleWidth = min(source.width.toDouble(), demand.viewport.width.toDouble() / (page.destination.width * demand.zoom.scale) * page.crop.width * source.width)
    val visibleHeight = min(source.height.toDouble(), demand.viewport.height.toDouble() / (page.destination.height * demand.zoom.scale) * page.crop.height * source.height)
    val columns = min(ceil(source.width / edge), ceil(visibleWidth / edge) + 1).toLong()
    val rows = min(ceil(source.height / edge), ceil(visibleHeight / edge) + 1).toLong()
    return columns * rows * 512L * 512 * 4
}

/** Decode at least one source pixel per screen pixel whenever the source has that detail. */
internal fun displayDetailSample(source: Size, page: PageGeometry, zoomScale: Float): Int {
    if (page.destination.width <= 0f || page.destination.height <= 0f || zoomScale <= 0f) return 1
    val ratio = min(
        source.width * page.crop.width / (page.destination.width * zoomScale),
        source.height * page.crop.height / (page.destination.height * zoomScale),
    )
    var sample = 1
    while (sample < (1 shl 22) && sample * 2f <= ratio) sample *= 2
    return sample
}

/** Tile boundaries belong to the source, so a one-pixel pan reuses the same decoded bitmaps. */
internal fun fixedDetailGrid(region: PixelRegion, sourceWidth: Int, sourceHeight: Int, sample: Int): List<PixelRegion> {
    val edge = (512L * sample).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    if (edge <= 0 || region.width <= 0 || region.height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return emptyList()
    val left = region.left.coerceIn(0, sourceWidth) / edge * edge
    val top = region.top.coerceIn(0, sourceHeight) / edge * edge
    val right = region.right.coerceIn(0, sourceWidth)
    val bottom = region.bottom.coerceIn(0, sourceHeight)
    return buildList {
        var y = top
        while (y < bottom) {
            val endY = min(sourceHeight.toLong(), y.toLong() + edge).toInt()
            var x = left
            while (x < right) {
                val endX = min(sourceWidth.toLong(), x.toLong() + edge).toInt()
                add(PixelRegion(x, y, endX, endY))
                x = endX
            }
            y = endY
        }
    }
}

internal fun readerDetailPlan(demands: List<DetailPageDemand>, maxBytes: Long): ReaderDetailPlan {
    val sourcePaths = demands.map { it.source.sourceFile.absolutePath }.toSet()
    data class PlannedPage(val demand: DetailPageDemand, var sample: Int, val visible: PixelRegion, val buffer: PixelRegion?) {
        fun requests(region: PixelRegion): List<DetailTileRequest> = fixedDetailGrid(
            region, demand.source.width, demand.source.height, sample,
        ).map { DetailTileRequest(DetailTileKey(demand.source.sourceFile.absolutePath, demand.source.pageIndex, it, sample), demand.source) }
    }
    val pages = demands.mapNotNull { demand ->
        val source = Size(demand.source.width.toFloat(), demand.source.height.toFloat())
        val visible = demand.geometry.visibleSource(ReaderGeometry.visibleViewport(demand.viewport, demand.zoom), source)
            ?: return@mapNotNull null
        PlannedPage(demand, displayDetailSample(source, demand.geometry, demand.zoom.scale), visible,
            demand.geometry.visibleSource(ReaderGeometry.visibleViewport(demand.viewport, demand.zoom, .5f), source))
    }
    // Very large screens or unusual source ratios must still fit the memory ceiling.
    for (unitPages in pages.groupBy { it.demand.priority }.values) {
        while (unitPages.sumOf { page -> visibleDetailBytes(page.demand, page.sample) } > maxBytes) {
            val largest = unitPages.filter { it.sample < (1 shl 22) }
                .maxByOrNull { page -> visibleDetailBytes(page.demand, page.sample) } ?: break
            largest.sample *= 2
        }
    }
    val visible = pages.sortedBy { it.demand.priority }.flatMap { page -> page.requests(page.visible) }
    val buffer = pages.sortedBy { it.demand.priority }.flatMap { page -> page.buffer?.let(page::requests).orEmpty() }
    val selected = linkedMapOf<DetailTileKey, DetailTileRequest>()
    var selectedBytes = 0L
    for (request in visible + buffer) {
        if (selected.containsKey(request.key)) continue
        if (request.estimatedBytes <= maxBytes - selectedBytes) {
            selected[request.key] = request
            selectedBytes += request.estimatedBytes
        }
    }
    return ReaderDetailPlan(selected.values.toList(), sourcePaths)
}

/** Values already published to Canvas are dereferenced on eviction, never explicitly recycled. */
internal class DetailMemoryCache<K, V>(private val maxBytes: Long, private val bytes: (V) -> Long) {
    private val entries = linkedMapOf<K, V>()
    var sizeBytes: Long = 0
        private set
    fun contains(key: K): Boolean = key in entries
    fun snapshot(): List<V> = entries.values.toList()
    fun retain(predicate: (K) -> Boolean) {
        val removed = entries.keys.filterNot(predicate)
        removed.forEach { key -> entries.remove(key)?.let { sizeBytes -= bytes(it) } }
    }
    fun put(key: K, value: V, wanted: Set<K>) {
        entries.remove(key)?.let { sizeBytes -= bytes(it) }
        entries[key] = value
        sizeBytes += bytes(value)
        while (sizeBytes > maxBytes && entries.isNotEmpty()) {
            val evicted = entries.keys.firstOrNull { it !in wanted } ?: entries.keys.first()
            entries.remove(evicted)?.let { sizeBytes -= bytes(it) }
        }
    }
}

/**
 * Pan updates replace a queued demand, never the running decode. One tile is published before
 * the worker reads the latest demand; continuous finger motion therefore cannot starve detail.
 */
@Composable
internal fun rememberReaderDetailTiles(
    bookKey: String,
    demands: List<DetailPageDemand>,
    onDecodeRegions: suspend (FullResolutionPage, List<Rect>, Int) -> List<Bitmap?>,
    maxBytes: Long = min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4).coerceAtLeast(4L * 1024 * 1024),
): List<DetailTile> {
    val plan = remember(demands, maxBytes) { readerDetailPlan(demands, maxBytes) }
    val updates = remember(bookKey, maxBytes) { Channel<ReaderDetailPlan>(Channel.CONFLATED) }
    val decode = rememberUpdatedState(onDecodeRegions)
    var published by remember(bookKey, maxBytes) { mutableStateOf<List<DetailTile>>(emptyList()) }
    SideEffect { updates.trySend(plan) }
    LaunchedEffect(bookKey, maxBytes) {
        val cache = DetailMemoryCache<DetailTileKey, DetailTile>(maxBytes) { it.bitmap.allocationByteCount.toLong() }
        val failed = linkedSetOf<DetailTileKey>()
        val attempted = linkedSetOf<DetailTileKey>()
        var active = updates.receive()
        while (true) {
            while (true) {
                val newest = updates.tryReceive().getOrNull() ?: break
                if (newest != active) { attempted.clear(); failed.clear() }
                active = newest
            }
            cache.retain { it.sourcePath in active.sourcePaths }
            failed.removeAll { it.sourcePath !in active.sourcePaths }
            val request = active.requests.firstOrNull { !cache.contains(it.key) && it.key !in failed && it.key !in attempted }
            if (request == null) {
                val retained = cache.snapshot()
                if (published != retained) published = retained
                val newest = updates.receive()
                if (newest != active) { attempted.clear(); failed.clear() }
                active = newest
                continue
            }
            attempted += request.key
            val bitmaps = try {
                decode.value(request.source, listOf(request.key.region.let { Rect(it.left, it.top, it.right, it.bottom) }), request.key.sampleSize)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OutOfMemoryError) {
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            ensureActive()
            // Gesture updates arriving during this decode own the eviction priorities too.
            while (true) {
                val newest = updates.tryReceive().getOrNull() ?: break
                if (newest != active) { attempted.clear(); failed.clear() }
                active = newest
            }
            val bitmap = bitmaps.firstOrNull()
            if (bitmap == null) {
                failed += request.key
            } else if (request.key.sourcePath in active.sourcePaths) {
                cache.put(request.key, DetailTile(request.key.pageIndex, request.key.sourcePath, request.key.region,
                    request.key.sampleSize, bitmap), active.requests.mapTo(hashSetOf()) { it.key })
                published = cache.snapshot()
            }
        }
    }
    return published
}
