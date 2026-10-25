package dev.tabletcomics.reader

import kotlin.math.abs
import kotlin.math.sign

/** Tracks only the part of a zoomed drag that moves outward from a horizontal image edge. */
internal class ZoomEdgeSwipe {
    private var accumulated = 0f

    fun drag(deltaX: Float, canTurnAtEdge: Boolean, touchSlop: Float): Float? {
        if (!canTurnAtEdge || !deltaX.isFinite() || deltaX == 0f) {
            accumulated = 0f
            return null
        }
        accumulated += deltaX
        if (abs(accumulated) <= touchSlop) return null
        return accumulated - sign(accumulated) * touchSlop
    }
}
