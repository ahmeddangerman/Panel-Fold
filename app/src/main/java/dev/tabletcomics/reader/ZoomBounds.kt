package dev.tabletcomics.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.sign

private const val MAX_ZOOM_SCALE = 5f

internal object ZoomBounds {
    /** Preserve magnification, but start each new page at its top rather than its old pan. */
    fun pageStart(scale: Float, viewport: Size, pages: List<Size>, horizontalPan: Float = 0f): ZoomTransform =
        pageStart(scale, viewport, fittedContentRect(viewport, pages), horizontalPan)

    fun pageStart(scale: Float, viewport: Size, content: Rect, horizontalPan: Float = 0f): ZoomTransform {
        val safeScale = if (scale.isFinite()) scale.coerceIn(1f, MAX_ZOOM_SCALE) else 1f
        val topPan = (viewport.height / 2f - content.top) * safeScale - viewport.height / 2f
        return ZoomTransform(safeScale, clampPan(viewport, content, safeScale, Offset(horizontalPan, topPan)))
    }

    fun fittedContentRect(viewport: Size, pages: List<Size>): Rect = ReaderGeometry.bounds(ReaderGeometry.layout(viewport, pages))

    fun fitPageToWidth(viewport: Size, pages: List<Size>, pageIndex: Int): ZoomTransform =
        fitPageToWidth(viewport, ReaderGeometry.layout(viewport, pages), pageIndex, fittedContentRect(viewport, pages))

    fun fitPageToWidth(viewport: Size, geometry: List<PageGeometry>, pageIndex: Int, content: Rect): ZoomTransform {
        val page = geometry.getOrNull(pageIndex)?.destination ?: return ZoomTransform.Identity
        if (viewport.width <= 0f || viewport.height <= 0f || page.isEmpty) return ZoomTransform.Identity
        val scale = (viewport.width / page.width).coerceIn(1f, MAX_ZOOM_SCALE)
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val pan = (center - page.center) * scale
        return ZoomTransform(scale, clampPan(viewport, content, scale, pan))
    }

    fun clampPan(viewport: Size, content: Rect, scale: Float, pan: Offset): Offset {
        if (viewport.width <= 0f || viewport.height <= 0f || content.isEmpty || !scale.isFinite() || scale <= 0f) return Offset.Zero
        return Offset(
            clampAxis(content.left, content.right, viewport.width, scale, pan.x),
            clampAxis(content.top, content.bottom, viewport.height, scale, pan.y),
        )
    }

    fun hasOverflow(viewport: Size, content: Rect, scale: Float): Boolean =
        !content.isEmpty && (content.width * scale > viewport.width + 1f || content.height * scale > viewport.height + 1f)

    /** Applies a drag delta measured in the unscaled viewport coordinate space. */
    fun panByViewportDelta(viewport: Size, content: Rect, scale: Float, pan: Offset, delta: Offset): Offset =
        clampPan(viewport, content, scale, pan + delta)

    /** Converts a finger-space turn offset into the scaled Canvas coordinate space. */
    fun canvasOffsetForViewportDrag(viewportOffset: Float, scale: Float): Float =
        if (scale.isFinite() && scale > 0f) viewportOffset / scale else 0f

    fun canvasTurnWidth(viewportWidth: Float, scale: Float): Float = canvasOffsetForViewportDrag(viewportWidth, scale)

    fun pageTurnNeighborOffset(viewportDrag: Float, viewportWidth: Float, scale: Float): Float {
        val canvasDrag = canvasOffsetForViewportDrag(viewportDrag, scale)
        return canvasDrag - sign(canvasDrag) * canvasTurnWidth(viewportWidth, scale)
    }

    /** An outward drag turns a page at the image edge, including width/height fit at relative zoom 1. */
    fun canTurnAtHorizontalEdge(
        viewport: Size,
        content: Rect,
        scale: Float,
        pan: Offset,
        dragDeltaX: Float,
        tolerance: Float = 1f,
    ): Boolean {
        if (viewport.width <= 0f || content.isEmpty || scale <= 0f || !dragDeltaX.isFinite() || dragDeltaX == 0f) return false
        val (minPan, maxPan) = axisPanLimits(content.left, content.right, viewport.width, scale)
        if (maxPan - minPan <= tolerance * 2f) return true
        val boundedPan = pan.x.coerceIn(minPan, maxPan)
        return if (dragDeltaX < 0f) boundedPan <= minPan + tolerance else boundedPan >= maxPan - tolerance
    }

    private fun clampAxis(minContent: Float, maxContent: Float, viewport: Float, scale: Float, pan: Float): Float {
        val (minPan, maxPan) = axisPanLimits(minContent, maxContent, viewport, scale)
        return (if (pan.isFinite()) pan else 0f).coerceIn(minPan, maxPan)
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
    fun transformBy(factor: Float, translation: Offset, viewport: Size, content: Rect, panSensitivity: Float = 1.5f): ZoomTransform {
        val nextScale = if (factor.isFinite()) (scale * factor).coerceIn(1f, MAX_ZOOM_SCALE) else scale
        val gain = if (panSensitivity.isFinite()) panSensitivity.coerceIn(.25f, 4f) else 1.5f
        val nextPan = ZoomBounds.panByViewportDelta(viewport, content, nextScale, pan, translation * gain)
        return copy(scale = nextScale, pan = nextPan)
    }

    fun reset(): ZoomTransform = Identity

    companion object {
        val Identity = ZoomTransform()
    }
}
