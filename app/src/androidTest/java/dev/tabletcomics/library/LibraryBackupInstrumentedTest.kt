package dev.tabletcomics.library

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryBackupInstrumentedTest {
    private lateinit var context: IsolatedDatabaseContext
    private lateinit var repository: LibraryRepository
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var preferenceName: String

    @Before fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        context = IsolatedDatabaseContext(base)
        preferenceName = "backup-test-${UUID.randomUUID()}"
        prefs = base.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        prefs.edit().putString("folder", "content://test-tree").commit()
        repository = LibraryRepository(context)
    }

    @After fun tearDown() = runBlocking {
        repository.close()
        context.deleteSharedPreferences(preferenceName)
        context.directory.deleteRecursively()
        Unit
    }

    private fun scanned(uri: String, title: String = "Issue", folder: String = "Series") =
        ScannedComic(uri, title, "zip", 123, 1000, folder)

    private fun backup(uri: String = "content://old/book", title: String = "Issue", favorite: Boolean = true) =
        LibraryBackupCodec.encode(LibraryBackup(42, listOf(BackupComic(uri, title, "zip", "Series", 3, 10, 30, 20,
            favorite, true, listOf(PageBookmark(3, 30)), setOf("Restored"), mapOf("zoom" to 2f, "fit" to "WIDTH", "rtl" to true, "spreads" to true))),
            setOf("Restored", "Empty"), mapOf("accent" to "amber", "lightMode" to true)))

    @Test fun pendingImportSurvivesExportAndRemapsOnNextScan() = runBlocking {
        prefs.edit().putString("folder", "content://chosen/tree").putString("privateKey", "keep").commit()
        val result = repository.importBackup(backup(), prefs)
        assertEquals(0, result.importedComics)
        assertEquals(1, result.pendingComics)
        assertTrue(repository.entries().isEmpty())
        assertEquals("content://old/book", LibraryBackupCodec.decode(repository.exportBackup(prefs)).comics.single().uri)
        repository.syncScannedComics(listOf(scanned("content://new/book")), prefs)
        val entry = repository.entries().single()
        assertEquals(3, entry.pageIndex)
        assertTrue(entry.favorite)
        assertTrue(entry.finished)
        assertEquals(setOf("Restored"), entry.customCollections)
        assertEquals(listOf(PageBookmark(3, 30)), repository.bookmarks(entry.uri))
        assertEquals(2f, prefs.getFloat("zoom:${entry.uri}", 1f))
        assertEquals("WIDTH", prefs.getString("fit:${entry.uri}", null))
        assertTrue(prefs.getBoolean("rtl:${entry.uri}", false))
        assertTrue(prefs.getBoolean("spreads:${entry.uri}", false))
        assertEquals("content://chosen/tree", prefs.getString("folder", null))
        assertEquals("keep", prefs.getString("privateKey", null))
        assertEquals(setOf("Restored", "Empty"), repository.customCollections().toSet())
    }

    @Test fun noSelectedFolderDefersImportEvenWhenStaleCatalogExists() = runBlocking {
        repository.syncScannedComics(listOf(scanned("content://old/book")), prefs)
        prefs.edit().remove("folder").commit()
        assertEquals(1, repository.importBackup(backup(), prefs).pendingComics)
        assertEquals(-1, repository.entries().single().pageIndex)
        assertFalse(repository.entries().single().favorite)
        assertFalse(prefs.contains("rtl:content://old/book"))
        assertFalse(prefs.contains("spreads:content://old/book"))
        val deferredExport = LibraryBackupCodec.decode(repository.exportBackup(prefs)).comics.single()
        assertEquals(3, deferredExport.pageIndex)
        assertEquals(true, deferredExport.preferences["rtl"])
        prefs.edit().putString("folder", "content://new-tree").commit()
        repository.syncScannedComics(listOf(scanned("content://new/book")), prefs)
        val restored = repository.entries().first { it.uri == "content://new/book" }
        assertEquals(3, restored.pageIndex)
        assertTrue(prefs.getBoolean("rtl:content://new/book", false))
        assertTrue(prefs.getBoolean("spreads:content://new/book", false))
    }

    @Test fun importMergesBookmarksMembershipsAndKeepsUnrelatedData() = runBlocking {
        repository.syncScannedComics(listOf(scanned("content://new/book"), scanned("content://other", "Other")), prefs)
        repository.createCollection("Existing")
        repository.setComicInCollection("content://new/book", "Existing", true)
        repository.toggleBookmark("content://new/book", 1)
        repository.setFavorite("content://other", true)
        val addedAt = repository.entries().first { it.uri == "content://new/book" }.addedAt
        assertEquals(1, repository.importBackup(backup(), prefs).importedComics)
        val imported = repository.entries().first { it.uri == "content://new/book" }
        assertEquals(addedAt, imported.addedAt)
        assertEquals(setOf("Existing", "Restored"), imported.customCollections)
        assertEquals(listOf(1, 3), repository.bookmarks(imported.uri).map { it.pageIndex })
        assertTrue(repository.entries().first { it.uri == "content://other" }.favorite)
    }

    @Test fun malformedLaterRecordLeavesEarlierRecordsAndSettingsUntouched() = runBlocking {
        repository.syncScannedComics(listOf(scanned("content://new/book")), prefs)
        prefs.edit().putString("accent", "teal").commit()
        val json = JSONObject(String(backup()))
        json.getJSONArray("comics").put(JSONObject(json.getJSONArray("comics").getJSONObject(0).toString())
            .put("uri", "content://second").put("pageIndex", -5))
        try { repository.importBackup(json.toString().toByteArray(), prefs); throw AssertionError("Expected rejection") }
        catch (_: IllegalArgumentException) { }
        assertFalse(repository.entries().single().favorite)
        assertEquals(-1, repository.entries().single().pageIndex)
        assertEquals("teal", prefs.getString("accent", null))
        assertTrue(repository.customCollections().isEmpty())
    }

    @Test fun ambiguousAndReusedUrisStayPendingInsteadOfOverwriting() = runBlocking {
        repository.syncScannedComics(listOf(scanned("content://old/book", "Reused URI"),
            scanned("content://new/a"), scanned("content://new/b")), prefs)
        val result = repository.importBackup(backup(), prefs)
        assertEquals(1, result.pendingComics)
        assertTrue(repository.entries().all { !it.favorite && it.pageIndex == -1 })
        assertFalse(prefs.contains("zoom:content://new/a"))
    }

    @Test fun deletingACollectionKeepsComicsAndOtherCollectionMemberships() = runBlocking {
        repository.syncScannedComics(listOf(scanned("content://first/book"), scanned("content://second/book", "Second")), prefs)
        repository.createCollection("Remove me")
        repository.createCollection("Keep me")
        repository.setComicInCollection("content://first/book", "Remove me", true)
        repository.setComicInCollection("content://first/book", "Keep me", true)
        repository.setComicInCollection("content://second/book", "Remove me", true)
        repository.saveProgress("content://first/book", 4, 20)
        repository.toggleBookmark("content://first/book", 4)

        assertTrue(repository.deleteCollection("Remove me"))

        assertEquals(listOf("Keep me"), repository.customCollections())
        val first = repository.entries().first { it.uri == "content://first/book" }
        val second = repository.entries().first { it.uri == "content://second/book" }
        assertEquals(setOf("Keep me"), first.customCollections)
        assertTrue(second.customCollections.isEmpty())
        assertEquals(4, first.pageIndex)
        assertEquals(4, repository.bookmarks("content://first/book").single().pageIndex)
        assertTrue(repository.deleteCollection("Remove me").not())
    }

    @Test fun deletedCollectionDoesNotReturnWhenPendingBackupBooksAreMatchedLater() = runBlocking {
        assertEquals(1, repository.importBackup(backup(), prefs).pendingComics)
        assertTrue(repository.customCollections().contains("Restored"))
        assertTrue(repository.deleteCollection("Restored"))

        repository.syncScannedComics(listOf(scanned("content://new/book")), prefs)

        assertTrue(repository.entries().single().customCollections.isEmpty())
        assertFalse(repository.customCollections().contains("Restored"))
        assertTrue(repository.customCollections().contains("Empty"))
    }

    @Test fun scanRelocatesStateAcrossRepeatedSafTreeChangesAndUsesDiscoveryTime() = runBlocking {
        val before = System.currentTimeMillis()
        repository.syncScannedComics(listOf(scanned("content://first/book")), prefs)
        val discovered = repository.entries().single().addedAt
        assertTrue(discovered >= before)
        repository.importBackup(backup(), prefs)
        repository.syncScannedComics(listOf(scanned("content://second/book")), prefs)
        repository.saveProgress("content://second/book", 5, 10)
        repository.syncScannedComics(listOf(scanned("content://third/book")), prefs)
        assertEquals(5, repository.entries().first { it.uri == "content://third/book" }.pageIndex)
        assertEquals(2f, prefs.getFloat("zoom:content://third/book", 1f))
        val exported = LibraryBackupCodec.decode(repository.exportBackup(prefs))
        assertEquals(listOf("content://third/book"), exported.comics.map { it.uri })
        repository.syncScannedComics(listOf(scanned("content://third/book").copy(lastModified = 999_999)), prefs)
        assertEquals(exported.comics.single().addedAt, repository.entries().first { it.uri == "content://third/book" }.addedAt)
    }

    @Test fun reusedDocumentIdPreservesOldStatePendingAndResetsReplacement() = runBlocking {
        repository.syncScannedComics(listOf(scanned("content://reused")), prefs)
        repository.importBackup(backup(), prefs)
        prefs.edit().putInt("position:content://reused", 3).commit()
        repository.syncScannedComics(listOf(scanned("content://reused", "Replacement")), prefs)
        assertEquals(-1, repository.entries().single().pageIndex)
        assertFalse(repository.entries().single().favorite)
        assertTrue(repository.bookmarks("content://reused").isEmpty())
        assertFalse(prefs.contains("zoom:content://reused"))
        assertFalse(prefs.contains("rtl:content://reused"))
        assertFalse(prefs.contains("spreads:content://reused"))
        assertFalse(prefs.contains("position:content://reused"))
        repository.syncScannedComics(listOf(scanned("content://reused", "Replacement"), scanned("content://found-original")), prefs)
        assertEquals(3, repository.entries().first { it.uri == "content://found-original" }.pageIndex)
        assertTrue(repository.entries().first { it.uri == "content://found-original" }.favorite)
    }

    @Test fun returningToAnOldTreeDoesNotReviveResetProgressOrFavorite() = runBlocking {
        repository.syncScannedComics(listOf(scanned("content://first")), prefs)
        repository.importBackup(backup(), prefs)
        repository.syncScannedComics(listOf(scanned("content://second")), prefs)
        repository.resetProgress("content://second")
        repository.setFavorite("content://second", false)
        prefs.edit().putFloat("zoom:content://second", 3f).commit()
        repository.syncScannedComics(listOf(scanned("content://first")), prefs)
        val latest = repository.entries().first { it.uri == "content://first" }
        assertEquals(-1, latest.pageIndex)
        assertFalse(latest.favorite)
        assertFalse(latest.finished)
        assertEquals(3f, prefs.getFloat("zoom:content://first", 1f))
    }

    @Test fun returningToAnOldTreeClearsEveryPreferenceResetToDefault() = runBlocking {
        repository.syncScannedComics(listOf(scanned("content://first")), prefs)
        repository.importBackup(backup(), prefs)
        prefs.edit().putInt("coverPage:content://first", 3).putBoolean("crop:content://first", true)
            .putBoolean("keepZoom:content://first", false).commit()
        repository.syncScannedComics(listOf(scanned("content://second")), prefs)
        assertEquals(3, prefs.getInt("coverPage:content://second", -1))
        repository.resetProgress("content://second")
        prefs.edit().also { editor ->
            (BackupSettings.comicKeys + setOf("position", "pages")).forEach { editor.remove("$it:content://second") }
        }.commit()
        repository.syncScannedComics(listOf(scanned("content://first")), prefs)
        (BackupSettings.comicKeys + setOf("position", "pages")).forEach { key ->
            assertFalse("Stale $key must not be restored from the old tree", prefs.contains("$key:content://first"))
        }
        assertEquals(-1, repository.entries().first { it.uri == "content://first" }.pageIndex)
    }

    @Test fun returningToAnOldTreeDoesNotReviveRemovedBookmarksOrMemberships() = runBlocking {
        val other = scanned("content://other", "Other")
        repository.syncScannedComics(listOf(scanned("content://first"), other), prefs)
        repository.importBackup(backup(), prefs)
        repository.createCollection("Keep")
        repository.setComicInCollection(other.uri, "Keep", true)
        repository.syncScannedComics(listOf(scanned("content://second"), other), prefs)
        assertEquals(listOf(3), repository.bookmarks("content://second").map { it.pageIndex })
        assertFalse(repository.toggleBookmark("content://second", 3))
        repository.setComicInCollection("content://second", "Restored", false)
        repository.syncScannedComics(listOf(scanned("content://first"), other), prefs)
        assertTrue(repository.bookmarks("content://first").isEmpty())
        assertTrue(repository.entries().first { it.uri == "content://first" }.customCollections.isEmpty())
        assertEquals(setOf("Keep"), repository.entries().first { it.uri == other.uri }.customCollections)
        assertTrue(repository.customCollections().contains("Restored"))
    }

    @Test fun failedPreferenceCommitRollsBackDatabaseAndRestoresOldPreferences() = runBlocking {
        repository.syncScannedComics(listOf(scanned("content://new/book")), prefs)
        prefs.edit().putString("accent", "teal").commit()
        var failNextCommit = true
        val failing = object : android.content.SharedPreferences by prefs {
            override fun edit(): android.content.SharedPreferences.Editor {
                val underlying = prefs.edit()
                return object : android.content.SharedPreferences.Editor by underlying {
                    override fun commit(): Boolean {
                        val saved = underlying.commit()
                        if (failNextCommit) { failNextCommit = false; return false }
                        return saved
                    }
                }
            }
        }
        try { repository.importBackup(backup(), failing); throw AssertionError("Expected persistence failure") }
        catch (_: IllegalStateException) { }
        assertFalse(repository.entries().single().favorite)
        assertEquals(-1, repository.entries().single().pageIndex)
        assertTrue(repository.bookmarks("content://new/book").isEmpty())
        assertTrue(repository.customCollections().isEmpty())
        assertEquals("teal", prefs.getString("accent", null))
        assertFalse(prefs.contains("zoom:content://new/book"))
    }

    @Test fun versionOneMigrationPreservesExistingLibraryAndDiscoveryTime() = runBlocking {
        repository.syncScannedComics(listOf(scanned("content://new/book")), prefs)
        repository.setFavorite("content://new/book", true)
        repository.createCollection("Keep")
        val added = repository.entries().single().addedAt
        repository.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("tablet_comics_library.db").absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE current_scan")
            db.execSQL("DROP TABLE backup_pending")
            db.execSQL("DELETE FROM metadata WHERE key='has_scan_snapshot'")
            db.version = 1
        }
        repository = LibraryRepository(context)
        assertTrue(repository.entries().single().favorite)
        assertEquals(added, repository.entries().single().addedAt)
        assertEquals(listOf("Keep"), repository.customCollections())
        assertEquals(1, repository.importBackup(backup(), prefs).importedComics)
        assertEquals(added, repository.entries().single().addedAt)
    }

    private class IsolatedDatabaseContext(base: Context) : ContextWrapper(base) {
        val directory = File(base.cacheDir, "backup-db-${UUID.randomUUID()}").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(directory, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, errorHandler)
    }
}
