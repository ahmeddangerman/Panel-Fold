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
    fun completedTurnPublishesIncomingPageBeforeClearingOutgoingFrame() {
        val state = TurnState()
        val next = unit(1)
        state.capture(unit(0), null, next)
        state.dragTo(-300f, hasNeighbor = true)
        state.release(0f, 400f, rightToLeft = false)
        var committed: ReaderUnit? = null

        state.completeSettle { target ->
            assertTrue(state.hasSnapshot)
            assertTrue(state.settling)
            committed = target
        }

        assertSame(next, committed)
        assertFalse(state.hasSnapshot)
        assertEquals(0f, state.offset, 0f)
    }

    @Test
    fun cancelledTurnDoesNotCommitIncomingPage() {
        val state = TurnState()
        state.capture(unit(0), null, unit(1))
        state.dragTo(-300f, hasNeighbor = true)
        state.release(0f, 400f, rightToLeft = false, cancelled = true)
        var commits = 0

        assertNull(state.completeSettle { commits++ })

        assertEquals(0, commits)
        assertFalse(state.hasSnapshot)
    }

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

    @Test
    fun missingNeighborStaysMissingForTheEntireGestureInBothDirections() {
        for (rightToLeft in listOf(false, true)) {
            val state = TurnState()
            val offset = if (rightToLeft) 300f else -300f
            state.capture(unit(0), null, null)
            state.capture(unit(0), null, unit(1)) // asynchronous prefetch arrives
            assertNull(state.snapshot!!.neighbor(offset, rightToLeft))
            state.dragTo(offset, state.snapshot!!.neighbor(offset, rightToLeft) != null)
            assertEquals(0f, state.offset, 0f)
            assertNull(state.release(0f, 400f, rightToLeft))
            assertNull(state.completeSettle())
        }
    }

    @Test
    fun settledPageRemainsVisibleAndRejectsGesturesUntilParentAcknowledgesIt() {
        val state = TurnState()
        val target = unit(1)
        state.capture(unit(0), null, target)
        state.dragTo(-300f, true)
        state.release(0f, 400f, false)
        var commits = 0
        state.completeSettle {
            assertSame(target, state.committedUnit)
            commits++
        }
        state.acknowledge(0) // parent still has the outgoing page
        state.capture(unit(0), null, target)
        assertFalse(state.hasSnapshot)
        assertSame(target, state.committedUnit)
        state.completeSettle { commits++ }
        assertEquals(1, commits)
        state.acknowledge(1)
        assertNull(state.committedUnit)
        state.capture(target, unit(0), unit(2))
        assertTrue(state.hasSnapshot)
    }

    @Test
    fun explicitSeekSupersedesASettledPageWaitingForTheParent() {
        val state = TurnState()
        state.capture(unit(0), null, unit(1))
        state.dragTo(-300f, true)
        state.release(0f, 400f, false)
        state.completeSettle()
        state.acknowledge(8)
        assertNull(state.committedUnit)
    }

    private fun unit(index: Int) = ReaderUnit(index, emptyList())
}
