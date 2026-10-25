package dev.tabletcomics.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekStateTest {
    @Test
    fun seekClampsToBookBounds() {
        assertEquals(0, SeekState(draftIndex = -4).destination(totalPages = 6, spreads = false))
        assertEquals(5, SeekState(draftIndex = 40).destination(totalPages = 6, spreads = false))
    }

    @Test
    fun seekMapsToSpreadStart() {
        assertEquals(3, SeekState(draftIndex = 4).destination(totalPages = 8, spreads = true))
    }

    @Test
    fun duplicateDestinationDoesNotStartWorker() {
        val requests = DestinationRequestGate()
        assertTrue(requests.begin(destination = 5, rendered = 2))
        assertFalse(requests.begin(destination = 5, rendered = 2))
        requests.complete(5)
        assertFalse(requests.begin(destination = 5, rendered = 5))
    }

    @Test
    fun failedSeekKeepsCurrentAndOffersRecovery() {
        val failed = SeekState(renderedIndex = 2)
            .requesting(8)
            .failed("Could not read page")

        assertEquals(2, failed.renderedIndex)
        assertEquals(8, failed.requestedIndex)
        assertFalse(failed.loading)
        assertEquals("Could not read page", failed.error)

        val recovered = failed.returnToRenderedPage()
        assertEquals(2, recovered.renderedIndex)
        assertEquals(null, recovered.requestedIndex)
        assertEquals(null, recovered.error)
    }
}
