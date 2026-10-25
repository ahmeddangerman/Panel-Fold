package dev.tabletcomics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyProgressImportTest {
    @Test fun importsSavedPositionsPerComicAcrossSeparateFolderScans() {
        val legacyValues = mapOf(
            "position:content://books/first" to 17,
            "pages:content://books/first" to 80,
            "position:content://books/second" to 4,
            "pages:content://books/second" to 24,
        )

        assertEquals(LegacyPageProgress(17, 80), legacyProgressFor("content://books/first", legacyValues))
        assertEquals(LegacyPageProgress(4, 24), legacyProgressFor("content://books/second", legacyValues))
        assertNull(legacyProgressFor("content://books/new", legacyValues))
    }
}
