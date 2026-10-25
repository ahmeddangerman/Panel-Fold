package dev.tabletcomics.archive

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ArchiveFormatTest {
    @Test fun signaturePrefixReaderStopsAtAnImmediateSupportedMarker() {
        val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(2 * 1024 * 1024) { 7 }
        var bytesRead = 0L
        val input = object : FilterInputStream(ByteArrayInputStream(bytes)) {
            override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also {
                if (it > 0) bytesRead += it
            }
        }

        val prefix = readArchiveDetectionPrefix(input)

        assertEquals(ArchiveFormat.ZIP, detectArchiveFormat(prefix, "cbz"))
        assertTrue("only the initial ZIP signature is needed", bytesRead <= 8)
    }

    @Test fun recognizesZipLocalAndEmptyArchiveSignatures() {
        assertEquals(ArchiveFormat.ZIP, detectArchiveFormat(byteArrayOf(0x50, 0x4b, 0x03, 0x04), "cbz"))
        assertEquals(ArchiveFormat.ZIP, detectArchiveFormat(byteArrayOf(0x50, 0x4b, 0x05, 0x06), "zip"))
    }

    @Test fun recognizesBothSupportedRarSignatures() {
        assertEquals(ArchiveFormat.RAR, detectArchiveFormat(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00), "cbr"))
        assertEquals(ArchiveFormat.RAR, detectArchiveFormat(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00), "rar"))
        val selfExtracting = ByteArray(20) + byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00)
        assertEquals(ArchiveFormat.RAR, detectArchiveFormat(selfExtracting, null))
    }

    @Test fun rejectsUnknownAndMismatchedSignatures() {
        assertThrows(ArchiveFormatException::class.java) { detectArchiveFormat(byteArrayOf(0x01, 0x02, 0x03), "zip") }
        assertThrows(ArchiveFormatException::class.java) {
            detectArchiveFormat(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00), "zip")
        }
    }

    @Test fun enforcesPageAndImageLimitsAtTheirBoundaries() {
        assertTrue(ArchiveLimits.isPageSizeAllowed(24L * 1024 * 1024))
        assertFalse(ArchiveLimits.isPageSizeAllowed(24L * 1024 * 1024 + 1))
        assertTrue(ArchiveLimits.isImageSizeAllowed(10_000, 10_000))
        assertFalse(ArchiveLimits.isImageSizeAllowed(10_001, 10_000))
        assertFalse(ArchiveLimits.isImageSizeAllowed(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(ArchiveLimits.isImageSizeAllowed(0, 10))
    }

    @Test fun enforcesTheStagedArchiveLimitAtItsBoundary() {
        assertTrue(ArchiveLimits.isArchiveSizeAllowed(8L * 1024 * 1024 * 1024))
        assertFalse(ArchiveLimits.isArchiveSizeAllowed(8L * 1024 * 1024 * 1024 + 1))
    }
}
