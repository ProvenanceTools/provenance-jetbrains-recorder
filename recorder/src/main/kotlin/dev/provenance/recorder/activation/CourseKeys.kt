package dev.provenance.recorder.activation

/**
 * The course public key is the verification anchor for every `.provenance-manifest`
 * the recorder loads (PRD §4.1). Mirrors the VS Code recorder's two-file split
 * (`course-public-key.ts` holds the swappable constant; `course-keys.ts` is the
 * stable import surface) so a future production-build task can swap the sibling
 * `CoursePublicKey.kt` in place without touching anything else.
 *
 * Unlike TypeScript, Kotlin's top-level `const val` is already a single stable
 * import path (`dev.provenance.recorder.activation.COURSE_PUBLIC_KEY_HEX`), so this
 * file only documents the stable import surface — callers import COURSE_PUBLIC_KEY_HEX
 * directly from this package.
 */
