# Design: Provenance JetBrains Recorder (`provjet`)

**Status:** Approved design, pre-implementation
**Date:** 2026-07-14
**External brand:** Provenance Recorder (identical to the VS Code recorder)
**Internal codename:** `provjet`

---

## 1. What this is

A JetBrains IDE plugin that records a tamper-evident `.provenance` log while a
student works on an assignment, producing a sealed submission bundle that is
**byte-for-byte format-compatible** with the one the VS Code recorder produces.
The Provenance analyzer and server do not care which editor produced a bundle —
only that it validates (hash chain intact, manifest signature verifies,
`extension_hash` on the allowlist).

This is a **port of the wiring, not a new product.** The event format, hash
chain, JCS canonicalization, ed25519 signing, bundle/manifest shapes, signed
checkpoints, and per-session keypair all already exist in the Provenance
monorepo's `packages/log-core` (pure TypeScript). This repo reimplements that
format in Kotlin and re-derives the editor-specific signal detection against the
IntelliJ Platform SDK.

It lives in **its own repo** (not the Provenance monorepo) for one reason: the
JVM/Gradle toolchain is physically incompatible with the monorepo's npm
workspace. This is a forcing function, not a preference — see §9.

## 2. Scope

- **Target:** all JetBrains IDEs. Build against the core IntelliJ Platform
  (`com.intellij.modules.platform`) so one artifact runs in IDEA, PyCharm,
  CLion, WebStorm, GoLand, etc. Consequence: **only platform-common APIs** are
  available — no language-specific ones.
- **v1 = full behavioral parity** with the VS Code recorder (recorder PRD §4):
  activation + manifest verification, `doc.open/change/save/close`, three-signal
  paste detection (§4.3), external-change detection (§4.5), terminal + git
  wiring, plugin snapshot, hash chain, signed checkpoints, chain recovery,
  bundle seal, disk-full degraded mode.
- Even though v1 targets full parity, it is **built core-first** (§8) so
  canonicalization drift and IntelliJ VFS semantics are never debugged in the
  same week.

## 3. The format-parity strategy (the low-risk core)

Reimplement `log-core` in Kotlin, gated by **golden conformance vectors**
exported from the Provenance monorepo.

- **JCS canonicalization:** use `erdtman/java-json-canonicalization` — the JVM
  twin of the `canonicalize` npm library the VS Code recorder uses (same author,
  same algorithm). This de-risks the single fiddliest part (number formatting,
  key ordering).
- **ed25519:** Tink or BouncyCastle (decide at implementation time; both are
  vetted).
- **Conformance suite** (`conformance/`): loads the exact fixtures behind
  `log-core`'s `hash-chain.test.ts` plus a full golden bundle, and asserts (a)
  byte-identical hashes for known inputs and (b) that the *real* analyzer/server
  accept a bundle this plugin produces. Format drift becomes a red test, not a
  production surprise.

The crypto/format port is the **well-bounded, low-risk** part of this project.

## 4. The IntelliJ wiring map (the ~70%, where the real work is)

VS Code's document/paste/watcher APIs and IntelliJ's equivalents have different
semantics. This table is the port's core risk register.

| Signal | VS Code (today) | IntelliJ equivalent | Risk |
|---|---|---|---|
| doc edits | `onDidChangeTextDocument` (diff-grained) | `DocumentListener` on editor documents | Med — reconstruct diff shape |
| open/save/close | doc lifecycle events | `FileEditorManagerListener` / `FileDocumentManagerListener` | Low |
| external change (§4.5) | `FileSystemWatcher` + expected-content model | `BulkFileListener` on VFS + **VFS-refresh-on-focus** quirk | **High** — VFS is a cached layer; refresh timing changes detection direction |
| paste (§4.3, 3-signal) | paste-command intercept + doc change + clipboard | `AnActionListener` on `$Paste` + doc event + `CopyPasteManager` | **High** — no 1:1 command surface |
| terminal | terminal API | terminal-plugin listeners (platform-common subset only) | Med |
| git | git extension API | Git4Idea (may be absent in some IDEs) | Med — must degrade gracefully |
| plugin snapshot | `vscode.extensions.all` | `PluginManagerCore.getPlugins()` | Low — fills the existing `ext.snapshot` event shape |
| status bar | status bar item | `StatusBarWidgetFactory` | Low |

**External-change detection is the highest-risk item.** IntelliJ's VFS is a
cached snapshot that refreshes on window focus; the recorder PRD §4.5 note
("easy to get the direction wrong") is doubly true here because the on-disk vs
expected-content comparison interacts with *when* the VFS believes the file
changed. Budget for this specifically.

## 5. Producer identity & the one format wrinkle

Two facts established from `log-core`:

- **Producer identity already exists:** `session.start.recorder.extension_id`
  (`events.ts:41`) lets this plugin identify itself (e.g.
  `edu.berkeley.provenance.recorder`) with **no format change**. The analyzer can
  distinguish editors from this field if a heuristic ever needs to.
- **The `vscode` field wrinkle:** `session.start` has a hard-coded
  `vscode: { version, commit, platform }` object (`events.ts:31`) that is part
  of the signed, test-vector-pinned format. A JetBrains recorder must emit
  *something* there.

  **Decision (v1):** fill it with editor-generic values — `version` = the IDE's
  version, `commit` = `''` (analyzers already accept `''` here), `platform` =
  the OS. **No format change.** Rationale: YAGNI, avoids signed-format churn and
  test-vector rework, and `extension_id` already disambiguates the host.
  Generalizing the field to `host`/`editor` would be an approval-gated format
  bump in the monorepo and is explicitly deferred unless a concrete need appears.

## 6. Build & signing parity

Mirror the VS Code recorder's `build:prod` flow:

- **Course public key** embedded at build time. A Gradle task replaces a
  constant in `CoursePublicKey.kt` from an env var, builds, then reverts the
  source (exactly as `tools/embed-course-key.ts` + the `git checkout` step do in
  the monorepo).
- **`extension_hash`** in the sealed manifest = SHA-256 of the plugin
  distribution `.zip`. This is what the analyzer's allowlist checks.
- The plugin **never modifies** `manifest.json` / `manifest.sig` after seal —
  same rule as the monorepo. The stored bundle must stay signature/chain
  verifiable.

## 7. Changes required in the Provenance monorepo (small, additive)

This repo does **not** change Provenance source except:

1. **Allowlist:** add the plugin's build hash(es) to
   `packages/analysis-core/src/heuristics/config/known-good-extension-hashes.json`.
   The allowlist is producer-agnostic — it just needs the hash. The
   `extension_hash_mismatch` heuristic will otherwise flag every JetBrains
   submission. Possibly teach `npm run update-hashes` about the second artifact.
2. **Golden-vector export:** a script in the monorepo that emits `log-core`'s
   vectors + a golden bundle as language-neutral JSON that this repo's
   conformance suite consumes. (Recommended over hand-maintaining fixtures here,
   which would be drift-prone.)

No new event types. No heuristic logic changes (heuristics read the format, not
the editor).

## 8. Build sequence

Full-parity v1, but layered so each stage is independently green:

1. `core/` + `conformance/` — format port proven against golden vectors
2. activation + manifest verification + status-bar widget
3. `doc.open/change/save/close` + hash chain + bundle seal → **first valid
   bundle the real analyzer accepts**
4. external-change detection (the high-risk VFS work, isolated)
5. three-signal paste detection
6. terminal + git wiring (with graceful degradation when Git4Idea is absent)
7. checkpoints + chain recovery + disk-full degraded mode

## 9. Why a separate repo (and why the VS Code recorder is *not* split)

`provjet` is separate because JVM/Gradle cannot live in the monorepo's npm
workspace — a toolchain forcing function.

The VS Code recorder (`provcode`) **stays in the monorepo** deliberately: it is
TypeScript, consumes `log-core` as live workspace source, and its format parity
is enforced by the same `npm run test` that runs log-core's vectors. Splitting
it would trade that co-located contract enforcement for mere symmetry with
`provjet`, and would reintroduce the log-core duplication/drift problem across a
repo boundary. **The rule for "own repo" is a toolchain boundary, not
symmetry.**

## 10. Testing

- **Conformance vectors** — cross-language format parity (§3).
- **Wiring unit tests** — JUnit + IntelliJ test fixtures
  (`BasePlatformTestCase` / `LightPlatformTestCase`), mocking the platform at the
  seam. Mirror the monorepo convention: test the event→log-entry transform as a
  pure function, separately from platform wiring.
- **Determinism** — inject clocks; no `System.currentTimeMillis()` in
  assertions (mirrors the monorepo's no-`Date.now()` rule).

## 11. Open questions (non-blocking, decide at implementation time)

1. **ed25519 library:** Tink vs BouncyCastle.
2. **Distribution:** JetBrains Marketplace vs side-loaded plugin `.zip` (like the
   dev-key VSIX). Affects the `build:prod` and hash-publishing story.
3. **Golden-vector export format/location** in the monorepo (a `scripts/` entry
   emitting to a committed fixtures file is the leading option).
4. **Plugin id** (reverse-DNS, stable forever), e.g.
   `edu.berkeley.provenance.recorder`.
