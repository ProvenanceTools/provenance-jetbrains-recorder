package dev.provenance.recorder.commands

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.provenance.core.deriveCourseKeypair
import dev.provenance.core.deriveStudentKeypair
import dev.provenance.recorder.activation.refreshStatusBarWidget
import dev.provenance.recorder.identity.CourseKeyCache
import dev.provenance.recorder.identity.ENROLL_URL
import dev.provenance.recorder.identity.IdentityImportOk
import dev.provenance.recorder.identity.IdentityStoreError
import dev.provenance.recorder.identity.PasswordSafeSecretStore
import dev.provenance.recorder.identity.SecretStore
import dev.provenance.recorder.identity.StoreResult
import dev.provenance.recorder.identity.exportMasterSecret
import dev.provenance.recorder.identity.loadOrCreateMasterSecret
import dev.provenance.recorder.identity.saveIdentityArtifact
import dev.provenance.recorder.session.RecorderSessionManager
import kotlinx.coroutines.runBlocking

/**
 * The four student-facing identity commands (program spec §S2, §5a).
 *
 * ## Enrollment is a PASTE, not a fetch
 *
 * Recorder PRD NG2 forbids network calls from the recorder, and this path honours it
 * completely. The flow is entirely out of band:
 *
 *  1. **Show My Enrollment Key** prints the student's public key.
 *  2. The student sends it to their institution's enrolment page (identity 2.1) or to
 *     course staff (legacy 2.0), and receives an `{ enrollment, enrollment_cert }` JSON
 *     blob back.
 *  3. **Import Enrollment Token** pastes that blob in — either version; the importer
 *     routes on the SIGNED `format_version` in the cert slot.
 *
 * Nothing here opens a socket, so the whole identity path works offline.
 *
 * ## Identity 2.1 needs no course
 *
 * Under 2.0 this command had to ask which course the key was for, because the key itself
 * was per-course. Under 2.1 a student has ONE key across every course, so the global key
 * is shown unconditionally and with no prompt — the friction that removal eliminates is
 * exactly what made the 2.0 design deadlock (a student could not enrol before their first
 * submission). Any active 2.0 course still gets its legacy per-course key listed
 * underneath, so a student mid-migration can still be issued a 2.0 token.
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
 * What the student is told after a credential lands while sessions are already running.
 *
 * Pure, and pinned by `EnrollmentRestartNoticeTest`, because the sentence that matters most is
 * the one it would be most convenient to leave out: **work recorded before the import stays
 * unattributed.** `session.start.identity` is written once, at the top of a signed, hash-chained
 * log; there is no amending it afterwards and no honest way to imply otherwise.
 */
internal fun identityStoredNotice(stored: String, activeRoots: Int): String = when {
    activeRoots <= 0 -> "$stored New recording sessions will include it."
    else -> "$stored Recording is restarting for ${assignments(activeRoots)} so that work from " +
        "this point on is attributed to you. Work recorded before now stays unattributed and " +
        "cannot be changed after the fact."
}

/** The degraded path: the credential IS stored, but a root did not come back up. */
internal fun restartFailedNotice(failedRoots: Int): String =
    "Your identity is stored, but recording could not be restarted for " +
        "${assignments(failedRoots)}. Close and reopen the project to start an attributed " +
        "session. Work recorded before now stays unattributed."

private fun assignments(n: Int): String = if (n == 1) "1 assignment" else "$n assignments"

/**
 * Apply a freshly-stored credential to the sessions already running, and say so.
 *
 * Never throws into the action: a failure here costs the restart, and the student is told to
 * reopen the project instead. Runs off the EDT because the restart writes `session.end`, closes
 * the writers, and re-runs chain recovery for each root.
 *
 * Scoped to the project the command ran in. A 2.1 credential is machine-global, so sessions in
 * ANOTHER open project window still carry the old (or no) identity until that window is
 * reopened — which is why the counted wording below says how many assignments are restarting
 * rather than claiming everything is now covered.
 */
private fun applyIdentityToOpenSessions(project: Project, title: String, stored: String) {
    val manager = project.service<RecorderSessionManager>()
    val open = manager.activeSessions.size
    notify(project, NotificationType.INFORMATION, title, identityStoredNotice(stored, open))
    if (open == 0) return

    object : Task.Backgroundable(project, "Applying your Provenance identity", false) {
        override fun run(indicator: ProgressIndicator) {
            // restartSessions isolates each root itself, so a throw escaping it means the whole
            // restart died; every root that was open is then a root that did not come back.
            val failedCount = runCatching { runBlocking { manager.restartSessions() }.failed.size }
                .getOrElse {
                    LOG.warn("restarting sessions after an identity import failed outright", it)
                    open
                }
            if (failedCount > 0) {
                notify(
                    project,
                    NotificationType.ERROR,
                    "Provenance: recording did not restart",
                    restartFailedNotice(failedCount),
                )
            }
            // Whatever happened, the widget must now show it: a restarted root has a fresh
            // identity outcome, and a failed one is marked degraded.
            refreshStatusBarWidget(project)
        }
    }.queue()
}

private val LOG = Logger.getInstance("dev.provenance.recorder.commands.EnrollmentActions")

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
        val secrets = storeOf()

        when (val master = loadOrCreateMasterSecret(secrets)) {
            is StoreResult.Err -> notify(
                project,
                NotificationType.ERROR,
                "Provenance: could not read your identity secret",
                describeMasterSecretError(master.error),
            )

            is StoreResult.Ok -> {
                val cache = keyCacheOf()
                val global = cache?.getGlobal(master.value) ?: deriveStudentKeypair(master.value)

                // Legacy per-course keys, listed only for courses actually being recorded.
                // Never prompted for: a student who has no 2.0 token to obtain must not be
                // asked to name a course they do not need.
                val legacy = activeCourseIds(project).joinToString("") { courseId ->
                    val kp = cache?.get(master.value, courseId)
                        ?: deriveCourseKeypair(master.value, courseId)
                    "\n\nLegacy (identity 2.0) key for $courseId:\n${kp.publicKeyHex}"
                }

                Messages.showInfoMessage(
                    project,
                    "Your Provenance enrollment key:\n\n${global.publicKeyHex}\n\n" +
                        "Paste it into your institution's Provenance enrolment page:\n\n" +
                        "    $ENROLL_URL\n\n" +
                        "You will get back a credential, which you import with \"Provenance: " +
                        "Import Enrollment Token\"." + legacy,
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

        when (val result = saveIdentityArtifact(storeOf(), pasted)) {
            // Stored AND applied: the identity block is built once, at session start, so a
            // credential imported into a live session does nothing until that session restarts.
            is StoreResult.Ok -> applyIdentityToOpenSessions(
                project,
                "Provenance: enrolled",
                when (val ok = result.value) {
                    is IdentityImportOk.Current21 ->
                        "Your identity for ${ok.institutionId} is stored, for every course."

                    is IdentityImportOk.Legacy20 ->
                        "You are now enrolled in ${ok.courseId}."
                },
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
            // Restarted for the same reason an enrollment import is: importing the SECRET is
            // what makes tokens the student already holds derive correctly, so this can turn a
            // live session's StudentKeyMismatch into a working identity — but only at the next
            // session.start, which is the one this restart produces.
            is StoreResult.Ok -> applyIdentityToOpenSessions(
                project,
                "Provenance: identity secret imported",
                "Your identity secret is stored. Your per-course keys re-derive from it, so any " +
                    "enrollment tokens you already have keep working.",
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

    is IdentityStoreError.InvalidCredentialShape ->
        "The credential is malformed (${e.reason})."

    is IdentityStoreError.InstitutionIdMismatch ->
        "The credential is for ${e.credentialInstitutionId} but its certificate is for " +
            "${e.certInstitutionId}. That looks like two separate pastes mixed together — " +
            "copy the whole blob again."

    is IdentityStoreError.UnsupportedIdentityVersion ->
        "That blob declares identity version \"${e.formatVersion}\", which this version of " +
            "the recorder does not understand. Update the plugin."

    is IdentityStoreError.SecretStoreUnavailable ->
        "The system credential store is unavailable (${e.reason})."

    else -> e.toString()
}
