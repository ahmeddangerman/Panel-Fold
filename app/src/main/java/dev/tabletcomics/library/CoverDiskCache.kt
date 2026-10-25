package dev.tabletcomics.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

class CoverDiskCache(private val directory: File, private val maxBytes: Long = 128L * 1024 * 1024) {
    fun get(key: String): Bitmap? {
        val file = fileFor(key)
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun put(key: String, bitmap: Bitmap) {
        if (!directory.exists() && !directory.mkdirs()) return
        val target = fileFor(key)
        val temporary = File(directory, "${target.name}.tmp")
        if (!runCatching { temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }.getOrDefault(false)) {
            temporary.delete()
            return
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        evictToLimit()
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(directory, "$name.png")
    }

    private fun evictToLimit() {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "png" } ?: return
        var total = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val bytes = file.length()
            if (file.delete()) total -= bytes
        }
    }
}
