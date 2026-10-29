package dev.tabletcomics.library

data class ComicLibraryEntry(
    val uri: String,
    val title: String,
    val format: String,
    val pageIndex: Int = -1,
    val pageCount: Int = 0,
    val lastReadAt: Long = 0L,
    val addedAt: Long = 0L,
    val favorite: Boolean = false,
    val finished: Boolean = false,
    val folderPath: String = "",
    val customCollections: Set<String> = emptySet(),
    val rightToLeft: Boolean = false,
)

internal data class ProgressFill(val startFraction: Float, val widthFraction: Float)

internal fun progressFill(progress: Float, rightToLeft: Boolean): ProgressFill {
    val safeProgress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    return ProgressFill(if (rightToLeft) 1f - safeProgress else 0f, safeProgress)
}

enum class LibraryFilter(val label: String) {
    ALL("All"), READING("Reading"), UNREAD("Unread"), FINISHED("Finished"), FAVORITES("Favorites")
}

enum class LibrarySort(val label: String) {
    RECENTLY_READ("Recently read"), TITLE("Title"), RECENTLY_ADDED("Recently added"), PROGRESS("Progress")
}

enum class ComicStatus(val label: String) { UNREAD("Unread"), READING("Reading"), FINISHED("Finished") }

fun comicStatus(entry: ComicLibraryEntry): ComicStatus = when {
    entry.finished -> ComicStatus.FINISHED
    entry.pageIndex >= 0 -> ComicStatus.READING
    else -> ComicStatus.UNREAD
}

fun progressFraction(entry: ComicLibraryEntry): Float =
    if (entry.pageCount <= 0 || entry.pageIndex < 0) Float.NaN
    else ((entry.pageIndex + 1).toFloat() / entry.pageCount).coerceIn(0f, 1f)

fun filterAndSortLibrary(
    entries: List<ComicLibraryEntry>,
    query: String,
    filter: LibraryFilter,
    sort: LibrarySort,
): List<ComicLibraryEntry> {
    val normalizedQuery = query.trim()
    val filtered = entries.asSequence()
        .filter { normalizedQuery.isEmpty() || it.title.contains(normalizedQuery, ignoreCase = true) }
        .filter {
            when (filter) {
                LibraryFilter.ALL -> true
                LibraryFilter.READING -> comicStatus(it) == ComicStatus.READING
                LibraryFilter.UNREAD -> comicStatus(it) == ComicStatus.UNREAD
                LibraryFilter.FINISHED -> it.finished
                LibraryFilter.FAVORITES -> it.favorite
            }
        }
    return when (sort) {
        LibrarySort.RECENTLY_READ -> filtered.sortedWith(
            compareByDescending<ComicLibraryEntry> { it.lastReadAt }.thenByNatural { it.title }
        )
        LibrarySort.TITLE -> filtered.sortedWith { a, b -> naturalCompare(a.title, b.title) }
        LibrarySort.RECENTLY_ADDED -> filtered.sortedWith(
            compareByDescending<ComicLibraryEntry> { it.addedAt }.thenByNatural { it.title }
        )
        LibrarySort.PROGRESS -> filtered.sortedWith(
            compareByDescending<ComicLibraryEntry> {
                val progress = progressFraction(it)
                if (progress.isNaN()) 0f else progress
            }.thenByNatural { it.title }
        )
    }.toList()
}

fun nextIssueInNaturalOrder(entries: List<ComicLibraryEntry>, currentUri: String): ComicLibraryEntry? {
    val ordered = entries.sortedWith { a, b -> naturalCompare(a.title, b.title) }
    val index = ordered.indexOfFirst { it.uri == currentUri }
    if (index < 0) return null
    return ordered.getOrNull(index + 1)
}

private fun <T> Comparator<T>.thenByNatural(selector: (T) -> String): Comparator<T> =
    thenComparator { a, b -> naturalCompare(selector(a), selector(b)) }

internal fun naturalCompare(left: String, right: String): Int {
    var i = 0
    var j = 0
    while (i < left.length && j < right.length) {
        val a = left[i]
        val b = right[j]
        if (a.isDigit() && b.isDigit()) {
            val aStart = i
            val bStart = j
            while (i < left.length && left[i].isDigit()) i++
            while (j < right.length && right[j].isDigit()) j++
            val aDigits = left.substring(aStart, i).trimStart('0').ifEmpty { "0" }
            val bDigits = right.substring(bStart, j).trimStart('0').ifEmpty { "0" }
            val lengthCompare = aDigits.length.compareTo(bDigits.length)
            if (lengthCompare != 0) return lengthCompare
            val numberCompare = aDigits.compareTo(bDigits)
            if (numberCompare != 0) return numberCompare
            val zeroCompare = left.substring(aStart, i).length.compareTo(right.substring(bStart, j).length)
            if (zeroCompare != 0) return zeroCompare
        } else {
            val compare = a.lowercaseChar().compareTo(b.lowercaseChar())
            if (compare != 0) return compare
            i++
            j++
        }
    }
    return (left.length - i).compareTo(right.length - j).takeIf { it != 0 }
        ?: left.compareTo(right, ignoreCase = true).takeIf { it != 0 }
        ?: left.compareTo(right)
}
