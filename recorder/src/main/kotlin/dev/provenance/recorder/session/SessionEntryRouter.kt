package dev.provenance.recorder.session

import dev.provenance.core.HashedEnvelope
import dev.provenance.recorder.failure.DiskFullHandler

/**
 * Routes one chained entry to the live .slog (and, on cadence, schedules a checkpoint) or,
 * once degraded, to the disk-full ring buffer. This is the pure composition of Plan 8's three
 * pieces (checkpoint cadence, checkpoint scheduler, disk-full handler) into the exact control
 * flow SessionHost.onEntry uses in production — direct port of the ordering in
 * provenance/packages/recorder/src/extension.ts:260-282:
 *
 * ```
 * if (diskFullHandler.degraded) { diskFullHandler.enqueue(entry); return }
 * writer.append(entry)
 * if (++count >= CHECKPOINT_INTERVAL) { count = 0; scheduleCheckpoint(entry.seq, entry.hash) }
 * ```
 *
 * [append] is the real SessionWriter.append() in production, which never throws for a write
 * failure (it reports failures via the writer's own onError callback, already wired to
 * [DiskFullHandler.handleWriteError] at construction) — but a test double may throw
 * synchronously to model the same failure deterministically, so this function also catches and
 * forwards. Either path is idempotent through [DiskFullHandler.handleWriteError]. Cadence is
 * only advanced (and a checkpoint only scheduled) for entries that actually reached [append]
 * successfully and while not degraded — never for the failing entry itself, never for entries
 * routed to the ring.
 *
 * Shared verbatim by RecordingSessionController's real wiring and
 * SessionLifecycleIntegrationTest's pure-JUnit exercise of the same control flow, so the two
 * can never drift.
 */
fun routeSessionEntry(
    entry: HashedEnvelope,
    append: (HashedEnvelope) -> Unit,
    diskFullHandler: DiskFullHandler,
    cadence: CheckpointCadence,
    scheduleCheckpoint: (seq: Long, entryHash: String) -> Unit,
) {
    if (diskFullHandler.degraded) {
        diskFullHandler.enqueue(entry)
        return
    }
    try {
        append(entry)
    } catch (e: Exception) {
        diskFullHandler.handleWriteError(e)
        return
    }
    if (cadence.onEntryAppended()) {
        scheduleCheckpoint(entry.seq, entry.hash)
    }
}
