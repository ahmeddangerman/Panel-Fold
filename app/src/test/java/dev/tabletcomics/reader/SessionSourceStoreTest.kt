package dev.tabletcomics.reader

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SessionSourceStoreTest {
    @Test fun neighboringSpreadsAndRetainedSolidPreviousKeepEveryPage() {
        assertEquals(setOf(0, 1, 2), retainedSourcePageIds(listOf(listOf(0), listOf(1, 2))))
        assertEquals(setOf(1, 2, 3, 4, 5, 6),
            retainedSourcePageIds(listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6))))
    }

    @Test fun evictionWaitsForActiveDecoderLease() = withRoot { root ->
        val store = SessionSourceStore(root)
        val source = store.save(2, byteArrayOf(7, 8, 9), 10, 20)
        val lease = requireNotNull(store.acquire(source))
        store.retainOnly(emptySet())
        assertTrue(source.sourceFile.exists())
        assertNull(store.acquire(source))
        lease.close()
        assertFalse(source.sourceFile.exists())
        store.close()
    }

    @Test fun originalBytesAreReusedWithoutChangingThem() = withRoot { root ->
        val store = SessionSourceStore(root)
        val original = byteArrayOf(0, -1, 34, 52, 18)
        val source = store.save(1, original, 4, 5)
        assertArrayEquals(original, store.readBytes(1))
        assertArrayEquals(original, source.sourceFile.readBytes())
        source.sourceFile.writeBytes(byteArrayOf(0))
        assertNull(store.readBytes(1))
        store.close()
    }

    @Test fun inactiveCleanupDoesNotDeleteOtherLiveSessions() = withRoot { root ->
        val first = SessionSourceStore(root)
        val second = SessionSourceStore(root)
        val original = first.save(1, byteArrayOf(1), 1, 1)
        val other = second.save(1, byteArrayOf(2), 1, 1)
        val orphan = root.resolve("session-dead").apply { mkdirs() }.resolve("page.image").apply { writeText("old") }
        cleanupInactivePageSourceDirectories(root)
        assertTrue(original.sourceFile.exists())
        assertTrue(other.sourceFile.exists())
        assertFalse(orphan.exists())
        first.close()
        assertTrue(other.sourceFile.exists())
        second.close()
    }

    @Test fun closingOldSessionCannotDeleteNewSessionWithSamePageIndex() = withRoot { root ->
        val old = SessionSourceStore(root)
        val oldSource = old.save(0, byteArrayOf(1), 1, 1)
        val lease = requireNotNull(old.acquire(oldSource))
        old.close()
        val fresh = SessionSourceStore(root)
        val freshSource = fresh.save(0, byteArrayOf(2), 1, 1)
        cleanupInactivePageSourceDirectories(root)
        assertTrue(oldSource.sourceFile.exists())
        assertArrayEquals(byteArrayOf(2), fresh.readBytes(0))
        lease.close()
        assertFalse(oldSource.sourceFile.exists())
        assertTrue(freshSource.sourceFile.exists())
        fresh.close()
    }

    @Test fun decoderCannotAcquireAnotherSessionsSourceAtSamePageIndex() = withRoot { root ->
        val first = SessionSourceStore(root)
        val second = SessionSourceStore(root)
        try {
            val old = first.save(0, byteArrayOf(1), 1, 1)
            second.save(0, byteArrayOf(2), 1, 1)
            assertNull(second.acquire(old))
        } finally {
            first.close()
            second.close()
        }
    }

    @Test fun retainedNeighborSpreadsKeepBothSourcesAndEvictOnlyOutsideWindow() = withRoot { root ->
        val store = SessionSourceStore(root)
        try {
            val sources = (0..7).associateWith { index -> store.save(index, byteArrayOf(index.toByte()), 1, 1) }
            val retained = retainedSourcePageIds(listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6)))
            store.retainOnly(retained)
            for ((index, source) in sources) assertEquals(index in retained, source.sourceFile.exists())
        } finally { store.close() }
    }

    private fun withRoot(test: (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("reader-sources").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
}
