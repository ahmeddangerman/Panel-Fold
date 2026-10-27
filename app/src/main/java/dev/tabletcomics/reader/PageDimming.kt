package dev.tabletcomics.reader

/** Fraction of the page's light removed. Keep a readable floor at the strongest setting. */
internal const val MAX_PAGE_DIMMING = .8f
internal const val DEFAULT_PAGE_DIMMING = .35f

internal fun safePageDimming(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, MAX_PAGE_DIMMING) else 0f
