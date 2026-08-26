package dev.provenance.recorder.activation

import com.intellij.openapi.components.Service
import dev.provenance.core.Manifest
import dev.provenance.recorder.identity.EnrollmentTrackedSession
import dev.provenance.recorder.identity.IdentityOutcome
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-scoped activation state: every currently-verified assignment root and its manifest.
 * Consulted by RecordingStatusBarWidgetFactory to decide whether to show the "Provenance:
 * recording" widget, and by RecordingStatusBarWidget to render the assignment count.
 * PRD §4.1 / CLAUDE.md: activation is the privacy gate.
 */
@Service(Service.Level.PROJECT)
class RecorderState {
    private val active = ConcurrentHashMap<Path, Manifest>()

    /** Activated roots whose recording session FAILED to start, with the failure reason.
     * Always a subset of [active]: a degraded root stays activated (so the student still sees
     * an indicator) but is knowingly not recording. */
    private val degraded = ConcurrentHashMap<Path, String>()

    /** Whether each started root could claim a student identity, and if not, why. Absent for a
     * root whose session never started — "we never asked" is not "they are not enrolled", and
     * the widget must not turn the first into the second. Read via [identitySessions] by
     * `EnrollNudge`, which decides the "(not enrolled)" suffix and the one-time nudge. */
    private val identities = ConcurrentHashMap<Path, IdentityOutcome>()

    val isActive: Boolean get() = active.isNotEmpty()

    /** Single-assignment convenience: the active manifest when exactly one assignment is
     * recording, else null (including when more than one is active — ambiguous). Multi-root
     * consumers should read [activeManifests] instead. */
    val manifest: Manifest? get() = active.values.singleOrNull()

    val activeManifests: Map<Path, Manifest> get() = active.toMap()

    /** Roots that are activated but not recording, keyed to the reason their session failed
     * to start. Sorted by path so the status bar renders deterministically. */
    val degradedRoots: Map<Path, String> get() = degraded.toSortedMap()

    fun isDegraded(root: Path): Boolean = degraded.containsKey(root.normalize())

    /**
     * Every started root's identity outcome, paired with whether its course requires
     * enrollment at all (`policy.enrollment.required`, Manifest 2.0 only — see
     * [resolveVerifiedEnrollmentPolicy]). Empty until the first session reports.
     *
     * This is the join point [dev.provenance.recorder.identity.EnrollmentTrackedSession]'s
     * KDoc refers to: it is the one place that knows both a root's identity outcome
     * (`identities`) and its manifest (`active`), so it is the one place that can pair
     * them.
     *
     * Deliberately NOT pre-filtered to enrollment-requiring roots — an earlier version
     * of this getter did exactly that, and it was wrong: it dropped a waived root's
     * EMITTED identity before [dev.provenance.recorder.identity.isUnenrolled] ever saw
     * it, so a legacy 2.0 holder attributed through a waived course but skipped
     * (`not_enrolled`) on a requiring one would read as "not enrolled" — false, and
     * exactly the misdiagnosis this whole feature exists to prevent. [isUnenrolled] and
     * friends do their own, asymmetric filtering internally (see [isUnenrolled]'s KDoc):
     * "was anyone attributed" reads every session here; only "does anyone still need to
     * enrol" is scoped to `enrollmentRequired` roots. Do not reintroduce a wholesale
     * filter at this call site.
     *
     * A root with no manifest on record (should not happen: [activate] and
     * [recordIdentity] are always called for the same root) defaults to requiring
     * enrollment, the same fail-safe direction [resolveVerifiedEnrollmentPolicy] itself
     * takes on malformed input.
     */
    val identitySessions: Collection<EnrollmentTrackedSession> get() =
        identities.entries.map { (root, outcome) ->
            EnrollmentTrackedSession(
                outcome = outcome,
                enrollmentRequired = active[root]?.let { resolveVerifiedEnrollmentPolicy(it).required } ?: true,
            )
        }

    /** Record what [buildSessionIdentity] decided for [root]. Idempotent; last write wins. */
    fun recordIdentity(root: Path, outcome: IdentityOutcome) {
        identities[root.normalize()] = outcome
    }

    fun activate(root: Path, m: Manifest) {
        val key = root.normalize()
        active[key] = m
        // A fresh activation supersedes any earlier failure for this root; the caller marks it
        // degraded again if starting its session fails this time round.
        degraded.remove(key)
    }

    /**
     * Record that [root] is activated but NOT recording, because starting its session failed.
     * Deliberately not a rollback: the root stays in [active] so the disclosure indicator keeps
     * rendering — a student must be able to tell recording is broken, not merely absent.
     */
    fun markDegraded(root: Path, reason: String) {
        degraded[root.normalize()] = reason
    }

    fun deactivate(root: Path) {
        val key = root.normalize()
        active.remove(key)
        degraded.remove(key)
        identities.remove(key)
    }

    fun deactivateAll() {
        active.clear()
        degraded.clear()
        identities.clear()
    }

    /** Back-compat alias used by existing tearDowns; clears every assignment. */
    fun deactivate() = deactivateAll()
}
