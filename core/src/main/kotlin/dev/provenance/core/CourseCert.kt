package dev.provenance.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Course certificate — the middle link of the Manifest 2.0 trust chain.
 * The Kotlin twin of log-core's `course-cert.ts`; program spec §2, §3.
 *
 * ```
 *   root keypair (Provenance maintainer, offline; NEVER signs a manifest)
 *        │ signs
 *        ▼
 *   course_cert { course_id, course_pubkey, valid_from, valid_until }
 *        │ authorizes
 *        ▼
 *   course keypair (course staff; signs `.provenance-manifest` files)
 * ```
 *
 * `core/` stays pure: **the root public key is never a constant in this module.**
 * It is embedded by the plugin build and passed in as a parameter to
 * [verifyCourseCert]. A hardcoded key here would make one build serve exactly one
 * deployment, which is the thing this design exists to remove.
 *
 * The signed payload is the certificate MINUS `root_sig`:
 *
 *   JCS({course_id, course_pubkey, valid_from, valid_until}) → UTF-8 → ed25519
 *
 * Revocation is deliberately NOT modelled here. An offline recorder cannot learn
 * that a key was revoked without a network call, which recorder PRD NG2 forbids.
 * Revocation is a server-side list checked by the analyzer, and it must key on
 * `course_pubkey` rather than on certificate identity: the certificate travels
 * outside the course-signed payload by design, so the student chooses which of
 * the course's certs to ship. The offline mitigation is short validity windows
 * (one semester per cert) — a real, accepted limitation (program spec §2).
 *
 * ## Permanent constraint: no user-derived object keys in a signed payload
 *
 * Every object KEY that ends up inside a canonicalized, signed payload — here, in
 * the manifest, and in the capture-policy block — MUST be a fixed ASCII
 * identifier chosen by us. Never a course id, student ref, filename, or any other
 * user-supplied string promoted to a key.
 *
 * The reason is cross-language canonicalization parity. provnvim's JCS is
 * hand-rolled in Lua and sorts object keys BYTEWISE; the JS (`canonicalize`) and
 * Kotlin (`java-json-canonicalization`) implementations sort by UTF-16 code unit.
 * Those two orderings agree for ASCII keys and can diverge above U+007F —
 * silently, producing different signed bytes and breaking signature
 * cross-verification between recorders. Values are unconstrained; only keys carry
 * this rule.
 */
data class CourseCert(
    /** Must equal the enclosing manifest's `course_id` (program spec §3 step 3). */
    val courseId: String,
    /** Hex ed25519 public key of the course signing key, 64 chars (32 bytes). */
    val coursePubkey: String,
    /** Inclusive lower bound of the validity window. ISO 8601 date or timestamp. */
    val validFrom: String,
    /** Inclusive upper bound of the validity window. ISO 8601 date or timestamp. */
    val validUntil: String,
    /** Hex ed25519 signature by the ROOT key, 128 chars (64 bytes). */
    val rootSig: String,
)

sealed interface CourseCertParse {
    data class Ok(val cert: CourseCert) : CourseCertParse

    data class Err(val reason: String) : CourseCertParse
}

/** Why an `issued_at` fell outside a certificate's window. Wire names match log-core. */
enum class CertWindowReason(val wire: String) {
    BEFORE_VALID_FROM("before_valid_from"),
    AFTER_VALID_UNTIL("after_valid_until"),
    UNPARSEABLE_TIMESTAMP("unparseable_timestamp"),
}

/**
 * Outcome of the validity-window check (program spec §3 step 4).
 *
 * Evaluated against `manifest.issued_at`, NEVER against wall-clock now: a Fall
 * 2026 bundle must still verify in 2028 for an adjudication case. The question is
 * always "was the cert valid when the manifest was issued".
 *
 * Being out of window is NOT fatal and does not invalidate a signature — the
 * caller decides what to do with it (program spec §4: an expired cert must not
 * stop a recorder from recording).
 */
sealed interface CertWindowStatus {
    val inWindow: Boolean

    data object InWindow : CertWindowStatus {
        override val inWindow: Boolean get() = true
    }

    data class OutOfWindow(val reason: CertWindowReason) : CertWindowStatus {
        override val inWindow: Boolean get() = false
    }
}

private val CERT_HEX_128_RE = Regex("^[0-9a-f]{128}$")
private val CERT_HEX_64_RE = Regex("^[0-9a-f]{64}$")

/**
 * Strict ISO 8601 / RFC 3339 grammar accepted by [parseIsoInstantMs].
 *
 * Spelled out as an explicit grammar rather than delegated to `java.time` so that
 * the TypeScript, Kotlin, and Lua ports implement the *same* accepting set.
 * `DateTimeFormatter.ISO_DATE_TIME` accepts a superset (leap seconds, `24:00:00`)
 * that `Date.parse` does not, and Lua has no date parser at all.
 * Groups: y, m, d, [hh, mm, ss, [frac], [offset]].
 */
private val ISO_INSTANT_RE =
    Regex("^(\\d{4})-(\\d{2})-(\\d{2})(?:T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:\\d{2})?)?$")

private const val MS_PER_DAY = 86_400_000L

/**
 * Parse an ISO 8601 timestamp to epoch milliseconds, or return `null` if it does
 * not match [ISO_INSTANT_RE]. The exact accepting set is pinned by the
 * `timestamp_parse_cases` conformance vector.
 *
 * Deliberate rules, all shared with log-core:
 *
 *  - A **date-only** string (`YYYY-MM-DD`) is the instant of UTC midnight that
 *    day. So `valid_until: "2027-01-15"` expires at `2027-01-15T00:00:00Z`, not
 *    at the end of that day. Harmless, because being out of window is non-fatal.
 *  - A timestamp with **no offset suffix** is treated as UTC.
 *  - `second > 59` rejects leap seconds (`:60`) and `hour > 23` rejects the
 *    legal-ISO `24:00:00`. `java.time` accepts both (normalizing the latter to
 *    next-day midnight) and JS `Date` accepts neither; rejecting here is what
 *    keeps the three ports' accepting sets identical.
 *  - A non-existent calendar date (`2026-02-31`, `2027-02-29`) is REJECTED, not
 *    rolled forward. JS `Date` would roll it to `2026-03-03`; `LocalDate.of`
 *    throws. Both normalize to `null` here.
 */
fun parseIsoInstantMs(value: String): Long? {
    val m = ISO_INSTANT_RE.matchEntire(value) ?: return null
    val g = m.groupValues

    val year = g[1].toInt()
    val month = g[2].toInt()
    val day = g[3].toInt()
    val hour = if (g[4].isEmpty()) 0 else g[4].toInt()
    val minute = if (g[5].isEmpty()) 0 else g[5].toInt()
    val second = if (g[6].isEmpty()) 0 else g[6].toInt()
    // Fractional seconds: pad/truncate to exactly 3 digits (milliseconds), the
    // same rule log-core uses (`padEnd(3, '0').slice(0, 3)`).
    val millis = if (g[7].isEmpty()) 0 else g[7].padEnd(3, '0').substring(0, 3).toInt()

    if (month < 1 || month > 12) return null
    if (day < 1 || day > 31) return null
    if (hour > 23 || minute > 59 || second > 59) return null

    val epochDay =
        try {
            LocalDate.of(year, month, day).toEpochDay()
        } catch (_: DateTimeException) {
            return null
        }

    var ms = epochDay * MS_PER_DAY +
        hour * 3_600_000L +
        minute * 60_000L +
        second * 1_000L +
        millis

    val offset = g[8]
    if (offset.isNotEmpty() && offset != "Z") {
        val sign = if (offset.startsWith("-")) 1L else -1L
        val offHours = offset.substring(1, 3).toInt()
        val offMinutes = offset.substring(4, 6).toInt()
        if (offHours > 23 || offMinutes > 59) return null
        ms += sign * (offHours * 60L + offMinutes) * 60_000L
    }

    return ms
}

/**
 * Build the canonical UTF-8 bytes the ROOT key signs. `root_sig` is excluded; the
 * four remaining fields are canonicalized (JCS orders keys, so the literal order
 * below is irrelevant).
 *
 * Takes the whole [CourseCert] and ignores `rootSig` — Kotlin has no structural
 * `Omit<T, K>`, and a second five-field parameter list would be a worse trade.
 */
fun buildCourseCertSignedPayload(cert: CourseCert): ByteArray {
    val payload = buildJsonObject {
        put("course_id", cert.courseId)
        put("course_pubkey", cert.coursePubkey)
        put("valid_from", cert.validFrom)
        put("valid_until", cert.validUntil)
    }.toString()
    return Canonical.canonicalize(payload).toByteArray(Charsets.UTF_8)
}

/**
 * Validate the shape of an already-JSON-parsed `course_cert` value.
 *
 * Takes a [JsonElement] rather than text because the certificate travels
 * **inline** inside `.provenance-manifest` (program spec §2): one file to
 * discover, one to distribute, and no chance of the two being separated by a copy
 * or a `.gitignore`.
 *
 * Unknown keys are ignored for forward compatibility. That is safe:
 * canonicalization operates on the four named fields only, so an unknown key
 * cannot silently change the signed bytes.
 *
 * The validity bounds must actually parse. Program spec §2 names short validity
 * windows as THE mitigation for having no offline revocation, so a bound that
 * silently never binds undercuts the only offline control there is. Certificates
 * are new in 2.0, so there is no archived-manifest compatibility cost to
 * enforcing this — unlike `manifest.issued_at`, which stays lenient because 1.x
 * manifests in the wild predate any such rule.
 */
fun parseCourseCert(value: JsonElement?): CourseCertParse {
    val obj = value as? JsonObject
        ?: return CourseCertParse.Err("invalid_shape: course_cert must be an object")

    val courseId = obj.certNonEmptyString("course_id")
        ?: return CourseCertParse.Err("invalid_shape: course_id must be a non-empty string")

    val bounds = LongArray(2)
    for ((i, field) in listOf("valid_from", "valid_until").withIndex()) {
        val raw = obj.certNonEmptyString(field)
            ?: return CourseCertParse.Err("invalid_shape: $field must be a non-empty string")
        val ms = parseIsoInstantMs(raw)
            ?: return CourseCertParse.Err("invalid_shape: $field must be an ISO 8601 date or timestamp")
        bounds[i] = ms
    }
    if (bounds[0] > bounds[1]) {
        return CourseCertParse.Err("invalid_shape: valid_until must not be earlier than valid_from")
    }

    val coursePubkey = obj.certNonEmptyString("course_pubkey")
    if (coursePubkey == null || !CERT_HEX_64_RE.matches(coursePubkey)) {
        return CourseCertParse.Err("invalid_shape: course_pubkey must be a 64-char hex string")
    }

    if (obj["root_sig"] == null) {
        return CourseCertParse.Err("invalid_shape: root_sig missing")
    }
    val rootSig = obj.certNonEmptyString("root_sig")
    if (rootSig == null || !CERT_HEX_128_RE.matches(rootSig)) {
        return CourseCertParse.Err("invalid_shape: root_sig must be a 128-char hex string")
    }

    return CourseCertParse.Ok(
        CourseCert(
            courseId = courseId,
            coursePubkey = coursePubkey,
            validFrom = obj.certNonEmptyString("valid_from")!!,
            validUntil = obj.certNonEmptyString("valid_until")!!,
            rootSig = rootSig,
        ),
    )
}

/**
 * Sign a course certificate with the ROOT private key (the inverse of
 * [verifyCourseCert]). Used by maintainer tooling and by tests; never by a
 * recorder — the root private key never touches a student machine.
 */
fun signCourseCert(cert: CourseCert, rootPrivkey32: ByteArray): String =
    Ed25519.bytesToHex(Ed25519.sign(buildCourseCertSignedPayload(cert), rootPrivkey32))

/**
 * Step 1 of the Manifest 2.0 verification order: verify `course_cert` minus
 * `root_sig` against the embedded root public key. Returns false (never throws)
 * on any malformed input.
 *
 * @param rootPubkeyHex Hex ed25519 root public key (64 chars). A PARAMETER, not a
 *                      constant — see the module docstring.
 */
fun verifyCourseCert(cert: CourseCert, rootPubkeyHex: String): Boolean {
    if (!CERT_HEX_64_RE.matches(rootPubkeyHex)) return false
    if (!CERT_HEX_128_RE.matches(cert.rootSig)) return false
    return try {
        Ed25519.verify(
            Ed25519.hexToBytes(cert.rootSig),
            buildCourseCertSignedPayload(cert),
            Ed25519.hexToBytes(rootPubkeyHex),
        )
    } catch (_: Exception) {
        false
    }
}

/**
 * Step 4 of the Manifest 2.0 verification order: is [issuedAt] inside
 * `[valid_from, valid_until]` (both bounds inclusive)?
 *
 * [issuedAt] is the manifest's `issued_at`. **Wall-clock now is never consulted** —
 * a cert that lapsed decades ago still reports `in_window` for a manifest issued
 * while it was live, which is the whole point for an adjudication years later.
 */
fun checkCertWindow(cert: CourseCert, issuedAt: String): CertWindowStatus {
    val from = parseIsoInstantMs(cert.validFrom)
    val until = parseIsoInstantMs(cert.validUntil)
    val issued = parseIsoInstantMs(issuedAt)

    if (from == null || until == null || issued == null) {
        return CertWindowStatus.OutOfWindow(CertWindowReason.UNPARSEABLE_TIMESTAMP)
    }
    if (issued < from) return CertWindowStatus.OutOfWindow(CertWindowReason.BEFORE_VALID_FROM)
    if (issued > until) return CertWindowStatus.OutOfWindow(CertWindowReason.AFTER_VALID_UNTIL)
    return CertWindowStatus.InWindow
}

private fun JsonObject.certNonEmptyString(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (!p.isString) return null
    return p.content.ifEmpty { null }
}

/**
 * Serialize a certificate back to its on-wire JSON shape.
 *
 * Needed because `session.start` 2.0 carries the FULL manifest — cert included —
 * into the bundle (program spec §5), so the analyzer can walk root → course →
 * manifest → session entirely offline. Emits all five fields, `root_sig` included:
 * unlike [buildCourseCertSignedPayload] this is transport, not the signed payload.
 */
fun CourseCert.toJsonObject(): JsonObject = buildJsonObject {
    put("course_id", courseId)
    put("course_pubkey", coursePubkey)
    put("valid_from", validFrom)
    put("valid_until", validUntil)
    put("root_sig", rootSig)
}
