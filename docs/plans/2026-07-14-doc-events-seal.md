# Document Event Wiring, Session Writer & Bundle Seal Implementation Plan (Plan 4 of the provjet series)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire `doc.open/change/save/close` (+ `session.start/heartbeat/end`) against the real IntelliJ Platform SDK, add an atomic buffered `.slog` writer and a `.slog.meta` writer, and a bundle-seal command — so this plugin can produce a sealed submission ZIP that the **real** Provenance analyzer/server (`packages/analysis-core`) accepts: hash chain intact, manifest signature verifies.

**Architecture:** Two new layers on top of `core/` (Plans 1–2, format/crypto, pure Kotlin/JVM):

1. **Event payload models** (`core/Events.kt`) — the Kotlin analogue of `log-core`'s `events.ts`. Per-event-kind payload shapes are part of the *format contract*, not editor-specific, so — matching where they live in the monorepo — they belong in `core/`, not `recorder/`. This is a correction relative to Plans 1–2, which ported the chain/crypto/bundle primitives but not the event-kind payload shapes; Plan 4 fills that gap first.
2. **`recorder/` session + wiring layer** — pure session orchestration (`SessionHost`, `SessionWriter`, `MetaWriter`, `AtomicWrite`, `RecorderContext`) plus the IntelliJ-specific seam (`DocumentListener` via the global event multicaster, `FileEditorManagerListener`, `FileDocumentManagerListener`, a heartbeat timer, and the seal command). Mirrors the monorepo's `packages/recorder/src/{session,io,wiring,events,commands}/` layout and its "pure transform, separately-tested seam" split (CLAUDE.md).

Two gaps found while reading Plans 1–2 are folded into this plan (Tasks 1–2) rather than deferred, because `SessionWriter` cannot be built without them: `BufferPolicy`/`Clock` (log-core's `buffer-policy.ts`/`clock.ts`) were never ported.

**Tech Stack:** Kotlin/JVM, IntelliJ Platform SDK (via the IntelliJ Platform Gradle Plugin, assumed set up by Plan 3), `java.nio`/`java.util.zip`/`java.security.MessageDigest` (JDK-native, no new dependency), kotlinx-serialization-json (already approved, Plan 1). JUnit 5 for pure-Kotlin tests; JUnit4-style `BasePlatformTestCase` for IntelliJ-seam tests (see Global Constraints — this is a real toolchain split, not a style choice).

## Plan series (context)

- Plan 1: `core/` — hashing, JCS, chain, NDJSON, chain-validator, conformance gate. **Written, not yet confirmed executed** (`core/` does not exist on disk as of this plan).
- Plan 2: `core/` — ed25519, manifest verify, bundle manifest + signing, session keypair + encrypted privkey, checkpoints.
- **Plan 3 (assumed, not yet written):** plugin scaffold (IntelliJ Platform Gradle Plugin) + activation + manifest verification + status-bar widget + sideload build for testing. **Plan 4 depends on Plan 3's output but Plan 3 has no doc yet.** Task 0 below pins down the exact, minimal interface Plan 4 needs from Plan 3 and stubs it for tests, so this plan is self-contained and executable before Plan 3 exists. **VERIFY AT EXECUTION:** if Plan 3 is written first, confirm its real API matches Task 0's assumed interface and adjust Task 12 (composition) if not.
- **Plan 4 (this):** `core/` event payload models + buffer policy + clock (gap-fill) — `doc.open/change/save/close` + `session.start/heartbeat/end` wiring — atomic session writer — bundle seal → first analyzer-accepted bundle.
- Plan 5: external-change detection (VFS — highest-risk, per design.md §4).
- Plan 6: three-signal paste detection.
- Plan 7: terminal + git wiring + plugin snapshot.
- Plan 8: checkpoints wiring + chain recovery + disk-full degraded mode.
- Plan 9: `build:prod` course-key embedding + `extension_hash` + Marketplace packaging + monorepo allowlist entry + golden-vector export script.

## Global Constraints

(Inherits Plans 1–2's Global Constraints: format is a fixed contract, `core/` has zero IntelliJ deps, do not hand-roll JCS, determinism in tests, `git commit --no-gpg-sign`, no Claude co-author trailer, explicit pathspec.) Additional, specific to this plan:

- **Scope is exactly design.md §8 step 3:** `doc.open/change/save/close` + hash chain + bundle seal → first valid bundle. **No paste detection (Plan 6), no external-change detection (Plan 5).** `DocChangePayload.source` is always `"typed"` in this plan — the paste classifier doesn't exist yet.
- **`vscode` field wrinkle (design.md §5, CLAUDE.md):** `session.start.data.vscode` must be filled with editor-generic values: `version` = the IDE version, `commit` = `""`, `platform` = OS. Do not rename the field to `host`/`editor` — that is an approval-gated format change owned by the monorepo.
- **Producer identity:** `session.start.data.recorder.extension_id` = this plugin's id (assume `edu.berkeley.provenance.recorder` per design.md §11 Q4, pending Plan 3 confirmation).
- **Event-kind payload models belong in `core/`, not `recorder/`.** In the monorepo, `DocOpenPayload`/`DocChangePayload`/etc. are defined in `packages/log-core/src/events.ts`, not `packages/recorder/src/`. Task 1 places their Kotlin equivalents in `core/` for the same reason: the payload shape is the format contract's per-kind schema, and both a future analyzer-side Kotlin consumer and `recorder/` need the same definition.
- **Testing split is a real toolchain constraint, not a preference.** `core/` and pure `recorder/` logic (SessionHost, SessionWriter's buffer math, AtomicWrite, RecorderContext's pure pieces, doc-event pure transforms) use JUnit 5, consistent with Plans 1–2. Anything touching `DocumentListener`/`FileEditorManagerListener`/the IntelliJ test fixture stack (`BasePlatformTestCase`) uses the platform's own JUnit4-based test infrastructure (`BasePlatformTestCase` extends `UsefulTestCase` → JUnit3/4 `TestCase`; test methods are `fun testXxx()`, no `@Test` annotation needed) — **VERIFY AT EXECUTION** exactly how Plan 3's Gradle setup (IntelliJ Platform Gradle Plugin's testing extension) wires this into `recorder/src/test/kotlin`, and whether JUnit 5 Jupiter tests can coexist in the same source set (they usually can, via `useJUnitPlatform()` + the platform's JUnit4 vintage/legacy bridge — do not assume, confirm before Task 9).
- **`AppExecutorUtil` for scheduled background work, not a bare `Thread`/coroutine.** `com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()` is a stable, dependency-free (already part of the IntelliJ Platform SDK) scheduled executor; used for the SessionWriter's periodic flush and the heartbeat tick. No new Gradle dependency. **VERIFY AT EXECUTION** exact class path against the SDK version Plan 3 pins.
- **`java.util.zip.ZipOutputStream` for the bundle seal, not a new library.** The VS Code recorder uses `jszip` (an approved dependency there); the JDK's `java.util.zip` package covers the same need natively, so no new Gradle dependency is needed or should be added without asking.
- **`InetAddress.getLocalHost()` is a known hang risk** (DNS lookups behind some VPNs/corporate networks) — do not use it for `machine_id`'s hostname component. Use `System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")` with a stable fallback, and `System.getProperty("user.name")` for the username component (always available, no I/O).
- **Atomic writes.** Write-temp-then-rename for `.slog.meta`, `manifest.json`, `manifest.sig` (CLAUDE.md). The `.slog` itself is append-only (buffered writer), not rewritten — same split as the TS recorder's `SessionWriter` vs `atomicWriteFile`.
- **Clock handling.** Monotonic clock for `t`, wall clock for `wall` — the ported `Clock` interface (Task 2) enforces this split the same way `log-core`'s does.
- **Every listener/timer has a `dispose()` path**, tied to an IntelliJ `Disposable` wherever the platform provides one (message bus connections, `EditorFactory.getEventMulticaster().addDocumentListener(listener, disposable)`, `AppExecutorUtil` scheduled futures via explicit `cancel()`).

### Task 0 (not implemented — read only): the assumed Plan 3 interface

Plan 4's composition task (Task 12) needs exactly two things from "activation," which Plan 3 owns:

```kotlin
package dev.provenance.recorder.activation

/** What Plan 3's activation flow hands off once a workspace is verified. Provisional — confirm against the real Plan 3 API before Task 12. */
data class ActivatedWorkspace(
    val manifest: dev.provenance.core.Manifest,   // Plan 2 Task 2
    val provenanceDir: java.nio.file.Path,        // <workspaceRoot>/.provenance, already created
    val workspaceRoot: java.nio.file.Path,
)
```

Task 12 defines a `RecordingSessionController` that takes an `ActivatedWorkspace` and a `Disposable` and starts recording. Everything else in this plan (Tasks 1–11) is independently testable without Plan 3 existing at all.

---

### Task 1: Event payload models (`core/Events.kt`) — port the events.ts subset this plan needs

**Files:**
- Create: `core/src/main/kotlin/dev/provenance/core/Events.kt`
- Test: `core/src/test/kotlin/dev/provenance/core/EventsTest.kt`

**Interfaces:**
- Consumes: `Envelope`'s `data: JsonObject` field (Plan 1 Task 3) — nothing else.
- Produces (mirrors `log-core`'s `events.ts`, PRD §5.1 + §4.2, restricted to the kinds this plan emits):
  - `data class Position(val line: Long, val character: Long)`
  - `data class Range(val start: Position, val end: Position)`
  - `data class SessionStartPayload(val formatVersion: String, val sessionId: String, val prevSessionId: String?, val assignmentId: String, val assignmentSemester: String, val manifestSig: String, val machineId: String, val vscodeVersion: String, val vscodeCommit: String, val vscodePlatform: String, val recorderVersion: String, val recorderExtensionId: String, val sessionPubkey: String)`
  - `data class SessionHeartbeatPayload(val focused: Boolean, val activeFile: String?, val idleSinceMs: Long)`
  - `data class SessionEndPayload(val reason: String)`
  - `data class DocOpenPayload(val path: String, val sha256: String, val lineCount: Long, val content: String?, val truncated: Boolean?)`
  - `data class DocChangeDelta(val range: Range, val text: String)`
  - `data class DocChangePayload(val path: String, val deltas: List<DocChangeDelta>, val source: String /* "typed" only in this plan */)`
  - `data class DocSavePayload(val path: String, val sha256: String)`
  - `data class DocClosePayload(val path: String)`
  - Extension functions `fun SessionStartPayload.toJsonObject(): JsonObject`, and one per other payload type, each emitting the **exact on-the-wire snake_case field names from PRD §4.2/§5.1** (`format_version`, `session_id`, `prev_session_id`, `assignment` → `{id, semester}`, `manifest_sig`, `machine_id`, `vscode` → `{version, commit, platform}`, `recorder` → `{version, extension_id}`, `session_pubkey`; `line_count`; `active_file`, `idle_since_ms`). `content`/`truncated` on `DocOpenPayload` are omitted from the JSON object when null (optional fields per PRD §4.2, not `null`-valued).

**Test intent:**
- Each `toJsonObject()` produces the exact key set and nesting PRD §4.2/§5.1 specifies (assert against a hand-built `JsonObject`, not a string, so key order doesn't matter — `Canonical.canonicalize` handles ordering downstream).
- `DocOpenPayload` with `content = null` omits both `content` and `truncated` keys entirely; with `truncated = true` and `content = null` emits only `truncated`.
- `SessionStartPayload.vscodeCommit = ""` round-trips as the empty string, not omitted (PRD §5.4: "Validators must accept `''` here without treating it as a structural failure" — the field must be *present and empty*, not absent).

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/dev/provenance/core/EventsTest.kt`:
```kotlin
package dev.provenance.core

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.content
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventsTest {
    @Test
    fun `session start payload emits PRD 5_1 shape with snake_case keys`() {
        val p = SessionStartPayload(
            formatVersion = "1.0", sessionId = "abc", prevSessionId = null,
            assignmentId = "hw03", assignmentSemester = "fa26",
            manifestSig = "deadbeef", machineId = "cafebabe",
            vscodeVersion = "2026.2", vscodeCommit = "", vscodePlatform = "darwin-arm64",
            recorderVersion = "0.1.0", recorderExtensionId = "edu.berkeley.provenance.recorder",
            sessionPubkey = "a".repeat(64),
        )
        val obj = p.toJsonObject()
        assertEquals("1.0", obj["format_version"]!!.jsonPrimitive.content)
        assertEquals("hw03", obj["assignment"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("fa26", obj["assignment"]!!.jsonObject["semester"]!!.jsonPrimitive.content)
        assertEquals("", obj["vscode"]!!.jsonObject["commit"]!!.jsonPrimitive.content)
        assertEquals("darwin-arm64", obj["vscode"]!!.jsonObject["platform"]!!.jsonPrimitive.content)
        assertEquals("edu.berkeley.provenance.recorder", obj["recorder"]!!.jsonObject["extension_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `doc open payload omits content and truncated when null`() {
        val p = DocOpenPayload(path = "hw.py", sha256 = "a".repeat(64), lineCount = 10, content = null, truncated = null)
        val obj = p.toJsonObject()
        assertTrue(!obj.containsKey("content"))
        assertTrue(!obj.containsKey("truncated"))
    }

    @Test
    fun `doc open payload with truncated true omits content`() {
        val p = DocOpenPayload(path = "big.py", sha256 = "b".repeat(64), lineCount = 9000, content = null, truncated = true)
        val obj = p.toJsonObject()
        assertTrue(!obj.containsKey("content"))
        assertEquals(true, obj["truncated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `doc change payload with one delta`() {
        val p = DocChangePayload(
            path = "hw.py",
            deltas = listOf(DocChangeDelta(Range(Position(0, 0), Position(0, 5)), "hello")),
            source = "typed",
        )
        val obj = p.toJsonObject()
        assertEquals("typed", obj["source"]!!.jsonPrimitive.content)
        assertEquals(1, obj["deltas"]!!.jsonObject.size.let { 1 }) // placeholder shape check below
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests 'dev.provenance.core.EventsTest'`
Expected: FAIL — `SessionStartPayload`, `DocOpenPayload`, etc. unresolved.

- [ ] **Step 3: Write the implementation**

`core/src/main/kotlin/dev/provenance/core/Events.kt`:
```kotlin
package dev.provenance.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Per-event-kind payload shapes (PRD §4.2, §5.1). Ported from log-core's events.ts.
 * Lives in core/ (not recorder/) because the payload shape is part of the format
 * contract, not editor-specific — mirrors where log-core places it.
 *
 * Only the kinds this plan (Plan 4) emits are ported: session.start/heartbeat/end,
 * doc.open/change/save/close. Later plans add paste, selection.change, focus.change,
 * fs.external_change, terminal.*, git.event, ext.*, clock.skew, recorder.* here too.
 */

data class Position(val line: Long, val character: Long)
data class Range(val start: Position, val end: Position)

private fun Position.toJsonObject(): JsonObject = buildJsonObject {
    put("line", line)
    put("character", character)
}

private fun Range.toJsonObject(): JsonObject = buildJsonObject {
    put("start", start.toJsonObject())
    put("end", end.toJsonObject())
}

data class SessionStartPayload(
    val formatVersion: String,
    val sessionId: String,
    val prevSessionId: String?,
    val assignmentId: String,
    val assignmentSemester: String,
    val manifestSig: String,
    val machineId: String,
    val vscodeVersion: String,
    val vscodeCommit: String,
    val vscodePlatform: String,
    val recorderVersion: String,
    val recorderExtensionId: String,
    val sessionPubkey: String,
)

fun SessionStartPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("format_version", formatVersion)
    put("session_id", sessionId)
    put("prev_session_id", prevSessionId)
    put("assignment", buildJsonObject {
        put("id", assignmentId)
        put("semester", assignmentSemester)
    })
    put("manifest_sig", manifestSig)
    put("machine_id", machineId)
    put("vscode", buildJsonObject {
        put("version", vscodeVersion)
        put("commit", vscodeCommit)
        put("platform", vscodePlatform)
    })
    put("recorder", buildJsonObject {
        put("version", recorderVersion)
        put("extension_id", recorderExtensionId)
    })
    put("session_pubkey", sessionPubkey)
}

data class SessionHeartbeatPayload(val focused: Boolean, val activeFile: String?, val idleSinceMs: Long)

fun SessionHeartbeatPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("focused", focused)
    put("active_file", activeFile)
    put("idle_since_ms", idleSinceMs)
}

data class SessionEndPayload(val reason: String)

fun SessionEndPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("reason", reason)
}

data class DocOpenPayload(
    val path: String,
    val sha256: String,
    val lineCount: Long,
    val content: String?,
    val truncated: Boolean?,
)

fun DocOpenPayload.toJsonObject(): JsonObject = buildJsonObject {
    put("path", path)
    put("sha256", sha256)
    put("line_count", lineCount)
    if (content != null) put("content", content)
    if (truncated != null) put("truncated", truncated)
}

data class DocChangeDelta(val range: Range, val text: String)

data class DocChangePayload(val path: String, val deltas: List<DocChangeDelta>, val source: String)

fun DocChangePayload.toJsonObject(): JsonObject = buildJsonObject {
    put("path", path)
    put("deltas", buildJsonArray {
        for (d in deltas) {
            add(buildJsonObject {
                put("range", d.range.toJsonObject())
                put("text", d.text)
            })
        }
    })
    put("source", source)
}

data class DocSavePayload(val path: String, val sha256: String)

fun DocSavePayload.toJsonObject(): JsonObject = buildJsonObject {
    put("path", path)
    put("sha256", sha256)
}

data class DocClosePayload(val path: String)

fun DocClosePayload.toJsonObject(): JsonObject = buildJsonObject {
    put("path", path)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests 'dev.provenance.core.EventsTest'`
Expected: PASS (4 tests). Fix the placeholder `deltas` assertion in the test to check `obj["deltas"]!!.jsonArray.size == 1` and `["text"]` content while implementing — the test as drafted above is intentionally sloppy on that one assertion; tighten it during Step 1/Step 4 iteration rather than leaving it as written.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dev/provenance/core/Events.kt \
  core/src/test/kotlin/dev/provenance/core/EventsTest.kt
git commit --no-gpg-sign -m "feat(core): port event payload models (session.*, doc.*) from log-core events.ts"
```

---

### Task 2: `BufferPolicy` + `Clock` (`core/`) — gap-fill prerequisite for SessionWriter

**Files:**
- Create: `core/src/main/kotlin/dev/provenance/core/BufferPolicy.kt`
- Create: `core/src/main/kotlin/dev/provenance/core/Clock.kt`
- Test: `core/src/test/kotlin/dev/provenance/core/BufferPolicyTest.kt`
- Test: `core/src/test/kotlin/dev/provenance/core/ClockTest.kt`

**Interfaces:**
- Produces:
  - `data class BufferPolicyConfig(val maxBytes: Int = 256 * 1024, val maxIntervalMs: Long = 1000)`
  - `data class BufferPolicyInput(val bufferedBytes: Int, val lastFlushAtMs: Long, val nowMs: Long)`
  - `val DEFAULT_BUFFER_POLICY: BufferPolicyConfig`
  - `fun shouldFlush(input: BufferPolicyInput, config: BufferPolicyConfig = DEFAULT_BUFFER_POLICY): Boolean` — same three rules as `log-core`'s `shouldFlush` (never flush empty; size threshold; time threshold).
  - `interface Clock { fun now(): Long; fun wall(): String }`
  - `class SystemClock : Clock` — `now()` = `System.nanoTime() / 1_000_000` (JVM monotonic-nanosecond clock, ms-scaled — the JVM analogue of `performance.now()`; **do not use `System.currentTimeMillis()` for `now()`**, it is wall-clock and can jump). `wall()` = `java.time.Instant.now().toString()` (ISO-8601 UTC, matches `new Date().toISOString()`).
  - `class FixedClock(initialNowMs: Long = 0, initialWall: java.time.Instant = java.time.Instant.EPOCH) : Clock` with `advance(ms: Long)`, `setNow(ms: Long)`, `setWall(instant: java.time.Instant)` — mirrors `log-core`'s `FixedClock` for deterministic tests (CLAUDE.md: no `System.currentTimeMillis()` in assertions).

**Test intent:**
- `shouldFlush`: empty buffer never flushes even past the interval; `bufferedBytes >= maxBytes` flushes; `nowMs - lastFlushAtMs >= maxIntervalMs` flushes; below both thresholds does not.
- `FixedClock.advance` moves both `now()` and `wall()` together; `setNow` moves only the monotonic value (simulates a non-wall jump, matching `log-core`'s clock-skew test fixture use).
- `SystemClock.wall()` matches `Instant.now().toString()`'s ISO-8601-with-`Z` shape (regex check, not an exact value).

- [ ] **Step 1–4: standard TDD** (write failing test → run → implement → run to pass), following the exact structure of Plan 1 Task 1. `BufferPolicy.kt` is a direct line-for-line port of `buffer-policy.ts`'s three-rule decision function; `Clock.kt` mirrors `clock.ts`'s `Clock`/`SystemClock`/`FixedClock` trio with the JVM-appropriate `now()`/`wall()` implementations above.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dev/provenance/core/BufferPolicy.kt \
  core/src/main/kotlin/dev/provenance/core/Clock.kt \
  core/src/test/kotlin/dev/provenance/core/BufferPolicyTest.kt \
  core/src/test/kotlin/dev/provenance/core/ClockTest.kt
git commit --no-gpg-sign -m "feat(core): port BufferPolicy + Clock from log-core (gap-fill for SessionWriter)"
```

---

### Task 3: Pure doc-event transforms (`recorder/`, zero IntelliJ imports)

**Files:**
- Create: `recorder/build.gradle.kts` modification — add `implementation(project(":core"))`, `implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")`, apply `kotlin("plugin.serialization")`, and JUnit 5 test deps (mirror `core/build.gradle.kts`'s JUnit block) if Plan 3 didn't already add these. **VERIFY AT EXECUTION** what Plan 3 already put in this file before duplicating entries.
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/events/DocEventTransforms.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/events/DocEventTransformsTest.kt`

**Interfaces:**
- Consumes: `Position`, `Range`, `DocOpenPayload`, `DocChangeDelta`, `DocChangePayload`, `DocSavePayload`, `DocClosePayload` (Task 1).
- Produces (mirrors `doc-events.ts`'s pure transformers — **no IntelliJ types anywhere in this file**, per CLAUDE.md "test the event-to-log-entry transformation as a pure function, separately from the wiring"):
  - `const val DOC_OPEN_MAX_INLINE_BYTES = 64 * 1024`
  - `fun buildDocOpenPayload(path: String, sha256: String, lineCount: Long, text: String, maxInlineBytes: Int = DOC_OPEN_MAX_INLINE_BYTES): DocOpenPayload` — inlines `text` as `content` when its UTF-8 byte length is `<= maxInlineBytes`; otherwise `content = null, truncated = true`.
  - `fun buildDocChangeDelta(startLine: Long, startChar: Long, endLine: Long, endChar: Long, insertedText: String): DocChangeDelta` — the single-range-delta builder that Task 9's `DocumentListener` seam calls with pre-change coordinates.
  - `fun buildDocChangePayload(path: String, delta: DocChangeDelta, source: String = "typed"): DocChangePayload` — always a single-element `deltas` list in this plan (see Task 9's design note on why IntelliJ's `DocumentEvent` maps 1:1 to one delta, unlike VS Code's potentially multi-delta `TextDocumentChangeEvent`).
  - `fun buildDocSavePayload(path: String, sha256: String): DocSavePayload`
  - `fun buildDocClosePayload(path: String): DocClosePayload`

**Test intent:**
- `buildDocOpenPayload`: text at exactly `maxInlineBytes` UTF-8 bytes inlines; one byte over truncates (use a multi-byte UTF-8 string, e.g. containing `"€"` (3 bytes), to catch a naive `.length` vs UTF-8-byte-length bug — this is the exact class of bug the VS Code recorder's `transformDocOpen` guards against with `TextEncoder().encode(text).length`).
- `buildDocChangeDelta`: builds the expected `Range`/`text` shape from raw line/char/text inputs — no IntelliJ dependency, so this is testable years before an IntelliJ SDK sandbox is available.
- `buildDocChangePayload`: `source` defaults to `"typed"`; deltas list has exactly one element.

- [ ] **Step 1–4: standard TDD**, mirroring the doc-events.ts logic directly. Full code for `buildDocOpenPayload`'s byte-length guard:

```kotlin
fun buildDocOpenPayload(
    path: String,
    sha256: String,
    lineCount: Long,
    text: String,
    maxInlineBytes: Int = DOC_OPEN_MAX_INLINE_BYTES,
): DocOpenPayload {
    val byteLen = text.toByteArray(Charsets.UTF_8).size
    return if (byteLen <= maxInlineBytes) {
        DocOpenPayload(path = path, sha256 = sha256, lineCount = lineCount, content = text, truncated = null)
    } else {
        DocOpenPayload(path = path, sha256 = sha256, lineCount = lineCount, content = null, truncated = true)
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add recorder/build.gradle.kts \
  recorder/src/main/kotlin/dev/provenance/recorder/events/DocEventTransforms.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/events/DocEventTransformsTest.kt
git commit --no-gpg-sign -m "feat(recorder): pure doc-event payload transforms (no IntelliJ deps)"
```

---

### Task 4: `AtomicWrite.kt` — write-temp-then-rename

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/io/AtomicWrite.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/io/AtomicWriteTest.kt`

**Interfaces:**
- Produces: `fun atomicWriteFile(targetPath: Path, contents: ByteArray)` — writes to `<targetPath>.<random-hex>.tmp` via `Files.newOutputStream` + `FileChannel.force(true)` (the JVM fsync equivalent), then `Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)`. On any exception: best-effort `Files.deleteIfExists(tmp)`, then rethrow the original exception (never mask it) — mirrors `atomic-write.ts` exactly, including the "never mask the original error" rule.
- Overload `fun atomicWriteFile(targetPath: Path, contents: String)` = UTF-8 encode + delegate.

**Test intent:**
- Round-trip: write then read back the exact bytes.
- Overwrite: writing twice to the same path leaves only the final content (no leftover `.tmp` files in the directory — `Files.list(dir)` after the call contains exactly the target file).
- Failure path: force a `Files.move` failure (e.g. target is a read-only directory, or inject a fake by writing to a path whose parent doesn't exist) and assert the `.tmp` file does not survive and the original exception propagates (not swallowed, not replaced by a secondary cleanup exception).

- [ ] **Step 1–4: standard TDD.** Use `@TempDir` (JUnit 5) for the test directory. Full implementation:

```kotlin
package dev.provenance.recorder.io

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.random.Random

/**
 * Write-temp-then-rename. Never partial-writes the target file (CLAUDE.md).
 * Mirrors log-core's / the VS Code recorder's atomic-write.ts.
 */
fun atomicWriteFile(targetPath: Path, contents: ByteArray) {
    val randomHex = Random.nextBytes(8).joinToString("") { "%02x".format(it) }
    val tmpPath = targetPath.resolveSibling("${targetPath.fileName}.$randomHex.tmp")
    try {
        Files.newByteChannel(
            tmpPath,
            setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
        ).use { channel ->
            channel.write(java.nio.ByteBuffer.wrap(contents))
            if (channel is java.nio.channels.FileChannel) channel.force(true)
        }
        Files.move(tmpPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (original: Exception) {
        try {
            Files.deleteIfExists(tmpPath)
        } catch (_: Exception) {
            // best-effort; never mask the original error
        }
        throw original
    }
}

fun atomicWriteFile(targetPath: Path, contents: String) =
    atomicWriteFile(targetPath, contents.toByteArray(StandardCharsets.UTF_8))
```

**Note:** `StandardCopyOption.ATOMIC_MOVE` across filesystems (e.g. tmp on a different mount than the target) throws `AtomicMoveNotSupportedException` on some platforms. **VERIFY AT EXECUTION** whether the `.provenance/` dir and its `.tmp` siblings are guaranteed same-filesystem (they are, by construction, since the tmp file is a sibling of the target) — this should be safe, but confirm on Windows CI if Plan 3 targets it.

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/io/AtomicWrite.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/io/AtomicWriteTest.kt
git commit --no-gpg-sign -m "feat(recorder): atomic write-temp-then-rename helper"
```

---

### Task 5: `SessionHost.kt` — chain state machine

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/session/SessionHost.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/session/SessionHostTest.kt`

**Interfaces:**
- Consumes: `core.chainEntry`, `core.GENESIS_PREV_HASH`, `core.Envelope`, `core.HashedEnvelope`, `core.Clock` (Plan 1 Task 4, Task 2 this plan).
- Produces:
  - `interface SessionHost { fun emit(kind: String, data: JsonObject): HashedEnvelope; val sessionId: String; val seq: Long; val tStartMs: Long }`
  - `fun createSessionHost(sessionId: String, clock: Clock, onEntry: (HashedEnvelope) -> Unit): SessionHost` — synchronous: builds the `Envelope`, calls `chainEntry`, advances `seq`/`prevHash` **before** calling `onEntry` (so state stays consistent even if `onEntry` throws — same ordering guarantee as `session-host.ts`), returns the `HashedEnvelope`.

**Test intent:**
- First `emit` uses `GENESIS_PREV_HASH`; second `emit`'s `prevHash` equals the first's `hash`.
- `t` is `max(0, round(clock.now() - tStart))` — inject a `FixedClock`, assert exact `t` values across multiple emits.
- `onEntry` throwing does not prevent `seq`/`prevHash` from advancing (assert a subsequent `emit` still chains correctly after a throwing `onEntry` on the prior call — wrap the throwing call in `assertThrows`, then emit again and check the chain).

- [ ] **Step 1–4: standard TDD**, direct port of `session-host.ts`'s logic (see that file's full source, already read — the Kotlin body is a straight translation: mutable `currentSeq`/`prevHash` closed-over vars, `tStart = clock.now()` captured at creation).

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/session/SessionHost.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/session/SessionHostTest.kt
git commit --no-gpg-sign -m "feat(recorder): SessionHost chain state machine"
```

---

### Task 6: `SessionWriter.kt` — buffered atomic `.slog` appender

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/io/SessionWriter.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/io/SessionWriterTest.kt`

**Interfaces:**
- Consumes: `core.serializeEntry`, `core.HashedEnvelope`, `Clock`, `BufferPolicyConfig`/`shouldFlush` (Task 2), `AppExecutorUtil` (IntelliJ SDK — **this is the first `recorder/` file with a real IntelliJ Platform SDK dependency**).
- Produces:
  - `class SessionWriter private constructor(...)` with `companion object { fun open(slogPath: Path, clock: Clock, bufferPolicy: BufferPolicyConfig = DEFAULT_BUFFER_POLICY, onError: (Exception) -> Unit = {}): SessionWriter }`.
  - `fun append(entry: HashedEnvelope)` — synchronous, non-blocking: serializes via `core.serializeEntry`, appends to an in-memory `StringBuilder`/`MutableList<String>` buffer, tracks `bufferedBytes`, and triggers a background flush via the scheduled executor if `shouldFlush(...)` says so. **Must not block the calling thread** (CLAUDE.md: "doc.change handlers must run in <1ms p99", PRD §4.7) — critically, on IntelliJ this call happens on EDT inside a write action (Task 9), so any blocking I/O here would freeze the UI. The actual file write happens on `AppExecutorUtil`'s pooled executor, never on the calling thread.
  - `fun flush()` — forces a write of whatever is buffered; safe to call concurrently (serialize via a single-threaded executor or an internal lock — see design note below).
  - `fun dispose()` — cancels the periodic flush future, does a final synchronous flush, closes the file channel. Idempotent.

**Design note — no `Promise.all`-shaped concurrency in Kotlin:** the TS `SessionWriter` serializes concurrent `flush()` calls via a promise chain (`this.flushChain = this.flushChain.then(...)`). The direct JVM analogue is **not** spawning a new executor task per `flush()` call and hoping for FIFO ordering (thread pools don't guarantee that) — instead, submit all flush work to a **single-threaded** `Executors.newSingleThreadExecutor()`-style serial queue (or, simpler and dependency-free: guard the actual write with a `synchronized(writeLock)` block and have every `flush()` call go through the *same* `AppExecutorUtil` scheduled task, never a fresh one). Pick the single-threaded-serial-queue approach and document why in a code comment — this is the one place in this plan closest to CLAUDE.md's "no unordered concurrency over operations that must be ordered" rule, translated to JVM terms.

**Test intent:**
- `append` followed by `flush()` writes the exact `serializeEntry` line(s) to disk, in append order.
- Two rapid `append` calls whose combined bytes exceed `maxBytes` trigger an automatic flush without an explicit `flush()` call — poll the file (with a bounded retry loop, not a fixed `Thread.sleep`) until the expected bytes appear, or use a synchronous test seam that lets the test await the scheduled flush deterministically (**VERIFY AT EXECUTION**: prefer injecting a stub scheduled-executor in tests over sleeping on the real `AppExecutorUtil` — real IntelliJ platform services generally require `BasePlatformTestCase`/an initialized `Application`; if `AppExecutorUtil` can't be exercised in a plain JUnit 5 test, this test either moves to the `BasePlatformTestCase` suite (Task 9's test class) or the executor is injected as a constructor parameter with a directly-callable in-test double).
- `dispose()` flushes any remaining buffered bytes and a subsequent `append` throws (mirrors the TS "throws if called after dispose()" contract).
- On a forced write error (inject via a wrapping `FileChannel`/`Path` seam, or point `slogPath` at a location that will fail — e.g. a directory), `onError` is called and the buffered lines are dropped (not retried) — matches the TS writer's documented "never restore dropped lines" policy (risk of duplicate writes on partial success).

- [ ] **Step 1: Write the failing test** for the basic append+flush round trip and the dispose-after-append-throws contract; get the executor-injection design right before writing more tests (this determines whether Step 1 tests can even run without a live IntelliJ Application).

- [ ] **Step 2: Run test to verify it fails.**

- [ ] **Step 3: Write the implementation.** Key structure (buffer/flush logic mirrors `session-writer.ts`; the scheduling seam is the new part):

```kotlin
package dev.provenance.recorder.io

import dev.provenance.core.*
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Injectable so tests don't need a live IntelliJ Application. Production passes
 *  com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService(). */
fun interface FlushScheduler {
    fun scheduleAtFixedRate(periodMs: Long, task: Runnable): ScheduledFuture<*>
}

class SessionWriter private constructor(
    private val channel: FileChannel,
    private val clock: Clock,
    private val bufferPolicy: BufferPolicyConfig,
    private val onError: (Exception) -> Unit,
    scheduler: FlushScheduler,
) {
    private val writeLock = Object()
    private var buffer = StringBuilder()
    private var bufferedBytes = 0
    private var lastFlushAtMs = clock.now()
    private var disposed = false
    private val flushFuture: ScheduledFuture<*> =
        scheduler.scheduleAtFixedRate(bufferPolicy.maxIntervalMs) { flush() }

    companion object {
        fun open(
            slogPath: Path,
            clock: Clock,
            bufferPolicy: BufferPolicyConfig = DEFAULT_BUFFER_POLICY,
            onError: (Exception) -> Unit = {},
            scheduler: FlushScheduler,
        ): SessionWriter {
            Files.createDirectories(slogPath.parent)
            val channel = FileChannel.open(slogPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            return SessionWriter(channel, clock, bufferPolicy, onError, scheduler)
        }
    }

    fun append(entry: HashedEnvelope) {
        check(!disposed) { "SessionWriter.append() called after dispose()" }
        val line = serializeEntry(entry)
        synchronized(writeLock) {
            buffer.append(line)
            bufferedBytes += line.toByteArray(StandardCharsets.UTF_8).size
        }
        if (shouldFlush(BufferPolicyInput(bufferedBytes, lastFlushAtMs, clock.now()), bufferPolicy)) {
            flush()
        }
    }

    fun flush() {
        val snapshot: String
        synchronized(writeLock) {
            if (buffer.isEmpty()) return
            snapshot = buffer.toString()
            buffer = StringBuilder()
            bufferedBytes = 0
        }
        try {
            channel.write(java.nio.ByteBuffer.wrap(snapshot.toByteArray(StandardCharsets.UTF_8)))
            lastFlushAtMs = clock.now()
        } catch (e: Exception) {
            // Lines are already dropped from the buffer — never restore (risk of duplicate
            // writes on partial success). Caller reacts via onError (e.g. recorder.degraded).
            onError(e)
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        flushFuture.cancel(false)
        flush()
        try {
            channel.close()
        } catch (e: Exception) {
            onError(e)
        }
    }
}
```

**Note:** unlike the TS writer's `fh.sync()` per flush, this uses plain `FileChannel.write` per flush and no explicit `force()` — matching PRD §4.7's "flush every 1s/256KB" durability bar (best-effort, not fsync-per-line); `AtomicWrite`'s `force(true)` is reserved for the small, infrequent `.slog.meta`/manifest writes where durability of the *whole file* matters more. **VERIFY AT EXECUTION**: if course policy wants fsync-per-flush on `.slog` too, add `channel.force(false)` after each `write` — flag this as an open question rather than silently deciding it, since it's a durability/performance tradeoff the PRD doesn't fully pin down for the append path specifically (§4.6 says atomic writes are for the meta files; §4.7 only specifies the flush cadence for `.slog`).

- [ ] **Step 4: Run tests to verify they pass.**

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/io/SessionWriter.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/io/SessionWriterTest.kt
git commit --no-gpg-sign -m "feat(recorder): buffered atomic SessionWriter for .slog"
```

---

### Task 7: `MetaWriter.kt` — `.slog.meta`

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/io/MetaWriter.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/io/MetaWriterTest.kt`

**Interfaces:**
- Consumes: `AtomicWrite.atomicWriteFile` (Task 4), `core.Canonical`, `core.EncryptedPrivkey`, `core.Checkpoint` (Plan 2 Tasks 4–5).
- Produces:
  - `data class SlogMeta(val formatVersion: String = "1.0", val sessionId: String, val sessionPubkey: String, val encryptedSessionPrivkey: EncryptedPrivkey, val checkpoints: List<Checkpoint>)` + a `toJsonText()` matching the on-the-wire shape (`format_version`, `session_id`, `session_pubkey`, `encrypted_session_privkey`, `checkpoints`).
  - `class MetaWriter private constructor(...)` with `companion object { fun create(metaPath: Path, sessionId: String, sessionPubkeyHex: String, encryptedPrivkey: EncryptedPrivkey): MetaWriter }` — writes the initial meta file (empty `checkpoints`) immediately, atomically.
  - `fun appendCheckpoint(cp: Checkpoint)` — appends to the in-memory list, re-serializes the **whole** file via `Canonical.canonicalize`, atomic-writes it. (Full-file rewrite per checkpoint, exactly like the TS `MetaWriter` — checkpoints are infrequent (every 100 events per `extension.ts`'s `CHECKPOINT_INTERVAL`), so this is not a hot path.)
  - `fun dispose()` — no-op, for symmetry with `SessionWriter` (the meta file is already durable after each `appendCheckpoint`).

**Test intent:**
- `create` writes a file whose canonical JSON round-trips to a `SlogMeta` with empty `checkpoints`.
- `appendCheckpoint` twice leaves a file with both checkpoints, in order, and no leftover `.tmp` files.
- The written file's bytes are `Canonical.canonicalize`d (assert byte-identity with `Canonical.canonicalize(meta.toJsonText())`, not just "parses to the right value" — this is what the checkpoint-signature-adjacent invariant depends on downstream, even though the meta file itself isn't signed).

- [ ] **Step 1–4: standard TDD**, direct port of `meta-writer.ts`.

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/io/MetaWriter.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/io/MetaWriterTest.kt
git commit --no-gpg-sign -m "feat(recorder): MetaWriter for .slog.meta (checkpoints + encrypted session key)"
```

---

### Task 8: `RecorderContext.kt` — the `session.start` payload builder

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/session/RecorderContext.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/session/RecorderContextTest.kt`

**Interfaces:**
- Consumes: `core.Manifest` (Plan 2 Task 2), `SessionStartPayload` (Task 1).
- Produces: `fun buildRecorderContext(manifest: Manifest, prevSessionId: String?, sessionId: String, sessionPubkeyHex: String, ideVersion: String, platform: String, recorderVersion: String, recorderExtensionId: String, hostnameProvider: () -> String? = ::defaultHostname, usernameProvider: () -> String = { System.getProperty("user.name") ?: "unknown" }): SessionStartPayload` — all environment-dependent lookups (IDE version, plugin descriptor, hostname) are **parameters, not internal calls**, so this function itself is pure and unit-testable without any IntelliJ SDK or JVM environment dependency; a separate thin wrapper (in Task 12) supplies the real values.
  - `fun computeMachineId(hostname: String, username: String, sessionId: String): String = Sha256.hex("$hostname:$username:$sessionId")` (Task 1's `core.Sha256`) — direct port of `computeMachineId` in `recorder-context.ts`. Session-id-salted, matching the monorepo's stated rationale (prevents cross-assignment correlation).
  - `fun defaultHostname(): String? = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")` — the non-blocking hostname lookup (see Global Constraints; **do not** add an `InetAddress.getLocalHost()` fallback without discussion, since that reintroduces the hang risk this design avoids). If both env vars are absent, `machine_id`'s hostname component falls back to the literal string `"unknown"` inside `buildRecorderContext` — flag this fallback explicitly in a code comment, since a silent empty-string hostname would make `machine_id` collide across different machines with the same username, which defeats its purpose.

**Test intent:**
- `buildRecorderContext` with fixed inputs produces the exact expected `SessionStartPayload` (assert every field, including `vscodeCommit = ""` always, `vscodePlatform` passed through verbatim).
- `computeMachineId` is deterministic for the same inputs and differs when any input changes (three cases: different hostname, different username, different sessionId all produce different hashes).
- `defaultHostname()` returns `null` gracefully when neither env var is set (test via a seam — inject the provider function rather than mutating real env vars, since JVM env vars aren't mutable at runtime from within the test process on most platforms).

- [ ] **Step 1–4: standard TDD**, direct port of `recorder-context.ts`'s `buildRecorderContext`/`computeMachineId`, restructured to take environment lookups as parameters per the design note above (this is a deliberate deviation from the TS version, which reads `os.hostname()`/`os.userInfo()` directly inside the function — done here specifically so the function stays a pure, IntelliJ-free unit under JUnit 5, consistent with this plan's testing split).

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/session/RecorderContext.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/session/RecorderContextTest.kt
git commit --no-gpg-sign -m "feat(recorder): RecorderContext session.start payload builder (pure, env injected)"
```

---

### Task 9: Doc wiring — `DocumentListener` + `FileEditorManagerListener` + `FileDocumentManagerListener`

**This is the highest-risk task in this plan** (design.md §4 rates doc edits "Med — reconstruct diff shape"; this task is where that risk is actually paid down).

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/DocWiring.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/wiring/DocWiringTest.kt` (extends `BasePlatformTestCase`)

**Interfaces:**
- Consumes: `buildDocOpenPayload`, `buildDocChangeDelta`, `buildDocChangePayload`, `buildDocSavePayload`, `buildDocClosePayload` (Task 3); `core.Sha256`.
- Produces: `class DocWiring(private val project: Project, private val provenanceDir: Path, private val workspaceRoot: Path, private val emitDocOpen: (DocOpenPayload) -> Unit, private val emitDocChange: (DocChangePayload) -> Unit, private val emitDocSave: (DocSavePayload) -> Unit, private val emitDocClose: (DocClosePayload) -> Unit, parentDisposable: Disposable)` — registers all subscriptions in `init {}`, tied to `parentDisposable` so everything tears down together (mirrors `DocWiringHandle`'s `dispose()`).

**Design decisions to make explicit (not buried in comments, per CLAUDE.md):**

1. **One delta per event, not a batch.** IntelliJ's `DocumentListener` fires one paired `beforeDocumentChange`/`documentChanged` call per atomic single-range mutation. Multi-caret edits fire *multiple* such pairs synchronously within one write action, rather than VS Code's single `TextDocumentChangeEvent` carrying multiple `contentChanges`. Plan 4 therefore emits **one `doc.change` event with a single-element `deltas` array per `DocumentEvent` pair** — this is schema-compatible (`DocChangePayload.deltas: List<DocChangeDelta>`, length 1 is valid) and arguably a more faithful record than batching, at the cost of more log lines for multi-caret edits. No format change needed.
2. **Pre-change coordinates must come from `beforeDocumentChange`, not `documentChanged`.** `DocumentEvent.getOffset()`/`getOldLength()` describe the region being replaced in the *pre-mutation* document. Converting those offsets to `{line, character}` must happen against the document's state **before** it mutates — i.e., inside `beforeDocumentChange`, via `document.getLineNumber(offset)` and `document.getLineNumber(offset + oldLength)` (plus the corresponding column arithmetic: `offset - document.getLineStartOffset(line)`). Calling the same conversion inside `documentChanged` would compute against the *already-mutated* document and produce a wrong range. `getNewFragment()` (the inserted text) is documented as valid specifically during `documentChanged()` of the insertion — read it there.
3. **Per-`Document` pending-range storage, not a single field.** `EditorFactory.getEventMulticaster().addDocumentListener()` is **global** — the same listener instance receives events for every open document across every open project. A single mutable "pending range" field would be clobbered by interleaved events from unrelated documents. Store pending ranges in a `WeakHashMap<Document, Range>` keyed by the `Document` instance (weak so closed documents don't leak), not a bare field. **VERIFY AT EXECUTION**: confirm `beforeDocumentChange`/`documentChanged` for a given document are never interleaved with another change to the *same* document before the pair completes (expected, since both fire synchronously within one write action on EDT) — if IntelliJ ever batches multiple documents' changes within one write action such that pairs interleave, the `WeakHashMap` keying still handles it correctly; a single field would not.
4. **Recordability filter**, mirroring `isRecordable()`/`isProvenanceArtifact()` in `doc-wiring.ts`:
   - `virtualFile.isInLocalFileSystem()` (or equivalent — **VERIFY AT EXECUTION** exact method/property name on `VirtualFile` for "is a real on-disk `file:` URI, not an in-memory/light/diff/http virtual file") — excludes non-`file`-scheme documents (analogous to VS Code's `uri.scheme !== 'file'` check).
   - The file must be under `workspaceRoot` (`virtualFile.toNioPath().startsWith(workspaceRoot)`, or `ProjectFileIndex.getInstance(project).isInContent(virtualFile)` — **VERIFY AT EXECUTION** which is the idiomatic/correct check for "inside this project's content, not some other open project's file" given the multicaster is global across all open projects).
   - The file must not be inside `provenanceDir` and must not be the activation manifest file itself (same self-recording-loop hazard `doc-wiring.ts` documents at length — the plugin must never record its own `.slog`/`.slog.meta`/`manifest.json`/`manifest.sig`/`.provenance-manifest`).
5. **Path relativization**: compute the workspace-relative path the same way for every emitted event — **VERIFY AT EXECUTION** the exact util (`VfsUtilCore.getRelativePath(virtualFile, workspaceRootVirtualFile, '/')` is the leading candidate; confirm against whatever `Path`/`VirtualFile` representation Plan 3's `ActivatedWorkspace.workspaceRoot` actually is).

**Registration seam:**

```kotlin
package dev.provenance.recorder.wiring

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.provenance.recorder.events.*
import java.nio.file.Path
import java.util.WeakHashMap

class DocWiring(
    private val project: Project,
    private val provenanceDir: Path,
    private val workspaceRoot: VirtualFile,
    private val emitDocOpen: (DocOpenPayload) -> Unit,
    private val emitDocChange: (DocChangePayload) -> Unit,
    private val emitDocSave: (DocSavePayload) -> Unit,
    private val emitDocClose: (DocClosePayload) -> Unit,
    parentDisposable: Disposable,
) {
    private data class PendingRange(val range: dev.provenance.core.Range)
    private val pending = WeakHashMap<Document, PendingRange>()
    private val seenPaths = mutableSetOf<String>()

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun beforeDocumentChange(event: DocumentEvent) {
                    val vf = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (!isRecordable(vf)) return
                    pending[event.document] = PendingRange(rangeOf(event.document, event.offset, event.oldLength))
                }

                override fun documentChanged(event: DocumentEvent) {
                    val vf = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (!isRecordable(vf)) return
                    val pendingRange = pending.remove(event.document) ?: return
                    val delta = buildDocChangeDelta(
                        pendingRange.range.start.line, pendingRange.range.start.character,
                        pendingRange.range.end.line, pendingRange.range.end.character,
                        event.newFragment.toString(),
                    )
                    emitDocChange(buildDocChangePayload(relativePath(vf), delta))
                }
            },
            parentDisposable,
        )

        project.messageBus.connect(parentDisposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    if (!isRecordable(file)) return
                    emitDocOpenFor(file)
                }
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (!isRecordable(file)) return
                    emitDocClose(buildDocClosePayload(relativePath(file)))
                }
            },
        )

        // beforeDocumentSaving fires with the about-to-be-written content already in the
        // Document — sha256 of document.getText() at this point IS the content that will
        // land on disk. VERIFY AT EXECUTION the exact topic constant (AppTopics.FILE_DOCUMENT_SYNC
        // vs a newer FileDocumentManagerListener.TOPIC) for the target platform version.
        project.messageBus.connect(parentDisposable).subscribe(
            com.intellij.AppTopics.FILE_DOCUMENT_SYNC,
            object : com.intellij.openapi.fileEditor.FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    val vf = FileDocumentManager.getInstance().getFile(document) ?: return
                    if (!isRecordable(vf)) return
                    val text = document.text
                    val hash = dev.provenance.core.Sha256.hex(text)
                    emitDocSave(buildDocSavePayload(relativePath(vf), hash))
                }
            },
        )

        // Catch-up: files already open at wiring-start time never fire fileOpened.
        // Mirrors doc-wiring.ts's "Issue A fix" — iterate currently-open files synchronously.
        for (vf in FileEditorManager.getInstance(project).openFiles) {
            if (isRecordable(vf)) emitDocOpenFor(vf)
        }
    }

    private fun emitDocOpenFor(vf: VirtualFile) {
        val path = relativePath(vf)
        if (!seenPaths.add(path)) return // defensive de-dup, mirrors seenDocs in doc-wiring.ts
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return
        val text = doc.text
        val hash = dev.provenance.core.Sha256.hex(text)
        emitDocOpen(buildDocOpenPayload(path, hash, doc.lineCount.toLong(), text))
    }

    private fun isRecordable(vf: VirtualFile): Boolean {
        if (!vf.isInLocalFileSystem) return false // VERIFY AT EXECUTION exact property name
        val nioPath = vf.toNioPathOrNull() ?: return false // VERIFY AT EXECUTION availability/name
        if (!nioPath.startsWith(workspaceRoot.toNioPathOrNull() ?: return false)) return false
        if (nioPath.startsWith(provenanceDir)) return false
        // TODO Task 9 Step N: exclude the activation manifest filename(s) explicitly, mirroring
        // MANIFEST_FILE_NAMES in manifest-loader.ts — confirm the exact filename(s) Plan 3 uses.
        return true
    }

    private fun relativePath(vf: VirtualFile): String =
        VfsUtilCore.getRelativePath(vf, workspaceRoot, '/') ?: vf.path

    private fun rangeOf(document: Document, offset: Int, length: Int): dev.provenance.core.Range {
        val startLine = document.getLineNumber(offset)
        val startChar = offset - document.getLineStartOffset(startLine)
        val endOffset = offset + length
        val endLine = document.getLineNumber(endOffset)
        val endChar = endOffset - document.getLineStartOffset(endLine)
        return dev.provenance.core.Range(
            dev.provenance.core.Position(startLine.toLong(), startChar.toLong()),
            dev.provenance.core.Position(endLine.toLong(), endChar.toLong()),
        )
    }
}
```

**Test intent (`BasePlatformTestCase`):**
- `testDocOpenEmittedForFileOpenedViaFixture` — `myFixture.configureByText("hw.py", "print(1)\n")`, assert `emitDocOpen` was called once with `path = "hw.py"`, correct `sha256`/`line_count`, `content = "print(1)\n"`.
- `testDocChangeEmitsSingleDeltaOnType` — configure a file, use `myFixture.type("x")` (or `WriteCommandAction.runWriteCommandAction(project) { document.insertString(...) }` if `myFixture.type` requires a focused editor context not available in a light test — **VERIFY AT EXECUTION**), assert `emitDocChange` receives a `DocChangePayload` with one delta whose `range` is the insertion point and `text = "x"`.
- `testDocChangeRangeIsPreChangeCoordinates` — type at a non-zero offset after inserting a newline first, assert the emitted delta's `range.start.line`/`character` match the position **before** the edit, not after (this is the test that would catch design decision #2 going wrong).
- `testDocSaveEmitsHashOfSavedContent` — configure + modify + `FileDocumentManager.getInstance().saveDocument(document)`, assert `emitDocSave`'s `sha256` matches `Sha256.hex(finalText)`.
- `testDocCloseEmittedOnFileClose` — open then close via `FileEditorManager`, assert `emitDocClose` fires.
- `testFilesInsideProvenanceDirAreNeverRecorded` — configure a file whose path is under a simulated `.provenance/` dir, assert none of the four `emit*` callbacks fire.
- `testAlreadyOpenFileAtWiringStartEmitsSyntheticDocOpen` — configure a file via `myFixture` **before** constructing `DocWiring`, then construct it, assert `emitDocOpen` still fires exactly once (the catch-up loop) and is not double-emitted if a live `fileOpened` also fires for the same document during the same test.

- [ ] **Step 1: Write the failing tests** (the six above, at minimum) against a `DocWiringTest : BasePlatformTestCase()` with fake `emit*` lambdas recording calls into local mutable lists.

- [ ] **Step 2: Run to verify they fail** (compile failure — `DocWiring` doesn't exist yet — or runtime failure if a stub exists). Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.wiring.DocWiringTest'` (**VERIFY AT EXECUTION** the exact Gradle task name the IntelliJ Platform Gradle Plugin's testing extension registers — it may not be the bare `test` task; check Plan 3's `recorder/build.gradle.kts`).

- [ ] **Step 3: Implement `DocWiring.kt`** as sketched above, resolving every "VERIFY AT EXECUTION" inline comment against the real SDK (this will likely take more than one iteration — budget for it, per design.md's "doc edits: Med risk" rating).

- [ ] **Step 4: Run to verify all six pass.**

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/wiring/DocWiring.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/wiring/DocWiringTest.kt
git commit --no-gpg-sign -m "feat(recorder): doc.open/change/save/close wiring via DocumentListener + FileEditorManagerListener"
```

---

### Task 10: Heartbeat

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/Heartbeat.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/wiring/HeartbeatTest.kt` (extends `BasePlatformTestCase`, or a plain JUnit 5 test with injected fakes if the app-activation topic can be stubbed without a live `Application` — **VERIFY AT EXECUTION**)

**Interfaces:**
- Produces: `class Heartbeat(private val emit: (SessionHeartbeatPayload) -> Unit, private val clock: Clock, private val getActiveFile: () -> String?, intervalMs: Long = 30_000, parentDisposable: Disposable)`.
- Tracks `lastActivityAtMs`, reset on: app (de)activation transitions, active-editor changes, document changes (reuse a lightweight subscription, not the full `DocWiring` seam). On each tick (via `AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay`), reads focus state **live** (not cached) and calls `emit(SessionHeartbeatPayload(focused, activeFile, now - lastActivityAtMs))`.
- Focus source: `com.intellij.openapi.application.ApplicationActivationListener` on the application-level message bus (`ApplicationManager.getApplication().messageBus.connect(parentDisposable).subscribe(ApplicationActivationListener.TOPIC, ...)`), methods `applicationActivated(ideFrame)`/`applicationDeactivated(ideFrame)`. **VERIFY AT EXECUTION** the exact package/topic name and whether "focused" should be tracked as a boolean flipped by these two callbacks or read from a different live accessor at tick time (`WindowManager.getInstance().getFocusedComponent(project) != null` is a plausible alternative if `ApplicationActivationListener` doesn't expose a simple poll — the PRD requirement is just "window focused (bool)" at tick time, so either a cached-flag-updated-by-listener or a live-poll approach satisfies it; pick the live-poll approach if available, matching the TS heartbeat's explicit "read windowState.focused LIVE — do not cache it" comment).
- `dispose()` cancels the scheduled future and disconnects the message bus subscriptions (or relies on `parentDisposable` for the latter).

**Test intent:**
- Tick emits with `idle_since_ms` growing when no activity resets it (inject a `FixedClock`, advance it, tick, assert `idle_since_ms` matches the advance).
- An activity reset (simulate a document-change callback firing) brings `idle_since_ms` back near zero on the next tick.
- `dispose()` stops further ticks (advance the clock further, tick manually if using an injectable scheduler, assert `emit` was not called again).

- [ ] **Step 1–4: standard TDD**, direct port of `heartbeat.ts`'s activity-reset + live-focus-read design, substituting the IntelliJ equivalents above for the three VS Code subscriptions.

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/wiring/Heartbeat.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/wiring/HeartbeatTest.kt
git commit --no-gpg-sign -m "feat(recorder): session.heartbeat every 30s via ApplicationActivationListener"
```

---

### Task 11: Bundle seal

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/commands/SealBundle.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/commands/SealBundleTest.kt` (plain JUnit 5 — the seal logic itself takes `Path`s and byte arrays, no IntelliJ SDK dependency required for the core function; only a thin `AnAction` wrapper, deferred to Step 6 below, touches the SDK)

**Interfaces:**
- Consumes: `core.parseEntries`, `core.validateChain`, `core.signBundleManifest`, `core.BundleManifest`, `core.SessionEntry`, `core.SubmissionFileEntry` (Plan 2 Task 3); `AtomicWrite.atomicWriteFile` (Task 4); `core.Sha256`.
- Produces: `data class SealResult` (sealed: `Ok(bundlePath: Path, manifestSha256: String, chainBroken: Boolean, unreadableSession: Boolean)`, `NoSessions`, `WriteError(message: String)`) and

```kotlin
fun sealBundle(
    provenanceDir: Path,
    workspaceRoot: Path,
    assignmentId: String,
    semester: String,
    filesUnderReview: List<String>,
    sessionPrivkey: ByteArray,
    computeExtensionHash: () -> String,
    outputDir: Path = workspaceRoot,
    now: () -> java.time.Instant = java.time.Instant::now,
): SealResult
```

Step-by-step (direct port of `seal.ts`'s `sealBundle`, same "never abort on a broken/unparseable chain, accumulate warnings instead" policy):

1. List `*.slog` files in `provenanceDir` (excluding `*.slog.meta`). None → `NoSessions`.
2. For each: read text, `parseEntries` (accumulate `unreadableSession = true` on parse failure, still record file hashes via `core.Sha256` of the raw bytes), `validateChain` (accumulate `chainBroken = true` on failure, do not stop), extract `session_id`/`prev_session_id` from the first entry if it's a `session.start` (`null` otherwise), compute `slog_sha256`/`meta_sha256` (sha256 of empty bytes if the `.meta` file is missing — same defensive fallback as the TS version).
3. Read each `filesUnderReview` entry's raw bytes from `workspaceRoot`; `present`/`sha256` or `missing`/`null`.
4. Build `BundleManifest(formatVersion = "1.1", assignmentId, semester, extensionHash = computeExtensionHash(), sessions, submissionFiles)`.
5. `signBundleManifest(manifest, sessionPrivkey)` → atomic-write `manifest.json` (the exact canonical JSON that was signed) and `manifest.sig` into `provenanceDir`.
6. Zip **everything** currently in `provenanceDir` (including the just-written manifest + sig) via `java.util.zip.ZipOutputStream`, skipping `*.tmp` and `*.corrupt-*` files, **plus** the raw bytes of every `present` reviewed file at its workspace-relative path in the zip root (mirrors `seal.ts`'s two-part zip contents).
7. Write the zip to `outputDir/<assignmentId>-bundle-<ISO-timestamp-with-colons-as-dashes>.zip`. Return `Ok`.

**Test intent:**
- No `.slog` files in `provenanceDir` → `NoSessions`.
- A single valid, chain-intact `.slog` + a valid `manifest.sig`-verifiable output: build a small fixture session (hand-construct 2–3 chained `HashedEnvelope`s via `core.chainEntry`, write them via `serializeEntry` to a temp `.slog`), call `sealBundle`, then **read the produced zip back** (`java.util.zip.ZipFile`) and assert: `manifest.json` + `manifest.sig` present, `manifest.json`'s bytes canonicalize to themselves (already-canonical), `manifest.sig` verifies against the test's public key via `core.Ed25519.verify` (Plan 2 Task 1), the `.slog` file's bytes are present unmodified.
- A deliberately corrupted `.slog` (broken hash chain) still produces a sealed bundle (`Ok` with `chainBroken = true`), not an error — this is the specific "never abort" behavior worth a dedicated test given how easy it is to accidentally add an early-return.
- A `filesUnderReview` entry that doesn't exist on disk appears in the manifest's `submissionFiles` as `status = "missing", sha256 = null` and is **not** present in the zip.

- [ ] **Step 1–4: standard TDD** for the core `sealBundle` function using `@TempDir` fixtures, following the seven steps above verbatim from `seal.ts`.

- [ ] **Step 5: Run the full test class, verify all pass.**

- [ ] **Step 6: Thin `AnAction` wrapper** (`SealBundleAction.kt`) that calls `sealBundle` with real paths/keys from the active session (constructed in Task 12) and shows a notification with the result — **VERIFY AT EXECUTION** exact `AnAction`/`Notification` API and whether Plan 3 already registered an actions/notification-group scaffold to hook into, since this is squarely IntelliJ-UI territory Plan 3 may already own conventions for.

- [ ] **Step 7: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/commands/SealBundle.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/commands/SealBundleTest.kt
git commit --no-gpg-sign -m "feat(recorder): bundle seal (manifest sign + zip .provenance/ + reviewed files)"
```

---

### Task 12: Composition — `RecordingSessionController`

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/session/RecordingSessionController.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/session/RecordingSessionControllerTest.kt` (`BasePlatformTestCase`)

**Interfaces:**
- Consumes: `ActivatedWorkspace` (Task 0's assumed Plan 3 interface), `SessionHost` (Task 5), `SessionWriter` (Task 6), `MetaWriter` (Task 7), `buildRecorderContext` (Task 8), `DocWiring` (Task 9), `Heartbeat` (Task 10), `generateSessionKeypair`/`encryptSessionPrivkey` (Plan 2 Task 4).
- Produces: `class RecordingSessionController(activated: ActivatedWorkspace, project: Project, ideVersion: String, platform: String, recorderVersion: String, recorderExtensionId: String, parentDisposable: Disposable)` — on construction, mirrors `activateImpl`'s Steps 3c–11 (session-host.ts/recorder-context.ts/session-writer.ts/meta-writer.ts callers in `extension.ts`, minus everything this plan explicitly excludes — paste intercept, fs watcher, terminal, git, extension snapshot):
  1. Generate session keypair.
  2. `buildRecorderContext(...)`.
  3. Open `SessionWriter` at `<provenanceDir>/session-<uuid>.slog`.
  4. Encrypt the session privkey under `manifest.sig`, create `MetaWriter`.
  5. Create `SessionHost`, wired so `onEntry` calls `writer.append(entry)`.
  6. `sessionHost.emit("session.start", recorderContext.toJsonObject())`.
  7. Start `Heartbeat` and `DocWiring`, both tied to `parentDisposable`.
  - `fun endSession(reason: String)` — emits `session.end`, flushes and disposes the writer, disposes the meta writer. Idempotent (guards against double-call, mirroring `deactivate()`'s `activeSession = null` guard).

**Test intent:**
- Construct against a real `BasePlatformTestCase` temp project + a fixture `ActivatedWorkspace` (hand-built `Manifest`, a `@TempDir`-backed `provenanceDir`); assert after construction that the `.slog` file exists and its first line, once flushed, parses (via `core.parseEntries`) to a single `session.start` entry with `seq = 0` and the expected `assignment_id`/`session_pubkey`.
- Type into a fixture-configured file after construction; assert (after an explicit `writer.flush()` or a bounded poll) that a `doc.change` entry appears in the `.slog` chained correctly after `session.start` (`prev_hash` equals `session.start`'s `hash`).
- `endSession` appends a `session.end` entry and the writer becomes unusable afterward (`append` throws, per Task 6's contract).

- [ ] **Step 1–4: standard TDD**, wiring the pieces exactly as in `extension.ts`'s `activateImpl`/`deactivate`, restricted to this plan's scope (no paste/fs-watcher/terminal/git/ext-snapshot — those are explicitly later plans; do not add stub no-op wiring for them here, just omit them, matching "stay in scope").

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/session/RecordingSessionController.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/session/RecordingSessionControllerTest.kt
git commit --no-gpg-sign -m "feat(recorder): RecordingSessionController — compose session host/writer/doc-wiring/heartbeat"
```

---

### Task 13: Validate a real sealed bundle against the monorepo's analysis-core (success criterion)

This is the plan's stated success criterion: **a sealed bundle the real Provenance analyzer/server accepts.** `analysis-core`'s `loadBundle`/`runValidation` (both exported from `@provenance/analysis-core`'s barrel, `packages/analysis-core/src/index.ts`) are the ground truth — not a Kotlin-side reimplementation of the checks.

**Files:**
- Create (in the **monorepo**, `/Users/aaryanmehta/projects/provenance`, not this repo — a throwaway verification script, not committed to either repo's normal source tree unless the user wants it kept): `/Users/aaryanmehta/projects/provenance/scripts/validate-jetbrains-bundle.mjs` — **ask before adding this file to the monorepo**, since CLAUDE.md there also says "stay in scope" and this repo's own CLAUDE.md says "this repo never changes the Provenance monorepo except two small, additive things" (allowlist entry + golden-vector export script, both Plan 9). A throwaway script run via `node --input-type=module -e '...'` (not committed) is the safer default; only add a checked-in script if asked.
- No production code changes in this repo for this task — it is a **verification step**, not a feature.

**Procedure:**

1. In this repo (`provenance-jetbrains-recorder`), run the `RecordingSessionControllerTest`-style flow but end-to-end and headless-writing to a real temp directory (or add a small `Main.kt`/test-only harness that: constructs an `ActivatedWorkspace` against a hand-built `Manifest` signed with a **test** course keypair generated via `core.Ed25519.generateKeypair()`, drives a `RecordingSessionController` through a `session.start` → a few `doc.open`/`doc.change`/`doc.save` calls (simulated, not necessarily through a live IDE UI) → `endSession`, then calls `sealBundle` (Task 11) to produce a real `.zip` on disk). Print the absolute path of the produced zip.
2. From the monorepo, run:

```bash
node --input-type=module -e '
import { readFileSync } from "node:fs";
import { loadBundle, runValidation } from "@provenance/analysis-core";
const bytes = readFileSync(process.argv[1]);
const result = await loadBundle(bytes.buffer, "jetbrains-test-bundle.zip");
if (!result.ok) { console.error("LOAD FAILED:", result.error); process.exit(1); }
const report = await runValidation(result.value);
console.log(JSON.stringify(report, null, 2));
if (report.overall === "fail") process.exit(1);
' /absolute/path/to/the/sealed/bundle.zip
```

(Run from `packages/analysis-core/` or with `node`'s module resolution able to find the workspace-linked `@provenance/analysis-core` — **VERIFY AT EXECUTION** the exact invocation; `npm run build --workspace=packages/analysis-core` must have been run first so `dist/` exists, since the package's `exports` map points at `./dist/index.js`, not `./src/`.)

3. Expected: `report.overall` is `"pass"` or `"warn"` (warn is acceptable — Check 8, `submitted_code_match`, is skipped for a 1.1 bundle with no independently-supplied "submitted code" to cross-check against in this synthetic test, which correctly yields `skipped` → overall `warn`, not `fail`). **`report.overall === "fail"` means either the chain is broken, the manifest signature doesn't verify against `session_pubkey`, or `session_pubkey` doesn't bind to `manifest_sig` — any of these is a real bug in Tasks 1–12, not an acceptable outcome.** Specifically assert (reading `report.checks`, in PRD §5.4 order): check 1 (`manifest signature verifies`) status `"pass"`, check 2 (`session_pubkey bound to manifest_sig`) status `"pass"`, check 3 (`hash chain intact`) status `"pass"`.
4. If any of checks 1–3 fail, the bug is almost certainly one of: (a) a canonicalization mismatch between this repo's `Canonical.canonicalize` and `log-core`'s (should have been caught by Plan 1/2's conformance suite already — if not, that suite has a gap, go fix it there first, not here); (b) the manifest's signed-payload field set doesn't match PRD §4.1's exact `{assignment_id, semester, issued_at, files_under_review}` shape; (c) `RecorderContext`'s `session_pubkey` field doesn't match the actual keypair used to sign the bundle manifest in `sealBundle`. Do not patch the analyzer to accept a Kotlin-side quirk — the analyzer is the fixed contract (CLAUDE.md: "never author the format here").

- [ ] **Step 1:** Produce a real sealed bundle via the harness described above (either a dedicated `Main.kt` smoke-test entry point in `recorder/src/test/kotlin/.../EndToEndSealSmokeTest.kt` marked as a manual/slow test, or a literal manual run — pick whichever this codebase's existing test conventions favor once Plan 3 exists; **VERIFY AT EXECUTION**).
- [ ] **Step 2:** Run the monorepo validation script above against the produced zip.
- [ ] **Step 3:** Confirm checks 1–3 are `"pass"` and `report.overall !== "fail"`. Record the exact `report` JSON output in the PR/commit description for this task as evidence (CLAUDE.md: "evidence before assertions").
- [ ] **Step 4:** No commit in this repo unless Step 1's harness is a real, intentionally-kept test file — if so, commit it:

```bash
git add recorder/src/test/kotlin/dev/provenance/recorder/EndToEndSealSmokeTest.kt
git commit --no-gpg-sign -m "test(recorder): end-to-end smoke test — sealed bundle validated against real analysis-core"
```

---

## Self-Review

**Spec coverage (design.md §8 step 3):** event payload models + buffer policy/clock gap-fill (Tasks 1–2), doc.open/change/save/close pure transforms + IntelliJ wiring (Tasks 3, 9), session.start/heartbeat/end (Tasks 5, 8, 10, 12), atomic session writer + meta writer (Tasks 4, 6, 7), bundle seal (Task 11), and the plan's actual success criterion — real-analyzer acceptance — as an explicit, non-skippable task (Task 13) rather than an implied "should work." Explicitly out of scope and not touched: paste detection, external-change detection, terminal/git wiring, checkpoints-triggering-recovery, disk-full degraded mode (Plans 5–8) — `MetaWriter`/`Checkpoint` plumbing exists (reused from Plan 2) but checkpoint *signing on a schedule* is wired in Task 12 minimally (matching `extension.ts`'s `CHECKPOINT_INTERVAL` pattern) only insofar as `SessionHost`'s `onEntry` needs *some* home for it to keep the `.slog.meta` non-empty for Task 13's validation; if this feels like scope creep during execution, it's acceptable to stub `MetaWriter` with zero checkpoints for Task 13's purposes and defer periodic checkpoint signing entirely to Plan 8, since PRD §5.4's checks 1–3 (Task 13's actual gate) don't depend on checkpoints being present.

**Placeholder scan:** every task has concrete file paths, full or near-full Kotlin bodies, and named test methods. The exceptions are intentional and marked: Task 9's `isRecordable()` has one `TODO` for the manifest-filename exclusion (genuinely blocked on Plan 3's exact filename constant) and several inline `VERIFY AT EXECUTION` markers on IntelliJ SDK method names I could not 100%-confirm via the fetched documentation (method/property existence was confirmed by search-result summaries and general platform knowledge, not by reading actual JetBrains source/javadoc line-by-line).

**Type consistency:** `core.Events.kt` (Task 1) payload types flow unchanged into Task 3's pure transforms, Task 9's wiring, and Task 12's `RecorderContext`/`SessionHost` composition. `Clock`/`BufferPolicyConfig` (Task 2) flow into `SessionWriter` (Task 6) and `SessionHost` (Task 5) unchanged. `core.HashedEnvelope`/`chainEntry`/`serializeEntry`/`parseEntries`/`validateChain` (Plan 1) and `core.BundleManifest`/`signBundleManifest`/`Ed25519`/`EncryptedPrivkey`/`Checkpoint` (Plan 2) are consumed as-is with no signature changes proposed.

**Open dependency approvals:** none beyond what Plans 1–2 already approved (IntelliJ Platform SDK, erdtman JCS, an ed25519 provider) plus the IntelliJ Platform SDK's `AppExecutorUtil`/`DocumentListener`/`FileEditorManagerListener`/`ApplicationActivationListener` surfaces, which are part of the SDK dependency Plan 3 already establishes, not a new library. `java.util.zip` and `java.nio` are JDK-native. **No new Gradle dependency is proposed by this plan** — flag this explicitly since it's worth confirming during review that no task quietly reached for a convenience library (e.g. a zip helper, a scheduling library) instead of the JDK/platform-native tool.

**Fidelity note (required — this plan leans harder on inference than Plans 1–2):** Plans 1–2 ported deterministic, source-transcribed logic (hash math, crypto) with a monorepo file open next to every task — high confidence throughout. This plan's `core/` tasks (1–2) carry the same confidence. Its `recorder/` pure-logic tasks (3–8, and the core of 11) are direct, source-transcribed ports of files I read in full (`doc-events.ts`, `session-host.ts`, `session-writer.ts`, `atomic-write.ts`, `meta-writer.ts`, `seal.ts`, `recorder-context.ts`, `heartbeat.ts`) — also high confidence. **Task 9 (doc wiring) and, to a lesser extent, Task 10 (heartbeat) are the plan's weakest-verified parts**: IntelliJ Platform SDK method/class names (`DocumentListener`, `EditorFactory.getEventMulticaster()`, `FileEditorManagerListener.FILE_EDITOR_MANAGER`, `FileDocumentManagerListener`/`AppTopics.FILE_DOCUMENT_SYNC`, `VirtualFile.isInLocalFileSystem()`, `VfsUtilCore.getRelativePath()`, `ApplicationActivationListener.TOPIC`, `AppExecutorUtil.getAppScheduledExecutorService()`) were confirmed to *exist and roughly do what's claimed* via JetBrains' own docs pages and search-result summaries (not by reading the actual Java source or a working sandbox build), and are marked `VERIFY AT EXECUTION` throughout Task 9's design notes and code. The three genuinely novel design decisions in Task 9 (single-delta-per-event, pre-change-coordinate capture in `beforeDocumentChange`, per-`Document` `WeakHashMap` pending-range storage) are my own reasoning from IntelliJ's documented `DocumentEvent` semantics, not transcribed from any existing Kotlin/Java source — they are the actual risk this task is meant to retire, and should get the most scrutiny in review, exactly as design.md §4 predicts ("doc edits: Med — reconstruct diff shape").
