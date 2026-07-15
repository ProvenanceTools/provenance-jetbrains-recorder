package dev.provenance.recorder.activation

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import dev.provenance.recorder.session.RecorderSessionManager
import dev.provenance.recorder.statusbar.RecordingStatusBarWidgetFactory

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
            is ManifestActivation.Active -> {
                state.activate(result.manifest)
                // Privacy gate satisfied: start recording for this project. The session manager
                // runs startup chain-recovery, opens .provenance/, and wires every signal into a
                // live RecordingSessionController. No-ops if a session is already active.
                project.service<RecorderSessionManager>().startFromActivation(result.manifest)
            }
            is ManifestActivation.Inactive -> state.deactivate()
        }
        refreshStatusBarWidget(project)
    }
}

/**
 * Ensures the "Provenance: recording" status-bar indicator matches [RecorderState]: present
 * once recording is active, absent otherwise.
 *
 * Delegates to [StatusBarWidgetsManager], which re-evaluates
 * [dev.provenance.recorder.statusbar.RecordingStatusBarWidgetFactory.isAvailable] (gated on
 * [RecorderState.isActive]) and installs or removes the widget accordingly. The manager owns
 * the EDT hop and the frame-not-yet-built ordering, so no manual status-bar mutation here.
 *
 * This previously added to / removed from the [com.intellij.openapi.wm.StatusBar] directly,
 * because the manager's async install had been seen to silently not surface the widget. That
 * direct path used `StatusBar.addWidget`/`removeWidget`, which are private platform API
 * (`@ApiStatus.Internal`) and must not be used by plugins — see the SDK's Internal API
 * Migration page. The disclosure requirement it was protecting (PRD §4.1: the indicator must
 * actually appear, not best-effort) is enforced by StatusBarWidgetActivationGateTest, which
 * asserts real presence in the project's status bar rather than trusting this call.
 */
internal fun refreshStatusBarWidget(project: Project) {
    if (project.isDisposed) return
    project.service<StatusBarWidgetsManager>().updateWidget(RecordingStatusBarWidgetFactory::class.java)
}
