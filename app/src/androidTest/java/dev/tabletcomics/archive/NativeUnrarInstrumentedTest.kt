package dev.tabletcomics.archive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeUnrarInstrumentedTest {
    @Test
    fun nativeUnrarListsAndExtractsRar4AndRar5Pages() {
        val context = InstrumentationRegistry.getInstrumentation().context
        for (fixture in listOf("stored-rar4.cbr", "stored-rar5.cbr")) {
            val archive = File(context.cacheDir, fixture).apply {
                context.assets.open(fixture).use { input -> outputStream().use { output -> input.copyTo(output) } }
            }
            try {
                val entry = NativeUnrar.list(archive.absolutePath).single()
                assertEquals("page.png", entry.name)
                val image = NativeUnrar.extract(
                    archive.absolutePath,
                    entry.name,
                    maxPageBytes = 24L * 1024 * 1024,
                    maxDictionaryBytes = 256L * 1024 * 1024,
                    cancellationCheck = {},
                )
                assertArrayEquals(context.assets.open("page.png").use { it.readBytes() }, image)
            } finally {
                archive.delete()
            }
        }
    }
}
