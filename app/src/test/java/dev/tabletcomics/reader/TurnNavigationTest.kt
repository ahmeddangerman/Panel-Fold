package dev.tabletcomics.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnNavigationTest {
    @Test fun reversingAcrossTheTouchSlopOriginDoesNotJumpThePreviewOffset() {
        val origin = -12f
        assertEquals(0f, TurnNavigation.offsetAfterTouchSlop(-12f, origin), 0f)
        assertEquals(0.1f, TurnNavigation.offsetAfterTouchSlop(-11.9f, origin), 0.001f)
        assertEquals(-0.1f, TurnNavigation.offsetAfterTouchSlop(-12.1f, origin), 0.001f)
        assertEquals(12f, TurnNavigation.offsetAfterTouchSlop(0f, origin), 0f)
    }

    @Test fun singlePageTurnsReachBothBoundariesWithoutWrapping() {
        assertNull(TurnNavigation.neighbor(0, 3, spreads = false, forward = false))
        assertEquals(1, TurnNavigation.neighbor(0, 3, spreads = false, forward = true))
        assertEquals(1, TurnNavigation.neighbor(2, 3, spreads = false, forward = false))
        assertNull(TurnNavigation.neighbor(2, 3, spreads = false, forward = true))
    }

    @Test fun coverStaysAloneAndLeadsIntoTheFirstPair() {
        assertEquals(0, TurnNavigation.spreadStart(0, spreads = true))
        assertEquals(1, TurnNavigation.neighbor(0, 6, spreads = true, forward = true))
        assertEquals(0, TurnNavigation.neighbor(1, 6, spreads = true, forward = false))
        assertEquals(0, TurnNavigation.neighbor(2, 6, spreads = true, forward = false))
        assertNull(TurnNavigation.neighbor(0, 6, spreads = true, forward = false))
    }

    @Test fun eitherPageOfASpreadMapsToTheSameNavigationUnit() {
        assertEquals(3, TurnNavigation.spreadStart(4, spreads = true))
        assertEquals(4, TurnNavigation.spreadStart(4, spreads = false))
        assertEquals(5, TurnNavigation.neighbor(3, 7, spreads = true, forward = true))
        assertEquals(5, TurnNavigation.neighbor(4, 7, spreads = true, forward = true))
        assertEquals(1, TurnNavigation.neighbor(4, 7, spreads = true, forward = false))
    }

    @Test fun unmatchedLastPageIsItsOwnFinalSpread() {
        assertEquals(5, TurnNavigation.neighbor(3, 6, spreads = true, forward = true))
        assertEquals(3, TurnNavigation.neighbor(5, 6, spreads = true, forward = false))
        assertNull(TurnNavigation.neighbor(5, 6, spreads = true, forward = true))
        assertNull(TurnNavigation.neighbor(5, 7, spreads = true, forward = true))
        assertNull(TurnNavigation.neighbor(6, 7, spreads = true, forward = true))
    }

    @Test fun emptyOrInvalidBookPositionsHaveNoNeighbor() {
        assertNull(TurnNavigation.neighbor(0, 0, spreads = true, forward = true))
        assertNull(TurnNavigation.neighbor(-1, 4, spreads = false, forward = true))
        assertNull(TurnNavigation.neighbor(4, 4, spreads = true, forward = false))
        assertNull(TurnNavigation.neighbor(0, 1, spreads = true, forward = true))
    }

    @Test fun slowShortDragReturnsButHalfwayDragCompletesInBothDirections() {
        assertFalse(TurnNavigation.shouldComplete(149f, 0f, 300f))
        assertFalse(TurnNavigation.shouldComplete(-149f, -300f, 300f))
        assertTrue(TurnNavigation.shouldComplete(150f, 0f, 300f))
        assertTrue(TurnNavigation.shouldComplete(-150f, 0f, 300f))
    }

    @Test fun fastFlickCompletesAShortDragOnlyInTheSameDirection() {
        assertTrue(TurnNavigation.shouldComplete(30f, 700f, 300f))
        assertTrue(TurnNavigation.shouldComplete(-30f, -900f, 300f))
        assertFalse(TurnNavigation.shouldComplete(30f, -900f, 300f))
        assertFalse(TurnNavigation.shouldComplete(-30f, 900f, 300f))
    }

    @Test fun strongReverseFlickCancelsEvenAfterPassingHalfway() {
        assertFalse(TurnNavigation.shouldComplete(200f, -700f, 300f))
        assertFalse(TurnNavigation.shouldComplete(-200f, 700f, 300f))
        assertTrue(TurnNavigation.shouldComplete(200f, -200f, 300f))
    }

    @Test fun absentOrInvalidGestureCannotComplete() {
        assertFalse(TurnNavigation.shouldComplete(0f, 1000f, 300f))
        assertFalse(TurnNavigation.shouldComplete(100f, 0f, 0f))
        assertFalse(TurnNavigation.shouldComplete(Float.NaN, 0f, 300f))
        assertFalse(TurnNavigation.shouldComplete(100f, Float.POSITIVE_INFINITY, 300f))
    }
}
