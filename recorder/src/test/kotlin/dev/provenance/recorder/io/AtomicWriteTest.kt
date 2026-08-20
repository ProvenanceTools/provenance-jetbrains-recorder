package dev.provenance.recorder.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
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

    // --- the outer handler must be Throwable-shaped -----------------------------
    //
    // Regression: an Error raised anywhere inside the try block sailed past
    // `catch (original: Exception)`, so the tmp file was never unlinked — the user's
    // .provenance/ directory held an orphaned tmp file from exactly this crash. IJent
    // answers unimplemented operations with kotlin.NotImplementedError, so both
    // Files.newByteChannel and Files.deleteIfExists are credible future sources here.

    /** An Error that is NOT one of the capability signals the seams absorb. */
    private class FsOperationError(message: String) : Error(message)

    @Test
    fun `an Error from the channel-open step is cleaned up after and rethrown unchanged`() {
        val target = tmp.root.toPath().resolve("out.txt")
        val boom = FsOperationError("An operation is not implemented: FILE_OPEN")
        var caught: Throwable? = null
        try {
            atomicWriteFile(
                target,
                "x".toByteArray(Charsets.UTF_8),
                BestEffortSync(),
                BestEffortMove(),
                { throw boom },
            )
        } catch (t: Throwable) {
            caught = t
        }
        assertSame("the original Throwable must propagate unmasked", boom, caught)
        assertTrue("no .tmp should survive, found: ${leftoverTmps()}", leftoverTmps().isEmpty())
        assertFalse(Files.exists(target))
    }

    @Test
    fun `an Error raised once the tmp file exists still unlinks the tmp file`() {
        // The open succeeds (a tmp file is on disk), then the write frame raises an
        // Error. This is the case that leaked a tmp file before the handler was widened.
        val target = tmp.root.toPath().resolve("out.txt")
        val boom = FsOperationError("An operation is not implemented: FILE_WRITE")
        var caught: Throwable? = null
        try {
            atomicWriteFile(
                target,
                "x".toByteArray(Charsets.UTF_8),
                BestEffortSync(),
                BestEffortMove(),
                { path -> REAL_CHANNEL_OPENER.open(path).also { throw boom } },
            )
        } catch (t: Throwable) {
            caught = t
        }
        assertSame("the original Throwable must propagate unmasked", boom, caught)
        assertTrue("no .tmp should survive, found: ${leftoverTmps()}", leftoverTmps().isEmpty())
        assertFalse(Files.exists(target))
    }

    @Test
    fun `an Error from the rename step is cleaned up after and rethrown unchanged`() {
        val target = tmp.root.toPath().resolve("out.txt")
        val boom = FsOperationError("An operation is not implemented: FILE_RENAME")
        var caught: Throwable? = null
        try {
            atomicWriteFile(
                target,
                "x".toByteArray(Charsets.UTF_8),
                BestEffortSync(),
                BestEffortMove { _, _ -> throw boom },
            )
        } catch (t: Throwable) {
            caught = t
        }
        assertSame("the original Throwable must propagate unmasked", boom, caught)
        assertTrue("no .tmp should survive, found: ${leftoverTmps()}", leftoverTmps().isEmpty())
        assertFalse(Files.exists(target))
    }

    @Test
    fun `a failing cleanup never masks the original Error`() {
        // The opener leaves a NON-EMPTY DIRECTORY at the tmp path and then raises an
        // Error, so the cleanup's own deleteIfExists fails (DirectoryNotEmptyException).
        // The caller must still see the original Error, not the cleanup failure.
        val target = tmp.root.toPath().resolve("out.txt")
        val boom = FsOperationError("An operation is not implemented: FILE_OPEN")
        var caught: Throwable? = null
        try {
            atomicWriteFile(
                target,
                "x".toByteArray(Charsets.UTF_8),
                BestEffortSync(),
                BestEffortMove(),
                { path ->
                    Files.createDirectory(path)
                    Files.write(path.resolve("child"), byteArrayOf(1))
                    throw boom
                },
            )
        } catch (t: Throwable) {
            caught = t
        }
        assertSame("cleanup failure must not replace the original error", boom, caught)
    }

    // --- atomicWriteFilePair: the rolling seal's json + sig ---------------------
    //
    // `manifest-<id>.json` and `manifest-<id>.sig` are a signature over a payload, so a
    // reader that catches a NEW json beside an OLD sig sees a seal that does not verify.
    // There is no multi-file rename anywhere, so the window cannot be closed — only shrunk,
    // by staging (write + fsync) EVERY file before renaming ANY of them.

    private fun pairTargets(): Pair<java.nio.file.Path, java.nio.file.Path> =
        tmp.root.toPath().resolve("manifest-s1.json") to tmp.root.toPath().resolve("manifest-s1.sig")

    @Test
    fun `pair write lands both files and leaves no tmp`() {
        val (json, sig) = pairTargets()
        atomicWriteFilePair(listOf(json to "{\"a\":1}", sig to "beef"))
        assertEquals("{\"a\":1}", String(Files.readAllBytes(json), Charsets.UTF_8))
        assertEquals("beef", String(Files.readAllBytes(sig), Charsets.UTF_8))
        assertTrue("no .tmp should survive, found: ${leftoverTmps()}", leftoverTmps().isEmpty())
    }

    /**
     * The staging property itself: no target is ever opened for writing. Every byte goes to a
     * `.tmp` sibling first, so a target can only ever change by a rename — which is what makes
     * a partially written signed artifact impossible for a reader (or a `git commit`) to see.
     */
    @Test
    fun `pair write never opens a target file directly`() {
        val (json, sig) = pairTargets()
        val opened = mutableListOf<java.nio.file.Path>()
        val opener = ChannelOpener { path ->
            opened.add(path)
            REAL_CHANNEL_OPENER.open(path)
        }
        atomicWriteFilePair(listOf(json to "{}", sig to "beef"), BestEffortSync(), BestEffortMove(), opener)
        assertEquals(2, opened.size)
        for (p in opened) {
            assertTrue("staged path must be a .tmp, was $p", p.fileName.toString().endsWith(".tmp"))
            assertNotEquals(json, p)
            assertNotEquals(sig, p)
        }
    }

    /**
     * Staging is ALL-OR-NOTHING before the first rename. When the second file cannot be
     * staged, neither target has been touched — the previous pair, whatever it was, is still
     * the one on disk, and it still verifies.
     */
    @Test
    fun `a staging failure on the second file leaves both targets untouched`() {
        val (json, sig) = pairTargets()
        Files.write(json, "old-json".toByteArray(Charsets.UTF_8))
        Files.write(sig, "old-sig".toByteArray(Charsets.UTF_8))
        var opens = 0
        val opener = ChannelOpener { path ->
            if (++opens == 2) throw IOException("disk full")
            REAL_CHANNEL_OPENER.open(path)
        }
        var caught: Exception? = null
        try {
            atomicWriteFilePair(listOf(json to "new-json", sig to "new-sig"), BestEffortSync(), BestEffortMove(), opener)
        } catch (e: Exception) {
            caught = e
        }
        assertTrue("the original error must not be masked", caught is IOException)
        assertEquals("old-json", String(Files.readAllBytes(json), Charsets.UTF_8))
        assertEquals("old-sig", String(Files.readAllBytes(sig), Charsets.UTF_8))
        assertTrue("no .tmp should survive, found: ${leftoverTmps()}", leftoverTmps().isEmpty())
    }

    /**
     * A filesystem that cannot atomic-move (WSL through IJent) must still get both files.
     * Every temp is fully written before any rename, so a plain move cannot expose a
     * partially written target — it only widens the window in which one is briefly absent.
     */
    @Test
    fun `pair write lands both files when atomic move is unsupported`() {
        val (json, sig) = pairTargets()
        atomicWriteFilePair(
            listOf(json to "{}", sig to "beef"),
            BestEffortSync { throw NotImplementedError("FILE_FORCE") },
            BestEffortMove { _, _ -> throw NotImplementedError("FILE_MOVE") },
        )
        assertEquals("{}", String(Files.readAllBytes(json), Charsets.UTF_8))
        assertEquals("beef", String(Files.readAllBytes(sig), Charsets.UTF_8))
        assertTrue("no .tmp should survive, found: ${leftoverTmps()}", leftoverTmps().isEmpty())
    }

    /**
     * A rename that genuinely fails mid-commit: the file already renamed is NOT rolled back
     * (it is a complete, fsynced file, and un-renaming it could only put older bytes back),
     * but the uncommitted temp is cleaned and the original error rethrown.
     */
    @Test
    fun `a rename failure mid-commit cleans the uncommitted tmp and rethrows`() {
        val (json, sig) = pairTargets()
        var moves = 0
        val mover = BestEffortMove { from, to ->
            if (++moves == 2) throw IOException("rename failed")
            Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        var caught: Exception? = null
        try {
            atomicWriteFilePair(listOf(json to "{}", sig to "beef"), BestEffortSync(), mover)
        } catch (e: Exception) {
            caught = e
        }
        assertTrue("the original error must not be masked", caught is IOException)
        assertEquals("{}", String(Files.readAllBytes(json), Charsets.UTF_8))
        assertFalse(Files.exists(sig))
        assertTrue("no .tmp should survive, found: ${leftoverTmps()}", leftoverTmps().isEmpty())
    }
}
