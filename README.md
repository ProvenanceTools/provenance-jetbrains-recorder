# Provenance Recorder for JetBrains

A JetBrains IDE plugin that records a tamper-evident `.provenance` log while a
student works on a course assignment. It is the JetBrains counterpart to the
[Provenance](https://github.com/) VS Code recorder: it produces a sealed
submission bundle in the **same format**, so the same Provenance analyzer and
server ingest and validate it regardless of which editor produced it.

> **Status:** in progress. `core/` (the format port) and the `recorder/` plugin
> scaffold — activation, manifest verification, and the recording status-bar
> widget — have landed. Document/paste/VFS event wiring and bundle seal are next.

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
output stays byte-for-byte compatible. See `docs/design.md` for the full design.

## Building

Kotlin + Gradle, using the IntelliJ Platform Gradle Plugin.

```sh
./gradlew :recorder:buildPlugin   # produce the sideload-able plugin .zip
./gradlew :recorder:runIde        # launch a sandbox IDE with the plugin loaded
./gradlew :recorder:test          # recorder unit + platform-fixture tests
./gradlew :core:test              # format unit + conformance tests
```

As of Plan 3, `:recorder:buildPlugin` / `:recorder:runIde` / `:recorder:test`
are real. The plugin activates only on a workspace with a valid, course-signed
`.provenance-manifest` at the root (PRD §4.1) — on any other folder it does
nothing observable. To sideload for manual testing: Settings → Plugins → gear
icon → Install Plugin from Disk → the `.zip` from `recorder/build/distributions/`.
Marketplace publishing and the production course-key embedding are Plan 9.

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
pinned vectors in `core/src/test/resources/conformance/vectors.json`. A failing
conformance test means the format has drifted — fix the implementation, never
the vectors.

## License

TBD.
