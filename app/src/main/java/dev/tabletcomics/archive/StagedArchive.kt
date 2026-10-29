package dev.tabletcomics.archive

import android.os.StatFs
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job

/** Registers blocking archive resources so a cancelled coroutine can close them immediately. */
fun interface ArchiveCancellationRegistrar {
    fun register(resource: Closeable): Closeable

    companion object {
        val NONE = ArchiveCancellationRegistrar { Closeable {} }
    }
}

@OptIn(InternalCoroutinesApi::class)
fun archiveCancellationRegistrar(job: Job): ArchiveCancellationRegistrar = ArchiveCancellationRegistrar { resource ->
    val handle = job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause is CancellationException) runCatching { resource.close() }
    }
    Closeable { handle.dispose() }
}

/** Owns one private staged archive and removes it when the reader releases it. */
class StagedArchive private constructor(val file: File) : Closeable {
    private var closed = false

    init {
        synchronized(activePaths) { activePaths.add(file.canonicalPath) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        synchronized(activePaths) { activePaths.remove(file.canonicalPath) }
        file.delete()
    }

    companion object {
        private const val PREFIX = "comic-"
        private const val DEFAULT_STALE_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        private val activePaths = mutableSetOf<String>()

        fun create(directory: File, extension: String): StagedArchive {
            require(extension in setOf("zip", "rar")) { "Unsupported staged archive extension." }
            if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create comic cache directory.")
            return StagedArchive(File.createTempFile(PREFIX, ".$extension", directory))
        }

        internal fun copyFrom(
            directory: File,
            extension: String,
            input: InputStream?,
            cancellationCheck: () -> Unit = {},
            registerCancellation: ArchiveCancellationRegistrar = ArchiveCancellationRegistrar.NONE,
            maxBytes: Long = ArchiveLimits.MAX_ARCHIVE_BYTES,
            minFreeBytes: Long = ArchiveLimits.MIN_FREE_BYTES,
            sizeLimitMessage: String = "Comic archive exceeds the 8 GiB limit.",
            availableBytes: () -> Long = { StatFs(directory.path).availableBytes },
            outputFactory: (File) -> OutputStream = ::FileOutputStream,
        ): StagedArchive {
            val staged = try {
                create(directory, extension)
            } catch (failure: Throwable) {
                runCatching { input?.close() }
                throw failure
            }
            try {
                val source = input ?: throw IOException("Cannot open this comic. Select its folder again.")
                source.use {
                    val cancellationHandle = registerCancellation.register(source)
                    try {
                        outputFactory(staged.file).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                cancellationCheck()
                                val count = source.read(buffer)
                                if (count < 0) break
                                if (total > maxBytes - count) throw IOException(sizeLimitMessage)
                                if (availableBytes() - count < minFreeBytes) throw IOException("Not enough free storage to open this comic.")
                                output.write(buffer, 0, count)
                                total += count
                            }
                            cancellationCheck()
                        }
                    } finally {
                        cancellationHandle.close()
                    }
                }
                return staged
            } catch (failure: Throwable) {
                staged.close()
                throw failure
            }
        }

        fun cleanupExpired(
            directory: File,
            nowMillis: Long = System.currentTimeMillis(),
            maxAgeMillis: Long = DEFAULT_STALE_AGE_MILLIS,
        ): Int {
            if (!directory.isDirectory || maxAgeMillis < 0) return 0
            var deleted = 0
            directory.listFiles().orEmpty().forEach { file ->
                val name = file.name
                if (!file.isFile || !name.startsWith(PREFIX) ||
                    !(name.endsWith(".zip") || name.endsWith(".rar")) ||
                    nowMillis - file.lastModified() < maxAgeMillis
                ) return@forEach
                synchronized(activePaths) {
                    if (file.canonicalPath !in activePaths && file.delete()) deleted++
                }
            }
            return deleted
        }
    }
}
