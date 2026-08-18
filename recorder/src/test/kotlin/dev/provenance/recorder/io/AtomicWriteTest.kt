package dev.provenance.recorder.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files

class AtomicWriteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `round trips bytes`() {
        val target = tmp.root.toPath().resolve("out.bin")
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        atomicWriteFile(target, bytes)
        assertArrayEquals(bytes, Files.readAllBytes(target))
    }

    @Test
    fun `round trips string as UTF-8`() {
        val target = tmp.root.toPath().resolve("out.txt")
        atomicWriteFile(target, "héllo €")
        assertEquals("héllo €", String(Files.readAllBytes(target), Charsets.UTF_8))
    }

    @Test
    fun `overwrite leaves only final content and no tmp files`() {
        val target = tmp.root.toPath().resolve("out.txt")
        atomicWriteFile(target, "first")
        atomicWriteFile(target, "second")
        assertEquals("second", String(Files.readAllBytes(target), Charsets.UTF_8))
        val siblings = Files.list(tmp.root.toPath()).use { it.toList() }
        assertEquals(1, siblings.size)
        assertEquals("out.txt", siblings[0].fileName.toString())
    }

    @Test
    fun `failure leaves no tmp file and rethrows original`() {
        // Target's parent does not exist → newByteChannel(CREATE) fails.
        val target = tmp.root.toPath().resolve("nope").resolve("out.txt")
        var threw = false
        try {
            atomicWriteFile(target, "x")
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("expected an exception", threw)
        // No leftover .tmp anywhere in the temp root.
        val leftovers = Files.walk(tmp.root.toPath()).use { s ->
            s.filter { it.fileName?.toString()?.endsWith(".tmp") == true }.toList()
        }
        assertTrue("no .tmp should survive, found: $leftovers", leftovers.isEmpty())
        assertFalse(Files.exists(target))
    }

    // --- fsync / rename degradation on filesystems that cannot do them ---------
    //
    // Regression: IntelliJ on Windows opening a project on the WSL filesystem
    // (\\wsl.localhost\...) routes file I/O through JetBrains' IJent nio provider,
    // whose FileChannel.force() throws kotlin.NotImplementedError (an Error, not an
    // Exception). That escaped atomicWriteFile → MetaWriter → the startup activity and
    // killed activation outright. Atomicity comes from write-temp-then-rename, so both
    // fsync and ATOMIC_MOVE must degrade rather than fail the write.

    private fun leftoverTmps() = Files.walk(tmp.root.toPath()).use { s ->
        s.filter { it.fileName?.toString()?.endsWith(".tmp") == true }.toList()
    }

    @Test
    fun `write lands when fsync throws NotImplementedError`() {
        val target = tmp.root.toPath().resolve("out.txt")
        val sync = BestEffortSync { throw NotImplementedError("An operation is not implemented: FILE_FORCE") }
        atomicWriteFile(target, "wsl".toByteArray(Charsets.UTF_8), sync, BestEffortMove())
        assertEquals("wsl", String(Files.readAllBytes(target), Charsets.UTF_8))
        assertTrue("no .tmp should survive, found: ${leftoverTmps()}", leftoverTmps().isEmpty())
    }

    @Test
    fun `write lands when fsync throws UnsupportedOperationException`() {
        val target = tmp.root.toPath().resolve("out.txt")
        val sync = BestEffortSync { throw UnsupportedOperationException("no fsync here") }
        atomicWriteFile(target, "ok".toByteArray(Charsets.UTF_8), sync, BestEffortMove())
        assertEquals("ok", String(Files.readAllBytes(target), Charsets.UTF_8))
        assertTrue("no .tmp should survive, found: ${leftoverTmps()}", leftoverTmps().isEmpty())
    }

    @Test
    fun `write lands when fsync throws IOException`() {
        val target = tmp.root.toPath().resolve("out.txt")
        val sync = BestEffortSync { throw IOException("sync failed") }
        atomicWriteFile(target, "ok".toByteArray(Charsets.UTF_8), sync, BestEffortMove())
        assertEquals("ok", String(Files.readAllBytes(target), Charsets.UTF_8))
    }

    @Test
    fun `unsupported fsync is attempted once then latched off`() {
        var calls = 0
        val sync = BestEffortSync {
            calls++
            throw NotImplementedError("FILE_FORCE")
        }
        repeat(3) { i ->
            val target = tmp.root.toPath().resolve("out$i.txt")
            atomicWriteFile(target, "v$i".toByteArray(Charsets.UTF_8), sync, BestEffortMove())
            assertEquals("v$i", String(Files.readAllBytes(target), Charsets.UTF_8))
        }
        assertEquals("fsync should be attempted only once, then latched off", 1, calls)
    }

    @Test
    fun `write lands when atomic move throws NotImplementedError`() {
        val target = tmp.root.toPath().resolve("out.txt")
        val mover = BestEffortMove { _, _ -> throw NotImplementedError("An operation is not implemented: FILE_MOVE") }
        atomicWriteFile(target, "moved".toByteArray(Charsets.UTF_8), BestEffortSync(), mover)
        assertEquals("moved", String(Files.readAllBytes(target), Charsets.UTF_8))
        assertTrue("no .tmp should survive, found: ${leftoverTmps()}", leftoverTmps().isEmpty())
    }

    @Test
    fun `write lands when atomic move throws AtomicMoveNotSupportedException`() {
        val target = tmp.root.toPath().resolve("out.txt")
        val mover = BestEffortMove { from, to ->
            throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "nope")
        }
        atomicWriteFile(target, "moved".toByteArray(Charsets.UTF_8), BestEffortSync(), mover)
        assertEquals("moved", String(Files.readAllBytes(target), Charsets.UTF_8))
    }

    @Test
    fun `write lands when atomic move throws UnsupportedOperationException`() {
        val target = tmp.root.toPath().resolve("out.txt")
        val mover = BestEffortMove { _, _ -> throw UnsupportedOperationException("no ATOMIC_MOVE") }
        atomicWriteFile(target, "moved".toByteArray(Charsets.UTF_8), BestEffortSync(), mover)
        assertEquals("moved", String(Files.readAllBytes(target), Charsets.UTF_8))
    }

    @Test
    fun `fallback move overwrites an existing target`() {
        val target = tmp.root.toPath().resolve("out.txt")
        val mover = BestEffortMove { _, _ -> throw NotImplementedError("FILE_MOVE") }
        atomicWriteFile(target, "first".toByteArray(Charsets.UTF_8), BestEffortSync(), mover)
        atomicWriteFile(target, "second".toByteArray(Charsets.UTF_8), BestEffortSync(), mover)
        assertEquals("second", String(Files.readAllBytes(target), Charsets.UTF_8))
        assertTrue("no .tmp should survive, found: ${leftoverTmps()}", leftoverTmps().isEmpty())
    }

    @Test
    fun `unsupported atomic move is attempted once then latched off`() {
        var calls = 0
        val mover = BestEffortMove { _, _ ->
            calls++
            throw NotImplementedError("FILE_MOVE")
        }
        repeat(3) { i ->
            val target = tmp.root.toPath().resolve("out$i.txt")
            atomicWriteFile(target, "v$i".toByteArray(Charsets.UTF_8), BestEffortSync(), mover)
            assertEquals("v$i", String(Files.readAllBytes(target), Charsets.UTF_8))
        }
        assertEquals("ATOMIC_MOVE should be attempted only once, then latched off", 1, calls)
    }

    @Test
    fun `degraded write produces byte-identical output to the durable write`() {
        val bytes = "héllo €".toByteArray(Charsets.UTF_8)
        val durable = tmp.root.toPath().resolve("durable.bin")
        val degraded = tmp.root.toPath().resolve("degraded.bin")
        atomicWriteFile(durable, bytes)
        atomicWriteFile(
            degraded,
            bytes,
            BestEffortSync { throw NotImplementedError("FILE_FORCE") },
            BestEffortMove { _, _ -> throw NotImplementedError("FILE_MOVE") },
        )
        assertArrayEquals(Files.readAllBytes(durable), Files.readAllBytes(degraded))
        assertArrayEquals(bytes, Files.readAllBytes(degraded))
    }

    @Test
    fun `a real move failure still cleans up tmp and rethrows the original`() {
        // Atomic move degrades, then the plain-move fallback genuinely fails (the
        // target is a non-empty directory) — that error must not be masked.
        val target = tmp.root.toPath().resolve("occupied")
        Files.createDirectory(target)
        Files.write(target.resolve("child"), byteArrayOf(1))
        var caught: Exception? = null
        try {
            atomicWriteFile(
                target,
                "x".toByteArray(Charsets.UTF_8),
                BestEffortSync(),
                BestEffortMove { _, _ -> throw NotImplementedError("FILE_MOVE") },
            )
        } catch (e: Exception) {
            caught = e
        }
        assertTrue("expected the underlying move failure to propagate, got $caught", caught is IOException)
        assertTrue("no .tmp should survive, found: ${leftoverTmps()}", leftoverTmps().isEmpty())
        assertTrue("the pre-existing target must be untouched", Files.exists(target.resolve("child")))
    }
}
