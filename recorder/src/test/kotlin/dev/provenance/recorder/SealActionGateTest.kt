package dev.provenance.recorder

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.FixedClock
import dev.provenance.recorder.commands.PrepareSubmissionBundleAction
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.session.ActivatedWorkspace
import dev.provenance.recorder.session.RecorderSessionManager
import dev.provenance.recorder.startup.RecoveryDecision
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ScheduledFuture

/**
 * Seal UI-trigger gate (PRD §4.6 / §5.3). Proves the "Provenance: Prepare Submission
 * Bundle" AnAction end to end:
 *
 *  - its enablement gate: disabled with no active session, enabled once one is live;
 *  - invoking [PrepareSubmissionBundleAction.actionPerformed] on a live session actually
 *    produces a sealed bundle ZIP on disk (the action's background-task path, not just
 *    the sealActiveSession() seam the all-signals gate covers).
 *
 * The produced bundle is copied to a stable build path and validated separately by the
 * real monorepo analysis-core (loadBundle + runValidation → 8/8), exactly as the existing
 * gates do — proving the action's output is analyzer-accepted, not merely well-formed.
 */
class SealActionGateTest : BasePlatformTestCase() {
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
        wsRoot = Files.createTempDirectory("seal-action-ws").toRealPath()
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

    private fun vfFor(name: String, content: String): VirtualFile {
        Files.writeString(wsRoot.resolve(name), content)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(wsRoot.resolve(name))!!
    }

    private fun actionEvent(action: PrepareSubmissionBundleAction) =
        TestActionEvent.createTestEvent(action) { dataId ->
            if (CommonDataKeys.PROJECT.`is`(dataId)) project else null
        }

    private fun startLiveSession(): RecorderSessionManager {
        val vf = vfFor("hw.py", "print(1)\n")
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
        val manager = project.service<RecorderSessionManager>()
        manager.start(
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
        // A real edit + save so the on-disk hw.py matches the event-reconstructed content
        // (check 8 submitted_code_match), yielding a genuine 8/8 bundle.
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "print(2)\n") }
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().saveDocument(doc) }
        }
        return manager
    }

    fun testActionDisabledWithoutActiveSession() {
        val action = PrepareSubmissionBundleAction()
        val e = actionEvent(action)
        action.update(e)
        assertFalse("no active session ⇒ the seal action is disabled/hidden", e.presentation.isEnabledAndVisible)
    }

    fun testActionPerformedSealsAnalyzerReadyBundle() {
        val manager = startLiveSession()
        // The real extension_hash is resolved from the plugin class loader, which the test
        // harness does not provide (classes load via PathClassLoader ⇒ descriptor is null), so
        // stand in for just that value. Everything else on the path — the action, the manager,
        // the seal, the chain, the signature — is the production code. Real resolution is
        // covered by the manual runIde pass in docs/manual-verification.md.
        manager.extensionHashOverride = { "0".repeat(64) }

        val action = PrepareSubmissionBundleAction()
        val enableEvent = actionEvent(action)
        action.update(enableEvent)
        assertTrue("a live session ⇒ the seal action is enabled", enableEvent.presentation.isEnabledAndVisible)

        // Invoke the real UI trigger. In unit-test mode Task.Backgroundable runs synchronously.
        action.actionPerformed(actionEvent(action))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val bundles = Files.list(wsRoot).use { s ->
            s.filter { it.fileName.toString().matches(Regex("hw03-bundle-.*\\.zip")) }.toList()
        }
        assertEquals("the seal action must produce exactly one bundle zip", 1, bundles.size)
        assertTrue("bundle must be a non-empty file", Files.size(bundles[0]) > 0)

        // Stash for the separate node analysis-core validation (loadBundle + runValidation).
        val outDir = Paths.get("build/e2e-seal-action")
        Files.createDirectories(outDir)
        val stable = outDir.resolve(bundles[0].fileName.toString())
        Files.copy(bundles[0], stable, StandardCopyOption.REPLACE_EXISTING)
        println("E2E_SEAL_ACTION_BUNDLE_PATH=" + stable.toAbsolutePath())
    }

    fun testTwoActiveSessionsSealsOnlyTheExplicitlyChosenOne() {
        val manager = startLiveSession() // "hw.py" under wsRoot, assignment "hw03"
        manager.extensionHashOverride = { "0".repeat(64) }

        // A second, independent assignment root + session.
        val wsRoot2 = Files.createTempDirectory("seal-action-ws2").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, wsRoot2.toString())
        Files.writeString(wsRoot2.resolve("hog.py"), "print('hog')\n")
        manager.start(
            activated = ActivatedWorkspace(
                HeavyTestManifests.signedManifestObject(assignmentId = "hog"),
                wsRoot2.resolve(".provenance"),
                wsRoot2,
            ),
            recovery = RecoveryDecision.CleanStart,
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "com.aaryanmehta.provenance.recorder",
            clock = FixedClock(0, Instant.parse("2026-07-14T00:00:00Z")),
            scheduler = NoopScheduler(),
            vfsDispatch = { it() },
        )

        // Seal the "hog" root directly through the manager (proves the manager side of the
        // multi-root seal path independent of the popup UI, which PlatformTestUtil cannot drive
        // headlessly) — the chooser UI itself is exercised manually per docs/manual-verification.md.
        val result = manager.sealSession(wsRoot2)
        assertTrue("sealing a specific root must succeed", result is dev.provenance.recorder.commands.SealResult.Ok)

        val hogBundles = Files.list(wsRoot2).use { s -> s.filter { it.fileName.toString().matches(Regex("hog-bundle-.*\\.zip")) }.toList() }
        assertEquals("exactly one bundle for the hog root", 1, hogBundles.size)
        val hw03Bundles = Files.list(wsRoot2).use { s -> s.filter { it.fileName.toString().contains("hw03") }.toList() }
        assertTrue("the hw03 root's bundle must never land under the hog root", hw03Bundles.isEmpty())

        // The real isolation check: sealing wsRoot2 must not have also sealed the hw03 session
        // sitting in wsRoot (its own outputDir). If sealSession ever leaked into sealing every
        // live session instead of only the chosen root, this is what would catch it — the
        // hw03Bundles check above never can, since sealSession's outputDir is always the sealed
        // session's own workspaceRoot, so an hw03 bundle could never land under wsRoot2 regardless.
        val wsRootBundlesAfterSeal = Files.list(wsRoot).use { s ->
            s.filter { it.fileName.toString().matches(Regex(".*-bundle-.*\\.zip")) }.toList()
        }
        assertTrue(
            "sealing wsRoot2 must not also seal the untouched hw03 root (wsRoot)",
            wsRootBundlesAfterSeal.isEmpty(),
        )

        // Stash for the same separate node analysis-core validation (loadBundle + runValidation)
        // the single-session test below already does — the multi-root path must produce an
        // equally analyzer-ready bundle, not just a same-named zip.
        val outDir = Paths.get("build/e2e-seal-action-multi")
        Files.createDirectories(outDir)
        val stable = outDir.resolve(hogBundles[0].fileName.toString())
        Files.copy(hogBundles[0], stable, StandardCopyOption.REPLACE_EXISTING)
        println("E2E_SEAL_ACTION_MULTI_BUNDLE_PATH=" + stable.toAbsolutePath())

        wsRoot2.toFile().deleteRecursively()
    }
}
