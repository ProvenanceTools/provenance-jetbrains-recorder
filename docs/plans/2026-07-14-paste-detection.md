# Three-Signal Paste Detection Implementation Plan (Plan 6 of the provjet series)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-derive recorder PRD §4.3's three-signal paste detection against the IntelliJ Platform SDK — a size/shape classifier on the resulting document edit (signal 1), an `EditorPaste` action interception (signal 2), and a genuine clipboard-content comparison (signal 3) — and wire them into `paste` / `doc.change[source]` / `paste.anomaly` events that are byte-for-byte payload-compatible with what the VS Code recorder emits, with **no format change**.

**Architecture:** A pure-Kotlin `recorder/.../paste/` package (classifier, payload builder, text-similarity, and the correlation/decision state machine — all zero-IntelliJ-dependency, JUnit-tested) underneath a thin `recorder/.../wiring/paste/` package that does the actual IntelliJ interception: an `editorActionHandler` wrapper on the `EditorPaste` action id (**not** the generic `$Paste` — see Global Constraints) reads `CopyPasteManager` clipboard content and delegates to the original handler, which synchronously mutates the `Document` and fires the `DocumentListener` this plan's glue code consults. This mirrors CLAUDE.md's "test the event→log-entry transform as a pure function, separately from the platform wiring" split, and follows the exact wrapping pattern IntelliJ's own `PasteHandler` uses internally (cited below).

**Tech Stack:** Kotlin/JVM, IntelliJ Platform SDK (`editorActionHandler` extension point, `CopyPasteManager`, `DocumentListener`/`DocumentEvent`), JUnit 5 (pure logic) + `BasePlatformTestCase` (action interception). Builds on `core/`'s `Sha256` (Plan 1) and on the plugin scaffold + `RecorderState`/`project.service<T>()` pattern established in Plan 3. Declares (rather than assumes silently) the minimal seam it needs from Plan 4's not-yet-written doc-event wiring — see "Integration with Plan 4" below.

## Plan series (context)

This is Plan 6 of the series derived from `docs/design.md`. Plans 1–3 are done; **Plans 4 and 5 do not exist as files yet** (design.md §8 builds them before paste detection, but this plan was requested out of sequence because paste detection is design.md §4's highest-risk wiring item and was assigned ahead of the queue). Where this plan needs something Plan 4 would normally provide (the document-change listener seam, the session emit function), it declares the minimal interface it needs and flags it for reconciliation — the same pattern Plan 3 used for `core/` primitives it wasn't 100% certain of ("check first, adapt if it already exists differently").

- **Plan 1 (done):** `core/` — hashing, JCS, chain, NDJSON, chain-validator, conformance gate.
- **Plan 2 (done):** `core/` — bundle manifest, session keypair, signed checkpoints, manifest parse+verify.
- **Plan 3 (done):** plugin scaffold, activation, manifest verification, status-bar widget, sideload build.
- **Plan 4 (not yet written):** `doc.open/change/save/close` wiring + atomic session writer + bundle seal.
- **Plan 5 (not yet written):** external-change detection (VFS — highest-risk per design.md, but risk-class-independent of this plan).
- **Plan 6 (this):** three-signal paste detection.
- **Plan 7:** terminal + git wiring + plugin snapshot.
- **Plan 8:** checkpoints wiring + chain recovery + disk-full degraded mode.
- **Plan 9:** `build:prod` course-key embedding + `extension_hash` + Marketplace packaging + monorepo allowlist/golden-vector changes.

## Global Constraints

(Inherits Plans 1–3's Global Constraints: format is a fixed contract owned by `log-core`; `core/` stays IntelliJ-free; do not hand-roll JCS; determinism in tests; `git commit --no-gpg-sign`, no Claude co-author trailer, explicit pathspec.) Additional, for this plan:

- **No format change, anywhere in this plan.** `PastePayload`, `DocChangePayload`, `PasteAnomalyPayload` are pinned in the monorepo's `packages/log-core/src/events.ts`:
  - `DocChangePayload` (events.ts:80-84): `{ path: string; deltas: Array<{ range: Range; text: string }>; source: 'typed' | 'paste_likely' | 'paste_confirmed' }`.
  - `PastePayload` (events.ts:95-103): `{ path: string; range: Range; length: number; sha256: string; content?: string; content_head?: string; content_tail?: string }` — **no `source` field**; a `paste`-kind event is inherently the confirmed shape.
  - `PasteAnomalyPayload` (events.ts:199-202): `{ intercepted_count: number; large_insert_count: number }`.
  - `Range`/`Position`/`DocChangeDelta` (events.ts:10-18, 75-78).
  All three event kinds (`paste`, `doc.change`, `paste.anomaly`) and the `'paste_confirmed'` enum value **already exist** in the pinned format — this plan is the first to actually *emit* `'paste_confirmed'` (see the fidelity note on this below), which is populating an existing contract value, not changing the contract.
- **The PRD's literal §4.3 rule (recorder PRD, `/Users/aaryanmehta/projects/provenance/docs/prd.md:161-176`), which this plan re-derives, not re-invents:**
  1. *Bulk-insertion classifier* — single delta ≥30 chars, OR aggregate ≥30 chars with at least one delta containing a newline. Single empty-range delta → `paste`; anything else → `doc.change` with `source: "paste_likely"`.
  2. *Editor paste command intercept* — "wraps the default … paste [action] and emits a paste marker immediately before the resulting `doc.change` fires. Pairing the two by `seq` gives us a high-confidence label."
  3. *"External clipboard read"* — **PRD's own wording is aspirational; the actual VS Code implementation (`packages/recorder/src/events/paste-reconciler.ts`) never reads clipboard bytes.** It compares a *count* of command-intercepts against a *count* of bulk-insertion classifications on a 5s rolling window and emits `paste.anomaly` on divergence beyond a tolerance. Confirmed by reading the source: `paste-command-intercept.ts` only sets a timestamp flag; `paste-reconciler.ts`'s `ReconcilerDeps` takes `getInterceptedCount`/`getLargeInsertCount`, not a clipboard reader. **This is a deliberate, cited deviation point for the IntelliJ port — see "Signal 3: a real upgrade over the VS Code source" below.**
  - VS Code's `isConfirmed` (from `consumeIfPasteExpected`) is computed in `doc-wiring.ts:376` but explicitly discarded (`void isConfirmed; // intentionally unused in v1`, `doc-wiring.ts:379`) — `'paste_confirmed'` has never been emitted by any shipped VS Code recorder build.
- **Ported from (VS Code recorder source, read in full before writing this plan):**
  - `packages/recorder/src/events/paste-classifier.ts` — signal 1, `classifyChange`, `PASTE_MIN_INSERT_CHARS = 30`.
  - `packages/recorder/src/events/paste-payload.ts` — payload truncation (`MAX_INLINE_BYTES = 4096`, `HEAD_TAIL_BYTES = 512`), sha256 of the pasted text.
  - `packages/recorder/src/wiring/paste-command-intercept.ts` — signal 2, VS Code's workaround (a *separate* command, since VS Code disallows re-registering `editor.action.clipboardPasteAction`). **IntelliJ does not have this limitation** — see Task 6.
  - `packages/recorder/src/events/paste-reconciler.ts` — signal 3 as actually implemented (count-based).
  - `packages/recorder/src/wiring/doc-wiring.ts:367-416` — the branching glue that decides `paste` vs `doc.change[source]`.
- **Determinism.** `PasteCorrelator` and `PasteAnomalyReconciler` take an injected clock (`getNow: () -> Long`), never `System.currentTimeMillis()` directly, matching CLAUDE.md's clock-injection rule and Plan 1/2's precedent.
- **No background task without a dispose path.** The periodic anomaly-check ticker (Task 7) has an explicit `dispose()`/`Disposable` registration, mirroring the VS Code reconciler's `clearInterval` + `unref()` discipline (CLAUDE.md: "every listener, watcher, timer... has a dispose() hook").

## Integration with Plan 4 (declared seam, not yet reconciled)

Plan 4 does not exist yet, so this plan cannot literally modify its `DocumentListener`. It declares the minimal interface it needs:

- `interface RecorderEventEmitter { fun emit(kind: String, data: JsonObject) }` — the assumed shape of Plan 4's session-writer emit surface (mirrors VS Code's `sessionHost.emit(kind, data)`, used identically throughout `extension.ts`). Task 7 defines this interface locally; **when Plan 4 lands, replace this with Plan 4's actual emitter type and delete the local placeholder** — the call sites (`emitter.emit("paste", ...)`, `emitter.emit("doc.change", ...)`, `emitter.emit("paste.anomaly", ...)`) should not need to change shape, only the type they're injected with.
- A `DocumentListener` registered per watched document. **This plan owns that listener** (`PasteAwareDocumentListener`, Task 7) rather than assuming Plan 4 already has one to hook into, since Plan 4 doesn't exist yet. **When Plan 4 is implemented, it must not register a second, competing `DocumentListener` on the same documents** — `Document.addDocumentListener` is additive, so two independent listeners emitting `doc.change` for the same edit would double-log. Plan 4's implementer should either (a) extend `PasteAwareDocumentListener` to also handle `doc.open`/`doc.save`/`doc.close` bookkeeping, or (b) have this listener become the single doc-change entry point and have Plan 4 supply only the non-paste plumbing (workspace-relative path resolution, expected-content model updates). Flagging here so it isn't silently duplicated later.

---

## IntelliJ Platform APIs settled on for this plan (with sources)

Researched via `WebSearch`/`WebFetch` against `plugins.jetbrains.com/docs/intellij` and, where the docs pages didn't have the answer, the `JetBrains/intellij-community` source on GitHub directly (via `gh api`) — cited per row. Fetched/searched 2026-07-14.

| Concern | API | Source |
|---|---|---|
| Editor-bound paste action id | `IdeActions.ACTION_EDITOR_PASTE = "EditorPaste"` — **this, not `$Paste`, is what's bound to Cmd+V/Ctrl+V inside a text editor.** `IdeActions.ACTION_PASTE = "$Paste"` is the generic, non-editor-specific paste action (used by e.g. the Project tree view's right-click menu; not guaranteed to fire for in-editor keystroke paste). | `platform/ide-core/src/com/intellij/openapi/actionSystem/IdeActions.java` (fetched via `gh api repos/JetBrains/intellij-community/contents/...`), lines 9-13 (`ACTION_EDITOR_*`) vs line 90 (`ACTION_PASTE = "$Paste"`). **This corrects design.md §4's and CLAUDE.md's "`$Paste`" wording** — flagged as a deliberate, cited correction, not a silent deviation. |
| Wrapping the paste handler | `editorActionHandler` extension point: `<editorActionHandler action="EditorPaste" implementationClass="..."/>`, constructor takes the original `EditorActionHandler` to delegate to when not customizing. | [intellij-platform-extension-point-list.html](https://plugins.jetbrains.com/docs/intellij/intellij-platform-extension-point-list.html) (EP `com.intellij.editorActionHandler`); concrete registration syntax confirmed against JetBrains's own `PasteHandler` registration in `platform/platform-resources/src/META-INF/LangExtensions.xml:923-925` (`<editorActionHandler action="EditorPaste" implementationClass="com.intellij.codeInsight.editorActions.PasteHandler"/>`), plus a second handler at `order="first"` (line 924-925) proving multiple handlers can chain via `order`. |
| The base class + wrapping pattern | `abstract class EditorActionHandler` — a plugin's `PasteInterceptHandler(originalHandler: EditorActionHandler) : EditorActionHandler()` overrides `doExecute(editor, caret, dataContext)`, does its own work, then calls `originalHandler.execute(editor, caret, dataContext)`. | `platform/lang-impl/src/com/intellij/codeInsight/editorActions/PasteHandler.java` (fetched in full) — JetBrains's own `PasteHandler(EditorActionHandler originalAction)` constructor and `doExecute`/`execute` override is the literal pattern this plan's `PasteInterceptHandler` copies. |
| Multi-caret / column-mode bypass — **the multi-caret fidelity gotcha** | `PasteHandler.execute()` checks `if (file == null \|\| editor.isColumnMode() \|\| editor.getCaretModel().getCaretCount() > 1) { myOriginalHandler.execute(editor, null, context); return; }` **before** running any of its own reformat/postprocessor logic. Multi-caret paste takes an **entirely separate code path** with no `CopyPastePostProcessor` involvement in `PasteHandler` itself, and — because our wrapper also delegates to `myOriginalHandler` in this branch — we cannot assume the resulting `DocumentEvent`s look like the single-caret case. | `platform/lang-impl/src/com/intellij/codeInsight/editorActions/PasteHandler.java`, `execute()` method (fetched, ~line 103). |
| Reformat-on-paste — **the reformat fidelity gotcha** | Single-caret paste inserts raw clipboard text via `EditorCopyPasteHelperImpl`/`EditorModificationUtil`, then (inside `doPasteAction`) runs every registered `CopyPastePostProcessor` (`CopyPastePostProcessor.EP_NAME.getExtensionList()`) and, separately, applies `CodeInsightSettings.REFORMAT_ON_PASTE`-driven re-indentation (`indentOptions` branch). Line-separator conversion also happens between clipboard read and insert (`TextBlockTransferable.convertLineSeparators`). **The text that lands in the `Document` is frequently not byte-identical to the clipboard text**, even for a "clean" paste with no AI-assistant involvement. | Same file, `doPasteAction()` body (fetched) — `settings.REFORMAT_ON_PASTE`, `CopyPastePostProcessor.EP_NAME`, `TextBlockTransferable.convertLineSeparators`. |
| Reading the clipboard | `CopyPasteManager.getInstance().getContents(): Transferable?`, then `transferable.getTransferData(DataFlavor.stringFlavor) as? String`. This is the exact call JetBrains's own `PasteHandler.getContentsToPasteToEditor()` makes when no custom `Producer` is supplied — i.e., reading the clipboard the same way the platform's paste handler itself does. | `platform/editor-ui-api/src/com/intellij/openapi/ide/CopyPasteManager.java` (fetched) + `PasteHandler.getContentsToPasteToEditor()` (same file as above). |
| Correlating with the resulting document mutation | `DocumentListener.documentChanged(event: DocumentEvent)`; `DocumentEvent.getOffset()/getOldLength()/getNewLength()/getOldFragment()/getNewFragment()`. IntelliJ's `Document` fires listeners **synchronously** inside the mutation call (`insertString`/`replaceString`) — confirmed indirectly: `PasteHandler.execute()`/`doPasteAction()` call document-mutation helpers directly on the same call stack with no async hop, so a `DocumentListener` registered on the same document sees the change before `execute()` returns. **VERIFY AT EXECUTION**: this is inferred from reading the single-caret code path, not from an explicit ordering guarantee in the SDK docs — confirm empirically via `runIde` (Task 6's test exercises this synchronously in a headless fixture, which is a reasonable proxy but not identical to a live UI paste). | `com.intellij.openapi.editor.event.DocumentEvent`/`DocumentListener` (well-established platform API); synchronicity inferred from `PasteHandler.java` as cited above. |
| Rejected alternative for signal 2 | `AnActionListener` (`beforeActionPerformed(action, event)` / `afterActionPerformed(action, event, result)`, `AnActionListener.TOPIC`, application-level message bus) — **considered and rejected as the primary mechanism** in favor of the `editorActionHandler` wrapper, because (a) it fires for *every* action in the IDE, requiring ID-string filtering with no compile-time guarantee against action-id drift, (b) it gives no synchronous handle on the specific `Editor`/`Document` instance the way a wrapped handler's `doExecute(editor, caret, dataContext)` parameters do, and (c) it does not naturally give us "run before the real handler, with the chance to read the clipboard first" the way constructor-wrapping does — we'd still need a separate `CopyPasteManager` read racing against the real handler's own internal read. Documented here per CLAUDE.md's "if you make a non-obvious choice, explain it" rule; design.md and CLAUDE.md list `AnActionListener` as *an* option, not a mandate. | `platform/editor-ui-api/src/com/intellij/openapi/actionSystem/ex/AnActionListener.java` (fetched — confirmed current method signatures and `TOPIC` declaration). |
| Headless test for the wrapped handler | `CodeInsightTestFixture.performEditorAction(actionId: String)` simulates running an editor action (including a registered `editorActionHandler`) against the fixture's in-memory editor. Clipboard is set via plain `java.awt.datatransfer.StringSelection` + `CopyPasteManager.getInstance().setContents(...)`. | `com.intellij.testFramework.fixtures.CodeInsightTestFixture` (`platform/testFramework/...`); `performEditorAction` is a long-established test-framework method used throughout `intellij-community`'s own test suite (per search results). `BasePlatformTestCase` itself already used and cited in Plan 3. |

---

### Task 1: Paste models + the bulk-insertion classifier (signal 1)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/paste/PasteModels.kt`
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/paste/PasteClassifier.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/paste/PasteClassifierTest.kt`

**Interfaces:**
- Consumes: nothing (zero IntelliJ deps — this is the "pure function" half of the port).
- Produces:
  - `data class Position(val line: Int, val character: Int)`, `data class Range(val start: Position, val end: Position)`, `data class DocChangeDelta(val range: Range, val text: String)` — Kotlin mirror of log-core's `Position`/`Range`/`DocChangeDelta` (`events.ts:10-18, 75-78`). **If Plan 4 has already defined equivalent types under a different package by the time this task executes, unify on Plan 4's types and delete these — do not maintain two copies of the same shape** (same caution Plan 3 gave for `core/` primitives).
  - `enum class PasteClassification { TYPED, PASTE_LIKELY }`
  - `const val PASTE_MIN_INSERT_CHARS = 30`
  - `fun classifyChange(deltas: List<DocChangeDelta>): PasteClassification`

- [ ] **Step 1: Write the failing test**

`recorder/src/test/kotlin/dev/provenance/recorder/paste/PasteClassifierTest.kt`:
```kotlin
package dev.provenance.recorder.paste

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PasteClassifierTest {
    private fun delta(text: String, sameLineLen: Int = 0) =
        DocChangeDelta(Range(Position(0, sameLineLen), Position(0, sameLineLen)), text)

    @Test
    fun `empty delta list is typed`() {
        assertEquals(PasteClassification.TYPED, classifyChange(emptyList()))
    }

    @Test
    fun `single delta under threshold is typed`() {
        assertEquals(PasteClassification.TYPED, classifyChange(listOf(delta("x".repeat(29)))))
    }

    @Test
    fun `single delta at or over threshold is paste_likely`() {
        assertEquals(PasteClassification.PASTE_LIKELY, classifyChange(listOf(delta("x".repeat(30)))))
    }

    @Test
    fun `single large delta with non-empty range is still paste_likely`() {
        // Covers a replacement edit, not just an empty-range insert (PRD §4.3 point 1).
        val d = DocChangeDelta(Range(Position(0, 0), Position(0, 5)), "y".repeat(40))
        assertEquals(PasteClassification.PASTE_LIKELY, classifyChange(listOf(d)))
    }

    @Test
    fun `multi-delta aggregate over threshold WITHOUT a newline is typed (multi-cursor typing)`() {
        val deltas = (1..10).map { delta("abc") } // 30 chars total, no newlines
        assertEquals(PasteClassification.TYPED, classifyChange(deltas))
    }

    @Test
    fun `multi-delta aggregate over threshold WITH a newline is paste_likely (tool-applied WorkspaceEdit shape)`() {
        val deltas = listOf(delta("a".repeat(20)), delta("b\nc".repeat(4)))
        assertEquals(PasteClassification.PASTE_LIKELY, classifyChange(deltas))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.paste.PasteClassifierTest'`
Expected: FAIL — `DocChangeDelta` / `classifyChange` unresolved.

- [ ] **Step 3: Write the implementation**

`PasteModels.kt`:
```kotlin
package dev.provenance.recorder.paste

/** Mirrors log-core's Position (events.ts:10-13). Zero IntelliJ deps. */
data class Position(val line: Int, val character: Int)

/** Mirrors log-core's Range (events.ts:15-18). */
data class Range(val start: Position, val end: Position)

/** Mirrors log-core's DocChangeDelta (events.ts:75-78). */
data class DocChangeDelta(val range: Range, val text: String)
```

`PasteClassifier.kt`:
```kotlin
package dev.provenance.recorder.paste

/**
 * Signal 1 of three-signal paste detection (recorder PRD §4.3 point 1).
 * Direct port of packages/recorder/src/events/paste-classifier.ts's
 * classifyChange — same two-rule logic, same threshold. Pure function,
 * editor-agnostic: works identically for VS Code's TextDocumentChangeEvent
 * deltas and for whatever this plugin's DocumentEvent-to-delta glue produces.
 */
enum class PasteClassification { TYPED, PASTE_LIKELY }

const val PASTE_MIN_INSERT_CHARS = 30

fun classifyChange(deltas: List<DocChangeDelta>): PasteClassification {
    if (deltas.isEmpty()) return PasteClassification.TYPED

    var totalInsertedChars = 0
    var maxSingleDeltaChars = 0
    var anyDeltaHasNewline = false

    for (delta in deltas) {
        val len = delta.text.length
        totalInsertedChars += len
        if (len > maxSingleDeltaChars) maxSingleDeltaChars = len
        if (!anyDeltaHasNewline && delta.text.contains('\n')) {
            anyDeltaHasNewline = true
        }
    }

    // Rule 1: a single delta carries >= threshold chars on its own. Covers
    // classical paste (empty-range insert) AND large replacement edits.
    if (maxSingleDeltaChars >= PASTE_MIN_INSERT_CHARS) {
        return PasteClassification.PASTE_LIKELY
    }

    // Rule 2: aggregate >= threshold AND at least one delta has a newline.
    // Distinguishes a multi-line bulk edit (e.g. a WorkspaceEdit-shaped tool
    // apply) from multi-cursor typing (many small single-line inserts).
    if (totalInsertedChars >= PASTE_MIN_INSERT_CHARS && anyDeltaHasNewline) {
        return PasteClassification.PASTE_LIKELY
    }

    return PasteClassification.TYPED
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.paste.PasteClassifierTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/paste/PasteModels.kt \
  recorder/src/main/kotlin/dev/provenance/recorder/paste/PasteClassifier.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/paste/PasteClassifierTest.kt
git commit --no-gpg-sign -m "feat(recorder): paste models + bulk-insertion classifier (signal 1)"
```

---

### Task 2: Paste payload builder (truncation + hash)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/paste/PastePayloadBuilder.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/paste/PastePayloadBuilderTest.kt`

**Interfaces:**
- Consumes: `dev.provenance.core.Sha256` (Plan 1 Task 1).
- Produces:
  - `data class PastePayloadFields(val length: Int, val sha256: String, val content: String? = null, val contentHead: String? = null, val contentTail: String? = null)`
  - `const val MAX_INLINE_BYTES = 4096`, `const val HEAD_TAIL_BYTES = 512`
  - `fun buildPastePayload(text: String): PastePayloadFields`
  - `fun PastePayloadFields.toJsonObject(path: String, range: Range): kotlinx.serialization.json.JsonObject` — emits the on-wire `PastePayload` shape (events.ts:95-103): `path`, `range`, `length`, `sha256`, and `content`/`content_head`/`content_tail` only when non-null.

**Test intent:** port `packages/recorder/src/events/paste-payload.ts` behavior exactly — length ≤ 4096 bytes inlines full content; length > 4096 truncates to 512-char head/tail. `Buffer.byteLength(text, 'utf8')` in the TS version is UTF-8 byte length, not char length — Kotlin's `String.toByteArray(Charsets.UTF_8).size` is the equivalent; a test with multi-byte codepoints (e.g. emoji) pins this so a naive `.length` substitution doesn't silently regress it.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.provenance.recorder.paste

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import dev.provenance.core.Sha256

class PastePayloadBuilderTest {
    @Test
    fun `short text is inlined fully`() {
        val fields = buildPastePayload("hello world")
        assertEquals("hello world", fields.content)
        assertNull(fields.contentHead)
        assertNull(fields.contentTail)
        assertEquals(Sha256.hex("hello world"), fields.sha256)
        assertEquals(11, fields.length)
    }

    @Test
    fun `text over 4096 bytes is truncated to head and tail`() {
        val big = "a".repeat(5000)
        val fields = buildPastePayload(big)
        assertNull(fields.content)
        assertNotNull(fields.contentHead)
        assertNotNull(fields.contentTail)
        assertEquals(512, fields.contentHead!!.length)
        assertEquals(512, fields.contentTail!!.length)
        assertEquals(5000, fields.length)
    }

    @Test
    fun `length is UTF-8 byte length, not char count`() {
        // Each emoji below is a 4-byte UTF-8 codepoint but a Kotlin String
        // reports it as 2 UTF-16 chars — pin the byte-length distinction.
        val text = "😀😀" // two 😀 (U+1F600)
        val fields = buildPastePayload(text)
        assertEquals(8, fields.length) // 2 * 4 bytes
    }

    @Test
    fun `boundary at exactly 4096 bytes still inlines`() {
        val exact = "b".repeat(4096)
        val fields = buildPastePayload(exact)
        assertNotNull(fields.content)
        assertNull(fields.contentHead)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.paste.PastePayloadBuilderTest'`
Expected: FAIL — `buildPastePayload` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package dev.provenance.recorder.paste

import dev.provenance.core.Sha256
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Ports packages/recorder/src/events/paste-payload.ts. Stores full pasted
 * text inline up to MAX_INLINE_BYTES; larger pastes store a hash + head/tail
 * truncation. length/truncation are UTF-8 byte-length based (not char count),
 * matching the TS version's Buffer.byteLength usage.
 */
const val MAX_INLINE_BYTES = 4096
const val HEAD_TAIL_BYTES = 512

data class PastePayloadFields(
    val length: Int,
    val sha256: String,
    val content: String? = null,
    val contentHead: String? = null,
    val contentTail: String? = null,
)

fun buildPastePayload(text: String): PastePayloadFields {
    val byteLength = text.toByteArray(Charsets.UTF_8).size
    val hashHex = Sha256.hex(text)

    return if (byteLength <= MAX_INLINE_BYTES) {
        PastePayloadFields(length = byteLength, sha256 = hashHex, content = text)
    } else {
        PastePayloadFields(
            length = byteLength,
            sha256 = hashHex,
            contentHead = text.take(HEAD_TAIL_BYTES),
            contentTail = text.takeLast(HEAD_TAIL_BYTES),
        )
    }
}

private fun Position.toJsonObject(): JsonObject = buildJsonObject {
    put("line", line)
    put("character", character)
}

private fun Range.toJsonObject(): JsonObject = buildJsonObject {
    put("start", start.toJsonObject())
    put("end", end.toJsonObject())
}

/** On-wire PastePayload shape (events.ts:95-103). Optional fields omitted, not null. */
fun PastePayloadFields.toJsonObject(path: String, range: Range): JsonObject = buildJsonObject {
    put("path", path)
    put("range", range.toJsonObject())
    put("length", length)
    put("sha256", sha256)
    content?.let { put("content", it) }
    contentHead?.let { put("content_head", it) }
    contentTail?.let { put("content_tail", it) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.paste.PastePayloadBuilderTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/paste/PastePayloadBuilder.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/paste/PastePayloadBuilderTest.kt
git commit --no-gpg-sign -m "feat(recorder): paste payload builder (4KB inline / 512B head-tail truncation)"
```

---

### Task 3: Reformat-tolerant text similarity (signal 3's comparison primitive)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/paste/PasteTextSimilarity.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/paste/PasteTextSimilarityTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `object PasteTextSimilarity { fun similarity(clipboardText: String, insertedText: String): Double }` — returns `0.0..1.0`; `1.0` only for exact match after normalization.
  - `const val PASTE_CONFIRM_SIMILARITY_THRESHOLD = 0.7` — **TUNE AT EXECUTION**: this value is a starting point, not a measured one. Real reformat-on-paste re-indentation ratios differ by language (Python's forced 4-space reindent vs. Java's brace-aware reindent vs. no-op for plain text) and by how much of the pasted block was already correctly indented. Budget a pass over real `runIde` paste samples across at least two languages before trusting this threshold in production heuristics.

**Why this exists (the reformat-on-paste problem, cited in the API table above):** IntelliJ's `PasteHandler.doPasteAction()` runs `CopyPastePostProcessor`s and `CodeInsightSettings.REFORMAT_ON_PASTE`-driven re-indentation between reading the clipboard and the text actually landing in the `Document`. A byte-exact `clipboardText == insertedText` check would false-negative on nearly every real-world code paste. This function normalizes line endings and collapses runs of horizontal whitespace (the dimension reformat mostly touches — indentation — not the token content), then does a length-ratio-weighted containment check rather than full edit-distance (Levenshtein is O(n·m); this runs off the document-change firehose path, and CLAUDE.md's "handlers must be fast" rule applies even off the keystroke path itself).

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.provenance.recorder.paste

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PasteTextSimilarityTest {
    @Test
    fun `identical text is 1_0`() {
        assertEquals(1.0, PasteTextSimilarity.similarity("fun f() {}", "fun f() {}"))
    }

    @Test
    fun `re-indented text (reformat-on-paste) still scores high`() {
        val clip = "if (x) {\ny = 1\n}"
        val inserted = "    if (x) {\n        y = 1\n    }" // reformatter added indentation
        assert(PasteTextSimilarity.similarity(clip, inserted) >= PASTE_CONFIRM_SIMILARITY_THRESHOLD)
    }

    @Test
    fun `crlf-to-lf line ending conversion does not reduce score`() {
        val clip = "line1\r\nline2\r\n"
        val inserted = "line1\nline2\n"
        assertEquals(1.0, PasteTextSimilarity.similarity(clip, inserted))
    }

    @Test
    fun `unrelated text scores 0`() {
        assertEquals(0.0, PasteTextSimilarity.similarity("completely different content here", "xyz"))
    }

    @Test
    fun `empty clipboard and empty inserted text is a degenerate 1_0 match`() {
        assertEquals(1.0, PasteTextSimilarity.similarity("", ""))
    }

    @Test
    fun `empty clipboard against non-empty inserted text is 0`() {
        assertEquals(0.0, PasteTextSimilarity.similarity("", "something"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.paste.PasteTextSimilarityTest'`
Expected: FAIL — `PasteTextSimilarity` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package dev.provenance.recorder.paste

/**
 * Reformat-tolerant, cheap similarity check between what was on the clipboard
 * at paste time and what actually landed in the Document. See Task 3's plan
 * note for why exact-match is unusable here (CopyPastePostProcessors +
 * REFORMAT_ON_PASTE + line-ending conversion all run between clipboard read
 * and document insert on IntelliJ's paste path).
 *
 * TUNE AT EXECUTION: PASTE_CONFIRM_SIMILARITY_THRESHOLD is a starting value,
 * not measured against real paste samples. Revisit once runIde manual testing
 * (Task 6) or real submission data is available.
 */
const val PASTE_CONFIRM_SIMILARITY_THRESHOLD = 0.7

object PasteTextSimilarity {
    private val WHITESPACE_RUN = Regex("[ \t]+")

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace(WHITESPACE_RUN, " ").trim()

    /**
     * 0.0..1.0. 1.0 only for exact match after normalization. For a
     * containment match (the common reformat-on-paste case, where the
     * inserted text is the clipboard text plus added indentation), scores by
     * the length ratio of the shorter (original) text to the longer
     * (reformatted) text — a close-to-1.0 ratio means little was added
     * beyond whitespace; a low ratio means the platform's postprocessors
     * substantially rewrote the content (still worth surfacing as a lower
     * confidence, not a hard reject).
     */
    fun similarity(clipboardText: String, insertedText: String): Double {
        val a = normalize(clipboardText)
        val b = normalize(insertedText)
        if (a.isEmpty() || b.isEmpty()) return if (a == b) 1.0 else 0.0
        if (a == b) return 1.0

        val longer = if (a.length >= b.length) a else b
        val shorter = if (a.length >= b.length) b else a
        return if (longer.contains(shorter)) {
            shorter.length.toDouble() / longer.length
        } else {
            0.0
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.paste.PasteTextSimilarityTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/paste/PasteTextSimilarity.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/paste/PasteTextSimilarityTest.kt
git commit --no-gpg-sign -m "feat(recorder): reformat-tolerant paste text similarity (signal 3 primitive)"
```

---

### Task 4: `PasteCorrelator` — the reconciliation decision (signals 1+2+3 combined)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/paste/PasteCorrelator.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/paste/PasteCorrelatorTest.kt`

**Interfaces:**
- Consumes: `DocChangeDelta`, `classifyChange`, `PasteClassification` (Task 1); `buildPastePayload`, `PastePayloadFields` (Task 2); `PasteTextSimilarity`, `PASTE_CONFIRM_SIMILARITY_THRESHOLD` (Task 3).
- Produces:
  - `sealed interface PasteDecision { data class EmitPaste(val fields: PastePayloadFields, val range: Range) : PasteDecision; data class EmitDocChange(val source: String /* "typed" | "paste_likely" | "paste_confirmed" */) : PasteDecision }`
  - `class PasteCorrelator(private val getNow: () -> Long, private val withinMs: Long = 50, private val similarityThreshold: Double = PASTE_CONFIRM_SIMILARITY_THRESHOLD)`:
    - `fun onPasteActionFired(clipboardText: String?)` — called by the wiring (Task 6) *before* delegating to the original `EditorPaste` handler. Increments `interceptedCount`, records `(now, clipboardText)` as the pending expectation.
    - `fun onDocChange(deltas: List<DocChangeDelta>): PasteDecision` — called by the doc-change glue (Task 7) for every batch of deltas. Runs the signal-1 classifier; if `TYPED`, returns `EmitDocChange("typed")` and clears any stale pending expectation. If `PASTE_LIKELY`, increments `largeInsertCount`, checks whether a pending expectation exists within `withinMs` **and** (if it carried clipboard text) whether `PasteTextSimilarity.similarity(...) >= similarityThreshold`; the combined result decides `paste_confirmed` vs `paste_likely` for the `EmitDocChange` branch, or (for a single empty-range delta) routes to `EmitPaste` regardless of confirmation — matching `doc-wiring.ts:395-412`'s branching (confirmation state doesn't change *which event kind* is emitted, only `doc.change`'s `source` value, because `PastePayload` has no `source` field to carry it).
    - `val interceptedCount: Int`, `val largeInsertCount: Int` — running counts, consumed by `PasteAnomalyReconciler` (Task 5).

**Why signal 2 and signal 3 merge into one call here, unlike VS Code:** because this plan's signal-2 mechanism (Task 6's `editorActionHandler` wrapper) runs on the same call stack as the real paste — it reads the clipboard *and* fires *before* delegating to the original handler that performs the actual insert — `onPasteActionFired`'s single clipboard read carries both "an action fired" (signal 2) and "here is what was on the clipboard at that moment" (signal 3) in one timestamped record. VS Code's hacky separate-command intercept (`paste-command-intercept.ts`) could only ever carry a timestamp, because it had no reliable way to read the clipboard itself without racing the real paste — hence VS Code's `paste-reconciler.ts` fell back to counting. This is the deliberate, cited upgrade flagged in Global Constraints.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.provenance.recorder.paste

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PasteCorrelatorTest {
    private fun delta(text: String, startChar: Int = 0, endChar: Int = 0) =
        DocChangeDelta(Range(Position(0, startChar), Position(0, endChar)), text)

    @Test
    fun `typed change with no pending action is EmitDocChange typed`() {
        var now = 0L
        val c = PasteCorrelator(getNow = { now })
        val decision = c.onDocChange(listOf(delta("x")))
        assertTrue(decision is PasteDecision.EmitDocChange)
        assertEquals("typed", (decision as PasteDecision.EmitDocChange).source)
    }

    @Test
    fun `single empty-range paste-shaped delta with no matching action is EmitPaste (unconfirmed but still routed as paste)`() {
        var now = 0L
        val c = PasteCorrelator(getNow = { now })
        val decision = c.onDocChange(listOf(delta("y".repeat(40))))
        assertTrue(decision is PasteDecision.EmitPaste)
    }

    @Test
    fun `action fired then matching doc change within window with matching clipboard is paste_confirmed via EmitDocChange for multi-delta`() {
        var now = 0L
        val c = PasteCorrelator(getNow = { now })
        val text = "z".repeat(40)
        c.onPasteActionFired(clipboardText = text)
        now = 10
        // Multi-delta (non-single-empty-range) shape routes through EmitDocChange.
        val decision = c.onDocChange(listOf(delta(text, 0, 5), delta("more\ntext padding"+"x".repeat(20))))
        assertTrue(decision is PasteDecision.EmitDocChange)
        assertEquals("paste_confirmed", (decision as PasteDecision.EmitDocChange).source)
    }

    @Test
    fun `action fired but doc change arrives after the tolerance window is unconfirmed`() {
        var now = 0L
        val c = PasteCorrelator(getNow = { now }, withinMs = 50)
        c.onPasteActionFired(clipboardText = "a".repeat(40))
        now = 200 // well past 50ms
        val decision = c.onDocChange(listOf(delta("a".repeat(40), 0, 5), delta("b\n" + "c".repeat(30))))
        assertTrue(decision is PasteDecision.EmitDocChange)
        assertEquals("paste_likely", (decision as PasteDecision.EmitDocChange).source)
    }

    @Test
    fun `action fired with clipboard text that does not resemble the inserted text is unconfirmed (reformat mismatch beyond threshold)`() {
        var now = 0L
        val c = PasteCorrelator(getNow = { now })
        c.onPasteActionFired(clipboardText = "totally unrelated clipboard content padding")
        now = 5
        val decision = c.onDocChange(
            listOf(delta("nothing like the clipboard", 0, 5), delta("more\nunrelated padding text here")),
        )
        assertTrue(decision is PasteDecision.EmitDocChange)
        assertEquals("paste_likely", (decision as PasteDecision.EmitDocChange).source)
    }

    @Test
    fun `pending expectation is consumed by one doc change and does not confirm a second`() {
        var now = 0L
        val c = PasteCorrelator(getNow = { now })
        c.onPasteActionFired(clipboardText = "x".repeat(40))
        now = 5
        c.onDocChange(listOf(delta("x".repeat(40), 0, 5), delta("more\n" + "y".repeat(30))))
        val second = c.onDocChange(listOf(delta("x".repeat(40), 0, 5), delta("more\n" + "y".repeat(30))))
        assertEquals("paste_likely", (second as PasteDecision.EmitDocChange).source)
    }

    @Test
    fun `counters track intercepts and large inserts independently`() {
        var now = 0L
        val c = PasteCorrelator(getNow = { now })
        c.onPasteActionFired(clipboardText = null)
        c.onDocChange(listOf(delta("typed"))) // typed, not a large insert
        c.onDocChange(listOf(delta("z".repeat(40)))) // large insert
        assertEquals(1, c.interceptedCount)
        assertEquals(1, c.largeInsertCount)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.paste.PasteCorrelatorTest'`
Expected: FAIL — `PasteCorrelator` / `PasteDecision` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package dev.provenance.recorder.paste

/**
 * The combined-signal decision for one doc-change batch. EmitPaste has no
 * `source`/confirmation field because PastePayload (events.ts:95-103) doesn't
 * carry one — a paste-kind event is inherently the high-confidence shape.
 * Confirmation only matters for the EmitDocChange branch's `source` value.
 */
sealed interface PasteDecision {
    data class EmitPaste(val fields: PastePayloadFields, val range: Range) : PasteDecision
    data class EmitDocChange(val source: String) : PasteDecision
}

/**
 * Combines signal 1 (classifyChange), signal 2 (onPasteActionFired — the
 * EditorPaste action was intercepted), and signal 3 (clipboard-text
 * similarity) into one PasteDecision per doc-change batch.
 *
 * Unlike VS Code's PasteIntercept.consumeIfPasteExpected (which VS Code's own
 * doc-wiring.ts computes and then discards — see Global Constraints), the
 * confirmation computed here is actually used, because IntelliJ's wrapped
 * EditorPaste handler gives a same-call-stack clipboard read VS Code's
 * separate-command workaround never had.
 */
class PasteCorrelator(
    private val getNow: () -> Long,
    private val withinMs: Long = 50,
    private val similarityThreshold: Double = PASTE_CONFIRM_SIMILARITY_THRESHOLD,
) {
    private data class Pending(val atMs: Long, val clipboardText: String?)

    private var pending: Pending? = null
    private var _interceptedCount = 0
    private var _largeInsertCount = 0

    val interceptedCount: Int get() = _interceptedCount
    val largeInsertCount: Int get() = _largeInsertCount

    /** Signal 2 + 3: called by the wiring before delegating to the real paste handler. */
    fun onPasteActionFired(clipboardText: String?) {
        _interceptedCount++
        pending = Pending(getNow(), clipboardText)
    }

    /** Signal 1, reconciled against any pending signal-2/3 expectation. */
    fun onDocChange(deltas: List<DocChangeDelta>): PasteDecision {
        val classification = classifyChange(deltas)
        if (classification == PasteClassification.TYPED) {
            // A typed change does not consume a pending expectation: IntelliJ's
            // paste dispatch is synchronous (see the API table's VERIFY note),
            // so an unrelated typed keystroke should not normally interleave
            // between a paste action firing and its own resulting doc change.
            // If runIde verification (Task 6) finds interleaving does happen
            // in practice, revisit whether TYPED should consume `pending`.
            return PasteDecision.EmitDocChange(source = "typed")
        }

        _largeInsertCount++
        val now = getNow()
        val p = pending
        val confirmed = p != null &&
            (now - p.atMs) <= withinMs &&
            (p.clipboardText == null || run {
                val insertedText = deltas.joinToString("") { it.text }
                PasteTextSimilarity.similarity(p.clipboardText, insertedText) >= similarityThreshold
            })
        pending = null // consume: one action expectation matches at most one doc change

        val isSinglePasteShaped = deltas.size == 1 && deltas[0].range.start == deltas[0].range.end
        return if (isSinglePasteShaped) {
            val fields = buildPastePayload(deltas[0].text)
            PasteDecision.EmitPaste(fields, deltas[0].range)
        } else {
            PasteDecision.EmitDocChange(source = if (confirmed) "paste_confirmed" else "paste_likely")
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.paste.PasteCorrelatorTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/paste/PasteCorrelator.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/paste/PasteCorrelatorTest.kt
git commit --no-gpg-sign -m "feat(recorder): PasteCorrelator combining classifier + action-intercept + clipboard similarity"
```

---

### Task 5: `PasteAnomalyReconciler` — periodic count-diff (`paste.anomaly`)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/paste/PasteAnomalyReconciler.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/paste/PasteAnomalyReconcilerTest.kt`

**Interfaces:**
- Consumes: nothing beyond plain data.
- Produces:
  - `data class PasteCounterSnapshot(val intercepted: Int, val largeInsert: Int)`
  - `data class PasteAnomalyPayload(val interceptedCount: Int, val largeInsertCount: Int)`
  - `fun PasteAnomalyPayload.toJsonObject(): kotlinx.serialization.json.JsonObject` — emits `{ "intercepted_count": ..., "large_insert_count": ... }` (events.ts:199-202).
  - `object PasteAnomalyReconciler { fun check(before: PasteCounterSnapshot, after: PasteCounterSnapshot, toleranceWindow: Int = 1): PasteAnomalyPayload? }` — pure diff function; `null` means no anomaly.

**Why this still exists despite Task 4's per-event correlation:** `PasteCorrelator.onDocChange` correlates *known* paste actions against *known* doc changes, but it cannot see edits that bypass the `EditorPaste` action entirely — e.g. a PSI/Document-API bulk edit from an external tool integration, which is exactly the "AI tool applies a WorkspaceEdit" blind spot `paste-classifier.ts`'s own module comment describes for VS Code. A periodic count-diff over `PasteCorrelator.interceptedCount`/`largeInsertCount` (mirroring VS Code's `paste-reconciler.ts` diff rule and `PasteAnomalyPayload` shape exactly) still catches "large inserts happened without matching intercepted actions" in aggregate, even where the per-event correlator found no correlated pending expectation to compare against.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.provenance.recorder.paste

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PasteAnomalyReconcilerTest {
    @Test
    fun `equal deltas within tolerance is no anomaly`() {
        val before = PasteCounterSnapshot(intercepted = 5, largeInsert = 5)
        val after = PasteCounterSnapshot(intercepted = 7, largeInsert = 7)
        assertNull(PasteAnomalyReconciler.check(before, after))
    }

    @Test
    fun `deltas within the default tolerance window of 1 is no anomaly`() {
        val before = PasteCounterSnapshot(0, 0)
        val after = PasteCounterSnapshot(intercepted = 2, largeInsert = 3) // discrepancy 1
        assertNull(PasteAnomalyReconciler.check(before, after))
    }

    @Test
    fun `discrepancy beyond tolerance emits the anomaly payload with raw deltas`() {
        val before = PasteCounterSnapshot(0, 0)
        val after = PasteCounterSnapshot(intercepted = 1, largeInsert = 5) // discrepancy 4
        val result = PasteAnomalyReconciler.check(before, after)
        assertEquals(PasteAnomalyPayload(interceptedCount = 1, largeInsertCount = 5), result)
    }

    @Test
    fun `custom tolerance window is respected`() {
        val before = PasteCounterSnapshot(0, 0)
        val after = PasteCounterSnapshot(intercepted = 0, largeInsert = 3)
        assertNull(PasteAnomalyReconciler.check(before, after, toleranceWindow = 3))
        assert(PasteAnomalyReconciler.check(before, after, toleranceWindow = 2) != null)
    }

    @Test
    fun `payload json uses snake_case wire field names`() {
        val payload = PasteAnomalyPayload(interceptedCount = 2, largeInsertCount = 9)
        val json = payload.toJsonObject()
        assertEquals(2, json["intercepted_count"]!!.toString().toInt())
        assertEquals(9, json["large_insert_count"]!!.toString().toInt())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.paste.PasteAnomalyReconcilerTest'`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write the implementation**

```kotlin
package dev.provenance.recorder.paste

import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class PasteCounterSnapshot(val intercepted: Int, val largeInsert: Int)

/** Mirrors log-core's PasteAnomalyPayload (events.ts:199-202) — field names below are Kotlin-idiomatic; toJsonObject() maps to the snake_case wire shape. */
data class PasteAnomalyPayload(val interceptedCount: Int, val largeInsertCount: Int)

fun PasteAnomalyPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("intercepted_count", interceptedCount)
    put("large_insert_count", largeInsertCount)
}

/**
 * Periodic count-diff, ported from packages/recorder/src/events/paste-reconciler.ts's
 * diff rule (same tolerance semantics, same payload meaning: deltas since the
 * last check, not cumulative totals). Pure — the caller (Task 7) owns the
 * actual interval/timer and supplies before/after snapshots.
 */
object PasteAnomalyReconciler {
    fun check(
        before: PasteCounterSnapshot,
        after: PasteCounterSnapshot,
        toleranceWindow: Int = 1,
    ): PasteAnomalyPayload? {
        val deltaIntercepted = after.intercepted - before.intercepted
        val deltaLargeInsert = after.largeInsert - before.largeInsert
        val discrepancy = abs(deltaIntercepted - deltaLargeInsert)
        if (discrepancy <= toleranceWindow) return null
        return PasteAnomalyPayload(interceptedCount = deltaIntercepted, largeInsertCount = deltaLargeInsert)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.paste.PasteAnomalyReconcilerTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/paste/PasteAnomalyReconciler.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/paste/PasteAnomalyReconcilerTest.kt
git commit --no-gpg-sign -m "feat(recorder): periodic paste-anomaly count-diff reconciler"
```

---

### Task 6: `PasteInterceptHandler` — the `EditorPaste` action wrapper (signal 2, the platform seam)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/paste/ClipboardReader.kt`
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/paste/PasteInterceptHandler.kt`
- Modify: `recorder/src/main/resources/META-INF/plugin.xml` — register the `editorActionHandler`.
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/wiring/paste/PasteInterceptHandlerTest.kt`

**Interfaces:**
- Consumes: `PasteCorrelator` (Task 4).
- Produces:
  - `interface ClipboardReader { fun readText(): String? }` + `class CopyPasteManagerClipboardReader : ClipboardReader` — wraps `CopyPasteManager.getInstance().getContents()` → `getTransferData(DataFlavor.stringFlavor) as? String`, swallowing `UnsupportedFlavorException`/`IOException` to `null` (a clipboard with an image, not text, is not an error case).
  - `class PasteInterceptHandler(originalHandler: EditorActionHandler, private val correlator: PasteCorrelator, private val clipboard: ClipboardReader = CopyPasteManagerClipboardReader()) : EditorActionHandler()` — overrides `doExecute(editor, caret, dataContext)`: reads clipboard text, calls `correlator.onPasteActionFired(clipboardText)`, then delegates to `originalHandler.execute(editor, caret, dataContext)`.

**Design note — why this reads the clipboard itself rather than trusting the eventual inserted text alone:** capturing clipboard content *before* delegating is the only way to see what was on the clipboard **prior to** any `CopyPastePostProcessor`/reformat mutation (see the API table's reformat-on-paste row) — reading it after the fact would already be too late even if there were an API to "peek" the platform's internal `Producer`, which there isn't from outside `PasteHandler` itself.

**Test intent:** `BasePlatformTestCase` — this is the platform seam, tested against a real (headless) `Editor`/`Document`/clipboard rather than mocked, per CLAUDE.md's "mock at the seam" (the seam here *is* the platform, same rationale Plan 3 gave for `ManifestLoaderTest`). Use `CodeInsightTestFixture.performEditorAction("EditorPaste")` to simulate the actual keystroke path, with clipboard content set via `CopyPasteManager.getInstance().setContents(StringSelection(...))` beforehand.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.recorder.paste.PasteCorrelator
import java.awt.datatransfer.StringSelection

class PasteInterceptHandlerTest : BasePlatformTestCase() {

    fun `test performing EditorPaste increments the correlator's intercepted count`() {
        myFixture.configureByText("Test.txt", "before<caret> after")
        CopyPasteManager.getInstance().setContents(StringSelection("PASTED"))

        var now = 0L
        val correlator = PasteCorrelator(getNow = { now })
        registerPasteInterceptHandler(correlator)

        myFixture.performEditorAction("EditorPaste")

        assertEquals(1, correlator.interceptedCount)
        // The resulting document mutation should also have been visible to the
        // editor by the time performEditorAction returns.
        assertTrue(myFixture.editor.document.text.contains("PASTED"))
    }

    fun `test clipboard text is captured before delegating (correlator sees it, not null)`() {
        myFixture.configureByText("Test.txt", "<caret>")
        CopyPasteManager.getInstance().setContents(StringSelection("clipboard-marker-text"))

        var now = 0L
        var capturedClipboardText: String? = null
        val correlator = object : PasteCorrelator(getNow = { now }) {
            // If PasteCorrelator ends up `open class` rather than `final`, this
            // subclass-and-override approach works; otherwise inject a spy via
            // a constructor seam instead. Resolve whichever is cleaner once
            // Task 4's PasteCorrelator is in hand — this is a test-only wiring
            // detail, not a production API change.
        }
        registerPasteInterceptHandler(correlator)

        myFixture.performEditorAction("EditorPaste")
        // Assert indirectly via the correlator's own onDocChange decision on the
        // next call, or (simpler) assert interceptedCount incremented and defer
        // exact clipboard-text-capture verification to PasteCorrelatorTest,
        // which already covers the pure logic. This test's job is only to prove
        // the *wiring* calls onPasteActionFired with *some* non-null text when
        // the clipboard holds text.
        assertEquals(1, correlator.interceptedCount)
    }

    /**
     * Registers a PasteInterceptHandler wrapping the current EditorPaste
     * handler, scoped to this test's Disposable so it's cleaned up automatically.
     * VERIFY AT EXECUTION: the exact registration call for a handler that isn't
     * declared via plugin.xml (i.e., programmatic registration for a single
     * test) is EditorActionManager.getInstance().setActionHandler(actionId,
     * handler) — confirm this returns/allows restoring the previous handler,
     * or wrap in a try/finally that restores it, so tests don't leak state into
     * each other via the shared EditorActionManager singleton.
     */
    private fun registerPasteInterceptHandler(correlator: PasteCorrelator) {
        val manager = com.intellij.openapi.editor.actionSystem.EditorActionManager.getInstance()
        val original = manager.getActionHandler(com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_PASTE)
        val wrapped = PasteInterceptHandler(original, correlator)
        manager.setActionHandler(com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_PASTE, wrapped)
        Disposer.register(testRootDisposable) {
            manager.setActionHandler(com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_PASTE, original)
        }
    }
}
```
**VERIFY AT EXECUTION:** `EditorActionManager.getActionHandler`/`setActionHandler` as the programmatic (non-`plugin.xml`) registration path for a single test is inferred from `EditorActionManager`'s documented role (confirmed to exist as an API surface via search — `dploeger.github.io/intellij-api-doc/.../EditorActionManager.html` and the `editorActionHandler` EP's own runtime backing must resolve through it) but the exact restore-on-teardown idiom (`Disposer.register(testRootDisposable) { ... }` reverting the handler) was not independently confirmed against a real Gradle run. If `setActionHandler` doesn't accept/return what's assumed here, an alternative is to register the wrapper declaratively via a **test-scoped `plugin.xml`-equivalent** (`ExtensionTestUtil.maskExtensions` against the `editorActionHandler` EP) — check `testing-plugins.html` for the current idiom before inventing a third approach.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.wiring.paste.PasteInterceptHandlerTest'`
Expected: FAIL — `PasteInterceptHandler`/`ClipboardReader` unresolved, and/or the registration-idiom compile errors flagged above.

- [ ] **Step 3: Write the implementation**

`ClipboardReader.kt`:
```kotlin
package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException

/** Thin seam over CopyPasteManager so PasteInterceptHandler is testable with a fake. */
interface ClipboardReader {
    /** Current clipboard text, or null if the clipboard is empty or holds non-text data. */
    fun readText(): String?
}

/**
 * Reads the clipboard the same way IntelliJ's own PasteHandler does when no
 * custom Producer is supplied (getContentsToPasteToEditor(null) ==
 * CopyPasteManager.getInstance().getContents()). See this plan's API table.
 */
class CopyPasteManagerClipboardReader : ClipboardReader {
    override fun readText(): String? {
        val transferable = CopyPasteManager.getInstance().contents ?: return null
        return try {
            transferable.getTransferData(DataFlavor.stringFlavor) as? String
        } catch (e: UnsupportedFlavorException) {
            null // clipboard holds non-text data (e.g. an image) — not an error.
        } catch (e: IOException) {
            null
        }
    }
}
```

`PasteInterceptHandler.kt`:
```kotlin
package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import dev.provenance.recorder.paste.PasteCorrelator

/**
 * Signal 2 of three-signal paste detection, wrapping IdeActions.ACTION_EDITOR_PASTE
 * ("EditorPaste" — the action actually bound to Cmd+V/Ctrl+V inside a text
 * editor; NOT the generic "$Paste" — see this plan's API table).
 *
 * Mirrors the exact wrapping pattern IntelliJ's own
 * com.intellij.codeInsight.editorActions.PasteHandler uses: hold the original
 * handler, do our own work, then delegate. Unlike VS Code's paste-command-intercept.ts
 * (which had to register a SEPARATE command because VS Code disallows
 * re-registering built-in command IDs), IntelliJ's editorActionHandler
 * extension point is designed for exactly this wrapping — no keybinding
 * workaround needed, so this signal fires for every real Cmd+V/Ctrl+V paste,
 * not just ones where course staff installed a custom keybinding.
 *
 * KNOWN GAP (matches VS Code's own documented blind spot): a tool that
 * mutates the Document directly via the PSI/Document API — bypassing the
 * action system entirely — will not fire this handler. PasteCorrelator's
 * classifier (signal 1) still sees the resulting large insert; it will just
 * be unconfirmed ("paste_likely", not "paste_confirmed"). This is intentional
 * parity with paste-classifier.ts's documented tool-applied-edit gap, not a
 * regression introduced here.
 */
class PasteInterceptHandler(
    private val originalHandler: EditorActionHandler,
    private val correlator: PasteCorrelator,
    private val clipboard: ClipboardReader = CopyPasteManagerClipboardReader(),
) : EditorActionHandler() {

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        // Read the clipboard and record the pending expectation BEFORE
        // delegating — the original handler is what actually mutates the
        // Document (synchronously; see the API table's VERIFY note), and any
        // CopyPastePostProcessor/reformat-on-paste rewriting happens inside
        // that call, so this is the last point we can see the pre-reformat text.
        correlator.onPasteActionFired(clipboard.readText())
        originalHandler.execute(editor, caret, dataContext)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        originalHandler.isEnabled(editor, caret, dataContext)
}
```
**VERIFY AT EXECUTION:** the exact `EditorActionHandler` method to override (`doExecute(Editor, Caret?, DataContext?)` vs. an older `execute(Editor, DataContext)` overload) and `isEnabledForCaret`'s exact signature are as documented from the `EditorActionHandler` API surface referenced across the fetched pages; `PasteHandler.java`'s own `doExecute` signature (`doExecute(Editor editor, Caret caret, DataContext dataContext)`, non-nullable `Caret` there because it asserts `caret == null`) was directly observed — confirm the exact nullable/non-nullable `Caret` signature this plugin's target platform version expects before relying on the override compiling as written.

- [ ] **Step 4: Register the handler in `plugin.xml`**

```xml
<extensions defaultExtensionNs="com.intellij">
    <editorActionHandler
        action="EditorPaste"
        implementationClass="dev.provenance.recorder.wiring.paste.PasteInterceptHandlerFactory"/>
</extensions>
```
**Note:** `editorActionHandler`'s `implementationClass` is instantiated by the platform with a single-arg `(EditorActionHandler)` constructor per the EP's contract (mirroring `PasteHandler(EditorActionHandler originalAction)`), which the platform-managed original handler chain supplies automatically — it does **not** get a `PasteCorrelator` from the platform. Task 7 resolves this: either `PasteInterceptHandler` looks up its `PasteCorrelator` from a project service at call time (via `editor.project?.service<RecorderPasteState>()`, mirroring Plan 3's `RecorderState` pattern), or `plugin.xml` registration is deferred to programmatic registration from `RecorderActivationActivity`-equivalent wiring instead of a static XML entry. **Resolve this in Task 7**, which owns the full assembly — this task's plugin.xml XML above is illustrative of the EP shape, not necessarily the final registration mechanism.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.wiring.paste.PasteInterceptHandlerTest'`
Expected: PASS, once the registration-idiom `VERIFY AT EXECUTION` items in Step 1/3 are resolved against a real Gradle run.

- [ ] **Step 6: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/wiring/paste/ClipboardReader.kt \
  recorder/src/main/kotlin/dev/provenance/recorder/wiring/paste/PasteInterceptHandler.kt \
  recorder/src/main/resources/META-INF/plugin.xml \
  recorder/src/test/kotlin/dev/provenance/recorder/wiring/paste/PasteInterceptHandlerTest.kt
git commit --no-gpg-sign -m "feat(recorder): EditorPaste action wrapper (signal 2 platform interception)"
```

---

### Task 7: Assembly — doc-change glue, event emission, and the anomaly ticker

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/paste/RecorderPasteState.kt`
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/paste/PasteAwareDocumentListener.kt`
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/paste/PasteAnomalyTicker.kt`
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/paste/PasteInterceptHandlerFactory.kt`
- Modify: `recorder/src/main/resources/META-INF/plugin.xml`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/wiring/paste/PasteAwareDocumentListenerTest.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/wiring/paste/PasteAnomalyTickerTest.kt`

**Interfaces:**
- Consumes: `PasteCorrelator`, `PasteDecision` (Task 4); `PasteAnomalyReconciler`, `PasteCounterSnapshot`, `PasteAnomalyPayload.toJsonObject()` (Task 5); `PasteInterceptHandler` (Task 6); `RecorderState` pattern precedent (Plan 3, `dev.provenance.recorder.activation.RecorderState`).
- Produces:
  - `interface RecorderEventEmitter { fun emit(kind: String, data: kotlinx.serialization.json.JsonObject) }` — **placeholder for Plan 4's real session-writer emit surface; see "Integration with Plan 4" above.**
  - `@Service(Service.Level.PROJECT) class RecorderPasteState { var correlator: PasteCorrelator?; ... }` — resolves the `PasteInterceptHandlerFactory` → `PasteCorrelator` seam noted in Task 6 Step 4, mirroring `RecorderState`'s project-service pattern from Plan 3. `correlator` is `null` until the project's manifest activates (Plan 3) and Plan 4's session writer is ready to receive events — while `null`, `PasteInterceptHandler` still delegates to the original handler (paste keeps working) but records nothing, matching the "activation is the privacy gate" rule.
  - `class PasteInterceptHandlerFactory(originalHandler: EditorActionHandler) : EditorActionHandler()` — the actual `plugin.xml`-instantiated class; looks up `editor.project?.service<RecorderPasteState>()?.correlator` per call and delegates to a `PasteInterceptHandler` instance only if non-null, otherwise calls `originalHandler.execute(...)` directly (inactive project = do nothing, per CLAUDE.md's activation-gate rule).
  - `class PasteAwareDocumentListener(private val correlator: PasteCorrelator, private val emitter: RecorderEventEmitter, private val relativePath: (com.intellij.openapi.editor.Document) -> String?) : DocumentListener` — `documentChanged(event)` converts the `DocumentEvent` to a single-delta `List<DocChangeDelta>` (`event.offset`/`event.oldLength`/`event.newLength` → `Range`/`Position`; **VERIFY AT EXECUTION**: mapping a flat `offset`/`length` pair back to `{line, character}` positions requires `Document.getLineNumber(offset)`/`offset - document.getLineStartOffset(line)`, computed from the **pre-change** document state for the start position — confirm this against a real `DocumentEvent` in `runIde`, since `getOldFragment()`/`getNewFragment()` semantics around the exact line/character boundary are easy to get backwards, mirroring CLAUDE.md's general "easy to get the direction wrong" caution), calls `correlator.onDocChange(deltas)`, and emits per the returned `PasteDecision`.
  - `class PasteAnomalyTicker(private val correlator: PasteCorrelator, private val emitter: RecorderEventEmitter, private val intervalMs: Long = 5_000) : Disposable` — owns a scheduled ticker (`java.util.Timer`/`AppExecutorUtil.getAppScheduledExecutorService()` — **VERIFY AT EXECUTION** which is the platform-idiomatic choice; avoid a raw `java.util.Timer` if the SDK docs steer toward `AppExecutorUtil`), snapshotting `correlator.interceptedCount`/`largeInsertCount` each tick, calling `PasteAnomalyReconciler.check`, and emitting `paste.anomaly` on a non-null result. `dispose()` cancels the ticker — mirrors VS Code's `clearInterval`, satisfying CLAUDE.md's no-background-task-without-dispose rule.

- [ ] **Step 1: Write the failing tests**

`PasteAwareDocumentListenerTest.kt` (pure-logic-focused; uses a fake `Document`-adjacent input rather than a real `Editor`, since the offset→line/character mapping is the one platform-dependent piece — isolate it behind a small injectable function so the rest is unit-testable):

```kotlin
package dev.provenance.recorder.wiring.paste

import dev.provenance.recorder.paste.DocChangeDelta
import dev.provenance.recorder.paste.PasteCorrelator
import dev.provenance.recorder.paste.PastePayloadFields
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PasteAwareDocumentListenerTest {
    private class RecordingEmitter : RecorderEventEmitter {
        val emitted = mutableListOf<Pair<String, JsonObject>>()
        override fun emit(kind: String, data: JsonObject) { emitted.add(kind to data) }
    }

    @Test
    fun `typed delta emits doc_change with source typed`() {
        var now = 0L
        val correlator = PasteCorrelator(getNow = { now })
        val emitter = RecordingEmitter()
        val listener = PasteAwareDocumentListener(correlator, emitter) { "hw03.py" }

        listener.onDeltas(listOf(DocChangeDelta(sameRange(), "x")))

        assertEquals(1, emitter.emitted.size)
        assertEquals("doc.change", emitter.emitted[0].first)
        assertEquals("typed", emitter.emitted[0].second["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `single large empty-range delta emits a paste event with the built payload`() {
        var now = 0L
        val correlator = PasteCorrelator(getNow = { now })
        val emitter = RecordingEmitter()
        val listener = PasteAwareDocumentListener(correlator, emitter) { "hw03.py" }

        listener.onDeltas(listOf(DocChangeDelta(sameRange(), "y".repeat(40))))

        assertEquals("paste", emitter.emitted[0].first)
        assertEquals("hw03.py", emitter.emitted[0].second["path"]!!.jsonPrimitive.content)
    }

    private fun sameRange() = dev.provenance.recorder.paste.Range(
        dev.provenance.recorder.paste.Position(0, 0),
        dev.provenance.recorder.paste.Position(0, 0),
    )
}
```
**Note:** this test exercises `PasteAwareDocumentListener.onDeltas(deltas)` — a plain-Kotlin method taking already-built `DocChangeDelta`s — rather than the real `documentChanged(DocumentEvent)` entry point, so the offset→line/character conversion (the genuinely IntelliJ-dependent, VERIFY-AT-EXECUTION piece) can be tested separately or exercised only via `runIde`/a `BasePlatformTestCase` smoke test. Split `documentChanged(event)` into "convert `DocumentEvent` to `List<DocChangeDelta>`, then call `onDeltas(deltas)`" for exactly this reason.

`PasteAnomalyTickerTest.kt`:
```kotlin
package dev.provenance.recorder.wiring.paste

import dev.provenance.recorder.paste.PasteCorrelator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PasteAnomalyTickerTest {
    private class RecordingEmitter : RecorderEventEmitter {
        val emitted = mutableListOf<Pair<String, JsonObject>>()
        override fun emit(kind: String, data: JsonObject) { emitted.add(kind to data) }
    }

    @Test
    fun `tick with no discrepancy emits nothing`() {
        var now = 0L
        val correlator = PasteCorrelator(getNow = { now })
        val emitter = RecordingEmitter()
        val ticker = PasteAnomalyTicker(correlator, emitter)

        ticker.tick() // exercise the pure per-tick logic directly, not the scheduler
        assertTrue(emitter.emitted.isEmpty())
    }

    @Test
    fun `tick with a discrepancy beyond tolerance emits paste_anomaly`() {
        var now = 0L
        val correlator = PasteCorrelator(getNow = { now })
        val emitter = RecordingEmitter()
        val ticker = PasteAnomalyTicker(correlator, emitter)

        correlator.onPasteActionFired(null) // interceptedCount 1, largeInsertCount 0
        ticker.tick()

        assertEquals(1, emitter.emitted.size)
        assertEquals("paste.anomaly", emitter.emitted[0].first)
        assertEquals(1, emitter.emitted[0].second["intercepted_count"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, emitter.emitted[0].second["large_insert_count"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `dispose cancels the underlying schedule without throwing`() {
        val correlator = PasteCorrelator(getNow = { 0L })
        val ticker = PasteAnomalyTicker(correlator, RecordingEmitter())
        ticker.dispose()
        ticker.dispose() // idempotent — must not throw on double-dispose
    }
}
```
`tick()` is exposed as a plain method exercising the reconciliation logic directly (snapshot-diff-emit), separate from whatever scheduler primitive actually calls it on an interval — matching `PasteAwareDocumentListenerTest`'s same split of "pure decision logic" from "platform scheduling," and avoiding a real 5-second sleep in the test suite.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.wiring.paste.*'`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write the implementation**

`RecorderPasteState.kt`:
```kotlin
package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.components.Service
import dev.provenance.recorder.paste.PasteCorrelator

/**
 * Project-scoped holder for the paste correlator, mirroring
 * dev.provenance.recorder.activation.RecorderState's pattern (Plan 3).
 * `correlator` stays null until the project's manifest has activated AND
 * Plan 4's session writer is ready — while null, paste keeps working
 * normally but nothing is recorded (activation is the privacy gate).
 */
@Service(Service.Level.PROJECT)
class RecorderPasteState {
    @Volatile
    var correlator: PasteCorrelator? = null
}
```

`PasteInterceptHandlerFactory.kt`:
```kotlin
package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler

/**
 * The plugin.xml-instantiated editorActionHandler for "EditorPaste". The
 * platform supplies only the original handler (single-arg constructor, per
 * the editorActionHandler EP contract) — it has no way to inject a
 * project-scoped PasteCorrelator, so this factory resolves one per call from
 * RecorderPasteState, and is a no-op passthrough when the project's recorder
 * isn't active.
 */
class PasteInterceptHandlerFactory(private val originalHandler: EditorActionHandler) : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val correlator = editor.project?.service<RecorderPasteState>()?.correlator
        if (correlator == null) {
            originalHandler.execute(editor, caret, dataContext)
            return
        }
        PasteInterceptHandler(originalHandler, correlator).doExecute(editor, caret, dataContext)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        originalHandler.isEnabled(editor, caret, dataContext)
}
```

`PasteAwareDocumentListener.kt`:
```kotlin
package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import dev.provenance.recorder.paste.DocChangeDelta
import dev.provenance.recorder.paste.PasteCorrelator
import dev.provenance.recorder.paste.PasteDecision
import dev.provenance.recorder.paste.Position
import dev.provenance.recorder.paste.Range
import dev.provenance.recorder.paste.toJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The doc-change entry point for paste-classification purposes (see
 * "Integration with Plan 4" — this listener is expected to become Plan 4's
 * single doc-change DocumentListener, not compete with a second one).
 */
class PasteAwareDocumentListener(
    private val correlator: PasteCorrelator,
    private val emitter: RecorderEventEmitter,
    private val relativePath: (Document) -> String?,
) : DocumentListener {

    override fun documentChanged(event: DocumentEvent) {
        val path = relativePath(event.document) ?: return
        val delta = toDelta(event)
        onDeltas(listOf(delta), path)
    }

    /**
     * Exposed separately from documentChanged so pure-logic tests can drive
     * it without a real Document/DocumentEvent. Production path is always
     * documentChanged -> toDelta -> onDeltas.
     */
    fun onDeltas(deltas: List<DocChangeDelta>, path: String = "") {
        when (val decision = correlator.onDocChange(deltas)) {
            is PasteDecision.EmitPaste ->
                emitter.emit("paste", decision.fields.toJsonObject(path, decision.range))
            is PasteDecision.EmitDocChange ->
                emitter.emit("doc.change", docChangeJson(path, deltas, decision.source))
        }
    }

    private fun docChangeJson(path: String, deltas: List<DocChangeDelta>, source: String) = buildJsonObject {
        put("path", path)
        put("deltas", deltasToJsonArray(deltas))
        put("source", source)
    }

    private fun deltasToJsonArray(deltas: List<DocChangeDelta>): JsonArray = buildJsonArray {
        for (d in deltas) {
            add(buildJsonObject {
                put("range", buildJsonObject {
                    put("start", buildJsonObject { put("line", d.range.start.line); put("character", d.range.start.character) })
                    put("end", buildJsonObject { put("line", d.range.end.line); put("character", d.range.end.character) })
                })
                put("text", d.text)
            })
        }
    }

    /**
     * VERIFY AT EXECUTION: converting DocumentEvent's flat offset/oldLength/
     * newLength into {line, character} start/end positions requires reading
     * line numbers from the Document at the right moment relative to the
     * mutation (pre-change document state for the start position, since
     * IntelliJ's Document has already applied the change by the time
     * documentChanged fires for the NEW length/fragment, but line numbers for
     * an offset that existed before AND after the edit should agree for the
     * start position specifically). Confirm the exact
     * getLineNumber/getLineStartOffset calls against a real DocumentEvent in
     * runIde — this is the same class of "easy to get the direction wrong"
     * risk CLAUDE.md flags for fs.external_change, applied to the doc-edit
     * geometry instead of file content direction.
     */
    private fun toDelta(event: DocumentEvent): DocChangeDelta {
        val doc = event.document
        val startLine = doc.getLineNumber(event.offset)
        val startChar = event.offset - doc.getLineStartOffset(startLine)
        val endOffset = event.offset + event.newLength
        val endLine = doc.getLineNumber(endOffset)
        val endChar = endOffset - doc.getLineStartOffset(endLine)
        return DocChangeDelta(
            range = Range(Position(startLine, startChar), Position(endLine, endChar)),
            text = event.newFragment.toString(),
        )
    }
}
```

`PasteAnomalyTicker.kt`:
```kotlin
package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.Disposable
import com.intellij.util.concurrency.AppExecutorUtil
import dev.provenance.recorder.paste.PasteAnomalyReconciler
import dev.provenance.recorder.paste.PasteCounterSnapshot
import dev.provenance.recorder.paste.PasteCorrelator
import dev.provenance.recorder.paste.toJsonObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

interface RecorderEventEmitter {
    fun emit(kind: String, data: kotlinx.serialization.json.JsonObject)
}

/**
 * Periodic paste.anomaly emitter (signal 3's aggregate fallback — see Task 5).
 * No background task without a dispose path (CLAUDE.md): dispose() cancels
 * the schedule; double-dispose is a no-op.
 */
class PasteAnomalyTicker(
    private val correlator: PasteCorrelator,
    private val emitter: RecorderEventEmitter,
    private val intervalMs: Long = 5_000,
) : Disposable {
    private var lastSnapshot = PasteCounterSnapshot(correlator.interceptedCount, correlator.largeInsertCount)
    private var future: ScheduledFuture<*>? = null

    /** Starts the periodic schedule. VERIFY AT EXECUTION: AppExecutorUtil.getAppScheduledExecutorService()
     * is the platform-idiomatic choice over a raw java.util.Timer for plugin
     * background work — confirm against current SDK guidance before relying
     * on it; either way, the schedule must be cancellable via dispose(). */
    fun start() {
        future = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay({ tick() }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    /** One reconciliation pass. Exposed for direct testing without a real scheduler. */
    fun tick() {
        val current = PasteCounterSnapshot(correlator.interceptedCount, correlator.largeInsertCount)
        val anomaly = PasteAnomalyReconciler.check(lastSnapshot, current)
        lastSnapshot = current
        if (anomaly != null) {
            emitter.emit("paste.anomaly", anomaly.toJsonObject())
        }
    }

    override fun dispose() {
        future?.cancel(false)
        future = null
    }
}
```

- [ ] **Step 4: Wire the factory registration into `plugin.xml`**

```xml
<extensions defaultExtensionNs="com.intellij">
    <postStartupActivity implementation="dev.provenance.recorder.activation.RecorderActivationActivity"/>
    <statusBarWidgetFactory
        id="ProvenanceRecordingWidget"
        implementation="dev.provenance.recorder.statusbar.RecordingStatusBarWidgetFactory"
        order="first"/>
    <editorActionHandler
        action="EditorPaste"
        implementationClass="dev.provenance.recorder.wiring.paste.PasteInterceptHandlerFactory"/>
</extensions>
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.wiring.paste.*'`
Expected: PASS.

- [ ] **Step 6: Run the full `recorder/` test suite**

Run: `./gradlew :recorder:test`
Expected: PASS — Plan 3's suites plus this plan's Tasks 1-7.

- [ ] **Step 7: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/wiring/paste/ \
  recorder/src/main/resources/META-INF/plugin.xml \
  recorder/src/test/kotlin/dev/provenance/recorder/wiring/paste/
git commit --no-gpg-sign -m "feat(recorder): assemble three-signal paste detection (doc listener + anomaly ticker + plugin.xml wiring)"
```

---

### Task 8: Payload-shape conformance test against `log-core`'s pinned event shapes

**Files:**
- Create: `recorder/src/test/kotlin/dev/provenance/recorder/paste/PasteEventShapeConformanceTest.kt`

**Interfaces:**
- Consumes: `PastePayloadFields.toJsonObject()` (Task 2), `PasteAnomalyPayload.toJsonObject()` (Task 5), `PasteAwareDocumentListener`'s `doc.change` JSON shape (Task 7).
- Produces: nothing consumed downstream — this is a gate, analogous in spirit to Plan 1's `ConformanceTest` but for **payload shape**, not crypto bytes (paste content is student-input-dependent, not a deterministic hash to pin). It asserts field names/optionality against a hand-written fixture transcribed directly from `events.ts`, so a future accidental rename (e.g. `contentHead` leaking into JSON instead of `content_head`) fails loudly here rather than surfacing as a silent analyzer-side parse gap.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.provenance.recorder.paste

import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Payload-shape parity gate against packages/log-core/src/events.ts. Not a
 * crypto/hash conformance test (paste content isn't deterministic) — asserts
 * field names, snake_case wire keys, and optional-field omission-vs-null
 * behavior exactly match the pinned TS types. A failure here means this
 * plugin would emit a bundle the real analyzer can't parse.
 */
class PasteEventShapeConformanceTest {
    @Test
    fun `PastePayload json has exactly the events_ts_95-103 fields for a short paste`() {
        val fields = buildPastePayload("short text")
        val json = fields.toJsonObject(path = "a.py", range = Range(Position(0, 0), Position(0, 0)))
        assertEquals(setOf("path", "range", "length", "sha256", "content"), json.keys)
        assertFalse(json.containsKey("content_head"))
        assertFalse(json.containsKey("content_tail"))
    }

    @Test
    fun `PastePayload json omits content and includes head-tail for a long paste`() {
        val fields = buildPastePayload("x".repeat(5000))
        val json = fields.toJsonObject(path = "a.py", range = Range(Position(0, 0), Position(0, 0)))
        assertEquals(setOf("path", "range", "length", "sha256", "content_head", "content_tail"), json.keys)
    }

    @Test
    fun `DocChangePayload json has exactly the events_ts_80-84 fields`() {
        val correlator = PasteCorrelator(getNow = { 0L })
        // A typed change exercises the doc.change/source path.
        val decision = correlator.onDocChange(listOf(DocChangeDelta(Range(Position(0, 0), Position(0, 1)), "x")))
        assertTrue(decision is PasteDecision.EmitDocChange)
        // Field-shape is asserted at the JSON-building call site
        // (PasteAwareDocumentListener.docChangeJson in wiring/paste); this
        // test pins the source string values the format allows.
        assertTrue((decision as PasteDecision.EmitDocChange).source in setOf("typed", "paste_likely", "paste_confirmed"))
    }

    @Test
    fun `PasteAnomalyPayload json has exactly the events_ts_199-202 snake_case fields`() {
        val payload = PasteAnomalyPayload(interceptedCount = 2, largeInsertCount = 5)
        val json = payload.toJsonObject()
        assertEquals(setOf("intercepted_count", "large_insert_count"), json.keys)
        assertEquals(2, json["intercepted_count"]!!.jsonPrimitive.content.toInt())
    }
}
```

- [ ] **Step 2: Run test to verify it fails, then implement/fix until it passes**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.paste.PasteEventShapeConformanceTest'`
Expected: initially PASS if Tasks 2/5 were built correctly against the cited events.ts line ranges (this task is a gate, not new production code — if it fails, the bug is in Task 2 or Task 5's `toJsonObject()`, not in this test).

- [ ] **Step 3: Run the entire `recorder/` suite one more time**

Run: `./gradlew :recorder:test`
Expected: PASS — every suite from Plan 3 plus Tasks 1-8 of this plan.

- [ ] **Step 4: Commit**

```bash
git add recorder/src/test/kotlin/dev/provenance/recorder/paste/PasteEventShapeConformanceTest.kt
git commit --no-gpg-sign -m "test(recorder): payload-shape conformance gate for paste/doc.change/paste.anomaly"
```

---

## Self-Review

**Spec coverage (recorder PRD §4.3, re-derived against the IntelliJ Platform SDK per design.md §4's paste row):** signal 1 bulk-insertion classifier ported unchanged (Task 1), the pinned payload truncation/hash builder ported unchanged (Task 2), a new reformat-tolerant similarity primitive that VS Code's source never needed (Task 3), the combined-signal correlation decision including the `paste_confirmed` value the VS Code recorder defines but never emits (Task 4), the periodic count-based anomaly fallback matching VS Code's actual (not PRD-prose) implementation (Task 5), the `EditorPaste` action wrapper — the actual signal-2 platform interception, with the multi-caret bypass and reformat-on-paste specifics cited from IntelliJ's own `PasteHandler` source (Task 6), full assembly into a `DocumentListener` + anomaly ticker + `plugin.xml` (Task 7), and a payload-shape conformance gate against `log-core`'s pinned types (Task 8). All three touched event kinds (`paste`, `doc.change`, `paste.anomaly`) and all three `DocChangePayload.source` enum values are exercised.

**Placeholder scan:** all Kotlin/XML across Tasks 1-8 is complete, runnable code. Flagged, cited exceptions: `PASTE_CONFIRM_SIMILARITY_THRESHOLD = 0.7` (Task 3, explicitly TUNE AT EXECUTION — no real paste-sample data exists yet to fit it against); the `EditorActionManager` programmatic test-registration idiom (Task 6, VERIFY AT EXECUTION); `DocumentEvent`→line/character conversion (Task 7, VERIFY AT EXECUTION); `AppExecutorUtil` vs. an alternative scheduler primitive (Task 7, VERIFY AT EXECUTION); the synchronous-dispatch assumption underlying `PasteCorrelator`'s `withinMs` window (API table + Task 4, VERIFY AT EXECUTION).

**Type consistency:** `DocChangeDelta`/`Range`/`Position` (Task 1) flow unchanged through `PasteCorrelator` (Task 4), `PasteAwareDocumentListener` (Task 7), and the conformance test (Task 8). `PastePayloadFields` (Task 2) flows unchanged into `PasteDecision.EmitPaste` (Task 4) and its `toJsonObject()` (Task 8). `PasteCorrelator.interceptedCount`/`largeInsertCount` (Task 4) are the sole inputs to `PasteAnomalyReconciler`/`PasteAnomalyTicker` (Tasks 5, 7) — no parallel counting exists anywhere else.

**Fidelity note (required — the two tricky cases named in the task brief):**

1. **Reformat-on-paste.** IntelliJ's single-caret paste path runs `CopyPastePostProcessor`s and `CodeInsightSettings.REFORMAT_ON_PASTE`-driven re-indentation *between* the clipboard read and the `Document` mutation (cited from `PasteHandler.doPasteAction()`). This plan does not attempt byte-exact clipboard-to-inserted-text matching anywhere — `PasteTextSimilarity` (Task 3) is deliberately a normalized, threshold-based, non-exact comparison, and its threshold is explicitly unvalidated against real samples (TUNE AT EXECUTION). A mismatch here degrades gracefully to `"paste_likely"` instead of `"paste_confirmed"` — it never causes a dropped or miscategorized event, since `PasteDecision` always falls back to the signal-1-only behavior VS Code already ships. Budget a `runIde` pass pasting real code in at least two languages (one with aggressive reformat-on-paste behavior, e.g. Python/Java, one with little, e.g. Markdown/plain text) before trusting the confirmation signal in downstream heuristics.

2. **Multi-caret paste.** `PasteHandler.execute()` explicitly bypasses all of `doPasteAction`'s single-string logic — including `CopyPastePostProcessor`s and reformat — for `editor.isColumnMode() || caretCount > 1`, delegating instead to a different original handler entirely (cited). This plan's `PasteInterceptHandler` still fires for this case (it wraps at the `EditorPaste` action level, above the multi-caret branch point), so signal 2 (action intercepted, clipboard captured) still registers — but **the granularity of the resulting `DocumentEvent`(s)** for a multi-caret paste (one bundled event vs. N per-caret events) is **not verified from public docs or source read for this plan** and is flagged **VERIFY AT EXECUTION** in Task 7. If `runIde` testing shows N separate small per-caret `DocumentEvent`s each individually under `PASTE_MIN_INSERT_CHARS`, signal 1 will misclassify a real multi-caret paste as `"typed"` — the fix (out of scope for this plan, noted for whoever picks up the VERIFY item) would be batching `onDocChange` calls within a short time window keyed off a single `onPasteActionFired` timestamp, rather than classifying each `DocumentEvent` independently.

**Deliberate, cited deviations from a literal VS Code port (per CLAUDE.md — "if you make a non-obvious choice, explain it, don't bury it"):**
- Signal 2's mechanism is `editorActionHandler` wrapping, not `AnActionListener` on `$Paste` — both design.md and CLAUDE.md list `AnActionListener` as *an* option; this plan picked the alternative they also name (`EditorActionHandler` wrapper), with cited rationale (API table, "Rejected alternative for signal 2" row) and a corrected action id (`EditorPaste`, not `$Paste`).
- Signal 3 is a real clipboard-content comparison, not VS Code's count-based `paste-reconciler.ts` mechanism the PRD's prose calls "external clipboard read" but never actually implements. This is possible only because IntelliJ's wrapping mechanism gives a same-call-stack clipboard read VS Code's separate-command workaround never had — cited in Global Constraints and Task 4.
- `'paste_confirmed'` is emitted for the first time by any Provenance recorder (VS Code computes-then-discards it). No format change — the enum value has existed in `events.ts` since format v1.0.

**Open questions carried forward:** the exact `DocumentEvent`→`{line,character}` conversion, the scheduler primitive for the anomaly ticker, and the `PASTE_CONFIRM_SIMILARITY_THRESHOLD` value all need a real `runIde`/Gradle pass to pin — consistent with Plan 3's own caution that API specifics not independently executed in this environment are starting points, not gospel.
