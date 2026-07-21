package dev.provenance.recorder.activation

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import dev.provenance.recorder.session.RecorderSessionManager
import dev.provenance.recorder.statusbar.RecordingStatusBarWidgetFactory
import java.nio.file.Paths

/**
 * Runs once per project open. PRD §4.1: activate only for workspaces whose manifest(s) verify;
 * otherwise do nothing observable. Discovers every nested verified manifest under the project
 * (recursive walk from the project dir + every content root) and starts one concurrent session
 * per discovered root, keyed by that root's resolved real path.
 *
 * [discoverer] is injectable for tests (via the internal secondary constructor); production
 * wires the real VFS-backed [discoverManifestRoots].
 */
class RecorderActivationActivity internal constructor(
    private val discoverer: (Project, String) -> List<DiscoveredManifest>,
) : ProjectActivity {

    constructor() : this(::discoverManifestRoots)

    override suspend fun execute(project: Project) {
        val discovered = discoverer(project, COURSE_PUBLIC_KEY_HEX)
        val state = project.service<RecorderState>()
        state.deactivateAll()
        val manager = project.service<RecorderSessionManager>()
        for (found in discovered) {
            // Activation state (the privacy gate / status bar) must not silently no-op just
            // because a real filesystem path can't be resolved (e.g. an in-memory test
            // fixture) — only *starting a session* additionally requires one.
            val resolvedRoot = runCatching { found.root.toNioPath() }.getOrNull()
                ?.let { runCatching { it.toRealPath() }.getOrDefault(it.normalize()) }
            val stateKey = resolvedRoot ?: Paths.get(found.root.path)
            state.activate(stateKey, found.manifest)
            if (resolvedRoot != null) {
                manager.startFromActivation(resolvedRoot, found.manifest)
            } else {
                LOG.info("discovered manifest at ${found.root.path} has no resolvable nio path; recording not started")
            }
        }
        refreshStatusBarWidget(project)
    }

    companion object {
        private val LOG = Logger.getInstance(RecorderActivationActivity::class.java)
    }
}

internal fun refreshStatusBarWidget(project: Project) {
    if (project.isDisposed) return
    project.service<StatusBarWidgetsManager>().updateWidget(RecordingStatusBarWidgetFactory::class.java)
}
