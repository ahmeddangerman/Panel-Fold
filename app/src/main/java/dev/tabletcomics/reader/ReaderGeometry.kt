package dev.tabletcomics.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** Display-only fitting. The original page and its archive bytes are never changed. */
enum class PageFit { PAGE, WIDTH, HEIGHT }

internal data class PageGeometry(val crop: Rect, val destination: Rect) {
    fun sourceToDisplay(region: Rect, source: Size): Rect {
        fun x(value: Float) = destination.left + (value / source.width - crop.left) / crop.width * destination.width
        fun y(value: Float) = destination.top + (value / source.height - crop.top) / crop.height * destination.height
        return Rect(x(region.left), y(region.top), x(region.right), y(region.bottom))
    }

    fun visibleSource(visible: Rect, source: Size): PixelRegion? {
        val intersection = destination.intersect(visible)
        if (intersection.isEmpty || source.width <= 0 || source.height <= 0) return null
        fun x(value: Float) = (crop.left + (value - destination.left) / destination.width * crop.width) * source.width
        fun y(value: Float) = (crop.top + (value - destination.top) / destination.height * crop.height) * source.height
        return PixelRegion(
            floor(x(intersection.left)).toInt().coerceIn(0, source.width.toInt()),
            floor(y(intersection.top)).toInt().coerceIn(0, source.height.toInt()),
            ceil(x(intersection.right)).toInt().coerceIn(0, source.width.toInt()),
            ceil(y(intersection.bottom)).toInt().coerceIn(0, source.height.toInt()),
        ).takeIf { it.width > 0 && it.height > 0 }
    }
}

internal object ReaderGeometry {
    val fullCrop = Rect(0f, 0f, 1f, 1f)

    fun layout(viewport: Size, pages: List<Size>, crops: List<Rect> = emptyList(), fit: PageFit = PageFit.PAGE): List<PageGeometry> {
        if (viewport.width <= 0 || viewport.height <= 0 || pages.isEmpty()) return emptyList()
        val slot = viewport.width / pages.size
        val base = pages.mapIndexed { index, page ->
            val crop = crops.getOrElse(index) { fullCrop }
            val width = (page.width * crop.width).coerceAtLeast(1f)
            val height = (page.height * crop.height).coerceAtLeast(1f)
            val scale = min(slot / width, viewport.height / height)
            val left = index * slot + (slot - width * scale) / 2
            val top = (viewport.height - height * scale) / 2
            PageGeometry(crop, Rect(left, top, left + width * scale, top + height * scale))
        }
        val content = bounds(base)
        val scale = when (fit) {
            PageFit.PAGE -> 1f
            PageFit.WIDTH -> viewport.width / content.width
            PageFit.HEIGHT -> viewport.height / content.height
        }
        val center = Offset(viewport.width / 2, viewport.height / 2)
        return base.map { page ->
            val rect = page.destination
            page.copy(destination = Rect(center + (rect.topLeft - center) * scale, center + (rect.bottomRight - center) * scale))
        }
    }

    fun bounds(pages: List<PageGeometry>): Rect = if (pages.isEmpty()) Rect.Zero else Rect(
        pages.minOf { it.destination.left }, pages.minOf { it.destination.top },
        pages.maxOf { it.destination.right }, pages.maxOf { it.destination.bottom },
    )

    /** Inverse of translate(pan), then scale around the viewport center. */
    fun visibleViewport(viewport: Size, zoom: ZoomTransform): Rect {
        val center = Offset(viewport.width / 2, viewport.height / 2)
        val start = center + (Offset.Zero - center - zoom.pan) / zoom.scale
        val end = center + (Offset(viewport.width, viewport.height) - center - zoom.pan) / zoom.scale
        return Rect(start, end)
    }
}

internal data class PixelRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun asFloatRect() = Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}
internal data class TileRequest(val pageSlot: Int, val region: PixelRegion)
internal data class TilePlan(val sampleSize: Int, val requests: List<TileRequest>)

internal object DetailTilePlanner {
    /** Bound allocations without truncating coverage. Interleave pages so neither half starves. */
    fun plan(regions: List<PixelRegion?>, maxTiles: Int = 20, tilePixels: Int = 512): TilePlan {
        require(maxTiles >= regions.count { it != null })
        require(tilePixels > 0)
        var sample = 1
        fun count(sampleSize: Int): Long {
            val edge = tilePixels.toLong() * sampleSize
            return regions.filterNotNull().sumOf { ((it.width + edge - 1) / edge) * ((it.height + edge - 1) / edge) }
        }
        while (count(sample) > maxTiles && sample < (1 shl 22)) sample *= 2
        val edge = (tilePixels.toLong() * sample).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val byPage = regions.mapIndexed { index, region ->
            buildList {
                if (region != null) {
                    var y = region.top
                    while (y < region.bottom) {
                        val bottom = min(region.bottom.toLong(), y.toLong() + edge).toInt()
                        var x = region.left
                        while (x < region.right) {
                            val right = min(region.right.toLong(), x.toLong() + edge).toInt()
                            add(TileRequest(index, PixelRegion(x, y, right, bottom)))
                            x = right
                        }
                        y = bottom
                    }
                }
            }
        }
        val requests = buildList {
            for (index in 0 until (byPage.maxOfOrNull { it.size } ?: 0)) byPage.forEach { it.getOrNull(index)?.let(::add) }
        }
        return TilePlan(sample, requests)
    }
}

/** A missing decoded neighbor is not evidence that the book has ended. */
internal fun isActualForwardEnd(current: ReaderUnit, totalPages: Int): Boolean =
    totalPages > 0 && (current.pages.maxOfOrNull { it.first } ?: current.startIndex) >= totalPages - 1
