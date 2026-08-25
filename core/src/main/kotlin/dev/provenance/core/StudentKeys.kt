package dev.provenance.core

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.SecureRandom

/**
 * Student master secret and per-course key derivation (program spec §S2).
 * The Kotlin twin of log-core's `student-keys.ts`.
 *
 * One secret for a student to hold and back up; one unlinkable ed25519 keypair
 * per course derived from it:
 *
 * ```
 *   master_secret (32 random bytes; NEVER leaves the student's machine)
 *        │ HKDF-SHA256, info bound to course_id
 *        ▼
 *   per-course ed25519 seed ──► student per-course keypair
 *        │ countersigns
 *        ▼
 *   session_pubkey  (the existing ephemeral session key)
 * ```
 *
 * ## Why derive instead of generating one key per course
 *
 * Three properties, all of which a student holding N independent keypairs loses:
 *
 *  - **One thing to back up.** A student who loses their key loses the ability to
 *    prove authorship of their own work. Backing up one 32-byte secret is a
 *    request a student can actually satisfy; backing up a growing set of
 *    per-course keys is not.
 *  - **Unlinkability.** Each course sees a public key derived under a different
 *    `info`, so two courses comparing rosters cannot tell that two entries are the
 *    same person. Correlating them requires the master secret, which never leaves
 *    the machine and is never sent to any server.
 *  - **Recoverability without escrow.** Re-deriving on a new machine needs only
 *    the master secret. There is no server-side key store to breach, because
 *    there is nothing to store.
 *
 * ## THE DERIVATION IS A CROSS-LANGUAGE CONTRACT
 *
 * Three recorders (TypeScript, Kotlin, Lua) must derive **byte-identical** keys
 * from the same master secret, or a student's signature made in one editor will
 * not verify against the public key their token names — and that failure looks
 * exactly like tampering. The parameters are pinned here and in the
 * `student-keys.json` conformance vectors:
 *
 * ```
 *   algorithm  HKDF (RFC 5869) with SHA-256
 *   IKM        the 32 RAW BYTES of the master secret (not hex, not base64)
 *   salt       UTF-8 bytes of "provenance-student-key-v1" — 25 bytes.
 *              Deliberately NON-EMPTY: HKDF's "absent salt" rule (substitute
 *              HashLen zero bytes) is a place where three implementations can
 *              quietly disagree, and HMAC's own zero-padding makes an empty salt
 *              and a 32-zero-byte salt produce the same PRK — an equivalence that
 *              is true but that no port should have to know. Passing concrete
 *              bytes removes the question entirely.
 *   info       UTF-8 bytes of "provenance-student-key-v1:" + course_id
 *   L          32 bytes
 * ```
 *
 * The 32-byte output IS the ed25519 secret key (seed). ed25519 accepts any 32
 * bytes as a seed, so there is no rejection sampling or retry loop — another
 * thing that would otherwise have to agree across three ports.
 *
 * `course_id` enters the derivation as a **value** inside `info`, never as a JSON
 * object key. The permanent no-user-derived-object-keys constraint documented in
 * `CourseCert.kt` is about canonicalization key ORDERING and does not apply to
 * `info`, which is a flat byte string: UTF-8 encoding is unambiguous across all
 * three languages. A non-ASCII `course_id` is therefore safe here, and a
 * conformance vector proves it — which is also why this file encodes with
 * [Charsets.UTF_8] and never `US_ASCII`.
 */

/** Length of a student master secret, in bytes. */
const val STUDENT_MASTER_SECRET_BYTES: Int = 32

/**
 * HKDF `info` prefix. The full info is this string concatenated with the
 * `course_id`, then UTF-8 encoded. **The trailing colon is part of the constant** —
 * it is the separator that stops `cs61b` + `-extra` and `cs61b-extra` colliding,
 * and a port that concatenates without it derives different keys for one of them.
 * Pinned by the `cs61b` / `cs61b-extra` conformance cases.
 */
const val STUDENT_KEY_HKDF_INFO_PREFIX: String = "provenance-student-key-v1:"

/** HKDF salt string. Its UTF-8 bytes (25 of them) are the salt. */
const val STUDENT_KEY_HKDF_SALT_UTF8: String = "provenance-student-key-v1"

/** Output length of the derivation, in bytes — an ed25519 seed. */
const val STUDENT_KEY_SEED_BYTES: Int = 32

/**
 * HKDF salt bytes.
 *
 * A function rather than a shared `val` so a caller cannot mutate the array and
 * silently change every subsequent derivation in the process — `ByteArray` has no
 * immutable form in Kotlin, which is the same hazard log-core defends against by
 * copying on read.
 */
fun studentKeyHkdfSalt(): ByteArray = STUDENT_KEY_HKDF_SALT_UTF8.toByteArray(Charsets.UTF_8)

data class StudentCourseKeypair(
    /** Hex-encoded ed25519 public key (32 bytes → 64 hex chars). */
    val publicKeyHex: String,
    /**
     * Raw 32-byte ed25519 secret key — the HKDF output itself. Kept in memory
     * only; it is re-derivable from the master secret and must never be persisted.
     */
    val privateKey: ByteArray,
) {
    // ByteArray uses identity equals/hashCode, which would make two keypairs
    // holding identical bytes compare unequal. Content-compare instead, so tests
    // and callers get the structural equality a data class implies.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StudentCourseKeypair) return false
        return publicKeyHex == other.publicKeyHex && privateKey.contentEquals(other.privateKey)
    }

    override fun hashCode(): Int = 31 * publicKeyHex.hashCode() + privateKey.contentHashCode()
}

private val secureRandom = SecureRandom()

/**
 * Generate a fresh 32-byte student master secret.
 *
 * This is the ONLY value in the identity scheme that a student must keep and back
 * up. It never leaves the machine, is never sent to a server, and is never written
 * into a log or a bundle. Losing it means losing the ability to sign as yourself in
 * every course; leaking it means every per-course key is derivable AND every course
 * identity becomes linkable.
 */
fun generateStudentMasterSecret(): ByteArray {
    val out = ByteArray(STUDENT_MASTER_SECRET_BYTES)
    secureRandom.nextBytes(out)
    return out
}

/**
 * Derive the raw 32-byte ed25519 seed for a student's key in one course.
 *
 * Pure and synchronous. THROWS (rather than returning a result type) on a malformed
 * input, because both failure modes are programmer errors at a call site that
 * controls both arguments — an unexpected condition, not an expected one. See
 * CLAUDE.md, "Errors are values when expected, exceptions when unexpected".
 *
 * @param masterSecret Exactly [STUDENT_MASTER_SECRET_BYTES] raw bytes.
 * @param courseId     The course this key is for; non-empty.
 */
fun deriveCourseKeySeed(masterSecret: ByteArray, courseId: String): ByteArray {
    require(masterSecret.size == STUDENT_MASTER_SECRET_BYTES) {
        "deriveCourseKeySeed: masterSecret must be exactly $STUDENT_MASTER_SECRET_BYTES bytes, " +
            "got ${masterSecret.size}"
    }
    require(courseId.isNotEmpty()) { "deriveCourseKeySeed: courseId must be a non-empty string" }

    val info = (STUDENT_KEY_HKDF_INFO_PREFIX + courseId).toByteArray(Charsets.UTF_8)
    val hkdf = HKDFBytesGenerator(SHA256Digest())
    hkdf.init(HKDFParameters(masterSecret, studentKeyHkdfSalt(), info))
    val out = ByteArray(STUDENT_KEY_SEED_BYTES)
    hkdf.generateBytes(out, 0, STUDENT_KEY_SEED_BYTES)
    return out
}

/**
 * Derive a student's per-course ed25519 keypair from their master secret.
 *
 * The private key is the [deriveCourseKeySeed] output verbatim; the public key is
 * the ordinary ed25519 public key for that seed. This is the key that signs
 * `session_pubkey` (see `Enrollment.kt`) and whose public half a course binds to a
 * roster entry inside an enrollment token.
 */
fun deriveCourseKeypair(masterSecret: ByteArray, courseId: String): StudentCourseKeypair {
    val privateKey = deriveCourseKeySeed(masterSecret, courseId)
    return StudentCourseKeypair(
        publicKeyHex = Ed25519.bytesToHex(Ed25519.publicKeyOf(privateKey)),
        privateKey = privateKey,
    )
}

// ---------------------------------------------------------------------------
// The CURRENT derivation: ONE global student key
// ---------------------------------------------------------------------------

/**
 * HKDF `info` for the CURRENT global student key. FIXED — no `course_id`, no
 * `institution_id`, no user-derived component of any kind.
 *
 * A student has ONE key, forever, across every course. Identity stopped being
 * course-scoped because a per-course key requires a per-course credential, which
 * requires a roster match, which only exists after the student's first submission —
 * while their very first session needs an identity before they do any work at all.
 * See `Institution.kt` for the full account.
 *
 * A pleasant side effect: with nothing user-derived in `info`, the encoding hazard
 * that [STUDENT_KEY_HKDF_INFO_PREFIX] has to live with is simply GONE here. Under v1 a
 * non-ASCII `course_id` encoded as `US_ASCII` rather than UTF-8 silently produced a
 * DIFFERENT key with no error — **it bit this repo once**, and the v1 conformance
 * vectors keep a `berkeley-café` case precisely to catch a recurrence. This constant
 * is pure ASCII and constant, so there is nothing left to get wrong.
 *
 * **There is no trailing colon**: nothing is concatenated onto it.
 */
const val STUDENT_KEY_HKDF_INFO: String = "provenance-student-key-v2"

/**
 * Derive the raw 32-byte ed25519 seed for a student's single GLOBAL key.
 *
 * Same master secret, same salt, same output length, same "the 32 bytes ARE the
 * ed25519 seed" rule as [deriveCourseKeySeed]. The ONLY difference is the `info`,
 * which is [STUDENT_KEY_HKDF_INFO] — fixed, ASCII, and carrying no user-derived
 * component. A student therefore has one key across every course, bound to a global
 * `student_ref` by a single credential obtained once.
 *
 * Because the two `info` strings differ, the v1 per-course keys and this key are
 * unrelated: a student's existing course keys are unaffected, and archived bundles
 * keep verifying against the public keys their tokens name.
 *
 * Pure and synchronous. THROWS on a malformed input, because that is a programmer
 * error at a call site that controls the argument — an unexpected condition, not an
 * expected one.
 *
 * @param masterSecret Exactly [STUDENT_MASTER_SECRET_BYTES] raw bytes.
 */
fun deriveStudentKeySeed(masterSecret: ByteArray): ByteArray {
    require(masterSecret.size == STUDENT_MASTER_SECRET_BYTES) {
        "deriveStudentKeySeed: masterSecret must be exactly $STUDENT_MASTER_SECRET_BYTES bytes, " +
            "got ${masterSecret.size}"
    }

    // UTF-8, never US_ASCII. The constant is pure ASCII so the two agree today, but
    // the v1 sibling derived a silently different key from a `US_ASCII` encoding and
    // nothing in this file should model that mistake as acceptable.
    val info = STUDENT_KEY_HKDF_INFO.toByteArray(Charsets.UTF_8)
    val hkdf = HKDFBytesGenerator(SHA256Digest())
    hkdf.init(HKDFParameters(masterSecret, studentKeyHkdfSalt(), info))
    val out = ByteArray(STUDENT_KEY_SEED_BYTES)
    hkdf.generateBytes(out, 0, STUDENT_KEY_SEED_BYTES)
    return out
}

/**
 * Derive a student's single global ed25519 keypair from their master secret.
 *
 * The private key is the [deriveStudentKeySeed] output verbatim; the public key is the
 * ordinary ed25519 public key for that seed. This is the key that countersigns
 * `session_pubkey` at 2.1 (see `Institution.kt`) and whose public half the institution
 * binds to a global `student_ref` inside a student credential.
 */
fun deriveStudentKeypair(masterSecret: ByteArray): StudentCourseKeypair {
    val privateKey = deriveStudentKeySeed(masterSecret)
    return StudentCourseKeypair(
        publicKeyHex = Ed25519.bytesToHex(Ed25519.publicKeyOf(privateKey)),
        privateKey = privateKey,
    )
}
