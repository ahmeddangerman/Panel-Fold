package dev.tabletcomics.library

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScannedComic(
    val uri: String,
    val title: String,
    val format: String,
    val size: Long,
    val lastModified: Long,
    val folderPath: String,
)

data class PageBookmark(val pageIndex: Int, val createdAt: Long)
data class LegacyPageProgress(val pageIndex: Int, val pageCount: Int)

internal fun legacyProgressFor(uri: String, values: Map<String, *>): LegacyPageProgress? {
    val pageIndex = values["position:$uri"] as? Int ?: return null
    if (pageIndex < 0) return null
    val pageCount = (values["pages:$uri"] as? Int ?: 0).coerceAtLeast(0)
    return LegacyPageProgress(pageIndex, pageCount)
}

class LibraryRepository(context: Context) {
    private val helper = LibraryDatabase(context.applicationContext)

    suspend fun syncScannedComics(comics: List<ScannedComic>, legacy: SharedPreferences) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val legacyValues = legacy.all
            for (comic in comics) {
                val exists = db.rawQuery("SELECT 1 FROM comics WHERE uri=?", arrayOf(comic.uri)).use { it.moveToFirst() }
                val values = android.content.ContentValues().apply {
                    put("title", comic.title)
                    put("format", comic.format)
                    put("size", comic.size)
                    put("modified", comic.lastModified)
                    put("folder_path", comic.folderPath)
                }
                if (exists) {
                    db.update("comics", values, "uri=?", arrayOf(comic.uri))
                } else {
                    val oldProgress = legacyProgressFor(comic.uri, legacyValues)
                    values.put("uri", comic.uri)
                    values.put("page_index", oldProgress?.pageIndex ?: -1)
                    values.put("page_count", oldProgress?.pageCount ?: 0)
                    values.put("last_read", if (oldProgress != null) now else 0L)
                    values.put("added_at", comic.lastModified.takeIf { it > 0 } ?: now)
                    values.put("favorite", 0)
                    values.put("finished", 0)
                    db.insertWithOnConflict("comics", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun entries(): List<ComicLibraryEntry> = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val collectionsByUri = mutableMapOf<String, MutableSet<String>>()
        db.rawQuery("SELECT comic_uri, collection_name FROM comic_collections", null).use { cursor ->
            while (cursor.moveToNext()) collectionsByUri.getOrPut(cursor.getString(0)) { mutableSetOf() }.add(cursor.getString(1))
        }
        db.rawQuery(
            "SELECT uri,title,format,page_index,page_count,last_read,added_at,favorite,finished,folder_path FROM comics", null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val uri = cursor.getString(0)
                    add(ComicLibraryEntry(
                        uri = uri,
                        title = cursor.getString(1),
                        format = cursor.getString(2),
                        pageIndex = cursor.getInt(3),
                        pageCount = cursor.getInt(4),
                        lastReadAt = cursor.getLong(5),
                        addedAt = cursor.getLong(6),
                        favorite = cursor.getInt(7) != 0,
                        finished = cursor.getInt(8) != 0,
                        folderPath = cursor.getString(9).orEmpty(),
                        customCollections = collectionsByUri[uri].orEmpty(),
                    ))
                }
            }
        }
    }

    suspend fun saveProgress(uri: String, pageIndex: Int, pageCount: Int) = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL(
            "UPDATE comics SET page_index=?, page_count=?, last_read=? WHERE uri=?",
            arrayOf(pageIndex, pageCount, System.currentTimeMillis(), uri),
        )
    }

    suspend fun setFavorite(uri: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL("UPDATE comics SET favorite=? WHERE uri=?", arrayOf(if (favorite) 1 else 0, uri))
    }

    suspend fun setFinished(uri: String, finished: Boolean) = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL("UPDATE comics SET finished=? WHERE uri=?", arrayOf(if (finished) 1 else 0, uri))
    }

    suspend fun resetProgress(uri: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL(
            "UPDATE comics SET page_index=-1,page_count=0,last_read=0,finished=0 WHERE uri=?", arrayOf(uri),
        )
    }

    suspend fun bookmarks(uri: String): List<PageBookmark> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT page_index,created_at FROM bookmarks WHERE comic_uri=? ORDER BY page_index", arrayOf(uri),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(PageBookmark(cursor.getInt(0), cursor.getLong(1))) }
        }
    }

    /** Returns true when the bookmark was added, false when the existing one was removed. */
    suspend fun toggleBookmark(uri: String, pageIndex: Int): Boolean = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val exists = db.rawQuery(
            "SELECT 1 FROM bookmarks WHERE comic_uri=? AND page_index=?", arrayOf(uri, pageIndex.toString()),
        ).use { it.moveToFirst() }
        if (exists) {
            db.delete("bookmarks", "comic_uri=? AND page_index=?", arrayOf(uri, pageIndex.toString()))
            false
        } else {
            db.execSQL(
                "INSERT OR IGNORE INTO bookmarks(comic_uri,page_index,created_at) VALUES(?,?,?)",
                arrayOf(uri, pageIndex, System.currentTimeMillis()),
            )
            true
        }
    }

    suspend fun customCollections(): List<String> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT name FROM collections ORDER BY name COLLATE NOCASE", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    suspend fun createCollection(name: String): Boolean = withContext(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.isEmpty()) return@withContext false
        val values = android.content.ContentValues().apply { put("name", clean) }
        helper.writableDatabase.insertWithOnConflict("collections", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    suspend fun setComicInCollection(uri: String, name: String, included: Boolean) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        if (included) db.execSQL(
            "INSERT OR IGNORE INTO comic_collections(comic_uri,collection_name) VALUES(?,?)", arrayOf(uri, name),
        ) else db.delete("comic_collections", "comic_uri=? AND collection_name=?", arrayOf(uri, name))
    }

    suspend fun close() = withContext(Dispatchers.IO) { helper.close() }
}

private class LibraryDatabase(context: Context) : SQLiteOpenHelper(context, "tablet_comics_library.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE comics (
                uri TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                format TEXT NOT NULL,
                size INTEGER NOT NULL DEFAULT 0,
                modified INTEGER NOT NULL DEFAULT 0,
                folder_path TEXT NOT NULL DEFAULT '',
                page_index INTEGER NOT NULL DEFAULT -1,
                page_count INTEGER NOT NULL DEFAULT 0,
                last_read INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL DEFAULT 0,
                favorite INTEGER NOT NULL DEFAULT 0,
                finished INTEGER NOT NULL DEFAULT 0
            )""".trimIndent(),
        )
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL,value INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE collections (name TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("""CREATE TABLE comic_collections (
            comic_uri TEXT NOT NULL,collection_name TEXT NOT NULL,
            PRIMARY KEY(comic_uri,collection_name)
        )""".trimIndent())
        db.execSQL("""CREATE TABLE bookmarks (
            comic_uri TEXT NOT NULL,page_index INTEGER NOT NULL,created_at INTEGER NOT NULL,
            PRIMARY KEY(comic_uri,page_index)
        )""".trimIndent())
        db.execSQL("CREATE INDEX comics_last_read_idx ON comics(last_read DESC)")
        db.execSQL("CREATE INDEX comics_folder_idx ON comics(folder_path)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}
