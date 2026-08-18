package dev.provenance.recorder.activation

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.provenance.core.Manifest
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Activation must degrade, never die. A single root whose session failed to start used to abort
 * the whole activity — after [RecorderState.activate] but before the status-bar refresh — so
 * [RecorderState] claimed "active" while no widget ever rendered and no session existed: silent
 * plugin death with no indicator and no signal to the student. (The real-world trigger was a
 * `kotlin.NotImplementedError` raised by an unimplemented filesystem operation on WSL — an
 * [Error], which is why the per-root boundary catches [Throwable], not [Exception].)
 *
 * HEAVY because the roots must have resolvable nio paths: activation only attempts a session
 * start for a root it can resolve on the real filesystem, and a light fixture's in-memory VFS
 * has none.
 */
class ActivationResilienceTest : HeavyPlatformTestCase() {

    private fun manifest(assignmentId: String) =
        Manifest(assignmentId, "fa26", "2026-09-15T00:00:00Z", listOf("$assignmentId.py"), "a".repeat(128))

    /** A real on-disk directory under the project base dir, standing in for a discovered root. */
    private fun root(name: String): VirtualFile =
        WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            VfsUtil.createDirectoryIfMissing(getOrCreateProjectBaseDir(), name)
        }

    /** The key activation stores a root under: its resolved real path. */
    private fun stateKeyOf(root: VirtualFile): Path =
        root.toNioPath().let { runCatching { it.toRealPath() }.getOrDefault(it.normalize()) }

    fun testOneRootFailingToStartDoesNotStopTheNextRootFromStarting() = runBlocking {
        val bad = root("bad")
        val good = root("good")
        val started = mutableListOf<Path>()
        val activity = RecorderActivationActivity(
            sessionStarter = { _, r, _ ->
                if (r == stateKeyOf(bad)) throw NotImplementedError("an operation is not implemented")
                started.add(r)
            },
            refreshWidget = {},
            discoverer = { _, _ ->
                listOf(DiscoveredManifest(bad, manifest("hw-bad")), DiscoveredManifest(good, manifest("hw-good")))
            },
        )

        activity.execute(project)

        assertEquals("a failing root must not abort the roots after it", listOf(stateKeyOf(good)), started)
    }

    fun testStatusBarWidgetIsRefreshedEvenWhenARootFailsToStart() = runBlocking {
        val refreshes = AtomicInteger()
        val bad = root("refresh-bad")
        val activity = RecorderActivationActivity(
            sessionStarter = { _, _, _ -> throw NotImplementedError("an operation is not implemented") },
            refreshWidget = { refreshes.incrementAndGet() },
            discoverer = { _, _ -> listOf(DiscoveredManifest(bad, manifest("hw-bad"))) },
        )

        activity.execute(project)

        assertEquals("the widget refresh must always run", 1, refreshes.get())
    }

    fun testFailedRootStaysActivatedAndIsMarkedDegradedWithItsReason() = runBlocking {
        val bad = root("degraded")
        val activity = RecorderActivationActivity(
            sessionStarter = { _, _, _ -> throw IllegalStateException("provenance dir is read-only") },
            refreshWidget = {},
            discoverer = { _, _ -> listOf(DiscoveredManifest(bad, manifest("hw-bad"))) },
        )

        activity.execute(project)

        val state = project.service<RecorderState>()
        assertTrue("a failed root must stay activated, not be rolled back", state.isActive)
        assertTrue("the failed root must be marked degraded", state.isDegraded(stateKeyOf(bad)))
        val reason = state.degradedRoots[stateKeyOf(bad)]
        assertNotNull(reason)
        assertTrue(
            "the degraded reason must carry the failure detail, got: $reason",
            reason!!.contains("provenance dir is read-only"),
        )
    }

    fun testRootThatStartsCleanlyIsNotMarkedDegraded() = runBlocking {
        val good = root("clean")
        val activity = RecorderActivationActivity(
            sessionStarter = { _, _, _ -> },
            refreshWidget = {},
            discoverer = { _, _ -> listOf(DiscoveredManifest(good, manifest("hw-good"))) },
        )

        activity.execute(project)

        val state = project.service<RecorderState>()
        assertTrue(state.isActive)
        assertTrue("a healthy activation must record no degradation", state.degradedRoots.isEmpty())
    }
}
