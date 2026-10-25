package dev.tabletcomics.archive

import android.app.ActivityManager
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE
import android.net.Uri
import java.io.IOException

/** RAR/CBR adapter backed by RARLab's native UnRAR engine. */
class RarArchiveReader(private val context: Context) : ArchiveReader {
    override fun readPreview(
        uri: Uri,
        cancellationCheck: () -> Unit,
        registerCancellation: ArchiveCancellationRegistrar,
    ): ByteArray? = open(uri, cancellationCheck, registerCancellation).use { archive ->
        archive.readPage(archive.pages.first(), cancellationCheck, registerCancellation)
    }

    override fun open(
        uri: Uri,
        cancellationCheck: () -> Unit,
        registerCancellation: ArchiveCancellationRegistrar,
    ): OpenArchive {
        val staged = StagedArchive.copyFrom(
            directory = context.cacheDir,
            extension = "rar",
            input = context.contentResolver.openInputStream(uri),
            cancellationCheck = cancellationCheck,
            registerCancellation = registerCancellation,
        )
        try {
            cancellationCheck()
            val entries = NativeUnrar.list(staged.file.absolutePath)
            if (entries.size > ArchiveLimits.MAX_ENTRIES) throw IOException("Comic has too many archive entries.")
            val pages = entries
                .asSequence()
                .filterNot { it.flags and RAR_DIRECTORY_FLAG != 0 }
                .filter { ZipArchiveReader.isSafeImageName(it.name) }
                .sortedWith { a, b -> ZipArchiveReader.NATURAL_PATH_ORDER.compare(a.name, b.name) }
                .toList()
            if (pages.size > ArchiveLimits.MAX_PAGES) throw IOException("Comic has too many pages.")
            if (pages.isEmpty()) throw IOException("No supported image pages found.")
            if (pages.any { it.flags and RAR_ENCRYPTED_FLAG != 0 }) {
                throw IOException("Password-protected RAR archives are not supported yet.")
            }
            val comicPages = pages.mapIndexed { index, entry -> ComicPage(entry.name, index) }

            return object : OpenArchive {
                override val pages = comicPages
                override val isSolid = pages.any { it.flags and RAR_SOLID_FLAG != 0 }

                override fun readPage(
                    page: ComicPage,
                    cancellationCheck: () -> Unit,
                    registerCancellation: ArchiveCancellationRegistrar,
                ): ByteArray {
                    cancellationCheck()
                    val entry = pages.getOrNull(page.position) ?: throw IOException("Page is missing.")
                    if (!ArchiveLimits.isPageSizeAllowed(entry.size)) throw IOException("Page is too large.")
                    val bytes = NativeUnrar.extract(
                        archivePath = staged.file.absolutePath,
                        entryName = entry.name,
                        maxPageBytes = ArchiveLimits.MAX_PAGE_BYTES,
                        maxDictionaryBytes = availableNativeDictionaryBudget(),
                        cancellationCheck = cancellationCheck,
                    )
                    if (!ArchiveLimits.isPageSizeAllowed(bytes.size.toLong())) throw IOException("Page is too large.")
                    return bytes
                }

                override fun close() {
                    staged.close()
                }
            }
        } catch (error: Throwable) {
            staged.close()
            throw error
        }
    }

    private fun availableNativeDictionaryBudget(): Long {
        val manager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        return ArchiveLimits.nativeRarDictionaryBudget(memory.availMem)
    }

    private companion object {
        const val RAR_ENCRYPTED_FLAG = 0x04
        const val RAR_SOLID_FLAG = 0x10
        const val RAR_DIRECTORY_FLAG = 0x20
    }
}
