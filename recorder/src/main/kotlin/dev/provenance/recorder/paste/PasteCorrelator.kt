package dev.provenance.recorder.paste

import dev.provenance.core.DocChangeDelta
import dev.provenance.core.Range

/**
 * The combined-signal decision for one doc-change batch. EmitPaste has no
 * source/confirmation field because PastePayload (events.ts:95-103) doesn't carry
 * one — a paste-kind event is inherently the high-confidence shape. Confirmation
 * only affects the EmitDocChange branch's `source` value.
 */
sealed interface PasteDecision {
    data class EmitPaste(val fields: PastePayloadFields, val range: Range) : PasteDecision
    data class EmitDocChange(val source: String) : PasteDecision
}

/**
 * Combines signal 1 (classifyChange), signal 2 (onPasteActionFired — the
 * EditorPaste action was intercepted), and signal 3 (clipboard-text similarity)
 * into one PasteDecision per doc-change batch.
 *
 * Unlike VS Code's consumeIfPasteExpected (computed then discarded in doc-wiring.ts),
 * the confirmation here is actually used: IntelliJ's wrapped EditorPaste handler
 * gives a same-call-stack clipboard read the VS Code separate-command workaround
 * never had, so 'paste_confirmed' can finally be emitted.
 *
 * Determinism: takes an injected clock (getNow), never System.currentTimeMillis().
 */
class PasteCorrelator(
    private val getNow: () -> Long,
    private val withinMs: Long = 50,
    private val similarityThreshold: Double = PASTE_CONFIRM_SIMILARITY_THRESHOLD,
) {
    private data class Pending(val atMs: Long, val clipboardText: String?)

    private var pending: Pending? = null
    private var interceptedCounter = 0
    private var largeInsertCounter = 0

    val interceptedCount: Int get() = interceptedCounter
    val largeInsertCount: Int get() = largeInsertCounter

    /** Signal 2 + 3: called by the wiring before delegating to the real paste handler. */
    fun onPasteActionFired(clipboardText: String?) {
        interceptedCounter++
        pending = Pending(getNow(), clipboardText)
    }

    /** Signal 1, reconciled against any pending signal-2/3 expectation. */
    fun onDocChange(deltas: List<DocChangeDelta>): PasteDecision {
        val classification = classifyChange(deltas)
        if (classification == PasteClassification.TYPED) {
            // A typed change does not consume a pending expectation: IntelliJ's paste
            // dispatch is synchronous, so an unrelated typed keystroke should not
            // normally interleave between a paste action firing and its resulting
            // doc change. VERIFY AT EXECUTION (runIde) if interleaving is observed.
            return PasteDecision.EmitDocChange(source = "typed")
        }

        largeInsertCounter++
        val now = getNow()
        val p = pending
        val confirmed = p != null &&
            (now - p.atMs) <= withinMs &&
            (p.clipboardText == null || run {
                val insertedText = deltas.joinToString("") { it.text }
                PasteTextSimilarity.similarity(p.clipboardText, insertedText) >= similarityThreshold
            })
        pending = null // consume: one action expectation matches at most one doc change

        val d0 = deltas.getOrNull(0)
        val isSinglePasteShaped = deltas.size == 1 && d0 != null && d0.range.start == d0.range.end
        return if (isSinglePasteShaped) {
            PasteDecision.EmitPaste(buildPastePayload(d0!!.text), d0.range)
        } else {
            PasteDecision.EmitDocChange(source = if (confirmed) "paste_confirmed" else "paste_likely")
        }
    }
}
