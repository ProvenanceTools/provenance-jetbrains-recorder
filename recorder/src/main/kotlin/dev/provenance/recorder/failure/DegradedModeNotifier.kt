package dev.provenance.recorder.failure

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * Surfaces disk-full degraded mode to the user via a balloon notification (PRD §4.8:
 * "surface a notification"). The notification group is registered in plugin.xml via the
 * com.intellij.notificationGroup extension point
 * (https://plugins.jetbrains.com/docs/intellij/notification-balloons.html); the group is
 * resolved from the [GROUP_ID] string directly (NotificationGroupManager lookup is no
 * longer necessary on current platforms).
 *
 * Thread-safety: DiskFullHandler.handleWriteError may run on the writer's background flush
 * thread, and Notification construction/UI must not be assumed EDT-safe. This dispatches
 * via invokeLater so the balloon is always shown on the UI thread.
 */
class DegradedModeNotifier(private val project: Project) {
    fun notifyDegraded() {
        ApplicationManager.getApplication().invokeLater {
            Notification(
                GROUP_ID,
                "Disk full — recording has switched to a minimal safety log. " +
                    "Free disk space and restart the IDE to resume full recording.",
                NotificationType.ERROR,
            ).notify(project)
        }
    }

    companion object {
        const val GROUP_ID = "Provenance Recorder"
    }
}
