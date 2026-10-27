package dev.tabletcomics.library

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBackupCodecTest {
    private fun comic(uri: String = "content://old/book", title: String = "Issue 01", folder: String = "Series") = BackupComic(
        uri, title, "zip", folder, pageIndex = 2, pageCount = 20, lastReadAt = 42, addedAt = 10,
        favorite = true, finished = false, bookmarks = listOf(PageBookmark(2, 40)),
        collections = setOf("Favorites"), preferences = mapOf("zoom" to 2f, "fit" to "WIDTH", "crop" to true,
            "keepZoom" to false, "coverPage" to 1, "rtl" to true, "spreads" to false),
    )

    private fun document(comics: List<BackupComic> = listOf(comic())) = LibraryBackup(50, comics, setOf("Favorites", "Empty collection"),
        mapOf("accent" to "amber", "lightMode" to true, "coverWidth" to 164, "panSensitivity" to 1.5f, "momentumStrength" to 0f))

    private fun invalid(block: () -> Unit) {
        try { block(); throw AssertionError("Expected invalid backup to be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun roundTripPreservesPortableStateAndEmptyCollections() {
        val original = document()
        assertEquals(original, LibraryBackupCodec.decode(LibraryBackupCodec.encode(original)))
    }

    @Test fun pageDimmerSettingsRoundTripAndRejectUnreadableOrInvalidStrengths() {
        val values = mapOf("pageDimmerEnabled" to true, "pageDimming" to .5f)
        val original = document().copy(settings = BackupSettings.globals(values))
        assertEquals(original, LibraryBackupCodec.decode(LibraryBackupCodec.encode(original)))
        assertEquals(values, original.settings)
        // Existing backups without the feature remain valid.
        assertFalse(LibraryBackupCodec.decode(LibraryBackupCodec.encode(document())).settings.containsKey("pageDimming"))
        listOf(-.01f, .81f, Float.NaN, Float.POSITIVE_INFINITY, "50%").forEach { value ->
            invalid { BackupSettings.validate("pageDimming", value, false) }
        }
        invalid { BackupSettings.validate("pageDimmerEnabled", "true", false) }
    }

    @Test fun corruptUnsupportedTruncatedAndTrailingInputAreRejected() {
        val valid = LibraryBackupCodec.encode(document())
        invalid { LibraryBackupCodec.decode("not a backup".toByteArray()) }
        invalid { LibraryBackupCodec.decode(valid.copyOf(valid.size - 1)) }
        invalid { LibraryBackupCodec.decode(valid + " garbage".toByteArray()) }
        invalid { LibraryBackupCodec.decode(JSONObject(String(valid)).put("version", 2).toString().toByteArray()) }
        invalid { LibraryBackupCodec.decode(JSONObject(String(valid)).put("kind", "other-app").toString().toByteArray()) }
        invalid { LibraryBackupCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
    }

    @Test fun boundedInputAndNestingAreRejectedBeforeParsing() {
        invalid { LibraryBackupCodec.decode(ByteArray(BackupLimits.MAX_BYTES + 1) { ' '.code.toByte() }) }
        invalid { LibraryBackupCodec.decode(("[".repeat(1000) + "]".repeat(1000)).toByteArray()) }
    }

    @Test fun allRecordAndSettingTypesAreValidated() {
        val valid = LibraryBackupCodec.encode(document())
        fun changed(change: (JSONObject) -> Unit): ByteArray = JSONObject(String(valid)).also(change).toString().toByteArray()
        invalid { LibraryBackupCodec.decode(changed { it.getJSONArray("comics").getJSONObject(0).put("pageIndex", 21) }) }
        invalid { LibraryBackupCodec.decode(changed { it.getJSONArray("comics").getJSONObject(0).put("favorite", "true") }) }
        invalid { LibraryBackupCodec.decode(changed { it.getJSONObject("settings").put("coverWidth", 164.5) }) }
        invalid { LibraryBackupCodec.decode(changed { it.getJSONObject("settings").put("folder", "content://untrusted") }) }
        invalid { LibraryBackupCodec.decode(changed { it.getJSONArray("comics").getJSONObject(0).getJSONObject("preferences").put("zoom", 100) }) }
        invalid { LibraryBackupCodec.decode(changed { it.getJSONArray("comics").getJSONObject(0).getJSONObject("preferences").put("fit", "INVALID") }) }
        invalid { LibraryBackupCodec.decode(changed { it.getJSONArray("comics").getJSONObject(0).getJSONObject("preferences").put("rtl", "true") }) }
        invalid { LibraryBackupCodec.decode(changed { it.getJSONArray("comics").getJSONObject(0).getJSONObject("preferences").put("spreads", 1) }) }
        invalid { LibraryBackupCodec.decode(changed { it.getJSONArray("comics").getJSONObject(0).getJSONArray("collections").put("Missing") }) }
    }

    @Test fun duplicateRecordsAndBookmarksAreRejected() {
        invalid { LibraryBackupCodec.encode(document(listOf(comic(), comic()))) }
        invalid { LibraryBackupCodec.encode(document(listOf(comic().copy(bookmarks = listOf(PageBookmark(2, 10), PageBookmark(2, 20)))))) }
    }

    @Test fun whitelistExportsOnlySupportedUserSettings() {
        val values = mapOf("folder" to "content://private/grant", "privateKey" to "secret", "accent" to "violet",
            "zoom:content://old/book" to 2f, "rtl:content://old/book" to true, "coverPage:content://old/book" to 3)
        assertEquals(mapOf("accent" to "violet"), BackupSettings.globals(values))
        assertEquals(mapOf("zoom" to 2f, "coverPage" to 3, "rtl" to true), BackupSettings.comic("content://old/book", values))
        val json = String(LibraryBackupCodec.encode(document().copy(settings = BackupSettings.globals(values))))
        assertFalse(json.contains("privateKey"))
        assertFalse(json.contains("content://private/grant"))
    }

    @Test fun uniquePortableIdentityRemapsAcrossSafUris() {
        val old = comic()
        val replacement = comic(uri = "content://new/book")
        assertEquals(mapOf(old.storageKey to replacement.uri), backupTargets(listOf(old), listOf(replacement)))
        assertEquals(emptyMap<String, String>(), backupTargets(listOf(old), listOf(replacement.copy(folderPath = "Different"))))
    }

    @Test fun ambiguityOnEitherSideNeverAppliesToAnArbitraryBook() {
        val old = comic()
        val current = comic(uri = "content://new/a")
        assertTrue(backupTargets(listOf(old), listOf(current, current.copy(uri = "content://new/b"))).isEmpty())
        assertTrue(backupTargets(listOf(old, old.copy(uri = "content://old/alias")), listOf(current)).isEmpty())
        // Exact identity + URI remains safe even when the portable identity has duplicates.
        assertEquals(mapOf(old.storageKey to old.uri), backupTargets(listOf(old), listOf(old, current)))
    }

    @Test fun reusedUriCannotOverwriteDifferentComicButCanMatchAUniqueNewLocation() {
        val old = comic()
        val reused = old.copy(title = "Different issue")
        assertTrue(backupTargets(listOf(old), listOf(reused)).isEmpty())
        val replacement = old.copy(uri = "content://new/location")
        assertEquals(mapOf(old.storageKey to replacement.uri), backupTargets(listOf(old), listOf(reused, replacement)))
    }
}
