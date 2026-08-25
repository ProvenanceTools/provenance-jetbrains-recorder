package dev.provenance.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The three `session.start` CAPABILITY REPORTS — collaboration spec §5.6. Ported from
 * log-core's `session-capabilities.ts`; the payload TYPE ([SessionFileScope]) lives in
 * [Events.kt] with every other payload, and the runtime narrowing lives here, exactly as
 * log-core splits it and exactly as [GitEvent.kt] and [PeerObserved.kt] already do for their
 * own fields.
 *
 * ## What a capability report is, and what it is emphatically not
 *
 * The parent spec §4 establishes the absence-vs-disabled rule: the effective capture policy
 * must travel into the bundle, because otherwise the analyzer cannot distinguish "this student
 * produced no `selection.change` events" from "this course disabled `selection.change`".
 * Collaboration spec §5.6 adds three more instances of the same rule, and these are they.
 *
 * A capability report says **"I could not"**. A capture knob says **"I was told not to"**.
 * Those are different facts with different owners: a knob is a course-signed manifest field a
 * professor controls, a capability report is the recorder describing the machine it ran on.
 * Nothing here is policy-gated and nothing here is ever a finding.
 *
 * ## None of the three is ever a finding
 *
 * No flag, no ninth validation check, no severity, no score. Facts only. They exist so an
 * EXISTING finding can be read correctly (decision D16 made `git_unrecorded_in` fire for an
 * honest pair whose partner simply was not recording — "git capture was impossible on this
 * machine" is exactly the context a grader needs), and so a coverage surface can say "we could
 * not check" instead of implying "there was nothing to check".
 *
 * ## Absent is the ordinary case, permanently
 *
 * Every bundle recorded before §5.6 landed carries none of these fields, and every field is
 * optional permanently. Each read therefore models absence as its own answer, and `absent` MUST
 * be read as "this recorder does not report the capability" — never as `unavailable`.
 *
 * ## Three answers, not two — the D12 shape
 *
 * [readGitCapture], [readWitnessCapture] and [readFileScope] each return absent / recorded /
 * malformed, and the third is what makes the second safe, exactly as
 * [readRepositoryDiscriminator] does for D12. [GitCaptureCapability] and
 * [WitnessCaptureCapability] are CLOSED ENUMS: a value outside the set is a nonconforming
 * writer, and folding it into a legal value would put an invented meaning in front of a grader.
 *
 * ## Writers OMIT, never `null`
 *
 * Omission and `null` canonicalize differently and therefore chain to different hashes, exactly
 * as `parents: []` and an absent `parents` do. Readers accept `null` as absence so a
 * nonconforming log still parses; a writer that emits it is nonconforming. [SessionStartPayload]
 * cannot express `null` for any of the three — its fields are typed `?` and
 * [SessionStartPayload.toJsonObject] SPREADS them with `?.let { put(...) }` — so a writer here
 * cannot emit the field at all without a value, which is how omission is enforced structurally
 * rather than by care at each call site.
 */

// ---------------------------------------------------------------------------
// Field names
// ---------------------------------------------------------------------------

/** The payload key that carries the git-capture report. */
const val GIT_CAPTURE_FIELD: String = "git_capture"

/** The payload key that carries the witness-capture report. */
const val WITNESS_CAPTURE_FIELD: String = "witness_capture"

/** The payload key that carries the file-scope report. */
const val FILE_SCOPE_FIELD: String = "file_scope"

// ---------------------------------------------------------------------------
// §5.6 item 2 — was git observation available?
// ---------------------------------------------------------------------------

/**
 * Every legal value of `session.start.git_capture`, in the wire spelling log-core uses.
 *
 *  - [AVAILABLE] — the git integration answered and this session was watching at least the
 *    repositories it owns. An absence of `git.event` in this session means git activity did not
 *    happen, not that it could not be seen.
 *  - [UNAVAILABLE] — the recorder could not observe git AT ALL on this machine. Nothing this
 *    session did could have produced a `git.event`.
 *  - [NOT_OWNED] — git observation worked, and every repository visible to it was outside this
 *    session's assignment scope, so its events were deliberately dropped by the ownership gate.
 *    The recorder COULD see git; it was routing, not incapacity.
 *
 * The three are NOT interchangeable. [UNAVAILABLE] is a statement about the machine's software;
 * [NOT_OWNED] is a statement about where the assignment sits relative to the repositories. A
 * reader that reports one as the other is describing a different situation than the one that
 * occurred.
 */
enum class GitCaptureCapability {
    AVAILABLE,
    UNAVAILABLE,
    NOT_OWNED,
    ;

    /** The wire spelling log-core uses. */
    val wire: String
        get() = when (this) {
            AVAILABLE -> "available"
            UNAVAILABLE -> "unavailable"
            NOT_OWNED -> "not_owned"
        }

    companion object {
        /** Every legal wire value, in log-core's fixed order. */
        val WIRE_VALUES: List<String> = listOf("available", "unavailable", "not_owned")

        /** The value for [wire], or null when it is outside the closed enum. */
        fun fromWire(wire: String): GitCaptureCapability? = entries.firstOrNull { it.wire == wire }
    }
}

// ---------------------------------------------------------------------------
// §5.6 item 3 — was `.provenance/` witnessing available?
// ---------------------------------------------------------------------------

/**
 * Every legal value of `session.start.witness_capture`.
 *
 *  - [AVAILABLE] — a `.provenance/` watcher was running. Had a partner's `.slog` appeared,
 *    changed or vanished, this session would have witnessed it.
 *  - [UNAVAILABLE] — no watcher. The absence of `peer.observed` events in this session says
 *    nothing whatsoever about what was in `.provenance/`.
 *
 * **Two values, deliberately, where [GitCaptureCapability] has three.** There is no witnessing
 * analogue of `not_owned`: a recorder witnesses the `.provenance/` directory it is itself
 * writing into, so there is no ownership question to route on.
 */
enum class WitnessCaptureCapability {
    AVAILABLE,
    UNAVAILABLE,
    ;

    /** The wire spelling log-core uses. */
    val wire: String
        get() = when (this) {
            AVAILABLE -> "available"
            UNAVAILABLE -> "unavailable"
        }

    companion object {
        /** Every legal wire value, in log-core's fixed order. */
        val WIRE_VALUES: List<String> = listOf("available", "unavailable")

        /** The value for [wire], or null when it is outside the closed enum. */
        fun fromWire(wire: String): WitnessCaptureCapability? = entries.firstOrNull { it.wire == wire }
    }
}

// ---------------------------------------------------------------------------
// Reads
// ---------------------------------------------------------------------------

/**
 * Why a present capability value could not be read.
 *
 * Descriptive only. Neither is evidence about a student, and no reader may turn one into a
 * finding.
 */
enum class CapabilityValueProblem {
    /** Present, but not a JSON string. */
    NOT_A_STRING,

    /** A string outside the closed enum. A nonconforming writer, or a newer one. */
    UNKNOWN_VALUE,
    ;

    /** The wire spelling log-core uses. */
    val wire: String
        get() = when (this) {
            NOT_A_STRING -> "not_a_string"
            UNKNOWN_VALUE -> "unknown_value"
        }
}

/**
 * What one `session.start` says about whether git observation was available.
 *
 * [Absent] and [Malformed] both leave a consumer with no usable answer and are still different
 * facts, which is why they are different variants: one is a recorder with nothing to say, the
 * other a recorder that said something wrong, and only the second is worth counting.
 */
sealed interface GitCaptureRead {
    data object Absent : GitCaptureRead

    data class Recorded(val capture: GitCaptureCapability) : GitCaptureRead

    data class Malformed(val problem: CapabilityValueProblem) : GitCaptureRead

    /** The wire spelling of the variant, matching log-core's `kind` field. */
    val kind: String
        get() = when (this) {
            is Absent -> "absent"
            is Recorded -> "recorded"
            is Malformed -> "malformed"
        }
}

/** @see GitCaptureRead */
sealed interface WitnessCaptureRead {
    data object Absent : WitnessCaptureRead

    data class Recorded(val capture: WitnessCaptureCapability) : WitnessCaptureRead

    data class Malformed(val problem: CapabilityValueProblem) : WitnessCaptureRead

    /** The wire spelling of the variant, matching log-core's `kind` field. */
    val kind: String
        get() = when (this) {
            is Absent -> "absent"
            is Recorded -> "recorded"
            is Malformed -> "malformed"
        }
}

/**
 * Read the git-capture report out of an untyped `session.start` `data` value.
 *
 * Pure and total: never throws, never mutates. A `.slog` is a student-editable file, so every
 * input here is untrusted and a malformed one is EXPECTED rather than exceptional.
 *
 * `null` (both an absent [data] and a `data` whose [GIT_CAPTURE_FIELD] entry is [JsonNull]) is
 * accepted as absence so a nonconforming log still parses — but a **writer must OMIT the
 * field**, never emit `null`.
 */
fun readGitCapture(data: JsonObject?): GitCaptureRead {
    val raw = data?.get(GIT_CAPTURE_FIELD) ?: return GitCaptureRead.Absent
    if (raw is JsonNull) return GitCaptureRead.Absent
    if (raw !is JsonPrimitive || !raw.isString) {
        return GitCaptureRead.Malformed(CapabilityValueProblem.NOT_A_STRING)
    }
    val capture = GitCaptureCapability.fromWire(raw.content)
        ?: return GitCaptureRead.Malformed(CapabilityValueProblem.UNKNOWN_VALUE)
    return GitCaptureRead.Recorded(capture)
}

/** @see readGitCapture */
fun readWitnessCapture(data: JsonObject?): WitnessCaptureRead {
    val raw = data?.get(WITNESS_CAPTURE_FIELD) ?: return WitnessCaptureRead.Absent
    if (raw is JsonNull) return WitnessCaptureRead.Absent
    if (raw !is JsonPrimitive || !raw.isString) {
        return WitnessCaptureRead.Malformed(CapabilityValueProblem.NOT_A_STRING)
    }
    val capture = WitnessCaptureCapability.fromWire(raw.content)
        ?: return WitnessCaptureRead.Malformed(CapabilityValueProblem.UNKNOWN_VALUE)
    return WitnessCaptureRead.Recorded(capture)
}

// ---------------------------------------------------------------------------
// §5.6 item 1 — the effective resolved file set
// ---------------------------------------------------------------------------

/**
 * Why a present `file_scope` could not be read.
 *
 * The last four are the PRIVACY shape check, and they are the reason this reader inspects the
 * value at all — see [readFileScope].
 */
enum class FileScopeProblem {
    /** Present, but not a JSON object. */
    NOT_AN_OBJECT,

    /** `watched` is missing or not an array. */
    WATCHED_NOT_AN_ARRAY,

    /** `complete` is missing or not a boolean. It is never inferred. */
    COMPLETE_NOT_A_BOOLEAN,

    /** An element of `watched` is not a string. */
    PATH_NOT_A_STRING,

    /** An element of `watched` is the empty string, which names no file. */
    PATH_EMPTY,

    /**
     * An element of `watched` is an ABSOLUTE path — POSIX (`/…`), a Windows drive (`C:\…`,
     * `c:/…`) or a UNC share (`\\host\…`). An absolute path embeds the account name and the
     * machine's layout; S14(b) forbids it.
     */
    PATH_ABSOLUTE,

    /** An element of `watched` contains a `..` segment, so it names something outside the
     * assignment scope. */
    PATH_ESCAPES_SCOPE,

    /**
     * An element of `watched` contains a colon somewhere other than a Windows drive letter
     * (which is [PATH_ABSOLUTE]).
     *
     * That is every spelling of a remote URL — `https://host/…`, `ssh://…`, `file://…` and
     * git's scp-style `user@host:path` — and a remote URL embeds the org and frequently the
     * student's own username, which S14(b) forbids. It is also not a portable filename
     * character, so a conforming assignment-relative path never needs one.
     */
    PATH_HAS_COLON,
    ;

    /** The wire spelling log-core uses. */
    val wire: String
        get() = when (this) {
            NOT_AN_OBJECT -> "not_an_object"
            WATCHED_NOT_AN_ARRAY -> "watched_not_an_array"
            COMPLETE_NOT_A_BOOLEAN -> "complete_not_a_boolean"
            PATH_NOT_A_STRING -> "path_not_a_string"
            PATH_EMPTY -> "path_empty"
            PATH_ABSOLUTE -> "path_absolute"
            PATH_ESCAPES_SCOPE -> "path_escapes_scope"
            PATH_HAS_COLON -> "path_has_colon"
        }
}

/**
 * What one `session.start` says about the files this session actually watched.
 *
 * @see readFileScope for why a malformed set is rejected WHOLE.
 */
sealed interface FileScopeRead {
    data object Absent : FileScopeRead

    data class Recorded(val watched: List<String>, val complete: Boolean) : FileScopeRead

    data class Malformed(val problem: FileScopeProblem) : FileScopeRead

    /** The wire spelling of the variant, matching log-core's `kind` field. */
    val kind: String
        get() = when (this) {
            is Absent -> "absent"
            is Recorded -> "recorded"
            is Malformed -> "malformed"
        }
}

/**
 * Reject anything that is not a scope-relative path. `null` when acceptable.
 *
 * Shared by the reader ([readFileScope], on an already-string-typed element) and the writer
 * ([buildFileScope]), exactly as [isUsableDiscriminator]-style sharing keeps D12's writer from
 * emitting a value its own reader would reject.
 */
private fun pathProblem(value: String): FileScopeProblem? {
    if (value.isEmpty()) return FileScopeProblem.PATH_EMPTY
    // Absolute first, so a Windows drive letter is diagnosed as what it is rather than as the
    // colon rule below.
    if (value.startsWith("/") || value.startsWith("\\")) return FileScopeProblem.PATH_ABSOLUTE
    if (Regex("^[A-Za-z]:([\\\\/].*)?$").matches(value)) return FileScopeProblem.PATH_ABSOLUTE
    if (value.contains(":")) return FileScopeProblem.PATH_HAS_COLON
    for (segment in value.split('/', '\\')) {
        if (segment == "..") return FileScopeProblem.PATH_ESCAPES_SCOPE
    }
    return null
}

/**
 * Read the effective resolved file set out of an untyped `session.start` `data` value.
 *
 * ## Why the LIST, and not the rule
 *
 * S25's problem is that "no events for this file" is ambiguous between _nothing happened_ and
 * _it was never watched_. A count cannot answer it, and the unresolved glob set would require
 * three hand-written recorder ports and one analyzer to agree on a matcher. The list is the
 * smallest thing that answers the question.
 *
 * ## `complete` is a claim, not a formatting detail
 *
 * A writer that caps the list emits `complete: false`, and a consumer must then read a path's
 * ABSENCE from `watched` as _unknown_ rather than as _not watched_. It is a required boolean
 * rather than an optional `truncated` flag because a consumer must never have to infer it.
 *
 * ## Why a bad element rejects the WHOLE set
 *
 * Dropping the offending entries would hand the consumer a silently NARROWED list, and a
 * narrowed list read as complete says "this file was not watched" about a file that was.
 * Rejecting outright costs only the information and cannot mislead.
 *
 * ## An EMPTY set is a real answer
 *
 * `{watched: [], complete: true}` is legal and meaningful: the scope resolved to nothing, so no
 * file was watched and every file's silence is explained. It is not absence and must not be
 * folded into it.
 */
fun readFileScope(data: JsonObject?): FileScopeRead {
    val raw = data?.get(FILE_SCOPE_FIELD) ?: return FileScopeRead.Absent
    if (raw is JsonNull) return FileScopeRead.Absent
    if (raw !is JsonObject) return FileScopeRead.Malformed(FileScopeProblem.NOT_AN_OBJECT)

    val watchedRaw = raw["watched"]
    if (watchedRaw !is JsonArray) return FileScopeRead.Malformed(FileScopeProblem.WATCHED_NOT_AN_ARRAY)

    val completeRaw = raw["complete"]
    if (completeRaw !is JsonPrimitive || completeRaw.isString ||
        (completeRaw.content != "true" && completeRaw.content != "false")
    ) {
        return FileScopeRead.Malformed(FileScopeProblem.COMPLETE_NOT_A_BOOLEAN)
    }
    val complete = completeRaw.content == "true"

    val watched = ArrayList<String>(watchedRaw.size)
    for (entry in watchedRaw) {
        if (entry !is JsonPrimitive || !entry.isString) {
            return FileScopeRead.Malformed(FileScopeProblem.PATH_NOT_A_STRING)
        }
        val problem = pathProblem(entry.content)
        if (problem != null) return FileScopeRead.Malformed(problem)
        watched.add(entry.content)
    }
    return FileScopeRead.Recorded(watched, complete)
}

// ---------------------------------------------------------------------------
// Writer helper
// ---------------------------------------------------------------------------

/**
 * Build a `file_scope` value for a writer.
 *
 * Exported so the recorders produce the identical shape, and so the `complete` claim is never
 * accidentally omitted. Returns `null` when there is nothing honest to report — a caller must
 * then OMIT the field (never assign `null`), mirroring [SessionFileScope]'s own optionality on
 * [SessionStartPayload].
 */
fun buildFileScope(watched: List<String>, complete: Boolean): SessionFileScope? {
    for (entry in watched) {
        if (pathProblem(entry) != null) return null
    }
    return SessionFileScope(watched.toList(), complete)
}

// ---------------------------------------------------------------------------
// The `file_scope` payload shape itself
// ---------------------------------------------------------------------------

/**
 * `session.start.file_scope` (collaboration spec §5.6 item 1): the effective resolved file set
 * this session actually watched.
 *
 * Not itself a top-level event payload — it is nested inside [SessionStartPayload] — so it lives
 * here rather than in [Events.kt], next to the reader that shape-checks it and the writer helper
 * ([buildFileScope]) that is the only supported way to construct one.
 */
data class SessionFileScope(val watched: List<String>, val complete: Boolean)

fun SessionFileScope.toJsonObject(): JsonObject = buildJsonObject {
    putJsonArray("watched") { watched.forEach { add(it) } }
    put("complete", complete)
}
