package dev.provenance.recorder.events

/**
 * The single source of truth for how much content the recorder will inline into one
 * event payload. Mirrors packages/recorder/src/events/inline-content-limits.ts.
 *
 * Three payloads carry content inline: `doc.open` (content), `paste` (content), and
 * `fs.external_change` (new_content). All three used to declare their own ceiling;
 * `doc.open` was already 64 KB while the other two were 4 KB.
 *
 * WHY 64 KB AND NOT 4 KB (recorder PRD §4.3 / §4.5)
 *
 * At 4 KB, neither a genuine external write nor a large paste to a real-sized source
 * file was ever recoverable: the evidence was discarded at record time, so no
 * analyzer-side fix could bring it back.
 *
 *  - fs.external_change: above the cap the analyzer sees only head/tail and a hash, so
 *    it cannot reconstruct the post-change file, and `mass_external_replacement` cannot
 *    evaluate the change at all.
 *  - paste: a `paste` event is NOT duplicated by a `doc.change`, so a pasted solution
 *    above the cap was unrecoverable in reconstruction AND invisible to the paste
 *    heuristics — the single most load-bearing detection case in the product.
 *
 * This is a THRESHOLD change, not a schema change: `content` / `new_content` are
 * already optional fields, so old and new analyzers interoperate in both directions
 * and `format_version` is deliberately NOT bumped.
 *
 * Measured in UTF-8 BYTES (`toByteArray(Charsets.UTF_8).size`), never in characters.
 * The boundary is INCLUSIVE: content of exactly MAX_INLINE_BYTES is inlined. Pinned
 * cross-language by the JSON vectors in recorder/src/test/resources/conformance, which
 * are generated from the monorepo TS by tools/export-conformance-vectors.ts.
 */
const val MAX_INLINE_BYTES = 64 * 1024
