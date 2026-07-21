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

    /**
     * Bounded FIFO of hashes this model has held, oldest first. Maintained eagerly (one
     * sha256 per content mutation) because the states we need to recognise later are
     * exactly the intermediate ones — by the time a stale on-disk snapshot reaches the
     * comparison, the content that produced it is long gone. Hashes only; never content.
     */
    private val recentHashes = ArrayDeque<String>()

    init {
        recordState()
    }

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

    /**
     * Whether [hash] is one of the recent content states this model has held.
     *
     * External-change detection uses this to tell "the editor's own write, which we
     * observed after a later keystroke already moved the model on" from "something
     * outside the editor wrote this file". An on-disk snapshot whose hash appears here is
     * a state the buffer genuinely passed through, so the write was ours.
     */
    fun hasRecentHash(hash: String): Boolean = recentHashes.contains(hash)

    /** Apply a single doc.change delta. Updates content + invalidates cached hash. */
    fun applyDelta(delta: Delta) {
        applyDeltaInPlace(delta)
        recordState()
    }

    /**
     * Apply many deltas in order. Only the resulting state is recorded in the recent-hash
     * ring: the states between deltas of a single change event were never observable as
     * buffer content, so they must not widen the tolerance window.
     */
    fun applyDeltas(deltas: List<Delta>) {
        for (d in deltas) applyDeltaInPlace(d)
        recordState()
    }

    /** Replace content wholesale (e.g. after an fs.external_change reconciliation). */
    fun reset(content: String) {
        _content = content
        _hash = null
        recordState()
    }

    /** Mutate content by one delta without touching the recent-hash ring. */
    private fun applyDeltaInPlace(delta: Delta) {
        _content = _content.substring(0, delta.offset) + delta.newText +
            _content.substring(delta.offset + delta.oldLength)
        _hash = null
    }

    /** Push the current hash onto the bounded recent-state ring. */
    private fun recordState() {
        val current = hash // computes + memoizes, so `hash` reads stay free
        if (recentHashes.lastOrNull() == current) return // no-op change: don't spend a slot
        recentHashes.addLast(current)
        if (recentHashes.size > RECENT_HASH_RING_SIZE) recentHashes.removeFirst()
    }

    companion object {
        /**
         * How many recent buffer-content hashes to retain per watched file — the tolerance
         * window for "the disk holds a state this buffer genuinely passed through".
         *
         * A *count* rather than a time window on purpose: it needs no clock, so the model
         * stays pure and deterministic under test, and it degrades correctly for fast and
         * slow typists alike. 32 states is comfortably more than the number of keystrokes
         * that can reach the EDT between a VFS save event and the pooled-thread comparison,
         * while staying trivially small (32 x 64 hex chars, ~2 KB per watched file).
         * Kept identical to the VS Code recorder's RECENT_HASH_RING_SIZE.
         */
        const val RECENT_HASH_RING_SIZE = 32
    }
}
