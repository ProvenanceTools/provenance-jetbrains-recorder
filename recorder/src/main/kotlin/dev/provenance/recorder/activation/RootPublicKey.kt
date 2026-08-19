package dev.provenance.recorder.activation

/**
 * The Provenance ROOT public key, hex-encoded ed25519 (32 bytes => 64 hex chars).
 *
 * Program spec: the monorepo's
 * `docs/superpowers/specs/2026-08-18-multicourse-program-architecture.md` §2.
 *
 * At Manifest 1.x the plugin embedded ONE course's signing key, so every course
 * needed its own plugin build. At Manifest 2.0 it embeds the ROOT key only: a
 * course's authority comes from its root-signed `course_cert`, which travels
 * inline in the `.provenance-manifest`. One build serves every course.
 *
 * This is the trust anchor for [dev.provenance.core.verifyManifestChain]. 1.x
 * manifests, which have no cert to chain, are grandfathered against
 * [LEGACY_COURSE_PUBLIC_KEY_HEX] instead — see that file.
 *
 * The constant below is the DEV root key shared by all three recorder
 * implementations (its private half lives in the monorepo's
 * `.notes/dev-root-keypair.json`, never here), so local development and the
 * integration fixtures can sign+verify test certs without a real root key.
 *
 * To produce a production build with the real root public key:
 *
 *   PROVENANCE_ROOT_PUBLIC_KEY_HEX=<hex> ./gradlew :recorder:buildProd
 *
 * `buildProd` runs `embedTrustAnchors` to overwrite the constant below before
 * building and signing, then `git checkout`'s this file to restore the dev key.
 * That task pins this file's shape: this exact constant name, and a quoted
 * 64-char lowercase hex literal on a single line. Never commit a real key here.
 *
 * Kotlin's top-level `const val` is already a single stable import path
 * (`dev.provenance.recorder.activation.ROOT_PUBLIC_KEY_HEX`), so unlike the VS
 * Code recorder there is no separate re-export module — callers import it
 * directly from this package.
 */
const val ROOT_PUBLIC_KEY_HEX: String =
    "80051f5bdb9064e0768bf2fca5cc9a4ee888502ab45472e0c6d0f4f704de4499"
