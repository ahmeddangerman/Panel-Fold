package dev.tabletcomics.archive

import android.app.ActivityManager
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE
import android.net.Uri
import android.os.StatFs
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Opens ZIP/CBZ and RAR/CBR archives, recursively indexing supported nested archives. */
class NestedArchiveReader(
    private val context: Context,
    private val expectedFormat: ArchiveFormat? = null,
) : ArchiveReader {
    override fun readPreview(
        uri: Uri,
        cancellationCheck: () -> Unit,
        registerCancellation: ArchiveCancellationRegistrar,
    ): ByteArray? {
        val format = expectedFormat ?: detectArchiveFormat(context, uri, null, cancellationCheck, registerCancellation)
        if (format == ArchiveFormat.ZIP) {
            ZipArchiveReader(context).readPreview(uri, cancellationCheck, registerCancellation)?.let { return it }
        } else {
            try {
                RarArchiveReader(context).readPreview(uri, cancellationCheck, registerCancellation)?.let { return it }
            } catch (failure: IOException) {
                if (failure.message != "No supported image pages found.") throw failure
            }
        }
        return open(uri, cancellationCheck, registerCancellation).use { archive ->
            archive.pages.firstOrNull()?.let { archive.readPage(it, cancellationCheck, registerCancellation) }
        }
    }

    override fun open(
        uri: Uri,
        cancellationCheck: () -> Unit,
        registerCancellation: ArchiveCancellationRegistrar,
    ): OpenArchive {
        val format = expectedFormat ?: detectArchiveFormat(context, uri, null, cancellationCheck, registerCancellation)
        val root = StagedArchive.copyFrom(
            directory = context.cacheDir,
            extension = format.fileExtension,
            input = context.contentResolver.openInputStream(uri),
            cancellationCheck = cancellationCheck,
            registerCancellation = registerCancellation,
        )
        val staged = mutableListOf(root)
        val pages = mutableListOf<PageSource>()
        val scan = ScanBudget()
        try {
            indexArchive(
                archiveFile = root.file,
                format = format,
                nestedDepth = 0,
                pathPrefix = "",
                stagedArchives = staged,
                pageSources = pages,
                budget = scan,
                cancellationCheck = cancellationCheck,
                registerCancellation = registerCancellation,
            )
            if (pages.isEmpty()) throw IOException("No supported image pages found.")
            val ordered = pages.sortedWith { left, right ->
                ZipArchiveReader.NATURAL_PATH_ORDER.compare(left.displayName, right.displayName)
            }
            rejectDuplicateImageNames(ordered.map { it.displayName })
            return RecursiveOpenArchive(context, staged, ordered)
        } catch (failure: Throwable) {
            staged.asReversed().forEach { runCatching { it.close() } }
            throw failure
        }
    }

    private fun indexArchive(
        archiveFile: File,
        format: ArchiveFormat,
        nestedDepth: Int,
        pathPrefix: String,
        stagedArchives: MutableList<StagedArchive>,
        pageSources: MutableList<PageSource>,
        budget: ScanBudget,
        cancellationCheck: () -> Unit,
        registerCancellation: ArchiveCancellationRegistrar,
    ) {
        cancellationCheck()
        openContainer(archiveFile, format).use { container ->
            val entries = container.entries
            if (entries.size > ArchiveLimits.MAX_ENTRIES)
                throw IOException("Comic has too many archive entries.")
            for (entry in entries) {
                cancellationCheck()
                if (++budget.entryCount > ArchiveLimits.MAX_TOTAL_ARCHIVE_ENTRIES)
                    throw IOException("Nested comic has too many archive entries.")
                if (entry.isDirectory) continue
                val normalizedName = normalizeArchivePath(entry.name) ?: continue
                val fullName = if (pathPrefix.isEmpty()) normalizedName else "$pathPrefix/$normalizedName"
                if (ZipArchiveReader.isSafeImageName(normalizedName)) {
                    if (pageSources.size >= ArchiveLimits.MAX_PAGES)
                        throw IOException("Comic has too many pages.")
                    if (entry.size >= 0 && !ArchiveLimits.isPageSizeAllowed(entry.size))
                        throw IOException("Page is too large.")
                    pageSources += PageSource(fullName, archiveFile, format, entry)
                    continue
                }
                if (!isSupportedNestedArchiveName(normalizedName)) continue
                if (!ArchiveLimits.canDescendIntoNestedArchive(nestedDepth))
                    throw IOException("This comic contains archives nested too deeply.")
                val remainingBytes = ArchiveLimits.MAX_NESTED_ARCHIVE_BYTES - budget.nestedArchiveBytes
                if (remainingBytes <= 0 || (entry.size >= 0 && entry.size > remainingBytes))
                    throw IOException("Nested archives exceed the app's safe extraction limit.")
                val child = container.stageNestedArchive(
                    entry = entry,
                    directory = context.cacheDir,
                    maxBytes = remainingBytes,
                    cancellationCheck = cancellationCheck,
                    registerCancellation = registerCancellation,
                    dictionaryBudget = nativeDictionaryBudget(),
                )
                stagedArchives += child
                budget.nestedArchiveBytes += child.file.length()
                val childFormat = try {
                    FileInputStream(child.file).use { input ->
                        val prefix = readArchiveDetectionPrefix(input, cancellationCheck)
                        detectArchiveFormat(prefix, normalizedName.substringAfterLast('.', ""))
                    }
                } catch (failure: IOException) {
                    throw IOException("Could not read nested archive '${normalizedName.take(120)}': ${failure.message}", failure)
                }
                indexArchive(
                    archiveFile = child.file,
                    format = childFormat,
                    nestedDepth = nestedDepth + 1,
                    pathPrefix = fullName,
                    stagedArchives = stagedArchives,
                    pageSources = pageSources,
                    budget = budget,
                    cancellationCheck = cancellationCheck,
                    registerCancellation = registerCancellation,
                )
            }
        }
    }

    private fun openContainer(file: File, format: ArchiveFormat): ArchiveContainer = when (format) {
        ArchiveFormat.ZIP -> ZipContainer(file)
        ArchiveFormat.RAR -> RarContainer(context, file)
    }

    private fun nativeDictionaryBudget(): Long {
        val manager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        return ArchiveLimits.nativeRarDictionaryBudget(memory.availMem)
    }

    private data class ScanBudget(var entryCount: Int = 0, var nestedArchiveBytes: Long = 0)

    private data class ArchiveEntryRef(
        val name: String,
        val size: Long,
        val ordinal: Int,
        val isDirectory: Boolean,
        val encrypted: Boolean = false,
        val zipEntry: ZipEntry? = null,
    )

    private data class PageSource(
        val displayName: String,
        val archiveFile: File,
        val format: ArchiveFormat,
        val entry: ArchiveEntryRef,
    )

    private interface ArchiveContainer : Closeable {
        val entries: List<ArchiveEntryRef>
        fun readPage(
            entry: ArchiveEntryRef,
            cancellationCheck: () -> Unit,
            registerCancellation: ArchiveCancellationRegistrar,
        ): ByteArray
        fun stageNestedArchive(
            entry: ArchiveEntryRef,
            directory: File,
            maxBytes: Long,
            cancellationCheck: () -> Unit,
            registerCancellation: ArchiveCancellationRegistrar,
            dictionaryBudget: Long,
        ): StagedArchive
    }

    private class ZipContainer(private val file: File) : ArchiveContainer {
        private val zip = ZipFile(file)
        override val entries: List<ArchiveEntryRef> = buildList {
            val enumeration = zip.entries()
            var ordinal = 0
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                add(ArchiveEntryRef(entry.name, entry.size, ordinal++, entry.isDirectory, zipEntry = entry))
            }
        }

        override fun readPage(
            entry: ArchiveEntryRef,
            cancellationCheck: () -> Unit,
            registerCancellation: ArchiveCancellationRegistrar,
        ): ByteArray {
            val zipEntry = entries.getOrNull(entry.ordinal)?.zipEntry ?: throw IOException("Page is missing.")
            if (entry.size >= 0 && !ArchiveLimits.isPageSizeAllowed(entry.size)) throw IOException("Page is too large.")
            zip.getInputStream(zipEntry).use { input ->
                val cancellation = registerCancellation.register(input)
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
                    cancellation.close()
                }
            }
        }

        override fun stageNestedArchive(
            entry: ArchiveEntryRef,
            directory: File,
            maxBytes: Long,
            cancellationCheck: () -> Unit,
            registerCancellation: ArchiveCancellationRegistrar,
            dictionaryBudget: Long,
        ): StagedArchive {
            val zipEntry = entries.getOrNull(entry.ordinal)?.zipEntry ?: throw IOException("Nested archive is missing.")
            return StagedArchive.copyFrom(
                directory = directory,
                extension = nestedExtension(entry.name),
                input = zip.getInputStream(zipEntry),
                cancellationCheck = cancellationCheck,
                registerCancellation = registerCancellation,
                maxBytes = maxBytes,
                sizeLimitMessage = "Nested archives exceed the app's safe extraction limit.",
            )
        }

        override fun close() = zip.close()
    }

    private class RarContainer(private val context: Context, private val file: File) : ArchiveContainer {
        private val rarEntries = NativeUnrar.list(file.absolutePath)
        private val session = NativeUnrar.openSession(file.absolutePath)
        override val entries: List<ArchiveEntryRef> = rarEntries.mapIndexed { ordinal, entry ->
            ArchiveEntryRef(
                name = entry.name,
                size = entry.size,
                ordinal = ordinal,
                isDirectory = entry.flags and RAR_DIRECTORY_FLAG != 0,
                encrypted = entry.flags and RAR_ENCRYPTED_FLAG != 0,
            )
        }

        override fun readPage(
            entry: ArchiveEntryRef,
            cancellationCheck: () -> Unit,
            registerCancellation: ArchiveCancellationRegistrar,
        ): ByteArray {
            if (entry.encrypted) throw IOException("Password-protected RAR archives are not supported yet.")
            if (!ArchiveLimits.isPageSizeAllowed(entry.size)) throw IOException("Page is too large.")
            return session.extract(
                entryOrdinal = entry.ordinal,
                maxPageBytes = ArchiveLimits.MAX_PAGE_BYTES,
                maxDictionaryBytes = nativeDictionaryBudget(),
                cancellationCheck = cancellationCheck,
                registerCancellation = registerCancellation,
            )
        }

        override fun stageNestedArchive(
            entry: ArchiveEntryRef,
            directory: File,
            maxBytes: Long,
            cancellationCheck: () -> Unit,
            registerCancellation: ArchiveCancellationRegistrar,
            dictionaryBudget: Long,
        ): StagedArchive {
            if (entry.encrypted) throw IOException("Password-protected RAR archives are not supported yet.")
            val availableForExtraction = (StatFs(directory.path).availableBytes - ArchiveLimits.MIN_FREE_BYTES).coerceAtLeast(0)
            val outputLimit = minOf(maxBytes, availableForExtraction)
            if (entry.size >= 0 && entry.size > outputLimit)
                throw IOException("Nested archives exceed the app's safe extraction limit.")
            val staged = StagedArchive.create(directory, nestedExtension(entry.name))
            try {
                session.extractToFile(
                    entryOrdinal = entry.ordinal,
                    maxOutputBytes = outputLimit,
                    maxDictionaryBytes = dictionaryBudget,
                    target = staged.file,
                    cancellationCheck = cancellationCheck,
                    registerCancellation = registerCancellation,
                )
                return staged
            } catch (failure: Throwable) {
                staged.close()
                throw failure
            }
        }

        override fun close() = session.close()

        private fun nativeDictionaryBudget(): Long {
            val manager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val memory = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(memory)
            return ArchiveLimits.nativeRarDictionaryBudget(memory.availMem)
        }

        private companion object {
            const val RAR_ENCRYPTED_FLAG = 0x04
            const val RAR_DIRECTORY_FLAG = 0x20
        }
    }

    private class RecursiveOpenArchive(
        private val context: Context,
        private val stagedArchives: List<StagedArchive>,
        private val sources: List<PageSource>,
    ) : OpenArchive {
        private var closed = false
        private var activeFile: File? = null
        private var activeFormat: ArchiveFormat? = null
        private var activeContainer: ArchiveContainer? = null
        override val pages: List<ComicPage> = sources.mapIndexed { index, source -> ComicPage(source.displayName, index) }

        @Synchronized
        override fun readPage(
            page: ComicPage,
            cancellationCheck: () -> Unit,
            registerCancellation: ArchiveCancellationRegistrar,
        ): ByteArray {
            if (closed) throw IOException("This comic archive is closed.")
            cancellationCheck()
            val source = sources.getOrNull(page.position)?.takeIf { pages.getOrNull(page.position) == page }
                ?: throw IOException("Page is missing.")
            if (activeFile != source.archiveFile || activeFormat != source.format) {
                activeContainer?.close()
                activeContainer = null
                activeFile = null
                activeFormat = null
                val opened = when (source.format) {
                    ArchiveFormat.ZIP -> ZipContainer(source.archiveFile)
                    ArchiveFormat.RAR -> RarContainer(context, source.archiveFile)
                }
                activeContainer = opened
                activeFile = source.archiveFile
                activeFormat = source.format
            }
            return checkNotNull(activeContainer).readPage(source.entry, cancellationCheck, registerCancellation)
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            runCatching { activeContainer?.close() }
            activeContainer = null
            activeFile = null
            stagedArchives.asReversed().forEach { runCatching { it.close() } }
        }
    }

    companion object {
        private const val RAR_ENCRYPTED_FLAG = 0x04
        private const val RAR_DIRECTORY_FLAG = 0x20
        private val ARCHIVE_EXTENSIONS = setOf("zip", "cbz", "rar", "cbr")

        internal fun isSupportedNestedArchiveName(name: String): Boolean {
            val normalized = normalizeArchivePath(name) ?: return false
            return normalized.substringAfterLast('.', "").lowercase() in ARCHIVE_EXTENSIONS
        }

        private fun normalizeArchivePath(name: String): String? {
            val normalized = name.replace('\\', '/')
            if (normalized.startsWith('/')) return null
            val parts = normalized.split('/')
            if (parts.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." || it.startsWith('.') || it == "__MACOSX" }) return null
            return parts.joinToString("/")
        }

        private fun nestedExtension(name: String): String =
            if (name.substringAfterLast('.', "").lowercase() in setOf("rar", "cbr")) "rar" else "zip"
    }
}

private val ArchiveFormat.fileExtension: String
    get() = if (this == ArchiveFormat.RAR) "rar" else "zip"
