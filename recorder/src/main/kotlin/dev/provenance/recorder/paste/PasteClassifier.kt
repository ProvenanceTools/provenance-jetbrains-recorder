package dev.provenance.recorder.paste

import dev.provenance.core.DocChangeDelta

/**
 * Signal 1 of three-signal paste detection (recorder PRD §4.3 point 1).
 * Direct port of packages/recorder/src/events/paste-classifier.ts's
 * classifyChange — same two-rule logic, same threshold. Pure function,
 * editor-agnostic.
 *
 * Reconciliation note: consumes core/'s DocChangeDelta (Plan 4 already defined
 * Position/Range/DocChangeDelta in dev.provenance.core) rather than a duplicate
 * recorder-local type — per the plan's own "unify on Plan 4's types" caution.
 */
enum class PasteClassification { TYPED, PASTE_LIKELY }

const val PASTE_MIN_INSERT_CHARS = 30

fun classifyChange(deltas: List<DocChangeDelta>): PasteClassification {
    if (deltas.isEmpty()) return PasteClassification.TYPED

    var totalInsertedChars = 0
    var maxSingleDeltaChars = 0
    var anyDeltaHasNewline = false

    for (delta in deltas) {
        val len = delta.text.length
        totalInsertedChars += len
        if (len > maxSingleDeltaChars) maxSingleDeltaChars = len
        if (!anyDeltaHasNewline && delta.text.contains('\n')) {
            anyDeltaHasNewline = true
        }
    }

    // Rule 1: a single delta carries >= threshold chars on its own. Covers
    // classical paste (empty-range insert) AND large replacement edits.
    if (maxSingleDeltaChars >= PASTE_MIN_INSERT_CHARS) {
        return PasteClassification.PASTE_LIKELY
    }

    // Rule 2: aggregate >= threshold AND at least one delta has a newline.
    // Distinguishes a multi-line bulk edit from multi-cursor typing.
    if (totalInsertedChars >= PASTE_MIN_INSERT_CHARS && anyDeltaHasNewline) {
        return PasteClassification.PASTE_LIKELY
    }

    return PasteClassification.TYPED
}
