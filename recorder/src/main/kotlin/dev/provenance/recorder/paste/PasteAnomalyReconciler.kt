package dev.provenance.recorder.paste

import dev.provenance.core.PasteAnomalyPayload
import kotlin.math.abs

data class PasteCounterSnapshot(val intercepted: Int, val largeInsert: Int)

/**
 * Periodic count-diff, ported from packages/recorder/src/events/paste-reconciler.ts's
 * diff rule (same tolerance semantics, same payload meaning: deltas since the last
 * check, not cumulative totals). Pure — the caller (PasteAnomalyTicker) owns the
 * interval/timer and supplies before/after snapshots.
 *
 * Why this exists despite PasteCorrelator's per-event correlation: the correlator
 * only sees edits that go through the EditorPaste action. A PSI/Document-API bulk
 * edit from an external tool bypasses the action system entirely — this aggregate
 * count-diff still catches "large inserts happened without matching intercepted
 * actions" (the AI-tool WorkspaceEdit blind spot paste-classifier.ts documents).
 */
object PasteAnomalyReconciler {
    fun check(
        before: PasteCounterSnapshot,
        after: PasteCounterSnapshot,
        toleranceWindow: Int = 1,
    ): PasteAnomalyPayload? {
        val deltaIntercepted = after.intercepted - before.intercepted
        val deltaLargeInsert = after.largeInsert - before.largeInsert
        val discrepancy = abs(deltaIntercepted - deltaLargeInsert)
        if (discrepancy <= toleranceWindow) return null
        return PasteAnomalyPayload(interceptedCount = deltaIntercepted, largeInsertCount = deltaLargeInsert)
    }
}
