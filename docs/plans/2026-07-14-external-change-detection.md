# External-Change Detection (VFS) Implementation Plan (Plan 5 of the provjet series)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the VS Code recorder's external-change detection (PRD §4.5) to the IntelliJ Platform: an in-memory expected-content model, all three detection paths (save-time hash check, VFS bulk-change listener, reload-from-disk detection), and `fs.external_change` event emission — proven correct on the comparison **direction** and on IntelliJ's VFS-refresh **timing**, which is where `docs/design.md` §4 flags this subsystem as the port's single highest-risk item.

**Architecture:** Lives in `recorder/` (the plugin module — this is IntelliJ-wiring, not format, so it does **not** go in `core/`). A pure-Kotlin `ExpectedContent`/`ExpectedContentRegistry` model (offset-based, simpler than VS Code's line/character model — IntelliJ's `DocumentEvent` is already offset-based) is the source of truth for "what the editor believes each watched file contains." Three IntelliJ-specific listeners feed it and compare against on-disk reality: (1) a save-time hash check hooked into the doc-save path, (2) a `BulkFileListener` on `VirtualFileManager.VFS_CHANGES` for changes IntelliJ's VFS becomes aware of asynchronously (native file watcher, or the refresh-on-frame-activation backstop), and (3) a `FileDocumentManagerListener.fileContentReloaded` hook for the case where an open, clean buffer gets silently reloaded from disk. All three funnel into one `fs.external_change` emission point via an `ExternalChangeCoordinator` that owns dedup and disposal.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (`com.intellij.openapi.vfs.*`, `com.intellij.openapi.fileEditor.*`), JUnit 5 + IntelliJ Platform test fixtures (`BasePlatformTestCase`). Builds on `core/`'s `Sha256` (Plan 1) for hashing and this repo's not-yet-written `recorder/` scaffold (Plans 3–4).

## Plan series (context)

Per `docs/design.md` §8 and Plan 1's series listing:

- Plan 1: `core/` — format port (done).
- Plan 2: `core/` — crypto + bundle (done).
- Plan 3: plugin scaffold + activation + manifest verification + status-bar widget + sideload build. **Not yet written.**
- Plan 4: `doc.open/change/save/close` wiring + atomic session writer + bundle seal → first analyzer-accepted bundle. **Not yet written.**
- **Plan 5 (this): external-change detection (VFS — highest-risk).**
- Plan 6: three-signal paste detection.
- Plan 7: terminal + git wiring + plugin snapshot.
- Plan 8: checkpoints + chain recovery + disk-full degraded mode.
- Plan 9: `build:prod` + Marketplace packaging + monorepo allowlist/vector-export changes.

### Assumptions inherited from not-yet-written Plans 3–4

Plans 3 and 4 do not exist as written documents yet, but this plan needs a few things from them to be well-defined. These are **working assumptions** — confirm/adjust when Plans 3–4 are actually written, per CLAUDE.md's "stop and ask on ambiguity." They are called out inline wherever used, not silently baked in:

1. A Gradle module `recorder/` exists, using the IntelliJ Platform Gradle Plugin, with package root `dev.provenance.recorder` (parallel to `core/`'s `dev.provenance.core`), and `testImplementation(intellijPlatform.testFramework(TestFrameworkType.Platform))` (or equivalent) wired for `BasePlatformTestCase`-based tests.
2. A `doc.change` pipeline exists (Plan 4) built on IntelliJ's `DocumentListener`, and a session-scoped emit sink of the shape `fun emit(kind: String, data: JsonObject)` (or equivalent) that hands events to the hash chain + session writer.
3. A session lifecycle object (`SessionHost` or similar, Plan 4) that owns a `Disposable` — everything this plan registers (listeners, message-bus connections) must be torn down when that `Disposable` is disposed, per CLAUDE.md's "every listener... has a `dispose()` hook."
4. `files_under_review` (the manifest's watched-file list, relative paths) is available at session-start, per PRD §4.1/§4.5 (Plan 3's manifest verification).

If any of these turn out different when Plans 3–4 are written, the integration seams in Tasks 4–7 below (marked "wire into Plan 4's X") are the points to adjust — the pure-Kotlin pieces (Tasks 1–3) are unaffected either way.

## Global Constraints

**Format (do not change, do not invent):**
- The event kind is **`fs.external_change`**, not `ext.change` — verified against `packages/log-core/src/events.ts:137,236` in the monorepo. The design.md sketch used `ext.change`; that was shorthand, not the real kind string. Use `fs.external_change`.
- Payload shape (`FsExternalChangePayload`, `events.ts:137-179`), field names are already snake_case on the wire — no camelCase-to-snake_case mapping needed (unlike `HashedEnvelope`'s `prevHash`→`prev_hash` in Plan 1):
  - `path: String`
  - `old_hash: String` — sha256 of content **before** the change; `""` for `operation: "create"`.
  - `new_hash: String` — sha256 of content **after** the change; `""` for `operation: "delete"`.
  - `diff_size: Int` — `abs(newContent.length - oldContent.length)` using **UTF-16 code-unit length** (`String.length` in both Kotlin and JS — they agree bit-for-bit here; do not switch to byte length for this field).
  - `explanation: String?` — `"formatter"` or `"git"`. **Out of scope for Plan 5** (populating it is Plan 7's terminal/git wiring); this plan only threads an optional injectable tagger interface through so Plan 7 can slot in later without touching this plan's code, mirroring the VS Code recorder's `explanationTagger?: ExplanationTagger` DI pattern (`fs-watcher.ts:39,60`).
  - `operation: String?` — `"modify" | "delete" | "create"`.
  - `new_content_size: Int?` — **UTF-8 byte length** (`text.toByteArray(Charsets.UTF_8).size`, matching JS `Buffer.byteLength(text, 'utf8')`) — **not** the same measure as `diff_size`. This asymmetry is inherited from the VS Code recorder (`external-change-content.ts:37` vs `external-change-detector.ts:57`) and must be ported faithfully, not "fixed."
  - `new_content: String?` — full content if `new_content_size <= 4096`.
  - `new_content_head` / `new_content_tail: String?` — first/last 512 chars if larger. **Slicing is UTF-16-code-unit-based** (`text.slice(0, 512)` / `.slice(-512)` in JS), even though the size threshold that decides whether to slice is byte-based. Port this quirk exactly — do not "fix" it to be consistent. A conformance test with multi-byte (emoji) content pins this.
- **The direction, stated once, precisely, because CLAUDE.md calls this out as the easy mistake:** `old_hash`/`old_content` = the **expected-content model** (what the editor's cumulative `doc.change` history says the file should contain) — never a previous on-disk read. `new_hash`/`new_content` = **on-disk reality** at detection time. The comparison is always `expected vs disk`, never `disk vs disk`. After emitting, `ExpectedContent.reset(onDiskContent)` re-seeds the model from disk so the *next* comparison starts from truth (PRD §4.5, `fs-watcher.ts:25-26`).

**Architecture:**
- `ExpectedContent`/`ExpectedContentRegistry` are plugin-wiring state (mirrors VS Code's `packages/recorder/src/state/`), not format — they belong in `recorder/`, **not** `core/`. `core/`'s zero-IntelliJ-dependency rule is irrelevant here since these classes also happen to have zero IntelliJ dependency (pure Kotlin) — that's a coincidence of the offset-based delta model, not a reason to relocate them into `core/`. Keep them in `recorder/` because they model plugin session state, matching the monorepo's module boundary logic (CLAUDE.md "Architecture rules").
- Every listener/message-bus connection registered in this plan takes an explicit `Disposable` and is torn down with the session (CLAUDE.md, "no background task without an explicit shutdown path").
- Handlers registered on `VirtualFileManager.VFS_CHANGES` run **on EDT, inside a write action** (see Sources below) — keep them fast; do the hashing/diffing/emit work off that thread (Task 5).

**Sources (IntelliJ Platform SDK, verified against current docs + `intellij-community` master source as of 2026-07):**
- Virtual File System overview, caching, refresh, `BulkFileListener`: https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html
- Threading model (EDT/BGT, read/write actions, read-action-from-background-thread rules): https://plugins.jetbrains.com/docs/intellij/threading-model.html
- `com.intellij.openapi.vfs.newvfs.events.VFileEvent` (base class — `isFromRefresh()`, `isFromSave()`, `getPath()`, `getFile()`), `VFileContentChangeEvent`, `VFileCreateEvent`, `VFileDeleteEvent` — source: `intellij-community/platform/core-api/src/com/intellij/openapi/vfs/newvfs/events/`.
- `com.intellij.openapi.vfs.newvfs.BulkFileListener` (topic `VirtualFileManager.VFS_CHANGES`, `@RequiresWriteLock before/after`) — source: `intellij-community/platform/core-api/src/com/intellij/openapi/vfs/newvfs/BulkFileListener.java`.
- `com.intellij.openapi.vfs.AsyncFileListener` (considered, not used — see Task 5 rationale) — source: `intellij-community/platform/core-api/src/com/intellij/openapi/vfs/AsyncFileListener.java`.
- `com.intellij.openapi.fileEditor.FileDocumentManagerListener` (`TOPIC`, `fileContentReloaded(file, document)`) — source: `intellij-community/platform/platform-api/src/com/intellij/openapi/fileEditor/FileDocumentManagerListener.java`.
- `com.intellij.openapi.application.ApplicationActivationListener` (`applicationActivated(IdeFrame)`) and refresh-on-frame-activation ("Synchronize files on frame activation" setting, `SaveAndSyncHandlerImpl.refreshOpenFiles`, ~300ms scheduling) — https://intellij-support.jetbrains.com/hc/en-us/community/posts/360006715420 and related JetBrains support threads.
- **Async VFS content writes (2026 platform change)** — VFS state updates before the physical disk write finishes for opted-in writers (`FileDocumentManagerImpl` opts in for editor saves via `SavingRequestor`); reading through `VirtualFile.contentsToByteArray()` sees the fresh state, raw NIO reads may not; `ManagingFS.getInstance().flushPendingUpdates(virtualFile)` flushes explicitly when guaranteed-on-disk bytes are needed outside a write action: https://blog.jetbrains.com/platform/2026/06/async-vfs-content-writes-what-plugin-authors-need-to-know/

**PRD §4.5's three detection paths (mapped to IntelliJ):**

| # | VS Code | IntelliJ | Task |
|---|---|---|---|
| 1 | save-time hash check in `doc.save` handler | save-time hash check after `FileDocumentManager.saveDocument()` | Task 4 |
| 2 | `FileSystemWatcher.onDidChange/Create/Delete` | `BulkFileListener` on `VirtualFileManager.VFS_CHANGES` | Task 5 |
| 3 | reload-from-disk heuristic in `doc.change` (`reason === undefined && !isDirty`) | `FileDocumentManagerListener.fileContentReloaded(file, document)` — **a direct signal, not a heuristic**; IntelliJ tells us explicitly when this happens | Task 6 |

Note the improvement path 3 gets for free: VS Code has to *infer* "this was a silent reload" from an absent `reason` field and a clean-buffer flag. IntelliJ's `fileContentReloaded` callback fires exactly and only for this case — no heuristic needed. Call this out in review; it's a case where the port is *more* precise than the original, not a compromise.

---

### Task 1: `ExpectedContent` + `ExpectedContentRegistry` (offset-based port)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/state/ExpectedContent.kt`
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/state/ExpectedContentRegistry.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/state/ExpectedContentTest.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/state/ExpectedContentRegistryTest.kt`

**Non-obvious choice:** VS Code's `ExpectedContent.applyDelta` (`state/expected-content.ts:91-113`) converts `{line, character}` positions to string offsets by hand, because `vscode.TextDocumentContentChangeEvent` gives line/character ranges. IntelliJ's `com.intellij.openapi.editor.event.DocumentEvent` gives `getOffset(): Int`, `getOldLength(): Int`, `getNewFragment(): CharSequence` directly — already offset-based. So the Kotlin `Delta` is simpler and the position-to-offset conversion is dropped entirely. This is a deliberate, documented simplification of the port, not a behavior change — the resulting content/hash is the same.

**Interfaces:**
- `data class Delta(val offset: Int, val oldLength: Int, val newText: String)` — mirrors `DocumentEvent(offset, oldLength, newFragment)` shape.
- `class ExpectedContent(initialContent: String)`:
  - `val content: String`
  - `val lineCount: Int` (same counting rule as VS Code: empty → 0, else `1 + count('\n')`)
  - `val hash: String` (memoized sha256 hex via `core.Sha256.hex`, invalidated by `applyDelta`/`reset`)
  - `fun applyDelta(delta: Delta)` — `content = content.substring(0, delta.offset) + delta.newText + content.substring(delta.offset + delta.oldLength)`
  - `fun applyDeltas(deltas: List<Delta>)`
  - `fun reset(content: String)`
- `class ExpectedContentRegistry(filesUnderReview: List<String>)`:
  - `fun isWatched(relativePath: String): Boolean`
  - `fun getOrCreate(relativePath: String, initialContent: String): ExpectedContent`
  - `fun get(relativePath: String): ExpectedContent?`
  - `fun delete(relativePath: String)`

**Step 1: Write the failing tests**

`ExpectedContentTest.kt` — port every case from the VS Code suite's intent, adapted to offset deltas:
```kotlin
package dev.provenance.recorder.state

import dev.provenance.core.Sha256
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExpectedContentTest {
    @Test
    fun `hash matches Sha256 of initial content`() {
        val ec = ExpectedContent("hello")
        assertEquals(Sha256.hex("hello"), ec.hash)
    }

    @Test
    fun `applyDelta splices at offset and invalidates the cached hash`() {
        val ec = ExpectedContent("hello world")
        ec.applyDelta(Delta(offset = 5, oldLength = 6, newText = " there"))
        assertEquals("hello there", ec.content)
        assertEquals(Sha256.hex("hello there"), ec.hash)
    }

    @Test
    fun `applyDeltas applies in order`() {
        val ec = ExpectedContent("abc")
        ec.applyDeltas(listOf(Delta(0, 1, "X"), Delta(1, 1, "Y")))
        assertEquals("XYc", ec.content)
    }

    @Test
    fun `reset replaces content and hash`() {
        val ec = ExpectedContent("abc")
        ec.reset("xyz")
        assertEquals("xyz", ec.content)
        assertEquals(Sha256.hex("xyz"), ec.hash)
    }

    @Test
    fun `lineCount counts newlines plus one, zero for empty`() {
        assertEquals(0, ExpectedContent("").lineCount)
        assertEquals(1, ExpectedContent("no newline").lineCount)
        assertEquals(3, ExpectedContent("a\nb\nc").lineCount)
        assertEquals(2, ExpectedContent("trailing\n").lineCount)
    }
}
```

`ExpectedContentRegistryTest.kt`:
```kotlin
package dev.provenance.recorder.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ExpectedContentRegistryTest {
    @Test
    fun `isWatched reflects the files_under_review set`() {
        val reg = ExpectedContentRegistry(listOf("src/Main.kt"))
        assertEquals(true, reg.isWatched("src/Main.kt"))
        assertEquals(false, reg.isWatched("src/Other.kt"))
    }

    @Test
    fun `getOrCreate returns the same instance on repeat calls`() {
        val reg = ExpectedContentRegistry(listOf("a.txt"))
        val first = reg.getOrCreate("a.txt", "v1")
        val second = reg.getOrCreate("a.txt", "ignored — already exists")
        assertSame(first, second)
        assertEquals("v1", second.content)
    }

    @Test
    fun `get returns null for an untracked path`() {
        val reg = ExpectedContentRegistry(listOf("a.txt"))
        assertNull(reg.get("a.txt"))
    }

    @Test
    fun `delete removes the entry so a later getOrCreate starts clean`() {
        val reg = ExpectedContentRegistry(listOf("a.txt"))
        reg.getOrCreate("a.txt", "v1")
        reg.delete("a.txt")
        val recreated = reg.getOrCreate("a.txt", "v2")
        assertEquals("v2", recreated.content)
    }
}
```

**Step 2: Run, verify failure** — `./gradlew :recorder:test --tests 'dev.provenance.recorder.state.*'`, expect unresolved references.

**Step 3: Implement**

`ExpectedContent.kt`:
```kotlin
package dev.provenance.recorder.state

import dev.provenance.core.Sha256

/** One doc.change delta. Offset-based — mirrors com.intellij.openapi.editor.event.DocumentEvent. */
data class Delta(val offset: Int, val oldLength: Int, val newText: String)

/**
 * In-memory model of what a watched file's content SHOULD be, per the sum of
 * doc.change deltas observed since the last reset. The source of truth for
 * external-change detection (PRD §4.5): on-disk content is always compared
 * AGAINST this, never the other way around.
 */
class ExpectedContent(initialContent: String) {
    private var _content: String = initialContent
    private var _hash: String? = null

    val content: String get() = _content

    val lineCount: Int
        get() {
            if (_content.isEmpty()) return 0
            var count = 1
            for (c in _content) if (c == '\n') count++
            return count
        }

    val hash: String
        get() {
            var h = _hash
            if (h == null) {
                h = Sha256.hex(_content)
                _hash = h
            }
            return h
        }

    fun applyDelta(delta: Delta) {
        _content = _content.substring(0, delta.offset) + delta.newText +
            _content.substring(delta.offset + delta.oldLength)
        _hash = null
    }

    fun applyDeltas(deltas: List<Delta>) {
        for (d in deltas) applyDelta(d)
    }

    /** Replace content wholesale (e.g. after an fs.external_change reconciliation). */
    fun reset(content: String) {
        _content = content
        _hash = null
    }
}
```

`ExpectedContentRegistry.kt`:
```kotlin
package dev.provenance.recorder.state

/** Maps watched relative paths to their ExpectedContent. Only files_under_review are tracked (PRD §4.5). */
class ExpectedContentRegistry(filesUnderReview: List<String>) {
    private val watched: Set<String> = filesUnderReview.toSet()
    private val map = HashMap<String, ExpectedContent>()

    fun isWatched(relativePath: String): Boolean = watched.contains(relativePath)

    fun getOrCreate(relativePath: String, initialContent: String): ExpectedContent =
        map.getOrPut(relativePath) { ExpectedContent(initialContent) }

    fun get(relativePath: String): ExpectedContent? = map[relativePath]

    fun delete(relativePath: String) {
        map.remove(relativePath)
    }
}
```

**Step 4: Run, verify pass** — `./gradlew :recorder:test --tests 'dev.provenance.recorder.state.*'`, expect 9 tests green.

**Step 5: Commit**
```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/state/ExpectedContent.kt \
  recorder/src/main/kotlin/dev/provenance/recorder/state/ExpectedContentRegistry.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/state/ExpectedContentTest.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/state/ExpectedContentRegistryTest.kt
git commit --no-gpg-sign -m "feat(recorder): expected-content model + registry (offset-based port)"
```

---

### Task 2: `ExternalChangeContent` inline/truncate helper

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/events/ExternalChangeContent.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/events/ExternalChangeContentTest.kt`
- Create (vector): `recorder/src/test/resources/conformance/external-change-content.json`

**Interfaces:**
- `const val MAX_INLINE_BYTES = 4096`
- `const val HEAD_TAIL_CHARS = 512` (named `_CHARS` not `_BYTES` here — deliberately, since the slice below is code-unit based; VS Code's `HEAD_TAIL_BYTES` name is a slight misnomer inherited from the original, noted not copied)
- `data class ExternalChangeContentFields(val newContentSize: Int, val newContent: String?, val newContentHead: String?, val newContentTail: String?)`
- `fun buildExternalChangeContent(text: String): ExternalChangeContentFields` — size check is UTF-8 byte length; head/tail slice is UTF-16-code-unit based (see Global Constraints). Mirrors `external-change-content.ts:36-51` exactly, including the size/slice unit mismatch.

**Test intent:**
- Small ASCII text (≤ 4096 bytes) → `newContent` set, head/tail null.
- Text just over 4096 UTF-8 bytes (use multi-byte chars so char count ≠ byte count) → head/tail set, `newContent` null, `newContentSize` is the byte count not the char count.
- **Cross-language vector** (`external-change-content.json`): generate in Node against the monorepo's `external-change-content.ts` with an emoji-heavy fixture (so byte-length vs code-unit-length diverge) and commit the exact `new_content_size`/`new_content_head`/`new_content_tail`. Assert the Kotlin output matches byte-for-byte. This is the test that catches a byte/char-unit mixup, which is an easy silent bug in this specific function.

**Vector generation (run once, commit output):**
```bash
# from ../provenance
node --input-type=module -e '
import { buildExternalChangeContent } from "./packages/recorder/src/events/external-change-content.js";
const text = "🎉".repeat(2000) + "tail-marker-🎉";
const fields = buildExternalChangeContent(text);
console.log(JSON.stringify({ input: text, ...fields }, null, 2));
'
```

**Steps:** standard TDD → implement → commit `feat(recorder): external-change content inline/truncate helper`.

Implementation sketch (full code, since this is a pure function with a real byte/char-unit trap):
```kotlin
package dev.provenance.recorder.events

const val MAX_INLINE_BYTES = 4096
const val HEAD_TAIL_CHARS = 512

data class ExternalChangeContentFields(
    val newContentSize: Int,
    val newContent: String?,
    val newContentHead: String?,
    val newContentTail: String?,
)

/** Mirrors external-change-content.ts. Size check: UTF-8 bytes. Head/tail slice: UTF-16 code units. */
fun buildExternalChangeContent(text: String): ExternalChangeContentFields {
    val byteLength = text.toByteArray(Charsets.UTF_8).size
    if (byteLength <= MAX_INLINE_BYTES) {
        return ExternalChangeContentFields(byteLength, text, null, null)
    }
    val head = text.substring(0, minOf(HEAD_TAIL_CHARS, text.length))
    val tail = text.substring(maxOf(0, text.length - HEAD_TAIL_CHARS))
    return ExternalChangeContentFields(byteLength, null, head, tail)
}
```

---

### Task 3: `FsExternalChangePayload` model + `ExternalChangeClassifier` (the direction-critical task)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/events/FsExternalChangePayload.kt`
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/events/ExternalChangeClassifier.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/events/ExternalChangeClassifierTest.kt`

**Interfaces:**
- `data class FsExternalChangePayload(val path: String, val oldHash: String, val newHash: String, val diffSize: Int, val explanation: String? = null, val operation: String? = null, val newContentSize: Int? = null, val newContent: String? = null, val newContentHead: String? = null, val newContentTail: String? = null)`
- `fun FsExternalChangePayload.toJsonObject(): JsonObject` — snake_case wire keys per Global Constraints, omitting null optional fields (kotlinx.serialization `buildJsonObject`).
- `sealed interface ExternalChangeResult { data class CleanSave(val newHash: String) : ExternalChangeResult; data class Changed(val oldHash: String, val newHash: String, val diffSize: Int) : ExternalChangeResult }`
- `fun classifySavedContent(expected: ExpectedContent, onDiskContent: String): ExternalChangeResult` — port of `compareSavedContent` (`external-change-detector.ts:44-65`). **Does not mutate `expected`** — caller resets after emitting, exactly as the VS Code version's docstring insists (`external-change-detector.ts:8-10`).

**Step 1: Write the failing tests — including an explicit direction regression test**

```kotlin
package dev.provenance.recorder.events

import dev.provenance.core.Sha256
import dev.provenance.recorder.state.ExpectedContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExternalChangeClassifierTest {
    @Test
    fun `matching hashes classify as clean save`() {
        val expected = ExpectedContent("hello")
        val result = classifySavedContent(expected, "hello")
        result as ExternalChangeResult.CleanSave
        assertEquals(Sha256.hex("hello"), result.newHash)
    }

    @Test
    fun `mismatched hashes classify as changed with old from EXPECTED and new from DISK`() {
        // Regression test for the direction CLAUDE.md warns about: old_hash must be
        // the expected (editor) model's hash, new_hash must be the on-disk hash.
        // Swapping them would make an external Claude-Code-CLI edit look like the
        // student's own typed baseline and the CLI's output look like "what the
        // editor expected" — silently inverting the anti-CLI signal (PRD §4.5, G3).
        val expected = ExpectedContent("editor believes this")
        val onDisk = "but disk actually has this"
        val result = classifySavedContent(expected, onDisk)
        result as ExternalChangeResult.Changed
        assertEquals(Sha256.hex("editor believes this"), result.oldHash)
        assertEquals(Sha256.hex("but disk actually has this"), result.newHash)
        assertTrue(result.oldHash != result.newHash)
    }

    @Test
    fun `diff_size is the absolute code-unit length difference, not byte length`() {
        val expected = ExpectedContent("aaaa") // 4 chars
        val result = classifySavedContent(expected, "aa") as ExternalChangeResult.Changed
        assertEquals(2, result.diffSize)
    }

    @Test
    fun `classifySavedContent does not mutate the expected model`() {
        val expected = ExpectedContent("original")
        classifySavedContent(expected, "different")
        assertEquals("original", expected.content) // caller resets, not the classifier
    }
}
```

**Step 2–4:** run → fail → implement → run → pass (4 tests).

```kotlin
package dev.provenance.recorder.events

import dev.provenance.core.Sha256
import dev.provenance.recorder.state.ExpectedContent

sealed interface ExternalChangeResult {
    data class CleanSave(val newHash: String) : ExternalChangeResult
    data class Changed(val oldHash: String, val newHash: String, val diffSize: Int) : ExternalChangeResult
}

/**
 * Compare on-disk content against the expected (editor) model. DIRECTION IS FIXED:
 * old = expected (source of truth for "what the editor believes"), new = on-disk
 * reality. Never the reverse. Does not mutate `expected` — caller resets after
 * emitting fs.external_change (PRD §4.5).
 */
fun classifySavedContent(expected: ExpectedContent, onDiskContent: String): ExternalChangeResult {
    val actualHash = Sha256.hex(onDiskContent)
    val expectedHash = expected.hash
    if (actualHash == expectedHash) return ExternalChangeResult.CleanSave(actualHash)
    val diffSize = kotlin.math.abs(onDiskContent.length - expected.content.length)
    return ExternalChangeResult.Changed(oldHash = expectedHash, newHash = actualHash, diffSize = diffSize)
}
```

`FsExternalChangePayload.kt` — full snake_case JSON emission, straightforward `buildJsonObject` mirroring Plan 1's `Envelope.toJsonText()` pattern; omit null optional fields.

**Step 5: Commit**
```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/events/FsExternalChangePayload.kt \
  recorder/src/main/kotlin/dev/provenance/recorder/events/ExternalChangeClassifier.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/events/ExternalChangeClassifierTest.kt
git commit --no-gpg-sign -m "feat(recorder): fs.external_change payload + direction-pinned classifier"
```

---

### Task 4: Path 1 — save-time hash check

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/watch/SaveTimeExternalChangeChecker.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/watch/SaveTimeExternalChangeCheckerTest.kt` (uses `BasePlatformTestCase`)

**Integration seam (assumption, see header):** Plan 4's doc-save wiring is assumed to call `FileDocumentManager.getInstance().saveDocument(document)` for a watched file and then have some hook point immediately after. This task adds `SaveTimeExternalChangeChecker.checkAfterSave(relativePath, virtualFile)`, to be **called from that hook** — the exact call site is Plan 4's to wire once it exists; this task delivers the checker as a standalone, independently testable unit so Plan 4 only needs a one-line call.

**Interfaces:**
- `class SaveTimeExternalChangeChecker(private val registry: ExpectedContentRegistry, private val emit: (FsExternalChangePayload) -> Unit)`
- `fun checkAfterSave(relativePath: String, file: VirtualFile)`:
  1. If `!registry.isWatched(relativePath)`, return.
  2. `val expected = registry.get(relativePath) ?: return` (never opened before save — nothing to compare; PRD §4.5 path 1 only fires for a doc that was open).
  3. Read on-disk content via **`file.contentsToByteArray()`** (VFS-mediated — per the async-VFS-writes note in Global Constraints, this reflects the just-completed save even if the physical disk write is still finishing in the background; do **not** read via raw NIO here).
  4. `classifySavedContent(expected, onDiskText)`. `CleanSave` → no-op. `Changed` → build `FsExternalChangePayload(operation = "modify", ...)` via `buildExternalChangeContent`, `emit(...)`, then `expected.reset(onDiskText)`.

**Test intent (`BasePlatformTestCase`):**
- Open a file via `myFixture.configureByText(...)`, seed the registry from its initial content, type through the editor (simulating normal `doc.change` → `ExpectedContent.applyDelta`), save via `FileDocumentManager.getInstance().saveDocument(...)`, call `checkAfterSave` — assert **no** emission (expected model tracked the typed edit, on-disk now matches).
- Same setup, but before calling `checkAfterSave`, overwrite the file on disk directly (bypassing the editor) via `WriteAction.run { file.setBinaryContent(...) }` to simulate "something else wrote between our last observed change and the save" (PRD §4.5 path 1's stated scenario) — assert emission fires with `old_hash` = the registry's pre-overwrite hash and `new_hash` = the overwritten content's hash (direction check again, at the integration level this time).
- File never opened (no registry entry) → `checkAfterSave` is a no-op, no exception.

**Steps:** TDD → commit `feat(recorder): save-time external-change hash check (PRD §4.5 path 1)`.

---

### Task 5: Path 2 — `BulkFileListener` VFS wiring (the highest-risk task)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/watch/VfsExternalChangeListener.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/watch/VfsExternalChangeListenerTest.kt` (uses `BasePlatformTestCase` with a **local temp-dir fixture**, not the in-memory `TempFileSystem` — real VFS refresh semantics require a real `LocalFileSystem`-backed file)

**Design rationale (why `BulkFileListener`, not `AsyncFileListener`):** `AsyncFileListener.prepareChange` runs *before* the VFS event is applied and explicitly **cannot see the new file content** (`AsyncFileListener.java`: "there will be no file in `VFileCreateEvent`... etc."). We need the post-change on-disk content to hash and diff. `BulkFileListener.after()` gives us that. The tradeoff is `after()` runs on EDT inside a write action, so it must stay cheap — see the dispatch design below.

**Interfaces:**
```kotlin
class VfsExternalChangeListener(
    private val workspaceRoot: VirtualFile,
    private val registry: ExpectedContentRegistry,
    private val getLastDocChangeAt: (String) -> Long,
    private val getNow: () -> Long,
    private val emit: (FsExternalChangePayload) -> Unit,
    private val recentDocChangeToleranceMs: Long = 250,
) : BulkFileListener {
    override fun after(events: List<VFileEvent>) { /* Step 1 below */ }
}
```

**Step 1: cheap synchronous triage in `after()` (stays on EDT, must be fast)**

For each event, in order:
1. Resolve `relativePath` via `VfsUtilCore.getRelativePath(event.file ?: continue, workspaceRoot, '/')` — skip if null (outside the workspace) or `!registry.isWatched(relativePath)`. **This is the plugin's project/scope filter** — VFS listeners are application-level and see every open project's events (Sources: virtual-file-system.html), so the registry's watched-set membership check is what scopes us to this session's `files_under_review`, matching CLAUDE.md's "drop events for files outside the workspace."
2. **Skip `event.isFromSave() == true`.** `isFromSave()` is `true` when the write's requestor implements `SavingRequestor` — `FileDocumentManagerImpl` opts in for editor saves. This is the IDE's own mediated write; it is (or will be) handled by Task 4's save-time check. This is a strictly better dedup mechanism than VS Code's 250ms timing-tolerance window (`fs-watcher.ts:16,56,82`) — it's an exact flag, not a race against a clock. **VERIFY AT EXECUTION (runIde / manual):** confirm empirically that a normal Ctrl+S save in a real IDE window produces VFS events with `isFromSave() == true` for the affected file, across the IDE versions this plugin targets (the `SavingRequestor` opt-in is implementation detail of `FileDocumentManagerImpl`, not a documented contract with a version-stability guarantee).
3. Keep the **cheap fields only**: `relativePath`, event type (`VFileContentChangeEvent` / `VFileCreateEvent` / `VFileDeleteEvent`), and `event.file` (or, for delete, nothing — the file is gone). Collect into a small list.
4. After the loop, hand the collected list to a background executor (`ApplicationManager.getApplication().executeOnPooledThread { ... }`) for the actual work (Step 2). **Do not read file content in `after()` itself** — this is the async-VFS-writes-blog's explicit warning ("if the listener only enqueues work, do not flush inside the synchronous listener... that puts waiting back under the write action") generalized to any slow I/O in this callback, and it's also just the project's standing "handler must be fast" rule (CLAUDE.md) applied to VFS instead of `doc.change`.

**Step 2: background classification + emit (off EDT)**

For each queued item, wrapped in `ReadAction.run { ... }` (background-thread VFS/content reads require a read action per the Threading Model source):
- **Modify** (`VFileContentChangeEvent`): `val expected = registry.get(relativePath) ?: return@run` (never opened — nothing to compare, matches VS Code's `fs-watcher.ts:99-104`). Recency guard: `if (getNow() - getLastDocChangeAt(relativePath) < recentDocChangeToleranceMs) return@run` — kept as a **secondary** guard even though `isFromSave()` is the primary dedup, because a formatter-on-save or a VCS operation triggered by the save could produce a *second*, separately-requestored VFS event in the same window that `isFromSave()` won't catch; the timing tolerance is the same conservative backstop VS Code uses, not the primary mechanism here. Read `file.contentsToByteArray()`, decode UTF-8, `classifySavedContent`, emit on `Changed`, `expected.reset(...)`.
- **Create** (`VFileCreateEvent`): mirror `fs-watcher.ts:141-193`'s race handling — if the registry already has an entry (a `doc.open` beat the VFS event to the registry), diff against it as a modify; otherwise pure create with `old_hash = ""`, then `registry.getOrCreate(relativePath, content)`.
- **Delete** (`VFileDeleteEvent`): if a registry entry exists, emit `operation = "delete"` with `old_hash` = registry's hash, `new_hash = ""`, then `registry.delete(relativePath)`. If no entry, still emit (file was never opened but was in `files_under_review`) with `old_hash = ""` — mirrors `fs-watcher.ts:195-222`.

**Registration (owned by the coordinator, Task 7):**
```kotlin
ApplicationManager.getApplication().messageBus
    .connect(sessionDisposable)
    .subscribe(VirtualFileManager.VFS_CHANGES, listener)
```

**Test intent:**
- Use a **local temp-dir fixture** (`IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()`, backed by real `LocalFileSystem`, not `TempFileSystem`) so a real VFS refresh is exercisable headlessly.
- **Direct external write, file closed in editor:** write bytes via `java.nio.file.Files.write` directly to the temp dir (bypassing VFS entirely — simulates `git checkout` / a CLI tool), then force a synchronous refresh via `VfsUtil.markDirtyAndRefresh(false, false, false, virtualFile)`. Assert the listener fires, `isFromSave()` is false on the resulting event (no `SavingRequestor`), `isFromRefresh()` is true (native-watcher/refresh path), and `emit` is called with the expected direction (`old_hash` = registry's pre-write hash, `new_hash` = the new on-disk hash).
- **Editor save, for regression against false-positives:** open + edit + save through `FileDocumentManager`, force the same synchronous refresh, assert the listener does **not** re-emit (Task 4 already covers it) — this is the `isFromSave()` dedup test.
- **Create:** create a new file directly on disk, refresh, assert `operation = "create"`, `old_hash = ""`.
- **Delete:** delete a tracked, registry-seeded file directly on disk, refresh, assert `operation = "delete"`, `new_hash = ""`, and that `registry.get(path)` is null afterward.
- **Batch (frame-activation simulation):** write to *two* watched files directly on disk, then force one refresh covering both (`VfsUtil.markDirtyAndRefresh(false, false, true, root)` recursively, or refresh each `VirtualFile` in the same write action) — assert **both** emit independently, correctly, and neither is skipped or double-counted. This is the closest a headless test gets to simulating "the user alt-tabbed back after several files changed while the IDE was unfocused" (Task 8 has more on why this can't be fully verified headlessly).

**Steps:** TDD → commit `feat(recorder): BulkFileListener VFS wiring (PRD §4.5 path 2)`.

---

### Task 6: Path 3 — reload-from-disk detection

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/watch/DocumentReloadExternalChangeListener.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/watch/DocumentReloadExternalChangeListenerTest.kt`

**Interfaces:**
```kotlin
class DocumentReloadExternalChangeListener(
    private val workspaceRoot: VirtualFile,
    private val registry: ExpectedContentRegistry,
    private val emit: (FsExternalChangePayload) -> Unit,
) : FileDocumentManagerListener {
    override fun fileContentReloaded(file: VirtualFile, document: Document) {
        val relativePath = VfsUtilCore.getRelativePath(file, workspaceRoot, '/') ?: return
        if (!registry.isWatched(relativePath)) return
        val expected = registry.get(relativePath) ?: run {
            registry.getOrCreate(relativePath, document.text)
            return
        }
        val result = classifySavedContent(expected, document.text)
        if (result is ExternalChangeResult.Changed) {
            emit(FsExternalChangePayload(path = relativePath, operation = "modify",
                oldHash = result.oldHash, newHash = result.newHash, diffSize = result.diffSize,
                /* + buildExternalChangeContent(document.text) fields */))
        }
        expected.reset(document.text)
    }
}
```
Note this is simpler than VS Code's path 3: no `reason === undefined && !isDirty` heuristic is needed (see Global Constraints table) — `fileContentReloaded` fires exactly when IntelliJ has silently reloaded a clean buffer from disk. There is no corresponding "suppress the would-be doc.change" step to write, either — unlike VS Code, IntelliJ doesn't route the reload through the normal `DocumentListener.documentChanged` callback, so Plan 4's `doc.change` handler will not see a spurious edit for this case in the first place. **VERIFY AT EXECUTION:** confirm this assumption (that `fileContentReloaded` and `DocumentListener.documentChanged` are mutually exclusive for the same reload) once Plan 4's `DocumentListener` exists — if IntelliJ *does* also fire a document-change event for the reload, Plan 4's handler needs a suppression flag mirroring `fs-watcher.ts`'s.

**Registration:**
```kotlin
project.messageBus.connect(sessionDisposable)
    .subscribe(FileDocumentManagerListener.TOPIC, listener)
```
(`FileDocumentManagerListener.TOPIC` is `@Topic.AppLevel`, `BroadcastDirection.TO_DIRECT_CHILDREN` — connecting via the project's message bus still receives it; no project-level filtering is needed beyond the registry's watched-set check, matching Task 5's approach.)

**Test intent (`BasePlatformTestCase`):**
- Open a watched file, seed the registry, write new bytes directly to disk (bypass editor), then trigger the platform's reload path — `FileDocumentManager.getInstance().reloadFromDisk(document)` in test code drives the same `fileContentReloaded` callback the real silent-reload path invokes. Assert emission with correct direction and that `expected.content` afterward equals the on-disk content (`reset` happened).
- Reload where on-disk content happens to match the expected model exactly (e.g., a no-op external touch) → no emission.
- File not in `files_under_review` → listener ignores it (registry gate).

**Steps:** TDD → commit `feat(recorder): reload-from-disk external-change detection (PRD §4.5 path 3)`.

---

### Task 7: `ExternalChangeCoordinator` — unify the three paths + lifecycle

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/watch/ExternalChangeCoordinator.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/watch/ExternalChangeCoordinatorTest.kt`

**Interfaces:**
```kotlin
class ExternalChangeCoordinator(
    private val project: Project,
    private val workspaceRoot: VirtualFile,
    filesUnderReview: List<String>,
    private val emit: (FsExternalChangePayload) -> Unit,
    private val getLastDocChangeAt: (String) -> Long,
    private val getNow: () -> Long,
) : Disposable {
    val registry = ExpectedContentRegistry(filesUnderReview)
    private val saveChecker = SaveTimeExternalChangeChecker(registry, emit)

    fun start() {
        val vfsListener = VfsExternalChangeListener(workspaceRoot, registry, getLastDocChangeAt, getNow, emit)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, vfsListener)

        val reloadListener = DocumentReloadExternalChangeListener(workspaceRoot, registry, emit)
        project.messageBus.connect(this).subscribe(FileDocumentManagerListener.TOPIC, reloadListener)
    }

    fun checkAfterSave(relativePath: String, file: VirtualFile) = saveChecker.checkAfterSave(relativePath, file)

    override fun dispose() { /* connections are children of `this` Disposable — auto-disposed */ }
}
```
This is the integration point Plan 4's session host owns: construct with the session's `Disposable`, `start()` once the manifest's `files_under_review` is known (post-activation), call `checkAfterSave` from the doc-save handler (Task 4's seam), and let the platform's `Disposable` tree handle teardown — no manual `dispose()` body needed beyond what `messageBus.connect(this)` already gives, but the class implements `Disposable` explicitly (rather than relying on being passed a disposable that isn't itself one) so Plan 4 can register it directly with `Disposer.register(sessionDisposable, coordinator)`, keeping one shutdown path per CLAUDE.md.

**Test intent:**
- Construct with a fake `emit` sink recording calls; drive one event through each of the three registered listeners (VFS write, editor save, forced reload) and confirm each reaches `emit` exactly once with the right payload — an end-to-end smoke test that the three paths don't double-fire for genuinely independent events, and that disposing the coordinator (via `Disposer.dispose(coordinator)`) unsubscribes all three (assert no further `emit` calls after dispose, by performing a post-dispose write + refresh).

**Steps:** TDD → commit `feat(recorder): external-change coordinator wiring the three PRD §4.5 paths`.

---

### Task 8: Focus/refresh timing edge cases — the dedicated risk task

This task is what `docs/design.md` §4 and CLAUDE.md are pointing at when they call this subsystem "highest risk" and warn about the VFS-refresh-on-focus quirk. Split deliberately into (A) what a headless JUnit suite *can* pin down, and (B) what genuinely requires a running IDE.

**Files:**
- Create: `recorder/src/test/kotlin/dev/provenance/recorder/watch/ExternalChangeTimingTest.kt`
- Modify: `README.md` (or a `docs/verification-checklist.md` if the repo has one by the time this is executed) — add the manual checklist from (B).

**(A) Headless-verifiable timing tests:**

1. **Out-of-order batch arrival.** `BulkFileListener.after()` receives a `List<VFileEvent>` — a single refresh (e.g., triggered by frame activation covering many files) can bundle changes to several watched files in one call. Write a test that modifies files B then A directly on disk (in that order), forces one batch refresh, and asserts the coordinator processes and emits for **both**, each with its own correct direction — order of iteration must not cause A's event to be attributed to B's `ExpectedContent` or vice versa (a plausible bug if the background-dispatch queue in Task 5 Step 1 accidentally shares mutable state across items instead of capturing per-item `relativePath`/`file` in the closure).
2. **Stale timestamp non-detection.** Per the VFS docs (Sources), refresh is timestamp-based: "If a file's contents were changed, but its timestamp remained the same, the IntelliJ Platform will not pick up the updated contents." Write a test that writes new content to a file with `Files.setLastModifiedTime` explicitly pinned to the old value, forces a refresh, and asserts **no** event fires — documenting this as a known, accepted false-negative window (same-second-timestamp-preserving writes are rare but not impossible on coarse-grained filesystems) rather than a bug to chase. This directly informs the Self-Review fidelity note below.
3. **Editor-save vs external-write race, same file, same tick.** Save through the editor and, on a separate thread, write directly to the same file's path before the save's VFS event is processed — assert the coordinator doesn't crash and that at most one of {Task 4's save-check, Task 5's VFS listener} claims the change (exact outcome depends on requestor race semantics; the test's job is to prove no double-emission and no lost update, not to pin one specific winner).
4. **`ManagingFS.flushPendingUpdates` sanity check.** Given the async-VFS-writes change (Sources), write via the editor's save path, then read the file's bytes through `VirtualFile.contentsToByteArray()` **without** an explicit flush and assert it already reflects the saved content (this is the documented "reads through platform APIs see changes immediately" guarantee) — this test exists to catch a platform-version regression of that guarantee, since Task 4 relies on it.

**(B) VERIFY AT EXECUTION (runIde / manual) — cannot be exercised headlessly:**

- [ ] **Frame-activation refresh actually fires for files changed while unfocused.** In a `runIde` sandbox: open the plugin's test project, alt-tab away, edit a watched file in an external editor (or `echo >>` from a terminal), alt-tab back into the IDE, and confirm an `fs.external_change` event appears in the session log within a few seconds of refocus — without requiring any manual "Reload from disk" action.
- [ ] **Native file watcher vs frame-activation backstop.** With "Synchronize files on frame activation" (Settings | Appearance & Behavior | System Settings) left at its default, edit a watched file externally *while the IDE window stays focused* (e.g., a second monitor terminal without switching focus) — confirm the native OS file watcher alone (not focus-triggered refresh) delivers the event, since students may never alt-tab away at all (e.g., running Claude Code in an integrated terminal panel inside the same IDE window).
- [ ] **Latency measurement.** Time the gap between an external write and the corresponding `fs.external_change` event's `wall` timestamp across a few runs, both same-window-terminal and alt-tab scenarios. PRD §4.5 doesn't specify a latency SLA, but a multi-second lag would look suspicious in replay (the event's `wall` clock reflects detection time, not write time — same caveat VS Code's `FileSystemWatcher` has, PRD §4.5's "timing note").
- [ ] **"Synchronize files on frame activation" disabled.** Confirm what happens to detection latency if a student disables this IDE setting — the native watcher should still cover it, but this is worth confirming isn't silently degraded to "only detected next full IDE restart."
- [ ] **Network/container filesystem check** (lower priority, note only): if course infrastructure ever runs student IDEs against a network-mounted or containerized filesystem, confirm the native watcher still works there — this is a known IntelliJ weak spot (Sources: threaded/timestamp caveats) unrelated to this plugin's code but worth a one-line note in `docs/admin-guide.md`-equivalent if this repo grows one.

**Steps:**
- [ ] Write and pass tests (A)1–4.
- [ ] Run `./gradlew :recorder:test --tests 'dev.provenance.recorder.watch.*'` — full watch-package suite green.
- [ ] Add the (B) checklist to the README/verification doc, explicitly marked unchecked (to be completed once Plan 3's `runIde` sideload build exists).
- [ ] Commit `test(recorder): external-change focus/refresh timing edge cases + manual verification checklist`.

---

## Self-Review

**Spec coverage (PRD §4.5, design.md §4's risk register):** all three detection paths ported — save-time check (Task 4), `BulkFileListener` VFS wiring (Task 5), reload-from-disk (Task 6) — unified behind one `ExternalChangeCoordinator` (Task 7) emitting a single `fs.external_change` shape (Tasks 2–3) built on a direction-pinned expected-content model (Task 1). Task 8 is the dedicated risk-mitigation task the prompt and design.md both call for.

**Correction made during research:** the event kind is `fs.external_change` (verified in `packages/log-core/src/events.ts:137,236`), not `ext.change` as the design sketch's shorthand suggested. Used the verified name throughout.

**Non-obvious choices, surfaced per CLAUDE.md's "conventions for talking to me":**
1. `ExpectedContent`'s delta model is offset-based (IntelliJ `DocumentEvent` shape), dropping VS Code's line/character-to-offset conversion entirely — a real simplification, not just a rename.
2. Path 3 (reload-from-disk) needs no heuristic in IntelliJ — `fileContentReloaded` is an exact signal VS Code doesn't have. Flagged as a place the port is *more* precise than the original.
3. `isFromSave()` replaces VS Code's 250ms timing-tolerance window as the *primary* modify-vs-save dedup mechanism (Task 5); the timing window is kept only as a secondary backstop for saves that trigger a second, differently-requestored write (e.g. a format-on-save plugin). This is a design improvement worth a second pair of eyes, since it's new territory the VS Code recorder never had.
4. `diff_size` uses UTF-16 code-unit length (matches JS `.length` exactly); `new_content_size` uses UTF-8 byte length; `new_content_head`/`tail` slicing is code-unit based despite the byte-based size gate. All three are faithful-port decisions, not fixes — pinned by the Task 2 conformance vector specifically because this is an easy silent divergence.
5. `explanation` (formatter/git false-positive suppression) is deliberately out of scope — the field is threaded through as an always-`null`-for-now optional so Plan 7 (terminal + git wiring) can populate it later without touching this plan's code. This keeps Plan 5 in scope per CLAUDE.md's "stay in scope" rule; PRD §4.5's false-positive handling is not fully implemented until Plan 7 lands. **This is a known gap after Plan 5**, not a silent omission — Prettier/Black-on-save and git-checkout scenarios will show up as unexplained `fs.external_change` events until Plan 7.

**FIDELITY / RISK NOTE (read this before executing):** This is the plan the whole series' §4 risk register points at. Two different kinds of risk are mixed together here and the tasks above try to separate them, but be honest about the boundary:

- Everything in Task 8(A), and all of Tasks 1–7's test suites, are **headless-verifiable and will genuinely prove correctness** of direction, dedup, and payload shape under IntelliJ's real VFS refresh mechanism (`markDirtyAndRefresh` against a real `LocalFileSystem` temp dir triggers the actual code path, not a mock).
- Task 8(B) — whether the *frame-activation refresh timing* behaves as documented in a real windowed IDE, whether the native file watcher's latency is acceptable for a same-window-terminal Claude-Code-CLI scenario (the PRD's primary motivating case, §4.5 second-to-last paragraph), and whether `isFromSave()` reliably tags every real editor save across the IDE versions this plugin targets — **cannot be verified by this plan's automated test suite.** These require `runIde` + manual alt-tab/terminal testing, and are listed as an explicit unchecked checklist rather than asserted as done. Do not mark this subsystem "complete" until that checklist has been run at least once against a real IDE window.
- The `isFromSave()`/`isFromRefresh()` mechanism itself is based on the `SavingRequestor` opt-in pattern, which is documented behavior (confirmed via the 2026 async-VFS-writes platform blog post and `intellij-community` source) but is an implementation detail of `FileDocumentManagerImpl`, not a version-pinned public contract the way `log-core`'s hash chain is. A future IntelliJ Platform release could change this without notice; Task 8(A)'s tests will catch a regression the next time they're run against a newer platform version, which is the best available guardrail short of pinning to a single IDE build.
