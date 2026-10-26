package dev.tabletcomics.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ReaderDetailTilesTest {
    private val geometry = PageGeometry(ReaderGeometry.fullCrop, Rect(0f, 0f, 1000f, 1500f))
    private fun demand(pan: Offset = Offset.Zero, priority: Int = 0, page: Int = 0) = DetailPageDemand(
        FullResolutionPage(page, File("/pages/$page.jpg"), 4000, 6000), geometry,
        Size(1000f, 1500f), ZoomTransform(3f, pan), priority,
    )

    @Test fun displaySampleKeepsSourceDetailAtOrAboveScreenResolution() {
        assertEquals(4, displayDetailSample(Size(4000f, 6000f), geometry, 1f))
        assertEquals(2, displayDetailSample(Size(4000f, 6000f), geometry, 2f))
        assertEquals(1, displayDetailSample(Size(4000f, 6000f), geometry, 3f))
        assertEquals(1, displayDetailSample(Size(4000f, 6000f), geometry, 5f))
    }

    @Test fun aSmallPanReusesTheSameSourceTileGrid() {
        val first = fixedDetailGrid(PixelRegion(100, 100, 700, 700), 2000, 2000, 1)
        val moved = fixedDetailGrid(PixelRegion(110, 110, 710, 710), 2000, 2000, 1)

        assertEquals(first, moved)
        assertEquals(PixelRegion(0, 0, 512, 512), first.first())
        assertEquals(4, first.size)
    }

    @Test fun gridEdgesNeverDecodePastTheOriginalImage() {
        val grid = fixedDetailGrid(PixelRegion(900, 500, 1200, 900), 1100, 800, 1)

        assertTrue(grid.all { it.left >= 0 && it.top >= 0 && it.right <= 1100 && it.bottom <= 800 })
        assertEquals(1100, grid.maxOf { it.right })
        assertEquals(800, grid.maxOf { it.bottom })
    }

    @Test fun movingTheViewportCannotToggleTheSafetySample() {
        val centered = demand()
        val shifted = demand(Offset(-310f, 720f))

        assertEquals(visibleDetailBytes(centered, 1), visibleDetailBytes(shifted, 1))
        val first = readerDetailPlan(listOf(centered), 8L * 1024 * 1024)
        val second = readerDetailPlan(listOf(shifted), 8L * 1024 * 1024)
        assertEquals(first.requests.first().key.sampleSize, second.requests.first().key.sampleSize)
        assertTrue(first.requests.sumOf { it.estimatedBytes } <= 8L * 1024 * 1024)
        assertTrue(second.requests.sumOf { it.estimatedBytes } <= 8L * 1024 * 1024)
    }

    @Test fun neighborTilesUseTheSameSourceSamplingAsTheCurrentPage() {
        val plan = readerDetailPlan(listOf(demand(), demand(priority = 1, page = 1)), 48L * 1024 * 1024)

        val current = plan.requests.filter { it.key.pageIndex == 0 }
        val next = plan.requests.filter { it.key.pageIndex == 1 }
        assertTrue(current.isNotEmpty())
        assertTrue(next.isNotEmpty())
        assertEquals(current.first().key.sampleSize, next.first().key.sampleSize)
        assertTrue(plan.requests.sumOf { it.estimatedBytes } <= 48L * 1024 * 1024)
    }

    @Test fun aPrefetchedPageKeepsItsSafetySampleWhenItBecomesCurrent() {
        val budget = 8L * 1024 * 1024
        val neighbor = readerDetailPlan(listOf(demand(priority = 1)), budget)
        val current = readerDetailPlan(listOf(demand(priority = 0)), budget)

        assertTrue(neighbor.requests.isNotEmpty())
        assertEquals(neighbor.requests.first().key.sampleSize, current.requests.first().key.sampleSize)
    }

    @Test fun cacheEvictsInactiveTilesFirstAndAccountsForActualBytes() {
        data class Image(val bytes: Long)
        val cache = DetailMemoryCache<String, Image>(10) { it.bytes }
        cache.put("current", Image(4), setOf("current"))
        cache.put("old", Image(4), setOf("current"))
        val earlierFrame = cache.snapshot()
        cache.put("next", Image(4), setOf("current", "next"))

        assertTrue(cache.contains("current"))
        assertTrue(cache.contains("next"))
        assertFalse(cache.contains("old"))
        assertEquals(8L, cache.sizeBytes)
        assertEquals(2, earlierFrame.size)
        cache.retain { it == "next" }
        assertEquals(4L, cache.sizeBytes)
    }
}
