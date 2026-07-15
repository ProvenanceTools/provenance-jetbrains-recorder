# CLAUDE.md

Project conventions and standing instructions for Claude Code working in this repo. Read this fully before doing anything.

## What this is

**provenance-jetbrains-recorder** (`provjet`): a JetBrains IDE plugin that records a tamper-evident `.provenance` log while a student works on an assignment, producing a sealed submission bundle that is **byte-for-byte format-compatible** with the one the VS Code recorder produces. It ships under the same **Provenance Recorder** name as the VS Code extension.

This is a **port of the wiring, not a new product.** The event format, hash chain, JCS canonicalization, ed25519 signing, bundle/manifest shapes, signed checkpoints, and per-session keypair all already exist in the [Provenance monorepo](https://github.com/ProvenanceTools/provenance)'s `packages/log-core` (pure TypeScript). This repo:

1. Reimplements that format in Kotlin (`core/`), and
2. Re-derives the editor-specific signal detection against the IntelliJ Platform SDK (`recorder/`).

The Provenance analyzer and server do not care which editor produced a bundle — only that it validates: hash chain intact, manifest signature verifies, `extension_hash` on the allowlist.

The full approved design is in `docs/design.md`. **Read it before implementing anything.** The recorder product spec is the monorepo's `docs/prd.md` (recorder PRD); section references like "§4.3" mean the recorder PRD.

## The Provenance format contract (do not redesign it)

The log file format is owned by the Provenance monorepo's `log-core`. This repo is a **second implementation of the same contract**, not an author of it. Treat the format as fixed:

- **The event envelope, hash chain, and JCS canonicalization are pinned by test vectors** in `log-core`'s `hash-chain.test.ts`. This repo's Kotlin implementation must reproduce them byte-for-byte.
- **Parity is enforced by golden conformance vectors** exported from `log-core` and checked in `conformance/`. If the conformance suite fails, the format is wrong — never "fix" it by changing the vectors.
- **JCS canonicalization** uses `erdtman/java-json-canonicalization` (the JVM twin of the `canonicalize` npm lib `log-core` uses — same author, same algorithm). Do not hand-roll canonicalization. Whitespace, key ordering, and number representation all matter.
- **Never modify `manifest.json` / `manifest.sig` after seal.** They are signed; the stored bundle must stay signature/chain verifiable.
- **Producer identity:** set `session.start.recorder.extension_id` to this plugin's id. This is how the analyzer distinguishes hosts — no format change needed.
- **The `session.start.vscode` field:** the format hard-codes a `vscode: { version, commit, platform }` object. Fill it with editor-generic values (`version` = IDE version, `commit` = `''`, `platform` = OS). Do **not** rename or generalize this field — that would be an approval-gated format change in the monorepo. See `docs/design.md` §5.

**If the format appears to require a change to accommodate JetBrains, STOP and ask.** A format change is a cross-repo, signed-contract, test-vector-pinned decision owned by the Provenance monorepo — never made unilaterally here to make an implementation easier.

## Working agreement

- **Stop and ask on ambiguity.** If a decision isn't covered by `docs/design.md`, the recorder PRD, or this file, do not invent an answer. Inventing architecture is the single biggest failure mode.
- **Stay in scope.** Touch only the files the current task requires. Do not opportunistically refactor. If you notice something that should change, mention it; don't change it.
- **No new dependencies without asking.** Every added Gradle dependency is a decision. Propose, justify, wait for approval. The approved core set is: the IntelliJ Platform SDK, `erdtman/java-json-canonicalization`, and a vetted ed25519 provider (Tink or BouncyCastle — to be pinned at implementation time).
- **No silent constraint softening.** If a conformance test fails and the obvious fix is to weaken the assertion or edit a vector, stop and explain. The vectors encode the format contract; loosening them is not a coding decision.
- **Read before writing.** Before editing any file, read it. Before editing any module, read its tests.
- **Small diffs.** A change touching more than ~200 lines across more than ~5 files is probably two changes. Split it.

## Architecture rules

- **`core/` is a pure-Kotlin port of the log format.** No IntelliJ Platform imports, no plugin APIs. It knows about events, hashing, canonicalization, signing, bundles — nothing about editors. This mirrors `log-core`'s zero-editor-dependency rule and keeps the conformance surface testable in isolation.
- **`recorder/` is the plugin: the IntelliJ wiring around `core/`.** Activation, document/VFS/terminal/git listeners, paste detection, the session host, the status-bar widget, the seal command. It depends on `core/` and the IntelliJ Platform SDK.
- **`conformance/` proves format parity** against golden vectors exported from the monorepo. It is the gate: a bundle this plugin seals must be accepted by the *real* Provenance analyzer/server.
- Events are **append-only**. There is no update or delete on a log, anywhere.
- The hash chain is the foundation of integrity. Exactly one chaining function; all log-producing paths go through it (mirrors `log-core`).
- **This repo never changes the Provenance monorepo** except two small, additive things (see `docs/design.md` §7): adding the plugin's build hash to the `known-good-extension-hashes.json` allowlist, and adding a golden-vector export script. Both are done in the monorepo with its own conventions, not here.

## IntelliJ wiring — things that are easy to get wrong here

The format port is the low-risk part. The wiring is ~70% of the work and where the real ambiguity lives (`docs/design.md` §4). VS Code's APIs and IntelliJ's have **different semantics** — do not assume a 1:1 mapping.

- **External-change detection (§4.5) is the highest-risk item.** IntelliJ's VFS is a *cached* layer that refreshes on window focus. The expected-content model is the source of truth; the on-disk hash is what you compare against — and getting the direction wrong is easy, made worse by *when* the VFS believes a file changed. Use `BulkFileListener`, and test the refresh-timing interaction explicitly.
- **Paste detection (§4.3) is three signals combined** — action interception (`$Paste` via `AnActionListener`), the resulting document change, and clipboard content (`CopyPasteManager`). IntelliJ has no single paste-command surface equivalent to VS Code's. Do not simplify to one signal without discussion.
- **Git wiring degrades gracefully.** Git4Idea may be absent in some IDEs / configurations. Missing git integration is a degraded signal, not a crash.
- **Activation is scoped and privacy-preserving.** Record only inside an activated assignment workspace whose `.provenance-manifest` verifies against the embedded course public key (§4.1). Drop events for files outside the workspace and for non-`file` VFS schemes. Never record a student's user-level config or out-of-workspace scratch files.
- **Atomic writes.** Write-temp-then-rename. Never partial-write the live log file.
- **Clock handling.** Monotonic clock for `t` (relative to session start); wall clock for `wall`. Don't conflate.
- **The document-change firehose.** IntelliJ fires document events rapidly; handlers must be fast and the writer must buffer. Keep per-event handler work minimal.

## Build & signing

- Kotlin + Gradle, using the **IntelliJ Platform Gradle Plugin**. Target the platform core (`com.intellij.modules.platform`) so one artifact runs across JetBrains IDEs — meaning **only platform-common APIs** are available.
- **Course public key** is embedded at build time: a Gradle task substitutes a constant from an env var, builds, then reverts the source (mirrors the monorepo's `embed-course-key` + `git checkout` flow). Never commit a real course key.
- The sealed manifest's `extension_hash` = SHA-256 of the plugin distribution `.zip`. That hash must be added to the monorepo allowlist or every submission gets flagged.
- **Plugin id** (reverse-DNS, e.g. `edu.berkeley.provenance.recorder`) is stable forever — Marketplace identity and auto-update channels key off it.

## Testing

- **Conformance suite** for cross-language format parity — the non-negotiable gate.
- **Wiring unit tests** with JUnit + IntelliJ test fixtures (`BasePlatformTestCase` / `LightPlatformTestCase`), mocking the platform at the seam. Test the event→log-entry transform as a pure function, separately from the platform wiring (mirrors the monorepo).
- **Determinism.** Inject clocks. No `System.currentTimeMillis()` / `Math.random()` in assertions.
- Every PR-sized change ships with tests. New behavior gets new tests; bug fixes get a regression test that fails before the fix.

## Code style

- Kotlin, idiomatic. Prefer `data class` + sealed hierarchies for event types (the Kotlin analogue of `log-core`'s discriminated unions).
- Pure functions over classes when there's no state to own. The session writer owns a file handle, so it's a class; hashing/canonicalization are pure.
- No background task without an explicit shutdown path. Every listener, watcher, timer, and coroutine has a `dispose()` / plugin-teardown hook.
- Errors are values when expected (a sealed `Result`-like type), exceptions when unexpected. Never swallow.

## Conventions for talking to me

- When you finish a task, summarize what you did, what you didn't do, and what you noticed but didn't change.
- If you make a non-obvious choice, explain it in the response — don't bury it in a comment.
- If you used a dependency you weren't told to use, or skipped a test you couldn't get to pass, lead with it.
- "Done" means: tests pass (including conformance), it compiles, the diff is reviewable. Not "I wrote some code."

## Git commits

- Conventional-commit prefixes (`feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `style`).
- Sign off with `git commit --no-gpg-sign`. Do **not** add a `Co-Authored-By: Claude` trailer or any Claude attribution.
- Always stage/commit with an explicit pathspec — the tree may contain unrelated in-progress work.
- Commit incrementally during multi-phase work.

## When in doubt

Re-read `docs/design.md`, re-read the recorder PRD section, re-read this file, and ask. The format is a contract owned elsewhere; the wiring is full of non-obvious IntelliJ semantics. The cost of a clarifying question is five minutes.
