# Terminal Wiring + Git Wiring + Plugin Snapshot Implementation Plan (Plan 7 of the provjet series)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the VS Code recorder's terminal activity, git activity, and periodic extension-snapshot wiring to the IntelliJ Platform, filling the existing `terminal.open` / `terminal.command` / `git.event` / `ext.snapshot` event shapes (already pinned in `log-core`) with IntelliJ-native signal sources. Terminal and git wiring must **not** load, reference, or crash on IDEs where the Terminal plugin or Git4Idea are absent.

**Architecture:** Same split as the VS Code source this ports from (`packages/recorder/src/wiring/terminal-wiring.ts`, `git-wiring.ts`, `extension-snapshot.ts`): a **pure payload-builder function per event kind** (input: plain Kotlin data, output: the exact on-the-wire `JsonObject`), tested with plain JUnit and zero IntelliJ Platform dependency — plus a **thin wiring class** that talks to the real Platform APIs and calls the builder. Terminal and git wiring are additionally gated at the plugin-descriptor level via IntelliJ's **optional plugin dependency** mechanism (`<depends optional="true" config-file="...">`), so the classes that reference `Git4Idea`/terminal-plugin types are never classloaded on an IDE that doesn't have those plugins — this is the platform-recommended way to avoid `NoClassDefFoundError` on optional deps (see Task 5/6 citations), and is stronger than a runtime `isPluginInstalled()` check sprinkled into always-loaded code.

**Tech Stack:** Kotlin, IntelliJ Platform Gradle Plugin (2.x) `bundledPlugins(...)` for compile-time visibility of `Git4Idea` and `org.jetbrains.plugins.terminal` types, kotlinx-serialization-json (from `core/`), JUnit 5. Builds on `core/`'s `Envelope`/JSON helpers (Plans 1–2) for the payload shape and on Plan 4's (not yet written) session/event-emission seam — see "Prerequisites and assumed seams" below.

---

## Prerequisites and assumed seams (read before starting)

This plan is written **ahead of** Plans 3–6 (per the series order in `docs/plans/2026-07-14-core-format-port.md`'s header: Plan 3 = plugin scaffold + activation, Plan 4 = doc wiring + session writer, Plan 5 = external-change detection, Plan 6 = paste detection). As of this writing, none of those exist in this repo yet — only `core/` is planned (Plans 1–2), not built. That means this plan cannot cite real file paths/signatures from Plan 4's session writer or Plan 3's plugin scaffold, because they don't exist yet.

To keep this plan **executable on its own** (design.md §8 says wiring should be independently green per stage), it defines two narrow, clearly-labeled placeholder seams:

1. **`RecorderEventSink`** — a `fun interface` of shape `(kind: String, data: JsonObject) -> Unit`. This is the injection point every wiring class in this plan emits through, mirroring the VS Code source's injected `emit`/`emitTerminalOpen`/`emitTerminalCommand` callback pattern (dependency injection for testability — see CLAUDE.md "Testing").
2. **`ActiveRecorderSession`** — a tiny process-wide holder of the current session's `RecorderEventSink?` (null when no assignment workspace is active).

**VERIFY AT EXECUTION:** By the time this plan is implemented, Plan 4 will likely have landed and will own the *real* session lifecycle and event emission (probably a project service wrapping the atomic session writer + hash chain). When that exists:
- If Plan 4's emitter has the same `(kind, data) -> Unit` shape (or one `RecorderEventSink` can trivially wrap), delete `ActiveRecorderSession` and inject Plan 4's real service into the wiring classes below instead.
- If Plan 4's activation model is materially different (e.g. wiring classes should be constructed fresh per session rather than reading a static holder), keep this plan's pure payload-builder tasks (1–3) unchanged — they have no seam dependency — and rewrite only the wiring classes' construction site (Tasks 4–6) to match.
- Either way, **do not weaken or skip the optional-dependency gating in Tasks 5–6** regardless of how the emitter seam is resolved; that gating is independent of Plan 4.

This plan also assumes Plan 3 has created the `recorder/` Gradle module (added to `settings.gradle.kts`), applied the IntelliJ Platform Gradle Plugin, and created `recorder/src/main/resources/META-INF/plugin.xml` with at least `<id>`, `<name>`, and `<depends>com.intellij.modules.platform</depends>`. **VERIFY AT EXECUTION** against Plan 3's actual output; adjust paths below if they differ.

## Global Constraints

(Inherits Plan 1/2's Global Constraints — format is a fixed contract, do not edit pinned vectors.) Additional for this plan:

- **Event kinds and payload shapes are pinned in `log-core`** (`packages/log-core/src/events.ts:116-130`, `:188-191`) and PRD §4.2/§4.4. Reproduced here for reference — **do not add, rename, or drop fields**:
  - `terminal.open` → `{ terminal_id: string, shell: string, shell_integration: boolean }`
  - `terminal.command` → `{ terminal_id: string, command: string, exit_code?: number }`
  - `ext.snapshot` → `{ extensions: Array<{ id: string, version: string, enabled: boolean }> }` (field is literally `extensions` even though JetBrains calls them plugins — **do not rename this field**, it's the wire contract)
  - `git.event` → `{ operation: string, commit_sha?: string }`
- **Match the VS Code recorder's actual emitted values, not just the payload shape.** The ported `git-wiring.ts` hardcodes `operation: 'state_change'` for every emission regardless of what actually happened (commit/checkout/branch switch/index change) — PRD §4.2's table says "operation (commit/checkout/etc)" but the *implementation* PRD §4 defers to is the flat `'state_change'` string. Match the implementation, not the table's illustrative wording, per this repo's "port the wiring, not a new product" mandate.
- **Optional-plugin isolation is structural, not a runtime `if`.** Any class that directly references a `Git4Idea` or `org.jetbrains.plugins.terminal` type must be loaded *only* via the optional `<depends optional="true" config-file="...">` descriptor for that plugin — never from the main `plugin.xml`'s always-loaded extensions, even guarded by a `PluginManagerCore.isPluginInstalled()` check. A static reference to an absent plugin's class throws `NoClassDefFoundError` at class-verify time regardless of any runtime guard around it. This is the officially documented pattern (`https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html`) and the community-recommended fix for exactly this failure mode.
- **`Git4Idea`'s plugin id is the literal string `Git4Idea`** (not reverse-DNS) — confirmed from the plugin's own `plugin.xml` (`https://github.com/JetBrains/intellij-community/blob/master/plugins/git4idea/resources/META-INF/plugin.xml`, `<id>Git4Idea</id>`). The terminal plugin's id is `org.jetbrains.plugins.terminal`.
- **No background task without a `dispose()` path** (CLAUDE.md). The periodic plugin-snapshot uses `com.intellij.util.Alarm`, which self-cancels when its parent `Disposable` is disposed — but `dispose()` still explicitly calls `cancelAllRequests()` per this repo's convention of not relying solely on implicit platform cleanup.
- **Determinism in pure-builder tests:** no `System.currentTimeMillis()`, no live `PluginManagerCore`/`GitRepositoryManager` calls in Task 1–3 tests — those are plain-data-in, `JsonObject`-out functions.

---

### Task 1: Terminal event payload builders (pure, no platform deps)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/RecorderEventSink.kt` (the seam — see "Prerequisites" above)
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/TerminalPayloads.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/wiring/TerminalPayloadsTest.kt`

**Interfaces:**
- Produces:
  - `fun interface RecorderEventSink { fun emit(kind: String, data: JsonObject) }`
  - `object ActiveRecorderSession { @Volatile var sink: RecorderEventSink? = null }` — placeholder holder, see Prerequisites.
  - `data class TerminalOpenInfo(val terminalId: String, val shell: String, val shellIntegration: Boolean)`
  - `fun buildTerminalOpenPayload(info: TerminalOpenInfo): JsonObject`
  - `data class TerminalCommandInfo(val terminalId: String, val command: String, val exitCode: Int?)`
  - `fun buildTerminalCommandPayload(info: TerminalCommandInfo): JsonObject`

**Step 1: Write the failing test**

`recorder/src/test/kotlin/dev/provenance/recorder/wiring/TerminalPayloadsTest.kt`:
```kotlin
package dev.provenance.recorder.wiring

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TerminalPayloadsTest {
    @Test
    fun `terminal open payload has exactly the pinned fields`() {
        val obj = buildTerminalOpenPayload(TerminalOpenInfo("term-0", "/bin/zsh", true))
        assertEquals("term-0", obj["terminal_id"]!!.jsonPrimitive.content)
        assertEquals("/bin/zsh", obj["shell"]!!.jsonPrimitive.content)
        assertEquals(true, obj["shell_integration"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(setOf("terminal_id", "shell", "shell_integration"), obj.keys)
    }

    @Test
    fun `terminal command payload omits exit_code when null`() {
        val obj = buildTerminalCommandPayload(TerminalCommandInfo("term-0", "ls -la", null))
        assertEquals("term-0", obj["terminal_id"]!!.jsonPrimitive.content)
        assertEquals("ls -la", obj["command"]!!.jsonPrimitive.content)
        assertNull(obj["exit_code"])
        assertEquals(setOf("terminal_id", "command"), obj.keys)
    }

    @Test
    fun `terminal command payload includes exit_code when present`() {
        val obj = buildTerminalCommandPayload(TerminalCommandInfo("term-1", "pytest", 1))
        assertEquals(1L, obj["exit_code"]!!.jsonPrimitive.content.toLong())
        assertEquals(setOf("terminal_id", "command", "exit_code"), obj.keys)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.wiring.TerminalPayloadsTest'`
Expected: FAIL — `buildTerminalOpenPayload` / `TerminalOpenInfo` unresolved.

**Step 3: Write the implementation**

`recorder/src/main/kotlin/dev/provenance/recorder/wiring/RecorderEventSink.kt`:
```kotlin
package dev.provenance.recorder.wiring

import kotlinx.serialization.json.JsonObject

/**
 * ASSUMED SEAM (Plan 7, pending Plan 4). The injection point every wiring class in this
 * package emits through — mirrors the VS Code recorder's injected `emit*` callback pattern
 * (packages/recorder/src/wiring/*.ts) for testability without a live session writer.
 *
 * When Plan 4 lands its real session/event-emission service, prefer wiring these classes
 * to that service directly. If its shape is compatible, delete [ActiveRecorderSession] and
 * pass Plan 4's emitter in at construction time instead of reading a static holder.
 */
fun interface RecorderEventSink {
    fun emit(kind: String, data: JsonObject)
}

/**
 * ASSUMED SEAM (Plan 7, pending Plan 4). Process-wide holder of the current session's sink;
 * null when no assignment workspace is active (mirrors "record nothing outside an activated
 * workspace", CLAUDE.md "Architecture rules"). Superseded by Plan 4's real activation model.
 */
object ActiveRecorderSession {
    @Volatile
    var sink: RecorderEventSink? = null
}
```

`recorder/src/main/kotlin/dev/provenance/recorder/wiring/TerminalPayloads.kt`:
```kotlin
package dev.provenance.recorder.wiring

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure event-kind payload builders for terminal.* events. Mirrors
 * packages/recorder/src/wiring/terminal-wiring.ts's emitTerminalOpen/emitTerminalCommand
 * shapes exactly (log-core events.ts:116-126). No IntelliJ Platform dependency — the platform
 * wiring (Task 5) constructs these info objects from real Terminal API state and calls these
 * builders, kept separate per this repo's "test the event->log-entry transform as a pure
 * function, separately from the platform wiring" convention (CLAUDE.md "Testing").
 */
data class TerminalOpenInfo(val terminalId: String, val shell: String, val shellIntegration: Boolean)

fun buildTerminalOpenPayload(info: TerminalOpenInfo): JsonObject =
    buildJsonObject {
        put("terminal_id", info.terminalId)
        put("shell", info.shell)
        put("shell_integration", info.shellIntegration)
    }

data class TerminalCommandInfo(val terminalId: String, val command: String, val exitCode: Int?)

fun buildTerminalCommandPayload(info: TerminalCommandInfo): JsonObject =
    buildJsonObject {
        put("terminal_id", info.terminalId)
        put("command", info.command)
        if (info.exitCode != null) put("exit_code", info.exitCode)
    }
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.wiring.TerminalPayloadsTest'`
Expected: PASS (3 tests).

**Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/wiring/RecorderEventSink.kt \
  recorder/src/main/kotlin/dev/provenance/recorder/wiring/TerminalPayloads.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/wiring/TerminalPayloadsTest.kt
git commit --no-gpg-sign -m "feat(recorder): terminal.open/terminal.command payload builders + event sink seam"
```

---

### Task 2: Git event payload builder (pure, no platform deps)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/GitPayloads.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/wiring/GitPayloadsTest.kt`

**Interfaces:**
- Produces:
  - `data class GitEventInfo(val operation: String, val commitSha: String?)`
  - `fun buildGitEventPayload(info: GitEventInfo): JsonObject`

**Step 1: Write the failing test**

`recorder/src/test/kotlin/dev/provenance/recorder/wiring/GitPayloadsTest.kt`:
```kotlin
package dev.provenance.recorder.wiring

import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitPayloadsTest {
    @Test
    fun `git event omits commit_sha when null`() {
        val obj = buildGitEventPayload(GitEventInfo("state_change", null))
        assertEquals("state_change", obj["operation"]!!.jsonPrimitive.content)
        assertNull(obj["commit_sha"])
        assertEquals(setOf("operation"), obj.keys)
    }

    @Test
    fun `git event includes commit_sha when present`() {
        val obj = buildGitEventPayload(GitEventInfo("state_change", "deadbeef"))
        assertEquals("deadbeef", obj["commit_sha"]!!.jsonPrimitive.content)
        assertEquals(setOf("operation", "commit_sha"), obj.keys)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.wiring.GitPayloadsTest'`
Expected: FAIL — `buildGitEventPayload` / `GitEventInfo` unresolved.

**Step 3: Write the implementation**

`recorder/src/main/kotlin/dev/provenance/recorder/wiring/GitPayloads.kt`:
```kotlin
package dev.provenance.recorder.wiring

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure payload builder for git.event. Mirrors packages/recorder/src/wiring/git-wiring.ts,
 * which always emits `operation: 'state_change'` regardless of the underlying git operation
 * (commit/checkout/branch switch/index change all look the same from the extension API) —
 * matched here exactly per this repo's "port the wiring, not a new product" mandate.
 * (log-core events.ts:188-191)
 */
data class GitEventInfo(val operation: String, val commitSha: String?)

fun buildGitEventPayload(info: GitEventInfo): JsonObject =
    buildJsonObject {
        put("operation", info.operation)
        if (info.commitSha != null) put("commit_sha", info.commitSha)
    }
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.wiring.GitPayloadsTest'`
Expected: PASS (2 tests).

**Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/wiring/GitPayloads.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/wiring/GitPayloadsTest.kt
git commit --no-gpg-sign -m "feat(recorder): git.event payload builder"
```

---

### Task 3: Plugin snapshot payload builder (pure, no platform deps)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/PluginSnapshotPayloads.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/wiring/PluginSnapshotPayloadsTest.kt`

**Interfaces:**
- Produces:
  - `data class PluginInfo(val id: String, val version: String, val enabled: Boolean)` — the JetBrains analogue of `vscode.Extension`; deliberately editor-agnostic naming isn't used because the *wire field* stays `extensions` per the Global Constraints note.
  - `fun buildExtSnapshotPayload(plugins: List<PluginInfo>): JsonObject`

**Step 1: Write the failing test**

`recorder/src/test/kotlin/dev/provenance/recorder/wiring/PluginSnapshotPayloadsTest.kt`:
```kotlin
package dev.provenance.recorder.wiring

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PluginSnapshotPayloadsTest {
    @Test
    fun `builds extensions array with id, version, enabled per plugin`() {
        val obj = buildExtSnapshotPayload(
            listOf(
                PluginInfo("Git4Idea", "251.1", true),
                PluginInfo("org.jetbrains.plugins.terminal", "251.1", false),
            ),
        )
        val extensions = obj["extensions"]!!.jsonArray
        assertEquals(2, extensions.size)
        val first = extensions[0].jsonObject
        assertEquals("Git4Idea", first["id"]!!.jsonPrimitive.content)
        assertEquals("251.1", first["version"]!!.jsonPrimitive.content)
        assertEquals(true, first["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(setOf("extensions"), obj.keys)
    }

    @Test
    fun `empty plugin list yields empty extensions array`() {
        val obj = buildExtSnapshotPayload(emptyList())
        assertEquals(0, obj["extensions"]!!.jsonArray.size)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.wiring.PluginSnapshotPayloadsTest'`
Expected: FAIL — `buildExtSnapshotPayload` / `PluginInfo` unresolved.

**Step 3: Write the implementation**

`recorder/src/main/kotlin/dev/provenance/recorder/wiring/PluginSnapshotPayloads.kt`:
```kotlin
package dev.provenance.recorder.wiring

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put

/**
 * Pure payload builder for ext.snapshot. Mirrors
 * packages/recorder/src/wiring/extension-snapshot.ts. The wire field is `extensions` even
 * though this recorder lists IntelliJ *plugins*, not VS Code extensions — same event shape,
 * different source, per design.md's note that this is the same log-core event, not a new one.
 * (log-core events.ts:128-130)
 */
data class PluginInfo(val id: String, val version: String, val enabled: Boolean)

fun buildExtSnapshotPayload(plugins: List<PluginInfo>): JsonObject =
    buildJsonObject {
        putJsonArray("extensions") {
            for (p in plugins) {
                addJsonObject {
                    put("id", p.id)
                    put("version", p.version)
                    put("enabled", p.enabled)
                }
            }
        }
    }
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.wiring.PluginSnapshotPayloadsTest'`
Expected: PASS (2 tests).

**Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/wiring/PluginSnapshotPayloads.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/wiring/PluginSnapshotPayloadsTest.kt
git commit --no-gpg-sign -m "feat(recorder): ext.snapshot payload builder for installed plugins"
```

---

### Task 4: Plugin snapshot wiring (always-on — no optional dependency needed)

`PluginManagerCore` is core-platform (`com.intellij.modules.platform`), always present — unlike terminal/git this wiring needs no optional-dependency gating.

**Files:**
- Modify: `recorder/build.gradle.kts` — confirm `com.intellij.util.Alarm` is available from the base platform artifact Plan 3 already depends on (it is — `Alarm` lives in `intellij.platform.util.ui`, part of the core platform module; no new dependency).
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/PluginSnapshotWiring.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/wiring/PluginSnapshotWiringTest.kt`

**Interfaces:**
- Consumes: `RecorderEventSink`, `PluginInfo`, `buildExtSnapshotPayload` (Tasks 1/3).
- Produces:
  - `class PluginSnapshotWiring(private val sink: RecorderEventSink, private val getPlugins: () -> List<PluginInfo>, private val intervalMs: Long = 5 * 60 * 1000L, parentDisposable: Disposable) : Disposable`
  - `fun start()` — emits immediately, then reschedules every `intervalMs` (PRD §4.2: "At session start and every 5 min").
  - `override fun dispose()` — cancels the alarm.
  - A companion factory `fun PluginSnapshotWiring.Companion.fromPlatform(sink: RecorderEventSink, parentDisposable: Disposable): PluginSnapshotWiring` that supplies `getPlugins = { PluginManagerCore.getPlugins().map { PluginInfo(it.pluginId.idString, it.version ?: "unknown", it.isEnabled) } }` — isolates the one line that touches `PluginManagerCore` so the class itself stays testable with an injected plugin list.

**Step 1: Write the failing test (using the injectable `getPlugins`, no real `PluginManagerCore` call)**

`recorder/src/test/kotlin/dev/provenance/recorder/wiring/PluginSnapshotWiringTest.kt`:
```kotlin
package dev.provenance.recorder.wiring

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PluginSnapshotWiringTest {
    @Test
    fun `emits immediately on start with the injected plugin list`() {
        val emitted = mutableListOf<Pair<String, Int>>()
        val parent = Disposer.newDisposable("test")
        try {
            val wiring = PluginSnapshotWiring(
                sink = RecorderEventSink { kind, data -> emitted.add(kind to data["extensions"]!!.jsonArray.size) },
                getPlugins = { listOf(PluginInfo("a.b.c", "1.0", true)) },
                intervalMs = 60_000L,
                parentDisposable = parent,
            )
            wiring.start()
            assertEquals(1, emitted.size)
            assertEquals("ext.snapshot" to 1, emitted[0])
        } finally {
            Disposer.dispose(parent)
        }
    }

    @Test
    fun `disposing cancels the periodic reschedule`() {
        val emitted = mutableListOf<String>()
        val parent = Disposer.newDisposable("test")
        val wiring = PluginSnapshotWiring(
            sink = RecorderEventSink { kind, _ -> emitted.add(kind) },
            getPlugins = { emptyList() },
            intervalMs = 1L, // would fire almost immediately if not cancelled
            parentDisposable = parent,
        )
        wiring.start()
        Disposer.dispose(parent) // must not throw, must stop future emissions
        val countAfterDispose = emitted.size
        Thread.sleep(50)
        assertEquals(countAfterDispose, emitted.size)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.wiring.PluginSnapshotWiringTest'`
Expected: FAIL — `PluginSnapshotWiring` unresolved. **VERIFY AT EXECUTION:** this test needs `com.intellij.openapi.Disposable`/`Disposer` on the test classpath, which requires the IntelliJ Platform Gradle Plugin's test dependency (Plan 3 should already provide `intellijPlatform { testFramework(TestFrameworkType.Platform) }` or similar — confirm against Plan 3's actual `recorder/build.gradle.kts` and adjust the dependency block if this doesn't resolve). `Disposer.newDisposable()`/`Disposer.dispose()` do not require a running IDE instance and work in plain JUnit once the platform-util jar is on the classpath.

**Step 3: Write the implementation**

`recorder/src/main/kotlin/dev/provenance/recorder/wiring/PluginSnapshotWiring.kt`:
```kotlin
package dev.provenance.recorder.wiring

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.util.Alarm

/**
 * Periodic ext.snapshot emitter. Mirrors
 * packages/recorder/src/wiring/extension-snapshot.ts: emit immediately, then every
 * [intervalMs] (default 5 min, PRD §4.2). Uses com.intellij.util.Alarm rather than a raw
 * Kotlin coroutine/timer because Alarm is the platform-idiomatic self-cancelling scheduler —
 * tying [dispose] to [parentDisposable] means plugin/project teardown stops the timer without
 * a manual leak-prone Job reference (CLAUDE.md: "every timer has a dispose() hook").
 */
class PluginSnapshotWiring(
    private val sink: RecorderEventSink,
    private val getPlugins: () -> List<PluginInfo>,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    parentDisposable: Disposable,
) : Disposable {
    companion object {
        const val DEFAULT_INTERVAL_MS: Long = 5 * 60 * 1000L

        fun fromPlatform(sink: RecorderEventSink, parentDisposable: Disposable): PluginSnapshotWiring =
            PluginSnapshotWiring(
                sink = sink,
                getPlugins = {
                    PluginManagerCore.getPlugins().map { descriptor ->
                        PluginInfo(
                            id = descriptor.pluginId.idString,
                            version = descriptor.version ?: "unknown",
                            enabled = descriptor.isEnabled,
                        )
                    }
                },
                parentDisposable = parentDisposable,
            )
    }

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parentDisposable)

    fun start() {
        snapshot()
        schedule()
    }

    private fun snapshot() {
        sink.emit("ext.snapshot", buildExtSnapshotPayload(getPlugins()))
    }

    private fun schedule() {
        if (alarm.isDisposed) return
        alarm.addRequest({ snapshot(); schedule() }, intervalMs)
    }

    override fun dispose() {
        alarm.cancelAllRequests()
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.wiring.PluginSnapshotWiringTest'`
Expected: PASS (2 tests).

**Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/wiring/PluginSnapshotWiring.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/wiring/PluginSnapshotWiringTest.kt
git commit --no-gpg-sign -m "feat(recorder): periodic ext.snapshot wiring via Alarm + PluginManagerCore"
```

---

### Task 5: Terminal wiring (optional dependency, config-file gated)

**Real API surface** (IntelliJ Platform, "Reworked Terminal" — see risk note below):
- Plugin id: `org.jetbrains.plugins.terminal` (`https://plugins.jetbrains.com/docs/intellij/embedded-terminal.html`).
- `TerminalTabsManagerListener` (`com.intellij.terminal.frontend.toolwindow`), subscribed via the project message bus topic `TerminalTabsManagerListener.TOPIC` (`@Topic.ProjectLevel`): `terminalViewCreated(view: TerminalView)`, `tabAdded(tab: TerminalToolWindowTab)`, `tabDetached(tab: TerminalToolWindowTab)`.
- `TerminalView.shellIntegrationDeferred: Deferred<TerminalShellIntegration>` (`com.intellij.terminal.frontend.view`) — resolves once shell integration finishes initializing; **never resolves** for unsupported shells (only Bash/Zsh/PowerShell are supported, and even those aren't guaranteed — `https://plugins.jetbrains.com/docs/intellij/embedded-terminal.html`).
- `TerminalShellIntegration.addCommandExecutionListener(parentDisposable, listener: TerminalCommandExecutionListener)` (`org.jetbrains.plugins.terminal.view.shellIntegration`) with `commandStarted(event)` / `commandFinished(event)`, each carrying a `TerminalCommandBlock` with `executedCommand: String?` and `exitCode: Int?`.

**RISK — flag prominently, VERIFY AT EXECUTION:** every type above is annotated `@ApiStatus.Experimental` in the platform source as of this research (2026-07-14). This is the "Reworked Terminal" rewrite (frontend/backend split); it is a genuinely current, documented, real API — not invented — but JetBrains reserves the right to break it across IDE versions without the usual deprecation cycle. Before implementing this task: (a) confirm the plugin's `since-build`/target platform version (set by Plan 3) actually ships the Reworked Terminal as default, (b) budget for this API surface changing under a platform version bump, (c) if it has changed, the fallback is the legacy `TerminalView`/`ShellTerminalWidget` singleton API referenced in older JetBrains docs — do not silently invent a shape; stop and re-research against the target platform version.

**Files:**
- Modify: `recorder/src/main/resources/META-INF/plugin.xml` — add the optional `<depends>`.
- Create: `recorder/src/main/resources/META-INF/provjet-terminal.xml` — the optional descriptor.
- Modify: `recorder/build.gradle.kts` — add `intellijPlatform { bundledPlugins("org.jetbrains.plugins.terminal") }` (compile-time visibility; this is separate from the runtime-optional `<depends>` above — https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html).
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/terminal/TerminalWiringStartupActivity.kt`
- Test: manual/VERIFY AT EXECUTION (see Step 5) — this class directly references terminal-plugin types, so it can only run inside an IntelliJ Platform test fixture that has the terminal plugin loaded (`intellijPlatform { testFramework(...) }` + the bundled plugin above made available to the test sandbox, per Plan 3's test setup — not yet established in this repo).

**Step 1: Declare the optional dependency in `plugin.xml`**

Add inside the `<idea-plugin>` root of `recorder/src/main/resources/META-INF/plugin.xml`:
```xml
<depends optional="true" config-file="provjet-terminal.xml">org.jetbrains.plugins.terminal</depends>
```

**Step 2: Create the optional descriptor**

`recorder/src/main/resources/META-INF/provjet-terminal.xml`:
```xml
<idea-plugin>
    <extensions defaultExtensionNs="com.intellij">
        <postStartupActivity implementation="dev.provenance.recorder.wiring.terminal.TerminalWiringStartupActivity"/>
    </extensions>
</idea-plugin>
```
**VERIFY AT EXECUTION:** confirm `postStartupActivity` (backed by `com.intellij.openapi.startup.ProjectActivity`, `suspend fun execute(project: Project)`) is the correct, non-deprecated extension point for Plan 3's chosen minimum platform build — this replaced the older `StartupActivity.DumbAware` registration around the 2022.3–2023.1 platform line; if Plan 3 targets something older, use the older EP instead and note the divergence.

**Step 3: Write the wiring class**

`recorder/src/main/kotlin/dev/provenance/recorder/wiring/terminal/TerminalWiringStartupActivity.kt`:
```kotlin
package dev.provenance.recorder.wiring.terminal

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.terminal.frontend.toolwindow.TerminalTabsManagerListener
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.view.TerminalView
import dev.provenance.recorder.wiring.ActiveRecorderSession
import dev.provenance.recorder.wiring.TerminalCommandInfo
import dev.provenance.recorder.wiring.TerminalOpenInfo
import dev.provenance.recorder.wiring.buildTerminalCommandPayload
import dev.provenance.recorder.wiring.buildTerminalOpenPayload
import kotlinx.coroutines.*
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandExecutionListener
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandFinishedEvent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * Registered ONLY via provjet-terminal.xml's optional <depends> on
 * org.jetbrains.plugins.terminal — this class references terminal-plugin types directly, so it
 * must never be reachable from the main plugin.xml's always-loaded extensions (see Global
 * Constraints: "Optional-plugin isolation is structural").
 *
 * Mirrors packages/recorder/src/wiring/terminal-wiring.ts: assign a stable counter-based
 * terminal_id per terminal, emit terminal.open once shell-integration status is known (best
 * effort — many shells never resolve it, matching VS Code's "record the gap, don't fail" PRD
 * §4.4 rule), then emit terminal.command from TerminalCommandExecutionListener when available.
 */
class TerminalWiringStartupActivity : ProjectActivity {
    companion object {
        /** How long to wait for shell-integration to initialize before emitting shell_integration=false. */
        const val SHELL_INTEGRATION_TIMEOUT_MS = 3_000L
    }

    override suspend fun execute(project: Project) {
        val counter = AtomicInteger(0)
        val terminalIds = ConcurrentHashMap<TerminalView, String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        project.messageBus.connect(project).subscribe(
            TerminalTabsManagerListener.TOPIC,
            object : TerminalTabsManagerListener {
                override fun terminalViewCreated(view: TerminalView) {
                    val sink = ActiveRecorderSession.sink ?: return // not recording — no active session
                    val terminalId = "term-${counter.getAndIncrement()}"
                    terminalIds[view] = terminalId

                    scope.launch {
                        val shellIntegration = withTimeoutOrNull(SHELL_INTEGRATION_TIMEOUT_MS) {
                            view.shellIntegrationDeferred.await()
                        }
                        sink.emit(
                            "terminal.open",
                            buildTerminalOpenPayload(
                                TerminalOpenInfo(
                                    terminalId = terminalId,
                                    shell = shellNameOf(view),
                                    shellIntegration = shellIntegration != null,
                                ),
                            ),
                        )
                        shellIntegration?.addCommandExecutionListener(
                            project,
                            object : TerminalCommandExecutionListener {
                                override fun commandFinished(event: TerminalCommandFinishedEvent) {
                                    val block = event.commandBlock
                                    val activeSink = ActiveRecorderSession.sink ?: return
                                    activeSink.emit(
                                        "terminal.command",
                                        buildTerminalCommandPayload(
                                            TerminalCommandInfo(
                                                terminalId = terminalId,
                                                command = block.executedCommand ?: "",
                                                exitCode = block.exitCode,
                                            ),
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }

                override fun tabDetached(tab: TerminalToolWindowTab) {
                    // Best-effort cleanup; VS Code's onDidCloseTerminal equivalent. TerminalToolWindowTab
                    // does not directly expose the originating TerminalView per the API surface fetched
                    // for this plan (2026-07-14) — VERIFY AT EXECUTION whether a reverse lookup is needed,
                    // or whether terminalIds should simply be allowed to grow for the session's lifetime
                    // (bounded by terminal count in a single IDE session, unlike long-lived doc maps).
                }
            },
        )
    }

    private fun shellNameOf(view: TerminalView): String {
        // TerminalView does not expose a synchronous shell path; only startupOptionsDeferred is
        // available (per plugins/terminal/frontend/src/.../TerminalView.kt fetched for this plan).
        // VERIFY AT EXECUTION: read the resolved shell command from
        // view.startupOptionsDeferred (a Deferred<TerminalStartupOptions>) once its exact shape is
        // confirmed against the target platform version; fall back to "unknown" (mirrors VS Code's
        // `creationOptions?.shellPath ?? 'unknown'`) if unavailable within a short timeout.
        return "unknown"
    }
}
```

**Step 4: Wire the compile-time dependency**

In `recorder/build.gradle.kts`, inside the existing `dependencies { intellijPlatform { ... } }` block (created by Plan 3):
```kotlin
dependencies {
    intellijPlatform {
        bundledPlugins("org.jetbrains.plugins.terminal")
    }
}
```

**Step 5: VERIFY AT EXECUTION — platform test**

This class cannot be exercised by plain JUnit (it needs a live `Project`, message bus, and the terminal plugin loaded). Write an integration test using the IntelliJ Platform Gradle Plugin's test fixtures once Plan 3 establishes that harness:
- Test intent: open a terminal tab in a `BasePlatformTestCase`/light-fixture project with the terminal plugin available, assert a `terminal.open` event is emitted with `shell_integration: false` when using a fixture shell that doesn't support integration (the common case in CI), and that no exception propagates.
- Test intent (degradation, covered structurally by Task 5 itself, not a unit test): with the terminal plugin *absent* from the test sandbox, the plugin loads without `NoClassDefFoundError` and no `terminal.*` events are ever emitted. Exercise this by running the packaged plugin in a minimal IDE profile that doesn't bundle the terminal plugin — flag as manual QA if the Gradle test harness can't easily construct a terminal-plugin-free sandbox.

**Step 6: Commit**

```bash
git add recorder/src/main/resources/META-INF/plugin.xml \
  recorder/src/main/resources/META-INF/provjet-terminal.xml \
  recorder/build.gradle.kts \
  recorder/src/main/kotlin/dev/provenance/recorder/wiring/terminal/TerminalWiringStartupActivity.kt
git commit --no-gpg-sign -m "feat(recorder): terminal.open/terminal.command wiring (optional org.jetbrains.plugins.terminal dep)"
```

---

### Task 6: Git wiring (optional dependency, config-file gated, graceful degradation)

**Real API surface:**
- Plugin id: `Git4Idea` (confirmed from `plugins/git4idea/resources/META-INF/plugin.xml`'s `<id>Git4Idea</id>`, `https://github.com/JetBrains/intellij-community/blob/master/plugins/git4idea/resources/META-INF/plugin.xml`).
- `GitRepositoryManager` (`git4idea.repo`, `@Service(Service.Level.PROJECT)`): `GitRepositoryManager.getInstance(project): GitRepositoryManager`, `.repositories: List<GitRepository>`.
- `GitRepository.GIT_REPO_CHANGE: Topic<GitRepositoryChangeListener>` — a **project-level** message bus topic (published via `syncPublisher(repository.project, GitRepository.GIT_REPO_CHANGE)` in `GitRepositoryManager`'s own source) that fires for changes to *any* repository in the project — unlike VS Code's per-repository `onDidChange`, there is no need to separately track `onDidOpenRepository`; one subscription on the project message bus covers every repo, present and future.
- `GitRepositoryChangeListener.repositoryChanged(repository: GitRepository)`.
- `Repository.getCurrentRevision(): String?` (on the `com.intellij.dvcs.repo.Repository` base interface `GitRepository` extends) — the commit sha source for `commit_sha`.

This is **simpler than the VS Code port** for repository-open tracking (one topic vs. `onDidOpenRepository`/`onDidCloseRepository` + a `Map` of watched repos) — note this simplification explicitly in the code comment so a future reader doesn't wonder why the open/close tracking from `git-wiring.ts` was dropped.

**Files:**
- Modify: `recorder/src/main/resources/META-INF/plugin.xml` — add the optional `<depends>`.
- Create: `recorder/src/main/resources/META-INF/provjet-git.xml` — the optional descriptor.
- Modify: `recorder/build.gradle.kts` — add `bundledPlugins("Git4Idea")`.
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/git/GitWiringStartupActivity.kt`
- Test: VERIFY AT EXECUTION (same platform-test constraint as Task 5).

**Step 1: Declare the optional dependency**

Add inside `recorder/src/main/resources/META-INF/plugin.xml`:
```xml
<depends optional="true" config-file="provjet-git.xml">Git4Idea</depends>
```

**Step 2: Create the optional descriptor**

`recorder/src/main/resources/META-INF/provjet-git.xml`:
```xml
<idea-plugin>
    <extensions defaultExtensionNs="com.intellij">
        <postStartupActivity implementation="dev.provenance.recorder.wiring.git.GitWiringStartupActivity"/>
    </extensions>
</idea-plugin>
```

**Step 3: Write the wiring class**

`recorder/src/main/kotlin/dev/provenance/recorder/wiring/git/GitWiringStartupActivity.kt`:
```kotlin
package dev.provenance.recorder.wiring.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.provenance.recorder.wiring.ActiveRecorderSession
import dev.provenance.recorder.wiring.GitEventInfo
import dev.provenance.recorder.wiring.buildGitEventPayload
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

/**
 * Registered ONLY via provjet-git.xml's optional <depends> on Git4Idea — this class references
 * Git4Idea types directly, so it must never be reachable from the main plugin.xml's
 * always-loaded extensions (see Global Constraints: "Optional-plugin isolation is structural").
 * If Git4Idea is absent, provjet-git.xml is never loaded, this class is never classloaded, and
 * git.event is simply never emitted for that session — the graceful-degradation requirement
 * from design.md §4 ("git ... must degrade gracefully if Git4Idea absent") is satisfied
 * structurally, not by a runtime check.
 *
 * Mirrors packages/recorder/src/wiring/git-wiring.ts's single behavior: on any repository
 * state change, emit git.event with operation: "state_change" and the current HEAD commit sha
 * if available. Simpler than the VS Code port: GIT_REPO_CHANGE is a project-wide topic, so
 * there's no separate onDidOpenRepository/onDidCloseRepository tracking needed — one
 * subscription covers every repository in the project, including ones opened after this
 * activity runs.
 */
class GitWiringStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.messageBus.connect(project).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { repository ->
                val sink = ActiveRecorderSession.sink ?: return@GitRepositoryChangeListener
                sink.emit(
                    "git.event",
                    buildGitEventPayload(GitEventInfo(operation = "state_change", commitSha = repository.currentRevision)),
                )
                // ASSUMED SEAM (pending Plan 5): VS Code's git-wiring.ts calls
                // explanationTagger?.markGit() here to suppress fs.external_change false
                // positives from git checkout/reset rewriting files (PRD §4.5, "False
                // positives to handle: ... Git operations"). Plan 5 (external-change
                // detection) owns that tagger and does not exist yet in this repo. When it
                // lands, call its markGit()-equivalent from this exact point — do not defer
                // fixing the false-positive suppression to a later plan; it belongs here.
            },
        )
    }
}
```

**Step 4: Wire the compile-time dependency**

In `recorder/build.gradle.kts`:
```kotlin
dependencies {
    intellijPlatform {
        bundledPlugins("org.jetbrains.plugins.terminal", "Git4Idea")
    }
}
```
(Combine with Task 5's line rather than duplicating the block.)

**Step 5: VERIFY AT EXECUTION — platform test + degradation**

- Test intent (happy path): in a platform test fixture with Git4Idea available and a real/mocked `GitRepository`, trigger a repository change and assert `git.event` fires with the expected `commit_sha`.
- Test intent (degradation — the one this task exists to prove): package the plugin and run/verify it inside an IDE build that does **not** bundle Git4Idea (or with Git4Idea disabled via the IDE's plugin manager). Confirm: plugin loads, no `NoClassDefFoundError`, no `git.event` ever appears in the session log, and no other functionality (doc/terminal/snapshot wiring) is affected. **This is the single most important verification in this plan** — design.md and CLAUDE.md both call out git-absence as the specific "must not crash" risk. If it crashes, the bug is almost certainly a stray reference to a `Git4Idea` type from a class reachable outside `provjet-git.xml` — audit for that first.

**Step 6: Commit**

```bash
git add recorder/src/main/resources/META-INF/plugin.xml \
  recorder/src/main/resources/META-INF/provjet-git.xml \
  recorder/build.gradle.kts \
  recorder/src/main/kotlin/dev/provenance/recorder/wiring/git/GitWiringStartupActivity.kt
git commit --no-gpg-sign -m "feat(recorder): git.event wiring (optional Git4Idea dep, graceful degradation)"
```

---

### Task 7: Assemble + cross-cutting degradation checklist

**Files:**
- Modify: `recorder/src/main/resources/META-INF/plugin.xml` — confirm both `<depends optional="true" ...>` lines from Tasks 5/6 are present alongside Plan 3's required `<depends>com.intellij.modules.platform</depends>`.
- No new production code — this task is a verification gate, mirroring Plan 1's Task 7 "conformance gate" pattern.

- [ ] **Step 1:** Run `./gradlew :recorder:test` — every pure-builder test (Tasks 1–3) and the `PluginSnapshotWiringTest` (Task 4) green. Expected: PASS, no platform-fixture tests required to pass at this step since Tasks 5/6's tests are VERIFY AT EXECUTION.
- [ ] **Step 2 (VERIFY AT EXECUTION):** Build the plugin distribution (`./gradlew :recorder:buildPlugin`) and sideload it into two IDE configurations: (a) a full IDE with Terminal + Git4Idea bundled (e.g. IntelliJ IDEA Ultimate/Community default install), (b) an IDE/profile without one or both — a lightweight IDE or one with those plugins manually disabled. Confirm (a) emits `terminal.open`, `terminal.command` (where shell integration supports it), `git.event`, and periodic `ext.snapshot`; confirm (b) loads cleanly with no crash and simply omits the corresponding event kinds.
- [ ] **Step 3 (VERIFY AT EXECUTION):** Confirm a bundle sealed with git/terminal both absent still passes the *real* Provenance analyzer's validation (design.md §3's conformance requirement) — absence of `terminal.*`/`git.event` lines must not fail hash-chain or manifest validation, since those event kinds are optional occurrences, not required ones, in the format.
- [ ] **Step 4: Commit** (only if Step 1 required fixes; otherwise this task produces no diff beyond what Tasks 1–6 already committed).

```bash
git add recorder/src/main/resources/META-INF/plugin.xml
git commit --no-gpg-sign -m "chore(recorder): confirm optional terminal/git dependency wiring assembled"
```

---

## Self-Review

**Spec coverage (task brief's SCOPE: "terminal activity events (optional-dep), git events (optional-dep, graceful degradation), and the periodic plugin snapshot"):** terminal.open/terminal.command payload + wiring (Tasks 1, 5), git.event payload + wiring with structural graceful degradation (Tasks 2, 6), ext.snapshot payload + always-on periodic wiring (Tasks 3, 4), assembly + degradation verification gate (Task 7).

**Real APIs cited, not invented** (all fetched from JetBrains' own docs or the `intellij-community` source during this planning session, 2026-07-14):
- Optional dependency mechanism: `<depends optional="true" config-file="...">` — `https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html`.
- Terminal: `org.jetbrains.plugins.terminal` plugin id, `TerminalTabsManagerListener`/`TerminalView`/`TerminalShellIntegration`/`TerminalCommandExecutionListener`/`TerminalCommandBlock` — `https://plugins.jetbrains.com/docs/intellij/embedded-terminal.html` + `plugins/terminal/**` in `JetBrains/intellij-community`.
- Git: `Git4Idea` plugin id, `GitRepositoryManager`, `GitRepository.GIT_REPO_CHANGE` topic, `GitRepositoryChangeListener`, `Repository.getCurrentRevision()` — `plugins/git4idea/**` and `platform/dvcs-api/**` in `JetBrains/intellij-community`.
- Plugin snapshot: `PluginManagerCore.getPlugins()` / `IdeaPluginDescriptor.{pluginId, version, isEnabled}` — JetBrains API docs mirror + platform source.
- Periodic scheduling: `com.intellij.util.Alarm` with `Alarm.ThreadToUse.POOLED_THREAD` tied to a `Disposable`.
- IntelliJ Platform Gradle Plugin 2.x `bundledPlugins(...)` syntax for compile-time visibility of optional-plugin types — `https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html`.

**Fidelity note on optional-plugin availability across IDEs — the central risk of this plan:** the design is built around the platform's documented, structural gating mechanism (optional `<depends>` + separate config-file), not a runtime `isPluginInstalled()` guard, because a runtime guard does not prevent `NoClassDefFoundError` when the guarding code itself lives in a class that statically references the optional plugin's types — the class fails to *load* before the guard ever runs. Every class in Tasks 5/6 that touches `Git4Idea`/terminal types is therefore isolated inside a file reachable only from `provjet-terminal.xml`/`provjet-git.xml`. This is high-confidence for the *mechanism* (directly from JetBrains' own docs and confirmed against the real `Git4Idea`/terminal `plugin.xml` source), but **medium confidence for the exact terminal API surface**, which is marked `@ApiStatus.Experimental` platform-wide as of this research — flagged explicitly in Task 5 as something to re-verify against the target platform version before implementation, with the legacy `TerminalView`/`ShellTerminalWidget` singleton API as the documented fallback if the Reworked Terminal API has moved. The git API surface is comparatively low-risk: `GitRepositoryManager`/`GitRepository`/`Repository` are long-stable, non-experimental platform types.

**Placeholder scan:** two deliberate, clearly-labeled placeholders — `RecorderEventSink`/`ActiveRecorderSession` (pending Plan 4's real session/emission service) and the `onGitActivity`/explanation-tagger integration point in `GitWiringStartupActivity` (pending Plan 5's external-change false-positive suppression). Both are called out at point of use and in "Prerequisites and assumed seams" above, not buried. `shellNameOf()` in Task 5 is a known-incomplete stub (`"unknown"` fallback) pending confirmation of `TerminalStartupOptions`'s exact shape — flagged inline as VERIFY AT EXECUTION rather than guessed at.

**Type consistency:** `RecorderEventSink`, `TerminalOpenInfo`/`TerminalCommandInfo`/`GitEventInfo`/`PluginInfo`, and the four `build*Payload` functions are defined once (Tasks 1–3) and reused unchanged by every wiring class (Tasks 4–6).

**Open dependency approvals (per this repo's CLAUDE.md "No new dependencies without asking"):** no new Gradle dependencies beyond what Plan 1 already added and the IntelliJ Platform Gradle Plugin's `bundledPlugins(...)` declarations for two plugins that ship with the platform SDK Plan 3 already depends on — these aren't new artifacts, just compile-time visibility toggles for code already targeting `com.intellij.modules.platform`. Confirm this reading is correct before Task 5.
