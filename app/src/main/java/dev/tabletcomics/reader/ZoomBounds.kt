package dev.tabletcomics.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

private const val MAX_ZOOM_SCALE = 5f
private const val PAN_SPEED_GAIN = 1.5f

internal object ZoomBounds {
    fun fittedContentRect(viewport: Size, pages: List<Size>): Rect {
        if (viewport.width <= 0f || viewport.height <= 0f || pages.isEmpty()) return Rect.Zero
        val slotWidth = viewport.width / pages.size
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        pages.forEachIndexed { index, page ->
            if (page.width <= 0f || page.height <= 0f) return@forEachIndexed
            val fit = min(slotWidth / page.width, viewport.height / page.height)
            val width = page.width * fit
            val height = page.height * fit
            val pageLeft = slotWidth * index + (slotWidth - width) / 2f
            val pageTop = (viewport.height - height) / 2f
            left = min(left, pageLeft)
            top = min(top, pageTop)
            right = max(right, pageLeft + width)
            bottom = max(bottom, pageTop + height)
        }
        return if (left.isFinite()) Rect(left, top, right, bottom) else Rect.Zero
    }

    fun fitPageToWidth(viewport: Size, pages: List<Size>, pageIndex: Int): ZoomTransform {
        if (viewport.width <= 0f || viewport.height <= 0f || pageIndex !in pages.indices) {
            return ZoomTransform.Identity
        }
        val page = pages[pageIndex]
        if (page.width <= 0f || page.height <= 0f) return ZoomTransform.Identity
        val slotWidth = viewport.width / pages.size
        val fit = min(slotWidth / page.width, viewport.height / page.height)
        val pageWidth = page.width * fit
        val pageHeight = page.height * fit
        val pageLeft = slotWidth * pageIndex + (slotWidth - pageWidth) / 2f
        val pageTop = (viewport.height - pageHeight) / 2f
        val pageRect = Rect(pageLeft, pageTop, pageLeft + pageWidth, pageTop + pageHeight)
        val scale = (viewport.width / pageWidth).coerceIn(1f, MAX_ZOOM_SCALE)
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val pan = Offset(
            (center.x - pageRect.center.x) * scale,
            (center.y - pageRect.center.y) * scale,
        )
        return ZoomTransform(scale, clampPan(viewport, fittedContentRect(viewport, pages), scale, pan))
    }

    fun clampPan(viewport: Size, content: Rect, scale: Float, pan: Offset): Offset {
        if (viewport.width <= 0f || viewport.height <= 0f || content.isEmpty || scale <= 0f) return Offset.Zero
        return Offset(
            clampAxis(content.left, content.right, viewport.width, scale, pan.x),
            clampAxis(content.top, content.bottom, viewport.height, scale, pan.y),
        )
    }

    /** Applies a drag delta measured in the unscaled viewport coordinate space. */
    fun panByViewportDelta(viewport: Size, content: Rect, scale: Float, pan: Offset, delta: Offset): Offset =
        clampPan(viewport, content, scale, pan + delta)

    /** Converts a finger-space turn offset into the scaled Canvas coordinate space. */
    fun canvasOffsetForViewportDrag(viewportOffset: Float, scale: Float): Float =
        if (scale.isFinite() && scale > 0f) viewportOffset / scale else 0f

    fun canvasTurnWidth(viewportWidth: Float, scale: Float): Float =
        canvasOffsetForViewportDrag(viewportWidth, scale)

    fun pageTurnNeighborOffset(viewportDrag: Float, viewportWidth: Float, scale: Float): Float {
        val canvasDrag = canvasOffsetForViewportDrag(viewportDrag, scale)
        return canvasDrag - sign(canvasDrag) * canvasTurnWidth(viewportWidth, scale)
    }

    /** Lets an outward horizontal drag turn a page once the zoomed content reaches that edge. */
    fun canTurnAtHorizontalEdge(
        viewport: Size,
        content: Rect,
        scale: Float,
        pan: Offset,
        dragDeltaX: Float,
        tolerance: Float = 1f,
    ): Boolean {
        if (viewport.width <= 0f || content.isEmpty || scale <= 1.01f || !dragDeltaX.isFinite() || dragDeltaX == 0f) {
            return false
        }
        val (minPan, maxPan) = axisPanLimits(content.left, content.right, viewport.width, scale)
        if (maxPan - minPan <= tolerance * 2f) return true
        val boundedPan = pan.x.coerceIn(minPan, maxPan)
        return if (dragDeltaX < 0f) boundedPan <= minPan + tolerance else boundedPan >= maxPan - tolerance
    }

    private fun clampAxis(minContent: Float, maxContent: Float, viewport: Float, scale: Float, pan: Float): Float {
        val (minPan, maxPan) = axisPanLimits(minContent, maxContent, viewport, scale)
        return pan.coerceIn(minPan, maxPan)
    }

    private fun axisPanLimits(minContent: Float, maxContent: Float, viewport: Float, scale: Float): Pair<Float, Float> {
        val center = viewport / 2f
        val transformedMin = center + (minContent - center) * scale
        val transformedMax = center + (maxContent - center) * scale
        if (transformedMax - transformedMin <= viewport) {
            val centeredPan = center - (transformedMin + transformedMax) / 2f
            return centeredPan to centeredPan
        }
        return (viewport - transformedMax) to -transformedMin
    }
}

internal data class ZoomTransform(val scale: Float = 1f, val pan: Offset = Offset.Zero) {
    fun transformBy(factor: Float, translation: Offset, viewport: Size, content: Rect): ZoomTransform {
        val nextScale = (scale * factor).coerceIn(1f, MAX_ZOOM_SCALE)
        val nextPan = if (nextScale > 1.01f) {
            ZoomBounds.panByViewportDelta(viewport, content, nextScale, pan, translation * PAN_SPEED_GAIN)
        } else Offset.Zero
        return copy(scale = nextScale, pan = nextPan)
    }

    fun reset(): ZoomTransform = Identity

    companion object {
        val Identity = ZoomTransform()
    }
}
