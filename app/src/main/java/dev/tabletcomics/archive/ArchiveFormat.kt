package dev.tabletcomics.archive

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException

enum class ArchiveFormat { ZIP, RAR }

class ArchiveFormatException(message: String) : IOException(message)

fun detectArchiveFormat(
    context: Context,
    uri: Uri,
    extension: String?,
    cancellationCheck: () -> Unit,
    registerCancellation: ArchiveCancellationRegistrar,
): ArchiveFormat {
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open this comic. Select its folder again.")
    input.use { source ->
        val cancellationHandle = registerCancellation.register(source)
        try {
            val prefix = readArchiveDetectionPrefix(source, cancellationCheck)
            cancellationCheck()
            return detectArchiveFormat(prefix, extension)
        } finally {
            cancellationHandle.close()
        }
    }
}

/** Detects the container from its bytes; the extension is used only to catch obvious mismatches. */
fun detectArchiveFormat(prefix: ByteArray, extension: String?): ArchiveFormat {
    val format = when {
        prefix.containsMarker(byteArrayOf(0x50, 0x4b, 0x03, 0x04), MAX_SFX_PREFIX_BYTES) ||
            prefix.containsMarker(byteArrayOf(0x50, 0x4b, 0x05, 0x06), MAX_SFX_PREFIX_BYTES) ||
            prefix.containsMarker(byteArrayOf(0x50, 0x4b, 0x07, 0x08), MAX_SFX_PREFIX_BYTES) ||
            prefix.containsMarker(byteArrayOf(0x50, 0x4b, 0x01, 0x02), MAX_SFX_PREFIX_BYTES) -> ArchiveFormat.ZIP
        prefix.containsMarker(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00), MAX_SFX_PREFIX_BYTES) ||
            prefix.containsMarker(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00), MAX_SFX_PREFIX_BYTES) -> ArchiveFormat.RAR
        else -> throw ArchiveFormatException("This file is not a supported ZIP/CBZ or RAR/CBR comic archive.")
    }
    val hint = when (extension?.substringAfterLast('.', extension)?.lowercase()) {
        "zip", "cbz" -> ArchiveFormat.ZIP
        "rar", "cbr" -> ArchiveFormat.RAR
        else -> null
    }
    if (hint != null && hint != format) {
        throw ArchiveFormatException("The file contents do not match its .$extension extension.")
    }
    return format
}

internal fun readArchiveDetectionPrefix(
    input: InputStream,
    cancellationCheck: () -> Unit = {},
): ByteArray {
    val maxPrefixBytes = MAX_SFX_PREFIX_BYTES + MAX_SIGNATURE_LENGTH
    val prefix = ByteArrayOutputStream()
    val buffer = ByteArray(32 * 1024)
    var carry = ByteArray(0)
    var absoluteOffset = 0
    while (prefix.size() < maxPrefixBytes) {
        cancellationCheck()
        val firstHeaderRemaining = (MAX_SIGNATURE_LENGTH - prefix.size()).coerceAtLeast(0)
        val requestSize = if (firstHeaderRemaining > 0) {
            minOf(firstHeaderRemaining, maxPrefixBytes - prefix.size())
        } else {
            minOf(buffer.size, maxPrefixBytes - prefix.size())
        }
        val count = input.read(buffer, 0, requestSize)
        if (count < 0) break
        if (count == 0) {
            val one = input.read()
            if (one < 0) break
            buffer[0] = one.toByte()
            prefix.write(buffer, 0, 1)
            val scanWindow = carry + buffer[0]
            if (containsSupportedMarker(scanWindow, absoluteOffset - carry.size)) return prefix.toByteArray()
            carry = scanWindow.takeLast(MAX_SIGNATURE_LENGTH - 1).toByteArray()
            absoluteOffset++
            continue
        }

        prefix.write(buffer, 0, count)
        val scanWindow = ByteArray(carry.size + count)
        carry.copyInto(scanWindow)
        buffer.copyInto(scanWindow, carry.size, 0, count)
        if (containsSupportedMarker(scanWindow, absoluteOffset - carry.size)) return prefix.toByteArray()
        carry = scanWindow.takeLast(MAX_SIGNATURE_LENGTH - 1).toByteArray()
        absoluteOffset += count
    }
    return prefix.toByteArray()
}

private fun containsSupportedMarker(bytes: ByteArray, absoluteOffset: Int): Boolean {
    val markers = listOf(
        byteArrayOf(0x50, 0x4b, 0x03, 0x04),
        byteArrayOf(0x50, 0x4b, 0x05, 0x06),
        byteArrayOf(0x50, 0x4b, 0x07, 0x08),
        byteArrayOf(0x50, 0x4b, 0x01, 0x02),
        byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00),
        byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00),
    )
    val lastOffset = minOf(bytes.size - 4, MAX_SFX_PREFIX_BYTES - absoluteOffset)
    if (lastOffset < 0) return false
    for (offset in 0..lastOffset) {
        val globalOffset = absoluteOffset + offset
        if (globalOffset !in 0..MAX_SFX_PREFIX_BYTES) continue
        if (markers.any { marker ->
                offset + marker.size <= bytes.size && marker.indices.all { bytes[offset + it] == marker[it] }
            }) return true
    }
    return false
}

private fun ByteArray.containsMarker(marker: ByteArray, maxOffset: Int): Boolean {
    if (size < marker.size) return false
    val lastOffset = minOf(size - marker.size, maxOffset)
    for (offset in 0..lastOffset) {
        var matches = true
        for (index in marker.indices) {
            if (this[offset + index] != marker[index]) {
                matches = false
                break
            }
        }
        if (matches) return true
    }
    return false
}

private const val MAX_SFX_PREFIX_BYTES = 4 * 1024 * 1024
private const val MAX_SIGNATURE_LENGTH = 8

object ArchiveLimits {
    const val MAX_PAGE_BYTES = 24L * 1024 * 1024
    const val MAX_PREVIEW_BYTES = 20L * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 8L * 1024 * 1024 * 1024
    const val MIN_FREE_BYTES = 1024L * 1024 * 1024
    const val MAX_ENTRIES = 20_000
    const val MAX_PAGES = 20_000
    const val MAX_IMAGE_PIXELS = 100_000_000L
    const val MAX_NATIVE_RAR_DICTIONARY_BYTES = 1024L * 1024 * 1024

    fun isPageSizeAllowed(sizeBytes: Long): Boolean = sizeBytes in 0..MAX_PAGE_BYTES

    fun isArchiveSizeAllowed(sizeBytes: Long): Boolean = sizeBytes in 0..MAX_ARCHIVE_BYTES

    fun isImageSizeAllowed(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height.toLong() <= MAX_IMAGE_PIXELS

    fun nativeRarDictionaryBudget(availableMemoryBytes: Long): Long =
        (availableMemoryBytes.coerceAtLeast(0) / 3).coerceAtMost(MAX_NATIVE_RAR_DICTIONARY_BYTES)
}
