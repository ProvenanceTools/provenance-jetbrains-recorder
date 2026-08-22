package dev.provenance.recorder.identity

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.provenance.recorder.activation.RecorderState

/**
 * Shows the un-enrolled student the enrollment page, at most twice in their life.
 *
 * The decision — whether to show, and what to persist afterwards — lives in [EnrollNudge] as
 * pure functions. This file is the IDE glue: it reads the persisted state, renders the balloon,
 * and turns the student's click into the next state.
 *
 * ## Where the state lives, and why it is not the SecretStore
 *
 * [PropertiesComponent.getInstance] with no project is APPLICATION-level, so the count is
 * per-machine and shared by every project — which is what a global 2.1 credential deserves.
 * `SecretStore`'s docstring bans `PropertiesComponent` as "readable plaintext IDE state"; that
 * ban is about the master secret and it still stands. This is a boolean about whether a popup
 * has been shown, and plaintext is exactly the right place for it.
 *
 * Reuses the existing `Provenance Recorder` balloon group registered in plugin.xml.
 */
object EnrollNudgeNotifier {
    private val LOG = Logger.getInstance(EnrollNudgeNotifier::class.java)

    /** Application-level key holding a [NudgeState] name. */
    const val STATE_KEY: String = "provenance.enrollNudge"

    private fun read(): NudgeState =
        NudgeState.parse(PropertiesComponent.getInstance().getValue(STATE_KEY))

    private fun write(state: NudgeState) {
        PropertiesComponent.getInstance().setValue(STATE_KEY, state.name)
    }

    /**
     * Render the enrollment state and, if it is time, nudge.
     *
     * Called once per activation, after every root's session has started — only then does
     * [RecorderState] hold the outcomes this reads. Never throws into activation: a failed
     * nudge costs a notification, and a throw here would cost the recording.
     */
    fun maybeNudge(project: Project) {
        try {
            if (project.isDisposed) return
            val outcomes = project.service<RecorderState>().identityOutcomes
            val state = read()
            if (!shouldShowNudge(outcomes, state)) return
            show(project, state)
        } catch (t: Throwable) {
            LOG.warn("could not surface the enrollment nudge", t)
        }
    }

    private fun show(project: Project, state: NudgeState) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val notification = Notification(GROUP_ID, NUDGE_MESSAGE, NotificationType.WARNING)

            // Every exit advances the state, including the balloon simply expiring: a student
            // who ignores it has answered as clearly as one who clicks "Later".
            fun resolve(action: NudgeAction, n: Notification) {
                write(nextNudgeState(state, action))
                n.expire()
            }

            notification.addAction(
                NotificationAction.create(NUDGE_ENROLL_LABEL) { _: AnActionEvent, n: Notification ->
                    resolve(NudgeAction.ENROLL, n)
                    // The student's click, in the student's browser. The recorder itself opens
                    // no socket — recorder PRD NG2 holds.
                    BrowserUtil.browse(ENROLL_URL)
                },
            )
            notification.addAction(
                NotificationAction.create(NUDGE_SHOW_KEY_LABEL) { e: AnActionEvent, n: Notification ->
                    resolve(NudgeAction.SHOW_KEY, n)
                    showEnrollmentKey(e)
                },
            )
            notification.addAction(
                NotificationAction.create(NUDGE_LATER_LABEL) { _: AnActionEvent, n: Notification ->
                    resolve(NudgeAction.DISMISS, n)
                },
            )

            // Dismissing the balloon by any other route — the × , or the IDE expiring it — is
            // the same answer as "Later".
            notification.whenExpired { if (read() == state) write(nextNudgeState(state, NudgeAction.DISMISS)) }
            notification.notify(project)
        }
    }

    /** Run the existing palette action rather than duplicating its key-derivation logic. */
    private fun showEnrollmentKey(e: AnActionEvent) {
        val action = ActionManager.getInstance().getAction(SHOW_ENROLLMENT_KEY_ACTION_ID) ?: return
        action.actionPerformed(e)
    }

    private const val GROUP_ID = "Provenance Recorder"
    private const val SHOW_ENROLLMENT_KEY_ACTION_ID = "dev.provenance.recorder.ShowEnrollmentKey"
}
