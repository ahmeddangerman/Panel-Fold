package dev.tabletcomics.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZoomEdgeSwipeTest {
    @Test
    fun pageTurnStartsOnlyAfterFingerMovesPastTouchSlopAtImageEdge() {
        val swipe = ZoomEdgeSwipe()

        assertNull(swipe.drag(deltaX = -6f, canTurnAtEdge = true, touchSlop = 10f))
        assertEquals(-3f, swipe.drag(deltaX = -7f, canTurnAtEdge = true, touchSlop = 10f)!!, 0f)
        assertEquals(-7f, swipe.drag(deltaX = -4f, canTurnAtEdge = true, touchSlop = 10f)!!, 0f)
    }

    @Test
    fun panningAwayFromEdgeResetsPendingPageSwipe() {
        val swipe = ZoomEdgeSwipe()

        assertNull(swipe.drag(deltaX = -8f, canTurnAtEdge = true, touchSlop = 10f))
        assertNull(swipe.drag(deltaX = -12f, canTurnAtEdge = false, touchSlop = 10f))
        assertNull(swipe.drag(deltaX = -4f, canTurnAtEdge = true, touchSlop = 10f))
        assertEquals(-3f, swipe.drag(deltaX = -9f, canTurnAtEdge = true, touchSlop = 10f)!!, 0f)
    }
}
