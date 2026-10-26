package dev.tabletcomics.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomBoundsTest {
    @Test
    fun newPageKeepsZoomAndAlignsItsTopWithTheViewport() {
        val viewport = Size(1200f, 800f)
        for (page in listOf(Size(600f, 1000f), Size(1000f, 1200f))) {
            val pages = listOf(page)
            val result = ZoomBounds.pageStart(2f, viewport, pages)
            val content = ZoomBounds.fittedContentRect(viewport, pages)
            val renderedTop = viewport.height / 2f + (content.top - viewport.height / 2f) * result.scale + result.pan.y
            assertEquals(2f, result.scale, 0f)
            assertEquals(0f, renderedTop, .01f)
        }
    }

    @Test
    fun unzoomedPageStillFitsAndCentersNormally() {
        val viewport = Size(1200f, 800f)
        val result = ZoomBounds.pageStart(1f, viewport, listOf(Size(1000f, 500f)))
        assertEquals(ZoomTransform.Identity, result)
    }

    @Test
    fun panClampsPortraitBounds() {
        val viewport = Size(800f, 1200f)
        val page = Rect(100f, 0f, 700f, 1200f)

        assertEquals(Offset(200f, -600f), ZoomBounds.clampPan(viewport, page, 2f, Offset(900f, -2000f)))
    }

    @Test
    fun panClampsLandscapeBounds() {
        val viewport = Size(1200f, 800f)
        val spread = Rect(0f, 100f, 1200f, 700f)

        assertEquals(Offset(-600f, 200f), ZoomBounds.clampPan(viewport, spread, 2f, Offset(-2000f, 900f)))
    }

    @Test
    fun smallPageRemainsCentered() {
        val viewport = Size(1000f, 1000f)
        val page = Rect(400f, 300f, 600f, 700f)

        assertEquals(Offset.Zero, ZoomBounds.clampPan(viewport, page, 1f, Offset(200f, -100f)))
    }

    @Test
    fun resetRestoresIdentity() {
        val transform = ZoomTransform(scale = 3f, pan = Offset(120f, -60f))

        assertEquals(ZoomTransform.Identity, transform.reset())
    }

    @Test
    fun screenSizedPanDeltaRemainsOneToOneAtHigherZoom() {
        val viewport = Size(1000f, 1000f)
        val content = Rect(0f, 0f, 1000f, 1000f)

        val moved = ZoomBounds.panByViewportDelta(
            viewport = viewport,
            content = content,
            scale = 3f,
            pan = Offset.Zero,
            delta = Offset(42f, -27f),
        )

        assertEquals(Offset(42f, -27f), moved)
    }

    @Test
    fun horizontalPageSwipeIsAvailableOnlyAtTheMatchingZoomPanEdge() {
        val viewport = Size(800f, 1200f)
        val content = Rect(100f, 0f, 700f, 1200f)
        val leftPanLimit = Offset(-200f, 0f)
        val rightPanLimit = Offset(200f, 0f)

        assertTrue(ZoomBounds.canTurnAtHorizontalEdge(viewport, content, 2f, leftPanLimit, -1f))
        assertFalse(ZoomBounds.canTurnAtHorizontalEdge(viewport, content, 2f, Offset.Zero, -1f))
        assertFalse(ZoomBounds.canTurnAtHorizontalEdge(viewport, content, 2f, leftPanLimit, 1f))
        assertTrue(ZoomBounds.canTurnAtHorizontalEdge(viewport, content, 2f, rightPanLimit, 1f))
    }

    @Test
    fun narrowZoomedPageDoesNotBlockHorizontalPageTurns() {
        val viewport = Size(1000f, 800f)
        val content = Rect(450f, 0f, 550f, 800f)

        assertTrue(ZoomBounds.canTurnAtHorizontalEdge(viewport, content, 2f, Offset.Zero, -1f))
        assertTrue(ZoomBounds.canTurnAtHorizontalEdge(viewport, content, 2f, Offset.Zero, 1f))
    }

    @Test
    fun pageTurnDrawOffsetCancelsGraphicsScale() {
        assertEquals(50f, ZoomBounds.canvasOffsetForViewportDrag(150f, 3f), 0f)
        assertEquals(150f, ZoomBounds.canvasOffsetForViewportDrag(150f, 1f), 0f)
    }

    @Test
    fun zoomedPageTurnPlacesNeighborAtViewportEdgeAndCentersItAtCompletion() {
        val viewportWidth = 1200f
        val scale = 3f
        val turnWidth = ZoomBounds.canvasTurnWidth(viewportWidth, scale)

        assertEquals(400f, turnWidth, 0f)
        assertEquals(0f, ZoomBounds.pageTurnNeighborOffset(-viewportWidth, viewportWidth, scale), 0f)
        assertEquals(0f, ZoomBounds.pageTurnNeighborOffset(viewportWidth, viewportWidth, scale), 0f)
        assertEquals(1200f, turnWidth * scale, 0f)
    }

    @Test
    fun widthFitZoomCentersTappedPageAndFillsTabletWidth() {
        val viewport = Size(1200f, 800f)
        val spread = listOf(Size(600f, 800f), Size(600f, 800f))

        val leftPage = ZoomBounds.fitPageToWidth(viewport, spread, pageIndex = 0)
        val rightPage = ZoomBounds.fitPageToWidth(viewport, spread, pageIndex = 1)

        assertEquals(2f, leftPage.scale, 0f)
        assertEquals(Offset(600f, 0f), leftPage.pan)
        assertEquals(2f, rightPage.scale, 0f)
        assertEquals(Offset(-600f, 0f), rightPage.pan)
    }

    @Test
    fun adjustableSensitivityChangesPanDistance() {
        val viewport = Size(1000f, 1000f)
        val content = Rect(0f, 0f, 1000f, 1000f)
        val moved = ZoomTransform(2f).transformBy(1f, Offset(20f, -10f), viewport, content, 2f)
        assertEquals(Offset(40f, -20f), moved.pan)
    }

    @Test
    fun zoomedPanUsesIncreasedViewportMovementGain() {
        val viewport = Size(1000f, 1000f)
        val content = Rect(0f, 0f, 1000f, 1000f)
        val transform = ZoomTransform(scale = 2f)

        val moved = transform.transformBy(1f, Offset(20f, -10f), viewport, content)

        assertEquals(Offset(30f, -15f), moved.pan)
    }
}
