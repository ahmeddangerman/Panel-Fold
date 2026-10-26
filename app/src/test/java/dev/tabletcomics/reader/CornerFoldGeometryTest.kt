package dev.tabletcomics.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CornerFoldGeometryTest {
    private val viewport = Size(1000f, 800f)

    @Test fun reflectedOriginalCornerFollowsBothFingerCoordinatesForAllFourCorners() {
        for (corner in PageCorner.entries) {
            val original = corner.position(viewport)
            val finger = original + Offset(if (corner.isRight) -420f else 420f, if (corner.isBottom) -170f else 170f)
            val fold = CornerFoldGeometry.calculate(viewport, corner, finger)!!

            assertPoint(finger, fold.reflection.map(original))
            assertNotNull(fold.crease)
            assertTrue(fold.frontPolygon.isNotEmpty())
            assertTrue(fold.backPolygon.isNotEmpty())
        }
    }

    @Test fun foldedArtworkPreservesDistancesInsteadOfScalingTheWholePage() {
        val fold = CornerFoldGeometry.calculate(viewport, PageCorner.BOTTOM_RIGHT, Offset(550f, 430f))!!
        val a = Offset(900f, 680f)
        val b = Offset(950f, 720f)

        assertEquals((a - b).getDistance(), (fold.reflection.map(a) - fold.reflection.map(b)).getDistance(), .001f)
        assertEquals(-1f, fold.reflection.m00 * fold.reflection.m11 - fold.reflection.m01 * fold.reflection.m10, .001f)
        assertPoint(a, fold.reflection.map(fold.reflection.map(a)))
    }

    @Test fun frontAndLiftedSourceAlwaysPartitionTheOriginalPage() {
        for (corner in PageCorner.entries) for (x in listOf(-500f, 0f, 250f, 700f, 1200f)) for (y in listOf(-150f, 150f, 600f, 950f)) {
            val fold = CornerFoldGeometry.calculate(viewport, corner, Offset(x, y)) ?: continue
            assertEquals(800000.0, area(fold.frontPolygon) + area(fold.liftedSourcePolygon), .5)
            assertTrue(area(fold.backPolygon) <= area(fold.liftedSourcePolygon) + .5)
            for (point in fold.frontPolygon + fold.liftedSourcePolygon + fold.backPolygon) {
                assertTrue(point.x >= -.01f && point.x <= viewport.width + .01f)
                assertTrue(point.y >= -.01f && point.y <= viewport.height + .01f)
            }
        }
    }

    @Test fun draggingPastTheOppositeEdgeCompletesTheTurnWithoutLeavingHalfThePage() {
        for (corner in PageCorner.entries) {
            val original = corner.position(viewport)
            val final = Offset(if (corner.isRight) -viewport.width else viewport.width * 2f, original.y)
            val fold = CornerFoldGeometry.calculate(viewport, corner, final)!!

            assertTrue(fold.frontPolygon.isEmpty())
            assertTrue(fold.backPolygon.isEmpty())
            assertEquals(800000.0, area(fold.liftedSourcePolygon), .5)
        }
    }

    @Test fun untouchedOrInvalidCornersLeaveTheNormalPageUnchanged() {
        assertNull(CornerFoldGeometry.calculate(viewport, PageCorner.TOP_RIGHT, Offset(1000f, 0f)))
        assertNull(CornerFoldGeometry.calculate(Size.Zero, PageCorner.TOP_RIGHT, Offset.Zero))
        assertNull(CornerFoldGeometry.calculate(viewport, PageCorner.TOP_RIGHT, Offset(Float.NaN, 0f)))
        assertNull(CornerFoldGeometry.calculate(viewport, PageCorner.TOP_RIGHT, Offset(1100f, -100f)))
    }

    @Test fun creasePointsStayStationaryWhenArtworkReflects() {
        val fold = CornerFoldGeometry.calculate(viewport, PageCorner.TOP_RIGHT, Offset(380f, 220f))!!

        assertPoint(fold.crease!!.start, fold.reflection.map(fold.crease.start))
        assertPoint(fold.crease.end, fold.reflection.map(fold.crease.end))
    }

    @Test fun onlyTouchesNearAnActualCornerSelectThePaperFold() {
        assertEquals(PageCorner.BOTTOM_RIGHT, PageCorner.fromTouch(Offset(990f, 790f), viewport, 72f))
        assertEquals(PageCorner.TOP_LEFT, PageCorner.fromTouch(Offset(20f, 30f), viewport, 72f))
        assertNull(PageCorner.fromTouch(Offset(990f, 400f), viewport, 72f))
        assertNull(PageCorner.fromTouch(Offset(500f, 30f), viewport, 72f))
        assertNull(PageCorner.fromTouch(Offset(-5f, 20f), viewport, 72f))
    }

    private fun assertPoint(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, .01f)
        assertEquals(expected.y, actual.y, .01f)
    }

    private fun area(polygon: List<Offset>): Double {
        var result = 0.0
        polygon.forEachIndexed { index, point ->
            val next = polygon[(index + 1) % polygon.size]
            result += point.x.toDouble() * next.y - next.x.toDouble() * point.y
        }
        return abs(result) / 2.0
    }
}
