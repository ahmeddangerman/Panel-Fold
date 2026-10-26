package dev.tabletcomics.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ReaderGeometryTest {
    @Test
    fun topAlignedFiveTimesPortraitRequestsTheVisibleTopRatherThanAnEmptyRegion() {
        val viewport = Size(1000f, 1000f)
        val page = ReaderGeometry.layout(viewport, listOf(Size(200f, 1000f))).single()
        val zoom = ZoomBounds.pageStart(5f, viewport, page.destination)
        val visible = ReaderGeometry.visibleViewport(viewport, zoom)

        assertEquals(Rect(400f, 0f, 600f, 200f), visible)
        assertEquals(PixelRegion(0, 0, 1000, 1000), page.visibleSource(visible, Size(1000f, 5000f)))
    }

    @Test
    fun centeredAndPannedZoomUseTheSameCenterAsDrawing() {
        val viewport = Size(1200f, 800f)
        val zoom = ZoomTransform(2.5f, Offset(-150f, 230f))
        val visible = ReaderGeometry.visibleViewport(viewport, zoom)
        fun forward(point: Offset): Offset {
            val center = Offset(viewport.width / 2f, viewport.height / 2f)
            return center + (point - center) * zoom.scale + zoom.pan
        }

        assertPoint(Offset.Zero, forward(visible.topLeft))
        assertPoint(Offset(1200f, 800f), forward(visible.bottomRight))
    }

    @Test
    fun spreadSlotsIndependentlyIntersectTheVisibleViewportRegardlessOfReadingOrder() {
        val viewport = Size(1200f, 800f)
        val geometry = ReaderGeometry.layout(viewport, listOf(Size(600f, 800f), Size(600f, 800f)))
        for ((pan, visibleSlot) in listOf(Offset(600f, 0f) to 0, Offset(-600f, 0f) to 1)) {
            val visible = ReaderGeometry.visibleViewport(viewport, ZoomTransform(2f, pan))
            val source = Size(1200f, 1600f)
            val regions = geometry.map { it.visibleSource(visible, source) }
            assertEquals(PixelRegion(0, 400, 1200, 1200), regions[visibleSlot])
            assertNull(regions[1 - visibleSlot])
        }
    }

    @Test
    fun widthFitStartsAtTopAndPansAtRelativeScaleOne() {
        val viewport = Size(1000f, 1000f)
        val geometry = ReaderGeometry.layout(viewport, listOf(Size(1000f, 2000f)), fit = PageFit.WIDTH)
        val content = ReaderGeometry.bounds(geometry)
        val start = ZoomBounds.pageStart(1f, viewport, content)

        assertEquals(Rect(0f, -500f, 1000f, 1500f), content)
        assertEquals(ZoomTransform(1f, Offset(0f, 500f)), start)
        assertTrue(ZoomBounds.hasOverflow(viewport, content, start.scale))
        assertEquals(Offset(0f, 350f), start.transformBy(1f, Offset(0f, -100f), viewport, content).pan)
        assertEquals(PixelRegion(0, 0, 1000, 1000), geometry.single().visibleSource(
            ReaderGeometry.visibleViewport(viewport, start), Size(1000f, 2000f),
        ))
    }

    @Test
    fun heightFitCanPanAndTurnAtItsHorizontalEdgesAtRelativeScaleOne() {
        val viewport = Size(1000f, 1000f)
        val page = ReaderGeometry.layout(viewport, listOf(Size(2000f, 1000f)), fit = PageFit.HEIGHT).single()
        assertEquals(Rect(-500f, 0f, 1500f, 1000f), page.destination)
        assertTrue(ZoomBounds.canTurnAtHorizontalEdge(viewport, page.destination, 1f, Offset(-500f, 0f), -1f))
        assertFalse(ZoomBounds.canTurnAtHorizontalEdge(viewport, page.destination, 1f, Offset.Zero, -1f))
        assertEquals(PixelRegion(500, 0, 1500, 1000), page.visibleSource(Rect(0f, 0f, 1000f, 1000f), Size(2000f, 1000f)))
    }

    @Test
    fun croppedSourceMappingAndVisibleRequestsUseTheSameFitGeometry() {
        val viewport = Size(1000f, 1000f)
        val crop = Rect(.05f, .1f, .95f, .9f)
        val source = Size(1000f, 2000f)
        for (fit in PageFit.entries) {
            val page = ReaderGeometry.layout(viewport, listOf(source), listOf(crop), fit).single()
            val croppedSource = Rect(50f, 200f, 950f, 1800f)
            assertRect(page.destination, page.sourceToDisplay(croppedSource, source))
            val visible = page.visibleSource(page.destination, source)!!
            assertTrue(abs(visible.left - 50) <= 1)
            assertTrue(abs(visible.top - 200) <= 1)
            assertTrue(abs(visible.right - 950) <= 1)
            assertTrue(abs(visible.bottom - 1800) <= 1)
        }
    }

    @Test
    fun adjacentTilesMapToIdenticalFloatingBoundaries() {
        val page = PageGeometry(ReaderGeometry.fullCrop, Rect(0f, 0f, 400f, 800f))
        val source = Size(2000f, 4000f)
        val left = page.sourceToDisplay(Rect(512f, 0f, 1024f, 512f), source)
        val right = page.sourceToDisplay(Rect(1024f, 0f, 1536f, 512f), source)
        val below = page.sourceToDisplay(Rect(512f, 512f, 1024f, 1024f), source)

        assertEquals(left.right, right.left, 0f)
        assertEquals(left.bottom, below.top, 0f)
        assertEquals(204.8f, left.right, .001f)
    }

    @Test
    fun missingOrFailedPrefetchIsNotTheEndUntilTheLastPage() {
        assertFalse(isActualForwardEnd(ReaderUnit(0, emptyList()), 100))
        assertFalse(isActualForwardEnd(ReaderUnit(98, emptyList()), 100))
        assertTrue(isActualForwardEnd(ReaderUnit(99, emptyList()), 100))
        assertFalse(isActualForwardEnd(ReaderUnit(0, emptyList()), 0))
        assertTrue(isActualForwardEnd(ReaderUnit(0, emptyList()), 1))
    }

    private fun assertPoint(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, .001f)
        assertEquals(expected.y, actual.y, .001f)
    }
    private fun assertRect(expected: Rect, actual: Rect) {
        assertPoint(expected.topLeft, actual.topLeft)
        assertPoint(expected.bottomRight, actual.bottomRight)
    }
}
