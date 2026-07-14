package dev.provenance.recorder.state

import dev.provenance.core.Sha256

/**
 * One doc.change delta. Offset-based — mirrors
 * com.intellij.openapi.editor.event.DocumentEvent (offset, oldLength, newFragment).
 *
 * Deliberate simplification vs the VS Code recorder: state/expected-content.ts's
 * Delta carries a {line, character} range and hand-converts it to string offsets,
 * because vscode.TextDocumentContentChangeEvent is line/character-based. IntelliJ's
 * DocumentEvent is already offset-based, so the position-to-offset conversion is
 * dropped entirely. Same resulting content/hash, less code.
 */
data class Delta(val offset: Int, val oldLength: Int, val newText: String)

/**
 * In-memory model of what a watched file's content SHOULD be, per the sum of
 * doc.change deltas observed since the last reset. The source of truth for
 * external-change detection (recorder PRD §4.5): on-disk content is always compared
 * AGAINST this, never the other way around. Mirrors state/expected-content.ts.
 */
class ExpectedContent(initialContent: String) {
    private var _content: String = initialContent
    private var _hash: String? = null // null = needs recompute

    val content: String get() = _content

    /** Line count. Empty string → 0. Non-empty with no '\n' → 1. Trailing '\n' counts an empty line. */
    val lineCount: Int
        get() {
            if (_content.isEmpty()) return 0
            var count = 1
            for (c in _content) if (c == '\n') count++
            return count
        }

    /** Current hex sha256 of the full content. Memoized; invalidated by applyDelta/reset. */
    val hash: String
        get() {
            var h = _hash
            if (h == null) {
                h = Sha256.hex(_content)
                _hash = h
            }
            return h
        }

    /** Apply a single doc.change delta. Updates content + invalidates cached hash. */
    fun applyDelta(delta: Delta) {
        _content = _content.substring(0, delta.offset) + delta.newText +
            _content.substring(delta.offset + delta.oldLength)
        _hash = null
    }

    /** Apply many deltas in order. */
    fun applyDeltas(deltas: List<Delta>) {
        for (d in deltas) applyDelta(d)
    }

    /** Replace content wholesale (e.g. after an fs.external_change reconciliation). */
    fun reset(content: String) {
        _content = content
        _hash = null
    }
}
