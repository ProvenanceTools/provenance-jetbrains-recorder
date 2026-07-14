package dev.provenance.recorder

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.Canonical
import dev.provenance.core.ChainCheck
import dev.provenance.core.Ed25519
import dev.provenance.core.FixedClock
import dev.provenance.core.GitEventPayload
import dev.provenance.core.HashedEnvelope
import dev.provenance.core.Manifest
import dev.provenance.core.ParseResult
import dev.provenance.core.Sha256
import dev.provenance.core.TerminalOpenPayload
import dev.provenance.core.parseEntries
import dev.provenance.core.toJsonObject
import dev.provenance.core.validateChain
import dev.provenance.recorder.commands.SealResult
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.session.ActivatedWorkspace
import dev.provenance.recorder.session.RecorderSessionManager
import dev.provenance.recorder.startup.RecoveryDecision
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ScheduledFuture

/**
 * THE GATE — an all-signals-live end-to-end run through the real RecorderSessionManager wiring:
 * a live session records doc.open/doc.change, a terminal.open, a git.event (which marks the
 * explanation tagger), and a genuine external write (fs.external_change, tagged explanation="git"
 * by the git mark), then seals through the manager's real seal seam. The produced bundle is left
 * at a stable build path and validated separately against the REAL monorepo analysis-core
 * (loadBundle + runValidation) — expected overall "pass", 8/8.
 *
 * This is a headless proof of the assembled whole (controller start, every coordinator wired into
 * the live append path, no double-emission, seal). It cannot prove real-IDE behavior (actual
 * project open, real terminal/git/paste while focused) — see the manual runIde checklist.
 */
class AllSignalsLiveGateTest : BasePlatformTestCase() {
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

    override fun setUp() {
        super.setUp()
        // Real on-disk workspace so production VFS/nio seams work; canonical (/private) form so
        // the platform's allowed-roots guard accepts it (mirrors ExternalChangeCoordinatorTest).
        wsRoot = Files.createTempDirectory("gate-ws").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, wsRoot.toString())
    }

    override fun tearDown() {
        try {
            runCatching { project.service<RecorderSessionManager>().stop() }
            wsRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun signedManifest(): Manifest {
        // Dev course keypair (scratchpad dev-key.txt) — same one the other E2E tests use.
        val coursePriv = Ed25519.hexToBytes("e1cd3820d5d4867defcd98e4436a80d92e99db284451b7595e75a66a4e8c7b75")
        val payload = buildJsonObject {
            put("assignment_id", "hw03")
            put("semester", "fa26")
            put("issued_at", "2026-07-14T00:00:00Z")
            putJsonArray("files_under_review") { add("hw.py") }
        }.toString()
        val sig = Ed25519.bytesToHex(Ed25519.sign(Canonical.canonicalize(payload).toByteArray(Charsets.UTF_8), coursePriv))
        return Manifest("hw03", "fa26", "2026-07-14T00:00:00Z", listOf("hw.py"), sig)
    }

    private fun vfFor(name: String, content: String): VirtualFile {
        Files.writeString(wsRoot.resolve(name), content)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(wsRoot.resolve(name))!!
    }

    private fun readEntries(slog: Path): List<HashedEnvelope> {
        val text = String(Files.readAllBytes(slog), Charsets.UTF_8)
        return (parseEntries(text) as ParseResult.Ok).entries
    }

    fun testAllSignalsLiveProduceAnalyzerReadyBundle() {
        val provDir = wsRoot.resolve(".provenance")
        val vf = vfFor("hw.py", "print(1)\n")
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }

        // Start the live session through the manager, with a synchronous VFS dispatch so the
        // external-change assertion is deterministic. Real localFs/nio seams (real files).
        val manager = project.service<RecorderSessionManager>()
        val session = manager.start(
            activated = ActivatedWorkspace(signedManifest(), provDir, wsRoot),
            recovery = RecoveryDecision.CleanStart,
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "edu.berkeley.provenance.recorder",
            clock = FixedClock(0, Instant.parse("2026-07-14T00:00:00Z")),
            scheduler = NoopScheduler(),
            vfsDispatch = { it() },
        )

        // (1) doc edit, then save. The feeder tracked the edit into the expected model, so the
        // save is clean (no false fs.external_change). Saving also leaves the buffer in sync with
        // disk — required before the external write below, since a dirty buffer + an external disk
        // change is a memory-disk conflict the test harness rejects.
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "print(2)\n") }
        ApplicationManager.getApplication().invokeAndWait {
            com.intellij.openapi.application.WriteAction.run<RuntimeException> {
                FileDocumentManager.getInstance().saveDocument(doc)
            }
        }

        // Checkpoint: the single keystroke must have produced exactly one doc.change — the feeder
        // (Plan 5) and DocWiring (Plan 4) coexisting must not double-log it. Measured here, before
        // the external write below, whose silent buffer reload legitimately drives its own
        // separate doc.change (a distinct signal, not a duplicate of this keystroke).
        session.controller.flush()
        assertEquals(
            "one keystroke → one doc.change (no feeder/DocWiring double-emit)",
            1,
            readEntries(session.controller.slogPath).count { it.kind == "doc.change" },
        )

        // (2) terminal signal via its now-open seam.
        project.service<dev.provenance.recorder.wiring.RecorderTerminalState>().emitTerminalOpen!!
            .invoke(TerminalOpenPayload(terminalId = "term-0", shell = "zsh", shellIntegration = true))

        // (3) git signal via its seam — this also marks the explanation tagger (markGit()).
        project.service<dev.provenance.recorder.wiring.RecorderGitState>().emit!!
            .invoke(GitEventPayload(operation = "state_change", commitSha = "deadbeef"))

        // (4) genuine external write — the LAST content event for hw.py. Because a git op was just
        // marked, the fs.external_change carries explanation="git" (checkout/reset suppression).
        val externalContent = "print(1)\nprint(2)\nimport external\n"
        Files.writeString(wsRoot.resolve("hw.py"), externalContent)
        VfsUtil.markDirtyAndRefresh(false, false, false, vf)

        session.controller.flush()
        val entries = readEntries(session.controller.slogPath)
        val kinds = entries.map { it.kind }

        assertEquals("chain valid across all live signals", ChainCheck.Valid, validateChain(entries))
        assertEquals("session.start", kinds.first())
        assertTrue("doc.open recorded", kinds.contains("doc.open"))
        assertTrue("doc.change recorded", kinds.contains("doc.change"))
        assertTrue("doc.save recorded", kinds.contains("doc.save"))
        assertTrue("terminal.open recorded", kinds.contains("terminal.open"))
        assertTrue("git.event recorded", kinds.contains("git.event"))

        val ext = entries.filter { it.kind == "fs.external_change" }
        assertEquals("exactly one fs.external_change (registry-reset dedup holds)", 1, ext.size)
        assertEquals("git op must explain the external change", "git", ext[0].data["explanation"]?.jsonPrimitive?.content)
        assertEquals(Sha256.hex(externalContent), ext[0].data["new_hash"]?.jsonPrimitive?.content)

        // (5) The newly-ported signals — exercise each so the sealed bundle carries every new event
        // kind and the analyzer gate proves they don't break chain/monotonic/validation.
        //   ext.snapshot — already auto-emitted at session start (PluginSnapshotWiring).
        assertTrue("ext.snapshot recorded at session start", kinds.contains("ext.snapshot"))
        //   focus.change — publish IDE (de)activation on the app bus.
        val frame = java.lang.reflect.Proxy.newProxyInstance(
            com.intellij.openapi.wm.IdeFrame::class.java.classLoader,
            arrayOf(com.intellij.openapi.wm.IdeFrame::class.java),
        ) { _, _, _ -> null } as com.intellij.openapi.wm.IdeFrame
        ApplicationManager.getApplication().messageBus
            .syncPublisher(com.intellij.openapi.application.ApplicationActivationListener.TOPIC)
            .applicationDeactivated(frame)
        //   ext.activate — publish a dynamic plugin load on the app bus.
        val descriptor = java.lang.reflect.Proxy.newProxyInstance(
            com.intellij.ide.plugins.IdeaPluginDescriptor::class.java.classLoader,
            arrayOf(com.intellij.ide.plugins.IdeaPluginDescriptor::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPluginId" -> com.intellij.openapi.extensions.PluginId.getId("com.example.copilot")
                "getVersion" -> "1.2.3"
                else -> null
            }
        } as com.intellij.ide.plugins.IdeaPluginDescriptor
        ApplicationManager.getApplication().messageBus
            .syncPublisher(com.intellij.ide.plugins.DynamicPluginListener.TOPIC)
            .pluginLoaded(descriptor)
        //   selection.change + clock.skew — through the same chained append path the wiring uses.
        session.controller.append(
            "selection.change",
            dev.provenance.recorder.events.buildSelectionChangePayload("hw.py", 1, 0, 2, 0, wasSelection = true).toJsonObject(),
        )
        session.controller.append("clock.skew", dev.provenance.core.ClockSkewPayload(1500).toJsonObject())

        session.controller.flush()
        val allEntries = readEntries(session.controller.slogPath)
        val allKinds = allEntries.map { it.kind }
        assertEquals("chain valid across every new signal kind", ChainCheck.Valid, validateChain(allEntries))
        for (k in listOf("ext.snapshot", "focus.change", "ext.activate", "selection.change", "clock.skew")) {
            assertTrue("$k recorded", allKinds.contains(k))
        }

        // Seal through the manager's real seal seam (deterministic hash + timestamp).
        val result = manager.sealActiveSession(
            now = { Instant.parse("2026-07-14T12:00:00Z") },
            computeExtensionHash = { Sha256.hex("provjet-dev-extension") },
        )
        assertTrue("seal failed: $result", result is SealResult.Ok)
        val ok = result as SealResult.Ok
        assertFalse("chain intact", ok.chainBroken)
        assertFalse("session readable", ok.unreadableSession)

        // Copy the sealed zip out of the temp workspace (deleted in tearDown) to a stable path
        // the separate node analysis-core validation reads.
        val outDir = Paths.get("build/e2e-all-signals")
        Files.createDirectories(outDir)
        val stable = outDir.resolve(ok.bundlePath.fileName.toString())
        Files.copy(ok.bundlePath, stable, StandardCopyOption.REPLACE_EXISTING)
        println("E2E_ALL_SIGNALS_BUNDLE_PATH=" + stable.toAbsolutePath())
    }
}
