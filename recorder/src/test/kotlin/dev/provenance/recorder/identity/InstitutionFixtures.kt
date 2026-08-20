package dev.provenance.recorder.identity

import dev.provenance.core.Ed25519
import dev.provenance.core.INSTITUTION_IDENTITY_FORMAT_VERSION
import dev.provenance.core.InstitutionCert
import dev.provenance.core.StudentCredential
import dev.provenance.core.deriveStudentKeypair
import dev.provenance.core.signInstitutionCert
import dev.provenance.core.signStudentCredential
import dev.provenance.core.toJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A complete, genuinely-signed identity-2.1 chain for the recorder-side tests:
 * root → institution key → student credential → the student's single GLOBAL key.
 *
 * Everything is real ed25519 over real JCS payloads, from fixed seeds. Nothing here is a
 * stub: `buildSessionIdentity` runs the same `verifyIdentityChain` the analyzer runs, so a
 * faked signature would simply be skipped and the tests would prove nothing.
 *
 * The master secret is deliberately the SAME array as [EnrollmentFixtures.masterSecret], so
 * a store can hold a 2.0 token and a 2.1 credential for one student at once — which is what
 * the precedence test needs.
 */
object InstitutionFixtures {
    const val INSTITUTION_ID: String = "berkeley"
    const val OTHER_INSTITUTION_ID: String = "stanford"
    const val STUDENT_REF: String = "9c8e1a70-2f2b-4c55-8f1e-6b4a0d9c7e21"

    val rootPriv: ByteArray = EnrollmentFixtures.rootPriv
    val institutionPriv: ByteArray = ByteArray(32) { 0x54 }

    /**
     * A real ed25519 institution key that the recorder's anchor never names. Used to mint
     * a GENUINELY SIGNED credential the chain must still reject: a valid signature from a
     * key nobody certified is worth nothing.
     */
    val foreignInstitutionPriv: ByteArray = ByteArray(32) { 0x67 }

    /** A root key that is NOT the recorder's embedded one. */
    val wrongRootPriv: ByteArray = ByteArray(32) { 0x68 }

    val masterSecret: ByteArray = EnrollmentFixtures.masterSecret

    val rootPubkeyHex: String get() = Ed25519.bytesToHex(Ed25519.publicKeyOf(rootPriv))
    val institutionPubkeyHex: String
        get() = Ed25519.bytesToHex(Ed25519.publicKeyOf(institutionPriv))
    val foreignInstitutionPubkeyHex: String
        get() = Ed25519.bytesToHex(Ed25519.publicKeyOf(foreignInstitutionPriv))

    /** The student's single global public key — no course anywhere in the derivation. */
    fun studentPubkeyHex(): String = deriveStudentKeypair(masterSecret).publicKeyHex

    /** The ROOT-signed authorization for the server-held institution key. */
    fun cert(
        institutionId: String = INSTITUTION_ID,
        formatVersion: String = INSTITUTION_IDENTITY_FORMAT_VERSION,
        institutionPubkey: String = institutionPubkeyHex,
        validFrom: String = "2026-08-20",
        validUntil: String = "2027-01-15",
        /** Which root key signs it. Defaults to the recorder's embedded one. */
        signingKey: ByteArray = rootPriv,
    ): InstitutionCert {
        val unsigned = InstitutionCert(
            formatVersion = formatVersion,
            institutionId = institutionId,
            institutionPubkey = institutionPubkey,
            validFrom = validFrom,
            validUntil = validUntil,
            rootSig = "",
        )
        return unsigned.copy(rootSig = signInstitutionCert(unsigned, signingKey))
    }

    /** The institution-signed statement binding the global student pubkey to a ref. */
    fun credential(
        institutionId: String = INSTITUTION_ID,
        formatVersion: String = INSTITUTION_IDENTITY_FORMAT_VERSION,
        studentPubkey: String = studentPubkeyHex(),
        issuedAt: String = "2026-09-01T00:00:00Z",
        expiresAt: String = "2027-01-15",
        /** Which institution key signs it. Defaults to the certified one. */
        signingKey: ByteArray = institutionPriv,
    ): StudentCredential {
        val unsigned = StudentCredential(
            formatVersion = formatVersion,
            institutionId = institutionId,
            studentRef = STUDENT_REF,
            studentPubkey = studentPubkey,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            institutionSig = "",
        )
        return unsigned.copy(institutionSig = signStudentCredential(unsigned, signingKey))
    }

    /** The `{ enrollment, enrollment_cert }` blob a student pastes into the import command. */
    fun blobJson(
        credential: StudentCredential = credential(),
        cert: InstitutionCert = cert(),
    ): String = buildJsonObject {
        put("enrollment", credential.toJsonObject())
        put("enrollment_cert", cert.toJsonObject())
    }.toString()

    /** A store already holding this student's master secret and a valid 2.1 credential. */
    fun credentialedStore(
        credential: StudentCredential = credential(),
        cert: InstitutionCert = cert(),
    ): FakeSecretStore {
        val s = FakeSecretStore()
        importMasterSecret(s, Ed25519.bytesToHex(masterSecret))
        saveStudentCredential(s, blobJson(credential, cert))
        return s
    }
}
