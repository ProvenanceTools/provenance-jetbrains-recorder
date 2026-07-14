package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.Disposable
import dev.provenance.core.PasteAnomalyPayload
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.paste.PasteAnomalyReconciler
import dev.provenance.recorder.paste.PasteCounterSnapshot
import dev.provenance.recorder.paste.PasteCorrelator
import java.util.concurrent.ScheduledFuture

/**
 * Periodic paste.anomaly emitter (signal 3's aggregate fallback — see
 * PasteAnomalyReconciler). Mirrors Heartbeat's injectable-scheduler shape: the
 * per-tick reconciliation logic is exposed as [tick] for deterministic unit testing,
 * while the interval scheduling goes through an injected [FlushScheduler] (production
 * wraps AppExecutorUtil; tests pass a no-op). No background task without a dispose
 * path (CLAUDE.md): [dispose] cancels the schedule and is idempotent.
 */
class PasteAnomalyTicker(
    private val correlator: PasteCorrelator,
    private val emit: (PasteAnomalyPayload) -> Unit,
    intervalMs: Long = DEFAULT_INTERVAL_MS,
    scheduler: FlushScheduler,
) : Disposable {
    private var lastSnapshot = PasteCounterSnapshot(correlator.interceptedCount, correlator.largeInsertCount)

    @Volatile
    private var disposed = false
    private val future: ScheduledFuture<*> = scheduler.scheduleAtFixedRate(intervalMs) { if (!disposed) tick() }

    /** One reconciliation pass: snapshot → diff-against-last → emit on anomaly. */
    fun tick() {
        val current = PasteCounterSnapshot(correlator.interceptedCount, correlator.largeInsertCount)
        val anomaly = PasteAnomalyReconciler.check(lastSnapshot, current)
        lastSnapshot = current
        if (anomaly != null) emit(anomaly)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        future.cancel(false)
    }

    companion object {
        const val DEFAULT_INTERVAL_MS: Long = 5_000
    }
}
