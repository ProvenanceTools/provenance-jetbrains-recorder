# Path scope — the provjet port

**Repo:** `provenance-jetbrains-recorder` (branch `feat/manifest-2.0-trust-chain`)
**Date:** 2026-08-22
**Status:** Design, derived from the monorepo's approved spec. Binding for this port.
**Upstream spec (the authority):** `provenance` monorepo,
[`docs/superpowers/specs/2026-08-22-path-scope-design.md`](../../../../provenance/docs/superpowers/specs/2026-08-22-path-scope-design.md)
**Upstream plan:** monorepo `docs/superpowers/plans/2026-08-22-path-scope.md`, worklist item 9.
**Conformance gate:** monorepo `tools/path-scope-vectors.json`, copied verbatim to
`core/src/test/resources/conformance/path-scope-vectors.json`.

---

## 0. How to read this

The upstream spec is the product authority and is **not restated here**. This
document says only what the port needs that the upstream spec does not: what
already exists in this repo, where this repo's structure and the upstream
implementation diverge, and which of the upstream's behaviours are binding
versus which are free to be re-expressed in idiomatic Kotlin.

Read the upstream spec first. §3 (format), §3.4 (precedence), §4 (recorder),
§5 (bundle disclosure) and §9 (the false-accusation review) are the sections
this port implements.

Two standing rules govern every choice, inherited unchanged:

> **R1. Fail toward surfacing evidence.** Anything that cannot be evaluated
> soundly becomes a visible fact with a reason, never a silent skip.
>
> **R2. Never manufacture a tamper finding against an innocent student.**

R2 is the binding constraint. §4 below is entirely about it.

---

## 1. Why this port is urgent

`ignore` and `attachments` are **required** fields in the Manifest 2.0 signed
payload as of the upstream change. This repo's `buildSignedPayload` does not
emit them, so:

- a manifest signed by the new staff tooling canonicalizes to bytes this repo
  cannot reproduce,
- its signature therefore fails verification here,
- and a manifest whose signature does not verify **does not activate the
  recorder**.

The monorepo is ahead of this repo on a signed format contract. Until this port
lands, any course that signs with current tooling gets a JetBrains recorder that
silently does not record. That is the whole urgency; everything else in this
document is the correctness work that has to come with it.

---

## 2. Ground truth — what exists in this repo today

| Piece | State | Evidence |
| --- | --- | --- |
| Manifest fields | `filesUnderReview: List<String>`, exact paths only | `core/…/Manifest.kt:52` |
| 2.0 signed payload | 10 keys; **no `ignore`, no `attachments`** | `core/…/Manifest.kt:248` `buildSignedPayload` |
| 1.x signed payload | 4 keys, untouched | same |
| Watch-set membership | `Set<String>` built once at construction, `Set.contains` | `recorder/…/state/ExpectedContentRegistry.kt` |
| Registry construction | from `filesUnderReview`, one site | `recorder/…/watch/ExternalChangeCoordinator.kt:49` |
| File watching | `BulkFileListener` on `VFS_CHANGES`, filtered by `isWatched` | `recorder/…/watch/VfsExternalChangeListener.kt:54` |
| Editor glob pre-filter | **none — there is no glob anywhere in this repo** | see §3.1 |
| `file_scope` disclosure | `watched` + `complete`, capped at 4096 | `recorder/…/session/RecorderContext.kt` |
| Bundle manifest | `SubmissionFileEntry(path, status, sha256)`; no `role` | `core/…/Bundle.kt:22` |
| Bundle manifest | `BundleManifest`; no `scope_capped` | `core/…/Bundle.kt:38` |
| Omit-when-false idiom | `if (isFinal) put("final", true)` | `core/…/Bundle.kt` `toJsonText` |
| Classic seal file collection | reads `filesUnderReview` by string; **no workspace walk** | `recorder/…/commands/SealBundle.kt:184` |
| Rolling seal file collection | same shape | `recorder/…/io/RollingSeal.kt` |
| Hard-excluded dir pruning | exists for manifest *discovery* only | `recorder/…/activation/ManifestDiscovery.kt:18` |

Three properties of today's code are load-bearing for what follows.

**The `final` field already establishes the omit-when-false idiom.**
`Bundle.kt`'s `toJsonText` writes `if (isFinal) put("final", true)` with a
comment explaining that the canonical bytes *are* the signed message. `role` and
`scope_capped` follow that precedent exactly; §3.3 is why this is not optional.

**`FILE_SCOPE_MAX_ENTRIES = 4096` already exists here** with the same value as
the monorepo. Only the resolver changes.

**This repo has no glob engine.** Not in the watcher, not in the scope check,
nowhere. That is a considerable advantage for this port and §3.1 explains why.

---

## 3. What is binding, and what is free

The distinction that matters: **anything that ends up inside JCS-canonicalized
signed bytes must match exactly. Everything else must match in behaviour, and
may be re-expressed idiomatically.**

### 3.1 Binding: the matcher, pinned by vectors

`matchesScopeEntry`, `validateScopeEntry`, `resolvePathRole`, `isExactEntry`,
`isHardExcluded` and the hard-exclusion constants port to
`core/…/PathScope.kt`, and are pinned by
`core/src/test/resources/conformance/path-scope-vectors.json` — copied verbatim
from the monorepo, never hand-authored here, per this repo's standing rule that
conformance vectors are exported artifacts.

Three ordering details decide correctness and are the things a port gets wrong:

1. `matchesScopeEntry` tests the **directory form first**, then the suffix form,
   then equality. That order is precisely why `*.java/` is a dead entry and why
   `validateScopeEntry` must reject it.
2. `validateScopeEntry`'s checks run in a fixed order. `"*/"` resolves to
   `bad_wildcard` and `"/"` to `absolute` only under that order.
3. The segment check runs on the **path part only**: strip a leading `*`, then
   strip one trailing `/`, then split. Getting this wrong rejects `src/`.

**Kotlin/JS agreement.** Kotlin's `String` comparison and `startsWith` /
`endsWith` operate on UTF-16 code units, the same as JavaScript's, so the
matcher agrees for all inputs, not merely ASCII. `String.trim()` trims by
`Char.isWhitespace()` where JS `trim()` uses its own whitespace set; the two
agree on every character the vectors exercise, and scope entries reaching this
function have already been constrained by the manifest parser. Verified, not
assumed — see the plan's Task 1 verification step.

**§4.2's editor-glob hazard does not arise here, and the vectors still must
pass.** The upstream rule is that a folder entry becomes a wide glob handed to
the editor's watcher, and that the watcher's verdict is a coarse pre-filter that
must be re-checked against `matchesScopeEntry` before anything is emitted. This
repo hands nothing to a glob: `VfsExternalChangeListener` receives *every* VFS
event under the project and filters each one through `ExpectedContentRegistry
.isWatched`. So provjet is structurally on the safe side of §4.2 — its watcher is
already maximally coarse and ours is already the only matcher.

That is a reason to keep the `editorGlobHazards` vectors, not to skip them. They
assert that paths a permissive watcher would plausibly deliver (`src` for entry
`src/`, `SRC/Main.java`, `notes.java.bak`) are *rejected*. Since this repo's
watcher delivers exactly such paths, those vectors test a live code path here
rather than a hypothetical one.

### 3.2 Binding: the 2.0 signed payload

`buildSignedPayload`'s 2.0 branch gains `ignore` and `attachments`. Both are
**required** at 2.0 — absent means the manifest does not parse, which means the
recorder does not activate. That severity is intended upstream and is not
softened here.

`parseManifestValue` gains the upstream's `checkScopeList` for all three lists
at 2.0: array-of-strings, and every entry through `validateScopeEntry`.

**The 1.x branch is not touched.** Archived 1.x signatures must keep verifying
byte-for-byte, and `manifest-v2.json`'s `legacy_no_format_version` case pins
that here. At 1.x the new entry forms do not exist and are not an error: an
entry ending in `/` means a file literally named `src/`, which matches nothing —
exactly today's behaviour. `checkScopeList` is **not** called at 1.x.

`scopeFromManifest(manifest): ResolvedScope` is the only supported way to build
a scope, defaulting both new lists to empty so a 1.x manifest resolves every
path to `reviewed` or `unscoped` exactly as it always has.

### 3.3 Binding: the two bundle-manifest fields

`SubmissionFileEntry.role: "reviewed" | "attachment"` (absent reads as
`reviewed`) and `BundleManifest.scope_capped: Boolean` (absent means "this
recorder does not report"). Both additive-optional, **no `format_version`
bump** — following `final`'s precedent.

`scope_capped` is emitted **only when true**. An absent key and a `false` value
canonicalize to different bytes and therefore hash differently, and the signed
message must stay byte-identical to what an uncapped session has always
produced. Use `Bundle.kt`'s existing `if (flag) put(...)` idiom, never a
nullable-emitting-null one.

`validateBundleManifestShape` gains the two matching checks: `role`, when
present, must be one of the pair; `scope_capped`, when present, must be a
boolean.

### 3.4 Binding as behaviour, free in vocabulary: the seal

The upstream seal accreted four fix rounds of drop disclosure on top of the
path-scope change. Its `SealWarnings` flags never enter the bundle manifest or
any signed artifact — they feed a user-facing notification and nothing else.
So this port reproduces the **facts**, in idiomatic Kotlin naming, through this
repo's own seal-result surface. It does not mirror the upstream field names for
their own sake.

**But the partition of facts is binding.** A dropped entry must remain
attributable to *which* reason it was dropped for. Collapsing "resolved outside
the workspace" into "could not be read" loses information staff need — those are
different facts about a student's submission — and so does folding away
"dropped as a duplicate of an already-sealed file". The vocabulary is free; a
single `filesDropped` boolean is not.

The distinct facts, all of which must survive:

| Fact | Meaning to staff |
| --- | --- |
| unreadable in-scope file | the file's existence is known-true or undetermined; its bytes are not in the bundle |
| unreadable in-scope directory | a whole subtree is absent from the bundle, and that is not evidence of anything |
| out-of-workspace path rejected | the path is there, and we refuse to read it because we cannot vouch for where it points |
| duplicate entry dropped | the bytes are sealed under a different spelling; this exact manifest claim vanished |
| in-scope symlink skipped | the walk declined to follow it; nothing was sealed under this path |

### 3.5 Binding: `file_scope.complete`

`file_scope` sits inside a hash-chained `session.start`, so it is binding.
`resolveFileScope` adopts the upstream rule exactly:

- `watched` carries only the **exact-path** entries,
- `complete` is false if **any** entry is non-exact, or if the exact entries
  exceed `FILE_SCOPE_MAX_ENTRIES` (4096, unchanged).

The downstream reason is worth recording. The analyzer reads `complete: false`
as "absence from this list does not prove the file was unwatched". It separately
reads a `complete: true` on a rule-bearing scope as a tell that the recorder
predates path scope and never applied the rules, and degrades its answer to
`unknown`. A provjet that emitted `complete: true` for a scope containing `src/`
would therefore be correctly diagnosed as stale, and every bundle it produced
would be answerable only at reduced fidelity.

---

## 4. The false-accusation surface (R2)

This is the part of the port that is not a port. **This repo has the bug the
upstream spent four fix rounds closing, today, on `main`, independent of path
scope.**

`SealBundle.kt`'s submission-file read is:

```kotlin
try {
    val bytes = Files.readAllBytes(abs)
    Reviewed(rel, true, Sha256.hex(bytes), bytes)
} catch (_: Exception) {
    Reviewed(rel, false, null, null)   // -> status "missing"
}
```

`RollingSeal.kt` has the same shape. **Every** failure mode becomes
`status: "missing"`: a permission error, a directory where a file was expected,
a symlink loop, too many open files, an I/O error mid-read. A `missing` record
is rendered to staff as *"File listed in files_under_review but absent on disk
at seal time"* and is used in academic-integrity proceedings.

So a student whose file is sitting on disk, fully readable by them, gets an
affirmative false claim that they did not submit it — because the seal hit
`EACCES`. That is the worst output this system can produce, and closing it is
part of this work rather than a follow-up.

### The five invariants

Inherited from upstream, where they cost four fix rounds. They are not to be
rediscovered, re-derived, or quietly implemented differently.

1. **`missing` is reachable from exactly ONE condition: the file genuinely does
   not exist** (`NoSuchFileException` / ENOENT). Never from a read failure,
   never from a containment rejection, never from a directory-where-a-file-was-
   expected, never from a non-regular file.
2. **Only an EXACT track entry may mint a `missing` record.** A rule entry
   asserts nothing about any particular file existing. A course writing
   `*.java` must not generate a finding for every `.java` file the student never
   wrote.
3. **Containment rejection, unreadable file, unreadable directory, non-regular
   file, and duplicate-drop each DROP the entry and raise their own distinct
   fact** — never a `missing`.
4. **Hard-excluded directories are pruned by SEGMENT NAME** (`.git`,
   `.provenance`), not root-anchored. A nested `vendor/lib/.git/`, and above all
   a *sibling assignment's* `hw3/.provenance/` under this repo's nested and
   concurrent multi-assignment recording, must both be pruned — otherwise a rule
   entry like `*.json` seals one student's provenance into another's evidence
   bundle. The exact-entry loop applies the same check independently, because it
   reads by string and never passes through the walk's pruning.
5. **`scope_capped` is emitted only when true.**

### Two mechanisms, kept on the invariants' account

**The regular-file gate.** Upstream gates on `stat().isFile()` before reading.
Half of that gate's justification is Node-specific (`readFile` on a FIFO blocks
forever). The other half is invariant 1 and is binding regardless of the JVM's
blocking semantics: an exact entry naming a **directory** — an ordinary staff
manifest typo, `src` instead of `src/` — must route to *dropped*, not to
`missing`. Upstream got `EISDIR`, collapsed it into the catch-all, and minted a
false accusation. Keep the gate.

Whether `Files.readAllBytes` on a FIFO blocks on the JVM is to be **verified
empirically**, not reasoned about. It does not change the gate either way.

**Containment.** `Path.toRealPath()` is the right primitive, and the requirement
is behavioural and two-sided: a symlink inside the workspace pointing outside it
must not have its bytes sealed, **and** must not mint a `missing`. Upstream took
extra rounds partly because a fix closed the exfiltration by converting it into
a false accusation. Both halves are required.

Realpath both sides — the root and the candidate — and compare. Realpathing only
the candidate against a lexical root rejects every path in an ordinary workspace
whose root sits behind a symlink.

**Case-folding is an open verification item.** Upstream's duplicate-drop
depends on `realpath` canonicalizing filesystem case on macOS. Whether Kotlin's
`toRealPath()` does the same must be verified directly. If it does not, the
dedupe does not dedupe, and one file is recorded twice under two spellings in a
signed manifest — which is a cross-repo decision, not a local one. See the
plan's Task 5 verification step.

---

## 5. Live membership

`ExpectedContentRegistry` stops taking a `List<String>` and starts taking a
`ResolvedScope`; `isWatched` stops being `Set.contains` and becomes a
`resolvePathRole(...) == reviewed` evaluation, plus the cap.

Membership is evaluated **per path, live** — never snapshotted at session start.
The reason to name a folder is that the file set is not known in advance; a
snapshot makes a file the student creates ten minutes in invisible for the rest
of the session, and "I wrote it in a new file" is ordinary, innocent behaviour.
A snapshot is an R2 violation by construction.

`EXPECTED_CONTENT_MAX_FILES = 512`. Part of the writer contract: **all three
recorders must cap at the same number**, or two ports disagree about when a
session is capped. When the registry is full and a new path would have been
admitted, it is not tracked — and the recorder **must disclose that**, via
`scope_capped` on the sealed manifest. A session that silently stopped watching
files it was told to watch would let the analyzer see the rules, see the file in
scope, see no activity, and draw a wrong conclusion about a student who did
nothing.

The cap flag is set as a deliberate side effect of the membership check: that is
the only moment the cap is observable. A path that was never in scope does not
set it — the cap did not cost us that file.

---

## 6. What this port deliberately does not do

- **No glob engine**, here or anywhere. Same as upstream.
- **No change to `DocWiring`'s emission gate — deliberately, under a standing
  ruling, and with a known gap.**

  Upstream spec §3.4 row 2 says `ignore` means "invisible to the recorder
  entirely", and the staff composer tells professors that no events are produced
  for those paths. **Neither is true in the monorepo today.** In
  `doc-wiring.ts`, `emitDocOpen` fires unconditionally outside the `isWatched`
  branch, and `emitDocChange`, `emitDocSave` and `emitPaste` sit outside their
  guards too. `doc.open` carries the file's full content — it is the
  reconstruction seed. So a course that ignores a file for privacy reasons gets
  that file's contents written verbatim into a signed, hash-chained log. The
  upstream whole-branch review missed it because it confirmed `resolvePathRole`
  returns `ignored` correctly and never checked whether consumers honour what
  that role means.

  This repo's `DocWiring` already matches the monorepo's shape: it emits
  `doc.*` for every recordable file in an activated root, and the scope check
  lives downstream in the external-change path.

  The ruling is to **mirror the monorepo's current behaviour exactly** and
  record the discrepancy rather than fix it here. A uniform, documented gap
  across three recorders is recoverable; three recorders disagreeing about
  whether ignored files get recorded is not. The fix is also a product call
  rather than a coding one — gating `doc.*` on `ignored` is not free, because
  internal-move classification reads paste content to downgrade a `large_paste`
  flag, and the retired `inline_content` knob is documented precedent that
  stripping content can make the system *more* accusatory. The decision is
  being taken upstream for all three recorders together.
- **No `/architecture` page update** — this repo has none.
- **No new dependencies.** Everything needed is in `kotlinx.serialization.json`,
  `java.nio.file`, and the existing test stack.
- **No linter added.** This repo has no ktlint or detekt, and adding one is a
  dependency decision requiring approval.
