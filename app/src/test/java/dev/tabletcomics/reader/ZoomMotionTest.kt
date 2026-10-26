package dev.tabletcomics.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomMotionTest {
    @Test fun zoomHasAnIntermediateFrame() {
        val frame = ZoomMotion.frame(ZoomTransform.Identity, ZoomTransform(3f, Offset(120f, 60f)), .5f)
        assertEquals(2f, frame.scale, 0f)
        assertEquals(Offset(60f, 30f), frame.pan)
    }
    @Test fun glideStopsAtPageBounds() {
        val target = ZoomMotion.glideTarget(ZoomTransform(2f), Offset(10000f, -10000f), Size(800f, 800f), Rect(0f, 0f, 800f, 800f))
        assertEquals(Offset(400f, -400f), target.pan)
    }
    @Test fun zeroMomentumStopsAtTheReleasedPosition() {
        val start = ZoomTransform(3f, Offset(30f, -50f))
        val target = ZoomMotion.glideTarget(start, Offset(1000f, -2000f), Size(1000f, 1000f), Rect(0f, 0f, 1000f, 1000f), 0f)
        assertEquals(start, target)
    }
    @Test fun momentumStrengthChangesTravelBeforeBoundsAreReached() {
        val start = ZoomTransform(5f)
        val target = ZoomMotion.glideTarget(start, Offset(1000f, -1000f), Size(1000f, 1000f), Rect(0f, 0f, 1000f, 1000f), .5f)
        assertEquals(Offset(90f, -90f), target.pan)
    }
}
