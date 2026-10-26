package dev.tabletcomics.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import dev.tabletcomics.library.coverCacheKey

class ArchivePreviewOrderTest {
    @Test
    fun selfExtractingZipPreviewSkipsTheBoundedLeadingStub() {
        val archive = zipOf("10.png" to byteArrayOf(10), "2.png" to byteArrayOf(2))
        val prefixed = ByteArray(32 * 1024 + 3) { 0x4d } + archive

        val preview = ZipArchiveReader.previewStream(ByteArrayInputStream(prefixed)).use {
            ZipArchiveReader.readNaturalPreview(it)
        }

        assertEquals(listOf<Byte>(2), preview?.toList())
    }

    @Test
    fun emptyEntriesStillCountTowardThePreviewWorkLimit() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            repeat(ArchiveLimits.MAX_ENTRIES + 1) { index ->
                zip.putNextEntry(ZipEntry("empty-$index.txt"))
                zip.closeEntry()
            }
        }

        val error = assertThrows(IOException::class.java) {
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { ZipArchiveReader.readNaturalPreview(it) }
        }
        assertEquals(true, error.message.orEmpty().contains("too many archive entries"))
    }

    @Test
    fun duplicateImageFilenamesHaveAnActionableFailure() {
        val error = assertThrows(IOException::class.java) {
            rejectDuplicateImageNames(listOf("page.png", "page.png"))
        }
        assertEquals(true, error.message.orEmpty().contains("duplicate image filename"))
        assertEquals(true, error.message.orEmpty().contains("Rename"))
    }

    @Test
    fun streamedPreviewRejectsDuplicateImageFilenames() {
        val original = zipOf("1.png" to byteArrayOf(1), "2.png" to byteArrayOf(2))
        val duplicate = original.toString(Charsets.ISO_8859_1).replace("2.png", "1.png").toByteArray(Charsets.ISO_8859_1)
        assertThrows(IOException::class.java) {
            ZipInputStream(ByteArrayInputStream(duplicate)).use { ZipArchiveReader.readNaturalPreview(it) }
        }
    }

    private fun zipOf(vararg pages: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            pages.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun zipCoverUsesNaturalFirstPage() {
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                listOf("10.png" to byteArrayOf(10), "2.png" to byteArrayOf(2)).forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

        val preview = ZipArchiveReader.readNaturalPreview(ZipInputStream(ByteArrayInputStream(archive)))

        assertEquals(listOf<Byte>(2), preview?.toList())
    }

    @Test
    fun oversizedIrrelevantZipEntryStopsPreviewScanWithoutDrainingTheEntry() {
        val uncompressed = ByteArray(ArchiveLimits.MAX_PREVIEW_BYTES.toInt() + 1024) { (it % 251).toByte() }
        val crc = CRC32().apply { update(uncompressed) }.value
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("metadata.bin").apply {
                    method = ZipEntry.STORED
                    size = uncompressed.size.toLong()
                    compressedSize = uncompressed.size.toLong()
                    this.crc = crc
                })
                zip.write(uncompressed)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("1.png"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }.toByteArray()
        var bytesRead = 0L
        val input = object : FilterInputStream(ByteArrayInputStream(archive)) {
            override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also {
                if (it > 0) bytesRead += it
            }
        }

        val preview = ZipArchiveReader.readNaturalPreview(ZipInputStream(input))

        org.junit.Assert.assertNull(preview)
        org.junit.Assert.assertTrue(
            "preview scan should stop near its 20 MiB cap, but consumed $bytesRead bytes",
            bytesRead <= ArchiveLimits.MAX_PREVIEW_BYTES + 64 * 1024,
        )
    }

    @Test
    fun previewKeepsSelectedCoverWhenLaterPagesExhaustScanBudget() {
        val uncompressed = ByteArray(ArchiveLimits.MAX_PREVIEW_BYTES.toInt() + 1024) { (it % 251).toByte() }
        val crc = CRC32().apply { update(uncompressed) }.value
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("1.png"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("2.png").apply {
                    method = ZipEntry.STORED
                    size = uncompressed.size.toLong()
                    compressedSize = uncompressed.size.toLong()
                    this.crc = crc
                })
                zip.write(uncompressed)
                zip.closeEntry()
            }
        }.toByteArray()

        val preview = ZipArchiveReader.readNaturalPreview(ZipInputStream(ByteArrayInputStream(archive)))

        assertEquals(listOf<Byte>(1), preview?.toList())
    }

    @Test
    fun changedSizeInvalidatesCover() {
        val original = coverCacheKey("content://provider/comic", size = 100L, lastModified = 10L)
        val changed = coverCacheKey("content://provider/comic", size = 101L, lastModified = 10L)

        assertNotEquals(original, changed)
    }

    @Test
    fun extensionSignatureMismatchReturnsActionableIoError() {
        val rar = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00)

        val error = assertThrows(IOException::class.java) { detectArchiveFormat(rar, "cbz") }
        assertEquals(true, error.message.orEmpty().contains("do not match", ignoreCase = true))
    }
}
