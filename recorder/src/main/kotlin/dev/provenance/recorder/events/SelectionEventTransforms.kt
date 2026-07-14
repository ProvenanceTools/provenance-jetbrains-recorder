package dev.provenance.recorder.events

import dev.provenance.core.Position
import dev.provenance.core.Range
import dev.provenance.core.SelectionChangePayload

/**
 * Pure transformer: raw caret/selection geometry → selection.change payload. NO IntelliJ types
 * (CLAUDE.md: "test the event→log-entry transformation as a pure function, separately from the
 * platform wiring"). Mirrors doc-events.ts transformSelectionChange, which uses the primary
 * caret and sets was_selection from whether the selection is non-empty.
 */
fun buildSelectionChangePayload(
    path: String,
    startLine: Long,
    startChar: Long,
    endLine: Long,
    endChar: Long,
    wasSelection: Boolean,
): SelectionChangePayload = SelectionChangePayload(
    path = path,
    range = Range(Position(startLine, startChar), Position(endLine, endChar)),
    wasSelection = wasSelection,
)
