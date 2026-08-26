package dev.provenance.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Enrollment policy — the professor-facing switch for whether the recorder tells an
 * un-enrolled student so. The Kotlin twin of log-core's `policy.ts` `enrollment`
 * addition; keep the two in step.
 *
 * ```jsonc
 * "policy": {
 *   "enrollment": { "required": false }
 * }
 * ```
 *
 * ## What this does NOT do
 *
 * A student who never enrolls still records a perfectly good bundle: the event
 * stream, the hash chain, and the seal are all unaffected. What they lose is
 * ATTRIBUTION — nothing in the bundle says who produced it — which only matters for
 * group work. This flag changes NOTHING about that. It is purely cosmetic: it
 * silences the "(not enrolled)" status-bar suffix and the enrollment nudge
 * notification for courses that have decided their students' un-enrolled state is
 * not worth surfacing (program spec: large first-week courses where the prompt
 * panics novices more than the missing attribution is worth). A student who DOES
 * hold a credential is completely unaffected either way — their session still
 * emits `session.start.identity` exactly as before. See
 * `dev.provenance.recorder.identity.EnrollNudge` for the student-facing half.
 *
 * ## Why the default is `required: true`
 *
 * Absent `policy`, absent `enrollment`, absent `required`, or ANY malformed value —
 * a string, a number, `null` — all resolve to `true`, i.e. exactly today's
 * behaviour. Every manifest signed before this flag existed, and every manifest
 * that gets the key wrong, keeps nudging. A course opts OUT; it can never
 * accidentally opt in to silence by getting the JSON shape wrong.
 *
 * ## Why this lives inside `policy`, not as a new top-level manifest field
 *
 * `policy` is the one signed manifest field [Manifest.toJsonObject] and
 * [buildSignedPayload] pass through VERBATIM as a [JsonObject] — see
 * `m.policy?.let { put("policy", it) }` in the latter. A new top-level field would
 * have to be added to every recorder's key-enumerating signed-payload builder at
 * once, in lockstep, or cross-recorder signature verification breaks; riding inside
 * `policy` needs no such coordination. See [Manifest]'s KDoc for the full
 * constraint, including the permanent "no user-derived object keys" rule: `enrollment`
 * and `required` are both fixed ASCII identifiers we chose, never anything
 * course-supplied.
 *
 * ## Only honoured at Manifest 2.0
 *
 * A 1.x manifest's `policy` block, if one is even present on the parsed object, is
 * NOT part of the signed payload — see [Manifest.policy]'s KDoc. Reading it here
 * would hand a student the same off switch the trust chain's step 0
 * ([ManifestChain.NotManifest20]) exists to deny them for capture policy. This type
 * and [resolveEnrollmentPolicy] do not gate on format version themselves — a bare
 * [JsonElement] carries no version context — so callers MUST route through
 * `dev.provenance.recorder.activation.resolveVerifiedEnrollmentPolicy`, which does.
 */
data class EnrollmentPolicy(
    /** Whether an un-enrolled student should be told so (status bar suffix + nudge). */
    val required: Boolean,
)

/**
 * Applied when the manifest carries no `policy` block, no `enrollment` key, or a
 * malformed value for `required` — i.e. today's behaviour: enrollment is required
 * and an un-enrolled student is nudged.
 */
val DEFAULT_ENROLLMENT_POLICY: EnrollmentPolicy = EnrollmentPolicy(required = true)

/**
 * Resolve a manifest `policy` block into the effective [EnrollmentPolicy].
 *
 * Total by construction: any absent or malformed input resolves to
 * [DEFAULT_ENROLLMENT_POLICY], so this never fails. Takes the whole `policy` block,
 * exactly like [resolveCapturePolicy], so both resolvers read the same untyped JSON
 * with the same shape of fallback and neither one throws on hostile input.
 *
 * Does NOT gate on manifest format version — see this file's module KDoc. Never
 * call this directly on an unverified manifest's `policy`; go through
 * `resolveVerifiedEnrollmentPolicy`.
 */
fun resolveEnrollmentPolicy(block: JsonElement?): EnrollmentPolicy {
    val obj = block as? JsonObject ?: return DEFAULT_ENROLLMENT_POLICY
    val enrollment = obj["enrollment"] as? JsonObject ?: return DEFAULT_ENROLLMENT_POLICY
    return EnrollmentPolicy(
        required = resolveEnrollmentBool(enrollment["required"], DEFAULT_ENROLLMENT_POLICY.required),
    )
}

/**
 * File-local twin of [resolveBool] in `CapturePolicy.kt` (top-level `private` is
 * file-scoped in Kotlin, so this does not collide with it). A non-boolean
 * primitive — a string, a number, JSON `null` — falls back rather than throwing or
 * guessing, matching every other policy-value resolver in this port.
 */
private fun resolveEnrollmentBool(value: JsonElement?, fallback: Boolean): Boolean {
    val p = value as? JsonPrimitive ?: return fallback
    if (p.isString) return fallback
    return when (p.content) {
        "true" -> true
        "false" -> false
        else -> fallback
    }
}
