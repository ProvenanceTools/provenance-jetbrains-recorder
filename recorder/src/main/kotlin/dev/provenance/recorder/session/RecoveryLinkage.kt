package dev.provenance.recorder.session

import dev.provenance.core.RecorderRecoveredFromCorruptionPayload
import dev.provenance.recorder.startup.RecoveryDecision

/**
 * Pure derivation of what a [RecoveryDecision] means for the *new* session's log, shared by
 * RecordingSessionController's real wiring and SessionLifecycleIntegrationTest. Mirrors the
 * documented rule in chain-recovery.ts / ChainRecovery.kt's header comment: prev_session_id
 * links ONLY a dangling prior session; a corrupt one is surfaced via
 * recorder.recovered_from_corruption instead, never chain linkage.
 */

/** session.start.prev_session_id for the new session — null unless the prior one was dangling. */
fun prevSessionIdFor(recovery: RecoveryDecision): String? =
    (recovery as? RecoveryDecision.PreviousSessionDangling)?.prevSessionId

/**
 * The recorder.recovered_from_corruption entry to emit immediately after session.start, if
 * recovery quarantined a corrupt prior session — null otherwise.
 */
fun recoveryFollowupPayload(recovery: RecoveryDecision): RecorderRecoveredFromCorruptionPayload? =
    (recovery as? RecoveryDecision.PreviousSessionCorrupt)?.let { RecorderRecoveredFromCorruptionPayload(it.quarantinedPath) }
