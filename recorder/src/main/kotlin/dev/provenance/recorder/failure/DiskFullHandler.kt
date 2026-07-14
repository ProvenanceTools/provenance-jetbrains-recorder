package dev.provenance.recorder.failure

import dev.provenance.core.HashedEnvelope

/** Event kinds retained in degraded mode; all others are dropped. PRD §4.8. */
val CRITICAL_KINDS: Set<String> = setOf(
    "session.start",
    "session.end",
    "fs.external_change",
    "chain.broken",
    "recorder.degraded",
    "recorder.recovered_from_corruption",
)

private const val DEFAULT_RING_CAPACITY = 256

/**
 * Handles write failures (disk-full and friends) on the live .slog write path.
 * Ported from provenance/packages/recorder/src/failure/disk-full-handler.ts.
 *
 * Design (from the TS header):
 * - Once degraded, only CRITICAL_KINDS entries are kept (fixed-capacity ring; FIFO eviction).
 * - handleWriteError is idempotent: the first error transitions to degraded; later calls are
 *   no-ops. Safe even if the recorder.degraded event itself fails to write — it is enqueued
 *   into the ring because its kind is critical.
 * - Disk-full is a one-way transition: no auto-recovery loop, no probe timer.
 *
 * synchronized() guards are a JVM-threading addition NOT present in the (single-threaded) TS
 * original: handleWriteError may fire on the writer's background flush thread while enqueue()
 * runs on the append thread. This guards the shared ArrayDeque + flag from a torn read; it is
 * not a behavior change.
 */
class DiskFullHandler(
    private val ringCapacity: Int = DEFAULT_RING_CAPACITY,
    private val onDegraded: (reason: String) -> Unit,
    private val notify: (message: String) -> Unit,
) {
    private var _degraded = false
    val degraded: Boolean
        get() = synchronized(this) { _degraded }

    private val ring = ArrayDeque<HashedEnvelope>()

    /** Idempotent: the first call transitions to degraded; later calls are no-ops. */
    fun handleWriteError(error: Throwable) {
        val shouldNotify = synchronized(this) {
            if (_degraded) {
                false
            } else {
                _degraded = true
                true
            }
        }
        if (!shouldNotify) return
        // Notify + signal outside the lock (notify may touch UI; onDegraded re-enters via
        // enqueue of the recorder.degraded event, which takes the lock itself).
        notify("Disk full — Provenance recording is degraded. Free space and restart your IDE.")
        onDegraded("disk_full")
    }

    /** True (accepted into the ring) iff degraded and entry.kind is critical. */
    fun enqueue(entry: HashedEnvelope): Boolean = synchronized(this) {
        if (!_degraded || entry.kind !in CRITICAL_KINDS) return@synchronized false
        if (ring.size >= ringCapacity) ring.removeFirst()
        ring.addLast(entry)
        true
    }

    /** Shallow copy of the ring — mutating it does not affect internal state. */
    fun snapshot(): List<HashedEnvelope> = synchronized(this) { ring.toList() }
}
