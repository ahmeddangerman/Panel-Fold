package dev.tabletcomics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import android.net.Uri
import dev.tabletcomics.library.ComicLibraryEntry
import androidx.test.platform.app.InstrumentationRegistry
import dev.tabletcomics.library.CoverDiskCache
import dev.tabletcomics.library.LibraryFilter
import dev.tabletcomics.library.LibrarySort
import dev.tabletcomics.ui.ComicsTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryNavigationInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val cache get() = CoverDiskCache(File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ui-test-covers"))

    @Test fun canReachAndOpenLastCollection() {
        val shelves = (1..30).map { LibraryShelfCollection("$it", "Shelf $it", 0, null, customName = "Shelf $it") }
        var selected: String? = null
        compose.setContent { ComicsTheme("violet") { CollectionsExperience(shelves, cache, { selected = it.name }, {}) } }
        compose.onNodeWithTag("collections-list").performScrollToNode(hasText("Shelf 30"))
        compose.onNodeWithText("Shelf 30").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("Shelf 30", selected) }
    }

    @Test fun collectionScopeHasAnExplicitEscapeEvenWhenEmpty() {
        var cleared = false
        compose.setContent {
            ComicsTheme("violet") {
                ComicLibraryScreen(emptyList(), cache, "", {}, LibraryFilter.ALL, {}, LibrarySort.TITLE, {}, 164, {}, {}, {},
                    scopeLabel = "Collection · Empty shelf", onClearScope = { cleared = true })
            }
        }
        compose.onNodeWithText("Collection · Empty shelf").assertIsDisplayed()
        compose.onNodeWithText("All comics").performClick()
        compose.runOnIdle { assertTrue(cleared) }
    }

    @Test fun libraryScrollSurvivesReaderCompositionRemoval() {
        val reading = mutableStateOf(false)
        val books = (1..40).map {
            val book = Book(Uri.parse("file:///missing-comic-$it.cbz"), "Comic $it", "zip")
            book to ComicLibraryEntry(book.uri.toString(), book.title, book.format)
        }
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            ComicsTheme("violet") {
                if (reading.value) androidx.compose.material3.Text("Reading")
                else DestinationContent(holder, "LIBRARY") {
                    ComicLibraryScreen(books, cache, "", {}, LibraryFilter.ALL, {}, LibrarySort.TITLE, {}, 164, {}, { reading.value = true }, {})
                }
            }
        }
        compose.onNodeWithTag("library-grid").performScrollToNode(hasText("Comic 35"))
        compose.onNodeWithText("Comic 35").performClick()
        compose.onNodeWithText("Reading").assertIsDisplayed()
        compose.runOnIdle { reading.value = false }
        compose.onNodeWithText("Comic 35").assertIsDisplayed()
    }
}
