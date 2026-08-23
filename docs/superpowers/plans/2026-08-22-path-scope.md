# Implementation plan — path scope (provjet port)

**Spec:** [`../specs/2026-08-22-path-scope-design.md`](../specs/2026-08-22-path-scope-design.md)
**Upstream spec:** monorepo `docs/superpowers/specs/2026-08-22-path-scope-design.md`
**Branch:** `feat/manifest-2.0-trust-chain`, base `e9bc2d6`

---

## Standing rules for every task

- **Escalate, never guess.** Anything not settled by the spec or this plan goes
  back to the manager. Inventing an answer is the failure mode this port is
  most exposed to.
- **Never weaken an assertion or edit a vector to make a test pass.** A failing
  conformance test means the port is wrong, or the vectors are stale and that is
  a cross-repo decision.
- **Read before writing.** Read the file, and read its tests, before editing.
- **The monorepo at `/Users/aaryanmehta/projects/provenance` is READ ONLY.**
- Commits: `git commit --no-gpg-sign`, conventional prefix, explicit pathspec
  after `--`, **no `Co-Authored-By` trailer and no Claude attribution**. Never
  `git stash` or `git clean` — the tree may hold unrelated work.
- Verify with real commands and paste real output. `./gradlew :core:test` for
  `core`, `./gradlew :recorder:test` for `recorder`. There is no lint task.

## Execution order and isolation

Dependencies are strong and mostly linear, so tasks run **sequentially in the
main working tree**, one writer at a time. Worktrees would buy little
parallelism here and would cost Gradle re-resolution plus near-certain conflicts
on the two files (`Manifest.kt`, `SealBundle.kt`) that several tasks touch.

```
T1  core: PathScope.kt + vectors            (no deps)
T2  core: Bundle.kt role + scope_capped     (no deps; may run beside T1)
T3  core: Manifest.kt ignore + attachments  (needs T1)
T4  recorder: registry + file_scope         (needs T1, T3)
T5a recorder: close the shipped false-accusation path  (no deps; cherry-pickable)
T5b recorder: SealBundle collects by walking (needs T1, T2, T3, T4, T5a)
T6  recorder: RollingSeal collection        (needs T5b)
```

Task 3 splits around the vector blocker: **T3a** adds the fields and
`scopeFromManifest` without touching `buildSignedPayload` (no vector impact, so
it can run immediately and unblocks T4/T5b); **T3b** makes them required at 2.0
and adds them to the signed payload, and waits for the regenerated
`manifest-v2.json`. T3b must land before this branch is pushed.

---

## Task 1 — `core/PathScope.kt` and the conformance vectors

**Files:** create `core/src/main/kotlin/dev/provenance/core/PathScope.kt`;
copy `core/src/test/resources/conformance/path-scope-vectors.json`; create
`core/src/test/kotlin/dev/provenance/core/PathScopeTest.kt`; extend
`core/src/test/kotlin/dev/provenance/core/ConformanceTest.kt`.

Port `packages/log-core/src/path-scope.ts` — types (`ScopeEntryProblem`,
`ResolvedScope`, `PathRole`), the hard-exclusion constants and `isHardExcluded`,
`isExactEntry`, `matchesScopeEntry`, `matchesAnyScopeEntry`, `resolvePathRole`,
`validateScopeEntry`. Port the docstrings' reasoning, condensed.

The problem `kind`s must map to the vector file's strings: `empty`,
`whitespace`, `backslash`, `absolute`, `dot_segment`, `empty_segment`,
`bad_wildcard`, `forbidden_char`. The roles likewise: `excluded`, `ignored`,
`attachment`, `reviewed`, `unscoped`.

Copy the vector file **byte-for-byte** from `tools/path-scope-vectors.json`; it
is an exported artifact, never hand-authored here.

The conformance test drives all four sections (`match`, `editorGlobHazards
.cases`, `validate`, `role`) plus the count guard, following `ConformanceTest
.kt`'s existing `@Nested` idiom.

See spec §3.1 for the three ordering details that decide correctness.

**Verify:** `./gradlew :core:test`. Report the vector assertion count.
Additionally confirm empirically that Kotlin's `trim()` and JS's agree on the
vector inputs, rather than assuming it.

---

## Task 2 — `core/Bundle.kt`: `role` and `scope_capped`

Add `SubmissionFileEntry.role: String?` (`"reviewed"` | `"attachment"`, absent
reads as `reviewed`) and `BundleManifest.scopeCapped: Boolean`.

Both additive-optional. **No `format_version` bump** — follow `final`'s
precedent, which is already in this file.

In `toJsonText`, emit `role` per entry, and emit `scope_capped` **only when
true**, using the existing `if (flag) put(...)` idiom. Spec §3.3 is why: absent
and `false` canonicalize to different bytes, and the canonical bytes are the
signed message.

`validateBundleManifestShape` gains two checks: `role`, when present, is one of
the pair; `scope_capped`, when present, is a boolean.

Existing bundle-manifest conformance vectors must still pass unchanged — that is
the proof the additions are genuinely additive.

**Verify:** `./gradlew :core:test`.

---

## Task 3 — `core/Manifest.kt`: `ignore` and `attachments`

Add both to `Manifest`, **required at 2.0**, and to `buildSignedPayload`'s 2.0
branch. Add `checkScopeList` and run all three lists through it at 2.0 only.
Add `scopeFromManifest`.

**The 1.x branch is not touched, and `checkScopeList` is not called at 1.x.**
`manifest-v2.json`'s `legacy_no_format_version` case pins the 1.x bytes; it must
still pass byte-identically.

> **BLOCKED pending the regenerated vector file.** The monorepo's
> `tools/export-conformance-vectors.ts` was not updated by the upstream change,
> so the exported `manifest-v2.json` in this repo carries a `valid_2_0` block
> with a ten-key `canonical_json` and no `ignore` / `attachments`. Under the new
> required-fields rule that manifest does not parse, and its pinned signature is
> over a payload `buildSignedPayload` can no longer produce. Implementing this
> task faithfully turns the existing conformance suite red for a reason that is
> not this port's fault. Do not hand-author a replacement vector and do not
> weaken the assertion; wait for the regenerated file.
>
> Ruled upstream: the monorepo is fixing the exporter (auditing the whole file,
> not just the one construction), regenerating, and adding a `tools/` test that
> asserts every emitted 2.0 manifest round-trips through `parseManifestValue`
> and `verifyManifestChain` — the exporter had no npm script and no output test,
> which is why it went stale invisibly. The regenerated `manifest-v2.json` will
> be handed over; copy it in verbatim.

**Verify:** `./gradlew :core:test`.

---

## Task 4 — recorder: live membership and `file_scope`

`ExpectedContentRegistry` takes a `ResolvedScope` instead of a `List<String>`.
`isWatched` becomes: already-tracked → true; else `resolvePathRole(...) ==
reviewed` → subject to the cap; else false.

`EXPECTED_CONTENT_MAX_FILES = 512`, with a `capHit()` accessor. The flag is set
as a deliberate side effect of the membership check — that is the only moment
the cap is observable, and a path that was never in scope must not set it.

Update the construction site (`ExternalChangeCoordinator.kt:49`) and thread the
`ResolvedScope` from the activated manifest via `scopeFromManifest`.

`RecorderContext.resolveFileScope` adopts the upstream rule: `watched` carries
only exact entries, and `complete` is false if any entry is non-exact **or** the
exact entries exceed 4096. Spec §3.5 has the downstream reason this is binding.

Note that `VfsExternalChangeListener` and `ExternalChangeEngine` need no change
of their own — they gate on `isWatched`, which now carries the new semantics.
Confirm that rather than assume it.

**Tests:** live admission mid-session (a path not in scope at construction that
a rule matches later); the cap at 512 refusing an in-scope path and setting
`capHit`; an out-of-scope path not setting it; hard exclusion beating the course
lists; `complete: false` for a rule-bearing scope.

**Verify:** `./gradlew :recorder:test`.

---

## Task 5a — recorder: close the shipped false-accusation path

**This is a bug fix, not part of the feature, and it gets its own commit.**

`SealBundle.kt`'s reviewed-file read and `RollingSeal.kt`'s `readSubmissionFile`
both do `catch (_: Exception) -> status "missing"`. Every failure mode becomes an
affirmative claim that the student did not submit the file: a permission error,
a symlink loop, an I/O error, and — the likely one — an exact entry naming a
**directory**, which is the ordinary staff typo `src` instead of `src/`.

Measured on this JVM (JDK 25, macOS, APFS): reading a directory throws
`java.io.IOException: Is a directory`, an `Exception`, so it lands in that catch.
A genuinely absent file throws `java.nio.file.NoSuchFileException` — from both
`readAllBytes` **and** `toRealPath`, which is the equivalence that makes failing
closed lossless. `Files.readAllBytes` on a FIFO **blocks indefinitely**;
`Files.isRegularFile` returns false for one.

The existing catch carries a comment reasoning carefully about not widening to
`Throwable`. That guard is real and points the wrong way — it never considered
an ordinary `IOException` reaching the same line with the same consequence.

**This bug is in the published build** (Marketplace plugin 32944, `v0.2.1`), so
this commit must be written to cherry-pick cleanly onto `main` without any
path-scope dependency. Do the fix against the current exact-path list; Task 5b
layers the walk on top.

Introduce the shared single-path read described in Task 5b's "the read", and use
it from both seals. `missing` becomes reachable from exactly one condition.
Everything else drops and is disclosed through `SealResult.Ok`'s existing
boolean-plus-`droppedDescriptions()` surface, preserving the fact partition in
spec §3.4.

**Regression tests, each failing before the fix:** an exact entry naming a
directory is dropped, never `missing` (this is the staff-typo case and the
likeliest to recur — name it explicitly); an unreadable file is dropped, never
`missing`; a symlink resolving outside the workspace is dropped and disclosed,
never `missing`; a genuinely absent file is still `missing`.

---

## Task 5b — recorder: `SealBundle.kt` collects by walking

This is the largest task and carries the R2 surface. Read spec §4 in full first,
including the five invariants and the two mechanisms kept on their account.

Introduce a shared workspace walk and a shared single-path read, used by both
seals — two copies that must agree about hard exclusions is exactly the
divergence path scope exists to avoid.

**The walk:** every file under the root as workspace-relative forward-slash
paths. Prune hard-excluded directories **at the directory level, by segment
name** (`.git`, `.provenance`) — walking a real `.git/` to discard it is the
difference between a seal that feels instant and one that does not. Do not
follow symlinks; record the declined links so the caller can disclose the drop.
Report whether any directory refused to list.

**The read:** classify one path as present-with-hash, genuinely absent,
unreadable, or resolved-outside-the-workspace. Realpath **both** sides for
containment. Gate on regular-file before reading. `missing` only for the one
condition that means the file does not exist.

**Collection**, mirroring upstream's three-part shape:

1. Walk; assign each path a role via `resolvePathRole`; keep `reviewed` and
   `attachment`. Anything that fails to read here is **dropped with its own
   distinct fact**, never recorded `missing` — this path was discovered by the
   walk, not asserted by the manifest.
2. Every **exact** `track` entry the walk did not already sight gets its own
   read attempt, in `track` order. This loop is the only one that may mint
   `missing`, and only for its own genuine-absence condition. Apply the
   segment-based hard-exclusion check here too, against the manifest string,
   because this loop reads by string and never passes through the walk's
   pruning.
3. Disclose in-scope symlinks the walk declined and this loop did not rescue.

Keep the sighting set from step 1 built from **sightings, not successful
reads** — a 1.x manifest's `files_under_review` is nothing but exact entries, so
a file the walk saw but could not reopen would otherwise fall through to step 2
and mint a false `missing` there.

Emit `role` per entry, and `scope_capped` when any session reports capped.

**Disclosure:** reproduce the five distinct facts in spec §3.4's table through
this repo's own seal-result surface, in idiomatic Kotlin naming. The vocabulary
is free; the partition is not.

**Verify empirically and report, do not reason about:**
- whether `Files.readAllBytes` on a FIFO blocks on this JVM;
- whether `Path.toRealPath()` canonicalizes filesystem case on macOS. **If it
  does not, stop and escalate** — a dedupe that does not dedupe puts two records
  for one file into a signed manifest, and that is a cross-repo decision.

**Tests:** mirror the upstream `seal.test.ts` "path scope at seal time" block —
rule-matched folder scope; an absent exact entry marked `missing` while rule
entries say nothing; `scope_capped` present when true and **absent** when false;
hard-exclusion pruning including a nested sibling `.provenance/`; an unreadable
walk-discovered file dropped and disclosed, never `missing`; an exact entry
naming a **directory** dropped, never `missing`; a symlink pointing outside the
workspace dropped and disclosed, never `missing`; an unreadable directory
disclosed.

**Verify:** `./gradlew :recorder:test`.

---

## Task 6 — recorder: `RollingSeal.kt` learns the same scope

Same collection logic as Task 5, via the shared walk and read. `scope_capped`
spread the same way. The rolling seal has no user-facing "seal now" action to
attach a warning surface to; if this repo's `RollingSealResult` has no
equivalent, do not invent one — document the trade-off in the module docstring
instead, exactly as upstream did. The safety property holds regardless.

Rolling seals run on every checkpoint, so the walk runs far more often here than
in the classic seal. Note any perf finding rather than silently optimizing.

**Verify:** `./gradlew :recorder:test`, then `./gradlew build` as the final gate.
