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
            val imageEntries = entries.withIndex()
                .asSequence()
                .filterNot { it.value.flags and RAR_DIRECTORY_FLAG != 0 }
                .filter { ZipArchiveReader.isSafeImageName(it.value.name) }
                .sortedWith { a, b -> ZipArchiveReader.NATURAL_PATH_ORDER.compare(a.value.name, b.value.name) }
                .toList()
            if (imageEntries.size > ArchiveLimits.MAX_PAGES) throw IOException("Comic has too many pages.")
            if (imageEntries.isEmpty()) throw IOException("No supported image pages found.")
            rejectDuplicateImageNames(imageEntries.map { it.value.name })
            if (imageEntries.any { it.value.flags and RAR_ENCRYPTED_FLAG != 0 }) {
                throw IOException("Password-protected RAR archives are not supported yet.")
            }
            val comicPages = imageEntries.mapIndexed { index, entry -> ComicPage(entry.value.name, index) }
            val session = NativeUnrar.openSession(staged.file.absolutePath)

            return object : OpenArchive {
                override val pages = comicPages
                override val isSolid = imageEntries.any { it.value.flags and RAR_SOLID_FLAG != 0 }

                override fun readPage(
                    page: ComicPage,
                    cancellationCheck: () -> Unit,
                    registerCancellation: ArchiveCancellationRegistrar,
                ): ByteArray {
                    cancellationCheck()
                    val indexedEntry = imageEntries.getOrNull(page.position) ?: throw IOException("Page is missing.")
                    val entry = indexedEntry.value
                    if (page.name != entry.name) throw IOException("Page is missing.")
                    if (!ArchiveLimits.isPageSizeAllowed(entry.size)) throw IOException("Page is too large.")
                    val bytes = session.extract(
                        entryOrdinal = indexedEntry.index,
                        maxPageBytes = ArchiveLimits.MAX_PAGE_BYTES,
                        maxDictionaryBytes = availableNativeDictionaryBudget(),
                        cancellationCheck = cancellationCheck,
                        registerCancellation = registerCancellation,
                    )
                    if (!ArchiveLimits.isPageSizeAllowed(bytes.size.toLong())) throw IOException("Page is too large.")
                    return bytes
                }

                @Synchronized
                override fun close() {
                    try { session.close() } finally { staged.close() }
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
