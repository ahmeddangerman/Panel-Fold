package dev.tabletcomics.archive

import java.io.IOException

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

    private external fun nativeExtract(
        archivePath: String,
        entryName: String,
        maxPageBytes: Long,
        maxDictionaryBytes: Long,
        cancellationCheck: () -> Unit,
    ): ByteArray
}

private fun Long.toMiB(): Long = (this + 1024L * 1024 - 1) / (1024L * 1024)
