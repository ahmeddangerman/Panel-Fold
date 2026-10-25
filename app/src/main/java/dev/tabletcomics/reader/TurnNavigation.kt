package dev.tabletcomics.reader

import kotlin.math.abs

/** Logical page navigation. Reading direction is applied by the gesture/UI layer. */
object TurnNavigation {
    private const val FLICK_SPEED_DP_PER_SECOND = 700f

    /** Keeps the touch-slop origin fixed so reversing a drag stays continuous. */
    fun offsetAfterTouchSlop(movement: Float, origin: Float): Float = movement - origin

    /** Index zero is the cover; subsequent landscape spreads start at 1, 3, 5, ... */
    fun spreadStart(index: Int, spreads: Boolean): Int {
        val safeIndex = index.coerceAtLeast(0)
        return if (!spreads || safeIndex == 0 || safeIndex % 2 == 1) safeIndex else safeIndex - 1
    }

    /** Returns the neighboring logical page/spread, or null at a book boundary. */
    fun neighbor(index: Int, pageCount: Int, spreads: Boolean, forward: Boolean): Int? {
        if (pageCount <= 0 || index !in 0 until pageCount) return null
        val start = spreadStart(index, spreads)
        val candidate = when {
            !spreads -> start + if (forward) 1 else -1
            forward -> if (start == 0) 1 else start + 2
            start == 1 -> 0
            else -> start - 2
        }
        return candidate.takeIf { it in 0 until pageCount }
    }

    /**
     * Offset and width must share units; velocity is signed dp/second. A deliberate
     * flick wins over distance, including an opposing flick that reverses a long drag.
     */
    fun shouldComplete(offset: Float, velocity: Float, width: Float): Boolean {
        if (!offset.isFinite() || !velocity.isFinite() || !width.isFinite() || width <= 0f || offset == 0f) {
            return false
        }
        if (abs(velocity) >= FLICK_SPEED_DP_PER_SECOND) {
            return (offset > 0f) == (velocity > 0f)
        }
        return abs(offset) >= width / 2f
    }
}
