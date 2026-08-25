package dev.provenance.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * `peer.observed` — PEER WITNESSING (program spec §7 mechanism 2, collaboration spec §5.5,
 * Tier 4.1). Ported from log-core's `peer-observed.ts`.
 *
 * In a shared repository a `git pull` drops a partner's `.slog` into `.provenance/`. The
 * recorder notices, hashes it, reads its chain tip, and writes what it saw into **its own**
 * signed chain. Deleting a partner's log then leaves your own chain testifying that it
 * existed, and hiding that means destroying both chains — which yields a submission with no
 * provenance at all, the loudest possible signal.
 *
 * ## Why the narrowing lives in `core/` and not in the wiring
 *
 * `parseEntries` deliberately does not reject unknown `kind` values and does not look inside
 * `data` at all (PRD §5.1, forward compatibility). So every consumer of a `peer.observed`
 * event has to narrow it itself, and there are four of them — the monorepo's analyzer and
 * server through `analysis-core`, plus this repo and provnvim, which need the identical rules
 * to EMIT conformant payloads. One narrowing function, with vectors, is how the ports are kept
 * from diverging. The hash chain has exactly one implementation for the same reason.
 *
 * ## Why a malformed payload is REJECTED rather than partially read
 *
 * A witness is an assertion about a **different student's** artifact. A payload we cannot
 * fully narrow is an assertion we cannot evaluate, and the safe direction for an unevaluable
 * claim against a third party is to decline it — never to keep the fields that happened to
 * parse and reason from those. Half a witness could name a session and omit the chain
 * commitment that bounds what it proves, which is the shape most likely to be read as stronger
 * than it is.
 *
 * Rejection here is NOT a finding and NOT a load failure. It is one witness that cannot be
 * used, reported as such.
 *
 * ## Cross-field rule: parsed-ness is all-or-nothing
 *
 * [PeerObservedPayload.sessionId], [PeerObservedPayload.seqHigh] and
 * [PeerObservedPayload.lastHash] are the three values read out of the FOREIGN chain. Either
 * the recorder parsed that chain or it did not, so these are all non-null together or all null
 * together. A payload with some of them is self-contradictory — a witness claiming to know the
 * session but not the tip — and is rejected as [PeerObservedShapeError.PartiallyParsed].
 *
 * The one exception is not an exception at all: [PeerObservedState.UNPARSEABLE] REQUIRES all
 * three to be null, because that state is by definition the case where the foreign chain could
 * not be read.
 *
 * ## No identity, ever
 *
 * No student ref, no key, no git author, and no path outside `.provenance/`. A witness names a
 * FILE and a CHAIN POSITION. This payload is about somebody ELSE, so the CPHS constraint that
 * keeps author identity out of `git.event` applies here with more force.
 */

/**
 * The five observation states, in a fixed order matching log-core's `PEER_OBSERVED_STATES`.
 *
 * An unrecognised state is REJECTED. Unlike an unknown event KIND — which is forward
 * compatibility and must pass — an unknown state inside a payload we do understand would have
 * to be given a meaning, and inventing one is how a reader ends up treating an unfamiliar
 * observation as an accusation.
 *
 * **Descriptive, never a verdict.** [DISAPPEARED] is not misconduct: a `git checkout` of a
 * branch without the partner's `.slog`, or a `git stash`, removes it from the working tree.
 */
enum class PeerObservedState(val wire: String) {
    APPEARED("appeared"),
    GREW("grew"),
    SHRANK("shrank"),
    DISAPPEARED("disappeared"),
    UNPARSEABLE("unparseable"),
    ;

    companion object {
        /** The wire spellings, in order. A port asserts its own enum against this. */
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        fun fromWire(value: String): PeerObservedState? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One recorder's signed record of ANOTHER contributor's `.provenance/` log.
 *
 * [sha256] and [bytes] are corroborating detail; [seqHigh] + [lastHash] are the COMMITMENT.
 * `sha256` is deliberately not the corroboration test — a foreign log is append-only and its
 * owner keeps recording, so the bytes witnessed are normally a PREFIX of the bytes finally
 * committed and digest inequality is the NORMAL case.
 */
data class PeerObservedPayload(
    /** The foreign log's BASENAME. Never a path outside `.provenance/`. */
    val file: String,
    /** sha256, lowercase hex, over the file's exact bytes at observation time. */
    val sha256: String,
    /** That file's length in bytes. */
    val bytes: Long,
    /** The foreign chain's logical session id, or null when the chain was not read. */
    val sessionId: String?,
    /**
     * The foreign chain's highest `seq`, or null when the chain was not read.
     *
     * **`0` is a real value** — a foreign log holding only its `session.start`. A truthiness
     * check turns the shortest possible honest witness into a malformed one.
     */
    val seqHigh: Long?,
    /** The `hash` at [seqHigh], or null when the chain was not read. */
    val lastHash: String?,
    val state: PeerObservedState,
)

/**
 * Serialize to the wire shape.
 *
 * **The three nullable fields are emitted EXPLICITLY as `null`, never omitted.** An omitted key
 * and a `null` value canonicalize differently and therefore chain to different hashes, so a
 * port that omits them produces a log whose entries hash differently from every other
 * recorder's for the same observation.
 */
fun PeerObservedPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("file", file)
    put("sha256", sha256)
    put("bytes", bytes)
    // `put(key, null as String?)` writes JsonNull, which is exactly what is wanted here — the
    // opposite of GitEventPayload.rootCommitSha, where absence must be an absent KEY.
    put("session_id", sessionId)
    put("seq_high", seqHigh)
    put("last_hash", lastHash)
    put("state", state.wire)
}

/** Why a `peer.observed` payload could not be narrowed. */
sealed interface PeerObservedShapeError {
    /** Not a JSON object. */
    data object NotAnObject : PeerObservedShapeError

    /** A required field is absent or of the wrong type. */
    data class BadField(val field: String) : PeerObservedShapeError

    /** `state` is a string, but not one of [PeerObservedState]. */
    data class UnknownState(val state: String) : PeerObservedShapeError

    /**
     * The three foreign-chain fields are not all-null or all-non-null. A witness that knows
     * the session but not the tip commits to nothing checkable while looking as though it
     * does.
     */
    data class PartiallyParsed(
        val present: List<String>,
        val absent: List<String>,
    ) : PeerObservedShapeError

    /**
     * `state` is `unparseable` yet the payload carries foreign-chain values. The recorder
     * cannot both have failed to parse the file and have read its chain.
     */
    data class UnparseableWithChainValues(val present: List<String>) : PeerObservedShapeError

    /** `seq_high` is not a non-negative integer. */
    data class BadSeqHigh(val value: Double) : PeerObservedShapeError

    /** `bytes` is not a non-negative integer. */
    data class BadBytes(val value: Double) : PeerObservedShapeError

    /** The wire spelling of the variant, matching log-core's `kind` field. */
    val kind: String
        get() = when (this) {
            is NotAnObject -> "not_an_object"
            is BadField -> "bad_field"
            is UnknownState -> "unknown_state"
            is PartiallyParsed -> "partially_parsed"
            is UnparseableWithChainValues -> "unparseable_with_chain_values"
            is BadSeqHigh -> "bad_seq_high"
            is BadBytes -> "bad_bytes"
        }
}

/** The outcome of narrowing. A `Result`-shaped sealed interface, as `core/` spells them. */
sealed interface PeerObservedParse {
    data class Ok(val payload: PeerObservedPayload) : PeerObservedParse

    data class Err(val error: PeerObservedShapeError) : PeerObservedParse
}

private val PEER_OBSERVED_HEX_64 = Regex("^[0-9a-f]{64}$")

/** A JSON number that is a non-negative integer, or null. Rejects 1.5, -1, NaN and 1e30. */
private fun JsonPrimitive.nonNegativeIntegerOrNull(): Long? {
    val asLong = longOrNull ?: return null
    return if (asLong >= 0) asLong else null
}

/**
 * Narrow an untyped `peer.observed` `data` value to a [PeerObservedPayload].
 *
 * Pure and total: never throws, never mutates, and returns a result type because a malformed
 * payload is an EXPECTED input (the log is a student-editable file).
 *
 * Unknown extra keys are IGNORED, not rejected — the same forward-compatibility rule
 * [resolveCapturePolicy] applies to unknown capture keys. A future field added by a newer
 * recorder must not make this reader refuse the whole witness. (Note the extra key does change
 * the canonical bytes and therefore the chain hash: narrowing is not canonicalization.)
 */
fun validatePeerObservedPayload(value: JsonObject?): PeerObservedParse {
    if (value == null) return PeerObservedParse.Err(PeerObservedShapeError.NotAnObject)

    fun bad(field: String) = PeerObservedParse.Err(PeerObservedShapeError.BadField(field))

    val fileEl = value["file"]
    if (fileEl !is JsonPrimitive || !fileEl.isString || fileEl.content.isEmpty()) return bad("file")

    val shaEl = value["sha256"]
    if (shaEl !is JsonPrimitive || !shaEl.isString || !PEER_OBSERVED_HEX_64.matches(shaEl.content)) {
        return bad("sha256")
    }

    val bytesEl = value["bytes"]
    if (bytesEl !is JsonPrimitive || bytesEl.isString || bytesEl is JsonNull) return bad("bytes")
    val bytesNum = bytesEl.doubleOrNull ?: return bad("bytes")
    val bytes = bytesEl.nonNegativeIntegerOrNull()
        ?: return PeerObservedParse.Err(PeerObservedShapeError.BadBytes(bytesNum))

    val stateEl = value["state"]
    if (stateEl !is JsonPrimitive || !stateEl.isString) return bad("state")
    val state = PeerObservedState.fromWire(stateEl.content)
        ?: return PeerObservedParse.Err(PeerObservedShapeError.UnknownState(stateEl.content))

    val sessionIdEl = value["session_id"]
    val sessionId: String? = when {
        sessionIdEl == null || sessionIdEl is JsonNull -> null
        sessionIdEl is JsonPrimitive && sessionIdEl.isString -> sessionIdEl.content
        else -> return bad("session_id")
    }

    val seqHighEl = value["seq_high"]
    val seqHigh: Long? = when {
        seqHighEl == null || seqHighEl is JsonNull -> null
        seqHighEl is JsonPrimitive && !seqHighEl.isString -> {
            val asDouble = seqHighEl.doubleOrNull ?: return bad("seq_high")
            seqHighEl.nonNegativeIntegerOrNull()
                ?: return PeerObservedParse.Err(PeerObservedShapeError.BadSeqHigh(asDouble))
        }
        else -> return bad("seq_high")
    }

    val lastHashEl = value["last_hash"]
    val lastHash: String? = when {
        lastHashEl == null || lastHashEl is JsonNull -> null
        lastHashEl is JsonPrimitive && lastHashEl.isString &&
            PEER_OBSERVED_HEX_64.matches(lastHashEl.content) -> lastHashEl.content
        else -> return bad("last_hash")
    }

    // Cross-field: the three foreign-chain reads travel together. `seqHigh != null` and NOT a
    // truthiness test — `seq_high: 0` is the shortest honest witness there is.
    val chainFields = listOf(
        "session_id" to (sessionId != null),
        "seq_high" to (seqHigh != null),
        "last_hash" to (lastHash != null),
    )
    val present = chainFields.filter { it.second }.map { it.first }
    val absent = chainFields.filterNot { it.second }.map { it.first }

    if (state == PeerObservedState.UNPARSEABLE) {
        if (present.isNotEmpty()) {
            return PeerObservedParse.Err(
                PeerObservedShapeError.UnparseableWithChainValues(present),
            )
        }
    } else if (present.isNotEmpty() && absent.isNotEmpty()) {
        return PeerObservedParse.Err(PeerObservedShapeError.PartiallyParsed(present, absent))
    }

    return PeerObservedParse.Ok(
        PeerObservedPayload(
            file = fileEl.content,
            sha256 = shaEl.content,
            bytes = bytes,
            sessionId = sessionId,
            seqHigh = seqHigh,
            lastHash = lastHash,
            state = state,
        ),
    )
}
