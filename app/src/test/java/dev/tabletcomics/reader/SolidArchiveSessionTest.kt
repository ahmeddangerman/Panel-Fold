package dev.tabletcomics.reader

import dev.tabletcomics.archive.ArchiveCancellationRegistrar
import dev.tabletcomics.archive.ComicPage
import dev.tabletcomics.archive.OpenArchive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolidArchiveSessionTest {
    @Test
    fun solidAdvancePlansOneDisplaySizeDecodeForCurrentAndNext() {
        val plan = solidReadPlan(start = 4, previous = 2, next = 6, cachedIndices = setOf(2))

        assertEquals(listOf(4, 6), plan.reads)
    }

    @Test
    fun solidAdvanceDecodesCurrentOnceAndReadsNextInOrder() {
        val plan = solidReadPlan(start = 4, previous = 2, next = 6, cachedIndices = setOf(2))
        val archive = RecordingSolidArchive(8)

        runBlocking {
            executeSolidReadPlan(plan) { index ->
                archive.readPage(archive.pages[index], {}, ArchiveCancellationRegistrar.NONE)
                true
            }
        }

        assertEquals(listOf(4, 6), archive.readPositions)
        assertEquals(1, archive.readPositions.count { it == 4 })
        assertTrue("decoded previous page should stay reusable", 2 in plan.retainedIndices)
    }

    @Test
    fun solidAdvanceWithCachedCurrentOnlyReadsMissingNext() {
        val plan = solidReadPlan(start = 4, previous = 2, next = 6, cachedIndices = setOf(2, 4))

        assertEquals(listOf(6), plan.reads)
        assertTrue(2 in plan.retainedIndices)
    }

    private class RecordingSolidArchive(pageCount: Int) : OpenArchive {
        override val pages = (0 until pageCount).map { ComicPage("$it.png", it) }
        override val isSolid = true
        val readPositions = mutableListOf<Int>()

        override fun readPage(
            page: ComicPage,
            cancellationCheck: () -> Unit,
            registerCancellation: ArchiveCancellationRegistrar,
        ): ByteArray {
            cancellationCheck()
            readPositions += page.position
            return byteArrayOf(page.position.toByte())
        }

        override fun close() = Unit
    }
}
