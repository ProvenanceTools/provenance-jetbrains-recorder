package dev.provenance.recorder

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.HashedEnvelope
import dev.provenance.core.FixedClock
import dev.provenance.core.ParseResult
import dev.provenance.core.parseEntries
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.session.ActivatedWorkspace
import dev.provenance.recorder.session.RecorderSessionManager
import dev.provenance.recorder.startup.RecoveryDecision
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ScheduledFuture

/**
 * Live-session workspace-scoping + manifest-filename gate (PRD §4.1 privacy scope,
 * CLAUDE.md "activation is scoped and privacy-preserving"). With a real live session
 * running through the real RecorderSessionManager over a real on-disk workspace, this
 * drives genuine document mutations through the production global DocumentListener and
 * asserts what the recorder records:
 *
 *  - an in-workspace reviewed file (`hw.py`) IS recorded (doc.change with its path);
 *  - the manifest file itself (`.provenance-manifest`) is NOT recorded
 *    (self-recording-loop hazard);
 *  - a file OUTSIDE the workspace root is NOT recorded.
 *
 * This exercises isRecordablePath live through DocWiring, not just as a pure unit.
 */
class ScopingGateTest : BasePlatformTestCase() {
    private class NoopScheduler : FlushScheduler {
        override fun scheduleAtFixedRate(periodMs: Long, task: Runnable): ScheduledFuture<*> =
            object : ScheduledFuture<Any?> {
                override fun cancel(m: Boolean) = true
                override fun isCancelled() = false
                override fun isDone() = false
                override fun get(): Any? = null
                override fun get(t: Long, u: java.util.concurrent.TimeUnit): Any? = null
                override fun getDelay(u: java.util.concurrent.TimeUnit) = 0L
                override fun compareTo(other: java.util.concurrent.Delayed?) = 0
            }
    }

    private lateinit var wsRoot: Path
    private lateinit var outsideRoot: Path

    override fun setUp() {
        super.setUp()
        wsRoot = Files.createTempDirectory("scoping-ws").toRealPath()
        outsideRoot = Files.createTempDirectory("scoping-outside").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, wsRoot.toString(), outsideRoot.toString())
    }

    override fun tearDown() {
        try {
            runCatching { project.service<RecorderSessionManager>().stop() }
            wsRoot.toFile().deleteRecursively()
            outsideRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun vfIn(root: Path, name: String, content: String): VirtualFile {
        Files.writeString(root.resolve(name), content)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(name))!!
    }

    private fun editAppend(vf: VirtualFile, text: String) {
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, text) }
    }

    private fun readEntries(slog: Path): List<HashedEnvelope> =
        (parseEntries(Files.readString(slog)) as ParseResult.Ok).entries

    fun testOnlyInWorkspaceReviewedFilesAreRecorded() {
        // Create all three files up front so their VFS entries exist before the session starts.
        val hw = vfIn(wsRoot, "hw.py", "print(1)\n")
        val manifestVf = vfIn(wsRoot, ".provenance-manifest", HeavyTestManifests.validManifestJson())
        val outside = vfIn(outsideRoot, "secret.py", "print('outside')\n")

        val manager = project.service<RecorderSessionManager>()
        val session = manager.start(
            activated = ActivatedWorkspace(HeavyTestManifests.signedManifestObject(), wsRoot.resolve(".provenance"), wsRoot),
            recovery = RecoveryDecision.CleanStart,
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "com.aaryanmehta.provenance.recorder",
            clock = FixedClock(0, Instant.parse("2026-07-14T00:00:00Z")),
            scheduler = NoopScheduler(),
            vfsDispatch = { it() },
        )

        // Drive a real keystroke into each file's Document — the global multicaster fires for all.
        editAppend(hw, "print(2)\n")
        editAppend(manifestVf, "\n") // must be dropped: manifest-filename filter
        editAppend(outside, "print('more')\n") // must be dropped: outside the workspace root

        session.controller.flush()
        val changePaths = readEntries(session.controller.slogPath)
            .filter { it.kind == "doc.change" }
            .map { it.data["path"]?.jsonPrimitive?.content }

        assertEquals("exactly one recordable doc.change (hw.py only)", listOf("hw.py"), changePaths)

        // Belt-and-suspenders: no event of any kind may reference the manifest or the outside file.
        val allText = Files.readString(session.controller.slogPath)
        assertFalse("the manifest file must never appear in the log", allText.contains(".provenance-manifest"))
        assertFalse("out-of-workspace paths must never appear in the log", allText.contains("secret.py"))
    }
}
