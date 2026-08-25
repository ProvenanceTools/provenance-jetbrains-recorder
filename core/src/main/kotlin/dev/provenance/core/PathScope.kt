package dev.provenance.core

/**
 * Path scope — the ONLY matcher that decides whether a path is in scope.
 *
 * Ported from log-core's `path-scope.ts`. Design spec:
 * `docs/superpowers/specs/2026-08-22-path-scope-design.md` §3. Answers
 * `2026-08-19-git-collaboration-semantics.md` §8.7 / S25.
 *
 * ## Why three forms and not a glob
 *
 * This rule is re-implemented by hand in Kotlin (here) and Lua (provnvim). A real
 * glob engine written three times is three chances for the same manifest to watch
 * different files on different editors, which is the divergence risk the parent
 * spec §10 exists to prevent. Three forms reduce the matcher to a size a port
 * cannot plausibly get wrong, and `path-scope-vectors.json` pins it across all
 * three ports.
 *
 * ## Why matching is byte-exact
 *
 * No separator normalization, no `.` resolution, no case folding. This is the same
 * rule `files_under_review` has always followed, for the same reason: normalizing
 * on one axis would quietly make two recorders' spellings compare equal here and
 * unequal everywhere else. macOS being case-insensitive on disk is a known and
 * accepted consequence.
 *
 * ## The editor glob is never the authority
 *
 * The IDE's own file watcher may be handed a directory entry as a coarse
 * pre-filter (`src/` -> a broad watch). This port MUST re-check the resulting path
 * against [matchesScopeEntry] before emitting anything — see design spec §4.2 and
 * the `editorGlobHazards` conformance vectors, which exist specifically to catch a
 * port that trusts its watcher's verdict alone.
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/**
 * Why a scope entry is illegal. [wire] is the on-the-wire spelling pinned by the
 * conformance vectors (`empty`, `whitespace`, `backslash`, `absolute`,
 * `dot_segment`, `empty_segment`, `bad_wildcard`, `forbidden_char`).
 */
enum class ScopeEntryProblemKind(val wire: String) {
    EMPTY("empty"),
    WHITESPACE("whitespace"),
    BACKSLASH("backslash"),
    ABSOLUTE("absolute"),
    DOT_SEGMENT("dot_segment"),
    EMPTY_SEGMENT("empty_segment"),
    BAD_WILDCARD("bad_wildcard"),
    FORBIDDEN_CHAR("forbidden_char"),
    ;

    companion object {
        /** The wire spellings, in enum declaration order. */
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        fun fromWire(value: String): ScopeEntryProblemKind? = entries.firstOrNull { it.wire == value }
    }
}

/** Everything wrong with one scope entry, returned by [validateScopeEntry]. */
data class ScopeEntryProblem(
    val kind: ScopeEntryProblemKind,
    /** A complete sentence, safe to show staff in the composer. */
    val detail: String,
)

/** The three course-signed lists, already extracted from a manifest. */
data class ResolvedScope(
    val track: List<String>,
    val ignore: List<String>,
    val attachments: List<String>,
)

/**
 * What the recorder does with a path. Ordered by the precedence in design spec
 * §3.4: excluded > ignored > attachment > reviewed > unscoped.
 */
enum class PathRole(val wire: String) {
    EXCLUDED("excluded"),
    IGNORED("ignored"),
    ATTACHMENT("attachment"),
    REVIEWED("reviewed"),
    UNSCOPED("unscoped"),
    ;

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        fun fromWire(value: String): PathRole? = entries.firstOrNull { it.wire == value }
    }
}

// ---------------------------------------------------------------------------
// Hard exclusions — not course-controllable
// ---------------------------------------------------------------------------

/**
 * Never in scope, whatever the manifest says.
 *
 * With exact-path lists this came for free — nobody lists their own provenance
 * directory. With rules it does not: `ignore: ["*.json"]` would otherwise reach
 * `.provenance/manifest.json`, and a broad `attachments` entry would seal the log
 * directory into itself.
 */
val HARD_EXCLUDED_PREFIXES: List<String> = listOf(".provenance/", ".git/")

val HARD_EXCLUDED_PATHS: List<String> = listOf(".provenance-manifest", "provenance-manifest")

fun isHardExcluded(path: String): Boolean {
    for (p in HARD_EXCLUDED_PREFIXES) {
        if (path.startsWith(p)) return true
    }
    return path in HARD_EXCLUDED_PATHS
}

// ---------------------------------------------------------------------------
// Matching
// ---------------------------------------------------------------------------

/** True iff [entry] is the exact-path form (neither directory nor suffix). */
fun isExactEntry(entry: String): Boolean = !entry.endsWith("/") && !entry.startsWith("*")

/**
 * The whole matching rule. Assumes [entry] has already passed [validateScopeEntry]
 * — a malformed entry is rejected at manifest parse time, so it never reaches
 * here.
 *
 * **Order is load-bearing.** The directory form (`endsWith("/")`) is tested
 * BEFORE the suffix form (`startsWith("*")`), which is exactly why `*.java/` —
 * legal-looking, matching neither developer's intuition for "both" — is rejected
 * by [validateScopeEntry]: the directory check would consume it first and it
 * could then only ever match a path literally beginning `*.java/`, which no
 * workspace-relative path does.
 */
fun matchesScopeEntry(path: String, entry: String): Boolean {
    if (entry.endsWith("/")) return path.startsWith(entry)
    if (entry.startsWith("*")) return path.endsWith(entry.substring(1))
    return path == entry
}

fun matchesAnyScopeEntry(path: String, entries: List<String>): Boolean {
    for (entry in entries) {
        if (matchesScopeEntry(path, entry)) return true
    }
    return false
}

/** Precedence chain, design spec §3.4. First match wins. */
fun resolvePathRole(path: String, scope: ResolvedScope): PathRole {
    if (isHardExcluded(path)) return PathRole.EXCLUDED
    if (matchesAnyScopeEntry(path, scope.ignore)) return PathRole.IGNORED
    if (matchesAnyScopeEntry(path, scope.attachments)) return PathRole.ATTACHMENT
    if (matchesAnyScopeEntry(path, scope.track)) return PathRole.REVIEWED
    return PathRole.UNSCOPED
}

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

private val FORBIDDEN_CHARS: List<Char> = listOf('?', '[', ']', '{', '}')

/** `^[A-Za-z]:` — a Windows drive-letter prefix, anchored at the start of the entry. */
private val DRIVE_LETTER_PREFIX = Regex("^[A-Za-z]:")

/**
 * Everything wrong with one entry, or null if it is legal.
 *
 * Runs at manifest PARSE time and rejects the manifest. A malformed entry is a
 * staff error that must be caught before a manifest is distributed to a class,
 * not a runtime surprise diagnosed weeks later.
 *
 * **Check order is load-bearing** — the conformance vectors pin cases that only
 * come out right if this order is preserved exactly: empty, whitespace,
 * backslash, absolute, wildcard-count/position, bare `*`, suffix-that-also-ends-
 * in-slash, forbidden chars, segment checks. In particular a leading star
 * immediately followed by a trailing slash must reach `bad_wildcard`, and a bare
 * slash must reach `absolute` — both fail differently if the absolute check is
 * not the FOURTH check.
 */
fun validateScopeEntry(entry: String): ScopeEntryProblem? {
    if (entry.isEmpty()) {
        return ScopeEntryProblem(ScopeEntryProblemKind.EMPTY, "An entry may not be empty.")
    }
    if (entry != entry.trim()) {
        return ScopeEntryProblem(
            ScopeEntryProblemKind.WHITESPACE,
            "An entry may not begin or end with whitespace. A trailing space is invisible here and " +
                "produces an entry that matches nothing.",
        )
    }
    if (entry.contains('\\')) {
        return ScopeEntryProblem(
            ScopeEntryProblemKind.BACKSLASH,
            "Use forward slashes. A backslash never matches, on any platform.",
        )
    }
    if (entry.startsWith("/") || DRIVE_LETTER_PREFIX.containsMatchIn(entry)) {
        return ScopeEntryProblem(
            ScopeEntryProblemKind.ABSOLUTE,
            "An entry must be relative to the folder holding the manifest.",
        )
    }

    val starCount = entry.count { it == '*' }
    if (starCount > 1 || (starCount == 1 && !entry.startsWith("*"))) {
        return ScopeEntryProblem(
            ScopeEntryProblemKind.BAD_WILDCARD,
            "The only wildcard is a single leading \"*\", which matches a filename suffix at any " +
                "depth (e.g. \"*.java\"). There is no \"**\" and no mid-path wildcard.",
        )
    }
    if (entry == "*") {
        return ScopeEntryProblem(ScopeEntryProblemKind.BAD_WILDCARD, "A leading \"*\" must be followed by a suffix.")
    }
    // A suffix entry that also ends in "/" validates as legal but is DEAD: the
    // matcher tests the directory form first, so `*.java/` can only ever match a
    // path that literally begins `*.java/`, which no workspace-relative path
    // does. A course that wrote it would sign a manifest that watches nothing and
    // find out weeks later. Rejecting at parse time is the whole point of this
    // function — the two forms are mutually exclusive, so asking for both is
    // always a mistake, never a shorthand.
    if (entry.startsWith("*") && entry.endsWith("/")) {
        return ScopeEntryProblem(
            ScopeEntryProblemKind.BAD_WILDCARD,
            "A leading \"*\" matches a filename suffix, so the entry may not also end with \"/\". " +
                "Write \"*.java\" to match files by suffix, or \"java/\" to match a directory.",
        )
    }

    for (c in FORBIDDEN_CHARS) {
        if (entry.contains(c)) {
            return ScopeEntryProblem(
                ScopeEntryProblemKind.FORBIDDEN_CHAR,
                "\"$c\" is not supported. The only forms are an exact path, a \"dir/\" prefix, and a " +
                    "leading \"*\" suffix.",
            )
        }
    }

    // Segment checks run on the path part only, so a leading "*" is not mistaken
    // for a segment. A directory entry's trailing "/" produces a final empty
    // segment that is legal, so it is dropped before checking.
    val pathPart = if (entry.startsWith("*")) entry.substring(1) else entry
    val withoutTrailingSlash = if (pathPart.endsWith("/")) pathPart.substring(0, pathPart.length - 1) else pathPart
    val segments = withoutTrailingSlash.split("/")
    for (seg in segments) {
        if (seg == "." || seg == "..") {
            return ScopeEntryProblem(
                ScopeEntryProblemKind.DOT_SEGMENT,
                "An entry may not contain a \".\" or \"..\" segment.",
            )
        }
        if (seg.isEmpty()) {
            return ScopeEntryProblem(
                ScopeEntryProblemKind.EMPTY_SEGMENT,
                "An entry may not contain an empty path segment.",
            )
        }
    }

    return null
}
