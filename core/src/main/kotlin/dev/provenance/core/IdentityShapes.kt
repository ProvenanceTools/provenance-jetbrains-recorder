package dev.provenance.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shape-validation and window primitives shared by the two identity families.
 * The Kotlin twin of log-core's `identity-shapes.ts`.
 *
 * INTERNAL to `core/`. It exists because `Enrollment.kt` (the legacy course-scoped
 * chain) and `Institution.kt` (the institution-scoped chain that replaces it) must
 * validate their artifacts with byte-identical rules. Two copies of "is this an ISO
 * 8601 bound" is exactly how two implementations of one rule drift apart, and this
 * repo is already a SECOND implementation of a contract owned elsewhere — a third
 * divergence inside it would be inexcusable.
 *
 * Nothing here does signature work except [verifyDetached]; everything else runs
 * BEFORE signature work, for the reason spelled out in both callers: JCS OMITS keys
 * whose value is absent, so an artifact missing a required field would otherwise
 * sign and verify perfectly while carrying nothing at that field.
 */

/** 64-byte ed25519 signature, lowercase hex. */
internal val IDENTITY_HEX_128_RE = Regex("^[0-9a-f]{128}$")

/** 32-byte ed25519 public key or seed, lowercase hex. */
internal val IDENTITY_HEX_64_RE = Regex("^[0-9a-f]{64}$")

// ---------------------------------------------------------------------------
// The two wire slots, as types
// ---------------------------------------------------------------------------

/**
 * The `enrollment_cert` wire slot: whichever artifact authorizes the key that signed
 * the credential.
 *
 * **This interface is the version DISCRIMINATOR's home.** `formatVersion` is inside
 * the signed payload of both implementations and sits at the same wire key in both,
 * so [verifyIdentityChain] can read it before knowing which shape it holds — and
 * routing on it, rather than on which fields happen to be present, is the whole
 * point. Presence is attacker-controlled and ambiguous; a signed version is neither.
 *
 *  - [EnrollmentCert]  — 2.0, COURSE-signed. Legacy; supported forever.
 *  - [InstitutionCert] — 2.1, ROOT-signed. Current.
 */
sealed interface IdentityCert {
    /** The SIGNED identity format version. `"2.0"` or `"2.1"`. */
    val formatVersion: String
}

/**
 * The `enrollment` wire slot: the credential itself.
 *
 *  - [EnrollmentToken]   — 2.0, enrollment-key-signed, names a course.
 *  - [StudentCredential] — 2.1, institution-key-signed, names an institution.
 */
sealed interface IdentityCredential {
    /** The SIGNED identity format version. `"2.0"` or `"2.1"`. */
    val formatVersion: String
}

/**
 * Transport form of whichever cert is in the slot.
 *
 * A free function rather than a member on [IdentityCert] deliberately: a member
 * would shadow the per-type `toJsonObject()` extensions that the rest of the module
 * (and the recorder) already call, and a silently shadowed serializer on a signed
 * artifact is not a hazard worth inviting for the sake of one dot.
 */
fun identityCertJson(cert: IdentityCert): JsonObject = when (cert) {
    is EnrollmentCert -> cert.toJsonObject()
    is InstitutionCert -> cert.toJsonObject()
}

/** Transport form of whichever credential is in the slot. See [identityCertJson]. */
fun identityCredentialJson(credential: IdentityCredential): JsonObject = when (credential) {
    is EnrollmentToken -> credential.toJsonObject()
    is StudentCredential -> credential.toJsonObject()
}

// ---------------------------------------------------------------------------
// Shape primitives
// ---------------------------------------------------------------------------

/**
 * A required non-empty string field.
 *
 * A missing key and a JSON-null-valued key are treated identically — JCS erases the
 * difference, so nothing downstream can rely on it.
 */
internal fun JsonObject.requireString(field: String): String? {
    val p = this[field] as? JsonPrimitive ?: return null
    if (!p.isString) return null
    return p.content.ifEmpty { null }
}

/**
 * Validate an ordered pair of ISO 8601 bounds.
 *
 * Both bounds MUST parse. Short validity windows are the only offline mitigation
 * either identity scheme has for the absence of revocation, so a bound that silently
 * never binds would undercut the sole control there is. These artifacts are new, so
 * unlike `manifest.issued_at` there is no archived-data compatibility cost to
 * enforcing it.
 *
 * Returns the two raw strings, or an error reason.
 */
internal fun JsonObject.requireOrderedBounds(
    lowerField: String,
    upperField: String,
): EnrollmentParse<Pair<String, String>> {
    val values = mutableListOf<String>()
    val instants = mutableListOf<Long>()
    for (field in listOf(lowerField, upperField)) {
        val raw = requireString(field)
            ?: return EnrollmentParse.Err("invalid_shape: $field must be a non-empty string")
        val ms = parseIsoInstantMs(raw)
            ?: return EnrollmentParse.Err("invalid_shape: $field must be an ISO 8601 date or timestamp")
        values += raw
        instants += ms
    }
    if (instants[0] > instants[1]) {
        return EnrollmentParse.Err("invalid_shape: $upperField must not be earlier than $lowerField")
    }
    return EnrollmentParse.Ok(values[0] to values[1])
}

/**
 * A required lowercase-hex field of an exact length.
 *
 * Returns the value, or null when absent, non-string, or not hex of that length.
 */
internal fun JsonObject.requireHex(field: String, re: Regex): String? =
    requireString(field)?.takeIf { re.matches(it) }

// ---------------------------------------------------------------------------
// Window arithmetic
// ---------------------------------------------------------------------------

/**
 * Shared window arithmetic: is [at] inside `[lower, upper]`?
 *
 * [lower] is inclusive from its first instant; a date-only [upper] is inclusive
 * through the END of that day, via [resolveValidUntilExclusiveMs]. Identical
 * semantics to [checkCertWindow], so a port implements the rule once.
 *
 * [at] is always a RELEVANT ISSUE TIME, never wall-clock now — see the callers.
 */
internal fun checkWindow(lower: String, upper: String, at: String): CertWindowStatus {
    val from = parseIsoInstantMs(lower)
    val untilExclusive = resolveValidUntilExclusiveMs(upper)
    val instant = parseIsoInstantMs(at)

    if (from == null || untilExclusive == null || instant == null) {
        return CertWindowStatus.OutOfWindow(CertWindowReason.UNPARSEABLE_TIMESTAMP)
    }
    if (instant < from) return CertWindowStatus.OutOfWindow(CertWindowReason.BEFORE_VALID_FROM)
    if (instant >= untilExclusive) return CertWindowStatus.OutOfWindow(CertWindowReason.AFTER_VALID_UNTIL)
    return CertWindowStatus.InWindow
}

// ---------------------------------------------------------------------------
// Signature primitive
// ---------------------------------------------------------------------------

/**
 * Shared ed25519 verification. Every malformed input is a verification FAILURE
 * rather than an exception: these are values arriving from a student-editable file,
 * so a bad hex string is an expected condition, not a bug.
 */
internal fun verifyDetached(payload: ByteArray, sigHex: String, pubkeyHex: String): Boolean {
    if (!IDENTITY_HEX_128_RE.matches(sigHex) || !IDENTITY_HEX_64_RE.matches(pubkeyHex)) return false
    return try {
        Ed25519.verify(Ed25519.hexToBytes(sigHex), payload, Ed25519.hexToBytes(pubkeyHex))
    } catch (_: Exception) {
        false
    }
}
