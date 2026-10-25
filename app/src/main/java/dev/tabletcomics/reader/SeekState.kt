package dev.tabletcomics.reader

/** Separates the requested destination from the last page whose bitmap is still on screen. */
internal data class SeekState(
    val draftIndex: Int = 0,
    val renderedIndex: Int? = null,
    val requestedIndex: Int? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val requiresFolderReselection: Boolean = false,
) {
    fun destination(totalPages: Int, spreads: Boolean): Int {
        if (totalPages <= 0) return 0
        return TurnNavigation.spreadStart(draftIndex.coerceIn(0, totalPages - 1), spreads)
    }

    fun requesting(index: Int): SeekState = copy(
        draftIndex = index,
        requestedIndex = index,
        loading = true,
        error = null,
        requiresFolderReselection = false,
    )

    fun rendered(index: Int): SeekState = copy(
        draftIndex = index,
        renderedIndex = index,
        requestedIndex = null,
        loading = false,
        error = null,
        requiresFolderReselection = false,
    )

    fun failed(message: String, requiresFolderReselection: Boolean = false): SeekState = copy(
        loading = false,
        error = message,
        requiresFolderReselection = requiresFolderReselection,
    )

    fun returnToRenderedPage(): SeekState = copy(
        draftIndex = renderedIndex ?: draftIndex,
        requestedIndex = null,
        loading = false,
        error = null,
        requiresFolderReselection = false,
    )
}

internal class DestinationRequestGate {
    private var pendingDestination: Int? = null

    fun begin(destination: Int, rendered: Int?): Boolean {
        if (pendingDestination == destination) return false
        if (pendingDestination == null && rendered == destination) return false
        pendingDestination = destination
        return true
    }

    fun force(destination: Int) {
        pendingDestination = destination
    }

    fun complete(destination: Int) {
        if (pendingDestination == destination) pendingDestination = null
    }

    fun isPending(destination: Int): Boolean = pendingDestination == destination

    fun clear() {
        pendingDestination = null
    }
}
