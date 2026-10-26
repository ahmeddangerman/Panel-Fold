package dev.tabletcomics.reader

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class BorderCropTest {
    @Test
    fun uniformWhiteAndBlackMarginsKeepASafetyRim() {
        for (border in listOf(-1, 0xff000000.toInt())) {
            val result = BorderCrop.detect(100, 100, page(border, margin = 8))
            assertEquals(Rect(.06f, .06f, .94f, .94f), result)
        }
    }

    @Test
    fun touchingArtAndColoredOrTransparentEdgesArePreserved() {
        val artTouchesCorner = page(-1, margin = 8).also { it[0] = 0xff444444.toInt() }
        assertEquals(ReaderGeometry.fullCrop, BorderCrop.detect(100, 100, artTouchesCorner))
        assertEquals(ReaderGeometry.fullCrop, BorderCrop.detect(100, 100, page(0xffddddff.toInt(), margin = 8)))
        assertEquals(ReaderGeometry.fullCrop, BorderCrop.detect(100, 100, page(0x00ffffff, margin = 8)))
    }

    @Test
    fun oversizedIntentionalMarginsAndBlankPagesArePreserved() {
        assertEquals(ReaderGeometry.fullCrop, BorderCrop.detect(100, 100, page(-1, margin = 20)))
        assertEquals(ReaderGeometry.fullCrop, BorderCrop.detect(100, 100, IntArray(10000) { -1 }))
        assertEquals(ReaderGeometry.fullCrop, BorderCrop.detect(10, 10, IntArray(100) { -1 }))
    }

    private fun page(border: Int, margin: Int): IntArray = IntArray(10000) { index ->
        val x = index % 100
        val y = index / 100
        if (x < margin || x >= 100 - margin || y < margin || y >= 100 - margin) border else 0xff445566.toInt()
    }
}
