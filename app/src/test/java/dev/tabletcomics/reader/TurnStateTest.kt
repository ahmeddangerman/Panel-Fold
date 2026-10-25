package dev.tabletcomics.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnStateTest {
    @Test
    fun releaseAtZeroClearsSnapshot() {
        val state = TurnState()
        state.capture(unit(0), previous = null, next = unit(1))

        assertNull(state.release(velocity = 900f, width = 400f, rightToLeft = false))
        assertFalse(state.hasSnapshot)
        assertEquals(0f, state.offset, 0f)
    }

    @Test
    fun boundaryGestureClearsSnapshot() {
        val state = TurnState()
        state.capture(unit(0), previous = null, next = null)
        state.dragTo(value = -100f, hasNeighbor = false)

        assertNull(state.release(velocity = 0f, width = 400f, rightToLeft = false))
        assertFalse(state.hasSnapshot)
    }

    @Test
    fun cancelledGestureReturnsAndClearsSnapshot() {
        val state = TurnState()
        state.capture(unit(0), previous = null, next = unit(1))
        state.dragTo(value = -100f, hasNeighbor = true)

        val settle = state.release(velocity = -900f, width = 400f, rightToLeft = false, cancelled = true)
        assertNotNull(settle)
        assertEquals(0f, settle!!.destination, 0f)
        assertNull(settle?.target)
        assertTrue(state.hasSnapshot)
        assertNull(state.completeSettle())
        assertFalse(state.hasSnapshot)
    }

    @Test
    fun layoutResetClearsAllTurnState() {
        val state = TurnState()
        state.capture(unit(0), previous = null, next = unit(1))
        state.dragTo(value = -80f, hasNeighbor = true)
        state.release(velocity = 0f, width = 400f, rightToLeft = false)

        state.reset()

        assertFalse(state.hasSnapshot)
        assertFalse(state.settling)
        assertEquals(0f, state.offset, 0f)
    }

    @Test
    fun completionCommitsCapturedTargetDespiteNeighborChanges() {
        val current = unit(2)
        val captured = unit(3)
        val state = TurnState()
        state.capture(current, previous = unit(1), next = captured)
        state.dragTo(value = -220f, hasNeighbor = true)
        state.release(velocity = 0f, width = 400f, rightToLeft = false)
        state.capture(unit(3), previous = unit(2), next = unit(4))

        assertSame(captured, state.completeSettle())
        assertFalse(state.hasSnapshot)
    }

    private fun unit(index: Int) = ReaderUnit(index, emptyList())
}
