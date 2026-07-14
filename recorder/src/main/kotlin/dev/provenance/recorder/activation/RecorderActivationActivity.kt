package dev.provenance.recorder.activation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Condition
import com.intellij.openapi.wm.WindowManager
import dev.provenance.recorder.session.RecorderSessionManager
import dev.provenance.recorder.statusbar.RecordingStatusBarWidget

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
 * once recording is active, absent otherwise. Runs on the EDT (widget install is a Swing
 * operation), synchronously if already on it, else via invokeLater.
 *
 * Why a direct add rather than the original `StatusBarWidgetsManager.updateAllWidgets()`
 * (the user-observed bug: recording worked but the indicator never appeared):
 *
 *  - Activation runs on a background ProjectActivity coroutine, and the manager installs
 *    widgets asynchronously on its own service coroutine scope after re-evaluating
 *    `isAvailable`. That async path is exactly what silently failed to surface the widget
 *    (reproduced headlessly: after `updateWidget`, `wasWidgetCreated` stays false and the
 *    widget never lands in the status bar). For a shipped-to-students disclosure signal
 *    (PRD §4.1) the indicator must appear reliably, not best-effort.
 *  - Adding straight to the project's status bar on the EDT is synchronous and observable.
 *    It is idempotent: `IdeStatusBarImpl` dedups by widget id, and we skip when the widget
 *    is already present — so it never races/duplicates with the platform's own
 *    factory-init pass (which covers the activation-before-frame ordering).
 *  - On a cold first open the frame's status bar may not exist yet; `getStatusBar` returns
 *    null and we no-op — the platform's factory-init pass then shows it, since `isActive`
 *    is now true. invokeLater also defers us past frame construction in the common case.
 */
internal fun refreshStatusBarWidget(project: Project) {
    val app = ApplicationManager.getApplication()
    val task = Runnable {
        if (project.isDisposed) return@Runnable
        val statusBar = WindowManager.getInstance().getStatusBar(project) ?: return@Runnable
        val active = project.service<RecorderState>().isActive
        val existing = statusBar.getWidget(RecordingStatusBarWidget.WIDGET_ID)
        if (active && existing == null) {
            statusBar.addWidget(RecordingStatusBarWidget(project), RecordingStatusBarWidget.WIDGET_ID)
        } else if (!active && existing != null) {
            statusBar.removeWidget(RecordingStatusBarWidget.WIDGET_ID)
        }
    }
    if (app.isDispatchThread) task.run() else app.invokeLater(task, Condition<Any> { project.isDisposed })
}
