package dev.tabletcomics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCatalogTest {
    private fun entry(
        title: String,
        page: Int = -1,
        pages: Int = 10,
        favorite: Boolean = false,
        finished: Boolean = false,
        lastRead: Long = 0,
        added: Long = 0,
    ) = ComicLibraryEntry(
        uri = title,
        title = title,
        format = "zip",
        pageIndex = page,
        pageCount = pages,
        favorite = favorite,
        finished = finished,
        lastReadAt = lastRead,
        addedAt = added,
    )

    @Test fun filtersDistinguishUnreadReadingFinishedAndFavorites() {
        val entries = listOf(
            entry("Unread"),
            entry("In progress", page = 0),
            entry("At last page", page = 9),
            entry("Finished", page = 9, finished = true),
            entry("Favorite unread", favorite = true),
        )

        assertEquals(listOf("Favorite unread", "Unread"),
            filterAndSortLibrary(entries, "", LibraryFilter.UNREAD, LibrarySort.TITLE).map { it.title })
        assertEquals(listOf("At last page", "In progress"),
            filterAndSortLibrary(entries, "", LibraryFilter.READING, LibrarySort.TITLE).map { it.title })
        assertEquals(listOf("Finished"),
            filterAndSortLibrary(entries, "", LibraryFilter.FINISHED, LibrarySort.TITLE).map { it.title })
        assertEquals(listOf("Favorite unread"),
            filterAndSortLibrary(entries, "", LibraryFilter.FAVORITES, LibrarySort.TITLE).map { it.title })
    }

    @Test fun naturalTitleSortAndSearchAreCaseInsensitive() {
        val entries = listOf(entry("Issue 10"), entry("The Hero"), entry("Issue 2"))

        assertEquals(listOf("Issue 2", "Issue 10", "The Hero"),
            filterAndSortLibrary(entries, "", LibraryFilter.ALL, LibrarySort.TITLE).map { it.title })
        assertEquals(listOf("The Hero"),
            filterAndSortLibrary(entries, "hero", LibraryFilter.ALL, LibrarySort.TITLE).map { it.title })
    }

    @Test fun progressIsClampedAndUnavailableBeforeFirstPageIsRead() {
        assertTrue(progressFraction(entry("Unread", page = -1)).isNaN())
        assertEquals(0.3f, progressFraction(entry("Reading", page = 2)), 0.001f)
        assertEquals(1f, progressFraction(entry("Overshoot", page = 20)), 0.001f)
        assertTrue(progressFraction(entry("Unknown", page = 4, pages = 0)).isNaN())
    }

    @Test fun progressOriginFollowsTheComicReadingDirection() {
        assertEquals(ProgressFill(0f, 0.42f), progressFill(0.42f, rightToLeft = false))
        assertEquals(ProgressFill(0.58f, 0.42f), progressFill(0.42f, rightToLeft = true))
        assertEquals(ProgressFill(0f, 0f), progressFill(Float.NaN, rightToLeft = false))
        assertEquals(ProgressFill(0f, 1f), progressFill(2f, rightToLeft = true))
    }

    @Test fun sortByRecentReadAndProgressPlacesMostRecentOrFurthestFirst() {
        val entries = listOf(
            entry("Low", page = 1, lastRead = 100),
            entry("High", page = 8, lastRead = 50),
        )
        assertEquals(listOf("Low", "High"),
            filterAndSortLibrary(entries, "", LibraryFilter.ALL, LibrarySort.RECENTLY_READ).map { it.title })
        assertEquals(listOf("High", "Low"),
            filterAndSortLibrary(entries, "", LibraryFilter.ALL, LibrarySort.PROGRESS).map { it.title })
    }

    @Test fun statusUsesSavedPageRatherThanAssumingPageZeroIsUnread() {
        assertEquals(ComicStatus.UNREAD, comicStatus(entry("New")))
        assertEquals(ComicStatus.READING, comicStatus(entry("Started", page = 0)))
        assertEquals(ComicStatus.READING, comicStatus(entry("At end", page = 9)))
        assertEquals(ComicStatus.FINISHED, comicStatus(entry("Done", page = 9, finished = true)))
    }

    @Test fun nextIssueUsesNaturalOrderAndStopsAtCollectionEnd() {
        val entries = listOf(entry("Series 10"), entry("Series 2"), entry("Series 1"))
        assertEquals("Series 10", nextIssueInNaturalOrder(entries, "Series 2")?.title)
        assertEquals(null, nextIssueInNaturalOrder(entries, "Series 10"))
        assertEquals(null, nextIssueInNaturalOrder(entries, "missing"))
    }
}
