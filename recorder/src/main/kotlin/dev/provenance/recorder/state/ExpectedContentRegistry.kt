package dev.provenance.recorder.state

import dev.provenance.core.PathRole
import dev.provenance.core.ResolvedScope
import dev.provenance.core.resolvePathRole

/**
 * Maps watched relative paths to their ExpectedContent, for every path the manifest's
 * [ResolvedScope] puts under review (recorder PRD §4.5). Mirrors
 * state/expected-content-registry.ts.
 *
 * Keys are workspace-relative paths using '/' separators — the same string the
 * doc.* wiring (DocWiring.relativePath) produces, so seeding from doc.open/doc.change
 * and lookups from the fs listeners agree on the key.
 *
 * ## Live membership
 *
 * Membership is a RULE evaluation, not a set lookup (design spec §5). A manifest may
 * name a folder, so the watched set is not knowable at session start: a file the
 * student creates ten minutes in is watched from its first keystroke. Snapshotting
 * instead would make "I wrote it in a new file" — ordinary, innocent behaviour —
 * produce a silent gap in the record. So [isWatched] evaluates [resolvePathRole]
 * against the live path every call; it never precomputes a set.
 *
 * ## The cap
 *
 * [ExpectedContent] holds full file content per path. Exact-path lists bounded that
 * naturally; a `src/` rule does not. [EXPECTED_CONTENT_MAX_FILES] is the bound, and
 * [capHit] is how the seal learns it bit. Disclosure is mandatory, not decorative: a
 * session that silently stopped watching files it was told to watch would let the
 * analyzer conclude "in scope, no activity" about a student who did nothing wrong.
 */
class ExpectedContentRegistry(
    private val scope: ResolvedScope,
    private val maxFiles: Int = EXPECTED_CONTENT_MAX_FILES,
) {
    private val map = HashMap<String, ExpectedContent>()
    private var _capHit = false

    /**
     * Whether this path is under review right now.
     *
     * Note the deliberate side effect: a path that WOULD have been admitted but for
     * the cap flips [capHit]. That is the only moment the cap is observable, and the
     * fact has to be recorded when it happens rather than inferred later. A path that
     * was never in scope does NOT set it — the cap did not cost us that file. Do not
     * "simplify" this by moving the cap check ahead of the role check: that would
     * flip capHit for paths the manifest never asked us to watch at all.
     */
    fun isWatched(relativePath: String): Boolean {
        // A path admitted once stays admitted — the cap must never evict an
        // already-tracked file mid-session, or its ExpectedContent baseline would go
        // stale and every later comparison for it would be wrong.
        if (map.containsKey(relativePath)) return true
        if (resolvePathRole(relativePath, scope) != PathRole.REVIEWED) return false
        if (map.size >= maxFiles) {
            _capHit = true
            return false
        }
        return true
    }

    /** True once the cap has refused a path that was otherwise under review. */
    fun capHit(): Boolean = _capHit

    /**
     * Get or create the ExpectedContent for a relative path, creating it from
     * [initialContent] on first use. Returns the existing instance if already present
     * (initialContent is then ignored).
     */
    fun getOrCreate(relativePath: String, initialContent: String): ExpectedContent =
        map.getOrPut(relativePath) { ExpectedContent(initialContent) }

    fun get(relativePath: String): ExpectedContent? = map[relativePath]

    fun delete(relativePath: String) {
        map.remove(relativePath)
    }
}

/**
 * Maximum number of files whose expected content is held in memory.
 *
 * Part of the writer contract: all three recorders must use the same number, or two
 * ports disagree about when a session is capped. Companion to `FILE_SCOPE_MAX_ENTRIES`
 * in `session/RecorderContext.kt`. **Must match log-core's own constant** — do not
 * change it here alone.
 */
const val EXPECTED_CONTENT_MAX_FILES: Int = 512
