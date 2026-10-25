package dev.tabletcomics.reader

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import java.io.FileNotFoundException

/** Saveable inputs needed to reopen the active document through its persisted SAF grant. */
internal data class ReaderRestoreState(
    val uri: String? = null,
    val title: String? = null,
    val format: String? = null,
    val logicalPage: Int = 0,
) {
    val hasActiveBook: Boolean
        get() = !uri.isNullOrBlank() && !title.isNullOrBlank() && format in setOf("zip", "rar")

    fun clampPage(pageCount: Int): Int = clampRestoredPage(logicalPage, pageCount)

    fun clearActiveBook(): ReaderRestoreState = Empty

    companion object {
        val Empty = ReaderRestoreState()

        val Saver: Saver<ReaderRestoreState, Any> = listSaver<ReaderRestoreState, String>(
            save = { listOf(it.uri.orEmpty(), it.title.orEmpty(), it.format.orEmpty(), it.logicalPage.toString()) },
            restore = { values ->
                ReaderRestoreState(
                    uri = values.getOrNull(0)?.ifBlank { null },
                    title = values.getOrNull(1)?.ifBlank { null },
                    format = values.getOrNull(2)?.ifBlank { null },
                    logicalPage = values.getOrNull(3)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                )
            },
        )
    }
}

internal fun clampRestoredPage(index: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else index.coerceIn(0, pageCount - 1)

internal fun requiresFolderReselection(failure: Throwable): Boolean {
    val visited = HashSet<Throwable>()
    var cause: Throwable? = failure
    while (cause != null && visited.add(cause)) {
        if (cause is SecurityException || cause is FileNotFoundException) return true
        val message = cause.message.orEmpty().lowercase()
        if ("select its folder again" in message || "permission" in message || "revoked" in message ||
            "no such file" in message || "document was removed" in message
        ) return true
        cause = cause.cause
    }
    return false
}
