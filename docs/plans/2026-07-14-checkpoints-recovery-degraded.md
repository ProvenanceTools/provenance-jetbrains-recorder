# Checkpoints Wiring, Chain Recovery & Disk-Full Degraded Mode Implementation Plan (Plan 8 of the provjet series)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the three failure/integrity behaviors from the VS Code recorder that make a session's `.slog`/`.slog.meta` pair trustworthy end-to-end: periodic signed checkpoints (PRD §4.6), startup chain recovery from a corrupt/truncated prior log (PRD §4.6, §4.8), and disk-full degraded mode (PRD §4.8). Each is a 1:1 port of an existing, already-shipped VS Code implementation — this plan is logic-and-wiring, not design.

**Architecture:** Split into pure Kotlin logic (fully unit-testable with injected seams, zero or minimal IntelliJ imports, living in `recorder/` alongside the VS Code equivalents' package layout) plus thin wiring tasks that connect that logic to real IntelliJ Platform I/O and UI surfaces. Checkpoint signing reuses `core`'s `signCheckpoint`/`verifyCheckpoint` (Plan 2 Task 5); recovery reuses `core`'s `parseEntries`/`validateChain` (Plan 1 Tasks 5–6). Async ordering (checkpoint sign+persist off the synchronous append path, but never out of order, and drainable at session end) uses a constructor-injected `CoroutineScope` — the platform's stated current best practice for anything targeting 2024.1+, and `kotlinx-coroutines-core` is bundled by the IntelliJ Platform Gradle Plugin, so this needs no new Gradle dependency.

**Tech Stack:** Kotlin/JVM, `core/`'s existing primitives, `kotlinx-coroutines-core` (platform-bundled), IntelliJ Platform `com.intellij.notification.*` for user-facing disclosure, JUnit 5 (+ IntelliJ test fixtures only where a task genuinely touches platform UI). No new Gradle dependencies.

## Plan series (context)

- Plan 1: `core/` — hashing, JCS, chain, NDJSON, chain-validator, conformance gate. **Done.**
- Plan 2: `core/` — bundle manifest, session keypair, signed checkpoints. **Done.**
- Plan 3: plugin scaffold + activation + manifest verification + status-bar widget + sideload build. **Not yet written.**
- Plan 4: `doc.open/change/save/close` wiring + atomic session writer + bundle seal. **Not yet written.**
- Plan 5: external-change detection (VFS). **Not yet written.**
- Plan 6: three-signal paste detection. **Not yet written.**
- Plan 7: terminal + git wiring + plugin snapshot. **Not yet written.**
- **Plan 8 (this):** checkpoints wiring + chain recovery + disk-full degraded mode.
- Plan 9: `build:prod` course-key embedding + `extension_hash` + Marketplace packaging + monorepo allowlist/export changes.

**Sequencing note:** this plan is written *ahead* of Plans 3–7 because its logic is independently specifiable and testable against `core/` alone. Every task that touches Plan 3's activation code or Plan 4's `SessionWriter`/`MetaWriter`/`SessionHost` is marked **ASSUMED INTERFACE** with the exact shape this plan needs — reconcile against the real Plan 3/4 code when this plan executes, and treat a mismatch as a signature to fix here, not a reason to guess.

## Global Constraints

(Inherits Plan 1 and Plan 2's Global Constraints.) Additional, specific to this plan:

- **Checkpoint cadence is entry-count-based, not wall-clock-periodic.** Verified against the real source (`packages/recorder/src/extension.ts:250-282`): `CHECKPOINT_INTERVAL = 100` — a checkpoint is signed and persisted every 100 appended entries, counted inside the `onEntry` callback, not on a timer. There is no periodic/`Alarm`-driven trigger for checkpoint cadence anywhere in the ported behavior — do not add one. (See Fidelity note in Self-Review — the task brief for this plan asked for research into periodic-scheduling APIs on the assumption checkpoints might be wall-clock periodic; source-checking shows they are not. The scheduling APIs researched below are used instead for a real, adjacent need: ordering the *async* sign+persist work.)
- **Checkpoint signed payload** (already implemented in `core/`, Plan 2 Task 5): `signCheckpoint(seq, entryHash, privateKey32): Checkpoint` signs `JCS({hash, seq})`. This plan calls it; it does not reimplement it.
- **Quarantine naming:** `<slogPath>.corrupt-<ISO-with-colons-and-dots-replaced-by-dashes>`, exactly mirroring `chain-recovery.ts`'s `now().toISOString().replace(/[:.]/g, '-')`.
- **`prev_session_id` is set only for a dangling prior session** (no trailing `session.end`) — never for a cleanly completed one, and never for a corrupt one (corruption is surfaced via `recorder.recovered_from_corruption`, not chain linkage). This is a deliberate product decision already made and documented in the TS source; do not change it.
- **`chain.broken` is not emitted by recovery.** It stays reserved in the event-kind space for a live session detecting its own chain breaking mid-stream (not implemented by any current plan) — recovery only ever emits `recorder.recovered_from_corruption`.
- **Disk-full is a one-way transition.** Once degraded, there is no auto-recovery loop; the user is told to free space and restart. Do not add a retry/probe timer — it doesn't exist in the ported behavior.
- **CRITICAL_KINDS** (retained in the degraded-mode ring buffer; all other kinds are dropped): `session.start`, `session.end`, `fs.external_change`, `chain.broken`, `recorder.degraded`, `recorder.recovered_from_corruption`. Ring capacity default 256, FIFO eviction.
- **The `.slog`/`.slog.meta` write path is plain blocking I/O, not the IntelliJ VFS.** The VS Code recorder writes its own log files via `node:fs/promises` directly, never `vscode.workspace.fs` — the VFS is reserved for watching the *student's* project files (Plan 5's `BulkFileListener` territory). The natural Kotlin analogue for Plan 4's writer is `java.nio.file.Files` / `FileChannel` on a background thread, and disk-full surfaces as a plain `java.io.IOException` (`FileSystemException: No space left on device`), not a VFS-specific error type. **ASSUMED INTERFACE (Plan 4):** this plan's `DiskFullHandler` only needs `Throwable` from an injected `onError` callback — it stays IntelliJ- and I/O-mechanism-agnostic either way.
- **`kotlinx-coroutines-core` is bundled by the IntelliJ Platform Gradle Plugin** — plugins should use the platform-provided version and must not package their own (`plugins.jetbrains.com/docs/intellij/using-kotlin.html`). No new Gradle dependency for `recorder/build.gradle.kts` main sourceSet. **`kotlinx-coroutines-test` (needed for `runTest`/`TestScope`) is a separate artifact and is *not* confirmed bundled** — this plan's tests avoid it (see Task 2) and use `runBlocking` + `Dispatchers.Unconfined`, both part of the bundled core artifact, instead. If a future plan wants `kotlinx-coroutines-test`, that is a new-dependency decision requiring approval per CLAUDE.md.
- **Notifications API** (`plugins.jetbrains.com/docs/intellij/notification-balloons.html`, `plugins.jetbrains.com/docs/intellij/informing-users.html`): register a group via the `com.intellij.notificationGroup` extension point in `plugin.xml` (`<notificationGroup id="..." displayType="BALLOON"/>`); construct with `Notification(groupId, content, NotificationType)` and show with `.notify(project)`. `NotificationGroupManager.getNotificationGroup(id)` is documented as no longer necessary — the group is resolved from the id string directly. `NotificationType` values: `INFORMATION`, `WARNING`, `ERROR`.
- **Coroutine scope with a shutdown hook** (`plugins.jetbrains.com/docs/intellij/plugin-services.html`): a light `@Service` with a `CoroutineScope` constructor parameter (`@Service class Foo(private val cscope: CoroutineScope)`, or `@Service(Service.Level.PROJECT) class Foo(project: Project, scope: CoroutineScope)`) receives a scope pre-configured with `Dispatchers.Default` that is automatically cancelled when the application/project closes or the plugin unloads — the platform's stated current best practice for 2024.1+ targets, cited as preferred over manually driving an `Alarm` for this kind of ordered background chain. **Legacy/alternative:** `com.intellij.util.Alarm` (`Alarm(Alarm.ThreadToUse.POOLED_THREAD, parentDisposable)`, `addRequest(runnable, delayMs)`, disposed automatically via its parent `Disposable`) is well-suited to debounce/rate-limit-style "run after a delay, cancel if superseded" work, but is a poor fit for an *ordered queue* of heterogeneous sign+persist calls that must never interleave or drop — hence the coroutine-scope choice here. **VERIFY AT EXECUTION:** confirm Plan 3's chosen target-platform baseline is ≥ 2024.1 before relying on constructor-scope injection; if it targets an older baseline, fall back to a manually created `CoroutineScope(SupervisorJob() + Dispatchers.IO)` cancelled explicitly from a `Disposable.dispose()` (same shutdown-hook guarantee, one line more code).
- **Disposer/Disposable** (`plugins.jetbrains.com/docs/intellij/disposers.html`): `Disposer.register(parentDisposable, childDisposable)`; never use `Application`/`Project` directly as a parent for plugin-owned resources (bypasses unload cleanup). Any listener/scope this plan wires must be registered under a plugin-owned parent `Disposable`, not the application or project directly.

---

### Task 1: Checkpoint cadence (pure counter)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/session/CheckpointCadence.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/session/CheckpointCadenceTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `class CheckpointCadence(interval: Int = CheckpointCadence.DEFAULT_INTERVAL) { fun onEntryAppended(): Boolean }` — call once per appended entry; returns `true` exactly when a checkpoint is due, and resets the internal counter. `DEFAULT_INTERVAL = 100`, matching `extension.ts`'s `CHECKPOINT_INTERVAL`.

```kotlin
package dev.provenance.recorder.session

/**
 * Counts appended entries and reports when a checkpoint is due (PRD §4.6: "signed every
 * N events"). Ported from the counter in provenance/packages/recorder/src/extension.ts
 * (`entryCountSinceLastCheckpoint >= CHECKPOINT_INTERVAL`). Pure, stateful, no I/O — this
 * is only the trigger; CheckpointScheduler (Task 2) does the signing and persisting.
 */
class CheckpointCadence(private val interval: Int = DEFAULT_INTERVAL) {
    init {
        require(interval > 0) { "interval must be positive" }
    }

    private var sinceLast = 0

    /** Call once per appended entry. Returns true (and resets the counter) when due. */
    fun onEntryAppended(): Boolean {
        sinceLast++
        if (sinceLast >= interval) {
            sinceLast = 0
            return true
        }
        return false
    }

    companion object {
        const val DEFAULT_INTERVAL = 100
    }
}
```

**Test intent:**
- With the default interval, calling `onEntryAppended()` 99 times returns `false` every time; the 100th call returns `true`.
- After a `true`, the counter resets: the *next* 99 calls return `false` again (verifies reset, not a one-shot latch).
- A custom `interval = 3` fires on the 3rd, 6th, 9th call (verifies the parameter is honored, independent of the pinned default).
- `interval = 0` throws `IllegalArgumentException` at construction (guards a silent infinite-checkpoint bug).

**Steps:** TDD — write the test file above's assertions → run `./gradlew :recorder:test --tests 'dev.provenance.recorder.session.CheckpointCadenceTest'` → FAIL (`CheckpointCadence` unresolved) → implement → PASS → commit:
```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/session/CheckpointCadence.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/session/CheckpointCadenceTest.kt
git commit --no-gpg-sign -m "feat(recorder): checkpoint cadence counter (every-N-entries trigger)"
```

---

### Task 2: Checkpoint scheduler (ordered, drainable async sign+persist)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/session/CheckpointScheduler.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/session/CheckpointSchedulerTest.kt`

**Interfaces:**
- Consumes: `dev.provenance.core.Checkpoint`, `dev.provenance.core.signCheckpoint` (Plan 2 Task 5).
- Produces:
  - `class CheckpointScheduler(scope: CoroutineScope, privateKey32: ByteArray, appendCheckpoint: suspend (Checkpoint) -> Unit, onError: (Throwable) -> Unit)`.
  - `fun schedule(seq: Long, entryHash: String)` — non-blocking; signs and persists off the caller's thread, but serialized so a later `schedule()` call's work never starts before an earlier one finishes (mirrors `extension.ts`'s `pendingCheckpoint = pendingCheckpoint.then(...)` chain).
  - `suspend fun drain()` — awaits the most recently scheduled sign+persist. Call this at session end, before the meta file/writer are closed, exactly as `extension.ts` awaits `pendingCheckpoint` in `deactivate()`.

```kotlin
package dev.provenance.recorder.session

import dev.provenance.core.Checkpoint
import dev.provenance.core.signCheckpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Signs and persists checkpoints off the synchronous append path, without ever letting two
 * sign+persist calls run out of order or interleaved. Ported from the `pendingCheckpoint`
 * promise-chain pattern in provenance/packages/recorder/src/extension.ts:253-281.
 *
 * CLAUDE.md: "No Promise.all over operations that must be ordered" / "every async loop has
 * a dispose() hook" — the Mutex here is the ordering guarantee (equivalent to TS's promise
 * chaining regardless of the injected scope's dispatcher parallelism); drain() is the
 * shutdown hook, awaited by the caller at session end.
 *
 * `privateKey32` is the session's raw in-memory private key (never persisted in this form —
 * only the encrypted form goes to disk, via Plan 2's encryptSessionPrivkey + Plan 4's
 * MetaWriter.create). signCheckpoint is synchronous CPU-bound ed25519 signing; the scope is
 * what keeps it off whatever thread calls schedule().
 */
class CheckpointScheduler(
    private val scope: CoroutineScope,
    private val privateKey32: ByteArray,
    private val appendCheckpoint: suspend (Checkpoint) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val mutex = Mutex()
    private var lastJob: Job? = null

    /** Enqueue a sign+persist for (seq, entryHash). Returns immediately. */
    fun schedule(seq: Long, entryHash: String) {
        lastJob = scope.launch {
            mutex.withLock {
                try {
                    val cp = signCheckpoint(seq, entryHash, privateKey32)
                    appendCheckpoint(cp)
                } catch (e: Throwable) {
                    onError(e)
                }
            }
        }
    }

    /** Await the most recently scheduled sign+persist. Call at session end. */
    suspend fun drain() {
        lastJob?.join()
    }
}
```

**Test intent:**
- Use `kotlinx.coroutines.test.runBlocking` (part of bundled `kotlinx-coroutines-core`, **not** `runTest` — see Global Constraints on avoiding `kotlinx-coroutines-test`) with `Dispatchers.Unconfined` for deterministic, immediate execution in tests.
- `schedule(seq, hash)` then `drain()` results in exactly one call to the injected `appendCheckpoint` with a `Checkpoint` whose `seq`/`hash` match and whose `sig` verifies against the corresponding public key (round-trip through `core`'s `verifyCheckpoint` — cheap, and confirms the scheduler didn't mangle the inputs).
- Two rapid `schedule()` calls followed by one `drain()`: `appendCheckpoint` is called exactly twice, **in seq order** (assert via a captured list, not just count — this is the ordering property the Mutex exists for).
- If `appendCheckpoint` throws, `onError` receives it and a *subsequent* `schedule()` call still succeeds (one bad checkpoint write doesn't wedge the scheduler).
- `drain()` with nothing ever scheduled returns immediately (no NPE on the null `lastJob`).

**Steps:** TDD as above → commit:
```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/session/CheckpointScheduler.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/session/CheckpointSchedulerTest.kt
git commit --no-gpg-sign -m "feat(recorder): ordered, drainable async checkpoint sign+persist"
```

---

### Task 3: Wire checkpoints into the session-entry path

**Files:**
- Modify: **ASSUMED INTERFACE (Plan 3/4)** — the session-startup/activation file that constructs the session host and writer (TS analogue: `packages/recorder/src/extension.ts`'s activation function). Exact Kotlin file/class name is Plan 3/4's to establish; this task documents the required call sites so they can be added wherever that lands.

**Interfaces (ASSUMED, from Plan 4 — reconcile at execution):**
- A `SessionWriter`-equivalent with `append(entry: HashedEnvelope)`, `dispose()`.
- A `MetaWriter`-equivalent with `suspend fun appendCheckpoint(cp: Checkpoint)`.
- A `SessionHost`-equivalent: constructed with an `onEntry: (HashedEnvelope) -> Unit` callback, exposing `fun emit(kind: String, data: JsonObject): HashedEnvelope`.
- A `SessionKeypair` (Plan 2 Task 4, already real) providing `privateKey: ByteArray`.

**Wiring (mirrors `extension.ts:249-296` exactly):**

```kotlin
// Inside session startup, after writer/metaWriter/keypair exist and before sessionHost.emit("session.start", ...):

val checkpointCadence = CheckpointCadence()
val checkpointScheduler = CheckpointScheduler(
    scope = sessionCoroutineScope, // ASSUMED: a CoroutineScope owned by this session, cancelled at session end — see Task 7 for how a Disposable-scoped one is constructed
    privateKey32 = keypair.privateKey,
    appendCheckpoint = { cp -> metaWriter.appendCheckpoint(cp) },
    onError = { e -> logger.warn("[provenance] checkpoint sign/write error", e) },
)

val sessionHost = createSessionHost(
    sessionId = recorderContext.sessionId,
    clock = clock,
    onEntry = { entry ->
        if (diskFullHandler.degraded) {           // Task 7
            diskFullHandler.enqueue(entry)
            return@createSessionHost
        }
        writer.append(entry)
        if (checkpointCadence.onEntryAppended()) {
            checkpointScheduler.schedule(entry.seq, entry.hash)
        }
    },
)

// At session end, before writer.dispose() / metaWriter close:
checkpointScheduler.drain()
```

**Test intent (this task, once the real assumed types exist):**
- Integration test (JUnit, fake `SessionWriter`/`MetaWriter`/`SessionHost` doubles — no real IntelliJ platform needed for this slice) asserting: appending exactly 100 entries triggers exactly one `appendCheckpoint` call referencing the 100th entry's `(seq, hash)`; appending 250 triggers exactly two, at the 100th and 200th; a session ended after 150 entries and a `drain()` call has exactly one checkpoint persisted (not a dangling one for the un-checkpointed 50).
- Confirms `checkpointScheduler.schedule()` is *not* called while `diskFullHandler.degraded` is true (checkpointing stops once degraded, matching `extension.ts`'s control flow where the checkpoint block is unreachable in the degraded branch).

**Steps:**
- [ ] Reconcile the ASSUMED types above against the real Plan 3/4 code (rename fields/methods as needed — this is expected, not a plan defect).
- [ ] Add the wiring at session-start and session-end.
- [ ] Write and pass the integration test with fakes.
- [ ] Commit: `git commit --no-gpg-sign -m "feat(recorder): wire checkpoint scheduler into session lifecycle"` (pathspec scoped to the actual files touched).

---

### Task 4: Chain recovery decision logic (pure, injected seam)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/startup/ChainRecovery.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/startup/ChainRecoveryTest.kt`

**Interfaces:**
- Consumes: `dev.provenance.core.parseEntries`, `dev.provenance.core.ParseResult`, `dev.provenance.core.validateChain`, `dev.provenance.core.ChainCheck`, `dev.provenance.core.HashedEnvelope` (Plan 1).
- Produces:
  - `sealed interface RecoveryDecision { data object CleanStart; data class PreviousSessionComplete(val prevSessionId: String); data class PreviousSessionDangling(val prevSessionId: String, val danglingPath: String); data class PreviousSessionCorrupt(val quarantinedPath: String) }`
  - `sealed interface SlogReadResult { data class Ok(val text: String); data class Err(val reason: String) }`
  - `interface RecoveryDeps { val provenanceDir: String; suspend fun readSlogFile(path: String): SlogReadResult; suspend fun rename(from: String, to: String); suspend fun listSlogFiles(dir: String): List<String>; fun now(): java.time.Instant }`
  - `suspend fun recoverPreviousSession(deps: RecoveryDeps): RecoveryDecision`

This is a direct port of `packages/recorder/src/startup/chain-recovery.ts` — read that file's header comment before touching this task; it documents the alphabetical-tie-break rationale, the prev-session-id-only-on-dangling rule, and why corruption is surfaced via quarantine + `recorder.recovered_from_corruption` rather than a `chain.broken` event. Do not deviate from that rationale.

```kotlin
package dev.provenance.recorder.startup

import dev.provenance.core.ChainCheck
import dev.provenance.core.ParseResult
import dev.provenance.core.parseEntries
import dev.provenance.core.validateChain
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

sealed interface RecoveryDecision {
    data object CleanStart : RecoveryDecision
    data class PreviousSessionComplete(val prevSessionId: String) : RecoveryDecision
    data class PreviousSessionDangling(val prevSessionId: String, val danglingPath: String) : RecoveryDecision
    data class PreviousSessionCorrupt(val quarantinedPath: String) : RecoveryDecision
}

sealed interface SlogReadResult {
    data class Ok(val text: String) : SlogReadResult
    data class Err(val reason: String) : SlogReadResult // "not_found" | "read_error"
}

/** Injection seam — the real java.nio-backed implementation is wired in Task 5. Everything
 *  here is testable with an in-memory fake, without touching disk. */
interface RecoveryDeps {
    val provenanceDir: String
    suspend fun readSlogFile(path: String): SlogReadResult
    suspend fun rename(from: String, to: String)
    suspend fun listSlogFiles(dir: String): List<String>
    fun now(): Instant
}

private fun quarantinePath(slogPath: String, now: Instant): String =
    "$slogPath.corrupt-${now.toString().replace(Regex("[:.]"), "-")}"

/**
 * Inspect provenanceDir for a previous session and return a recovery decision.
 * Side effect: if the chain is invalid (or unreadable/unparsable), renames the .slog to
 * `<slog>.corrupt-<ISO>` (quarantine) before returning.
 */
suspend fun recoverPreviousSession(deps: RecoveryDeps): RecoveryDecision {
    val slogFiles = deps.listSlogFiles(deps.provenanceDir).filter { it.endsWith(".slog") }.sorted()
    if (slogFiles.isEmpty()) return RecoveryDecision.CleanStart

    // Alphabetically last — see chain-recovery.ts header comment for the tie-break rationale.
    val slogPath = "${deps.provenanceDir}/${slogFiles.last()}"

    suspend fun quarantine(): RecoveryDecision.PreviousSessionCorrupt {
        val quarantined = quarantinePath(slogPath, deps.now())
        deps.rename(slogPath, quarantined)
        return RecoveryDecision.PreviousSessionCorrupt(quarantined)
    }

    val readResult = deps.readSlogFile(slogPath)
    if (readResult !is SlogReadResult.Ok) return quarantine()

    val parseResult = parseEntries(readResult.text)
    if (parseResult !is ParseResult.Ok) return quarantine()

    val entries = parseResult.entries
    if (validateChain(entries) !is ChainCheck.Valid) return quarantine()

    val first = entries.firstOrNull() ?: return quarantine()
    if (first.kind != "session.start") return quarantine()
    val prevSessionId = first.data["session_id"]?.jsonPrimitive?.contentOrNull ?: return quarantine()

    val last = entries.lastOrNull()
    val isComplete = last != null && last.kind == "session.end"

    return if (isComplete) {
        RecoveryDecision.PreviousSessionComplete(prevSessionId)
    } else {
        RecoveryDecision.PreviousSessionDangling(prevSessionId, slogPath)
    }
}
```

**Test intent (all via an in-memory fake `RecoveryDeps` — no real filesystem, no IntelliJ):**
- No `.slog` files in the directory → `CleanStart`.
- Multiple `.slog` files → the alphabetically-last one is chosen (assert on which file was read, via a fake that records calls).
- A well-formed, chain-valid `.slog` ending in `session.end` → `PreviousSessionComplete` with the correct `prevSessionId`; **no `rename` call** (nothing quarantined).
- Same but *without* a trailing `session.end` → `PreviousSessionDangling` with the correct `prevSessionId` and `danglingPath`; no `rename` call.
- `readSlogFile` returns `Err` → `PreviousSessionCorrupt`, and `rename` was called with `(slogPath, "<slogPath>.corrupt-<iso>")` where `<iso>` is derived from the injected `now()` (assert the exact quarantine path format, colons/dots replaced with dashes).
- Well-formed JSON lines but a broken hash chain (mutate one entry after building a valid chain, mirroring `ChainValidatorTest`'s tamper test from Plan 1) → `PreviousSessionCorrupt`, quarantined.
- First entry's `kind` isn't `session.start`, or its `data` has no `session_id` → `PreviousSessionCorrupt` (malformed-header cases).
- **Never** does any test path see a `RecoveryDecision` that also triggers `chain.broken` emission — that event kind does not appear anywhere in this module (guards against accidentally reviving the reserved-but-unused kind).

**Steps:** TDD → commit:
```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/startup/ChainRecovery.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/startup/ChainRecoveryTest.kt
git commit --no-gpg-sign -m "feat(recorder): startup chain recovery decision logic"
```

---

### Task 5: Wire chain recovery into activation

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/startup/NioRecoveryDeps.kt` — the real `RecoveryDeps` implementation over `java.nio.file`.
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/startup/NioRecoveryDepsTest.kt`
- Modify: **ASSUMED INTERFACE (Plan 3)** — the activation entry point (TS analogue: the top of `extension.ts`'s activation function, before `SessionWriter.open(...)`).

**Interfaces:**
- `class NioRecoveryDeps(override val provenanceDir: String) : RecoveryDeps` using `java.nio.file.Files.readString`/`Files.exists` for `readSlogFile`, `java.nio.file.Files.move` (with `StandardCopyOption.ATOMIC_MOVE` where supported, falling back gracefully — **VERIFY AT EXECUTION**: `ATOMIC_MOVE` support is filesystem-dependent; the rename target is same-directory so it should hold on all platforms the plugin targets, but confirm) for `rename`, `Files.list` + filter for `listSlogFiles`, `Instant.now()` for `now()`. Run off the EDT — this does blocking file I/O at plugin activation, which must not stall the UI thread. Use `Dispatchers.IO` (bundled coroutines) or `ApplicationManager.getApplication().executeOnPooledThread { }` — **VERIFY AT EXECUTION** against whatever thread Plan 3's activation callback already runs on (some IntelliJ activation extension points already run off-EDT; wrapping again would be redundant, not wrong, but check).

**Wiring (mirrors `extension.ts:170-296`):**

```kotlin
val recovery = recoverPreviousSession(NioRecoveryDeps(provenanceDir))

val prevSessionId: String? = (recovery as? RecoveryDecision.PreviousSessionDangling)?.prevSessionId

// ... build recorderContext with prevSessionId, open writer/metaWriter (Plan 4), create sessionHost (Task 3) ...

sessionHost.emit("session.start", recorderContext)

if (recovery is RecoveryDecision.PreviousSessionCorrupt) {
    sessionHost.emit(
        "recorder.recovered_from_corruption",
        buildJsonObject { put("quarantined_path", recovery.quarantinedPath) },
    )
}
```

**Test intent:**
- `NioRecoveryDepsTest` (plain JUnit, real temp directory via `@TempDir`, no IntelliJ platform needed): `readSlogFile` returns `Ok` for an existing file and `Err("not_found")` for a missing one; `rename` actually moves the file and the original path no longer exists; `listSlogFiles` returns only `.slog`-suffixed names, not `.slog.meta` or unrelated files.
- Activation-level test (once Plan 3's real activation exists): a fixture directory with a dangling prior `.slog` produces a new session whose `session.start.data.prev_session_id` equals the prior session's id; a fixture with a corrupted prior `.slog` produces a new session whose second entry (seq 1) is `recorder.recovered_from_corruption` referencing the quarantined path, and the original file is gone from its original location (found instead at the quarantined path).

**Steps:**
- [ ] Implement and test `NioRecoveryDeps` in isolation (no ASSUMED types needed for this half).
- [ ] Reconcile the activation wiring against Plan 3's real entry point.
- [ ] Commit `NioRecoveryDeps` and its test separately from the activation wiring (small diffs): `git commit --no-gpg-sign -m "feat(recorder): java.nio-backed RecoveryDeps"`, then a second commit for the activation wiring once reconciled.

---

### Task 6: Disk-full degraded mode handler (pure, injected seam)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/failure/DiskFullHandler.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/failure/DiskFullHandlerTest.kt`

**Interfaces:**
- Consumes: `dev.provenance.core.HashedEnvelope`.
- Produces:
  - `val CRITICAL_KINDS: Set<String>` (the six kinds listed in Global Constraints).
  - `class DiskFullHandler(ringCapacity: Int = 256, onDegraded: (reason: String) -> Unit, notify: (message: String) -> Unit) { val degraded: Boolean; fun handleWriteError(error: Throwable); fun enqueue(entry: HashedEnvelope): Boolean; fun snapshot(): List<HashedEnvelope> }`

Ported from `packages/recorder/src/failure/disk-full-handler.ts` — read its header comment (idempotence rationale, ring-eviction policy) before touching this task.

**Porting note (Kotlin/JVM vs single-threaded JS):** the TS original has no concurrency to worry about. On the JVM, `handleWriteError` may be invoked from a background I/O thread (the writer's async flush) while `enqueue` is invoked from whatever thread appends entries (per this plan's Task 3 wiring, that's the `onEntry` callback, likely the same thread that calls `sessionHost.emit`). To avoid a torn `ArrayDeque` under concurrent access, guard shared state with `synchronized(this)` — a deliberate, minimal deviation from the TS source to account for real JVM threading, not a behavior change.

```kotlin
package dev.provenance.recorder.failure

import dev.provenance.core.HashedEnvelope

/** Event kinds retained in degraded mode; all others are dropped. PRD §4.8. */
val CRITICAL_KINDS: Set<String> = setOf(
    "session.start",
    "session.end",
    "fs.external_change",
    "chain.broken",
    "recorder.degraded",
    "recorder.recovered_from_corruption",
)

private const val DEFAULT_RING_CAPACITY = 256

/**
 * Handles write failures (disk-full and friends) on the live .slog write path.
 * Ported from provenance/packages/recorder/src/failure/disk-full-handler.ts.
 * synchronized() guards are a JVM-threading addition not present in the (single-threaded)
 * TS original — see Task 6 porting note.
 */
class DiskFullHandler(
    private val ringCapacity: Int = DEFAULT_RING_CAPACITY,
    private val onDegraded: (reason: String) -> Unit,
    private val notify: (message: String) -> Unit,
) {
    private var _degraded = false
    val degraded: Boolean
        get() = synchronized(this) { _degraded }

    private val ring = ArrayDeque<HashedEnvelope>()

    /** Idempotent: the first call transitions to degraded; later calls are no-ops. */
    fun handleWriteError(error: Throwable) {
        val shouldNotify = synchronized(this) {
            if (_degraded) {
                false
            } else {
                _degraded = true
                true
            }
        }
        if (!shouldNotify) return
        notify("Disk full — Provenance recording is degraded. Free space and restart your IDE.")
        onDegraded("disk_full")
    }

    /** True (accepted into the ring) iff degraded and entry.kind is critical. */
    fun enqueue(entry: HashedEnvelope): Boolean = synchronized(this) {
        if (!_degraded || entry.kind !in CRITICAL_KINDS) return@synchronized false
        if (ring.size >= ringCapacity) ring.removeFirst()
        ring.addLast(entry)
        true
    }

    fun snapshot(): List<HashedEnvelope> = synchronized(this) { ring.toList() }
}
```

**Test intent:**
- `degraded` is `false` initially; after one `handleWriteError`, `true`.
- `handleWriteError` is idempotent: calling it twice invokes `notify` and `onDegraded` exactly once each (assert call counts, not just final state — this is the property most likely to regress).
- Before degraded, `enqueue` always returns `false` (nothing buffered) regardless of kind.
- After degraded, `enqueue` returns `true` and buffers only for kinds in `CRITICAL_KINDS`; a non-critical kind (e.g. `doc.change`) returns `false` and is not in `snapshot()`.
- Ring eviction: with `ringCapacity = 2`, enqueueing three critical entries leaves only the last two in `snapshot()`, oldest evicted first (FIFO).
- `snapshot()` returns a copy — mutating the returned list does not affect subsequent `enqueue` behavior (matches the TS `[...this.ring]` shallow-copy contract).
- Concurrency smoke test: spin up N threads calling `handleWriteError`/`enqueue` concurrently against a shared handler; assert no exception and the final `snapshot()` size never exceeds `ringCapacity` (this is the one property Kotlin adds over the TS original — worth a dedicated test given the porting note above).

**Steps:** TDD → commit:
```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/failure/DiskFullHandler.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/failure/DiskFullHandlerTest.kt
git commit --no-gpg-sign -m "feat(recorder): disk-full degraded-mode handler with critical-kind ring buffer"
```

---

### Task 7: Wire degraded mode into the writer, notifications, and status bar

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/failure/DegradedModeNotifier.kt`
- Modify: **ASSUMED INTERFACE (Plan 3)** — `recorder/src/main/resources/META-INF/plugin.xml` (register the notification group).
- Modify: **ASSUMED INTERFACE (Plan 3/4)** — session-startup activation file (same one touched in Tasks 3 and 5) and, optionally, Plan 3's status-bar widget class if it exposes a way to reflect a degraded flag.

**Interfaces:**
- `class DegradedModeNotifier(private val project: Project) { fun notifyDegraded() }` using `Notification(GROUP_ID, content, NotificationType.ERROR).notify(project)`.
- `GROUP_ID = "Provenance Recorder"`, registered in `plugin.xml`: `<notificationGroup id="Provenance Recorder" displayType="BALLOON"/>` under `<extensions defaultExtensionNs="com.intellij">`.

```kotlin
package dev.provenance.recorder.failure

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Surfaces disk-full degraded mode to the user via a balloon notification. Notification
 * group registration: plugin.xml, com.intellij.notificationGroup extension point
 * (see https://plugins.jetbrains.com/docs/intellij/notification-balloons.html).
 *
 * VERIFY AT EXECUTION: confirm Notification.notify(project) is safe to call from the
 * background thread DiskFullHandler.handleWriteError() runs on for the target platform
 * baseline (Plan 3 pins the baseline). If not, dispatch via
 * ApplicationManager.getApplication().invokeLater { ... } before constructing/showing it.
 */
class DegradedModeNotifier(private val project: Project) {
    fun notifyDegraded() {
        Notification(
            GROUP_ID,
            "Disk full — recording has switched to a minimal safety log. " +
                "Free disk space and restart the IDE to resume full recording.",
            NotificationType.ERROR,
        ).notify(project)
    }

    companion object {
        const val GROUP_ID = "Provenance Recorder"
    }
}
```

**Wiring (mirrors `extension.ts:204-232`):**

```kotlin
// Forward reference populated once sessionHost exists, same pattern as the TS original
// (sessionHostEmit is null until Step "create session host", but guaranteed set before any
// write can occur — no write happens before session.start is emitted).
var sessionHostEmit: ((kind: String, data: JsonObject) -> Unit)? = null

val diskFullHandler = DiskFullHandler(
    onDegraded = { reason ->
        sessionHostEmit?.invoke("recorder.degraded", buildJsonObject { put("reason", reason) })
    },
    notify = { _ -> notifier.notifyDegraded() }, // DegradedModeNotifier — message text lives there; the DiskFullHandler's own `message` param is unused by this notify lambda, matching that DegradedModeNotifier owns user-facing copy
)

val writer = /* Plan 4 */ SessionWriter.open(
    slogPath = slogPath,
    onError = { e -> diskFullHandler.handleWriteError(e) },
)

// ... sessionHost created per Task 3 ...
sessionHostEmit = { kind, data -> sessionHost.emit(kind, data) }
```

- [ ] **Step 1:** Add the `notificationGroup` extension point to `plugin.xml`. If Plan 3's `plugin.xml` does not yet exist when this task runs, this step blocks on Plan 3 — do not fabricate a `plugin.xml` here.
- [ ] **Step 2:** Implement `DegradedModeNotifier` as above.
- [ ] **Step 3:** Reconcile and add the `diskFullHandler`/`sessionHostEmit`/`writer` wiring against the real Plan 3/4 activation code.
- [ ] **Step 4 (optional, status bar):** if Plan 3's status-bar widget exposes a mutable presentation (commonly via `WidgetPresentation` + `statusBar.updateWidget(widgetId)` — `plugins.jetbrains.com/docs/intellij/status-bar-widgets.html`), have `DegradedModeNotifier` or the wiring layer also flip a degraded flag the widget reads and call the update method. **VERIFY AT EXECUTION** against Plan 3's actual widget class and method name — do not guess a class that doesn't exist yet; if Plan 3 has no such hook, the balloon notification alone satisfies PRD §4.8's "surface a notification" requirement and this step can be skipped without blocking the rest of the plan.
- [ ] **Step 5:** Commit, pathspec scoped to files actually touched:
```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/failure/DegradedModeNotifier.kt \
  recorder/src/main/resources/META-INF/plugin.xml
git commit --no-gpg-sign -m "feat(recorder): wire disk-full degraded mode to writer + user notification"
```

---

### Task 8: End-to-end integration test + full-suite gate

**Files:**
- Create: `recorder/src/test/kotlin/dev/provenance/recorder/session/SessionLifecycleIntegrationTest.kt` (fakes for `SessionWriter`/`MetaWriter`, real `CheckpointCadence`/`CheckpointScheduler`/`DiskFullHandler`/chain-recovery logic).

**Test intent (the payoff of building the recovery/degraded logic with injected seams — this whole plan is unit-testable without ever starting a real IDE):**
1. **Normal run with checkpoints:** append 250 fake entries through the full `onEntry` wiring from Task 3; assert `appendCheckpoint` was called exactly twice, at seq 99 and seq 199 (0-indexed), each with a valid signature over the corresponding entry hash.
2. **Startup recovery — dangling session:** seed a fake filesystem with a valid-chain `.slog` lacking a trailing `session.end`; run `recoverPreviousSession`; assert `PreviousSessionDangling` with the right id, and that this id ends up as `prev_session_id` in the *new* session's `session.start` payload (full wiring from Task 5).
3. **Startup recovery — corrupted session:** seed a fake filesystem with a `.slog` whose second line has a tampered `data` field (breaks the hash chain); run recovery; assert the file was "renamed" (in the fake) to the expected quarantine path, the new session's seq-1 entry is `recorder.recovered_from_corruption` with `quarantined_path` matching, and `prev_session_id` is `null` (not linked — matches the documented rule).
4. **Disk-full degraded mode:** drive `onEntry` with a fake `writer.append` that throws on the 50th call; assert: entries 1–49 reached the (fake) writer; entry 50's write error flips `diskFullHandler.degraded` to `true` and triggers exactly one `notify` call; entries 51+ never reach the fake writer again (no further disk I/O attempted against a full disk); of entries 51+, only `CRITICAL_KINDS` ones appear in `diskFullHandler.snapshot()`; **no checkpoint is scheduled for any entry ≥ 50** (checkpointing stops once degraded, per Global Constraints).
5. **Atomic-write discipline preserved:** the fake writer used in scenario 4 asserts (via its own internal invariant check) that it never receives a second `append` call after reporting failure without first being told the buffer was cleared — i.e., this test proves Plan 8's wiring never retries a failed write against the same writer instance, which is the property that keeps the live `.slog` free of partial/duplicate lines when disk fills up mid-flush. (This test does *not* re-verify Plan 4's own atomic-write internals — that's Plan 4's test suite's job — it verifies Plan 8's wiring doesn't defeat that guarantee by hammering a failed writer.)

**Steps:**
- [ ] Write the five scenarios above against the fakes.
- [ ] Run `./gradlew :recorder:test --tests 'dev.provenance.recorder.session.SessionLifecycleIntegrationTest'` — PASS.
- [ ] Run `./gradlew :core:test :recorder:test` — the entire `core/` + `recorder/` suite green. This is the gate before Plan 9 (packaging) begins.
- [ ] Commit:
```bash
git add recorder/src/test/kotlin/dev/provenance/recorder/session/SessionLifecycleIntegrationTest.kt
git commit --no-gpg-sign -m "test(recorder): end-to-end checkpoint/recovery/degraded-mode integration gate"
```

---

## Self-Review

**Spec coverage (design.md §8 item 7 — "checkpoints + chain recovery + disk-full degraded mode"):** checkpoint cadence (Task 1), ordered async sign+persist with a drain shutdown-hook (Task 2), wiring into the session-entry path (Task 3), recovery decision logic (Task 4), java.nio wiring + activation integration (Task 5), degraded-mode handler with critical-kind ring buffer (Task 6), user disclosure via `Notification` + optional status-bar reflection (Task 7), end-to-end gate (Task 8). All three PRD §4.6/§4.8 behaviors are covered.

**Fidelity note:** every pure-logic task (1, 2, 4, 6) is a direct, verified port against the real, already-shipped TS source (`chain-recovery.ts`, `disk-full-handler.ts`, the checkpoint block in `extension.ts:249-296`) — I read those files, not just their descriptions, before writing the Kotlin. One correction to the task brief worth flagging explicitly: the brief asked me to research periodic/scheduled background APIs on the premise that checkpoints are wall-clock-periodic; the real source shows checkpoint cadence is entry-count-based (every 100 events), not time-based, and there is no periodic timer anywhere in the ported behavior. I used the coroutine-scope research for what the plan actually needs instead — ordering the async sign+persist work with a proper drain/shutdown hook (Task 2) — rather than inventing a wall-clock trigger that doesn't exist in the product. The `synchronized()` guards in `DiskFullHandler` (Task 6) are the one deliberate, minimal addition beyond the TS source, needed because the JVM has real threads where Node.js does not; flagged inline in the code comment and covered by a dedicated concurrency test.

**IntelliJ usage is minimal, as instructed:** only Task 7 touches real platform surface (`com.intellij.notification.Notification`, and optionally a status-bar update whose exact call is deferred to Plan 3's real widget). Tasks 1, 2, 4, 6 are plain JUnit-testable Kotlin with zero or (Task 2) bundled-library-only dependencies — no `BasePlatformTestCase` needed anywhere in this plan.

**Open dependency note:** no new Gradle dependency is added. `kotlinx-coroutines-core` is IntelliJ-Platform-bundled (cited); this plan deliberately avoids `kotlinx-coroutines-test` (unconfirmed-bundled) by testing with `runBlocking` + `Dispatchers.Unconfined` instead.

**VERIFY AT EXECUTION flags (collected):**
1. Task 2 / Global Constraints — confirm Plan 3's target-platform baseline is ≥ 2024.1 before relying on constructor-injected `CoroutineScope`; otherwise fall back to a manually-disposed `CoroutineScope`.
2. Task 3, 5, 7 — every "ASSUMED INTERFACE" call site must be reconciled against Plan 3/4's actual `SessionWriter`/`MetaWriter`/`SessionHost`/activation code and `plugin.xml`, none of which exist yet as of this writing.
3. Task 5 — `Files.move` with `ATOMIC_MOVE` for quarantine rename: confirm same-directory atomic rename holds on all target platforms (should, but verify).
4. Task 5 — confirm which thread Plan 3's activation extension point already runs on before deciding whether `NioRecoveryDeps` needs its own `Dispatchers.IO`/pooled-thread wrapper.
5. Task 7 — confirm `Notification.notify(project)`'s thread-safety on the target platform baseline; add `invokeLater` dispatch if needed.
6. Task 7 Step 4 — status-bar reflection of degraded state is optional and entirely contingent on what Plan 3's widget actually exposes; do not block the rest of the plan on it.

**Placeholder scan:** no task contains a "TODO"/"add validation"-style stub — every code block is complete, runnable Kotlin (modulo the explicitly-labeled ASSUMED INTERFACE call sites, which are assumptions about *other, not-yet-written* plans' surface area, not gaps in this plan's own logic).
