# Provenance Recorder for JetBrains

A JetBrains IDE plugin that records a tamper-evident `.provenance` log while a
student works on a course assignment. It is the JetBrains counterpart to the
[Provenance](https://github.com/) VS Code recorder: it produces a sealed
submission bundle in the **same format**, so the same Provenance analyzer and
server ingest and validate it regardless of which editor produced it. It ships
under the same **Provenance Recorder** name as the VS Code extension.

> **Status:** built and working, not yet Marketplace-published. `core/` (the
> format port) and the full `recorder/` wiring — activation, manifest
> verification, document/paste/VFS/terminal/git event capture, external-change
> detection, the hash chain, signed checkpoints, chain recovery, bundle seal,
> and disk-full degraded mode — have all landed. `core:test` and
> `recorder:test` are green (105 and 235 tests respectively), and sealed
> bundles produced by the plugin pass all 8 of the Provenance
> analysis-core validation checks end-to-end. A sideloadable
> plugin `.zip` builds today via `./gradlew :recorder:buildPlugin`. What
> remains is distribution: a
> signed Marketplace release needs operator secrets (Marketplace token,
> code-signing certificate, the real course public key) that only a human can
> supply — see "Production release" below.

## What it does

While active on an activated assignment workspace, the plugin records a
timestamped, hash-chained log of editing activity — document changes, file
open/save/close, pastes, edits made outside the IDE, and session metadata — and
seals it into a signed submission bundle at the end. The log is append-only and
tamper-evident; casual edits to it are detectable.

The plugin is **offline**: it makes no network calls during a session. It
activates **only** on a workspace containing a valid, course-signed
`.provenance-manifest`, and records only within that workspace.

## Relationship to Provenance

The log format — event envelope, hash chain, JCS canonicalization, ed25519
signing, and bundle/manifest shapes — is defined by the Provenance monorepo's
`log-core` package. This repo is an independent Kotlin implementation of that
same format, verified against golden conformance vectors so the two editors'
output stays byte-for-byte compatible. The format is a fixed contract this repo
implements rather than redesigns. See `docs/design.md` for the full design.

## Install

**Sideload (available now).**

```sh
./gradlew :recorder:buildPlugin
```

Then in the IDE: **Settings → Plugins → gear icon → Install Plugin from
Disk…**, and pick the `.zip` from `recorder/build/distributions/`. This is the
dev-key build — see "Production release" below for what changes in a signed
Marketplace release.

**JetBrains Marketplace (coming).** Not yet published. The Gradle wiring for a
signed release (`buildProd`/`publishProd`) is in place; only the operator
secrets are missing. See "Production release" below.

## Building

Kotlin + Gradle, using the IntelliJ Platform Gradle Plugin.

```sh
./gradlew :recorder:buildPlugin   # produce the sideload-able plugin .zip
./gradlew :recorder:runIde        # launch a sandbox IDE with the plugin loaded
./gradlew :recorder:test          # recorder unit + platform-fixture tests
./gradlew :core:test              # format unit + conformance tests
```

See "Install" above for how to load the built plugin into an IDE, and
"Production release" below for the signed Marketplace build.

## Production release (JetBrains Marketplace)

Marketplace is the intended primary distribution channel once a course is
ready to publish; the sideload `.zip` above is for early testing and
self-hosted course builds in the meantime. The production build embeds the
**real** course public key (so
the plugin trusts only manifests signed by the course offline key) and ships a
**signed** artifact. Every step that needs a real secret or is a one-way decision
is marked — **an agent cannot run those; a human operator must.** The Gradle wiring
is in place (`buildProd`/`publishProd`, `signPlugin`/`verifyPlugin`/`publishPlugin`);
only the secrets are missing.

### `extension_hash` (needed for the analyzer allowlist)

Every submission's `extension_hash` must be on the analyzer allowlist
(`packages/analysis-core/src/heuristics/config/known-good-extension-hashes.json` in
the monorepo) or it is flagged. Compute it from the built distribution:

```sh
./gradlew :recorder:computeExtensionHash   # → recorder/build/extension-hash.txt (64-hex)
```

It is a reproducible SHA-256 over the *extracted* plugin file tree (sorted
`<relpath>\0<bytes>`, the same algorithm the seal command uses at runtime), not a
hash of the `.zip` bytes. The **dev** build (checked-in dev key, unsigned sideload)
and the **prod** build (real course key, signed) produce **different** hashes —
embedding a different course key changes compiled bytes. Add the value for whichever
build students actually install. The dev-build hash is already in the allowlist for
local/testing installs; a real release needs its own entry (see below).

### One-time setup (REQUIRES OPERATOR SECRETS)

1. **Marketplace token.** Create/confirm a JetBrains Account and generate a Personal
   Access Token at <https://plugins.jetbrains.com/author/me/tokens>. Save it as
   `PUBLISH_TOKEN` in your CI secret store — it is shown only once.
   (<https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html>)
2. **Code-signing certificate + key**
   (<https://plugins.jetbrains.com/docs/intellij/plugin-signing.html>):
   ```sh
   openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
   openssl rsa -in private_encrypted.pem -out private.pem
   openssl req -key private.pem -new -x509 -days 365 -out chain.crt
   ```
   Store `chain.crt` contents as `CERTIFICATE_CHAIN`, `private.pem` contents as
   `PRIVATE_KEY`, and the passphrase as `PRIVATE_KEY_PASSWORD`. Never commit these.
3. **Plugin id.** The reverse-DNS id declared in `plugin.xml` is a permanent
   identity once published — Marketplace and auto-update channels key off it
   forever. Confirm it with the course before the first publish.
4. **The first publication must be uploaded manually** through the Marketplace web UI
   (per JetBrains docs); `publishPlugin` automation only works for subsequent versions
   of an already-registered plugin. Build the signed zip
   (`./gradlew :recorder:buildProd`, output under `recorder/build/distributions/`) and
   upload it by hand the first time.

### Every release (REQUIRES OPERATOR SECRETS)

```sh
export PROVENANCE_COURSE_PUBLIC_KEY_HEX=<64-hex production course public key>
export CERTIFICATE_CHAIN="$(cat chain.crt)"
export PRIVATE_KEY="$(cat private.pem)"
export PRIVATE_KEY_PASSWORD=<passphrase>
export PUBLISH_TOKEN=<Marketplace personal access token>

# Bump `pluginVersion` in gradle.properties first — Marketplace rejects duplicates.
./gradlew :recorder:buildProd     # embed key → build → sign → hash → revert key
./gradlew :recorder:publishProd   # buildProd + verifyPlugin, then publishPlugin
```

`buildProd` always reverts `CoursePublicKey.kt` to the dev key afterward (even on
failure), so the real key is never left in the working tree. After a release, copy
`recorder/build/extension-hash.txt` into the monorepo allowlist:

```sh
cd ../provenance
node scripts/update-extension-hash-allowlist.mjs --hash <hex-from-extension-hash.txt>
```

Every release needs its own allowlist entry, or its submissions get flagged.

## External-change detection — manual verification checklist

External-change detection (PRD §4.5) is the port's highest-risk subsystem. Its
direction, dedup (`isFromSave`), payload shape, create/delete/reload paths, and the
feeder/reload interaction are all covered by the headless `:recorder:test` suite
against a real `LocalFileSystem` temp dir. The items below **cannot** be exercised
headlessly — they need a running windowed IDE (`:recorder:runIde`) — and are
**unchecked until run manually at least once** on the IDE versions the plugin targets:

- [ ] **Frame-activation refresh fires for files changed while unfocused.** In a
  `runIde` sandbox with a manifest-activated project: alt-tab away, edit a watched file
  in an external editor (or `echo >>` from a terminal), alt-tab back, and confirm an
  `fs.external_change` appears in the session log within a few seconds — no manual
  "Reload from disk" needed.
- [ ] **Native watcher while the IDE stays focused.** Edit a watched file externally
  (second-monitor terminal, or Claude Code in an integrated terminal panel) *without*
  switching focus; confirm the native OS file watcher alone delivers the event, since
  students may never alt-tab away.
- [ ] **Latency.** Time the gap between an external write and the event's `wall`
  timestamp, both same-window-terminal and alt-tab; confirm it is not multi-second
  (would look suspicious in replay).
- [ ] **"Synchronize files on frame activation" disabled.** Confirm the native watcher
  still covers detection and it does not silently degrade to "only on next IDE restart".
- [ ] **`isFromSave()` tags every real editor save.** Confirm a normal Ctrl+S in the
  running IDE produces VFS events with `isFromSave() == true` for the saved file across
  target IDE versions (the `SavingRequestor` opt-in is an implementation detail, not a
  version-pinned contract; `ExternalChangeTimingTest` will catch a regression when
  next run against a newer platform).
- [ ] **Network/container filesystems** (note only): if course infra ever runs student
  IDEs against a network-mounted or containerized FS, confirm the native watcher works
  there — a known IntelliJ weak spot, unrelated to this plugin's code.

## Documentation

- `docs/design.md` — the approved architecture and design.
- `CLAUDE.md` — conventions and standing instructions for development.
- The recorder product spec (`docs/prd.md`) lives in the Provenance monorepo.

### Conformance

`core/`'s output is verified byte-for-byte against Provenance's `log-core` via
vectors in `core/src/test/resources/conformance/` (plus a golden sealed bundle).
These are generated, not hand-authored — regenerate them from the monorepo:

```sh
cd ../provenance
node --experimental-strip-types tools/export-conformance-vectors.ts \
  --out ../provenance-jetbrains-recorder/core/src/test/resources/conformance
```

Never hand-edit a vector file. A failing conformance test after regenerating means
the format has drifted — fix `core/`'s implementation, never the vectors.

## Trademarks

JetBrains®, IntelliJ IDEA®, and the IntelliJ Platform are trademarks or
registered trademarks of JetBrains s.r.o. This plugin is an independent
project and is not affiliated with or endorsed by JetBrains s.r.o.

## License

See [`LICENSE`](LICENSE). This plugin bundles a small number of third-party
libraries in its distributed `.zip` (Bouncy Castle, the Kotlin standard
library, kotlinx.serialization, JetBrains `annotations`, and
`java-json-canonicalization`); their licenses and required notices are
reproduced in [`THIRD-PARTY-NOTICES.txt`](THIRD-PARTY-NOTICES.txt).
