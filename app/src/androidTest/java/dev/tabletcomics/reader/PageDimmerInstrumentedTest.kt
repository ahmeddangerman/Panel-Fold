package dev.tabletcomics.reader

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class PageDimmerInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private fun page(index: Int, color: Int, width: Int = 200, height: Int = 300) = ReaderUnit(index,
        listOf(index to Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }))

    private fun assertPixel(expected: Int, x: Float, y: Float) {
        val pixels = compose.onNodeWithTag("dimmer-reader").captureToImage().toPixelMap()
        val actual = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()].toArgb()
        assertEquals(255, Color.alpha(actual))
        listOf(Color.red(expected) to Color.red(actual), Color.green(expected) to Color.green(actual),
            Color.blue(expected) to Color.blue(actual)).forEach { (want, got) ->
            assertTrue("Expected pixel $expected, got $actual", abs(want - got) <= 2)
        }
    }

    @Test fun dimmingCanBeDisabledAndLeavesBlackMarginsAndReaderControlsUnchanged() {
        val strength = mutableFloatStateOf(0f)
        val first = page(0, Color.WHITE, 200, 200)
        compose.setContent {
            Box(Modifier.size(300.dp, 450.dp).testTag("dimmer-reader")) {
                PageTurnReader(first, null, null, 1, false, "single", "dimmer-margins",
                    pageDimming = strength.floatValue, onTurn = {}, onToggleControls = {}, onEndReached = {},
                    showZoomReset = false, onDecodeRegions = { _, _, _ -> emptyList() }, modifier = Modifier.fillMaxSize())
                // Reader controls are composited after the page Canvas, as in ComicApp.
                Box(Modifier.size(30.dp).background(androidx.compose.ui.graphics.Color.Blue))
            }
        }
        assertPixel(Color.WHITE, .5f, .5f)
        compose.runOnIdle { strength.floatValue = .5f }
        assertPixel(Color.rgb(128, 128, 128), .5f, .5f)
        assertPixel(Color.BLACK, .9f, .05f)
        assertPixel(Color.BLUE, .05f, .03f)
        compose.runOnIdle { strength.floatValue = 0f }
        assertPixel(Color.WHITE, .5f, .5f)
    }

    @Test fun outgoingAndIncomingPagesHaveTheSameDimmingBeforeDuringAndAfterTurning() {
        val first = page(0, Color.RED)
        val second = page(1, Color.BLUE)
        val current = mutableStateOf(first)
        compose.setContent {
            PageTurnReader(current.value, if (current.value == second) first else null,
                if (current.value == first) second else null, 2, false, "single", "dimmer-turn",
                pageDimming = .5f, onTurn = { current.value = it }, onToggleControls = {}, onEndReached = {},
                showZoomReset = false, onDecodeRegions = { _, _, _ -> emptyList() },
                modifier = Modifier.size(300.dp, 450.dp).testTag("dimmer-reader"))
        }
        assertPixel(Color.rgb(128, 0, 0), .5f, .5f)
        compose.onNodeWithTag("dimmer-reader").performTouchInput {
            down(Offset(width * .9f, height * .5f))
            moveBy(Offset(-width * .6f, 0f), delayMillis = 250)
        }
        assertPixel(Color.rgb(128, 0, 0), .2f, .5f)
        assertPixel(Color.rgb(0, 0, 128), .8f, .5f)
        compose.onNodeWithTag("dimmer-reader").performTouchInput { up() }
        compose.runOnIdle { assertEquals(second, current.value) }
        assertPixel(Color.rgb(0, 0, 128), .5f, .5f)
    }
}
