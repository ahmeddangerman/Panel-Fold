package dev.tabletcomics.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

class ThumbnailFailureTest {
    @Test fun damagedPageIsSkippedWithoutFailingTheReader() = runBlocking {
        assertNull(loadThumbnailOrNull { throw IOException("damaged preview page") })
    }

    @Test fun cancelledThumbnailRequestStillPropagatesCancellation() {
        val cancelled = CancellationException("page changed")
        try {
            runBlocking { loadThumbnailOrNull<String> { throw cancelled } }
            throw AssertionError("cancellation should propagate")
        } catch (failure: CancellationException) {
            assertSame(cancelled, failure)
        }
    }
}
