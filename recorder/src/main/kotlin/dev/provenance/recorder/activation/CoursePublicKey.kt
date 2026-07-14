package dev.provenance.recorder.activation

/**
 * The course's offline-signing public key, hex-encoded ed25519 (32 bytes => 64 hex chars).
 *
 * The constant below is a DEV placeholder — a real ed25519 public key whose private
 * seed is kept out of the repo, so local development and integration tests can
 * sign+verify test manifests without a real course key. To produce a production
 * build with the real course public key, a Plan 9 Gradle task substitutes this
 * constant from an env var, builds, then `git checkout`'s this file to restore the
 * dev key — mirrors the VS Code recorder's `tools/embed-course-key.ts` flow.
 * Never commit a real course key here.
 */
const val COURSE_PUBLIC_KEY_HEX: String =
    "958d262beee700b5a55a218fcb7aa9a6aa1ed4eb200a3ce8fdd09e9160d5564b"
