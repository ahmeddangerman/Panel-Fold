package dev.tabletcomics.archive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job

class StagedArchiveTest {
    @Test
    fun copyCancellationClosesInputAndOutput() {
        val directory = Files.createTempDirectory("staging-test").toFile()
        val readStarted = CountDownLatch(1)
        val cancellationRequested = AtomicBoolean(false)
        val inputClosed = AtomicBoolean(false)
        val outputClosed = AtomicBoolean(false)
        val registeredInput = AtomicReference<Closeable?>()
        val failure = AtomicReference<Throwable?>()
        val input = object : InputStream() {
            override fun read(): Int = read(byteArrayOf(), 0, 0)

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readStarted.countDown()
                while (!inputClosed.get()) Thread.sleep(5)
                return -1
            }

            override fun close() {
                inputClosed.set(true)
            }
        }
        val worker = Thread {
            try {
                StagedArchive.copyFrom(
                    directory = directory,
                    extension = "zip",
                    input = input,
                    cancellationCheck = {
                        if (cancellationRequested.get()) throw CancellationException("test cancellation")
                    },
                    registerCancellation = { resource -> registeredInput.set(resource); Closeable {} },
                    maxBytes = 256 * 1024,
                    minFreeBytes = 0,
                    availableBytes = { Long.MAX_VALUE },
                    outputFactory = { file ->
                        object : FilterOutputStream(FileOutputStream(file)) {
                            override fun close() { outputClosed.set(true); super.close() }
                        }
                    },
                )
            } catch (caught: Throwable) {
                failure.set(caught)
            }
        }
        worker.start()
        assertTrue("copy did not reach its blocking read", readStarted.await(2, TimeUnit.SECONDS))
        cancellationRequested.set(true)
        checkNotNull(registeredInput.get()).close()
        worker.join(2_000)

        assertFalse("copy worker remained blocked after source close", worker.isAlive)
        assertTrue(failure.get() is CancellationException)
        assertTrue(inputClosed.get())
        assertTrue(outputClosed.get())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        directory.delete()
    }

    @Test
    fun stagedArchiveCloseIsIdempotent() {
        val directory = Files.createTempDirectory("staging-test").toFile()
        val staged = StagedArchive.create(directory, "rar")
        assertTrue(staged.file.exists())

        staged.close()
        staged.close()

        assertFalse(staged.file.exists())
        directory.delete()
    }

    @Test
    fun jobCancellationClosesRegisteredBlockingResourceImmediately() {
        val job = Job()
        val closed = AtomicBoolean(false)
        val registration = archiveCancellationRegistrar(job).register(Closeable { closed.set(true) })

        job.cancel(CancellationException("test cancellation"))

        assertTrue(closed.get())
        registration.close()
    }

    @Test
    fun cleanupSkipsActiveAndUnrelatedFiles() {
        val directory = Files.createTempDirectory("staging-test").toFile()
        val active = StagedArchive.create(directory, "zip")
        active.file.setLastModified(1L)
        val unrelated = File(directory, "comic-not-owned.txt").apply { writeText("keep"); setLastModified(1L) }

        StagedArchive.cleanupExpired(directory, nowMillis = 20_000L, maxAgeMillis = 10_000L)

        assertTrue(active.file.exists())
        assertTrue(unrelated.exists())
        active.close()
        directory.deleteRecursively()
    }

    @Test
    fun cleanupDeletesOnlyExpiredOwnedFiles() {
        val directory = Files.createTempDirectory("staging-test").toFile()
        val expiredZip = File(directory, "comic-expired.zip").apply { writeText("staged"); setLastModified(1L) }
        val expiredRar = File(directory, "comic-expired.rar").apply { writeText("staged"); setLastModified(1L) }
        val recent = File(directory, "comic-recent.zip").apply { writeText("staged"); setLastModified(15_000L) }
        val unrelated = File(directory, "other.zip").apply { writeText("keep"); setLastModified(1L) }

        StagedArchive.cleanupExpired(directory, nowMillis = 20_000L, maxAgeMillis = 10_000L)

        assertFalse(expiredZip.exists())
        assertFalse(expiredRar.exists())
        assertTrue(recent.exists())
        assertTrue(unrelated.exists())
        directory.deleteRecursively()
    }
}
