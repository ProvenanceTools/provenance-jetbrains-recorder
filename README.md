<picture>
  <source media="(prefers-color-scheme: dark)" srcset="brand/exports/lockup-dark.png" />
  <img alt="Provenance for JetBrains IDEs" src="brand/exports/lockup-light.png" width="440" />
</picture>

**Provenance Recorder for JetBrains IDEs** records a tamper-evident log of how a student's
code came into existence, and seals it into a signed submission bundle.

It is the JetBrains counterpart to the
[Provenance](https://github.com/ProvenanceTools/provenance) VS Code recorder, and ships under the
same **Provenance Recorder** name. Both produce a bundle in the **same format**, so the
Provenance analyzer and server ingest and validate a submission regardless of which editor
produced it — they care only that it validates: hash chain intact, manifest signature
verifies, `extension_hash` on the allowlist.

This is a **port of the wiring, not a new product**. The event format, hash chain, JCS
canonicalization, ed25519 signing, bundle and manifest shapes, signed checkpoints, and
per-session keypair are all defined by the Provenance monorepo's `log-core` package. This
repo reimplements that format in Kotlin and re-derives the editor-specific signal detection
against the IntelliJ Platform SDK.

## How it works

The plugin activates only on a workspace containing a valid, course-signed
`.provenance-manifest`. From then on it records a timestamped, hash-chained log of editing
activity, buffered and written atomically, and seals it into a signed bundle at the end of
the session.

The log format is a **fixed contract owned by the Provenance monorepo**, not by this repo.
Parity is not assumed — it is tested. `core/`'s output is verified byte-for-byte against
`log-core` using golden vectors exported from the monorepo, so both editors' bundles stay
compatible down to the byte. If a conformance test fails, the implementation is wrong; the
vectors are never edited to make it pass.

```
core/       pure-Kotlin port of the log format — no IntelliJ Platform imports
recorder/   the plugin: IntelliJ wiring around core/
```

## What it records

- **Document changes.** File open, save, close, and every edit, recorded from IntelliJ's
  document-change firehose with per-event handler work kept minimal and the writer buffered.
- **Pastes.** Detected by combining three signals — action interception (`$Paste` via
  `AnActionListener`), the resulting document change, and clipboard content
  (`CopyPasteManager`). IntelliJ has no single paste-command surface, so no one signal is
  sufficient.
- **External changes.** Edits made to watched files *outside* the IDE, detected via
  `BulkFileListener` against an in-memory expected-content model. Only files listed in the
  manifest's `files_under_review` get that model.
- **Selection and caret movement.**
- **Terminal and git activity**, where the host IDE exposes it. Git4Idea may be absent in
  some IDEs or configurations; missing git integration is a degraded signal, not a crash.
- **Session metadata and signed checkpoints** throughout the session, so a truncated log
  still validates up to its last checkpoint.

## Privacy & security

- **Offline.** The plugin makes no network calls during a session.
- **Scoped to the assignment workspace.** It activates only against a `.provenance-manifest`
  that verifies with the course public key embedded at build time. Events for files outside
  the workspace, and for non-`file` VFS schemes, are dropped. It never records a student's
  user-level config or out-of-workspace scratch files.
- **Append-only.** There is no update or delete on a log, anywhere. Exactly one chaining
  function; every log-producing path goes through it.
- **Signed at seal.** Each session gets its own ed25519 keypair, and the private key is
  encrypted at rest under a key derived from the manifest signature. `manifest.json` and
  `manifest.sig` are never modified after seal.
- **Atomic writes.** Write-temp-then-rename. The live log file is never partially written.

## Requirements

- **JDK 17.**
- **A JetBrains IDE on build 261 or newer** (2026.1+). The plugin targets the platform core
  (`com.intellij.modules.platform`), so one artifact runs across JetBrains IDEs — and only
  platform-common APIs are available to it.
- **A course-signed `.provenance-manifest`** at the workspace root. Without one the plugin
  stays inactive by design.

## Install

**JetBrains Marketplace.** Published at
[plugins.jetbrains.com/plugin/32944-provenance-recorder](https://plugins.jetbrains.com/plugin/32944-provenance-recorder).
Install from the IDE: **Settings → Plugins → Marketplace**, search *Provenance Recorder*.
This is the **signed release** — it trusts only manifests signed by the real course key
embedded at publish time.

**Sideload (development).** Build the dev-key `.zip` yourself:

```sh
./gradlew :recorder:buildPlugin
```

Then in the IDE: **Settings → Plugins → gear icon → Install Plugin from Disk…**, and pick
the `.zip` from `recorder/build/distributions/`. This is the **dev-key** build — it trusts
manifests signed by the checked-in development key, not a real course key. Cutting a signed
Marketplace release is documented in [Releasing](#releasing).

## Building

Kotlin and Gradle, using the IntelliJ Platform Gradle Plugin.

```sh
git clone https://github.com/ProvenanceTools/provenance-jetbrains-recorder
cd provenance-jetbrains-recorder

./gradlew :core:test         # format unit + conformance tests
./gradlew :recorder:test     # recorder unit + platform-fixture tests
./gradlew :recorder:runIde   # sandbox IDE with the plugin loaded, for manual testing
```

Wiring tests use JUnit with IntelliJ test fixtures (`BasePlatformTestCase` /
`LightPlatformTestCase`), mocking the platform at the seam. The event→log-entry transform is
tested as a pure function, separately from the platform wiring. Clocks are injected, so no
test asserts against wall-clock time.

## Conformance

Cross-language format parity is the non-negotiable gate. `core/`'s output is checked
byte-for-byte against `log-core` using the vectors in
`core/src/test/resources/conformance/`, plus a golden sealed bundle. These are generated,
not hand-authored — regenerate them from the monorepo:

```sh
cd ../provenance
node --experimental-strip-types tools/export-conformance-vectors.ts \
  --out ../provenance-jetbrains-recorder/core/src/test/resources/conformance
```

**Never hand-edit a vector file.** A failing conformance test after regenerating means the
implementation has drifted from the format — fix `core/`, never the vectors.

## Repo layout

```
provenance-jetbrains-recorder/
├── core/                                   # pure-Kotlin port of the log format
│   └── src/test/resources/conformance/     # golden vectors exported from log-core
├── recorder/                               # the plugin: IntelliJ wiring around core/
├── docs/
│   ├── design.md                           # the approved architecture and design
│   ├── releasing.md                        # signed Marketplace release runbook
│   ├── manual-verification.md              # checks that need a windowed IDE
│   └── plans/                              # implementation plans
├── CLAUDE.md                               # repo conventions
├── gradle.properties                       # plugin version, platform version, since-build
└── settings.gradle.kts                     # :core, :recorder
```

## Architecture rules (enforced)

- **`core/` has zero IntelliJ Platform imports.** It knows about events, hashing,
  canonicalization, signing, and bundles — nothing about editors. This mirrors `log-core`'s
  zero-editor-dependency rule and keeps the conformance surface testable in isolation.
- **`recorder/` depends on `core/` and the IntelliJ Platform SDK.** Activation, listeners,
  paste detection, the session host, the status-bar widget, the seal command.
- **The log format is a contract, not a design space.** It is pinned by test vectors in
  `log-core` and by the golden vectors here. A change to accommodate JetBrains would be a
  cross-repo, signed-contract decision owned by the monorepo — never made unilaterally here.
- **JCS canonicalization uses `erdtman/java-json-canonicalization`**, the JVM twin of the
  `canonicalize` npm library `log-core` uses. Never hand-rolled: whitespace, key ordering,
  and number representation all matter.
- **No background task without an explicit shutdown path.** Every listener, watcher, timer,
  and coroutine has a `dispose()` / plugin-teardown hook.

## Common commands

| Command                                  | What it does                                                        |
| ---------------------------------------- | ------------------------------------------------------------------- |
| `./gradlew :core:test`                   | Format unit + conformance tests.                                    |
| `./gradlew :recorder:test`               | Recorder unit + platform-fixture tests.                             |
| `./gradlew :recorder:buildPlugin`        | Build the sideloadable plugin `.zip`.                               |
| `./gradlew :recorder:runIde`             | Launch a sandbox IDE with the plugin loaded.                        |
| `./gradlew :recorder:computeExtensionHash` | Reproducible SHA-256 of the built distribution, for the allowlist. |
| `./gradlew :recorder:buildProd`          | Embed course key → build → sign → hash → revert. Needs secrets.     |
| `./gradlew :recorder:publishProd`        | `buildProd` + `verifyPlugin`, then publish. Needs secrets.          |

## Releasing

Cutting a signed Marketplace release, computing the `extension_hash` for the analyzer
allowlist, and the operator secrets each step needs are documented in
[`docs/releasing.md`](docs/releasing.md).

Every release needs its own allowlist entry in the monorepo, or its submissions get flagged
— the dev build and a production build hash differently, because embedding a different
course key changes the compiled bytes.

## Documentation

| Document                                                     | What's in it                                     |
| ------------------------------------------------------------ | ------------------------------------------------ |
| [`docs/design.md`](docs/design.md)                           | The approved architecture and design.            |
| [`docs/releasing.md`](docs/releasing.md)                     | Signed Marketplace release runbook.              |
| [`docs/manual-verification.md`](docs/manual-verification.md) | External-change checks that need a windowed IDE. |
| [`CLAUDE.md`](CLAUDE.md)                                     | Repo conventions and architecture rules.         |

The recorder product spec (`docs/prd.md`) lives in the Provenance monorepo.

## License

Licensed under the Apache License, Version 2.0 — see [`LICENSE`](LICENSE) and
[`NOTICE`](NOTICE).

The distributed plugin `.zip` bundles a number of third-party open-source libraries (Bouncy
Castle, the Kotlin standard library, kotlinx.serialization, JetBrains `annotations`, and
`java-json-canonicalization`). Their licenses and required notices are reproduced in
[`THIRD-PARTY-NOTICES.txt`](THIRD-PARTY-NOTICES.txt).

## Trademarks

JetBrains®, IntelliJ IDEA®, and the IntelliJ Platform are trademarks or registered
trademarks of JetBrains s.r.o. This plugin is an independent project and is not affiliated
with, endorsed by, or sponsored by JetBrains s.r.o.

## Contributing

Contributor conventions and architecture rules live in [`CLAUDE.md`](CLAUDE.md); the design
is in [`docs/design.md`](docs/design.md). Read `CLAUDE.md` before making changes. The rule
that matters most: **this repo implements the Provenance log format, it does not author
it.** The format is pinned by conformance vectors, and loosening an assertion or editing a
vector to make a test pass is not a coding decision — if the format appears to need a
change, stop and ask.
