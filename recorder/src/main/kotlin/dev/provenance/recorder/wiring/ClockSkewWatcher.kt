package dev.provenance.recorder.wiring

import com.intellij.openapi.Disposable
import dev.provenance.core.ClockSkewPayload
import dev.provenance.recorder.io.FlushScheduler
import java.util.concurrent.ScheduledFuture
import kotlin.math.abs

/**
 * Clock-skew watcher (recorder PRD §4.2: clock.skew on non-monotonic wall-clock jumps).
 * Ported from the VS Code recorder's clock-watcher.ts.
 *
 * On each [tick] it compares how much *monotonic* time elapsed against how much *wall* time
 * elapsed since the last reference point; when they diverge by at least [driftThresholdMs] it
 * emits clock.skew with delta_ms = (wall elapsed − monotonic elapsed) and resets the reference
 * points so a single jump is reported once, not on every subsequent tick.
 *
 * Pure + injectable exactly like Heartbeat/PluginSnapshotWiring: the two clock readers and the
 * [FlushScheduler] are injected, so drift detection is a plain unit test with no live IDE. Unlike
 * PluginSnapshotWiring there is no immediate emit at construction — only the reference points are
 * captured (matching clock-watcher.ts). No background task without a dispose path (CLAUDE.md):
 * [dispose] cancels the schedule and is idempotent.
 */
class ClockSkewWatcher(
    private val emit: (ClockSkewPayload) -> Unit,
    private val getMonotonicMs: () -> Long,
    private val getWallMs: () -> Long,
    private val driftThresholdMs: Long = DEFAULT_DRIFT_THRESHOLD_MS,
    intervalMs: Long = DEFAULT_INTERVAL_MS,
    scheduler: FlushScheduler,
) : Disposable {
    @Volatile
    private var disposed = false

    // Reference points captured at start (clock-watcher.ts: t0Monotonic / t0Wall).
    private var t0Monotonic = getMonotonicMs()
    private var t0Wall = getWallMs()

    private val future: ScheduledFuture<*> = scheduler.scheduleAtFixedRate(intervalMs) { if (!disposed) tick() }

    /** One check: emit + reset iff |wall_elapsed − monotonic_elapsed| >= threshold. */
    fun tick() {
        val now = getMonotonicMs()
        val nowWall = getWallMs()
        val expected = now - t0Monotonic // monotonic time elapsed
        val actual = nowWall - t0Wall // wall time elapsed
        val drift = actual - expected
        if (abs(drift) >= driftThresholdMs) {
            emit(ClockSkewPayload(drift))
            // Reset so we don't keep re-emitting the same drift.
            t0Monotonic = now
            t0Wall = nowWall
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        future.cancel(false)
    }

    companion object {
        /** Interval between checks (clock-watcher.ts default). */
        const val DEFAULT_INTERVAL_MS: Long = 1_000

        /** Minimum |drift| to emit (clock-watcher.ts default). */
        const val DEFAULT_DRIFT_THRESHOLD_MS: Long = 500
    }
}
