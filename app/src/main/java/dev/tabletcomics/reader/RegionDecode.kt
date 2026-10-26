package dev.tabletcomics.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Android decoders support power-of-two samples. Round up to respect the caller's pixel budget. */
internal fun regionDecodeSampleSize(requested: Int): Int {
    val wanted = requested.coerceIn(1, 1 shl 30)
    var sample = 1
    while (sample < wanted) sample = sample shl 1
    return sample
}

/** One mutex outlives canceled effects, so their synchronous decoder cannot overlap a replacement. */
internal class SerializedRegionBatches {
    private val mutex = Mutex()
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        coroutineContext.ensureActive()
        block()
    }
}

internal suspend fun <R, T : Any> cancellableRegionBatch(
    regions: List<R>, decode: (R) -> T?, discard: (T) -> Unit,
): List<T?> {
    val decoded = ArrayList<T?>(regions.size)
    try {
        for (region in regions) {
            coroutineContext.ensureActive()
            decoded += decode(region)
            // BitmapRegionDecoder is synchronous: discard the just-completed image if it was canceled.
            coroutineContext.ensureActive()
        }
        return decoded
    } catch (failure: Throwable) {
        decoded.filterNotNull().forEach(discard)
        throw failure
    }
}

internal class PageRegionDecoder {
    private val batches = SerializedRegionBatches()

    suspend fun decode(
        store: SessionSourceStore, source: FullResolutionPage, regions: List<Rect>, sampleSize: Int,
    ): List<Bitmap?> = withContext(Dispatchers.IO) {
        batches.run {
            val lease = store.acquire(source) ?: return@run List(regions.size) { null }
            lease.use {
                var decoder: BitmapRegionDecoder? = null
                try {
                    coroutineContext.ensureActive()
                    decoder = BitmapRegionDecoder.newInstance(source.sourceFile.absolutePath, false)
                    val activeDecoder = decoder ?: return@use List(regions.size) { null }
                    val sample = regionDecodeSampleSize(sampleSize)
                    cancellableRegionBatch(regions, decode = { region ->
                        val safe = Rect(region)
                        if (!safe.intersect(0, 0, source.width, source.height) || safe.isEmpty) null
                        else activeDecoder.decodeRegion(safe, BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inScaled = false
                        })
                    }, discard = { it.recycle() })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: OutOfMemoryError) {
                    List(regions.size) { null }
                } catch (_: Exception) {
                    List(regions.size) { null }
                } finally {
                    decoder?.recycle()
                }
            }
        }
    }
}
