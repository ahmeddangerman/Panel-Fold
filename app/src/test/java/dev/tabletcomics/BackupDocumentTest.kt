package dev.tabletcomics

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupDocumentTest {
    @Test fun preservesUtf8Bytes() {
        val bytes = "{\"title\":\"قصص مصورة\"}".toByteArray(Charsets.UTF_8)
        assertArrayEquals(bytes, readBackupDocument(ByteArrayInputStream(bytes)))
    }
    @Test fun rejectsOversizedDocumentsBeforeParsing() {
        assertThrows(IOException::class.java) {
            readBackupDocument(ByteArrayInputStream(ByteArray(MAX_BACKUP_FILE_BYTES + 1)))
        }
    }
    @Test fun acceptsExactDocumentLimit() {
        val bytes = ByteArray(MAX_BACKUP_FILE_BYTES)
        assertArrayEquals(bytes, readBackupDocument(ByteArrayInputStream(bytes)))
    }
}
