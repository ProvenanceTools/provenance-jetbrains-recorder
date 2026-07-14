package dev.provenance.recorder

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.Canonical
import dev.provenance.core.Ed25519
import dev.provenance.core.FixedClock
import dev.provenance.core.Manifest
import dev.provenance.recorder.commands.SealResult
import dev.provenance.recorder.commands.sealBundle
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.session.ActivatedWorkspace
import dev.provenance.recorder.session.RecordingSessionController
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.ScheduledFuture

/**
 * End-to-end: drive a RecordingSessionController (session.start → doc.* → session.end),
 * seal the bundle, and leave the .zip at a stable path for the real analysis-core
 * validation gate (Plan 4 Task 13). The Node validation is run separately against the
 * produced zip; this test only produces it and asserts the seal succeeded.
 */
class EndToEndSealSmokeTest : BasePlatformTestCase() {
    private class NoopScheduler : FlushScheduler {
        override fun scheduleAtFixedRate(periodMs: Long, task: Runnable): ScheduledFuture<*> =
            object : ScheduledFuture<Any?> {
                override fun cancel(m: Boolean) = true
                override fun isCancelled() = false
                override fun isDone() = false
                override fun get(): Any? = null
                override fun get(t: Long, u: java.util.concurrent.TimeUnit): Any? = null
                override fun getDelay(u: java.util.concurrent.TimeUnit) = 0L
                override fun compareTo(o: java.util.concurrent.Delayed?) = 0
            }
    }

    fun testProduceAnalyzerReadyBundle() {
        // Dev course keypair (scratchpad dev-key.txt) — signs the assignment manifest.
        val coursePriv = Ed25519.hexToBytes("e1cd3820d5d4867defcd98e4436a80d92e99db284451b7595e75a66a4e8c7b75")
        val payload = buildJsonObject {
            put("assignment_id", "hw03")
            put("semester", "fa26")
            put("issued_at", "2026-07-14T00:00:00Z")
            putJsonArray("files_under_review") { add("hw.py") }
        }.toString()
        val sig = Ed25519.bytesToHex(Ed25519.sign(Canonical.canonicalize(payload).toByteArray(Charsets.UTF_8), coursePriv))
        val manifest = Manifest("hw03", "fa26", "2026-07-14T00:00:00Z", listOf("hw.py"), sig)

        val wsRoot = Files.createTempDirectory("e2e-ws")
        val provDir = wsRoot.resolve(".provenance")

        myFixture.configureByText("hw.py", "print(1)\n")

        val controller = RecordingSessionController(
            activated = ActivatedWorkspace(manifest, provDir, wsRoot),
            project = project,
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "edu.berkeley.provenance.recorder",
            parentDisposable = testRootDisposable,
            clock = FixedClock(0, Instant.parse("2026-07-14T00:00:00Z")),
            scheduler = NoopScheduler(),
            localFsOf = { true },
            nioPathOf = { vf -> wsRoot.resolve(vf.name) },
        )

        val doc = myFixture.getDocument(myFixture.file)
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "print(2)\n") }
        val finalText = doc.text
        WriteCommandAction.runWriteCommandAction(project) { FileDocumentManager.getInstance().saveDocument(doc) }
        controller.endSession("submit")

        // The reviewed file on disk must match the last recorded hash (doc.save) → check 8 matches.
        Files.write(wsRoot.resolve("hw.py"), finalText.toByteArray(Charsets.UTF_8))

        val outDir: Path = Paths.get("build/e2e-bundle")
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
        assertFalse("chain must be intact", ok.chainBroken)
        assertFalse("session must be readable", ok.unreadableSession)
        assertTrue(Files.exists(ok.bundlePath))
        println("E2E_BUNDLE_PATH=" + ok.bundlePath.toAbsolutePath())
    }
}
