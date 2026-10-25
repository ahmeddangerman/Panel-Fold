package dev.tabletcomics.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderScanGenerationTest {
    @Test
    fun olderScanCannotPublishAfterNewerScan() {
        val scans = FolderScanGeneration()
        val oldScan = scans.begin()
        val newScan = scans.begin()

        assertFalse(scans.isCurrent(oldScan))
        assertTrue(scans.isCurrent(newScan))
    }
}
