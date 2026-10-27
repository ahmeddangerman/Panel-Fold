package dev.tabletcomics.library

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import dev.tabletcomics.reader.MAX_PAGE_DIMMING
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Portable metadata only: no SAF grants, source files, cache contents or private keys. */
internal data class LibraryBackup(
    val createdAt: Long,
    val comics: List<BackupComic>,
    val collections: Set<String>,
    val settings: Map<String, Any>,
)

internal data class BackupComic(
    val uri: String,
    val title: String,
    val format: String,
    val folderPath: String,
    val pageIndex: Int = -1,
    val pageCount: Int = 0,
    val lastReadAt: Long = 0,
    val addedAt: Long = 0,
    val favorite: Boolean = false,
    val finished: Boolean = false,
    val bookmarks: List<PageBookmark> = emptyList(),
    val collections: Set<String> = emptySet(),
    val preferences: Map<String, Any> = emptyMap(),
) {
    val identity: List<String> get() = listOf(title, folderPath, format.lowercase(java.util.Locale.ROOT))
    val storageKey: String get() = JSONArray(listOf(uri) + identity).toString()
}

data class BackupImportResult(val importedComics: Int, val pendingComics: Int, val collections: Int) {
    val summary: String get() = "Imported $importedComics comics and $collections collections." +
        if (pendingComics > 0) " $pendingComics comics are waiting for a matching library scan." else ""
}

/** Limits apply before JSON parsing and before any repository writes. */
object BackupLimits {
    const val MAX_BYTES = 5 * 1024 * 1024
    internal const val MAX_COMICS = 10_000
    internal const val MAX_COLLECTIONS = 1_000
    internal const val MAX_BOOKMARKS = 100_000
    internal const val MAX_PAGE = 1_000_000
}

internal object BackupSettings {
    val globalKeys = setOf("accent", "lightMode", "coverWidth", "showReaderProgress", "keepScreenAwake", "panSensitivity", "momentumStrength", "pageDimmerEnabled", "pageDimming")
    val comicKeys = setOf("zoom", "fit", "crop", "keepZoom", "coverPage", "rtl", "spreads")

    fun validate(key: String, value: Any, comic: Boolean): Any {
        require(key in if (comic) comicKeys else globalKeys) { "Unsupported backup setting: $key" }
        return when (key) {
            "lightMode", "showReaderProgress", "keepScreenAwake", "pageDimmerEnabled", "crop", "keepZoom", "rtl", "spreads" ->
                (value as? Boolean) ?: throw IllegalArgumentException("$key must be a boolean")
            "accent" -> (value as? String)?.also { require(it in setOf("violet", "teal", "amber")) { "Unknown accent" } }
                ?: throw IllegalArgumentException("accent must be a string")
            "fit" -> (value as? String)?.also { require(it in setOf("PAGE", "WIDTH", "HEIGHT")) { "Unknown page fit" } }
                ?: throw IllegalArgumentException("fit must be a string")
            "coverWidth" -> exactInt(value, 120, 240, key)
            "coverPage" -> exactInt(value, 0, BackupLimits.MAX_PAGE, key)
            "zoom" -> finiteFloat(value, 1f, 5f, key)
            "panSensitivity" -> finiteFloat(value, .25f, 4f, key)
            "momentumStrength" -> finiteFloat(value, 0f, 3f, key)
            "pageDimming" -> finiteFloat(value, 0f, MAX_PAGE_DIMMING, key)
            else -> error("Unreachable setting")
        }
    }

    fun globals(values: Map<String, *>): Map<String, Any> = globalKeys.mapNotNull { key ->
        values[key]?.let { key to validate(key, it, false) }
    }.toMap()

    fun comic(uri: String, values: Map<String, *>): Map<String, Any> = comicKeys.mapNotNull { key ->
        values["$key:$uri"]?.let { key to validate(key, it, true) }
    }.toMap()

    private fun finiteFloat(value: Any, min: Float, max: Float, key: String): Float {
        require(value is Number) { "$key must be a number" }
        val number = value.toDouble()
        require(number.isFinite() && number >= min.toDouble() && number <= max.toDouble()) { "$key is outside its supported range" }
        return number.toFloat()
    }
}

private fun exactLong(value: Any, min: Long, max: Long, field: String): Long {
    require(value is Int || value is Long) { "$field must be an integer" }
    return (value as Number).toLong().also { require(it in min..max) { "Invalid $field" } }
}

private fun exactInt(value: Any, min: Int, max: Int, field: String): Int = exactLong(value, min.toLong(), max.toLong(), field).toInt()

internal object LibraryBackupCodec {
    private const val VERSION = 1
    private const val KIND = "tablet-comics-library"

    fun encode(backup: LibraryBackup): ByteArray {
        val json = JSONObject().put("kind", KIND).put("version", VERSION).put("createdAt", backup.createdAt)
            .put("collections", JSONArray(backup.collections.sorted()))
            .put("settings", JSONObject(backup.settings))
            .put("comics", JSONArray(backup.comics.map(::comicJson)))
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= BackupLimits.MAX_BYTES) { "Backup exceeds the 5 MiB size limit" }
        // Exercise exactly the same invariants on generated and imported documents.
        decode(bytes)
        return bytes
    }

    fun decode(bytes: ByteArray): LibraryBackup {
        require(bytes.isNotEmpty() && bytes.size <= BackupLimits.MAX_BYTES) { "Backup must be between 1 byte and 5 MiB" }
        try {
            val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            checkNesting(source)
            val tokener = JSONTokener(source)
            val json = tokener.nextValue() as? JSONObject ?: throw IllegalArgumentException("Backup must be a JSON object")
            require(tokener.nextClean() == '\u0000') { "Unexpected content after backup" }
            require(json.get("kind") == KIND) { "This is not a PanelFlow backup" }
            require(exactInt(json.get("version"), 0, Int.MAX_VALUE, "version") == VERSION) { "Unsupported backup version" }
            val createdAt = exactLong(json.get("createdAt"), 0, Long.MAX_VALUE, "createdAt")
            val collections = strings(json.getJSONArray("collections"), BackupLimits.MAX_COLLECTIONS, "collections", 256)
            val settings = settings(json.getJSONObject("settings"), false)
            val array = json.getJSONArray("comics")
            require(array.length() <= BackupLimits.MAX_COMICS) { "Too many comics in backup" }
            var bookmarkCount = 0L
            val comics = (0 until array.length()).map { index ->
                parseComic(array.getJSONObject(index)).also {
                    bookmarkCount += it.bookmarks.size
                    require(bookmarkCount <= BackupLimits.MAX_BOOKMARKS) { "Too many bookmarks in backup" }
                }
            }
            require(bookmarkCount <= BackupLimits.MAX_BOOKMARKS) { "Too many bookmarks in backup" }
            require(comics.map { it.storageKey }.toSet().size == comics.size) { "Duplicate comic records in backup" }
            require(comics.all { collections.containsAll(it.collections) }) { "Comic refers to an unknown collection" }
            return LibraryBackup(createdAt, comics, collections, settings)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid backup JSON", error)
        }
    }

    fun comicJson(comic: BackupComic): JSONObject = JSONObject()
        .put("uri", comic.uri).put("title", comic.title).put("format", comic.format).put("folderPath", comic.folderPath)
        .put("pageIndex", comic.pageIndex).put("pageCount", comic.pageCount).put("lastReadAt", comic.lastReadAt)
        .put("addedAt", comic.addedAt).put("favorite", comic.favorite).put("finished", comic.finished)
        .put("bookmarks", JSONArray(comic.bookmarks.map { JSONObject().put("pageIndex", it.pageIndex).put("createdAt", it.createdAt) }))
        .put("collections", JSONArray(comic.collections.sorted())).put("preferences", JSONObject(comic.preferences))

    fun parseComic(json: JSONObject): BackupComic {
        val uri = string(json, "uri", 8192)
        val title = string(json, "title", 2048)
        val format = string(json, "format", 32)
        val folderPath = string(json, "folderPath", 4096, allowEmpty = true)
        val pageIndex = exactInt(json.get("pageIndex"), -1, BackupLimits.MAX_PAGE, "pageIndex")
        val pageCount = exactInt(json.get("pageCount"), 0, BackupLimits.MAX_PAGE, "pageCount")
        require(pageCount == 0 || pageIndex < pageCount) { "Page position exceeds page count" }
        val bookmarksJson = json.getJSONArray("bookmarks")
        require(bookmarksJson.length() <= BackupLimits.MAX_BOOKMARKS) { "Too many bookmarks" }
        val bookmarks = (0 until bookmarksJson.length()).map { index ->
            val bookmark = bookmarksJson.getJSONObject(index)
            PageBookmark(exactInt(bookmark.get("pageIndex"), 0, BackupLimits.MAX_PAGE, "bookmark page"),
                exactLong(bookmark.get("createdAt"), 0, Long.MAX_VALUE, "bookmark creation time"))
        }
        require(bookmarks.map { it.pageIndex }.toSet().size == bookmarks.size) { "Duplicate bookmarks" }
        return BackupComic(uri, title, format, folderPath, pageIndex, pageCount,
            exactLong(json.get("lastReadAt"), 0, Long.MAX_VALUE, "lastReadAt"),
            exactLong(json.get("addedAt"), 0, Long.MAX_VALUE, "addedAt"),
            json.get("favorite") as? Boolean ?: throw IllegalArgumentException("favorite must be a boolean"),
            json.get("finished") as? Boolean ?: throw IllegalArgumentException("finished must be a boolean"),
            bookmarks, strings(json.getJSONArray("collections"), BackupLimits.MAX_COLLECTIONS, "memberships", 256),
            settings(json.getJSONObject("preferences"), true))
    }

    private fun settings(json: JSONObject, comic: Boolean): Map<String, Any> = json.keys().asSequence().associateWith { key ->
        BackupSettings.validate(key, json.get(key), comic)
    }

    private fun string(json: JSONObject, key: String, max: Int, allowEmpty: Boolean = false): String {
        val value = json.get(key) as? String ?: throw IllegalArgumentException("$key must be text")
        require(value.length <= max && (allowEmpty || value.isNotBlank()) && '\u0000' !in value) { "Invalid $key" }
        return value
    }

    private fun strings(array: JSONArray, max: Int, field: String, maxLength: Int): Set<String> {
        require(array.length() <= max) { "Too many $field" }
        val values = (0 until array.length()).map { index ->
            val value = array.get(index) as? String ?: throw IllegalArgumentException("$field must contain text")
            require(value.isNotBlank() && value.length <= maxLength && '\u0000' !in value) { "Invalid $field" }
            value
        }
        require(values.toSet().size == values.size) { "Duplicate $field" }
        return values.toSet()
    }

    private fun checkNesting(source: String) {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (char in source) {
            if (quote != null) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == quote) quote = null
            } else when (char) {
                '"', '\'' -> quote = char
                '{', '[' -> { depth++; require(depth <= 24) { "Backup JSON is nested too deeply" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Unbalanced backup JSON" } }
            }
        }
        require(depth == 0 && quote == null) { "Incomplete backup JSON" }
    }
}

/** Both sides must be unique before a changed URI can be matched by portable identity. */
internal fun backupTargets(imported: List<BackupComic>, current: List<BackupComic>): Map<String, String> {
    val incomingCounts = imported.groupingBy { it.identity }.eachCount()
    val byUri = current.associateBy { it.uri }
    val byIdentity = current.groupBy { it.identity }
    val proposed = imported.mapNotNull { comic ->
        val exact = byUri[comic.uri]?.takeIf { it.identity == comic.identity }
        val target = exact ?: byIdentity[comic.identity]?.singleOrNull()?.takeIf { incomingCounts[comic.identity] == 1 }
        target?.let { comic.storageKey to it.uri }
    }
    val collisions = proposed.groupingBy { it.second }.eachCount()
    return proposed.filter { collisions[it.second] == 1 }.toMap()
}
