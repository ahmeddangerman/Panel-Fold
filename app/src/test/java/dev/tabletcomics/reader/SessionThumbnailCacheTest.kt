package dev.tabletcomics.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionThumbnailCacheTest {
    @Test fun decodeFinishingAfterBookSwitchCannotPublishIntoNewBook() {
        val cache = SessionThumbnailCache<String>(10) { it.length }
        val oldBook = ThumbnailBookKey(1, "content://old-book")
        val newBook = ThumbnailBookKey(2, "content://new-book")
        cache.activate(oldBook)
        cache.activate(newBook) // The old synchronous decode finishes after this reset.
        assertFalse(cache.publish(oldBook, 0, "old"))
        assertNull(cache.get(newBook, 0))
        assertTrue(cache.publish(newBook, 0, "new"))
        assertEquals("new", cache.get(newBook, 0))
        assertNull(cache.get(oldBook, 0))
    }

    @Test fun reopeningSameDocumentStillRejectsEarlierEpoch() {
        val cache = SessionThumbnailCache<String>(10) { it.length }
        val firstOpen = ThumbnailBookKey(1, "content://same-book")
        val secondOpen = firstOpen.copy(epoch = 3)
        cache.activate(firstOpen)
        cache.activate(secondOpen)
        assertFalse(cache.publish(firstOpen, 7, "old"))
        assertTrue(cache.publish(secondOpen, 7, "new"))
        cache.activate(null)
        assertFalse(cache.publish(secondOpen, 7, "late"))
    }

    @Test fun weightedCacheEvictsLeastRecentlyUsedImages() {
        val cache = SessionThumbnailCache<String>(6) { it.length }
        val book = ThumbnailBookKey(1, "book")
        cache.activate(book)
        cache.publish(book, 0, "aa")
        cache.publish(book, 1, "bb")
        cache.publish(book, 2, "cc")
        assertEquals("aa", cache.get(book, 0))
        cache.publish(book, 3, "dd")
        assertNull(cache.get(book, 1))
        assertEquals("aa", cache.get(book, 0))
        assertEquals("cc", cache.get(book, 2))
        assertEquals("dd", cache.get(book, 3))
    }
}
