package dev.provenance.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The consolidated cross-language conformance gate. Every crypto/format primitive
 * in `core/` is checked here against golden vectors generated from the monorepo's
 * `log-core` (@noble libs, canonicalize). Because ed25519 (RFC 8032) and JCS are
 * deterministic, "matches log-core" means byte-identical — a red test here means
 * the format is wrong, never that a vector should change.
 */
class ConformanceTest {
    private fun vector(name: String): JsonObject =
        Json.parseToJsonElement(
            this::class.java.getResource("/conformance/$name")!!.readText(),
        ).jsonObject

    private val vectors: JsonObject by lazy { vector("vectors.json") }

    @Test
    fun `sha256 vectors match`() {
        for (v in vectors["sha256"]!!.jsonArray) {
            val o = v.jsonObject
            assertEquals(o["hex"]!!.jsonPrimitive.content, Sha256.hex(o["input"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun `chain vectors match`() {
        for (v in vectors["chain"]!!.jsonArray) {
            val o = v.jsonObject
            val e = o["envelope"]!!.jsonObject
            val env = Envelope(
                seq = e["seq"]!!.jsonPrimitive.long,
                t = e["t"]!!.jsonPrimitive.long,
                wall = e["wall"]!!.jsonPrimitive.content,
                kind = e["kind"]!!.jsonPrimitive.content,
                data = e["data"]!!.jsonObject,
            )
            val result = chainEntry(o["prev_hash"]!!.jsonPrimitive.content, env)
            assertEquals(o["hash"]!!.jsonPrimitive.content, result.hash)
        }
    }

    @Test
    fun `ed25519 vector matches noble`() {
        val v = vector("ed25519.json")
        val priv = Ed25519.hexToBytes(v["priv_hex"]!!.jsonPrimitive.content)
        val msg = v["msg_utf8"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8)
        assertEquals(v["sig_hex"]!!.jsonPrimitive.content, Ed25519.bytesToHex(Ed25519.sign(msg, priv)))
        assertEquals(v["pub_hex"]!!.jsonPrimitive.content, Ed25519.bytesToHex(Ed25519.publicKeyOf(priv)))
    }

    @Test
    fun `manifest vector verifies against course pubkey`() {
        val v = vector("manifest.json")
        val m = (parseManifest(v["manifest"]!!.jsonObject.toString()) as ManifestParse.Ok).manifest
        assertTrue(verifyManifest(m, v["course_pubkey_hex"]!!.jsonPrimitive.content))
    }

    @Test
    fun `bundle manifest signing reproduces log-core canonical json and signature`() {
        val v = vector("bundle-manifest.json")
        val m = validateBundleManifestShape(v["manifest"]!!.jsonObject.toString()).getOrThrow()
        val priv = ByteArray(32) { 3 }
        val signed = signBundleManifest(m, priv)
        assertEquals(v["canonical_json"]!!.jsonPrimitive.content, signed.canonicalJson)
        assertEquals(v["signature_hex"]!!.jsonPrimitive.content, signed.signatureHex)
    }

    @Test
    fun `session privkey ciphertext matches noble and decrypts`() {
        val v = vector("session-key.json")
        fun h(k: String) = v[k]!!.jsonPrimitive.content
        val enc = encryptSessionPrivkey(
            Ed25519.hexToBytes(h("privkey_hex")),
            h("manifest_sig"),
            saltBytes = Ed25519.hexToBytes(h("salt_hex")),
            nonceBytes = Ed25519.hexToBytes(h("nonce_hex")),
        )
        assertEquals(h("ciphertext_hex"), enc.ciphertext)
        assertEquals(h("privkey_hex"), Ed25519.bytesToHex(decryptSessionPrivkey(enc, h("manifest_sig"))))
    }

    @Test
    fun `checkpoint signature matches log-core`() {
        val v = vector("checkpoint.json")
        val priv = ByteArray(32) { 4 }
        val cp = signCheckpoint(
            v["seq"]!!.jsonPrimitive.long,
            v["hash"]!!.jsonPrimitive.content,
            priv,
        )
        assertEquals(v["sig"]!!.jsonPrimitive.content, cp.sig)
        assertTrue(verifyCheckpoint(cp, v["session_pubkey_hex"]!!.jsonPrimitive.content))
    }
}
