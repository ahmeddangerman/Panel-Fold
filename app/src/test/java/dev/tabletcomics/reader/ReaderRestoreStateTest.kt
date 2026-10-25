package dev.tabletcomics.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.FileNotFoundException

class ReaderRestoreStateTest {
    @Test
    fun restoresActiveBookAndLogicalPage() {
        val saved = ReaderRestoreState(uri = "content://provider/comic", title = "Comic", format = "rar", logicalPage = 12)

        assertTrue(saved.hasActiveBook)
        assertEquals("content://provider/comic", saved.uri)
        assertEquals("Comic", saved.title)
        assertEquals("rar", saved.format)
        assertEquals(12, saved.logicalPage)
    }

    @Test
    fun restoredPageClampsToNewPageCount() {
        assertEquals(4, ReaderRestoreState(logicalPage = 30).clampPage(pageCount = 5))
        assertEquals(0, ReaderRestoreState(logicalPage = -2).clampPage(pageCount = 5))
        assertEquals(0, ReaderRestoreState(logicalPage = 30).clampPage(pageCount = 0))
    }

    @Test
    fun invalidUriReturnsToLibrary() {
        val state = ReaderRestoreState(uri = "content://provider/deleted", title = "Old", format = "zip", logicalPage = 7)
            .clearActiveBook()

        assertFalse(state.hasActiveBook)
        assertNull(state.uri)
        assertEquals(0, state.logicalPage)
    }

    @Test
    fun revokedGrantProducesReselectionState() {
        assertTrue(requiresFolderReselection(SecurityException("Permission has been revoked")))
        assertTrue(requiresFolderReselection(IOException("Cannot open this comic. Select its folder again.")))
        assertFalse(requiresFolderReselection(IOException("Corrupt archive header")))
        assertTrue(requiresFolderReselection(FileNotFoundException("document was removed")))
    }
}
