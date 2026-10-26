package dev.tabletcomics.reader

internal data class ThumbnailBookKey(val epoch: Long, val document: String)

/** Generation validation and insertion share a lock, so an obsolete decode cannot republish. */
internal class SessionThumbnailCache<T>(private val byteBudget: Long, private val byteCount: (T) -> Int) {
    private data class Key(val book: ThumbnailBookKey, val index: Int)
    private val entries = LinkedHashMap<Key, T>(16, .75f, true)
    private var activeBook: ThumbnailBookKey? = null
    private var bytes = 0L

    @Synchronized fun activate(book: ThumbnailBookKey?) {
        activeBook = book
        entries.clear()
        bytes = 0
    }

    @Synchronized fun get(book: ThumbnailBookKey, index: Int): T? =
        if (book == activeBook) entries[Key(book, index)] else null

    @Synchronized fun publish(book: ThumbnailBookKey, index: Int, value: T): Boolean {
        if (activeBook != book) return false
        val key = Key(book, index)
        entries.put(key, value)?.let { bytes -= byteCount(it) }
        bytes += byteCount(value)
        val iterator = entries.entries.iterator()
        while (bytes > byteBudget && iterator.hasNext()) {
            bytes -= byteCount(iterator.next().value)
            iterator.remove()
        }
        return true
    }
}
