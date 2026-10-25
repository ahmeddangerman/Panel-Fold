package dev.tabletcomics.archive

import org.junit.Assert.assertEquals
import org.junit.Test

class RarArchiveReaderTest {
    @Test
    fun nativeDictionaryBudgetUsesAvailableDeviceMemoryAndCeiling() {
        val mib = 1024L * 1024
        assertEquals(0L, ArchiveLimits.nativeRarDictionaryBudget(0))
        assertEquals(33L, ArchiveLimits.nativeRarDictionaryBudget(100))
        assertEquals(64L * mib, ArchiveLimits.nativeRarDictionaryBudget(192L * mib))
        assertEquals((1024L * mib) / 3, ArchiveLimits.nativeRarDictionaryBudget(1024L * mib))
        assertEquals(1024L * mib, ArchiveLimits.nativeRarDictionaryBudget(4L * 1024 * mib))
        assertEquals(1024L * mib, ArchiveLimits.nativeRarDictionaryBudget(Long.MAX_VALUE))
    }
}
