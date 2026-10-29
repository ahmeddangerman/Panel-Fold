package dev.tabletcomics.archive

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NestedArchiveReaderInstrumentedTest {
    @Test
    fun opensImagesInsideNestedZipAndRarWithoutExpandingThemIntoMemoryFiles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val expectedPng = instrumentation.context.assets.open("page.png").use { it.readBytes() }
        val nestedZip = zipOf("chapter/page.png" to expectedPng)
        val nestedRar = instrumentation.context.assets.open("native/stored-rar4.cbr").use { it.readBytes() }
        val outer = File(context.cacheDir, "nested-archive-${System.nanoTime()}.zip")
        outer.writeBytes(zipOf(
            "chapter-01.cbz" to nestedZip,
            "chapter-02.cbr" to nestedRar,
            "cover.png" to expectedPng,
        ))
        try {
            NestedArchiveReader(context, ArchiveFormat.ZIP).open(
                Uri.fromFile(outer), {}, ArchiveCancellationRegistrar.NONE,
            ).use { archive ->
                assertEquals(3, archive.pages.size)
                assertEquals(
                    listOf("chapter-01.cbz/chapter/page.png", "chapter-02.cbr/page.png", "cover.png"),
                    archive.pages.map { it.name },
                )
                assertArrayEquals(expectedPng, archive.readPage(archive.pages[0], {}, ArchiveCancellationRegistrar.NONE))
                assertArrayEquals(expectedPng, archive.readPage(archive.pages[1], {}, ArchiveCancellationRegistrar.NONE))
            }
        } finally {
            outer.delete()
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
