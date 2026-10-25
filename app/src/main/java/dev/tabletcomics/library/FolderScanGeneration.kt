package dev.tabletcomics.library

internal class FolderScanGeneration {
    private var generation = 0L

    @Synchronized
    fun begin(): Long = ++generation

    @Synchronized
    fun isCurrent(token: Long): Boolean = token == generation
}

internal fun coverCacheKey(uri: String, size: Long, lastModified: Long): String =
    "$uri#$size#$lastModified"
