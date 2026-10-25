package dev.tabletcomics.archive

import android.net.Uri
import java.io.Closeable

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
