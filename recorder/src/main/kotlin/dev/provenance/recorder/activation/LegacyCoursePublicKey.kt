package dev.provenance.recorder.activation

/**
 * The LEGACY course public key, hex-encoded ed25519 (32 bytes => 64 hex chars).
 *
 * Program spec: the monorepo's
 * `docs/superpowers/specs/2026-08-18-multicourse-program-architecture.md` §2, §9.
 *
 * Manifest 2.0 moved the plugin's trust anchor to a single embedded ROOT key (see
 * [ROOT_PUBLIC_KEY_HEX]): a course's authority now comes from its root-signed
 * `course_cert`, not from a course-specific plugin build. But every Manifest 1.x
 * file already in the field was signed directly by a course's OLD signing key,
 * with no cert and no chain — verifying those against the root key fails closed,
 * and in this system failing to activate means **silently recording nothing at
 * all**, for every student, for the rest of the term. That is a worse outcome for
 * an integrity tool than honouring one grandfathered key.
 *
 * So this constant grandfathers that key back in. `ManifestActivation.kt` routes
 * on `format_version`: 2.0 chain-verifies against [ROOT_PUBLIC_KEY_HEX], while
 * missing-or-1.0 verifies here, byte-for-byte as it did before Manifest 2.0
 * existed.
 *
 * **Scheduled for removal.** A second permanent trust anchor is precisely what the
 * root-key hierarchy exists to eliminate. Once program spec §9's migration has
 * completed for every course with manifests still active in the field, delete this
 * file, the 1.x-routing branch in `ManifestActivation.kt` that reads it, the
 * `legacyCoursePubkeyHex` parameters threaded through the activation path, and its
 * entry in `embedTrustAnchors` in `recorder/build.gradle.kts`.
 *
 * The constant below is **provjet's own** prior embedded dev course key — the exact
 * value `CoursePublicKey.kt` held on `main` before this change. That specificity is
 * the point: grandfathering means "keep accepting the 1.x manifests you already
 * accepted", and the key that signed those is whatever THIS recorder verified
 * against. The three recorders each carried a different dev course key
 * (VS Code `46f91d59…`, provjet `958d262b…`, provnvim `b5bca59f…`), so adopting a
 * sibling's value here would silently stop accepting every 1.x manifest in
 * provjet's field — precisely the failure this clause exists to prevent. Only
 * [ROOT_PUBLIC_KEY_HEX] is genuinely shared across the three.
 *
 * To produce a production build with the real legacy course public key:
 *
 *   PROVENANCE_LEGACY_COURSE_PUBLIC_KEY_HEX=<hex> ./gradlew :recorder:buildProd
 *
 * That variable is **optional** — omit it once no 1.x manifests remain in the
 * field, and the production build ships the dev key here, which no real manifest
 * can satisfy. That is the intended retirement mechanism, and it is why
 * `verifyEmbeddedTrustAnchors` only asserts this constant when the variable is
 * supplied. Never commit a real key here.
 */
const val LEGACY_COURSE_PUBLIC_KEY_HEX: String =
    "958d262beee700b5a55a218fcb7aa9a6aa1ed4eb200a3ce8fdd09e9160d5564b"
