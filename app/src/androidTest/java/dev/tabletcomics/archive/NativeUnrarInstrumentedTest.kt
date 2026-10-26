package dev.tabletcomics.archive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class NativeUnrarInstrumentedTest {
    @Test
    fun compressedRar4AndRar5EnforceSmallDictionaryBudget() {
        for (fixture in listOf("compressed-rar4.rar", "compressed-rar5.rar")) withNativeFixture(fixture) { archive ->
            val entry = NativeUnrar.list(archive.absolutePath).first()
            NativeUnrar.openSession(archive.absolutePath).use { session ->
                val rejected = assertThrows(RarDictionaryLimitException::class.java) {
                    session.extract(0, PAGE_LIMIT, 64 * 1024, {})
                }
                assertEquals(64 * 1024L, rejected.allowedBytes)
                assertTrue(rejected.requiredBytes >= 256 * 1024)
                assertEquals(entry.size, session.extract(0, PAGE_LIMIT, DICTIONARY_LIMIT, {}).size.toLong())
            }
        }
    }

    @Test
    fun solidRar4AndRar5ReuseTheForwardCursorAndRecoverFromCancellation() {
        for (fixture in listOf("solid-rar4.rar", "solid-rar5.rar")) withNativeFixture(fixture) { archive ->
            val last = NativeUnrar.list(archive.absolutePath).lastIndex
            var forwardChecks = 0
            val expected = NativeUnrar.openSession(archive.absolutePath).use { session ->
                session.extract(last - 1, PAGE_LIMIT, DICTIONARY_LIMIT, {})
                session.extract(last, PAGE_LIMIT, DICTIONARY_LIMIT, { forwardChecks++ })
            }
            var replayChecks = 0
            NativeUnrar.openSession(archive.absolutePath).use { session ->
                assertArrayEquals(expected, session.extract(last, PAGE_LIMIT, DICTIONARY_LIMIT, { replayChecks++ }))
                assertTrue(forwardChecks < replayChecks)
            }
            NativeUnrar.openSession(archive.absolutePath).use { session ->
                var checks = 0
                assertThrows(CancellationException::class.java) {
                    session.extract(last, PAGE_LIMIT, DICTIONARY_LIMIT, {
                        if (++checks == 4) throw CancellationException("cancel during solid skip")
                    })
                }
                assertEquals(4, checks)
                assertArrayEquals(expected, session.extract(last, PAGE_LIMIT, DICTIONARY_LIMIT, {}))
            }
        }
    }

    @Test
    fun nameBasedCompatibilityExtractionRejectsAmbiguousDuplicatePages() = withNativeFixture("duplicate.rar") { archive ->
        val error = assertThrows(java.io.IOException::class.java) {
            NativeUnrar.extract(archive.absolutePath, "page.png", PAGE_LIMIT, DICTIONARY_LIMIT, {})
        }
        assertTrue(error.message.orEmpty().contains("duplicate page names"))
    }

    private fun withNativeFixture(name: String, block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().context
        val archive = File.createTempFile("unrar-test-", ".rar", context.cacheDir)
        try {
            context.assets.open("native/$name").use { input -> archive.outputStream().use { output -> input.copyTo(output) } }
            block(archive)
        } finally {
            archive.delete()
        }
    }

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

    private companion object {
        const val PAGE_LIMIT = 24L * 1024 * 1024
        const val DICTIONARY_LIMIT = 256L * 1024 * 1024
    }
}
