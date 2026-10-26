package dev.tabletcomics.archive

import java.io.IOException
import java.io.Closeable

internal data class NativeRarEntry(val name: String, val size: Long, val flags: Int)

internal class RarDictionaryLimitException(
    val requiredBytes: Long,
    val allowedBytes: Long,
) : IOException(
    "This RAR page needs a ${requiredBytes.toMiB()} MiB dictionary, but the current safe memory budget is ${allowedBytes.toMiB()} MiB. Close other apps and retry.",
)

internal object NativeUnrar {
    init {
        System.loadLibrary("tablet_unrar")
    }

    fun list(archivePath: String): Array<NativeRarEntry> = nativeList(archivePath)

    /** Keeps a serialized extraction cursor and solid dictionary for this archive. */
    fun openSession(archivePath: String): Session = Session(nativeOpenSession(archivePath))

    class Session internal constructor(private val handle: Long) : Closeable {
        @Volatile private var closed = false
        private val readLock = Any()

        fun extract(
            entryOrdinal: Int,
            maxPageBytes: Long,
            maxDictionaryBytes: Long,
            cancellationCheck: () -> Unit,
            registerCancellation: ArchiveCancellationRegistrar = ArchiveCancellationRegistrar.NONE,
        ): ByteArray = synchronized(readLock) {
            if (closed) throw IOException("This RAR archive is closed.")
            cancellationCheck()
            val cancellationHandle = registerCancellation.register(Closeable { nativeCancelSession(handle) })
            try {
                cancellationCheck()
                nativeReadSession(handle, entryOrdinal, maxPageBytes, maxDictionaryBytes, cancellationCheck)
            } finally {
                cancellationHandle.close()
            }
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            nativeCloseSession(handle)
        }
    }

    fun extract(
        archivePath: String,
        entryName: String,
        maxPageBytes: Long,
        maxDictionaryBytes: Long,
        cancellationCheck: () -> Unit,
    ): ByteArray = nativeExtract(
        archivePath,
        entryName,
        maxPageBytes,
        maxDictionaryBytes,
        cancellationCheck,
    )

    private external fun nativeList(archivePath: String): Array<NativeRarEntry>

    private external fun nativeOpenSession(archivePath: String): Long
    private external fun nativeReadSession(
        handle: Long,
        entryOrdinal: Int,
        maxPageBytes: Long,
        maxDictionaryBytes: Long,
        cancellationCheck: () -> Unit,
    ): ByteArray
    private external fun nativeCancelSession(handle: Long)
    private external fun nativeCloseSession(handle: Long)

    private external fun nativeExtract(
        archivePath: String,
        entryName: String,
        maxPageBytes: Long,
        maxDictionaryBytes: Long,
        cancellationCheck: () -> Unit,
    ): ByteArray
}

private fun Long.toMiB(): Long = (this + 1024L * 1024 - 1) / (1024L * 1024)
