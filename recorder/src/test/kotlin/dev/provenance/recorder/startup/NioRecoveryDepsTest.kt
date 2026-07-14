package dev.provenance.recorder.startup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/** Real filesystem via JUnit 4 TemporaryFolder; no IntelliJ platform needed. */
class NioRecoveryDepsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `readSlogFile returns Ok for an existing file`() = runBlocking {
        val f = tmp.newFile("session-a.slog")
        f.writeText("hello-slog")
        val deps = NioRecoveryDeps(tmp.root.absolutePath)
        assertEquals(SlogReadResult.Ok("hello-slog"), deps.readSlogFile(f.absolutePath))
    }

    @Test
    fun `readSlogFile returns Err not_found for a missing file`() = runBlocking {
        val deps = NioRecoveryDeps(tmp.root.absolutePath)
        val result = deps.readSlogFile("${tmp.root.absolutePath}/missing.slog")
        assertEquals(SlogReadResult.Err("not_found"), result)
    }

    @Test
    fun `rename moves the file and the original path no longer exists`() = runBlocking {
        val f = tmp.newFile("session-b.slog")
        f.writeText("payload")
        val deps = NioRecoveryDeps(tmp.root.absolutePath)
        val to = "${f.absolutePath}.corrupt-x"

        deps.rename(f.absolutePath, to)

        assertFalse("original gone", f.exists())
        assertTrue("moved to quarantine", Files.exists(java.nio.file.Path.of(to)))
        assertEquals("payload", Files.readString(java.nio.file.Path.of(to)))
    }

    @Test
    fun `listSlogFiles returns only slog-suffixed names, not meta or unrelated`() = runBlocking {
        tmp.newFile("session-1.slog")
        tmp.newFile("session-1.slog.meta")
        tmp.newFile("notes.txt")
        tmp.newFile("session-2.slog")
        val deps = NioRecoveryDeps(tmp.root.absolutePath)

        val names = deps.listSlogFiles(tmp.root.absolutePath).sorted()
        assertEquals(listOf("session-1.slog", "session-2.slog"), names)
    }

    @Test
    fun `listSlogFiles returns empty for a non-existent directory`() = runBlocking {
        val deps = NioRecoveryDeps("${tmp.root.absolutePath}/nope")
        assertEquals(emptyList<String>(), deps.listSlogFiles("${tmp.root.absolutePath}/nope"))
    }

    @Test
    fun `end-to-end recovery over a real directory quarantines a corrupt slog`() = runBlocking {
        val f = tmp.newFile("session-c.slog")
        f.writeText("{ not valid json\n")
        val deps = NioRecoveryDeps(tmp.root.absolutePath)

        val decision = recoverPreviousSession(deps)

        assertTrue(decision is RecoveryDecision.PreviousSessionCorrupt)
        assertFalse("original moved out of live path", f.exists())
    }
}
