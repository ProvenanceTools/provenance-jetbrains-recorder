package dev.provenance.recorder.commands

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import dev.provenance.recorder.failure.DegradedModeNotifier
import dev.provenance.recorder.session.RecorderSessionManager

/**
 * "Provenance: Prepare Submission Bundle" — the UI trigger for the bundle seal, matching the VS
 * Code recorder's `provenance.prepareSubmissionBundle` command (PRD §4.6 / §5.3). It only
 * orchestrates: enabled iff a session is active, it runs the (already-tested, untouched) pure
 * seal off the EDT via a background task and reports the outcome as a balloon. All seal logic
 * lives in [RecorderSessionManager.sealActiveSession] → [sealBundle]; this class adds no
 * behavior of its own.
 */
class PrepareSubmissionBundleAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null && project.service<RecorderSessionManager>().activeSession != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Seal does synchronous file I/O (read .slog, zip the bundle) — never on the EDT.
        object : Task.Backgroundable(project, "Preparing Provenance submission bundle", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = project.service<RecorderSessionManager>().sealActiveSession()
                notify(project, result)
            }
        }.queue()
    }

    private fun notify(project: Project, result: SealResult) {
        val (message, type) = when (result) {
            is SealResult.Ok ->
                if (result.chainBroken || result.unreadableSession) {
                    "Provenance bundle saved to ${result.bundlePath}. Integrity issues were detected " +
                        "in the recording and will be reviewed by course staff." to NotificationType.WARNING
                } else {
                    "Provenance bundle saved to ${result.bundlePath}." to NotificationType.INFORMATION
                }
            is SealResult.NoSessions -> "No session data to seal." to NotificationType.WARNING
            is SealResult.WriteError -> "Bundle write error: ${result.message}" to NotificationType.ERROR
        }
        ApplicationManager.getApplication().invokeLater {
            Notification(DegradedModeNotifier.GROUP_ID, message, type).notify(project)
        }
    }
}
