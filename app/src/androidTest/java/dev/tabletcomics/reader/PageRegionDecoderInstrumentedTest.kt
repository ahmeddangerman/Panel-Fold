package dev.tabletcomics.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class PageRegionDecoderInstrumentedTest {
    @Test fun actualPngRegionsRespectBoundsAndPowerOfTwoSampleWithoutRewritingOriginal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "region-test-${System.nanoTime()}")
        val original = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
        original.eraseColor(Color.BLUE)
        val bytes = ByteArrayOutputStream().use { output ->
            check(original.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        original.recycle()
        val store = SessionSourceStore(root)
        var result = emptyList<Bitmap?>()
        try {
            val source = store.save(0, bytes, 128, 96)
            result = PageRegionDecoder().decode(store, source, listOf(
                Rect(-30, -20, -1, -1),
                Rect(129, 0, 160, 30),
                Rect(-16, -16, 64, 64),
                Rect(0, 0, 128, 96),
            ), sampleSize = 3)
            assertNull(result[0])
            assertNull(result[1])
            assertEquals(16, result[2]!!.width)
            assertEquals(16, result[2]!!.height)
            assertEquals(32, result[3]!!.width)
            assertEquals(24, result[3]!!.height)
            assertEquals(Color.BLUE, result[3]!!.getPixel(0, 0))
            assertArrayEquals(bytes, source.sourceFile.readBytes())
            store.retainOnly(emptySet())
            assertNull(PageRegionDecoder().decode(store, source, listOf(Rect(0, 0, 32, 32)), 1).single())
        } finally {
            result.filterNotNull().forEach { it.recycle() }
            store.close()
            root.deleteRecursively()
        }
    }
}
