package dev.tabletcomics.library

import android.content.ContentValues
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

internal fun readBackupComics(db: SQLiteDatabase, preferences: Map<String, *>): List<BackupComic> {
    val bookmarks = mutableMapOf<String, MutableList<PageBookmark>>()
    db.rawQuery("SELECT comic_uri,page_index,created_at FROM bookmarks ORDER BY page_index", null).use { cursor ->
        while (cursor.moveToNext()) bookmarks.getOrPut(cursor.getString(0)) { mutableListOf() }
            .add(PageBookmark(cursor.getInt(1), cursor.getLong(2)))
    }
    val collections = mutableMapOf<String, MutableSet<String>>()
    db.rawQuery("SELECT comic_uri,collection_name FROM comic_collections", null).use { cursor ->
        while (cursor.moveToNext()) collections.getOrPut(cursor.getString(0)) { mutableSetOf() }.add(cursor.getString(1))
    }
    return db.rawQuery("SELECT uri,title,format,folder_path,page_index,page_count,last_read,added_at,favorite,finished FROM comics", null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val uri = cursor.getString(0)
                add(BackupComic(uri, cursor.getString(1), cursor.getString(2), cursor.getString(3).orEmpty(),
                    cursor.getInt(4), cursor.getInt(5), cursor.getLong(6), cursor.getLong(7), cursor.getInt(8) != 0, cursor.getInt(9) != 0,
                    bookmarks[uri].orEmpty(), collections[uri].orEmpty(), BackupSettings.comic(uri, preferences)))
            }
        }
    }
}

/** Version-one databases have no scan snapshot; their existing catalog remains usable until scanned. */
internal fun activeBackupComics(db: SQLiteDatabase, comics: List<BackupComic>): List<BackupComic> {
    val scanned = db.rawQuery("SELECT value FROM metadata WHERE key='has_scan_snapshot'", null).use { it.moveToFirst() }
    if (!scanned) return comics
    val active = db.rawQuery("SELECT uri FROM current_scan", null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    return comics.filter { it.uri in active }
}

internal fun readPendingBackupComics(db: SQLiteDatabase): List<BackupComic> =
    db.rawQuery("SELECT payload FROM backup_pending ORDER BY rowid", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(LibraryBackupCodec.parseComic(JSONObject(cursor.getString(0)))) }
    }

internal fun savePendingBackupComic(db: SQLiteDatabase, comic: BackupComic) {
    db.insertWithOnConflict("backup_pending", null, ContentValues().apply {
        put("storage_key", comic.storageKey)
        put("payload", LibraryBackupCodec.comicJson(comic).toString())
    }, SQLiteDatabase.CONFLICT_REPLACE)
}

/** Updates only user state; scanned metadata and original discovery time remain owned by the scan. */
internal fun applyBackupComic(db: SQLiteDatabase, comic: BackupComic, targetUri: String, replaceAssociations: Boolean = false) {
    if (replaceAssociations) {
        db.delete("bookmarks", "comic_uri=?", arrayOf(targetUri))
        db.delete("comic_collections", "comic_uri=?", arrayOf(targetUri))
    }
    db.execSQL("UPDATE comics SET page_index=?,page_count=?,last_read=?,favorite=?,finished=? WHERE uri=?",
        arrayOf(comic.pageIndex, comic.pageCount, comic.lastReadAt, if (comic.favorite) 1 else 0, if (comic.finished) 1 else 0, targetUri))
    comic.bookmarks.forEach { bookmark ->
        db.execSQL("INSERT OR IGNORE INTO bookmarks(comic_uri,page_index,created_at) VALUES(?,?,?)",
            arrayOf(targetUri, bookmark.pageIndex, bookmark.createdAt))
    }
    comic.collections.forEach { collection ->
        db.execSQL("INSERT OR IGNORE INTO collections(name) VALUES(?)", arrayOf(collection))
        db.execSQL("INSERT OR IGNORE INTO comic_collections(comic_uri,collection_name) VALUES(?,?)", arrayOf(targetUri, collection))
    }
}

internal fun comicPreferenceUpdates(comic: BackupComic, targetUri: String, replaceMissing: Boolean = false): Map<String, Any> =
    buildMap {
        if (replaceMissing) BackupSettings.comicKeys.forEach { key ->
            put("$key:$targetUri", comic.preferences[key] ?: RemovedBackupPreference)
        } else comic.preferences.forEach { (key, value) -> put("$key:$targetUri", value) }
        // Keep the legacy fallback coherent with an imported or relocated unread/reset state.
        put("position:$targetUri", if (comic.pageIndex >= 0) comic.pageIndex else RemovedBackupPreference)
        put("pages:$targetUri", if (comic.pageIndex >= 0) comic.pageCount else RemovedBackupPreference)
    }

internal fun applyPendingBackupComics(
    db: SQLiteDatabase,
    current: List<BackupComic>,
    updates: MutableMap<String, Any>,
): Int {
    val pending = readPendingBackupComics(db)
    val targets = backupTargets(pending, current)
    for (comic in pending) {
        val target = targets[comic.storageKey] ?: continue
        applyBackupComic(db, comic, target)
        updates.putAll(comicPreferenceUpdates(comic, target))
        db.delete("backup_pending", "storage_key=?", arrayOf(comic.storageKey))
    }
    return targets.size
}

/** Keep unrelated historical records, while exporting the current location of relocated comics once. */
internal fun exportBackupComics(db: SQLiteDatabase, comics: List<BackupComic>): List<BackupComic> {
    val active = activeBackupComics(db, comics).groupBy { it.identity }
    val canonical = comics.filter { comic ->
        val current = active[comic.identity]?.singleOrNull()
        current == null || current.uri == comic.uri
    }
    return (canonical + readPendingBackupComics(db)).associateBy { it.storageKey }.values.toList()
}

/** DB changes roll back if preferences cannot be persisted. Restore preferences on a DB failure too. */
internal fun <T> withBackupTransaction(
    db: SQLiteDatabase,
    prefs: SharedPreferences,
    block: (MutableMap<String, Any>) -> T,
): T {
    val updates = mutableMapOf<String, Any>()
    var before: Map<String, *> = emptyMap<String, Any>()
    var preferencesAttempted = false
    db.beginTransaction()
    try {
        before = prefs.all
        val result = block(updates)
        if (updates.isNotEmpty()) {
            preferencesAttempted = true
            check(writeBackupPreferences(prefs, updates)) { "Could not save imported settings" }
        }
        db.setTransactionSuccessful()
        db.endTransaction()
        return result
    } catch (failure: Throwable) {
        if (db.inTransaction()) {
            try { db.endTransaction() } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
        }
        if (preferencesAttempted) {
            val editor = prefs.edit()
            updates.keys.forEach { key ->
                val old = before[key]
                if (old == null) editor.remove(key) else putBackupPreference(editor, key, old)
            }
            if (!editor.commit()) failure.addSuppressed(IllegalStateException("Could not restore previous settings"))
        }
        throw failure
    }
}

private fun writeBackupPreferences(prefs: SharedPreferences, values: Map<String, Any>): Boolean = prefs.edit().let { editor ->
    values.forEach { (key, value) -> putBackupPreference(editor, key, value) }
    editor.commit()
}

internal object RemovedBackupPreference

private fun putBackupPreference(editor: SharedPreferences.Editor, key: String, value: Any) {
    when (value) {
        RemovedBackupPreference -> editor.remove(key)
        is String -> editor.putString(key, value)
        is Boolean -> editor.putBoolean(key, value)
        is Int -> editor.putInt(key, value)
        is Long -> editor.putLong(key, value)
        is Float -> editor.putFloat(key, value)
        is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
        else -> throw IllegalArgumentException("Unsupported preference type for $key")
    }
}
