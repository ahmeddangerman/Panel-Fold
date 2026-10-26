package dev.tabletcomics.archive

import android.net.Uri
import java.io.Closeable
import java.io.IOException

data class ComicPage(val name: String, val position: Int)

interface OpenArchive : Closeable {
    val pages: List<ComicPage>
    val isSolid: Boolean get() = false
    fun readPage(
        page: ComicPage,
        cancellationCheck: () -> Unit,
        registerCancellation: ArchiveCancellationRegistrar,
    ): ByteArray
}

interface ArchiveReader {
    fun open(
        uri: Uri,
        cancellationCheck: () -> Unit,
        registerCancellation: ArchiveCancellationRegistrar,
    ): OpenArchive

    fun readPreview(
        uri: Uri,
        cancellationCheck: () -> Unit,
        registerCancellation: ArchiveCancellationRegistrar,
    ): ByteArray?
}

/** Name-based ZIP access must never silently select one of two different pages. */
internal fun rejectDuplicateImageNames(names: Iterable<String>) {
    val seen = HashSet<String>()
    for (name in names) {
        if (!seen.add(name)) {
            throw IOException("This comic contains duplicate image filename '${name.take(120)}'. Rename the duplicate files and recreate the archive.")
        }
    }
}
