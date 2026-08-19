package dev.provenance.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Capture policy — the professor-facing control over what a recorder records.
 * The Kotlin twin of log-core's `policy.ts`; program spec §4.
 *
 * The block lives INSIDE the course-signed manifest payload, which is the whole
 * point: **a professor can turn capture down, a student cannot turn it off.**
 *
 * ```jsonc
 * "policy": {
 *   "capture": {
 *     "selection_change":      true,
 *     "focus_change":          true,
 *     "terminal":              true,
 *     "doc_open_close":        true,
 *     "inline_content":        true,   // paste + fs.external_change content snippets
 *     "heartbeat_interval_ms": 30000   // clamped to [5000, 120000]
 *   }
 * }
 * ```
 *
 * ## Why this type lives in `core/` and not `recorder/`
 *
 * The terminal and git listeners are plugin-gated behind optional `<depends>`
 * declarations (Git4Idea, the terminal plugin) and are structured so their
 * classes are never loaded when the optional plugin is absent. A policy type
 * imported *there* from a main-path package would be reachable from both sides of
 * that boundary and risks exactly the `NoClassDefFoundError` those classes are
 * shaped to avoid. `core/` has no IntelliJ Platform dependency at all, so it is
 * safe from every gated call site — and the policy is part of the signed format
 * contract anyway, which is what `core/` owns.
 *
 * ## The hard floor
 *
 * Most event kinds cannot be disabled at all, because validation checks 3–8 and
 * the integrity story depend on them. The floor is enforced **by the schema
 * itself**: a floor event simply has no key in `policy.capture`, so there is no
 * way to express "off" for it. [FLOOR_EVENT_KINDS] is that set written out so an
 * implementation can assert it, and [POLICY_GATED_EVENT_KINDS] is its complement —
 * the only kinds a policy can reach.
 *
 * ## The absence-vs-disabled rule
 *
 * The effective policy MUST travel into the bundle (it does, inside the manifest
 * carried by `session.start`). Without it the analyzer cannot tell "this student
 * produced no `selection.change` events" from "this course disabled
 * `selection.change`", and heuristics mis-fire on the difference.
 *
 * ## Permanent constraint: no user-derived object keys
 *
 * Every key in `policy.capture` is a fixed ASCII identifier chosen by us, and
 * every future addition must be too. See `CourseCert.kt` for the full statement of
 * the rule.
 */
data class CapturePolicy(
    /** Capture `selection.change` events. */
    val selectionChange: Boolean,
    /** Capture `focus.change` events. */
    val focusChange: Boolean,
    /** Capture `terminal.open` and `terminal.command` events. */
    val terminal: Boolean,
    /** Capture `doc.open` and `doc.close` events. */
    val docOpenClose: Boolean,
    /**
     * Inline content snippets in `paste` and `fs.external_change` payloads.
     * Not an event gate — the events themselves are on the floor; this controls
     * whether their `content` / `new_content` fields are populated.
     */
    val inlineContent: Boolean,
    /** Heartbeat cadence in milliseconds, always within the clamp range. */
    val heartbeatIntervalMs: Long,
)

/**
 * Applied when the manifest carries no `policy` block at all, and per-key when a
 * key is absent or malformed. Everything on, 30s heartbeat — i.e. exactly the
 * v1.x recorder behaviour, so a 1.x manifest resolves to today's capture set.
 */
val DEFAULT_CAPTURE_POLICY = CapturePolicy(
    selectionChange = true,
    focusChange = true,
    terminal = true,
    docOpenClose = true,
    inlineContent = true,
    heartbeatIntervalMs = 30_000L,
)

/** Inclusive lower clamp for `heartbeat_interval_ms` (program spec §4). */
const val HEARTBEAT_INTERVAL_MIN_MS: Long = 5_000L

/** Inclusive upper clamp for `heartbeat_interval_ms` (program spec §4). */
const val HEARTBEAT_INTERVAL_MAX_MS: Long = 120_000L

/**
 * Event kinds that can NEVER be disabled, because validation checks 3–8 and the
 * integrity story depend on them.
 *
 * These have no key in `policy.capture` **by design** — the schema is the
 * enforcement mechanism, this list is only the assertable statement of it. Do not
 * add a `policy.capture` key for anything on this list.
 *
 * `session.heartbeat` is here because bundle-level Active/Idle and the
 * `gap_in_heartbeats` heuristic depend on it; only its *interval* is tunable.
 * `paste.anomaly` is on the floor by the schema rule (it has no `policy.capture`
 * key) even though program spec §4's prose list omits it — it is a paste-integrity
 * signal, so floor is the correct and safe reading.
 */
val FLOOR_EVENT_KINDS: List<String> = listOf(
    "session.start",
    "session.end",
    "session.resumed",
    "session.heartbeat",
    "doc.change",
    "doc.save",
    "paste",
    "paste.anomaly",
    "fs.external_change",
    "git.event",
    "clock.skew",
    "chain.broken",
    "ext.snapshot",
    "ext.activate",
    "recorder.degraded",
    "recorder.recovered_from_corruption",
)

/**
 * The complement of [FLOOR_EVENT_KINDS]: every event kind a policy can switch off,
 * mapped to the boolean `policy.capture` key that switches it.
 *
 * log-core asserts at compile time that `FLOOR_EVENT_KINDS ∪ keys(...)` is exactly
 * its `EventKind` union. This port has no `EventKind` enum — kinds are plain
 * strings here — so that particular assertion is not expressible; the conformance
 * vector pins both lists instead.
 */
val POLICY_GATED_EVENT_KINDS: Map<String, String> = mapOf(
    "doc.open" to "doc_open_close",
    "doc.close" to "doc_open_close",
    "selection.change" to "selection_change",
    "focus.change" to "focus_change",
    "terminal.open" to "terminal",
    "terminal.command" to "terminal",
)

private fun resolveBool(value: JsonElement?, fallback: Boolean): Boolean {
    val p = value as? JsonPrimitive ?: return fallback
    if (p.isString) return fallback
    return when (p.content) {
        "true" -> true
        "false" -> false
        else -> fallback
    }
}

/**
 * Clamp `heartbeat_interval_ms` into `[HEARTBEAT_INTERVAL_MIN_MS,
 * HEARTBEAT_INTERVAL_MAX_MS]`.
 *
 * A non-number, `NaN`, or non-finite value falls back to the DEFAULT rather than
 * clamping — clamping a non-number is meaningless, and a course that wrote garbage
 * here should get the safe cadence, not the floor.
 */
private fun resolveHeartbeatInterval(value: JsonElement?): Long {
    val p = value as? JsonPrimitive ?: return DEFAULT_CAPTURE_POLICY.heartbeatIntervalMs
    if (p.isString) return DEFAULT_CAPTURE_POLICY.heartbeatIntervalMs
    val d = p.content.toDoubleOrNull() ?: return DEFAULT_CAPTURE_POLICY.heartbeatIntervalMs
    if (!d.isFinite()) return DEFAULT_CAPTURE_POLICY.heartbeatIntervalMs
    if (d < HEARTBEAT_INTERVAL_MIN_MS) return HEARTBEAT_INTERVAL_MIN_MS
    if (d > HEARTBEAT_INTERVAL_MAX_MS) return HEARTBEAT_INTERVAL_MAX_MS
    // log-core carries the raw JS number through; a millisecond cadence is an
    // integer everywhere it is used, so this port truncates a fractional value
    // rather than widening the field to a Double.
    return d.toLong()
}

/**
 * Resolve a manifest `policy` block into the effective [CapturePolicy].
 *
 * Total by construction: any absent, malformed, or out-of-range input resolves to
 * a well-defined value, so this never fails and returns no error type. A missing
 * block (a 1.x manifest, or a 2.0 manifest whose course specified nothing)
 * resolves to [DEFAULT_CAPTURE_POLICY].
 *
 * Takes a [JsonElement] so it can be applied directly to untrusted parsed JSON.
 */
fun resolveCapturePolicy(block: JsonElement?): CapturePolicy {
    val obj = block as? JsonObject ?: return DEFAULT_CAPTURE_POLICY
    val capture = obj["capture"] as? JsonObject ?: return DEFAULT_CAPTURE_POLICY

    return CapturePolicy(
        selectionChange = resolveBool(capture["selection_change"], DEFAULT_CAPTURE_POLICY.selectionChange),
        focusChange = resolveBool(capture["focus_change"], DEFAULT_CAPTURE_POLICY.focusChange),
        terminal = resolveBool(capture["terminal"], DEFAULT_CAPTURE_POLICY.terminal),
        docOpenClose = resolveBool(capture["doc_open_close"], DEFAULT_CAPTURE_POLICY.docOpenClose),
        inlineContent = resolveBool(capture["inline_content"], DEFAULT_CAPTURE_POLICY.inlineContent),
        heartbeatIntervalMs = resolveHeartbeatInterval(capture["heartbeat_interval_ms"]),
    )
}

/**
 * Is [kind] captured under [policy]?
 *
 * Floor kinds always return `true` — there is no key that could turn them off.
 */
fun isEventKindCaptured(kind: String, policy: CapturePolicy): Boolean =
    when (POLICY_GATED_EVENT_KINDS[kind]) {
        null -> true
        "doc_open_close" -> policy.docOpenClose
        "selection_change" -> policy.selectionChange
        "focus_change" -> policy.focusChange
        "terminal" -> policy.terminal
        "inline_content" -> policy.inlineContent
        else -> true
    }
