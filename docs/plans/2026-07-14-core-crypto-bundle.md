# Core Crypto & Bundle Implementation Plan (Plan 2 of the provjet series)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Extend `core/` with the ed25519/crypto and bundle layer — manifest parse+verify (the activation gate), bundle manifest shape+signing, per-session ed25519 keypair + encrypted private key, and signed checkpoints — each proven against log-core via deterministic cross-language vectors.

**Architecture:** Still pure-Kotlin/JVM, zero IntelliJ deps. ed25519 (RFC 8032, deterministic), HKDF-SHA256, and XChaCha20-Poly1305 come from **BouncyCastle**. Because ed25519 signing is deterministic, a fixed (key, message) yields a fixed signature that MUST match `@noble/ed25519`'s — that equality is the conformance gate.

**Tech Stack:** Kotlin/JVM, BouncyCastle (`org.bouncycastle:bcprov-jdk18on`), kotlinx-serialization-json, JUnit 5. Builds on Plan 1's `Sha256`, `Canonical`, `Envelope`.

## Global Constraints

(Inherits Plan 1's Global Constraints.) Additional:
- **ed25519 is RFC 8032 pure-variant, deterministic.** Same key + message → identical 64-byte (128-hex) signature across BouncyCastle and `@noble/ed25519`. This is the cross-language guarantee the conformance tests rely on.
- **Manifest signed payload** (PRD §4.1): `JCS({assignment_id, semester, issued_at, files_under_review})` → UTF-8 → ed25519. The `sig` field is excluded.
- **Bundle manifest signed payload** (PRD §5.3): `JCS(entire manifest object)` → UTF-8 → ed25519.
- **Checkpoint signed payload** (PRD §4.6): `JCS({hash, seq})` → UTF-8 → ed25519.
- **Session privkey encryption** (PRD §4.6): XChaCha20-Poly1305; key = HKDF-SHA256(IKM = hex-decoded manifest sig, salt = 16 random bytes, info = ASCII `"provenance-session-key-v1"`, len 32); nonce = 24 random bytes; algorithm tag `"xchacha20-poly1305-hkdf-sha256-v1"`. The analyzer (TS, `@noble/ciphers`) must be able to decrypt what this produces — **cross-language decrypt is a conformance requirement**.
- **Generating cross-language vectors:** the sibling monorepo (`../provenance`) is the source of truth. Where a vector value isn't pinned in this plan, generate it by running a tiny Node script against `packages/log-core` and commit the result to `core/src/test/resources/conformance/`. Never invent a crypto vector.

---

### Task 1: Ed25519 wrapper (BouncyCastle)

**Files:**
- Modify: `core/build.gradle.kts` — add `implementation("org.bouncycastle:bcprov-jdk18on:1.79")`.
- Create: `core/src/main/kotlin/dev/provenance/core/Ed25519.kt`
- Test: `core/src/test/kotlin/dev/provenance/core/Ed25519Test.kt`
- Create (vector): `core/src/test/resources/conformance/ed25519.json`

**Interfaces:**
- Produces:
  - `object Ed25519 { fun generateKeypair(): Pair<ByteArray, ByteArray> /* (priv32, pub32) */; fun sign(message: ByteArray, privateKey32: ByteArray): ByteArray /* 64 */; fun verify(signature: ByteArray, message: ByteArray, publicKey32: ByteArray): Boolean; fun publicKeyOf(privateKey32: ByteArray): ByteArray }`
  - Hex helpers `fun hexToBytes(String): ByteArray`, `fun bytesToHex(ByteArray): String` (lowercase).

**Test intent:**
- Round-trip: generate → sign → verify true; tampered message → verify false.
- **Cross-language vector** (`ed25519.json`): a fixed 32-byte private key (hex) + a fixed message (the bytes of `JCS({"a":1})`) → the exact 128-hex signature `@noble/ed25519` produces. Generate this once via Node against log-core's `@noble/ed25519` and commit it; assert `bytesToHex(Ed25519.sign(msg, priv)) == vector.sig`. This proves BC ed25519 ≡ noble ed25519.
- `verify` returns false (never throws) on malformed input.

**Vector generation (run once, commit output):**
```bash
# from ../provenance
node --input-type=module -e '
import * as ed from "@noble/ed25519";
const priv = new Uint8Array(32).fill(7);           // fixed test key
const msg = new TextEncoder().encode("{\"a\":1}");  // fixed message
const sig = await ed.signAsync(msg, priv);
const pub = await ed.getPublicKeyAsync(priv);
const hex = b => Buffer.from(b).toString("hex");
console.log(JSON.stringify({ priv_hex: hex(priv), msg_utf8: "{\"a\":1}", pub_hex: hex(pub), sig_hex: hex(sig) }, null, 2));
'
```
Commit the JSON to `core/src/test/resources/conformance/ed25519.json`.

**Steps:** standard TDD (write cross-language + round-trip test → fails → implement BC `Ed25519Signer`/`Ed25519PrivateKeyParameters`/`Ed25519PublicKeyParameters` wrapper → passes → commit `feat(core): ed25519 wrapper matching noble via BouncyCastle`).

---

### Task 2: Manifest parse + verify (the activation gate primitive)

**Files:**
- Create: `core/src/main/kotlin/dev/provenance/core/Manifest.kt`
- Test: `core/src/test/kotlin/dev/provenance/core/ManifestTest.kt`

**Interfaces:**
- Consumes: `Canonical`, `Ed25519`.
- Produces:
  - `data class Manifest(val assignmentId: String, val semester: String, val issuedAt: String, val filesUnderReview: List<String>, val sig: String)`
  - `sealed interface ManifestParse { data class Ok(val manifest: Manifest); data class Err(val reason: String) }`
  - `fun parseManifest(text: String): ManifestParse` — validates JSON + field shapes; `sig` must be 128 hex. (Mirrors log-core `parseManifest`.)
  - `fun verifyManifest(manifest: Manifest, coursePubkeyHex: String): Boolean` — builds `JCS({assignment_id, semester, issued_at, files_under_review})`, UTF-8, ed25519-verify against the 64-hex pubkey. Returns false on any malformed input.

**Test intent:**
- Cross-language: build a manifest in Node via log-core's `signManifest` with a fixed course keypair; assert this Kotlin `verifyManifest` returns true for that sig and false if any field is mutated. Commit the signed manifest + pubkey as `core/src/test/resources/conformance/manifest.json`.
- `parseManifest` rejects: non-object, missing fields, non-128-hex sig.

**Steps:** TDD as above → commit `feat(core): manifest parse + ed25519 verify (activation gate)`.

---

### Task 3: Bundle manifest — model, shape validation, signing

**Files:**
- Create: `core/src/main/kotlin/dev/provenance/core/Bundle.kt`
- Test: `core/src/test/kotlin/dev/provenance/core/BundleTest.kt`

**Interfaces:**
- Consumes: `Canonical`, `Ed25519`.
- Produces:
  - `data class SubmissionFileEntry(val path: String, val status: String /* "present"|"missing" */, val sha256: String?)`
  - `data class SessionEntry(val sessionId: String?, val prevSessionId: String?, val slogSha256: String, val metaSha256: String)`
  - `data class BundleManifest(val formatVersion: String /* "1.0"|"1.1" */, val assignmentId: String, val semester: String, val extensionHash: String, val sessions: List<SessionEntry>, val submissionFiles: List<SubmissionFileEntry>?)`
  - `fun BundleManifest.toJsonText(): String` — emits the on-the-wire shape with snake_case keys (`format_version`, `extension_hash`, `slog_sha256`, `meta_sha256`, `prev_session_id`, `session_id`, `submission_files`). `submission_files` omitted when null (1.0).
  - `fun validateBundleManifestShape(jsonText: String): Result<BundleManifest>` — mirrors log-core `validateBundleManifestShape` (accepts 1.0 without submission_files; 1.1 requires it; present→64-hex sha, missing→null sha).
  - `data class SignedBundleManifest(val canonicalJson: String, val signatureHex: String)`
  - `fun signBundleManifest(manifest: BundleManifest, signingPrivkey32: ByteArray): SignedBundleManifest` — `canonicalJson = JCS(manifest.toJsonText()); signatureHex = bytesToHex(Ed25519.sign(canonicalJson.utf8, privkey))`. Caller writes `canonicalJson`→`manifest.json`, `signatureHex`→`manifest.sig`.

**Test intent:**
- Sign then verify with the matching pubkey → true.
- Cross-language: sign a fixed manifest in Kotlin, verify in Node via log-core against the session pubkey → true (round-trip vector committed as `bundle-manifest.json`).
- Shape validator accepts a real 1.1 manifest and rejects: wrong version, missing `extension_hash`, non-64-hex sha, 1.1 missing `submission_files`.

**Steps:** TDD → commit `feat(core): bundle manifest model, shape validation, ed25519 signing`.

---

### Task 4: Session keypair + encrypted private key

**Files:**
- Modify: `core/build.gradle.kts` — confirm BouncyCastle covers XChaCha20-Poly1305 + HKDF (it does: `HKDFBytesGenerator`, `XChaCha20Poly1305`/`ChaCha20Poly1305` with XChaCha nonce handling).
- Create: `core/src/main/kotlin/dev/provenance/core/SessionKeys.kt`
- Test: `core/src/test/kotlin/dev/provenance/core/SessionKeysTest.kt`

**Interfaces:**
- Consumes: `Ed25519`.
- Produces:
  - `data class SessionKeypair(val publicKeyHex: String, val privateKey: ByteArray)`
  - `fun generateSessionKeypair(): SessionKeypair`
  - `data class EncryptedPrivkey(val algorithm: String, val nonce: String, val ciphertext: String, val salt: String, val info: String)` — hex fields; `algorithm = "xchacha20-poly1305-hkdf-sha256-v1"`, `info = "provenance-session-key-v1"`.
  - `fun encryptSessionPrivkey(privateKey32: ByteArray, manifestSig: String, saltBytes: ByteArray = random16, nonceBytes: ByteArray = random24): EncryptedPrivkey` — salt/nonce injectable for deterministic tests.
  - `fun decryptSessionPrivkey(enc: EncryptedPrivkey, manifestSig: String): ByteArray` — throws on auth-tag failure (wrong manifestSig).

**Test intent:**
- Round-trip: encrypt → decrypt returns the original 32 bytes.
- Wrong `manifestSig` on decrypt → throws (the replay-resistance security property).
- **Cross-language decrypt (the hard gate):** encrypt a fixed privkey in Kotlin with fixed salt+nonce+manifestSig; hand the resulting `EncryptedPrivkey` to Node via log-core's `decryptSessionPrivkey` and assert it recovers the original bytes. Commit the fixtures. **If XChaCha20-Poly1305/HKDF framing differs between BouncyCastle and `@noble/ciphers`, this is where it surfaces — STOP and reconcile the exact nonce/tag/IKM handling; do not weaken the test.**

**Risk note:** This is the single highest-risk task in Plan 2. BouncyCastle's XChaCha20 API and tag placement must match `@noble/ciphers` (16-byte tag appended to ciphertext, 24-byte nonce, HKDF IKM = hex-decoded sig bytes). Budget iteration here.

**Steps:** TDD → commit `feat(core): session keypair + XChaCha20-Poly1305 encrypted privkey`.

---

### Task 5: Signed checkpoints

**Files:**
- Create: `core/src/main/kotlin/dev/provenance/core/Checkpoint.kt`
- Test: `core/src/test/kotlin/dev/provenance/core/CheckpointTest.kt`

**Interfaces:**
- Consumes: `Canonical`, `Ed25519`.
- Produces:
  - `data class Checkpoint(val seq: Long, val hash: String, val sig: String)`
  - `fun signCheckpoint(seq: Long, entryHash: String, privateKey32: ByteArray): Checkpoint` — signs `JCS({hash: entryHash, seq})` UTF-8. (JCS sorts keys, so `{hash, seq}` ordering is normalized.)
  - `fun verifyCheckpoint(cp: Checkpoint, publicKeyHex: String): Boolean` — false (never throws) on invalid.

**Test intent:**
- Sign → verify true; wrong pubkey → false; tampered hash → false.
- Cross-language: sign in Kotlin, verify in Node via log-core `verifyCheckpoint` → true (fixture committed).

**Steps:** TDD → commit `feat(core): signed seq→hash checkpoints`.

---

### Task 6: Consolidate conformance + full-suite gate

**Files:**
- Modify: `core/src/test/kotlin/dev/provenance/core/ConformanceTest.kt` — add ed25519, manifest, bundle-sign, session-key, checkpoint vector assertions.

- [ ] Run `./gradlew :core:test` — the entire `core/` suite green (Plan 1 + Plan 2). This is the gate before any plugin work (Plan 3) begins.
- [ ] Commit `test(core): full crypto/bundle conformance gate`.

---

## Self-Review

**Spec coverage (design.md §3 crypto/format completion):** ed25519 (T1), manifest gate (T2), bundle sign+shape (T3), session keypair + encrypted privkey (T4), checkpoints (T5), conformance (T6). Together with Plan 1, `core/` is now feature-complete for the format contract the plugin (Plans 3+) will drive.

**Fidelity note:** ed25519/manifest/bundle/checkpoint tasks are high-confidence (deterministic crypto, exact log-core source transcribed). **Task 4 (XChaCha20 cross-language)** carries real BouncyCastle-vs-noble API risk and is flagged for iteration. All crypto vectors are generated from log-core, never invented.
