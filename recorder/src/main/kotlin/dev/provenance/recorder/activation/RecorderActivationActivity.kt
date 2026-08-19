package dev.provenance.recorder.activation

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import dev.provenance.core.Manifest
import dev.provenance.recorder.session.RecorderSessionManager
import dev.provenance.recorder.statusbar.RecordingStatusBarWidgetFactory
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs once per project open. PRD §4.1: activate only for workspaces whose manifest(s) verify;
 * otherwise do nothing observable. Discovers every nested verified manifest under the project
 * (recursive walk from the project dir + every content root) and starts one concurrent session
 * per discovered root, keyed by that root's resolved real path.
 *
 * Resilience: one root whose session fails to start must never take the others — or the
 * student's only visible signal — down with it. Each root's start is isolated behind a
 * [Throwable] boundary (not [Exception]: the failure that motivated this was a
 * `kotlin.NotImplementedError` from an unimplemented filesystem operation on WSL, an [Error]
 * that slips straight through an `Exception` catch), the failed root stays activated and is
 * marked degraded with its reason so the status bar can render "not recording (error)", and
 * the widget refresh runs in a `finally` so activation can never leave [RecorderState] claiming
 * "active" with no indicator ever drawn. A root whose [com.intellij.openapi.vfs.VirtualFile] has
 * no filesystem path is degraded by the same rule: it is activated and can never record, so it
 * must not render as recording either.
 *
 * [sessionStarter], [refreshWidget] and [discoverer] are injectable for tests (via the internal
 * constructors); production wires the real VFS-backed [discoverManifestRoots], the real
 * [RecorderSessionManager] and the real status-bar refresh.
 */
class RecorderActivationActivity internal constructor(
    private val sessionStarter: suspend (Project, Path, Manifest) -> Unit,
    private val refreshWidget: (Project) -> Unit,
    private val discoverer: (Project, String) -> List<DiscoveredManifest>,
) : ProjectActivity {

    constructor() : this(::startSessionFromActivation, ::refreshStatusBarWidget, ::discoverManifestRoots)

    internal constructor(discoverer: (Project, String) -> List<DiscoveredManifest>) :
        this(::startSessionFromActivation, ::refreshStatusBarWidget, discoverer)

    override suspend fun execute(project: Project) {
        try {
            activate(project)
        } finally {
            // ALWAYS, even if discovery itself blew up: RecorderState may already say "active",
            // and an active gate with no rendered widget is the silent-death shape this guards.
            refreshWidget(project)
        }
    }

    private suspend fun activate(project: Project) {
        // The whole walk must happen under ONE read action: it reads
        // ProjectRootManager.contentRoots (@RequiresReadLock, assertion suppressed — so a race
        // with a module-root write action silently returns an incomplete root set) and then
        // traverses the VFS and reads each candidate manifest, all of which are read-lock
        // contracts inside PersistentFSImpl. A ProjectActivity coroutine is not the EDT, so
        // read access is never implicit here.
        val discovered = ReadAction.compute<List<DiscoveredManifest>, Throwable> {
            discoverer(project, COURSE_PUBLIC_KEY_HEX)
        }
        val state = project.service<RecorderState>()
        state.deactivateAll()
        for (found in discovered) {
            // Activation state (the privacy gate / status bar) must not silently no-op just
            // because a real filesystem path can't be resolved (e.g. an in-memory test
            // fixture) — only *starting a session* additionally requires one.
            val nioPath = runCatching { found.root.toNioPath() }
            val resolvedRoot = nioPath.getOrNull()
                ?.let { runCatching { it.toRealPath() }.getOrDefault(it.normalize()) }
            val stateKey = resolvedRoot ?: Paths.get(found.root.path)
            state.activate(stateKey, found.manifest)
            if (resolvedRoot != null) {
                try {
                    sessionStarter(project, resolvedRoot, found.manifest)
                } catch (c: CancellationException) {
                    // Project closing / activity cancelled — not a recording failure, and
                    // swallowing it would break structured concurrency.
                    throw c
                } catch (t: Throwable) {
                    // Per-root isolation: one broken assignment must not stop the others, and
                    // must not roll back its own activation — the student needs to SEE that
                    // this assignment is not recording.
                    LOG.warn("failed to start recording for ${found.root.path}; marking it degraded", t)
                    state.markDegraded(stateKey, degradedReason(t))
                }
            } else {
                // Activated but structurally unable to record: a session needs a real filesystem
                // path, and this root has none (an in-memory test fixture, or a non-local project
                // root in production). Marked degraded for the same reason a failed session start
                // is — unmarked, the root rendered the NORMAL "recording" indicator while nothing
                // was ever written, which is the active-but-silent failure this indicator exists
                // to make impossible. The cause goes to the log; the tooltip gets the short form.
                LOG.warn(
                    "discovered manifest at ${found.root.path} has no resolvable nio path; " +
                        "marking it degraded (recording not started)",
                    nioPath.exceptionOrNull(),
                )
                state.markDegraded(stateKey, "no local filesystem path (${found.root.fileSystem.protocol})")
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(RecorderActivationActivity::class.java)
    }
}

/** Short, student-facing failure text for the status-bar tooltip. */
internal fun degradedReason(t: Throwable): String {
    val detail = t.message?.takeIf { it.isNotBlank() } ?: return t.javaClass.simpleName
    return "${t.javaClass.simpleName}: $detail"
}

/** Production session start: the seam [RecorderActivationActivity] replaces in tests. */
internal suspend fun startSessionFromActivation(project: Project, root: Path, manifest: Manifest) {
    project.service<RecorderSessionManager>().startFromActivation(root, manifest)
}

internal fun refreshStatusBarWidget(project: Project) {
    if (project.isDisposed) return
    project.service<StatusBarWidgetsManager>().updateWidget(RecordingStatusBarWidgetFactory::class.java)
}
