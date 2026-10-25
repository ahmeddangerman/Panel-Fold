package dev.tabletcomics.archive

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException

/** Copies a SAF document to private storage because ZipFile requires a seekable file. */
class ZipArchiveReader(private val context: Context) : ArchiveReader {
    override fun readPreview(
        uri: Uri,
        cancellationCheck: () -> Unit,
        registerCancellation: ArchiveCancellationRegistrar,
    ): ByteArray? {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open this comic.")
        ZipInputStream(stream).use { zip ->
            val cancellationHandle = registerCancellation.register(zip)
            try {
                return readNaturalPreview(zip, cancellationCheck)
            } finally {
                cancellationHandle.close()
            }
        }
    }

    override fun open(
        uri: Uri,
        cancellationCheck: () -> Unit,
        registerCancellation: ArchiveCancellationRegistrar,
    ): OpenArchive {
        val staged = StagedArchive.copyFrom(
            directory = context.cacheDir,
            extension = "zip",
            input = context.contentResolver.openInputStream(uri),
            cancellationCheck = cancellationCheck,
            registerCancellation = registerCancellation,
        )
        try {
            cancellationCheck()
            val zip = ZipFile(staged.file)
            try {
                val names = mutableListOf<String>()
                val entries = zip.entries()
                var entryCount = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    entryCount++
                    if (entryCount > ArchiveLimits.MAX_ENTRIES) throw IOException("Comic has too many archive entries.")
                    if (!entry.isDirectory && isSafeImageName(entry.name)) names.add(entry.name)
                    if (names.size > ArchiveLimits.MAX_PAGES) throw IOException("Comic has too many pages.")
                }
                names.sortWith(NATURAL_PATH_ORDER)
                if (names.isEmpty()) throw IOException("No supported image pages found.")
                return object : OpenArchive {
                    override val pages = names.mapIndexed { index, name -> ComicPage(name, index) }
                    override fun readPage(
                        page: ComicPage,
                        cancellationCheck: () -> Unit,
                        registerCancellation: ArchiveCancellationRegistrar,
                    ): ByteArray {
                        cancellationCheck()
                        val entry = zip.getEntry(page.name) ?: throw IOException("Page is missing.")
                        if (entry.size >= 0 && !ArchiveLimits.isPageSizeAllowed(entry.size)) throw IOException("Page is too large.")
                        zip.getInputStream(entry).use { input ->
                            val cancellationHandle = registerCancellation.register(input)
                            try {
                                val result = ByteArrayOutputStream()
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    cancellationCheck()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (!ArchiveLimits.isPageSizeAllowed(result.size().toLong() + count)) throw IOException("Page is too large.")
                                    result.write(buffer, 0, count)
                                }
                                return result.toByteArray()
                            } finally {
                                cancellationHandle.close()
                            }
                        }
                    }
                    override fun close() {
                        try { zip.close() } finally { staged.close() }
                    }
                }
            } catch (error: Exception) {
                zip.close()
                throw error
            }
        } catch (error: Throwable) {
            staged.close()
            throw error
        }
    }

    companion object {
        internal fun readBounded(input: InputStream, limit: Long, cancellationCheck: () -> Unit = {}): ByteArray? {
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                cancellationCheck()
                val remaining = limit - result.size().toLong()
                val requestSize = minOf(buffer.size.toLong(), remaining.coerceAtLeast(0) + 1).toInt()
                val count = input.read(buffer, 0, requestSize)
                if (count < 0) return result.toByteArray()
                if (count.toLong() > remaining) return null
                result.write(buffer, 0, count)
            }
        }

        private fun drainBounded(input: InputStream, limit: Long, cancellationCheck: () -> Unit): Long? {
            var consumed = 0L
            val buffer = ByteArray(16 * 1024)
            while (true) {
                cancellationCheck()
                val remaining = limit - consumed
                val requestSize = minOf(buffer.size.toLong(), remaining.coerceAtLeast(0) + 1).toInt()
                val count = input.read(buffer, 0, requestSize)
                if (count < 0) return consumed
                if (count.toLong() > remaining) return null
                consumed += count
            }
        }

        internal fun isSafeImageName(name: String): Boolean {
            val parts = name.replace('\\', '/').split('/')
            if (name.startsWith('/') || parts.any { it.isEmpty() || it == ".." || it.startsWith('.') || it == "__MACOSX" }) return false
            return name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp")
        }

        internal fun readNaturalPreview(zip: ZipInputStream, cancellationCheck: () -> Unit = {}): ByteArray? {
            var selectedName: String? = null
            var selectedBytes: ByteArray? = null
            var scannedBytes = 0L
            while (true) {
                cancellationCheck()
                val entry = zip.nextEntry ?: return selectedBytes
                val remaining = ArchiveLimits.MAX_PREVIEW_BYTES - scannedBytes
                if (remaining <= 0) return selectedBytes
                if (entry.size > remaining) return null

                val isPage = !entry.isDirectory && isSafeImageName(entry.name)
                if (isPage && isEarlierNaturalCover(entry.name, selectedName)) {
                    val bytes = readBounded(zip, remaining, cancellationCheck) ?: return null
                    scannedBytes += bytes.size
                    selectedName = entry.name
                    selectedBytes = bytes
                } else {
                    val drained = drainBounded(zip, remaining, cancellationCheck) ?: return null
                    scannedBytes += drained
                }
            }
        }

        internal fun isEarlierNaturalCover(candidate: String, current: String?): Boolean =
            current == null || NATURAL_PATH_ORDER.compare(candidate, current) < 0

        internal val NATURAL_PATH_ORDER = Comparator<String> { a, b ->
            val left = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
            val right = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i]; val y = right[i]
                val comparison = if (x.first().isDigit() && y.first().isDigit()) {
                    val xn = x.trimStart('0').ifEmpty { "0" }
                    val yn = y.trimStart('0').ifEmpty { "0" }
                    compareValues(xn.length, yn.length).takeIf { it != 0 } ?: xn.compareTo(yn)
                } else x.compareTo(y)
                if (comparison != 0) return@Comparator comparison
            }
            compareValues(left.size, right.size)
        }
    }
}
