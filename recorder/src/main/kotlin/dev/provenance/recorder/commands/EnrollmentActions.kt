package dev.provenance.recorder.commands

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.provenance.core.deriveCourseKeypair
import dev.provenance.recorder.identity.CourseKeyCache
import dev.provenance.recorder.identity.IdentityStoreError
import dev.provenance.recorder.identity.PasswordSafeSecretStore
import dev.provenance.recorder.identity.SecretStore
import dev.provenance.recorder.identity.StoreResult
import dev.provenance.recorder.identity.exportMasterSecret
import dev.provenance.recorder.identity.loadOrCreateMasterSecret
import dev.provenance.recorder.identity.saveEnrollment
import dev.provenance.recorder.session.RecorderSessionManager

/**
 * The four student-facing identity commands (program spec §S2, §5a).
 *
 * ## Enrollment is a PASTE, not a fetch
 *
 * Recorder PRD NG2 forbids network calls from the recorder, and this path honours it
 * completely. The flow is entirely out of band:
 *
 *  1. **Show My Enrollment Key** prints the student's per-course public key.
 *  2. The student sends it to course staff however the course prefers, and receives an
 *     `{ enrollment, enrollment_cert }` JSON blob back.
 *  3. **Import Enrollment Token** pastes that blob in.
 *
 * Nothing here opens a socket, so the whole identity path works offline.
 *
 * ## Export / Import Student Identity Secret
 *
 * These exist because the credential vault is not readable by hand and there is no escrow
 * to recover from. They are the ONLY way to carry an identity to a new machine — per-course
 * keys re-derive byte-identically from the same master secret, so every token the student
 * already holds keeps working and nothing has to be re-minted.
 */
private fun storeOf(): SecretStore = PasswordSafeSecretStore()

/**
 * The application-scoped derived-key cache, or null when the service container cannot supply
 * it. Shared with the session-start path so the key shown here and the key that countersigns
 * `session_pubkey` are the same derivation — resolved defensively because a missing cache
 * must degrade to direct derivation, never fail the command.
 */
private fun keyCacheOf(): CourseKeyCache? = runCatching {
    com.intellij.openapi.application.ApplicationManager.getApplication()
        ?.getService(CourseKeyCache::class.java)
}.getOrNull()

private fun notify(project: Project, type: NotificationType, title: String, body: String) {
    com.intellij.notification.NotificationGroupManager.getInstance()
        .getNotificationGroup("Provenance Recorder")
        .createNotification(title, body, type)
        .notify(project)
}

/**
 * The course ids currently being recorded, so the student never has to type one. Only 2.0
 * manifests carry a `course_id`; a 1.x assignment has no identity layer at all.
 */
private fun activeCourseIds(project: Project): List<String> =
    project.service<RecorderSessionManager>().activeSessions.values
        .mapNotNull { it.activated.manifest.courseId }
        .distinct()
        .sorted()

/**
 * "Provenance: Show My Enrollment Key" — step 1 of enrolling.
 *
 * Displays the per-course PUBLIC key. Safe to show and to send: it is the value a course
 * binds to a roster entry, and it is already written into every bundle the student submits.
 * The master secret it derives from is never displayed here.
 *
 * This is also the command that creates a master secret on first use, which is deliberate:
 * enrolling is the first moment an identity is actually needed.
 */
class ShowEnrollmentKeyAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val courses = activeCourseIds(project)
        val courseId = when {
            courses.isEmpty() -> Messages.showInputDialog(
                project,
                "Course id (from your assignment's manifest):",
                "Provenance: Show My Enrollment Key",
                null,
            )?.trim()

            courses.size == 1 -> courses.first()
            else -> Messages.showEditableChooseDialog(
                "Which course?",
                "Provenance: Show My Enrollment Key",
                null,
                courses.toTypedArray(),
                courses.first(),
                null,
            )?.trim()
        }
        if (courseId.isNullOrEmpty()) return

        val secrets = storeOf()
        when (val master = loadOrCreateMasterSecret(secrets)) {
            is StoreResult.Err -> notify(
                project,
                NotificationType.ERROR,
                "Provenance: could not read your identity secret",
                describeMasterSecretError(master.error),
            )

            is StoreResult.Ok -> {
                val keypair = keyCacheOf()?.get(master.value, courseId)
                    ?: deriveCourseKeypair(master.value, courseId)
                // showCopyableInfoMessage, not a plain info dialog: the student must be able
                // to select and copy this to send to course staff.
                Messages.showInfoMessage(
                    project,
                    "Your enrollment key for $courseId:\n\n${keypair.publicKeyHex}\n\n" +
                        "Send this to your course staff. They will send back an enrollment " +
                        "token, which you import with \"Provenance: Import Enrollment Token\".",
                    "Provenance: Enrollment Key",
                )
            }
        }
    }
}

/** "Provenance: Import Enrollment Token" — step 3 of enrolling. */
class ImportEnrollmentTokenAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val pasted = Messages.showMultilineInputDialog(
            project,
            "Paste the enrollment token your course staff sent you:",
            "Provenance: Import Enrollment Token",
            "",
            null,
            null,
        ) ?: return

        when (val result = saveEnrollment(storeOf(), pasted)) {
            is StoreResult.Ok -> notify(
                project,
                NotificationType.INFORMATION,
                "Provenance: enrolled",
                "You are now enrolled in ${result.value}. New recording sessions will " +
                    "include your identity.",
            )

            is StoreResult.Err -> notify(
                project,
                NotificationType.ERROR,
                "Provenance: could not import that token",
                describeImportError(result.error),
            )
        }
    }
}

/**
 * "Provenance: Export Student Identity Secret" — the new-machine path, old machine.
 *
 * Shows the 64-hex master secret so the student can copy it to a new machine. This is the
 * one command that displays the secret itself; there is no escrow, so this is the only way
 * an identity survives a machine change.
 */
class ExportStudentSecretAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        when (val exported = exportMasterSecret(storeOf())) {
            is StoreResult.Ok -> Messages.showInfoMessage(
                project,
                "Your Provenance identity secret:\n\n${exported.value}\n\n" +
                    "Copy this somewhere safe and import it on your other machine with " +
                    "\"Provenance: Import Student Identity Secret\". Anyone who has it can " +
                    "sign as you in every course — do not share it.",
                "Provenance: Student Identity Secret",
            )

            is StoreResult.Err -> notify(
                project,
                NotificationType.ERROR,
                "Provenance: no identity secret to export",
                describeMasterSecretError(exported.error),
            )
        }
    }
}

/**
 * "Provenance: Import Student Identity Secret" — the new-machine path, new machine.
 *
 * After this, per-course keys re-derive byte-identically, so every enrollment token the
 * student already holds keeps working. A malformed paste is rejected without touching any
 * existing secret: overwriting on a typo would be unrecoverable.
 */
class ImportStudentSecretAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val pasted = Messages.showInputDialog(
            project,
            "Paste the identity secret exported from your other machine:",
            "Provenance: Import Student Identity Secret",
            null,
        ) ?: return

        when (
            val result = dev.provenance.recorder.identity.importMasterSecret(storeOf(), pasted)
        ) {
            is StoreResult.Ok -> notify(
                project,
                NotificationType.INFORMATION,
                "Provenance: identity secret imported",
                "Your per-course keys will re-derive from it, so any enrollment tokens you " +
                    "already have keep working.",
            )

            is StoreResult.Err -> notify(
                project,
                NotificationType.ERROR,
                "Provenance: could not import that secret",
                describeMasterSecretError(result.error),
            )
        }
    }
}

private fun describeMasterSecretError(e: IdentityStoreError): String = when (e) {
    is IdentityStoreError.NoMasterSecret ->
        "No identity secret is stored on this machine yet. Run \"Provenance: Show My " +
            "Enrollment Key\" to create one."

    is IdentityStoreError.CorruptMasterSecret ->
        "The stored identity secret is unreadable (${e.reason}). It was NOT replaced — " +
            "replacing it would invalidate every enrollment token you hold. Import your " +
            "secret from another machine if you have it."

    is IdentityStoreError.SecretStoreUnavailable ->
        "The system credential store is unavailable (${e.reason}). Recording continues " +
            "normally; only your identity is affected."

    else -> e.toString()
}

private fun describeImportError(e: IdentityStoreError): String = when (e) {
    is IdentityStoreError.InvalidJson -> "That is not valid JSON (${e.message})."
    is IdentityStoreError.UnsupportedFormatVersion ->
        "That ${e.artifact} declares format_version \"${e.formatVersion}\", which this " +
            "version of the recorder does not understand. Update the plugin."

    is IdentityStoreError.InvalidCertShape -> "The enrollment certificate is malformed (${e.reason})."
    is IdentityStoreError.InvalidTokenShape -> "The enrollment token is malformed (${e.reason})."
    is IdentityStoreError.CourseIdMismatch ->
        "The token is for ${e.tokenCourseId} but its certificate is for ${e.certCourseId}. " +
            "Ask your course staff to re-issue it."

    is IdentityStoreError.SecretStoreUnavailable ->
        "The system credential store is unavailable (${e.reason})."

    else -> e.toString()
}
