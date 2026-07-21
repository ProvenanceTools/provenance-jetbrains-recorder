package dev.provenance.recorder

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.Canonical
import dev.provenance.core.ChainCheck
import dev.provenance.core.Ed25519
import dev.provenance.core.FixedClock
import dev.provenance.core.HashedEnvelope
import dev.provenance.core.Manifest
import dev.provenance.core.ParseResult
import dev.provenance.core.parseEntries
import dev.provenance.core.serializeEntry
import dev.provenance.core.validateChain
import dev.provenance.recorder.commands.SealResult
import dev.provenance.recorder.commands.sealBundle
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.session.ActivatedWorkspace
import dev.provenance.recorder.session.RecordingSessionController
import dev.provenance.recorder.session.createSessionHost
import dev.provenance.recorder.startup.NioRecoveryDeps
import dev.provenance.recorder.startup.RecoveryDecision
import dev.provenance.recorder.startup.recoverPreviousSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.ScheduledFuture

/**
 * Plan 8's correctness gate: a session that starts *after* a real startup chain-recovery
 * (against a genuinely corrupt/truncated prior .slog on a real filesystem, via the real
 * NioRecoveryDeps — no fakes) must still produce a hash chain that core's validateChain
 * accepts, and a bundle sealed from it must still be accepted by the real, unmodified
 * Provenance analyzer (packages/analysis-core in the monorepo). Recovery only ever renames
 * the corrupt file (quarantine) and appends new entries to a brand-new session file — it
 * never rewrites or reorders anything, which is what makes both of those hold.
 *
 * This test produces the sealed bundle and asserts the Kotlin-side chain check; the
 * companion Node-side analysis-core validation (loadBundle + runValidation) is run
 * separately against the printed bundle path — see docs/plans/2026-07-14-doc-events-seal.md
 * Task 13 for the exact invocation this mirrors.
 */
class EndToEndRecoveryValidationTest : BasePlatformTestCase() {
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

    private fun manifestAndCourseKey(): Pair<Manifest, ByteArray> {
        val coursePriv = Ed25519.hexToBytes("e1cd3820d5d4867defcd98e4436a80d92e99db284451b7595e75a66a4e8c7b75")
        val payload = buildJsonObject {
            put("assignment_id", "hw03")
            put("semester", "fa26")
            put("issued_at", "2026-07-14T00:00:00Z")
            putJsonArray("files_under_review") { add("hw.py") }
        }.toString()
        val sig = Ed25519.bytesToHex(Ed25519.sign(Canonical.canonicalize(payload).toByteArray(Charsets.UTF_8), coursePriv))
        return Manifest("hw03", "fa26", "2026-07-14T00:00:00Z", listOf("hw.py"), sig) to coursePriv
    }

    /** Writes a real .slog file to [provDir] via the real session host + serializer. */
    private fun writeRealSlog(provDir: Path, sessionId: String, danglingOrComplete: Boolean): Path {
        val collected = mutableListOf<HashedEnvelope>()
        val h = createSessionHost(sessionId, FixedClock()) { collected.add(it) }
        h.emit("session.start", buildJsonObject { put("session_id", sessionId) })
        h.emit("doc.change", buildJsonObject { put("path", "a.txt") })
        if (!danglingOrComplete) {
            // corrupt path handled by caller (tampers after writing)
        } else {
            h.emit("session.end", buildJsonObject { put("reason", "closed") })
        }
        val text = collected.joinToString("") { serializeEntry(it) }
        val path = provDir.resolve("session-$sessionId.slog")
        Files.createDirectories(provDir)
        Files.write(path, text.toByteArray(Charsets.UTF_8))
        return path
    }

    private fun readNewSessionEntries(c: RecordingSessionController): List<HashedEnvelope> {
        c.flush()
        val text = String(Files.readAllBytes(c.slogPath), Charsets.UTF_8)
        return (parseEntries(text) as ParseResult.Ok).entries
    }

    fun testRecoveryFromCorruptPriorSessionStillProducesAValidChainAndAnalyzerAcceptedBundle() {
        val (manifest, _) = manifestAndCourseKey()
        val wsRoot = Files.createTempDirectory("recovery-corrupt-ws")
        val provDir = wsRoot.resolve(".provenance")

        // Seed a real, on-disk .slog whose second line is tampered (breaks the hash chain) —
        // a genuinely corrupt prior session, not a fake.
        val priorPath = writeRealSlog(provDir, "prior-corrupt-session", danglingOrComplete = true)
        val lines = String(Files.readAllBytes(priorPath), Charsets.UTF_8).trimEnd('\n').split("\n").toMutableList()
        lines[1] = lines[1].replace("a.txt", "TAMPERED.txt")
        Files.write(priorPath, (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))

        // Real recovery: real filesystem, real NioRecoveryDeps, real rename.
        val decision = runBlocking { recoverPreviousSession(NioRecoveryDeps(provDir.toString())) }
        assertTrue("expected corruption to be detected: $decision", decision is RecoveryDecision.PreviousSessionCorrupt)
        val quarantinedPath = (decision as RecoveryDecision.PreviousSessionCorrupt).quarantinedPath
        assertFalse("original corrupt file must be gone (renamed, not copied)", Files.exists(priorPath))
        assertTrue("quarantine file must exist on disk", Files.exists(Paths.get(quarantinedPath)))

        myFixture.configureByText("hw.py", "print(1)\n")
        val controller = RecordingSessionController(
            activated = ActivatedWorkspace(manifest, provDir, wsRoot),
            project = project,
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "com.aaryanmehta.provenance.recorder",
            parentDisposable = testRootDisposable,
            clock = FixedClock(0, Instant.parse("2026-07-14T00:00:00Z")),
            scheduler = NoopScheduler(),
            recovery = decision,
        )

        // The new session's log must open with session.start (no prev_session_id — corruption
        // is never chain-linked) followed immediately by recorder.recovered_from_corruption.
        val opening = readNewSessionEntries(controller)
        assertEquals("session.start", opening[0].kind)
        assertEquals(
            "corruption must never set prev_session_id",
            JsonNull,
            opening[0].data["prev_session_id"],
        )
        assertEquals(1L, opening[1].seq)
        assertEquals("recorder.recovered_from_corruption", opening[1].kind)
        assertEquals(quarantinedPath, opening[1].data["quarantined_path"]?.jsonPrimitive?.content)

        val doc = myFixture.getDocument(myFixture.file)
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "print(2)\n") }
        val finalText = doc.text
        WriteCommandAction.runWriteCommandAction(project) { FileDocumentManager.getInstance().saveDocument(doc) }
        controller.endSession("submit")
        Files.write(wsRoot.resolve("hw.py"), finalText.toByteArray(Charsets.UTF_8))

        // Append-only + correctness gate: the CONTINUED (new) session's own chain must still
        // validate cleanly after recovery — recovery never rewrites/reorders anything.
        val finalEntries = readNewSessionEntries(controller)
        assertEquals("recovery/degraded wiring must not have broken the chain", ChainCheck.Valid, validateChain(finalEntries))

        val outDir: Path = Paths.get("build/e2e-recovery-bundle")
        if (Files.exists(outDir)) outDir.toFile().deleteRecursively()
        Files.createDirectories(outDir)

        val result = sealBundle(
            provenanceDir = provDir,
            workspaceRoot = wsRoot,
            assignmentId = "hw03",
            semester = "fa26",
            filesUnderReview = listOf("hw.py"),
            sessionPrivkey = controller.sessionPrivkey,
            computeExtensionHash = { dev.provenance.core.Sha256.hex("provjet-dev-extension") },
            outputDir = outDir,
            now = { Instant.parse("2026-07-14T12:00:00Z") },
        )
        assertTrue("seal failed: $result", result is SealResult.Ok)
        val ok = result as SealResult.Ok
        assertFalse("chain must be intact even after recovery", ok.chainBroken)
        assertFalse("session must be readable", ok.unreadableSession)
        assertTrue(Files.exists(ok.bundlePath))
        println("E2E_RECOVERY_BUNDLE_PATH=" + ok.bundlePath.toAbsolutePath())
    }

    fun testRecoveryFromDanglingPriorSessionLinksPrevSessionId() {
        val (manifest, _) = manifestAndCourseKey()
        val wsRoot = Files.createTempDirectory("recovery-dangling-ws")
        val provDir = wsRoot.resolve(".provenance")

        // A dangling prior session: valid chain, no trailing session.end (models a crash).
        writeRealSlog(provDir, "prior-dangling-session", danglingOrComplete = false)

        val decision = runBlocking { recoverPreviousSession(NioRecoveryDeps(provDir.toString())) }
        assertTrue("expected a dangling decision: $decision", decision is RecoveryDecision.PreviousSessionDangling)
        assertEquals("prior-dangling-session", (decision as RecoveryDecision.PreviousSessionDangling).prevSessionId)

        myFixture.configureByText("hw.py", "print(1)\n")
        val controller = RecordingSessionController(
            activated = ActivatedWorkspace(manifest, provDir, wsRoot),
            project = project,
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "com.aaryanmehta.provenance.recorder",
            parentDisposable = testRootDisposable,
            clock = FixedClock(0, Instant.parse("2026-07-14T00:00:00Z")),
            scheduler = NoopScheduler(),
            recovery = decision,
        )

        val entries = readNewSessionEntries(controller)
        assertEquals("session.start", entries[0].kind)
        assertEquals("prior-dangling-session", entries[0].data["prev_session_id"]?.jsonPrimitive?.content)
        assertEquals(ChainCheck.Valid, validateChain(entries))
        controller.endSession("submit")
    }
}
