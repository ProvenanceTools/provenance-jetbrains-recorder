package dev.provenance.recorder.activation

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager

/**
 * Runs once per project open. PRD §4.1: activate only when the workspace-root
 * manifest verifies; otherwise do nothing observable (RecorderState stays inactive,
 * no I/O, no UI).
 *
 * The [loader] is injectable for tests (via the internal secondary constructor);
 * production uses the no-arg public constructor, which wires the real VFS-backed
 * [loadAndVerifyManifest]. An explicit no-arg constructor (rather than a Kotlin
 * default-parameter primary constructor) is used so the platform can instantiate
 * the extension by reflection without ambiguity.
 */
class RecorderActivationActivity internal constructor(
    private val loader: (Project, String) -> ManifestActivation,
) : ProjectActivity {

    constructor() : this(::loadAndVerifyManifest)

    override suspend fun execute(project: Project) {
        val result = loader(project, COURSE_PUBLIC_KEY_HEX)
        val state = project.service<RecorderState>()
        when (result) {
            is ManifestActivation.Active -> state.activate(result.manifest)
            is ManifestActivation.Inactive -> state.deactivate()
        }
        refreshStatusBarWidget(project)
    }
}

/**
 * Re-checks status-bar widget availability after the (asynchronous) activation
 * decision lands. Extracted so this file compiles before Task 6's widget factory
 * exists, and so Task 6 need not touch this file.
 * StatusBarWidgetsManager.updateAllWidgets() re-evaluates every factory's
 * isAvailable(project), which is how the recording widget appears once
 * RecorderState.isActive flips to true.
 */
internal fun refreshStatusBarWidget(project: Project) {
    project.service<StatusBarWidgetsManager>().updateAllWidgets()
}
