# Provenance Recorder for JetBrains

A JetBrains IDE plugin that records a tamper-evident `.provenance` log while a
student works on a course assignment. It is the JetBrains counterpart to the
[Provenance](https://github.com/) VS Code recorder: it produces a sealed
submission bundle in the **same format**, so the same Provenance analyzer and
server ingest and validate it regardless of which editor produced it.

> **Status:** pre-implementation. The architecture is designed
> (`docs/design.md`); code has not landed yet.

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
./gradlew buildPlugin   # produce the distributable plugin .zip
./gradlew runIde        # launch a sandbox IDE with the plugin loaded
./gradlew test          # unit + conformance tests
```

(Build tasks are placeholders until the Gradle project lands; see
`docs/design.md` §8 for the build sequence.)

## Documentation

- `docs/design.md` — the approved architecture and design.
- `CLAUDE.md` — conventions and standing instructions for development.
- The recorder product spec (`docs/prd.md`) lives in the Provenance monorepo.

## License

TBD.
