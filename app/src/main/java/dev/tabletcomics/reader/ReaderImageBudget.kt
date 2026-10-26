package dev.tabletcomics.reader

import kotlin.math.min
import kotlin.math.sqrt

/** One quality level for current, previous and next, including two-page spreads. */
internal fun readingPageDimension(displayDimension: Int, pageSlots: Int, bitmapBudget: Long): Int {
    val residentPages = 3 * pageSlots.coerceIn(1, 2)
    // Square pages are the worst case; leave 15% of the budget for rounding/transition overlap.
    val budgetDimension = sqrt(bitmapBudget.coerceAtLeast(0) * .85 / (4.0 * residentPages)).toInt()
    return min(displayDimension.coerceIn(1600, 4096), budgetDimension).coerceAtLeast(1)
}
