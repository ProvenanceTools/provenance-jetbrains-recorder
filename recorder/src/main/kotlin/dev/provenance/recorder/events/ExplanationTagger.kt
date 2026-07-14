package dev.provenance.recorder.events

/**
 * Tracks the most recent "benign explanation" for an external change — a formatter run
 * or a git operation — so that an fs.external_change detected within a short window can
 * carry an `explanation` field instead of reading as an unexplained (suspicious) edit
 * (recorder PRD §4.5: "Anything we can't explain stays flagged").
 *
 * Direct 1:1 port of provenance/packages/recorder/src/events/explanation-tags.ts. The
 * git wiring calls [markGit] on every emitted git.event; the external-change emit path
 * calls [consume] once per change and, if non-null, sets FsExternalChangePayload.explanation.
 * No automatic detection here — callers mark explicitly.
 *
 * Pure logic: [getNow] is an injected monotonic clock (the session's Clock.now()), so this
 * is unit-testable without any IntelliJ Platform or wall-clock dependency.
 */
class ExplanationTagger(
    private val getNow: () -> Long,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private data class Tag(val kind: String, val at: Long)

    @Volatile
    private var latest: Tag? = null

    /** Record that a formatter operation just ran. */
    fun markFormatter() {
        latest = Tag("formatter", getNow())
    }

    /** Record that a git operation just ran. */
    fun markGit() {
        latest = Tag("git", getNow())
    }

    /**
     * Return and clear the most recent tag if it is within the window. One explanation
     * explains one external change (consume-once semantics). Returns null if no tag has
     * been set or if the tag has expired (elapsed >= windowMs, matching the TS original's
     * boundary), clearing an expired tag so it is not checked again.
     */
    fun consume(): String? {
        val tag = latest ?: return null
        val elapsed = getNow() - tag.at
        if (elapsed >= windowMs) {
            latest = null
            return null
        }
        latest = null
        return tag.kind
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 2000L
    }
}
