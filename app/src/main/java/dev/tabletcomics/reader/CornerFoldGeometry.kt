package dev.tabletcomics.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.sqrt

internal enum class PageCorner(val isRight: Boolean, val isBottom: Boolean) {
    TOP_LEFT(false, false), TOP_RIGHT(true, false), BOTTOM_LEFT(false, true), BOTTOM_RIGHT(true, true);

    fun position(viewport: Size): Offset = Offset(if (isRight) viewport.width else 0f, if (isBottom) viewport.height else 0f)

    companion object {
        fun fromTouch(position: Offset, viewport: Size, extent: Float): PageCorner? {
            if (!position.x.isFinite() || !position.y.isFinite() || !extent.isFinite() || extent <= 0f ||
                !viewport.width.isFinite() || !viewport.height.isFinite() || viewport.width <= 0f || viewport.height <= 0f) return null
            val right = position.x > viewport.width / 2f
            val bottom = position.y > viewport.height / 2f
            val xDistance = if (right) viewport.width - position.x else position.x
            val yDistance = if (bottom) viewport.height - position.y else position.y
            if (xDistance !in 0f..extent || yDistance !in 0f..extent) return null
            return entries.first { it.isRight == right && it.isBottom == bottom }
        }
    }
}

/** Screen-coordinate reflection, suitable for Android Matrix.setValues in row-major order. */
internal data class FoldReflection(
    val m00: Float, val m01: Float, val m10: Float, val m11: Float, val tx: Float, val ty: Float,
) {
    fun map(point: Offset): Offset = Offset(m00 * point.x + m01 * point.y + tx, m10 * point.x + m11 * point.y + ty)
    fun matrixValues(): FloatArray = floatArrayOf(m00, m01, tx, m10, m11, ty, 0f, 0f, 1f)
}

internal data class FoldSegment(val start: Offset, val end: Offset)

internal data class CornerFold(
    /** The face of the original page remains stationary; only its lifted portion is removed. */
    val frontPolygon: List<Offset>,
    val liftedSourcePolygon: List<Offset>,
    /** Draw the incoming page below everything, then the reflected paper back over the front. */
    val backPolygon: List<Offset>,
    val crease: FoldSegment?,
    val reflection: FoldReflection,
    val normal: Offset,
    val midpoint: Offset,
) {
    val revealPolygon: List<Offset> get() = liftedSourcePolygon
}

/** A paper corner follows the finger by folding over the perpendicular bisector of corner/finger. */
internal object CornerFoldGeometry {
    fun calculate(viewport: Size, corner: PageCorner, draggedCorner: Offset): CornerFold? {
        if (!viewport.width.isFinite() || !viewport.height.isFinite() || viewport.width <= 0f || viewport.height <= 0f ||
            !draggedCorner.x.isFinite() || !draggedCorner.y.isFinite()) return null
        val original = corner.position(viewport)
        val inwardX = if (corner.isRight) original.x - draggedCorner.x else draggedCorner.x - original.x
        val inwardY = if (corner.isBottom) original.y - draggedCorner.y else draggedCorner.y - original.y
        if (inwardX <= 0f && inwardY <= 0f) return null
        val dx = original.x.toDouble() - draggedCorner.x
        val dy = original.y.toDouble() - draggedCorner.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < .001) return null
        val nx = dx / length
        val ny = dy / length
        val mx = (original.x.toDouble() + draggedCorner.x) / 2.0
        val my = (original.y.toDouble() + draggedCorner.y) / 2.0
        val plane = nx * mx + ny * my
        val rectangle = listOf(Offset.Zero, Offset(viewport.width, 0f), Offset(viewport.width, viewport.height), Offset(0f, viewport.height))
        fun distance(point: Offset): Double = point.x * nx + point.y * ny - plane

        val front = validPolygon(clip(rectangle) { -distance(it) })
        val lifted = validPolygon(clip(rectangle, ::distance))
        if (lifted.isEmpty()) return null
        val reflection = FoldReflection(
            (1.0 - 2.0 * nx * nx).toFloat(), (-2.0 * nx * ny).toFloat(),
            (-2.0 * nx * ny).toFloat(), (1.0 - 2.0 * ny * ny).toFloat(),
            (2.0 * nx * plane).toFloat(), (2.0 * ny * plane).toFloat(),
        )
        if (reflection.matrixValues().any { !it.isFinite() }) return null
        var back = lifted.map(reflection::map)
        back = clip(back) { it.x.toDouble() }
        back = clip(back) { viewport.width.toDouble() - it.x }
        back = clip(back) { it.y.toDouble() }
        back = clip(back) { viewport.height.toDouble() - it.y }
        val intersections = mutableListOf<Offset>()
        rectangle.forEachIndexed { index, a ->
            val b = rectangle[(index + 1) % rectangle.size]
            val da = distance(a)
            val db = distance(b)
            if (abs(da) < .001) intersections += a
            if ((da < 0.0 && db > 0.0) || (da > 0.0 && db < 0.0)) {
                val t = (da / (da - db)).toFloat()
                intersections += a + (b - a) * t
            }
        }
        val unique = intersections.distinctBy { (it.x * 100f).toInt() to (it.y * 100f).toInt() }
        val crease = if (unique.size < 2) null else {
            var start = unique.first()
            var end = unique.last()
            var longest = 0f
            for (a in unique) for (b in unique) {
                val distanceSquared = (a - b).getDistanceSquared()
                if (distanceSquared > longest) { longest = distanceSquared; start = a; end = b }
            }
            FoldSegment(start, end)
        }
        return CornerFold(front, lifted, validPolygon(back), crease, reflection, Offset(nx.toFloat(), ny.toFloat()), Offset(mx.toFloat(), my.toFloat()))
    }

    /** Sutherland-Hodgman clipping, keeping the non-negative side of each plane. */
    private fun clip(polygon: List<Offset>, distance: (Offset) -> Double): List<Offset> {
        if (polygon.isEmpty()) return emptyList()
        val result = mutableListOf<Offset>()
        var previous = polygon.last()
        var previousDistance = distance(previous)
        for (current in polygon) {
            val currentDistance = distance(current)
            val previousInside = previousDistance >= -.00001
            val currentInside = currentDistance >= -.00001
            if (previousInside != currentInside) {
                val denominator = previousDistance - currentDistance
                if (abs(denominator) > 1e-12) {
                    val t = (previousDistance / denominator).toFloat().coerceIn(0f, 1f)
                    result += previous + (current - previous) * t
                }
            }
            if (currentInside) result += current
            previous = current
            previousDistance = currentDistance
        }
        return result
    }

    private fun validPolygon(polygon: List<Offset>): List<Offset> {
        if (polygon.size < 3 || polygon.any { !it.x.isFinite() || !it.y.isFinite() }) return emptyList()
        var area = 0.0
        polygon.forEachIndexed { index, point ->
            val next = polygon[(index + 1) % polygon.size]
            area += point.x.toDouble() * next.y - next.x.toDouble() * point.y
        }
        return if (abs(area) < .001) emptyList() else polygon
    }
}
