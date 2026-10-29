package dev.tabletcomics.reader

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import dev.tabletcomics.ui.ComicsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReaderGestureInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private fun page(index: Int, color: Int) = ReaderUnit(index, listOf(index to Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }))

    @Test fun verticalReadingTurnsOnAnUpwardSwipe() {
        val first = page(0, android.graphics.Color.RED)
        val second = page(1, android.graphics.Color.BLUE)
        val current = mutableStateOf(first)
        var turns = 0
        compose.setContent { ComicsTheme("violet") {
            PageTurnReader(current.value, null, if (current.value.startIndex == 0) second else null, 2, false,
                "vertical", "vertical-test", verticalReading = true,
                onTurn = { turns++; current.value = it }, onToggleControls = {}, onEndReached = {},
                onDecodeRegions = { _, regions, _ -> regions.map { null } },
                modifier = Modifier.fillMaxSize().testTag("reader"))
        } }

        compose.onNodeWithTag("reader").performTouchInput { swipeUp() }

        compose.runOnIdle { assertEquals(1, turns); assertEquals(1, current.value.startIndex) }
    }

    @Test fun unloadedNextIsNotTheEndOfTheBook() {
        val current = page(0, android.graphics.Color.RED)
        var ends = 0
        compose.setContent { ComicsTheme("violet") {
            PageTurnReader(current, null, null, 3, false, "single", "unloaded", onTurn = {}, onToggleControls = {},
                onEndReached = { ends++ }, onDecodeRegions = { _, regions, _ -> regions.map { null } },
                modifier = Modifier.fillMaxSize().testTag("reader"))
        } }
        compose.onNodeWithTag("reader").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(0, ends) }
    }

    @Test fun lateNeighborStaysFrozenThenTheNextSwipeCommits() {
        val first = page(0, android.graphics.Color.RED)
        val second = page(1, android.graphics.Color.BLUE)
        val current = mutableStateOf(first)
        val next = mutableStateOf<ReaderUnit?>(null)
        var turns = 0
        var ends = 0
        compose.setContent { ComicsTheme("violet") {
            PageTurnReader(current.value, null, next.value, 3, false, "single", "late", onTurn = { turns++; current.value = it; next.value = null },
                onToggleControls = {}, onEndReached = { ends++ }, onDecodeRegions = { _, regions, _ -> regions.map { null } },
                modifier = Modifier.fillMaxSize().testTag("reader"))
        } }
        compose.onNodeWithTag("reader").performTouchInput {
            down(Offset(width * .9f, height * .5f)); moveBy(Offset(-width * .2f, 0f))
        }
        compose.runOnIdle { next.value = second }
        compose.onNodeWithTag("reader").performTouchInput { moveBy(Offset(-width * .6f, 0f)); up() }
        compose.runOnIdle { assertEquals(0, turns); assertEquals(0, ends) }
        compose.onNodeWithTag("reader").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, turns); assertEquals(1, current.value.startIndex); assertEquals(0, ends) }
    }

    @Test fun zoomedTurnLeavesOnlyTheIncomingPageOnScreen() {
        val first = page(0, android.graphics.Color.RED)
        val second = page(1, android.graphics.Color.BLUE)
        val current = mutableStateOf(first)
        var turns = 0
        compose.setContent { ComicsTheme("violet") {
            PageTurnReader(current.value, null, if (current.value.startIndex == 0) second else null, 2, false, "single", "zoomed",
                initialZoomScale = 2f, onTurn = { turns++; current.value = it }, onToggleControls = {}, onEndReached = {},
                onDecodeRegions = { _, regions, _ -> regions.map { null } }, modifier = Modifier.fillMaxSize().testTag("reader"))
        } }
        // First outward drag pans to the edge; a subsequent drag completes the turn.
        repeat(3) { compose.onNodeWithTag("reader").performTouchInput { swipeLeft() } }
        compose.runOnIdle { assertEquals(1, turns); assertEquals(1, current.value.startIndex) }
        val image = compose.onNodeWithTag("reader").captureToImage().toPixelMap()
        assertEquals(android.graphics.Color.BLUE, image[image.width / 2, image.height / 2].toArgb())
    }

    @Test fun pressAndHoldShowsTheMagnifierUntilTheFingerLifts() {
        val current = page(0, android.graphics.Color.RED)
        compose.setContent { ComicsTheme("violet") {
            PageTurnReader(current, null, null, 1, false, "single", "magnifier-test", onTurn = {},
                onToggleControls = {}, onEndReached = {}, onDecodeRegions = { _, regions, _ -> regions.map { null } },
                modifier = Modifier.fillMaxSize().testTag("reader"))
        } }

        compose.onNodeWithTag("reader").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700L)
        compose.onNodeWithTag("reader").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Magnifier active",
        ))
        compose.onNodeWithTag("reader").performTouchInput { moveTo(Offset(width * .6f, height * .4f)) }
        compose.onNodeWithTag("reader").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Magnifier active",
        ))
        compose.onNodeWithTag("reader").performTouchInput { up() }
        compose.onNodeWithTag("reader").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Magnifier hidden",
        ))
    }
}
