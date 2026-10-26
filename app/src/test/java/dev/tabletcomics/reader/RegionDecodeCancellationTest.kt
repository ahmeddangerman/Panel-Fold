package dev.tabletcomics.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RegionDecodeCancellationTest {
    @Test fun canceledSynchronousDecodeFinishesBeforeReplacementAndSkipsRemainingRegions() = runBlocking {
        val batches = SerializedRegionBatches()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacementRequested = CountDownLatch(1)
        val replacementDecodeStarted = CountDownLatch(1)
        val obsoleteRegions = Collections.synchronizedList(mutableListOf<Int>())
        val discarded = Collections.synchronizedList(mutableListOf<Int>())
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        fun beginDecode() {
            maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
        }
        val obsolete = launch(Dispatchers.Default) {
            batches.run {
                cancellableRegionBatch(listOf(1, 2, 3), decode = { region ->
                    beginDecode()
                    try {
                        obsoleteRegions += region
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        region
                    } finally { active.decrementAndGet() }
                }, discard = { discarded += it })
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            obsolete.cancel()
            val replacement = async(Dispatchers.Default) {
                replacementRequested.countDown()
                batches.run {
                    cancellableRegionBatch(listOf(4), decode = { region ->
                        replacementDecodeStarted.countDown()
                        beginDecode()
                        try { region } finally { active.decrementAndGet() }
                    }, discard = { discarded += it })
                }
            }
            assertTrue(replacementRequested.await(5, TimeUnit.SECONDS))
            assertFalse("replacement must wait for the canceled synchronous decode",
                replacementDecodeStarted.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            withTimeout(5_000) {
                obsolete.join()
                assertEquals(listOf(4), replacement.await())
            }
            assertTrue(obsolete.isCancelled)
            assertEquals(listOf(1), obsoleteRegions)
            assertEquals(listOf(1), discarded)
            assertEquals(1, maximum.get())
        } finally {
            release.countDown()
            obsolete.cancel()
        }
    }

    @Test fun partialBatchFailureDiscardsItsAlreadyDecodedResults() = runBlocking {
        val discarded = mutableListOf<Int>()
        val expected = IllegalArgumentException("bad region")
        try {
            cancellableRegionBatch(listOf(1, 2, 3), decode = { region ->
                if (region == 3) throw expected
                region.takeUnless { it == 2 }
            }, discard = { discarded += it })
            throw AssertionError("batch should fail")
        } catch (failure: IllegalArgumentException) {
            assertEquals(expected, failure)
        }
        assertEquals(listOf(1), discarded)
    }
}
