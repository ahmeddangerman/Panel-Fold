package dev.tabletcomics.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderImageBudgetTest {
    @Test fun threeReadyUnitsFitBudgetIncludingLandscapeSpreads() {
        for (budgetMiB in listOf(8, 16, 32, 64, 93)) {
            for (slots in 1..2) {
                val budget = budgetMiB * 1024L * 1024
                val dimension = readingPageDimension(4096, slots, budget)
                val residentBytes = 3L * slots * dimension * dimension * 4
                assertTrue(residentBytes <= budget * .85)
            }
        }
    }

    @Test fun enoughMemoryKeepsTheDisplayResolution() {
        assertEquals(2000, readingPageDimension(2000, 1, 64L * 1024 * 1024))
    }

    @Test fun spreadBudgetAccountsForBothPagesInEveryUnit() {
        val budget = 32L * 1024 * 1024
        assertTrue(readingPageDimension(4096, 2, budget) < readingPageDimension(4096, 1, budget))
    }
}
