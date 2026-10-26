package dev.tabletcomics.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionDecodeSampleTest {
    @Test fun sampleIsAlwaysPositivePowerOfTwoAndNeverDecodesMoreThanRequested() {
        assertEquals(1, regionDecodeSampleSize(-1))
        assertEquals(1, regionDecodeSampleSize(1))
        assertEquals(2, regionDecodeSampleSize(2))
        assertEquals(4, regionDecodeSampleSize(3))
        assertEquals(8, regionDecodeSampleSize(8))
        assertEquals(16, regionDecodeSampleSize(9))
        assertEquals(1 shl 30, regionDecodeSampleSize(Int.MAX_VALUE))
    }
}
