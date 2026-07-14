package dev.provenance.recorder

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.ChainCheck
import dev.provenance.core.FixedClock
import dev.provenance.core.HashedEnvelope
import dev.provenance.core.ParseResult
import dev.provenance.core.parseEntries
import dev.provenance.core.serializeEntry
import dev.provenance.core.validateChain
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.session.ActivatedWorkspace
import dev.provenance.recorder.session.RecorderSessionManager
import dev.provenance.recorder.session.createSessionHost
import dev.provenance.recorder.startup.NioRecoveryDeps
import dev.provenance.recorder.startup.RecoveryDecision
import dev.provenance.recorder.startup.recoverPreviousSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ScheduledFuture

/**
 * Drives the recovery + close lifecycle through the REAL [RecorderSessionManager]
 * assembly (start → wire every coordinator → stop), rather than the controller in
 * isolation as EndToEndRecoveryValidationTest does. This proves the manager itself
 * correctly:
 *
 *  - writes a trailing `session.end` and keeps the chain valid when a session is
 *    stopped (the project-close path — the platform disposes the manager, which calls
 *    stop());
 *  - consumes a real startup-recovery decision computed against a genuinely dangling
 *    prior `.slog` and links it via `prev_session_id`;
 *  - consumes a real recovery decision for a genuinely corrupt prior `.slog`,
 *    quarantining it and surfacing `recorder.recovered_from_corruption` at seq 1 with
 *    no `prev_session_id` linkage.
 */
class ManagerLifecycleGateTest : BasePlatformTestCase() {
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
        wsRoot = Files.createTempDirectory("mgr-lifecycle-ws").toRealPath()
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

    private fun activated(provDir: Path) =
        ActivatedWorkspace(HeavyTestManifests.signedManifestObject(), provDir, wsRoot)

    private fun startVia(manager: RecorderSessionManager, provDir: Path, recovery: RecoveryDecision) =
        manager.start(
            activated = activated(provDir),
            recovery = recovery,
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "edu.berkeley.provenance.recorder",
            clock = FixedClock(0, Instant.parse("2026-07-14T00:00:00Z")),
            scheduler = NoopScheduler(),
            vfsDispatch = { it() },
        )

    /** Writes a real prior `.slog` via the real session host + serializer. */
    private fun writePriorSlog(provDir: Path, sessionId: String, complete: Boolean): Path {
        val collected = mutableListOf<HashedEnvelope>()
        val h = createSessionHost(sessionId, FixedClock()) { collected.add(it) }
        h.emit("session.start", buildJsonObject { put("session_id", sessionId) })
        h.emit("doc.change", buildJsonObject { put("path", "a.txt") })
        if (complete) h.emit("session.end", buildJsonObject { put("reason", "closed") })
        val text = collected.joinToString("") { serializeEntry(it) }
        Files.createDirectories(provDir)
        val path = provDir.resolve("session-$sessionId.slog")
        Files.write(path, text.toByteArray(Charsets.UTF_8))
        return path
    }

    private fun readEntries(slog: Path): List<HashedEnvelope> =
        (parseEntries(Files.readString(slog)) as ParseResult.Ok).entries

    fun testManagerStopWritesSessionEndAndKeepsChainValid() {
        val provDir = wsRoot.resolve(".provenance")
        val manager = project.service<RecorderSessionManager>()
        val session = startVia(manager, provDir, RecoveryDecision.CleanStart)
        session.controller.append("doc.change", buildJsonObject { put("path", "hw.py") })

        val slog = session.controller.slogPath
        manager.stop() // models project close: disposes the session → endSession → session.end

        val entries = readEntries(slog)
        val kinds = entries.map { it.kind }
        assertEquals("session.start", kinds.first())
        assertEquals("closing the session must append a trailing session.end", "session.end", kinds.last())
        assertEquals("chain must stay valid across the full lifecycle", ChainCheck.Valid, validateChain(entries))
        assertNull("clean first session has no manager active session after stop", manager.activeSession)
    }

    fun testManagerStartAfterDanglingPriorLinksPrevSessionId() {
        val provDir = wsRoot.resolve(".provenance")
        writePriorSlog(provDir, "prior-dangling", complete = false)

        val decision = runBlocking { recoverPreviousSession(NioRecoveryDeps(provDir.toString())) }
        assertTrue("a crash-truncated prior must read as dangling: $decision", decision is RecoveryDecision.PreviousSessionDangling)

        val manager = project.service<RecorderSessionManager>()
        val session = startVia(manager, provDir, decision)
        session.controller.flush()

        val entries = readEntries(session.controller.slogPath)
        assertEquals("session.start", entries[0].kind)
        assertEquals(
            "the manager must link the dangling prior via prev_session_id",
            "prior-dangling",
            entries[0].data["prev_session_id"]?.jsonPrimitive?.content,
        )
        assertEquals(ChainCheck.Valid, validateChain(entries))
    }

    fun testManagerStartAfterCorruptPriorQuarantinesAndEmitsRecovery() {
        val provDir = wsRoot.resolve(".provenance")
        val priorPath = writePriorSlog(provDir, "prior-corrupt", complete = true)
        // Tamper the second line: breaks the hash chain → genuine corruption.
        val lines = Files.readString(priorPath).trimEnd('\n').split("\n").toMutableList()
        lines[1] = lines[1].replace("a.txt", "TAMPERED.txt")
        Files.write(priorPath, (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))

        val decision = runBlocking { recoverPreviousSession(NioRecoveryDeps(provDir.toString())) }
        assertTrue("tampered prior must read as corrupt: $decision", decision is RecoveryDecision.PreviousSessionCorrupt)
        val quarantined = (decision as RecoveryDecision.PreviousSessionCorrupt).quarantinedPath
        assertFalse("corrupt original must be renamed away", Files.exists(priorPath))
        assertTrue("quarantine file must exist", Files.exists(Path.of(quarantined)))

        val manager = project.service<RecorderSessionManager>()
        val session = startVia(manager, provDir, decision)
        session.controller.flush()

        val entries = readEntries(session.controller.slogPath)
        assertEquals("session.start", entries[0].kind)
        assertEquals("corruption is never linked via prev_session_id", JsonNull, entries[0].data["prev_session_id"])
        assertEquals(1L, entries[1].seq)
        assertEquals("recorder.recovered_from_corruption", entries[1].kind)
        assertEquals(quarantined, entries[1].data["quarantined_path"]?.jsonPrimitive?.content)
        assertEquals(ChainCheck.Valid, validateChain(entries))
    }
}
