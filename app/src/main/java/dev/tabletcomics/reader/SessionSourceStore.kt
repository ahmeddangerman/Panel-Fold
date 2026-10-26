package dev.tabletcomics.reader

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID

private const val PAGE_SOURCE_DIRECTORY = "comic-page-source"

/** Call on IO. Live stores (including closed stores with decoder leases) remain protected. */
fun cleanupInactivePageSourceDirectories(context: Context) {
    cleanupInactivePageSourceDirectories(File(context.cacheDir, PAGE_SOURCE_DIRECTORY))
}

internal fun cleanupInactivePageSourceDirectories(root: File) = synchronized(sourceDirectoryLock) {
    root.listFiles()?.forEach { child ->
        if (child.absolutePath !in liveSourceDirectories) child.deleteRecursively()
    }
}

private val sourceDirectoryLock = Any()
private val liveSourceDirectories = mutableSetOf<String>()

internal fun pageSourceRoot(context: Context): File = File(context.cacheDir, PAGE_SOURCE_DIRECTORY)

/** Original encoded bytes, isolated to one open comic. Files are never rewritten. */
internal class SessionSourceStore(root: File) : Closeable {
    private val lock = Any()
    private val directory = File(root.canonicalFile, "session-${UUID.randomUUID()}")
    private val entries = mutableMapOf<Int, Entry>()
    private var closed = false
    private var activeLeases = 0

    init {
        synchronized(sourceDirectoryLock) {
            if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Could not stage original pages.")
            liveSourceDirectories += directory.absolutePath
        }
    }

    private class Entry(val source: FullResolutionPage, val byteCount: Long) {
        var leases = 0
        var retained = true
    }

    internal class Lease internal constructor(val source: FullResolutionPage, private val release: () -> Unit) : Closeable {
        private var released = false
        override fun close() = synchronized(this) {
            if (!released) {
                released = true
                release()
            }
        }
    }

    fun source(index: Int): FullResolutionPage? = synchronized(lock) {
        entries[index]?.takeIf { valid(it) }?.source
    }

    fun save(index: Int, bytes: ByteArray, width: Int, height: Int): FullResolutionPage = synchronized(lock) {
        check(!closed) { "Page source session is closed." }
        require(bytes.isNotEmpty() && width > 0 && height > 0)
        entries[index]?.takeIf { valid(it) }?.let { return@synchronized it.source }
        entries.remove(index)?.let(::evict)
        val file = File.createTempFile("page-$index-", ".image", directory)
        try {
            file.writeBytes(bytes)
            val source = FullResolutionPage(index, file, width, height)
            entries[index] = Entry(source, bytes.size.toLong())
            source
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    fun acquire(source: FullResolutionPage): Lease? = synchronized(lock) {
        val entry = entries[source.pageIndex]?.takeIf { it.source == source && valid(it) }
            ?: return@synchronized null
        entry.leases++
        activeLeases++
        Lease(source) {
            synchronized(lock) {
                entry.leases--
                activeLeases--
                if (!entry.retained && entry.leases == 0) entry.source.sourceFile.delete()
                finishClose()
            }
        }
    }

    /** Missing/truncated staging files fall back to the archive; healthy originals avoid replay. */
    fun readBytes(index: Int): ByteArray? {
        val source = source(index) ?: return null
        val lease = acquire(source) ?: return null
        return lease.use {
            try {
                source.sourceFile.readBytes().takeIf { bytes ->
                    synchronized(lock) { entries[index]?.byteCount == bytes.size.toLong() }
                }
            } catch (_: IOException) {
                null
            }
        }
    }

    fun retainOnly(indices: Set<Int>) = synchronized(lock) {
        val removed = entries.filterKeys { it !in indices }.values.toList()
        entries.keys.retainAll(indices)
        removed.forEach(::evict)
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            entries.values.forEach(::evict)
            entries.clear()
        }
        finishClose()
    }

    private fun valid(entry: Entry) = !closed && entry.retained &&
        entry.source.sourceFile.length() == entry.byteCount

    private fun evict(entry: Entry) {
        entry.retained = false
        if (entry.leases == 0) entry.source.sourceFile.delete()
    }

    private fun finishClose() {
        if (closed && activeLeases == 0) synchronized(sourceDirectoryLock) {
            directory.deleteRecursively()
            liveSourceDirectories -= directory.absolutePath
        }
    }
}

internal fun retainedSourcePageIds(unitPageIds: Iterable<Iterable<Int>>): Set<Int> =
    unitPageIds.flatMap { it.toList() }.toSet()
