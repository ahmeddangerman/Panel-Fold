package dev.tabletcomics.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.compose.foundation.layout.size
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
import dev.tabletcomics.ui.ComicsTheme
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Different preview/detail colors make a quality downgrade observable in the actual Canvas. */
class ReaderDetailMotionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val sourceWidth = 1024
    private val sourceHeight = 1536
    private val blockNewDecodes = AtomicBoolean(false)
    private val decodeGate = CompletableDeferred<Unit>()
    private val decoded = ConcurrentLinkedQueue<Pair<Int, Rect>>()

    private fun page(index: Int, previewColor: Int) = ReaderUnit(
        index,
        listOf(index to Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(previewColor) }),
        mapOf(index to FullResolutionPage(index, File("/fake/detail-motion-$index.jpg"), sourceWidth, sourceHeight)),
    )

    private suspend fun decode(source: FullResolutionPage, regions: List<Rect>, sample: Int): List<Bitmap?> {
        if (blockNewDecodes.get()) decodeGate.await()
        return regions.map { region ->
            Bitmap.createBitmap(
                ((region.width() + sample - 1) / sample).coerceAtLeast(1),
                ((region.height() + sample - 1) / sample).coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            ).apply {
                eraseColor(if (source.pageIndex == 0) Color.GREEN else Color.CYAN)
                decoded += source.pageIndex to Rect(region)
            }
        }
    }

    private fun hasDecoded(page: Int, x: Int, y: Int): Boolean =
        decoded.any { (index, region) -> index == page && region.contains(x, y) }

    private fun decodedArea(page: Int): Long = decoded.filter { it.first == page }
        .map { it.second }.distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
        .sumOf { it.width().toLong() * it.height() }

    private fun assertPixel(color: Int, xFraction: Float = .5f, yFraction: Float = .5f) {
        val pixels = compose.onNodeWithTag("reader-detail").captureToImage().toPixelMap()
        assertEquals(color, pixels[(pixels.width * xFraction).toInt(), (pixels.height * yFraction).toInt()].toArgb())
    }

    @Test
    fun sharpTilesRemainOnScreenWhileTheFingerPans() {
        val first = page(0, Color.RED)
        compose.setContent {
            ComicsTheme("violet") {
                PageTurnReader(first, null, null, 1, false, "single", "detail-pan",
                    initialZoomScale = 2f, panSensitivity = 1f, momentumStrength = 0f,
                    onTurn = {}, onToggleControls = {}, onEndReached = {}, showZoomReset = false,
                    onDecodeRegions = ::decode,
                    modifier = Modifier.size(300.dp, 450.dp).testTag("reader-detail"))
            }
        }
        compose.waitUntil(10_000) { hasDecoded(0, 512, 384) && hasDecoded(0, 512, 550) }
        compose.waitForIdle()
        assertPixel(Color.GREEN)
        blockNewDecodes.set(true)

        compose.onNodeWithTag("reader-detail").performTouchInput {
            down(Offset(width * .5f, height * .5f))
            moveBy(Offset(0f, -height * .1f), delayMillis = 32)
        }
        assertPixel(Color.GREEN)
        compose.onNodeWithTag("reader-detail").performTouchInput {
            moveBy(Offset(0f, -height * .05f), delayMillis = 32)
        }
        assertPixel(Color.GREEN)
        compose.onNodeWithTag("reader-detail").performTouchInput { up() }
        assertPixel(Color.GREEN)
    }

    @Test
    fun outgoingAndPrefetchedIncomingTilesSurviveATurnAndPageHandoff() {
        val first = page(0, Color.RED)
        val second = page(1, Color.BLUE)
        val current = mutableStateOf(first)
        var turns = 0
        compose.setContent {
            ComicsTheme("violet") {
                PageTurnReader(current.value, if (current.value.startIndex == 1) first else null,
                    if (current.value.startIndex == 0) second else null, 2, false, "single", "detail-turn",
                    onTurn = { turns++; current.value = it }, onToggleControls = {}, onEndReached = {},
                    showZoomReset = false, momentumStrength = 0f, onDecodeRegions = ::decode,
                    modifier = Modifier.size(300.dp, 450.dp).testTag("reader-detail"))
            }
        }
        val sourceArea = sourceWidth.toLong() * sourceHeight
        compose.waitUntil(10_000) { decodedArea(0) >= sourceArea && decodedArea(1) >= sourceArea }
        compose.waitForIdle()
        assertPixel(Color.GREEN)
        // A second decode cannot conceal a cache loss during the gesture or parent handoff.
        blockNewDecodes.set(true)

        compose.onNodeWithTag("reader-detail").performTouchInput {
            down(Offset(width * .9f, height * .5f))
            moveBy(Offset(-width * .6f, 0f), delayMillis = 250)
        }
        assertPixel(Color.GREEN, xFraction = .2f)
        assertPixel(Color.CYAN, xFraction = .8f)
        compose.onNodeWithTag("reader-detail").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, turns); assertEquals(1, current.value.startIndex) }
        assertPixel(Color.CYAN)
    }
}
