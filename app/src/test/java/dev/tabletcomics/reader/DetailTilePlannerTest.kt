package dev.tabletcomics.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DetailTilePlannerTest {
    @Test
    fun oversizedSpreadsAreFullyCoveredWithinOneSharedBudget() {
        val regions = listOf(PixelRegion(13, 29, 8205, 8221), PixelRegion(80, 50, 8272, 8242))
        val plan = DetailTilePlanner.plan(regions)

        assertTrue(plan.sampleSize > 1)
        assertEquals(listOf(0, 1), plan.requests.take(2).map { it.pageSlot })
        assertCoverage(regions, plan)
    }

    @Test
    fun emptyAndPartiallyVisibleSpreadsKeepTheirOriginalPageSlots() {
        val regions = listOf(null, PixelRegion(1000, 2000, 1200, 2300))
        val plan = DetailTilePlanner.plan(regions)

        assertEquals(1, plan.sampleSize)
        assertEquals(listOf(TileRequest(1, regions[1]!!)), plan.requests)
        assertTrue(DetailTilePlanner.plan(listOf(null, null)).requests.isEmpty())
    }

    @Test
    fun randomizedVisibleRegionsHaveNoGapsOverlapOrOversizedDecodedTiles() {
        val random = Random(12345)
        repeat(250) {
            val regions = List(2) {
                if (random.nextInt(5) == 0) null else {
                    val left = random.nextInt(20000)
                    val top = random.nextInt(20000)
                    PixelRegion(left, top, left + random.nextInt(1, 80000), top + random.nextInt(1, 80000))
                }
            }
            assertCoverage(regions, DetailTilePlanner.plan(regions))
        }
    }

    @Test
    fun giantSourceDimensionsDoNotOverflowTileCounting() {
        val regions = listOf(PixelRegion(0, 0, Int.MAX_VALUE, Int.MAX_VALUE))
        assertCoverage(regions, DetailTilePlanner.plan(regions))
    }

    @Test
    fun nearbyPrefetchUsesRemainingTileBudgetWithoutReducingVisibleResolution() {
        val visible = listOf(PixelRegion(1000, 1000, 2000, 2000))
        val ring = listOf(PixelRegion(500, 500, 2500, 2500).subtract(visible.single()))
        val plan = DetailTilePlanner.planPrioritized(visible, ring)

        assertEquals(1, plan.visible.sampleSize)
        assertTrue(plan.buffer.sampleSize >= plan.visible.sampleSize)
        assertTrue(plan.requests.size <= 20)
        assertCoverage(visible, plan.visible)
        assertTrue(plan.buffer.requests.all { tile ->
            !(
            tile.region.left < visible.single().right && tile.region.right > visible.single().left &&
                tile.region.top < visible.single().bottom && tile.region.bottom > visible.single().top
            )
        })
    }

    @Test
    fun visibleTilesUseTheWholeBudgetWhenThereIsNotRoomForEveryBufferSection() {
        val visible = listOf(PixelRegion(1000, 1000, 5096, 5096))
        val ring = listOf(List(8) { index -> PixelRegion(index * 512, 0, index * 512 + 256, 256) })

        val plan = DetailTilePlanner.planPrioritized(visible, ring)

        assertTrue(plan.buffer.requests.isEmpty())
        assertTrue(plan.visible.requests.size <= 20)
    }

    private fun assertCoverage(regions: List<PixelRegion?>, plan: TilePlan) {
        assertTrue(plan.sampleSize > 0 && (plan.sampleSize and (plan.sampleSize - 1)) == 0)
        assertTrue(plan.requests.size <= 20)
        regions.forEachIndexed { slot, region ->
            val tiles = plan.requests.filter { it.pageSlot == slot }.map { it.region }
            if (region == null) assertTrue(tiles.isEmpty()) else {
                var area = 0L
                tiles.forEach { tile ->
                    assertTrue(tile.left >= region.left && tile.right <= region.right)
                    assertTrue(tile.top >= region.top && tile.bottom <= region.bottom)
                    assertTrue(tile.width > 0 && tile.height > 0)
                    assertTrue((tile.width.toLong() + plan.sampleSize - 1) / plan.sampleSize <= 512)
                    assertTrue((tile.height.toLong() + plan.sampleSize - 1) / plan.sampleSize <= 512)
                    area += tile.width.toLong() * tile.height
                }
                assertEquals(region.width.toLong() * region.height, area)
                for (index in tiles.indices) for (other in index + 1 until tiles.size) {
                    val a = tiles[index]
                    val b = tiles[other]
                    assertTrue(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
                }
            }
        }
    }
}
