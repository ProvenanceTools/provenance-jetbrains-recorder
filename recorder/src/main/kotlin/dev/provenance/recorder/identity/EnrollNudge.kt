package dev.provenance.recorder.identity

/**
 * Pointing an un-enrolled student at the enrollment page.
 *
 * Mirrors `packages/recorder/src/activation/enroll-nudge.ts` in the VS Code recorder; keep the
 * two in step.
 *
 * A student who never enrols still records perfectly good bundles — the event stream, the hash
 * chain and the seal are all unaffected (rule 1 in [buildSessionIdentity]). What they lose is
 * ATTRIBUTION: nothing in the bundle says who produced it. That failure is silent, it is the
 * default state of every fresh install, and until this file existed its only trace was a
 * `LOG.debug` line — a channel that is off by default and that no student will ever read.
 *
 * ## Why this reads the identity outcome instead of looking up a credential
 *
 * "Is this student enrolled?" looks like a one-line [SecretStore] lookup, and a lookup gets it
 * wrong. A student holding a LEGACY 2.0 course token has no 2.1 credential stored, so the lookup
 * calls them un-enrolled — but their sessions DO emit an identity and their work IS attributed.
 * Telling them otherwise would be false and would push them to re-enrol for nothing.
 *
 * [buildSessionIdentity] has already answered the real question, both families handled. This
 * file consumes that answer.
 *
 * ## Not every skip is the student's problem
 *
 * Of the ten skip reasons, only two mean "you have no credential and enrolling would fix it" —
 * see [isUnenrolledSkip]. Attaching an enrollment URL to a packaging bug would send the student
 * somewhere that cannot help them and bury the real fault.
 *
 * ## No network, still
 *
 * Recorder PRD NG2 forbids the recorder making network calls during a session. Nothing here
 * fetches. The URL is a string the student is shown; opening it is their own click in their own
 * browser, and enrollment stays a paste in both directions ([EnrollmentActions]).
 */

/**
 * The enrollment page.
 *
 * Hardcoded, and deliberately not a setting. Every institution running this recorder today is
 * Berkeley, and the value cannot be derived from anything an un-enrolled student holds:
 * `institutionId` lives inside the credential and the institution cert, which is exactly what a
 * student who has not enrolled does not have. The manifest carries no institution field at all.
 *
 * When a second institution appears this becomes a signed manifest field or a setting — and that
 * is when it deserves a design, with the `institutionId` already in the credential to key off.
 */
const val ENROLL_URL: String = "https://provenance.eecs.berkeley.edu/enroll"

/**
 * How far the student has got with the nudge. Persisted per MACHINE, globally — never per
 * course. A 2.1 credential is institution-scoped and covers every course at once, so a student
 * taking 61B and 61C is one person with one enrollment and must be nudged once, not once per
 * assignment root.
 */
enum class NudgeState {
    UNSEEN,
    INTENT,
    DONE,
    ;

    companion object {
        /** Anything unrecognised (absent, older build, hand-edited state) reads as fresh. */
        fun parse(raw: String?): NudgeState =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: UNSEEN
    }
}

/** What the student did with the notification. */
enum class NudgeAction { ENROLL, SHOW_KEY, DISMISS }

const val NUDGE_MESSAGE: String =
    "Provenance is recording, but you have not enrolled — this work will not be attributed to you."

const val NUDGE_ENROLL_LABEL: String = "Enroll"
const val NUDGE_SHOW_KEY_LABEL: String = "Show My Key"
const val NUDGE_LATER_LABEL: String = "Later"

/**
 * Does this skip reason mean the student has no credential and enrolling fixes it?
 *
 * Only two of the ten do:
 *
 *  - [IdentitySkipReason.NotEnrolled] — the 2.0 path found no token for this course.
 *  - [IdentitySkipReason.ManifestNot20] — reached ONLY after the 2.1 lookup found no credential
 *    (see the precedence block in [buildSessionIdentity]), so it means "no 2.1 credential, and
 *    the manifest is too old to carry a 2.0 anchor". Enrolling yields a 2.1 credential, the 2.1
 *    path then runs first, and the manifest version stops mattering. So: actionable.
 *
 * Everything else is withheld deliberately:
 *
 *  - [IdentitySkipReason.NoRootPublicKey], [IdentitySkipReason.InstitutionCertNotRootSigned] —
 *    the build shipped without a usable trust anchor. Not fixable by enrolling, and a web page
 *    would hide a packaging fault that needs staff.
 *  - [IdentitySkipReason.MasterSecretUnavailable] — the keyring is unavailable. Enrolling needs
 *    that same keyring, so the advice would fail on arrival.
 *  - [IdentitySkipReason.CredentialKeyMismatch], [IdentitySkipReason.StudentKeyMismatch] — they
 *    HAVE a credential; it belongs to another machine. Already messaged, in more detail, at the
 *    moment of import.
 *  - the rest — something is wrong that a student cannot act on.
 */
fun isUnenrolledSkip(reason: IdentitySkipReason): Boolean =
    reason is IdentitySkipReason.NotEnrolled || reason is IdentitySkipReason.ManifestNot20

/**
 * Did any session claim an identity?
 *
 * All-or-nothing on purpose. With several assignment roots open a 2.1 credential covers all of
 * them, so mixed outcomes are only reachable by a legacy 2.0 holder enrolled in some courses and
 * not others. For that student plain "recording" is the honest status line: at least one session
 * IS attributed. The per-course gap surfaces where it belongs — on the analyzer, against the
 * submission that lacks a contributor.
 */
fun anyIdentityEmitted(outcomes: Collection<IdentityOutcome>): Boolean =
    outcomes.any { it is IdentityOutcome.Emitted }

/**
 * Should the student see "(not enrolled)"?
 *
 * True only when no session emitted an identity AND at least one skipped for a reason enrolling
 * would fix. A machine whose keyring is broken reads as plain "recording": the identity is
 * missing, but "not enrolled" would be the wrong diagnosis and the wrong instruction.
 */
fun isUnenrolled(outcomes: Collection<IdentityOutcome>): Boolean {
    if (anyIdentityEmitted(outcomes)) return false
    return outcomes.any { it is IdentityOutcome.Skipped && isUnenrolledSkip(it.reason) }
}

/**
 * Show the nudge this session?
 *
 * [NudgeState.DONE] is terminal. UNSEEN and INTENT both show, capping the student's lifetime
 * exposure at two notifications: one on the first un-enrolled session, and one more only if they
 * showed intent and did not finish.
 */
fun shouldShowNudge(outcomes: Collection<IdentityOutcome>, state: NudgeState): Boolean {
    if (state == NudgeState.DONE) return false
    return isUnenrolled(outcomes)
}

/**
 * The state to persist after the student acts.
 *
 * Dismissing means no, and no is permanent — a student who has decided must not be asked again.
 * Clicking through to enrol or to see their key is intent, which buys exactly one follow-up: the
 * browser opens, real life intervenes, and the token never gets pasted. The second nudge catches
 * that. There is no third, whatever they click, because by then the persistent "(not enrolled)"
 * status bar has said it every session and a popup is nagging.
 */
fun nextNudgeState(current: NudgeState, action: NudgeAction): NudgeState = when {
    current == NudgeState.DONE -> NudgeState.DONE
    action == NudgeAction.DISMISS -> NudgeState.DONE
    current == NudgeState.UNSEEN -> NudgeState.INTENT
    else -> NudgeState.DONE
}

/** The status-bar suffix for the enrollment state, appended to whatever the widget already says. */
fun enrollmentSuffix(unenrolled: Boolean): String = if (unenrolled) " (not enrolled)" else ""

/** The extra tooltip line for an un-enrolled student, or null when there is nothing to add. */
fun enrollmentTooltipLine(unenrolled: Boolean): String? =
    if (!unenrolled) {
        null
    } else {
        "You have not enrolled, so this work is not attributed to you. " +
            "Enrol at $ENROLL_URL, then run \"Provenance: Import Enrollment Token\"."
    }
