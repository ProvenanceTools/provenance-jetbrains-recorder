# Nested manifest discovery + concurrent multi-assignment recording — JetBrains recorder

**Repo:** `provenance-jetbrains-recorder` (provjet, Kotlin/Gradle IntelliJ plugin)
**Date:** 2026-07-20
**Status:** Approved design, ready for implementation plan (superpowers SDD)
**Sibling specs:** VS Code (`provenance` monorepo `packages/recorder`), Neovim (`provenance-neovim-recorder`) — same feature, editor-specific mechanics.

## Problem

The plugin activates once per `Project` and reads `.provenance-manifest` only as a **direct child** of `project.guessProjectDir()` (non-recursive). Recording is scoped to that single root, and `RecorderSessionManager` owns exactly one live session. Students keep assignments nested inside a larger course project (e.g. `61a/cats/`, `61a/hog/`); opening `61a/` finds no manifest at the base dir and records nothing — the identical limitation to the VS Code recorder.

## Goals

- Opening a project whose base dir (or a subtree) contains one or more assignment folders activates recording for each discovered assignment.
- Multiple assignments record **concurrently, each as its own session**, writing into its **own** `<assignmentRoot>/.provenance/`.
- Seal (`PrepareSubmissionBundleAction`) lets the student **choose which assignment** to bundle when more than one is active.
- Terminal/git events are attributed **by path** to the owning session; dropped when no assignment owns them.
- All integrity guarantees preserved; still platform-common APIs only (no language/module-specific deps beyond what's already used).

## Non-goals

- No change to log format, manifest schema, JCS, hash chain, or signing/verification.
- No recording of files outside every assignment root.

## Locked decisions

1. **Track separately.** N verified manifests → N concurrent sessions (a session is bound to one manifest signature).
2. **Per-assignment `.provenance/`.** Each session's output dir is `<assignmentRoot>/.provenance/`. This already falls out of `workspaceRoot.resolve(".provenance")` once each session gets its own root.
3. **Seal selector.** >1 active session → the action prompts (JetBrains chooser / popup) for which assignment; exactly one → no prompt.
4. **Terminal/git attribution = by path.** Route to the session whose assignment root contains the terminal cwd / git operation path; drop if none owns it. This is the sharpest change here because terminal/git are currently single-slot project-wide callbacks.
5. **Nearest-enclosing ownership.** A file belongs to the nearest-ancestor verified-manifest directory.

## Current architecture (seams to change)

| Concern | Location |
|---|---|
| Activation (`postStartupActivity`, per project) | `recorder/src/main/resources/META-INF/plugin.xml:126`; `recorder/.../activation/RecorderActivationActivity.kt:21-42` |
| Root = `project.guessProjectDir()` | `activation/ManifestLoader.kt:34-40`; `session/RecorderSessionManager.kt:77-83` |
| Manifest lookup = `baseDir.findChild`, non-recursive | `activation/ManifestLoader.kt:17-31` |
| Single-session assumption (`var activeSession`, `check(activeSession == null)`) | `session/RecorderSessionManager.kt:65-66, 76, 117` |
| Single manifest state | `activation/RecorderState.kt:13-15` |
| `.provenance/` from single root | `RecorderSessionManager.kt:77-83` |
| Recordability filter (`normalized.startsWith(root)`) | `wiring/RecordabilityFilter.kt:33-49` |
| Global doc multicaster filtered per root | `wiring/DocWiring.kt:157-164` (`:81` app-level listener) |
| External-change coordinator (single root + filesUnderReview) | `watch/ExternalChangeCoordinator.kt:39,54,64,79` |
| Terminal/git single-slot emit seams | `RecorderSessionManager.kt:203-211` (`RecorderTerminalState`, `RecorderGitState`) |
| Seal seals `activeSession` (singular) | `RecorderSessionManager.kt:258`; `PrepareSubmissionBundleAction` |

## Design

### 1. Discovery

- The manifest loader is already factored into a path-agnostic core `loadAndVerifyManifest(baseDir: VirtualFile, ...)`. Add a **recursive discovery** pass over the project that collects every directory containing a `.provenance-manifest`/`provenance-manifest`, using a platform-common VFS walk (`VfsUtilCore.visitChildren` / `VfsUtil.iterateChildrenRecursively`), pruning `.git`, `.provenance/`, build output, and other heavy dirs, with a depth cap. Verify each hit with the existing `evaluateManifestText`. Skip failures.
- **Idiomatic alternative to evaluate in the plan:** JetBrains projects can have multiple **content roots / modules**; mapping each content root to a discovery scope may be cleaner than a raw VFS walk. The plan should pick one approach and justify it, but must still discover **nested** manifests within a root (a course project is usually one content root with assignments beneath).
- React to project structure / VFS changes to add/remove sessions best-effort.

### 2. Session registry (one → many)

Convert `RecorderSessionManager` from a single `var activeSession` to a `Map<Path, ActiveSession>` keyed by assignment root. Each entry owns its own context (bound to that manifest), writer, `.provenance/` dir, `filesUnderReview` model, and external-change coordinator, plus its own child `Disposable`. `start`/`startFromActivation` become per-root; the `check(activeSession == null)` invariant becomes per-root. `RecorderState` becomes a **set** of active manifests (or is derived from the registry); the status-bar widget reflects "N assignments recording".

### 3. Scoping / event routing

- `RecordabilityFilter` already takes `root` as a value. Replace the single global doc listener's "filter by the one root" with a **dispatcher**: for each document event, find the session whose assignment root is the nearest ancestor of the file and deliver to that session only; drop if none. Avoid N independent global listeners each doing full per-keystroke work — one listener + a router is preferred.
- Each event goes to exactly one session; no cross-session leakage.

### 4. Seal selector

- `PrepareSubmissionBundleAction`: if one active session, seal it. If more than one, show a chooser (`JBPopupFactory` list / `Messages` popup) of active assignments (label = `assignment_id` + relative dir), defaulting to the assignment owning the currently focused editor, and seal the chosen one.

### 5. Terminal/git routing (by path) — the hard part

- Today `RecorderTerminalState.emit*` and `RecorderGitState.emit` are single nullable callbacks a session sets; a second session would clobber them. Replace these single slots with a **router** owned by the session manager: terminal/git events carry (or can resolve) a path (terminal cwd, git repo root); the router looks up the owning session by nearest-ancestor root and delivers there, dropping if none owns it. Terminal/git wiring registers **once** at the project level and fans **into the router**, not into a specific session.

## Integrity invariants (must hold)

- Log format, manifest schema, JCS, hash chain, signing untouched.
- Each session chains independently and is bound to its own manifest signature.
- No file outside all assignment roots recorded; no event double-recorded.
- A failing-signature manifest produces no session and does not affect others.
- CLAUDE.md privacy invariant preserved: "no event escapes its assignment root."

## How we confirm it works (acceptance criteria)

JUnit/plugin tests (the repo's existing test harness), plus the golden conformance vectors:

1. **Discovery:** a project with two nested manifests → two sessions; manifest at base dir → one; none → none; a bad-signature manifest skipped while a sibling valid one activates.
2. **Routing:** edits under `cats/` recorded by the cats session only; under `hog/` by hog only; under the bare parent recorded by none; nearest-enclosing wins.
3. **Per-`.provenance/`:** each session writes its own `<root>/.provenance/`.
4. **Seal selector:** one active → no prompt; two active → chooser, seals only the chosen assignment; produced bundle verifies against the analyzer/conformance path.
5. **Terminal/git:** command with cwd under cats → cats session; command at the parent (no owner) → dropped; the second session does not clobber the first's terminal/git wiring.
6. **Regression:** opening the assignment as the project base dir behaves as before; single-session path unchanged.

"Works" = the repo's Gradle build + `check`/tests green (e.g. `./gradlew build test`), plus conformance vectors passing (sign → analyzer verifies). Report the exact commands run and their output.

## Rollout

- Feature branch off `main` (e.g. `feat/nested-manifest-discovery`). This spec is the branch's first commit.
- Small, reviewable commits per SDD task. Do not merge or open a PR — stop after verification and report. Flag the multi-session refactor of `RecorderSessionManager` + the terminal/git router as the highest-risk area for careful review.
