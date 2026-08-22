package dev.provenance.recorder.identity

import dev.provenance.core.ENROLLMENT_FORMAT_VERSION
import dev.provenance.core.INSTITUTION_IDENTITY_FORMAT_VERSION
import dev.provenance.core.EnrollmentCert
import dev.provenance.core.EnrollmentParse
import dev.provenance.core.EnrollmentToken
import dev.provenance.core.InstitutionCert
import dev.provenance.core.STUDENT_MASTER_SECRET_BYTES
import dev.provenance.core.Ed25519
import dev.provenance.core.StudentCredential
import dev.provenance.core.generateStudentMasterSecret
import dev.provenance.core.parseEnrollmentCert
import dev.provenance.core.parseEnrollmentToken
import dev.provenance.core.parseInstitutionCert
import dev.provenance.core.parseStudentCredential
import dev.provenance.core.toJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Student master secret + per-course enrollment storage (program spec §5a).
 * The JetBrains twin of the VS Code recorder's `secret-store.ts`.
 *
 * ## Where the secrets live
 *
 * Everything here goes through [SecretStore] and nowhere else — in production, the IDE's
 * PasswordSafe credential vault. See [PasswordSafeSecretStore] for why that store and not
 * IDE state or a workspace file.
 *
 * ## Moving to a new machine
 *
 * There is no escrow and no server-side key store, by design — so nobody can recover this
 * for a student. The student runs **"Provenance: Export Student Identity Secret"** on the
 * old machine, copies the 64-hex-character string it shows, and runs **"Provenance: Import
 * Student Identity Secret"** on the new one. Their per-course keys are then re-derived by
 * HKDF (`deriveCourseKeypair`) byte-identically, so every enrollment token they already
 * hold keeps working and nothing has to be re-minted.
 *
 * If the secret is lost outright, the student generates a fresh one and asks for a new
 * enrollment token per course; past bundles remain verifiable, because each one carries the
 * token that was current when it was recorded.
 *
 * ## Why enrollment tokens live here too
 *
 * A token is a signed PUBLIC statement, not a secret — it is written verbatim into
 * `session.start`. It is stored alongside the master secret anyway so there is exactly ONE
 * persistence mechanism to reason about: a wiped or unavailable keyring then loses both
 * together, which reads unambiguously as "not enrolled" rather than as a half-state where a
 * token exists but the key it names does not.
 */

/** The store key holding the hex-encoded 32-byte master secret. */
const val MASTER_SECRET_KEY: String = "provenance.studentMasterSecret"

/** Prefix for the per-course enrollment blobs. Identity 2.0 only. */
const val ENROLLMENT_KEY_PREFIX: String = "provenance.enrollment."

/**
 * The store key holding the ONE identity-2.1 student credential.
 *
 * Singular and course-free, unlike [ENROLLMENT_KEY_PREFIX]: a 2.1 credential names no
 * course, because a student now has one global key bound to one global `student_ref`,
 * obtained once. That is the whole point of the 2.1 change — see `Institution.kt`.
 */
const val CREDENTIAL_KEY: String = "provenance.studentCredential"

/**
 * The store key for one course's enrollment.
 *
 * `courseId` is a STORAGE key here, not a JCS object key, so the
 * no-user-derived-object-keys constraint from `CourseCert.kt` does not apply — nothing
 * about this string is ever canonicalized or signed.
 */
fun enrollmentKeyForCourse(courseId: String): String = ENROLLMENT_KEY_PREFIX + courseId

/** Result plumbing. CLAUDE.md: errors are values when expected. */
sealed interface StoreResult<out T> {
    data class Ok<T>(val value: T) : StoreResult<T>

    data class Err(val error: IdentityStoreError) : StoreResult<Nothing>
}

sealed interface IdentityStoreError {
    /** Nothing stored. Only [loadMasterSecret] reports this; the load-or-create path creates one. */
    data object NoMasterSecret : IdentityStoreError

    /** Something is stored but is not 64 hex characters. NEVER overwritten automatically. */
    data class CorruptMasterSecret(val reason: String) : IdentityStoreError

    /** The keyring itself failed — common on a headless Linux box with no libsecret. */
    data class SecretStoreUnavailable(val reason: String) : IdentityStoreError

    data class InvalidJson(val message: String) : IdentityStoreError

    data class UnsupportedFormatVersion(
        /** `"cert"` or `"token"`. */
        val artifact: String,
        val formatVersion: String,
    ) : IdentityStoreError

    data class InvalidTokenShape(val reason: String) : IdentityStoreError

    data class InvalidCertShape(val reason: String) : IdentityStoreError

    data class CourseIdMismatch(val tokenCourseId: String, val certCourseId: String) : IdentityStoreError

    /** 2.1 shape failure on the credential slot. The twin of [InvalidTokenShape]. */
    data class InvalidCredentialShape(val reason: String) : IdentityStoreError

    /**
     * 2.1: the credential and the cert travelling with it name different institutions.
     * The analogue of [CourseIdMismatch].
     *
     * Note what this does NOT do: it cannot detect the cross-institution forgery
     * `verifyIdentityChain` guards against, because that check needs the root-verified
     * anchor and import time has none. It catches a student who mixed two pastes.
     */
    data class InstitutionIdMismatch(
        val credentialInstitutionId: String,
        val certInstitutionId: String,
    ) : IdentityStoreError

    /**
     * The importer's own version refusal: the pasted blob declares an identity version
     * that is neither 2.0 nor 2.1, so there is no family to route it to.
     */
    data class UnsupportedIdentityVersion(val formatVersion: String) : IdentityStoreError
}

/** The 2.0 `{ enrollment, enrollment_cert }` pair a student pastes in and we persist. */
data class StoredEnrollment(
    val enrollment: EnrollmentToken,
    val enrollmentCert: EnrollmentCert,
)

/**
 * The 2.1 `{ enrollment, enrollment_cert }` pair.
 *
 * The field names deliberately match [StoredEnrollment] and the two `SessionIdentity`
 * wire slots. These two fields are literally two-thirds of a `SessionIdentity`, so
 * [buildSessionIdentity] drops the stored pair straight in and adds only
 * `sessionPubkeySig`. There is no rename step between the paste and the signed log
 * entry, and therefore no rename step to get wrong.
 */
data class StoredCredential(
    val enrollment: StudentCredential,
    val enrollmentCert: InstitutionCert,
)

/** What an identity import turned out to be, once routed on the signed version. */
sealed interface IdentityImportOk {
    /** Legacy course-scoped. Still importable forever; only MINTING was retired. */
    data class Legacy20(val courseId: String) : IdentityImportOk

    data class Current21(
        val institutionId: String,
        val studentRef: String,
        /** So a caller can check this machine derives it, without re-reading the store. */
        val studentPubkey: String,
    ) : IdentityImportOk
}

private val HEX_MASTER_RE = Regex("^[0-9a-f]{${STUDENT_MASTER_SECRET_BYTES * 2}}$")

private fun describe(e: Throwable): String = e.message ?: e.toString()

/**
 * Normalize a pasted secret: students copy it out of a dialog, so a stray newline,
 * surrounding whitespace, or an uppercase rendering must not read as corruption.
 */
private fun normalizeHex(raw: String): String =
    raw.trim().replace(Regex("\\s+"), "").lowercase()

// ---------------------------------------------------------------------------
// Master secret
// ---------------------------------------------------------------------------

/** Read the stored master secret WITHOUT creating one. */
fun loadMasterSecret(secrets: SecretStore): StoreResult<ByteArray> {
    val stored = try {
        secrets.get(MASTER_SECRET_KEY)
    } catch (e: Throwable) {
        return StoreResult.Err(IdentityStoreError.SecretStoreUnavailable(describe(e)))
    }

    if (stored.isNullOrEmpty()) return StoreResult.Err(IdentityStoreError.NoMasterSecret)
    if (!HEX_MASTER_RE.matches(stored)) {
        // NOT overwritten. A mis-encoded value may still be recoverable by hand, and
        // silently replacing it would invalidate every token the student holds — every
        // per-course key would change, so every stored token would name a stale pubkey.
        return StoreResult.Err(
            IdentityStoreError.CorruptMasterSecret(
                "expected ${STUDENT_MASTER_SECRET_BYTES * 2} hex characters",
            ),
        )
    }
    return StoreResult.Ok(Ed25519.hexToBytes(stored))
}

/**
 * Read the master secret, generating and persisting one on first use.
 *
 * A corrupt stored value is an ERROR rather than a regeneration trigger, for the reason in
 * [loadMasterSecret].
 */
fun loadOrCreateMasterSecret(secrets: SecretStore): StoreResult<ByteArray> {
    when (val existing = loadMasterSecret(secrets)) {
        is StoreResult.Ok -> return existing
        is StoreResult.Err ->
            if (existing.error !is IdentityStoreError.NoMasterSecret) return existing
    }

    val fresh = generateStudentMasterSecret()
    return try {
        secrets.store(MASTER_SECRET_KEY, Ed25519.bytesToHex(fresh))
        StoreResult.Ok(fresh)
    } catch (e: Throwable) {
        StoreResult.Err(IdentityStoreError.SecretStoreUnavailable(describe(e)))
    }
}

/** Hex-encode the stored master secret for the student to copy to a new machine. */
fun exportMasterSecret(secrets: SecretStore): StoreResult<String> =
    when (val loaded = loadMasterSecret(secrets)) {
        is StoreResult.Ok -> StoreResult.Ok(Ed25519.bytesToHex(loaded.value))
        is StoreResult.Err -> loaded
    }

/**
 * Adopt a master secret pasted from another machine.
 *
 * A malformed paste leaves any existing secret UNTOUCHED — overwriting it on a typo would
 * be unrecoverable, since there is no escrow to restore from.
 */
fun importMasterSecret(secrets: SecretStore, raw: String): StoreResult<Unit> {
    val hex = normalizeHex(raw)
    if (!HEX_MASTER_RE.matches(hex)) {
        return StoreResult.Err(
            IdentityStoreError.CorruptMasterSecret(
                "expected ${STUDENT_MASTER_SECRET_BYTES * 2} hex characters, got ${hex.length}",
            ),
        )
    }
    return try {
        secrets.store(MASTER_SECRET_KEY, hex)
        StoreResult.Ok(Unit)
    } catch (e: Throwable) {
        StoreResult.Err(IdentityStoreError.SecretStoreUnavailable(describe(e)))
    }
}

// ---------------------------------------------------------------------------
// Enrollment tokens
// ---------------------------------------------------------------------------

/**
 * Validate a pasted `{ enrollment, enrollment_cert }` blob and persist it under the course
 * the token names.
 *
 * Shape and version only — **SIGNATURES ARE NOT CHECKED HERE**, because the trust anchor
 * for that is the manifest's root-verified `course_cert`, which belongs to a workspace and
 * is not in scope at import time. The real check happens at session start in
 * [buildSessionIdentity], against the manifest actually being recorded. Validating here is
 * only to reject an obvious paste error while the student is standing there to fix it.
 */
fun saveEnrollment(secrets: SecretStore, rawJson: String): StoreResult<String> {
    val parsed = try {
        Json.parseToJsonElement(rawJson)
    } catch (e: Exception) {
        return StoreResult.Err(IdentityStoreError.InvalidJson(describe(e)))
    }
    val obj = parsed as? JsonObject
        ?: return StoreResult.Err(IdentityStoreError.InvalidJson("expected a JSON object"))

    // Version gate BEFORE shape, mirroring verifyIdentityChain step 0: a future 3.0
    // artifact must be rejected as a version problem, never read under 2.0 rules.
    for ((field, artifact) in listOf("enrollment_cert" to "cert", "enrollment" to "token")) {
        val declared = ((obj[field] as? JsonObject)?.get("format_version") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        if (declared != ENROLLMENT_FORMAT_VERSION) {
            return StoreResult.Err(
                IdentityStoreError.UnsupportedFormatVersion(artifact, declared ?: ""),
            )
        }
    }

    val cert = when (val c = parseEnrollmentCert(obj["enrollment_cert"])) {
        is EnrollmentParse.Err -> return StoreResult.Err(IdentityStoreError.InvalidCertShape(c.reason))
        is EnrollmentParse.Ok -> c.value
    }
    val token = when (val t = parseEnrollmentToken(obj["enrollment"])) {
        is EnrollmentParse.Err -> return StoreResult.Err(IdentityStoreError.InvalidTokenShape(t.reason))
        is EnrollmentParse.Ok -> t.value
    }

    // Caught here as well as in the chain walk: storing a pair that can never verify would
    // leave the student believing they are enrolled while every session silently omitted an
    // identity.
    if (token.courseId != cert.courseId) {
        return StoreResult.Err(IdentityStoreError.CourseIdMismatch(token.courseId, cert.courseId))
    }

    val blob = buildJsonObject {
        put("enrollment", token.toJsonObject())
        put("enrollment_cert", cert.toJsonObject())
    }.toString()

    return try {
        secrets.store(enrollmentKeyForCourse(token.courseId), blob)
        StoreResult.Ok(token.courseId)
    } catch (e: Throwable) {
        StoreResult.Err(IdentityStoreError.SecretStoreUnavailable(describe(e)))
    }
}

/**
 * Read the stored enrollment for one course.
 *
 * Returns null for EVERY failure — absent, unreadable keyring, corrupt blob. This is on the
 * session-start path, where the only correct response to "cannot produce an identity" is to
 * record without one.
 */
fun loadEnrollment(secrets: SecretStore, courseId: String): StoredEnrollment? {
    val raw = try {
        secrets.get(enrollmentKeyForCourse(courseId))
    } catch (_: Throwable) {
        return null
    }
    if (raw.isNullOrEmpty()) return null

    val obj = try {
        Json.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return null

    val cert = parseEnrollmentCert(obj["enrollment_cert"]) as? EnrollmentParse.Ok ?: return null
    val token = parseEnrollmentToken(obj["enrollment"]) as? EnrollmentParse.Ok ?: return null
    return StoredEnrollment(enrollment = token.value, enrollmentCert = cert.value)
}

/** Forget one course's enrollment. NEVER touches the master secret. */
fun clearEnrollment(secrets: SecretStore, courseId: String) {
    try {
        secrets.delete(enrollmentKeyForCourse(courseId))
    } catch (_: Throwable) {
        // Best effort — there is nothing useful to do if the keyring is unavailable.
    }
}

// ---------------------------------------------------------------------------
// Student credentials — identity 2.1, INSTITUTION-scoped
// ---------------------------------------------------------------------------

/**
 * Validate a pasted 2.1 `{ enrollment, enrollment_cert }` blob and persist it.
 *
 * Step for step the twin of [saveEnrollment], in the same order and for the same
 * reasons, with the 2.1 artifacts and the 2.1 cross-field check:
 *
 *  1. JSON, and a JSON *object*.
 *  2. Version gate on BOTH slots, cert first — before any shape work, so a future 3.0
 *     artifact is refused as a version problem and never read under 2.1 rules. This
 *     mirrors `verifyIdentityChain` step 0.
 *  3. Shape, cert first, via `core`'s own parsers.
 *  4. `institution_id` agreement between the credential and the cert travelling with
 *     it — the 2.1 analogue of the 2.0 `course_id` comparison.
 *
 * **SIGNATURES ARE NOT CHECKED HERE**, exactly as at 2.0. The 2.1 trust anchor is the
 * recorder's embedded ROOT public key, and the real walk happens at session start in
 * [buildSessionIdentity], against the session actually being recorded. Validating here
 * only rejects an obvious paste error while the student is standing there to fix it.
 */
fun saveStudentCredential(
    secrets: SecretStore,
    rawJson: String,
): StoreResult<IdentityImportOk.Current21> {
    val parsed = try {
        Json.parseToJsonElement(rawJson)
    } catch (e: Exception) {
        return StoreResult.Err(IdentityStoreError.InvalidJson(describe(e)))
    }
    val obj = parsed as? JsonObject
        ?: return StoreResult.Err(IdentityStoreError.InvalidJson("expected a JSON object"))

    // Version gate BEFORE shape, mirroring verifyIdentityChain step 0.
    for ((field, artifact) in listOf("enrollment_cert" to "cert", "enrollment" to "credential")) {
        val declared = ((obj[field] as? JsonObject)?.get("format_version") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        if (declared != INSTITUTION_IDENTITY_FORMAT_VERSION) {
            return StoreResult.Err(
                IdentityStoreError.UnsupportedFormatVersion(artifact, declared ?: ""),
            )
        }
    }

    val cert = when (val c = parseInstitutionCert(obj["enrollment_cert"])) {
        is EnrollmentParse.Err -> return StoreResult.Err(IdentityStoreError.InvalidCertShape(c.reason))
        is EnrollmentParse.Ok -> c.value
    }
    val credential = when (val t = parseStudentCredential(obj["enrollment"])) {
        is EnrollmentParse.Err ->
            return StoreResult.Err(IdentityStoreError.InvalidCredentialShape(t.reason))

        is EnrollmentParse.Ok -> t.value
    }

    // Caught here as well as in the chain walk: storing a pair that can never verify
    // would leave the student believing they are enrolled while every session silently
    // omitted an identity.
    if (credential.institutionId != cert.institutionId) {
        return StoreResult.Err(
            IdentityStoreError.InstitutionIdMismatch(credential.institutionId, cert.institutionId),
        )
    }

    val blob = buildJsonObject {
        put("enrollment", credential.toJsonObject())
        put("enrollment_cert", cert.toJsonObject())
    }.toString()

    return try {
        secrets.store(CREDENTIAL_KEY, blob)
        StoreResult.Ok(
            IdentityImportOk.Current21(
                institutionId = credential.institutionId,
                studentRef = credential.studentRef,
                studentPubkey = credential.studentPubkey,
            ),
        )
    } catch (e: Throwable) {
        StoreResult.Err(IdentityStoreError.SecretStoreUnavailable(describe(e)))
    }
}

/**
 * Read the stored 2.1 credential.
 *
 * Returns null for EVERY failure, for the same reason as [loadEnrollment]: this is on
 * the session-start path, where the only correct response to "cannot produce an
 * identity" is to record without one.
 */
fun loadStudentCredential(secrets: SecretStore): StoredCredential? {
    val raw = try {
        secrets.get(CREDENTIAL_KEY)
    } catch (_: Throwable) {
        return null
    }
    if (raw.isNullOrEmpty()) return null

    val obj = try {
        Json.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return null

    val cert = parseInstitutionCert(obj["enrollment_cert"]) as? EnrollmentParse.Ok ?: return null
    val credential = parseStudentCredential(obj["enrollment"]) as? EnrollmentParse.Ok ?: return null
    return StoredCredential(enrollment = credential.value, enrollmentCert = cert.value)
}

/** Forget the 2.1 credential. NEVER touches the master secret. */
fun clearStudentCredential(secrets: SecretStore) {
    try {
        secrets.delete(CREDENTIAL_KEY)
    } catch (_: Throwable) {
        // Best effort — nothing useful to do if the keyring is unavailable.
    }
}

// ---------------------------------------------------------------------------
// The one importer — routes on the SIGNED version
// ---------------------------------------------------------------------------

/**
 * Import whatever identity artifact a student pasted, 2.0 or 2.1.
 *
 * ## Routing on the signed version, never on which fields exist
 *
 * Both versions use the same two wire slots, so "which keys are present" says nothing
 * about which version this is. The discriminator is the `format_version` INSIDE
 * `enrollment_cert` — signed in both families, at the same wire key in both — which is
 * exactly what `verifyIdentityChain` step 0 reads, and for exactly the reason spelled
 * out there. Presence is attacker-controlled and ambiguous; a signed version is neither.
 *
 * Reading the declared version off an unvalidated object is safe precisely because
 * nothing has been trusted yet — the routed-to function re-reads and re-validates it
 * before anything is stored.
 *
 * ## Both versions remain importable, forever
 *
 * A student who still holds a 2.0 token can still import it, and a recorder that already
 * stored one keeps using it. 2.0 MINTING is retired; 2.0 handling is not, and archived
 * material is the entire justification for the system.
 */
fun saveIdentityArtifact(secrets: SecretStore, rawJson: String): StoreResult<IdentityImportOk> {
    val parsed = try {
        Json.parseToJsonElement(rawJson)
    } catch (e: Exception) {
        return StoreResult.Err(IdentityStoreError.InvalidJson(describe(e)))
    }
    val obj = parsed as? JsonObject
        ?: return StoreResult.Err(IdentityStoreError.InvalidJson("expected a JSON object"))

    val declared = ((obj["enrollment_cert"] as? JsonObject)?.get("format_version") as? JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: ""

    return when (declared) {
        INSTITUTION_IDENTITY_FORMAT_VERSION -> when (val r = saveStudentCredential(secrets, rawJson)) {
            is StoreResult.Ok -> StoreResult.Ok(r.value)
            is StoreResult.Err -> r
        }

        ENROLLMENT_FORMAT_VERSION -> when (val r = saveEnrollment(secrets, rawJson)) {
            is StoreResult.Ok -> StoreResult.Ok(IdentityImportOk.Legacy20(r.value))
            is StoreResult.Err -> r
        }

        else -> StoreResult.Err(IdentityStoreError.UnsupportedIdentityVersion(declared))
    }
}
