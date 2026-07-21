# Nested Manifest Discovery + Concurrent Multi-Assignment Recording — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Discover every nested `.provenance-manifest` under a JetBrains project (recursive VFS walk), run one concurrent recording session per verified manifest, route every doc/selection/terminal/git event to exactly one owning session by nearest-ancestor path, and add a seal-time assignment chooser when more than one session is active — while never changing the log format, manifest schema, JCS, hash chain, or signing.

**Architecture:** `RecorderSessionManager` becomes a `Map<Path, ActiveSession>` registry instead of a single `var activeSession`. `DocWiring`/`SelectionWiring` stop being constructed once per session (which the old code embedded inside `RecordingSessionController`) and become a single project-scoped pair of listeners that resolve the *owning* session per event via a new `SessionRouter`/`RecordableSessionSink` seam (nearest-ancestor lookup against the live registry), exactly mirroring how `RecorderTerminalState`/`RecorderGitState`'s null-callback privacy gate already works today — except the callback itself becomes a router (keyed by a resolved cwd/repo-root path) instead of a single fixed emit closure, so a second session can never clobber the first. Discovery is a pure recursive walk over `VirtualFile` reusing the existing, unmodified `loadAndVerifyManifest`.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (`com.intellij.modules.platform` — platform-common APIs only), JUnit + `BasePlatformTestCase`/`HeavyPlatformTestCase`. No new dependencies.

## Global Constraints

- Log format, manifest schema, JCS, hash chain, signing are **never** touched. `TerminalOpenPayload`, `TerminalCommandPayload`, `GitEventPayload`, and every other wire-format payload in `core/` keep their exact existing fields — routing paths (cwd, git repo root) are resolved and consumed **only** on the platform side, never added to a signed/chained payload.
- Platform-common APIs only (target stays `com.intellij.modules.platform`). No new Gradle dependencies.
- Terminal/git attribution is by path (cwd / repo root); drop (do not record) when no session's root is an ancestor of that path. Never guess an owner.
- A file belongs to the **nearest-ancestor** verified-manifest directory (locked decision #5 in the spec) — this must hold even when one verified manifest's directory is nested inside another's.
- Small, reviewable commits per task, conventional-commit prefixes, `git commit --no-gpg-sign`, no `Co-Authored-By` trailer, explicit pathspecs (never `git add -A`).
- Every new/changed behavior ships with a test. Never weaken an assertion or a test to make it pass — stop and report if a requirement can't be met.
- Branch: `feat/nested-manifest-discovery` (already created; this plan's own commit lands on it). Do not merge, push, or open a PR — stop after verification.
- Build/verify commands: `./gradlew :core:test`, `./gradlew :recorder:test`, and (if present) the conformance-vector task. Run the full `./gradlew build test` before declaring done, and report exact output.

## Known risk carried forward (read before Task 4)

`TerminalWiringStartupActivity.terminalViewCreated` needs a terminal's **working directory** to route by path. The existing code already reads `view.startupOptionsDeferred.await().shellCommand` from what is very likely a `ShellStartupOptions`-shaped object, which (in the classic, non-experimental terminal plugin) also carries a `workingDirectory` field — but this file already carries an `EXPERIMENTAL-API RISK` comment because the "Reworked Terminal" frontend types it uses are `@ApiStatus.Experimental` and may not match 1:1. Task 4 requires **compiling against the real SDK** to confirm the field name/type before relying on it. If no working-directory accessor exists on the resolved type, the required behavior is to resolve `cwd = null` (which the router already treats as "no owner — drop", per this plan's locked design) — **never** invent or guess a directory. If this happens, flag it prominently in the task's completion report; do not silently ship a guess.

---

### Task 1: Manifest discovery core

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/activation/ManifestDiscovery.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/activation/ManifestDiscoveryTest.kt`

**Interfaces:**
- Consumes: `loadAndVerifyManifest(baseDir: VirtualFile, coursePubkeyHex: String): ManifestActivation` (existing, `activation/ManifestLoader.kt`, unmodified), `ManifestActivation` sealed interface (existing, `activation/ManifestActivation.kt`).
- Produces: `data class DiscoveredManifest(val root: VirtualFile, val manifest: Manifest)`; `fun discoverManifestRoots(searchRoots: List<VirtualFile>, coursePubkeyHex: String = COURSE_PUBLIC_KEY_HEX, maxDepth: Int = DEFAULT_MAX_DISCOVERY_DEPTH, prunedDirNames: Set<String> = DISCOVERY_PRUNED_DIR_NAMES): List<DiscoveredManifest>`; `fun discoverManifestRoots(project: Project, coursePubkeyHex: String = COURSE_PUBLIC_KEY_HEX): List<DiscoveredManifest>`. Task 6 (`RecorderActivationActivity`) consumes both.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.provenance.recorder.activation

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.Canonical
import dev.provenance.core.Ed25519
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

class ManifestDiscoveryTest : BasePlatformTestCase() {

    private fun signedManifestJson(priv: ByteArray, assignmentId: String): String {
        val payload = buildJsonObject {
            put("assignment_id", assignmentId)
            put("semester", "fa26")
            put("issued_at", "2026-09-15T00:00:00Z")
            putJsonArray("files_under_review") { add("hw.py") }
        }.toString()
        val canon = Canonical.canonicalize(payload)
        val sig = Ed25519.bytesToHex(Ed25519.sign(canon.toByteArray(Charsets.UTF_8), priv))
        return buildJsonObject {
            put("assignment_id", assignmentId)
            put("semester", "fa26")
            put("issued_at", "2026-09-15T00:00:00Z")
            putJsonArray("files_under_review") { add("hw.py") }
            put("sig", sig)
        }.toString()
    }

    fun `test finds a single manifest at the search root`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val dir = myFixture.addFileToProject("proj/.provenance-manifest", signedManifestJson(priv, "hw01")).virtualFile.parent
        val found = discoverManifestRoots(listOf(dir), Ed25519.bytesToHex(pub))
        assertEquals(1, found.size)
        assertEquals("hw01", found[0].manifest.assignmentId)
        assertEquals(dir, found[0].root)
    }

    fun `test finds two sibling nested manifests and skips a bad-signature sibling`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val (foreignPriv, _) = Ed25519.generateKeypair()
        val base = myFixture.addFileToProject("course/cats/.provenance-manifest", signedManifestJson(priv, "cats")).virtualFile.parent.parent
        myFixture.addFileToProject("course/hog/.provenance-manifest", signedManifestJson(priv, "hog"))
        myFixture.addFileToProject("course/bad/.provenance-manifest", signedManifestJson(foreignPriv, "bad"))

        val found = discoverManifestRoots(listOf(base), Ed25519.bytesToHex(pub))

        val assignmentIds = found.map { it.manifest.assignmentId }.toSet()
        assertEquals(setOf("cats", "hog"), assignmentIds)
    }

    fun `test returns empty list when nothing verifies`() {
        val (_, pub) = Ed25519.generateKeypair()
        val dir = myFixture.addFileToProject("empty/unrelated.txt", "nothing").virtualFile.parent
        assertTrue(discoverManifestRoots(listOf(dir), Ed25519.bytesToHex(pub)).isEmpty())
    }

    fun `test does not descend into pruned directories`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val base = myFixture.addFileToProject("proj2/marker.txt", "x").virtualFile.parent
        myFixture.addFileToProject("proj2/node_modules/pkg/.provenance-manifest", signedManifestJson(priv, "sneaky"))
        val found = discoverManifestRoots(listOf(base), Ed25519.bytesToHex(pub))
        assertTrue("manifests under a pruned dir name must never be discovered", found.isEmpty())
    }

    fun `test finds a manifest nested inside an already-found manifest directory`() {
        val (priv, pub) = Ed25519.generateKeypair()
        val outer = myFixture.addFileToProject("nest/.provenance-manifest", signedManifestJson(priv, "outer")).virtualFile.parent
        myFixture.addFileToProject("nest/inner/.provenance-manifest", signedManifestJson(priv, "inner"))
        val found = discoverManifestRoots(listOf(outer), Ed25519.bytesToHex(pub))
        assertEquals(setOf("outer", "inner"), found.map { it.manifest.assignmentId }.toSet())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail (function doesn't exist)**

Run: `./gradlew :recorder:test --tests "dev.provenance.recorder.activation.ManifestDiscoveryTest"`
Expected: FAIL (compile error — `discoverManifestRoots` unresolved).

- [ ] **Step 3: Implement `ManifestDiscovery.kt`**

```kotlin
package dev.provenance.recorder.activation

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import dev.provenance.core.Manifest

/** One verified assignment root discovered under the project. */
data class DiscoveredManifest(val root: VirtualFile, val manifest: Manifest)

/**
 * Directory names discovery never descends into: heavy, irrelevant, or (for `.provenance` /
 * `.idea`) generated by the recorder or IDE itself, mirroring RecordabilityFilter's
 * IDE_SETTINGS_DIR_NAME exclusion generalized to a walk that runs before any session (and its
 * one fixed provenanceDir) exists.
 */
val DISCOVERY_PRUNED_DIR_NAMES: Set<String> = setOf(
    ".git", ".provenance", ".idea", ".gradle", "node_modules", "build", "out", "target", "dist",
)

/** Bounds cost on deep/large trees. Five levels comfortably covers realistic course layouts
 * (e.g. `61a/proj/cats/` is 3 deep from a `61a/` search root). */
const val DEFAULT_MAX_DISCOVERY_DEPTH: Int = 6

/**
 * Recursively find every directory under [searchRoots] holding a verified `.provenance-manifest`
 * / `provenance-manifest`, skipping [prunedDirNames] and anything past [maxDepth]. Reuses the
 * existing, unmodified [loadAndVerifyManifest] per directory — an unparsable or bad-signature
 * manifest is silently skipped (PRD §4.1: "the extension does nothing"), never throws, never
 * partially activates. Continues descending into a directory that already holds a verified
 * manifest, so a genuinely nested assignment-inside-an-assignment is still discovered; any
 * resulting overlap is resolved by nearest-ancestor ownership at routing time, not here.
 */
fun discoverManifestRoots(
    searchRoots: List<VirtualFile>,
    coursePubkeyHex: String = COURSE_PUBLIC_KEY_HEX,
    maxDepth: Int = DEFAULT_MAX_DISCOVERY_DEPTH,
    prunedDirNames: Set<String> = DISCOVERY_PRUNED_DIR_NAMES,
): List<DiscoveredManifest> {
    val found = mutableListOf<DiscoveredManifest>()
    val visited = mutableSetOf<String>()
    for (searchRoot in searchRoots) {
        walk(searchRoot, depth = 0, maxDepth, prunedDirNames, visited) { dir ->
            val result = loadAndVerifyManifest(dir, coursePubkeyHex)
            if (result is ManifestActivation.Active) {
                found += DiscoveredManifest(dir, result.manifest)
            }
        }
    }
    return found
}

private fun walk(
    dir: VirtualFile,
    depth: Int,
    maxDepth: Int,
    prunedDirNames: Set<String>,
    visited: MutableSet<String>,
    onDir: (VirtualFile) -> Unit,
) {
    if (!dir.isDirectory) return
    if (!visited.add(dir.path)) return
    onDir(dir)
    if (depth >= maxDepth) return
    for (child in dir.children) {
        if (child.isDirectory && child.name !in prunedDirNames) {
            walk(child, depth + 1, maxDepth, prunedDirNames, visited, onDir)
        }
    }
}

/**
 * Project-level convenience: search the guessed project dir plus every content root (covers
 * both the common single-content-root course project and a multi-module/attached-modules
 * layout) without requiring every consumer to assemble the search-root list by hand.
 */
fun discoverManifestRoots(project: Project, coursePubkeyHex: String = COURSE_PUBLIC_KEY_HEX): List<DiscoveredManifest> {
    val roots = LinkedHashSet<VirtualFile>()
    project.guessProjectDir()?.let { roots += it }
    roots += ProjectRootManager.getInstance(project).contentRoots
    return discoverManifestRoots(roots.toList(), coursePubkeyHex)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :recorder:test --tests "dev.provenance.recorder.activation.ManifestDiscoveryTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/activation/ManifestDiscovery.kt recorder/src/test/kotlin/dev/provenance/recorder/activation/ManifestDiscoveryTest.kt
git commit --no-gpg-sign -m "feat(recorder): add recursive nested-manifest discovery"
```

---

### Task 2: `RecorderState` → multi-root

**Files:**
- Modify: `recorder/src/main/kotlin/dev/provenance/recorder/activation/RecorderState.kt`
- Modify (tests): `recorder/src/test/kotlin/dev/provenance/recorder/activation/RecorderStateTest.kt`

**Interfaces:**
- Produces: `RecorderState.activate(root: Path, m: Manifest)`, `RecorderState.deactivate(root: Path)`, `RecorderState.deactivateAll()`, `RecorderState.deactivate()` (alias for `deactivateAll`, back-compat), `RecorderState.isActive: Boolean`, `RecorderState.manifest: Manifest?` (single-manifest back-compat: the manifest when exactly one assignment is active, else null), `RecorderState.activeManifests: Map<Path, Manifest>`. Consumed by Task 6 (activation), Task 8 (status bar).

- [ ] **Step 1: Update the failing tests first**

Replace `RecorderStateTest.kt` in full:

```kotlin
package dev.provenance.recorder.activation

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.Manifest
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths

class RecorderStateTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            project.service<RecorderState>().deactivateAll()
        } finally {
            super.tearDown()
        }
    }

    private fun manifest(assignmentId: String = "hw03") =
        Manifest(assignmentId, "fa26", "2026-09-15T00:00:00Z", listOf("hw03.py"), "a".repeat(128))

    private fun root(name: String): Path = Paths.get("/ws-$name")

    fun `test isActive is false by default`() {
        assertFalse(project.service<RecorderState>().isActive)
    }

    fun `test activate then isActive is true, manifest is stored`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest())
        assertTrue(state.isActive)
        assertEquals("hw03", state.manifest?.assignmentId)
    }

    fun `test deactivateAll clears every manifest`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest())
        state.deactivateAll()
        assertFalse(state.isActive)
        assertNull(state.manifest)
    }

    fun `test deactivate one root leaves the other active`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest("hw-a"))
        state.activate(root("b"), manifest("hw-b"))
        state.deactivate(root("a"))
        assertTrue(state.isActive)
        assertEquals(setOf("hw-b"), state.activeManifests.values.map { it.assignmentId }.toSet())
    }

    fun `test manifest is null when more than one assignment is active`() {
        val state = project.service<RecorderState>()
        state.activate(root("a"), manifest("hw-a"))
        state.activate(root("b"), manifest("hw-b"))
        assertNull("manifest is the single-assignment convenience; ambiguous with 2 active", state.manifest)
        assertEquals(2, state.activeManifests.size)
    }

    fun `test activity activates state for every discovered root when discoverer returns Active manifests`() = runBlocking {
        val m = myFixture.addFileToProject("hw07/.provenance-manifest", "{}").virtualFile.parent
        val activity = RecorderActivationActivity { _, _ -> listOf(DiscoveredManifest(m, manifest("hw07"))) }
        activity.execute(project)
        val state = project.service<RecorderState>()
        assertTrue(state.isActive)
        assertEquals("hw07", state.manifest?.assignmentId)
    }

    fun `test activity leaves state inactive when discoverer returns nothing`() = runBlocking {
        val state = project.service<RecorderState>()
        state.activate(root("stale"), manifest())
        val activity = RecorderActivationActivity { _, _ -> emptyList() }
        activity.execute(project)
        assertFalse(state.isActive)
        assertNull(state.manifest)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :recorder:test --tests "dev.provenance.recorder.activation.RecorderStateTest"`
Expected: FAIL (compile errors — `activate(Path, Manifest)`, `deactivateAll()`, `activeManifests`, and `DiscoveredManifest`-based `RecorderActivationActivity` constructor don't exist yet).

- [ ] **Step 3: Rewrite `RecorderState.kt`**

```kotlin
package dev.provenance.recorder.activation

import com.intellij.openapi.components.Service
import dev.provenance.core.Manifest
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-scoped activation state: every currently-verified assignment root and its manifest.
 * Consulted by RecordingStatusBarWidgetFactory to decide whether to show the "Provenance:
 * recording" widget, and by RecordingStatusBarWidget to render the assignment count.
 * PRD §4.1 / CLAUDE.md: activation is the privacy gate.
 */
@Service(Service.Level.PROJECT)
class RecorderState {
    private val active = ConcurrentHashMap<Path, Manifest>()

    val isActive: Boolean get() = active.isNotEmpty()

    /** Single-assignment convenience: the active manifest when exactly one assignment is
     * recording, else null (including when more than one is active — ambiguous). Multi-root
     * consumers should read [activeManifests] instead. */
    val manifest: Manifest? get() = active.values.singleOrNull()

    val activeManifests: Map<Path, Manifest> get() = active.toMap()

    fun activate(root: Path, m: Manifest) {
        active[root.normalize()] = m
    }

    fun deactivate(root: Path) {
        active.remove(root.normalize())
    }

    fun deactivateAll() {
        active.clear()
    }

    /** Back-compat alias used by existing tearDowns; clears every assignment. */
    fun deactivate() = deactivateAll()
}
```

- [ ] **Step 4: Do NOT run tests yet** — `RecorderActivationActivity` still has the old single-manifest constructor; Step 3's test file references the new `DiscoveredManifest`-based constructor. Proceed straight to updating the activity in this same task (it's the direct consumer of `RecorderState` and the two must land together to compile).

- [ ] **Step 5: Update `RecorderActivationActivity.kt`**

```kotlin
package dev.provenance.recorder.activation

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import dev.provenance.recorder.session.RecorderSessionManager
import dev.provenance.recorder.statusbar.RecordingStatusBarWidgetFactory
import java.nio.file.Paths

/**
 * Runs once per project open. PRD §4.1: activate only for workspaces whose manifest(s) verify;
 * otherwise do nothing observable. Discovers every nested verified manifest under the project
 * (recursive walk from the project dir + every content root) and starts one concurrent session
 * per discovered root, keyed by that root's resolved real path.
 *
 * [discoverer] is injectable for tests (via the internal secondary constructor); production
 * wires the real VFS-backed [discoverManifestRoots].
 */
class RecorderActivationActivity internal constructor(
    private val discoverer: (Project, String) -> List<DiscoveredManifest>,
) : ProjectActivity {

    constructor() : this(::discoverManifestRoots)

    override suspend fun execute(project: Project) {
        val discovered = discoverer(project, COURSE_PUBLIC_KEY_HEX)
        val state = project.service<RecorderState>()
        state.deactivateAll()
        val manager = project.service<RecorderSessionManager>()
        for (found in discovered) {
            // Activation state (the privacy gate / status bar) must not silently no-op just
            // because a real filesystem path can't be resolved (e.g. an in-memory test
            // fixture) — only *starting a session* additionally requires one.
            val resolvedRoot = runCatching { found.root.toNioPath() }.getOrNull()
                ?.let { runCatching { it.toRealPath() }.getOrDefault(it.normalize()) }
            val stateKey = resolvedRoot ?: Paths.get(found.root.path)
            state.activate(stateKey, found.manifest)
            if (resolvedRoot != null) {
                manager.startFromActivation(resolvedRoot, found.manifest)
            } else {
                LOG.info("discovered manifest at ${found.root.path} has no resolvable nio path; recording not started")
            }
        }
        refreshStatusBarWidget(project)
    }

    companion object {
        private val LOG = Logger.getInstance(RecorderActivationActivity::class.java)
    }
}

internal fun refreshStatusBarWidget(project: Project) {
    if (project.isDisposed) return
    project.service<StatusBarWidgetsManager>().updateWidget(RecordingStatusBarWidgetFactory::class.java)
}
```

Note: `manager.startFromActivation(root: Path, manifest: Manifest)` does not exist yet — it lands in Task 5. This file will not compile in isolation until Task 5 lands; that's expected (Tasks 2 and 6 both touch this file — see the note at the top of Task 6). For this task, temporarily stub the call as `manager.startFromActivation(resolvedRoot, found.manifest)` and skip running the full test suite; run only `RecorderStateTest` scoped to the parts that don't require a live session (the two `` `test activity ...` `` cases exercise `state`, not the manager) — actually, since `startFromActivation`'s signature must change for this to compile at all, **do this step together with Task 5's `RecorderSessionManager` change in the same working session before compiling**, or (simpler) temporarily change the call to the current single-arg `startFromActivation(manifest)` and revisit it as part of Task 6's own step. To keep this task's diff compilable and independently testable, use the *simpler* path: leave `RecorderActivationActivity` calling the OLD single-arg `startFromActivation(found.manifest)` for now (looping calls it once per root, each call still no-ops after the first since the old manager only supports one session) and land the full per-root `startFromActivation(root, manifest)` wiring in Task 6 once Task 5's registry exists. Adjust the snippet above: replace `manager.startFromActivation(resolvedRoot, found.manifest)` with `manager.startFromActivation(found.manifest)` for this task only.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :recorder:test --tests "dev.provenance.recorder.activation.RecorderStateTest"`
Expected: PASS (7 tests).

- [ ] **Step 7: Run the full recorder test suite to check for other breakage**

Run: `./gradlew :recorder:test`
Expected: `RecordingStatusBarWidgetFactoryTest` fails to compile (`state.activate(manifest())` — missing the `root` arg). Fix it now:

In `recorder/src/test/kotlin/dev/provenance/recorder/statusbar/RecordingStatusBarWidgetFactoryTest.kt`, change:
```kotlin
project.service<RecorderState>().activate(manifest())
```
to:
```kotlin
project.service<RecorderState>().activate(java.nio.file.Paths.get("/ws"), manifest())
```
and in its `tearDown()`, change `project.service<RecorderState>().deactivate()` — this still compiles (the no-arg alias is preserved) but leave as-is.

Run: `./gradlew :recorder:test`
Expected: PASS across the module.

- [ ] **Step 8: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/activation/RecorderState.kt recorder/src/main/kotlin/dev/provenance/recorder/activation/RecorderActivationActivity.kt recorder/src/test/kotlin/dev/provenance/recorder/activation/RecorderStateTest.kt recorder/src/test/kotlin/dev/provenance/recorder/statusbar/RecordingStatusBarWidgetFactoryTest.kt
git commit --no-gpg-sign -m "feat(recorder): RecorderState tracks a set of active assignment roots"
```

---

### Task 3: `SessionRouter`/`RecordableSessionSink` seam + `DocWiring`/`SelectionWiring` rewrite

**This is the "sharpest change" the design calls out for doc events.** Currently `DocWiring`/`SelectionWiring` are constructed fresh, once per session, by `RecordingSessionController.init` — each instance registers its own listener on the **global** `EditorFactory` multicaster (shared across the whole IDE), filtered only by that one session's `workspaceRoot`. With two *disjoint* roots (siblings like `cats/`/`hog/`) this happens to not double-fire, but with any nested/overlapping roots it would double-record an event to both sessions — violating "no event escapes its assignment root" / "no event double-recorded". The fix: construct `DocWiring`/`SelectionWiring` **once**, project-scoped, and have them resolve the *one* owning session per event via nearest-ancestor lookup, dropping the event if no session owns it (identical privacy-gate shape to `RecorderTerminalState`'s null callback today).

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/SessionRouter.kt`
- Modify: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/DocWiring.kt`
- Modify: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/SelectionWiring.kt`
- Modify (tests): `recorder/src/test/kotlin/dev/provenance/recorder/wiring/DocWiringTest.kt`, `recorder/src/test/kotlin/dev/provenance/recorder/wiring/paste/DocWiringPasteTest.kt`

**Interfaces:**
- Produces (new file `SessionRouter.kt`):
  ```kotlin
  interface RecordableSessionSink {
      val workspaceRoot: Path
      val pasteCorrelator: PasteCorrelator?
      fun onDocOpen(payload: DocOpenPayload)
      fun onDocChange(payload: DocChangePayload)
      fun onDocSave(payload: DocSavePayload)
      fun onDocClose(payload: DocClosePayload)
      fun onPaste(payload: PastePayload)
      fun onSelectionChange(payload: SelectionChangePayload)
  }
  interface SessionRouter {
      /** The nearest-enclosing session owning [nioPath] (must already have passed
       * isRecordablePath against that session's root/provenanceDir/manifestNames), or null. */
      fun sinkFor(nioPath: Path): RecordableSessionSink?
  }
  ```
- Consumes (Task 5): `RecorderSessionManager` implements `SessionRouter`; `RecordingSessionController` implements `RecordableSessionSink`.
- `DocWiring(project: Project, router: SessionRouter, parentDisposable: Disposable, localFsOf: (VirtualFile) -> Boolean = ..., nioPathOf: (VirtualFile) -> Path? = ...)`
- `SelectionWiring(router: SessionRouter, parentDisposable: Disposable, localFsOf: (VirtualFile) -> Boolean = ..., nioPathOf: (VirtualFile) -> Path? = ...)`

- [ ] **Step 1: Create the seam file**

```kotlin
package dev.provenance.recorder.wiring

import dev.provenance.core.DocChangePayload
import dev.provenance.core.DocClosePayload
import dev.provenance.core.DocOpenPayload
import dev.provenance.core.DocSavePayload
import dev.provenance.core.PastePayload
import dev.provenance.core.SelectionChangePayload
import dev.provenance.recorder.paste.PasteCorrelator
import java.nio.file.Path

/**
 * What a single owning recording session exposes to the project-scoped DocWiring/
 * SelectionWiring routers. Implemented by RecordingSessionController (session package):
 * this interface lives in `wiring` (not `session`) so DocWiring/SelectionWiring — themselves
 * in `wiring` — don't need to depend on the `session` package.
 */
interface RecordableSessionSink {
    val workspaceRoot: Path
    val pasteCorrelator: PasteCorrelator?
    fun onDocOpen(payload: DocOpenPayload)
    fun onDocChange(payload: DocChangePayload)
    fun onDocSave(payload: DocSavePayload)
    fun onDocClose(payload: DocClosePayload)
    fun onPaste(payload: PastePayload)
    fun onSelectionChange(payload: SelectionChangePayload)
}

/**
 * Resolves the one session (if any) that owns a given file path, by nearest-ancestor verified
 * manifest root. Implemented by RecorderSessionManager against its live session registry.
 * Returning null is the router's privacy gate: no owner ⇒ nothing is recorded for that path,
 * mirroring RecorderTerminalState/RecorderGitState's null-callback gate.
 */
interface SessionRouter {
    fun sinkFor(nioPath: Path): RecordableSessionSink?
}
```

- [ ] **Step 2: Write the failing test for `DocWiring`**

Replace `DocWiringTest.kt` in full:

```kotlin
package dev.provenance.recorder.wiring

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.DocChangePayload
import dev.provenance.core.DocClosePayload
import dev.provenance.core.DocOpenPayload
import dev.provenance.core.DocSavePayload
import dev.provenance.core.PastePayload
import dev.provenance.core.SelectionChangePayload
import dev.provenance.core.Sha256
import dev.provenance.recorder.paste.PasteCorrelator
import java.nio.file.Path
import java.nio.file.Paths

class DocWiringTest : BasePlatformTestCase() {
    private val opens = mutableListOf<DocOpenPayload>()
    private val changes = mutableListOf<DocChangePayload>()
    private val saves = mutableListOf<DocSavePayload>()
    private val closes = mutableListOf<DocClosePayload>()

    private val workspaceRoot: Path = Paths.get("/ws")

    private class FakeSink(
        override val workspaceRoot: Path,
        override val pasteCorrelator: PasteCorrelator? = null,
        val opens: MutableList<DocOpenPayload>,
        val changes: MutableList<DocChangePayload>,
        val saves: MutableList<DocSavePayload>,
        val closes: MutableList<DocClosePayload>,
    ) : RecordableSessionSink {
        override fun onDocOpen(payload: DocOpenPayload) { opens.add(payload) }
        override fun onDocChange(payload: DocChangePayload) { changes.add(payload) }
        override fun onDocSave(payload: DocSavePayload) { saves.add(payload) }
        override fun onDocClose(payload: DocClosePayload) { closes.add(payload) }
        override fun onPaste(payload: dev.provenance.core.PastePayload) = Unit
        override fun onSelectionChange(payload: SelectionChangePayload) = Unit
    }

    private fun fakeSink(pasteCorrelator: PasteCorrelator? = null) =
        FakeSink(workspaceRoot, pasteCorrelator, opens, changes, saves, closes)

    /** Owns everything under /ws; nothing else. Mirrors a single active session. */
    private fun routerOwningWs(pasteCorrelator: PasteCorrelator? = null): SessionRouter {
        val sink = fakeSink(pasteCorrelator)
        return SessionRouter { path -> if (path.startsWith(workspaceRoot)) sink else null }
    }

    private fun install(router: SessionRouter = routerOwningWs()) = DocWiring(
        project = project,
        router = router,
        parentDisposable = testRootDisposable,
        // Light-fixture files are not on the local FS; map them into the workspace by name.
        localFsOf = { true },
        nioPathOf = { vf -> workspaceRoot.resolve(vf.name) },
    )

    private fun document(): Document = myFixture.getDocument(myFixture.file)

    fun testDocOpenEmittedForAlreadyOpenFileViaCatchUp() {
        myFixture.configureByText("hw.py", "print(1)\n")
        install()
        assertEquals(1, opens.size)
        val open = opens[0]
        assertEquals("hw.py", open.path)
        assertEquals("print(1)\n", open.content)
        assertEquals(Sha256.hex("print(1)\n"), open.sha256)
    }

    fun testDocChangeEmitsSingleDeltaWithInsertedText() {
        myFixture.configureByText("hw.py", "print(1)\n")
        install()
        val doc = document()
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(3, "X") }
        assertEquals(1, changes.size)
        val c = changes[0]
        assertEquals("hw.py", c.path)
        assertEquals("typed", c.source)
        assertEquals(1, c.deltas.size)
        assertEquals("X", c.deltas[0].text)
    }

    fun testDocChangeRangeIsPreChangeCoordinates() {
        myFixture.configureByText("hw.py", "print(1)\n")
        install()
        val doc = document()
        WriteCommandAction.runWriteCommandAction(project) { doc.replaceString(0, 5, "say") }
        val d = changes[0].deltas[0]
        assertEquals(0L, d.range.start.line)
        assertEquals(0L, d.range.start.character)
        assertEquals(0L, d.range.end.line)
        assertEquals(5L, d.range.end.character)
        assertEquals("say", d.text)
    }

    fun testDocSaveEmitsHashOfSavedContent() {
        myFixture.configureByText("hw.py", "print(1)\n")
        install()
        val doc = document()
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "print(2)\n") }
        val finalText = doc.text
        WriteCommandAction.runWriteCommandAction(project) { FileDocumentManager.getInstance().saveDocument(doc) }
        assertTrue("expected at least one save", saves.isNotEmpty())
        assertEquals(Sha256.hex(finalText), saves.last().sha256)
        assertEquals("hw.py", saves.last().path)
    }

    fun testNoOwningSessionEmitsNothing() {
        myFixture.configureByText("hw.py", "print(1)\n")
        // Router with no owner at all — every path is dropped, exactly like a file outside
        // every assignment root.
        install(router = SessionRouter { null })
        val doc = document()
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(0, "Z") }
        assertTrue(opens.isEmpty())
        assertTrue(changes.isEmpty())
    }

    fun testDocCloseEmittedOnFileClose() {
        myFixture.configureByText("hw.py", "print(1)\n")
        val vf: VirtualFile = myFixture.file.virtualFile
        install()
        FileEditorManager.getInstance(project).closeFile(vf)
        assertEquals(1, closes.size)
        assertEquals("hw.py", closes[0].path)
    }

    fun testTwoFilesWithTheSameRelativeNameInDifferentRootsBothGetDocOpen() {
        // Regression for the shared-listener de-dup bug: seenPaths must be keyed by absolute
        // path, not by relative path, or the second root's same-named file would be
        // (wrongly) treated as already-seen and silently dropped.
        val otherRoot = Paths.get("/ws-other")
        val vfA = myFixture.addFileToProject("a/hw.py", "print('a')\n").virtualFile
        val vfB = myFixture.addFileToProject("b/hw.py", "print('b')\n").virtualFile
        val sinkA = fakeSink()
        val opensB = mutableListOf<DocOpenPayload>()
        val sinkB = FakeSink(otherRoot, null, opensB, mutableListOf(), mutableListOf(), mutableListOf())
        val router = SessionRouter { path ->
            when {
                path.startsWith(workspaceRoot) -> sinkA
                path.startsWith(otherRoot) -> sinkB
                else -> null
            }
        }
        DocWiring(
            project = project,
            router = router,
            parentDisposable = testRootDisposable,
            localFsOf = { true },
            nioPathOf = { vf -> if (vf == vfA) workspaceRoot.resolve("hw.py") else otherRoot.resolve("hw.py") },
        )
        assertEquals(1, opens.size)
        assertEquals(1, opensB.size)
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :recorder:test --tests "dev.provenance.recorder.wiring.DocWiringTest"`
Expected: FAIL (compile error — `DocWiring`'s constructor doesn't have `router`/`SessionRouter` yet).

- [ ] **Step 4: Rewrite `DocWiring.kt`**

```kotlin
package dev.provenance.recorder.wiring

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.provenance.core.Position
import dev.provenance.core.Range
import dev.provenance.core.Sha256
import dev.provenance.recorder.events.buildDocChangeDelta
import dev.provenance.recorder.events.buildDocChangePayload
import dev.provenance.recorder.events.buildDocClosePayload
import dev.provenance.recorder.events.buildDocOpenPayload
import dev.provenance.recorder.events.buildDocSavePayload
import dev.provenance.recorder.paste.PasteDecision
import dev.provenance.recorder.paste.toPastePayload
import java.nio.file.Path
import java.util.WeakHashMap

/**
 * doc.open/change/save/close wiring (recorder PRD §4.2). Registered ONCE, project-scoped
 * (constructed by RecorderSessionManager, not per-session — see design.md's nested-manifest
 * discovery plan): a single global DocumentListener + FileEditorManagerListener +
 * FileDocumentManagerListener, each resolving the *one* owning session per event via
 * [router], and dropping the event when no session owns the path. This is what makes
 * "no event escapes its assignment root" hold even for overlapping/nested roots — a per-
 * session listener filtered only by "is this under my root" would double-fire for a file
 * whose nearest ancestor differs from a farther, also-matching ancestor.
 *
 * [localFsOf]/[nioPathOf] are injectable so the transform is testable under a light fixture
 * whose files are not on the local file system; production uses the real VirtualFile checks.
 */
class DocWiring(
    private val project: Project,
    private val router: SessionRouter,
    parentDisposable: Disposable,
    private val localFsOf: (VirtualFile) -> Boolean = { it.isInLocalFileSystem },
    private val nioPathOf: (VirtualFile) -> Path? = { runCatching { it.toNioPath() }.getOrNull() },
) {
    private val pending = WeakHashMap<Document, Range>()
    // Keyed by absolute nio path, NOT relative path: two different owning roots can each have
    // a file with the same relative name (e.g. "hw.py" under both cats/ and hog/), and a
    // relative-path key would wrongly treat the second as already-seen.
    private val seenPaths = mutableSetOf<Path>()

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun beforeDocumentChange(event: DocumentEvent) {
                    val vf = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (sinkFor(vf) == null) return
                    pending[event.document] = rangeOf(event.document, event.offset, event.oldLength)
                }

                override fun documentChanged(event: DocumentEvent) {
                    val vf = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    val sink = sinkFor(vf) ?: return
                    val range = pending.remove(event.document) ?: return
                    val delta = buildDocChangeDelta(
                        range.start.line, range.start.character,
                        range.end.line, range.end.character,
                        event.newFragment.toString(),
                    )
                    val path = relativePath(vf, sink.workspaceRoot)
                    val correlator = sink.pasteCorrelator
                    if (correlator == null) {
                        sink.onDocChange(buildDocChangePayload(path, delta))
                        return
                    }
                    when (val decision = correlator.onDocChange(listOf(delta))) {
                        is PasteDecision.EmitPaste -> sink.onPaste(decision.fields.toPastePayload(path, decision.range))
                        is PasteDecision.EmitDocChange -> sink.onDocChange(buildDocChangePayload(path, delta, decision.source))
                    }
                }
            },
            parentDisposable,
        )

        project.messageBus.connect(parentDisposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    val sink = sinkFor(file) ?: return
                    emitDocOpenFor(file, sink)
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    val sink = sinkFor(file) ?: return
                    sink.onDocClose(buildDocClosePayload(relativePath(file, sink.workspaceRoot)))
                }
            },
        )

        project.messageBus.connect(parentDisposable).subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    val vf = FileDocumentManager.getInstance().getFile(document) ?: return
                    val sink = sinkFor(vf) ?: return
                    sink.onDocSave(buildDocSavePayload(relativePath(vf, sink.workspaceRoot), Sha256.hex(document.text)))
                }
            },
        )

        // Catch-up: files already open when wiring starts never fire fileOpened.
        for (vf in FileEditorManager.getInstance(project).openFiles) {
            val sink = sinkFor(vf) ?: continue
            emitDocOpenFor(vf, sink)
        }
    }

    private fun sinkFor(vf: VirtualFile): RecordableSessionSink? {
        if (!localFsOf(vf)) return null
        val path = nioPathOf(vf) ?: return null
        return router.sinkFor(path)
    }

    private fun emitDocOpenFor(vf: VirtualFile, sink: RecordableSessionSink) {
        val path = nioPathOf(vf) ?: return
        if (!seenPaths.add(path)) return // defensive de-dup, keyed by absolute path
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return
        val text = doc.text
        sink.onDocOpen(buildDocOpenPayload(relativePath(vf, sink.workspaceRoot), Sha256.hex(text), doc.lineCount.toLong(), text))
    }

    private fun relativePath(vf: VirtualFile, workspaceRoot: Path): String {
        val nio = nioPathOf(vf) ?: return vf.name
        return runCatching { workspaceRoot.normalize().relativize(nio.normalize()).toString().replace('\\', '/') }
            .getOrDefault(vf.name)
    }

    private fun rangeOf(document: Document, offset: Int, length: Int): Range {
        val startLine = document.getLineNumber(offset)
        val startChar = offset - document.getLineStartOffset(startLine)
        val endOffset = offset + length
        val endLine = document.getLineNumber(endOffset)
        val endChar = endOffset - document.getLineStartOffset(endLine)
        return Range(Position(startLine.toLong(), startChar.toLong()), Position(endLine.toLong(), endChar.toLong()))
    }
}
```

Note the manifest-name/`.provenance`/`.idea` exclusions (previously `isRecordablePath` called directly inside DocWiring) are now applied **inside** `router.sinkFor` (Task 5's `RecorderSessionManager.sinkFor` implementation) rather than in DocWiring itself — DocWiring only needs to know "does *some* session own this path," not re-derive the exclusion rules per-root.

- [ ] **Step 5: Run to verify `DocWiringTest` passes**

Run: `./gradlew :recorder:test --tests "dev.provenance.recorder.wiring.DocWiringTest"`
Expected: PASS (8 tests).

- [ ] **Step 6: Update `DocWiringPasteTest.kt`**

Replace in full:

```kotlin
package dev.provenance.recorder.wiring.paste

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.DocChangePayload
import dev.provenance.core.DocClosePayload
import dev.provenance.core.DocOpenPayload
import dev.provenance.core.DocSavePayload
import dev.provenance.core.PastePayload
import dev.provenance.core.SelectionChangePayload
import dev.provenance.recorder.paste.PasteCorrelator
import dev.provenance.recorder.wiring.DocWiring
import dev.provenance.recorder.wiring.RecordableSessionSink
import dev.provenance.recorder.wiring.SessionRouter
import java.nio.file.Path
import java.nio.file.Paths

class DocWiringPasteTest : BasePlatformTestCase() {
    private val changes = mutableListOf<DocChangePayload>()
    private val pastes = mutableListOf<PastePayload>()
    private val workspaceRoot: Path = Paths.get("/ws")
    private var now = 0L
    private lateinit var correlator: PasteCorrelator

    private class FakeSink(
        override val workspaceRoot: Path,
        override val pasteCorrelator: PasteCorrelator?,
        val changes: MutableList<DocChangePayload>,
        val pastes: MutableList<PastePayload>,
    ) : RecordableSessionSink {
        override fun onDocOpen(payload: DocOpenPayload) = Unit
        override fun onDocChange(payload: DocChangePayload) { changes.add(payload) }
        override fun onDocSave(payload: DocSavePayload) = Unit
        override fun onDocClose(payload: DocClosePayload) = Unit
        override fun onPaste(payload: PastePayload) { pastes.add(payload) }
        override fun onSelectionChange(payload: SelectionChangePayload) = Unit
    }

    private fun install() {
        correlator = PasteCorrelator(getNow = { now })
        val sink = FakeSink(workspaceRoot, correlator, changes, pastes)
        DocWiring(
            project = project,
            router = SessionRouter { path -> if (path.startsWith(workspaceRoot)) sink else null },
            parentDisposable = testRootDisposable,
            localFsOf = { true },
            nioPathOf = { vf -> workspaceRoot.resolve(vf.name) },
        )
    }

    private fun document(): Document = myFixture.getDocument(myFixture.file)

    fun testTypedSmallInsertEmitsExactlyOneDocChangeAndNoPaste() {
        myFixture.configureByText("hw.py", "print(1)\n")
        install()
        WriteCommandAction.runWriteCommandAction(project) { document().insertString(3, "X") }
        assertEquals(1, changes.size)
        assertEquals("typed", changes[0].source)
        assertTrue(pastes.isEmpty())
    }

    fun testLargePasteShapedInsertEmitsExactlyOnePasteAndNoDocChange() {
        myFixture.configureByText("hw.py", "print(1)\n")
        install()
        val payload = "y".repeat(40)
        WriteCommandAction.runWriteCommandAction(project) { document().insertString(3, payload) }
        assertEquals("expected exactly one paste event", 1, pastes.size)
        assertTrue("no doc.change should be double-logged for a paste", changes.isEmpty())
        assertEquals("hw.py", pastes[0].path)
        assertEquals(40L, pastes[0].length)
        assertEquals(payload, pastes[0].content)
    }
}
```

- [ ] **Step 7: Run to verify `DocWiringPasteTest` passes**

Run: `./gradlew :recorder:test --tests "dev.provenance.recorder.wiring.paste.DocWiringPasteTest"`
Expected: PASS (2 tests).

- [ ] **Step 8: Rewrite `SelectionWiring.kt` the same way**

```kotlin
package dev.provenance.recorder.wiring

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import dev.provenance.recorder.events.buildSelectionChangePayload
import java.nio.file.Path
import java.util.WeakHashMap

/**
 * selection.change wiring (recorder PRD §4.2). Registered ONCE, project-scoped, mirroring
 * DocWiring's router-based rewrite for the same reason: a per-session listener filtered only
 * by "is this under my root" would double-fire for overlapping/nested assignment roots.
 */
class SelectionWiring(
    private val router: SessionRouter,
    parentDisposable: Disposable,
    private val localFsOf: (VirtualFile) -> Boolean = { it.isInLocalFileSystem },
    private val nioPathOf: (VirtualFile) -> Path? = { runCatching { it.toNioPath() }.getOrNull() },
) {
    private val lastEmitted = WeakHashMap<Editor, dev.provenance.core.SelectionChangePayload>()

    init {
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) = handle(event.editor)
            },
            parentDisposable,
        )
        multicaster.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(event: SelectionEvent) = handle(event.editor)
            },
            parentDisposable,
        )
    }

    private fun handle(editor: Editor) {
        val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!localFsOf(vf)) return
        val path = nioPathOf(vf) ?: return
        val sink = router.sinkFor(path) ?: return

        val caret = editor.caretModel.primaryCaret
        val wasSelection = caret.hasSelection()
        val startPos = if (wasSelection) editor.offsetToLogicalPosition(caret.selectionStart) else caret.logicalPosition
        val endPos = if (wasSelection) editor.offsetToLogicalPosition(caret.selectionEnd) else caret.logicalPosition

        val relPath = runCatching {
            sink.workspaceRoot.normalize().relativize(path.normalize()).toString().replace('\\', '/')
        }.getOrDefault(vf.name)

        val payload = buildSelectionChangePayload(
            path = relPath,
            startLine = startPos.line.toLong(),
            startChar = startPos.column.toLong(),
            endLine = endPos.line.toLong(),
            endChar = endPos.column.toLong(),
            wasSelection = wasSelection,
        )

        if (lastEmitted[editor] == payload) return
        lastEmitted[editor] = payload
        sink.onSelectionChange(payload)
    }
}
```

There is no pre-existing dedicated `SelectionWiringTest`; selection.change coverage today comes only through the manager-level heavy gates. Leave it that way (no new test file required by this task — Task 9's end-to-end suite exercises it live), but do add one focused unit test to keep parity with DocWiring's coverage:

Create `recorder/src/test/kotlin/dev/provenance/recorder/wiring/SelectionWiringTest.kt`:

```kotlin
package dev.provenance.recorder.wiring

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.DocChangePayload
import dev.provenance.core.DocClosePayload
import dev.provenance.core.DocOpenPayload
import dev.provenance.core.DocSavePayload
import dev.provenance.core.PastePayload
import dev.provenance.core.SelectionChangePayload
import dev.provenance.recorder.paste.PasteCorrelator
import java.nio.file.Path
import java.nio.file.Paths

class SelectionWiringTest : BasePlatformTestCase() {
    private val workspaceRoot: Path = Paths.get("/ws")
    private val changes = mutableListOf<SelectionChangePayload>()

    private class FakeSink(override val workspaceRoot: Path, val changes: MutableList<SelectionChangePayload>) : RecordableSessionSink {
        override val pasteCorrelator: PasteCorrelator? = null
        override fun onDocOpen(payload: DocOpenPayload) = Unit
        override fun onDocChange(payload: DocChangePayload) = Unit
        override fun onDocSave(payload: DocSavePayload) = Unit
        override fun onDocClose(payload: DocClosePayload) = Unit
        override fun onPaste(payload: PastePayload) = Unit
        override fun onSelectionChange(payload: SelectionChangePayload) { changes.add(payload) }
    }

    fun testCaretMoveEmitsSelectionChangeForAnOwnedFile() {
        myFixture.configureByText("hw.py", "print(1)\nprint(2)\n")
        val sink = FakeSink(workspaceRoot, changes)
        SelectionWiring(
            router = SessionRouter { path -> if (path.startsWith(workspaceRoot)) sink else null },
            parentDisposable = testRootDisposable,
            localFsOf = { true },
            nioPathOf = { vf -> workspaceRoot.resolve(vf.name) },
        )
        val editor = myFixture.editor
        WriteCommandAction.runWriteCommandAction(project) { editor.caretModel.moveToOffset(5) }
        assertTrue("expected at least one selection.change", changes.isNotEmpty())
        assertEquals("hw.py", changes.last().path)
    }

    fun testNoOwningSessionEmitsNothing() {
        myFixture.configureByText("hw.py", "print(1)\n")
        SelectionWiring(
            router = SessionRouter { null },
            parentDisposable = testRootDisposable,
            localFsOf = { true },
            nioPathOf = { vf -> workspaceRoot.resolve(vf.name) },
        )
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.caretModel.moveToOffset(3) }
        assertTrue(changes.isEmpty())
    }
}
```

- [ ] **Step 9: Run all wiring tests**

Run: `./gradlew :recorder:test --tests "dev.provenance.recorder.wiring.*"`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/wiring/SessionRouter.kt recorder/src/main/kotlin/dev/provenance/recorder/wiring/DocWiring.kt recorder/src/main/kotlin/dev/provenance/recorder/wiring/SelectionWiring.kt recorder/src/test/kotlin/dev/provenance/recorder/wiring/DocWiringTest.kt recorder/src/test/kotlin/dev/provenance/recorder/wiring/paste/DocWiringPasteTest.kt recorder/src/test/kotlin/dev/provenance/recorder/wiring/SelectionWiringTest.kt
git commit --no-gpg-sign -m "refactor(recorder): route doc/selection events to the owning session by nearest-ancestor path"
```

Note: this task lands `DocWiring`/`SelectionWiring` against the *interface*; nothing constructs them from production code yet (that's `RecordingSessionController`/`RecorderSessionManager` today, and moves to `RecorderSessionManager` in Task 5). Expect `RecordingSessionController.kt` to fail to compile after this task if left untouched (it still calls the old `DocWiring(...)`/`SelectionWiring(...)` constructors) — **do this task and Task 5 in the same sitting before running the full module build**, or, if landing them as strictly separate commits, temporarily leave `RecordingSessionController`'s old calls in a non-compiling state between Task 3's commit and Task 5's commit (acceptable given they are two halves of one interdependent change — flag this in the task handoff so the reviewer isn't surprised by a red build between the two commits).

---

### Task 4: `RecorderTerminalState`/`RecorderGitState` become path-aware routers

**Files:**
- Modify: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/RecorderTerminalState.kt`
- Modify: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/RecorderGitState.kt`
- Modify: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/terminal/TerminalWiringStartupActivity.kt`
- Modify: `recorder/src/main/kotlin/dev/provenance/recorder/wiring/git/GitWiringStartupActivity.kt`

**Interfaces:**
- Produces: `RecorderTerminalState.emitTerminalOpen: ((cwd: Path?, TerminalOpenPayload) -> Unit)?`, `RecorderTerminalState.emitTerminalCommand: ((cwd: Path?, TerminalCommandPayload) -> Unit)?`, `RecorderGitState.emit: ((repoRoot: Path?, GitEventPayload) -> Unit)?`. Consumed by Task 5 (`RecorderSessionManager` installs the router into these seams).
- No wire-format change: `TerminalOpenPayload`/`TerminalCommandPayload`/`GitEventPayload` (in `core/`) are untouched. The path is a routing key passed **alongside** the payload, never added to it.

There is no dedicated unit test for these two state holders or their startup activities today (they need the optional Terminal/Git4Idea plugins loaded, which only the Heavy/e2e gates in `recorder/src/test/kotlin/dev/provenance/recorder/` exercise). This task is a mechanical signature change; its correctness is proven by Task 9's end-to-end routing tests and by the pre-existing `GitExternalChangeGateTest`, which must still pass unmodified after this change (it drives a real Git4Idea repository and asserts a `git.event` reaches the (single) session's log — a strong regression check that `repository.root` resolves correctly).

- [ ] **Step 1: Update `RecorderTerminalState.kt`**

```kotlin
package dev.provenance.recorder.wiring

import com.intellij.openapi.components.Service
import dev.provenance.core.TerminalCommandPayload
import dev.provenance.core.TerminalOpenPayload
import java.nio.file.Path

/**
 * Project-scoped emit seam for terminal.open / terminal.command. [cwd] is the terminal's
 * working directory (when resolvable), used by the installed router to find the owning
 * session by nearest-ancestor; null routes to no owner (dropped, never guessed).
 *
 * References ONLY core payload types (no terminal-plugin types), so it is safe on the main
 * plugin.xml load path. The gated TerminalWiringStartupActivity (registered only via
 * provjet-terminal.xml) resolves this service and reads the emit callbacks: null until at
 * least one session is active (privacy gate), nothing recorded while null. RecorderSessionManager
 * installs the router once, for as long as at least one session is active, and clears it back to
 * null when the last session stops.
 */
@Service(Service.Level.PROJECT)
class RecorderTerminalState {
    @Volatile
    var emitTerminalOpen: ((cwd: Path?, TerminalOpenPayload) -> Unit)? = null

    @Volatile
    var emitTerminalCommand: ((cwd: Path?, TerminalCommandPayload) -> Unit)? = null
}
```

- [ ] **Step 2: Update `RecorderGitState.kt`**

```kotlin
package dev.provenance.recorder.wiring

import com.intellij.openapi.components.Service
import dev.provenance.core.GitEventPayload
import java.nio.file.Path

/**
 * Project-scoped emit seam for git.event. [repoRoot] is the Git4Idea repository's working-tree
 * root, used by the installed router to find the owning session by nearest-ancestor; null
 * routes to no owner (dropped).
 */
@Service(Service.Level.PROJECT)
class RecorderGitState {
    @Volatile
    var emit: ((repoRoot: Path?, GitEventPayload) -> Unit)? = null
}
```

- [ ] **Step 3: Update `GitWiringStartupActivity.kt`'s call site**

Change the listener body from:
```kotlin
GitRepositoryChangeListener { repository ->
    val emit = state.emit ?: return@GitRepositoryChangeListener
    emit(GitEventPayload(operation = "state_change", commitSha = repository.currentRevision))
}
```
to:
```kotlin
GitRepositoryChangeListener { repository ->
    val emit = state.emit ?: return@GitRepositoryChangeListener
    val repoRoot = runCatching { repository.root.toNioPath() }.getOrNull()
    emit(repoRoot, GitEventPayload(operation = "state_change", commitSha = repository.currentRevision))
}
```
(`GitRepository.root: VirtualFile` is a stable, long-standing platform API — part of `com.intellij.dvcs.repo.Repository`, not experimental.)

- [ ] **Step 4: Update `TerminalWiringStartupActivity.kt`'s call sites**

Inside `terminalViewCreated`'s coroutine, resolve the cwd once (reusing the already-awaited `startupOptionsDeferred`) and pass it to both emit calls:

```kotlin
view.coroutineScope.launch {
    val shellIntegration =
        withTimeoutOrNull(SHELL_INTEGRATION_TIMEOUT_MS) { view.shellIntegrationDeferred.await() }
    val shell = shellNameOf(view)
    // VERIFY against the real SDK (see this file's existing EXPERIMENTAL-API RISK note):
    // startupOptionsDeferred resolves a ShellStartupOptions-shaped object that already
    // exposes `.shellCommand` (used above); it is expected to also expose the working
    // directory the shell was launched in. If the exact accessor/type differs once compiled
    // against the real platform jar, adjust the line below to match it — but if NO such
    // accessor exists on the resolved type, resolve cwd = null (never invent a directory);
    // null routes to "no owner" per this plan's locked design, which is exactly the
    // documented, tested fallback (see GitExternalChangeGateTest-style routing tests).
    val cwd: java.nio.file.Path? = runCatching {
        view.startupOptionsDeferred.await().workingDirectory?.let(java.nio.file.Paths::get)
    }.getOrNull()

    state.emitTerminalOpen?.invoke(
        cwd,
        TerminalOpenPayload(terminalId = terminalId, shell = shell, shellIntegration = shellIntegration != null),
    )

    shellIntegration?.addCommandExecutionListener(
        connection,
        object : TerminalCommandExecutionListener {
            override fun commandFinished(event: TerminalCommandFinishedEvent) {
                val block = event.commandBlock
                state.emitTerminalCommand?.invoke(
                    cwd,
                    TerminalCommandPayload(terminalId = terminalId, command = block.executedCommand ?: "", exitCode = block.exitCode),
                )
            }
        },
    )
}
```

- [ ] **Step 5: Compile-check (this module can't run its own unit tests for these two files; verify via the module build once Task 5 lands, since `RecorderSessionManager` is the only production assigner of these callbacks)**

Run: `./gradlew :recorder:compileKotlin`
Expected: compiles (these two files have no other callers yet besides Task 5's upcoming `RecorderSessionManager`; if Task 5 hasn't landed yet, expect the *old* `wireTerminalAndGit` call site in `RecorderSessionManager.kt` to fail to compile against the new two-arg callback types — that's expected and resolved by Task 5).

- [ ] **Step 6: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/wiring/RecorderTerminalState.kt recorder/src/main/kotlin/dev/provenance/recorder/wiring/RecorderGitState.kt recorder/src/main/kotlin/dev/provenance/recorder/wiring/terminal/TerminalWiringStartupActivity.kt recorder/src/main/kotlin/dev/provenance/recorder/wiring/git/GitWiringStartupActivity.kt
git commit --no-gpg-sign -m "refactor(recorder): terminal/git emit seams carry a routing path"
```

---

### Task 5: `RecorderSessionManager` registry + `RecordingSessionController` sink (highest-risk task — use `opus`)

**This is the capstone task.** It converts the single `var activeSession` into a `Map<Path, ActiveSession>`, wires the project-scoped `DocWiring`/`SelectionWiring`/terminal-router/git-router (Tasks 3 & 4) into the live registry, and makes `RecordingSessionController` implement `RecordableSessionSink`. Everything from Tasks 1–4 was preparation for this; nothing in Tasks 1–4 is exercised by production code until this task lands.

**Files:**
- Modify: `recorder/src/main/kotlin/dev/provenance/recorder/session/RecorderSessionManager.kt`
- Modify: `recorder/src/main/kotlin/dev/provenance/recorder/session/RecordingSessionController.kt`
- Modify (tests): `recorder/src/test/kotlin/dev/provenance/recorder/session/RecorderSessionManagerTest.kt`

**Interfaces:**
- Produces: `RecorderSessionManager.activeSessions: Map<Path, ActiveSession>`, `RecorderSessionManager.activeSession: ActiveSession?` (back-compat: the session when exactly one is active, else null — preserves every existing single-session test/call site unchanged), `RecorderSessionManager.start(activated, recovery, ideVersion, platform, recorderVersion, recorderExtensionId, clock, scheduler, vfsDispatch): ActiveSession` (same as before, **minus** the `localFsOf`/`nioPathOf` params — no longer needed once `DocWiring`/`SelectionWiring` move out of the controller), `RecorderSessionManager.startFromActivation(root: Path, manifest: Manifest): Unit` (was `startFromActivation(manifest: Manifest)` — signature changes), `RecorderSessionManager.stop(root: Path? = null)` (no-arg = stop all, preserves every existing `manager.stop()` call site), `RecorderSessionManager.sealSession(root: Path, ...): SealResult`, `RecorderSessionManager.sealActiveSession(...): SealResult` (back-compat single-session convenience), `RecorderSessionManager.rootOwning(path: Path): Path?`, `RecorderSessionManager.localFsOfOverride`/`nioPathOfOverride: @TestOnly @Volatile var` (test seams for the now project-scoped `DocWiring`/`SelectionWiring`). `RecorderSessionManager` implements `SessionRouter` (Task 3). `RecordingSessionController` implements `RecordableSessionSink` (Task 3) and drops its `localFsOf`/`nioPathOf` constructor params.
- Consumed by: Task 6 (`RecorderActivationActivity.startFromActivation(root, manifest)`), Task 7 (`PrepareSubmissionBundleAction` uses `activeSessions`, `sealSession`, `rootOwning`).

- [ ] **Step 1: Update `RecorderSessionManagerTest.kt` first (TDD — these will fail against the old manager)**

Replace the file in full:

```kotlin
package dev.provenance.recorder.session

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.FixedClock
import dev.provenance.core.GitEventPayload
import dev.provenance.core.Manifest
import dev.provenance.core.ParseResult
import dev.provenance.core.TerminalOpenPayload
import dev.provenance.core.parseEntries
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.startup.RecoveryDecision
import dev.provenance.recorder.wiring.RecorderGitState
import dev.provenance.recorder.wiring.RecorderTerminalState
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture

class RecorderSessionManagerTest : BasePlatformTestCase() {
    private class NoopScheduler : FlushScheduler {
        override fun scheduleAtFixedRate(periodMs: Long, task: Runnable): ScheduledFuture<*> =
            object : ScheduledFuture<Any?> {
                override fun cancel(m: Boolean) = true
                override fun isCancelled() = false
                override fun isDone() = false
                override fun get(): Any? = null
                override fun get(t: Long, u: java.util.concurrent.TimeUnit): Any? = null
                override fun getDelay(u: java.util.concurrent.TimeUnit) = 0L
                override fun compareTo(other: java.util.concurrent.Delayed?) = 0
            }
    }

    private lateinit var wsRoot: Path
    private lateinit var provDir: Path
    private lateinit var wsRoot2: Path
    private lateinit var provDir2: Path

    override fun setUp() {
        super.setUp()
        wsRoot = Files.createTempDirectory("mgr-ws")
        provDir = wsRoot.resolve(".provenance")
        wsRoot2 = Files.createTempDirectory("mgr-ws2")
        provDir2 = wsRoot2.resolve(".provenance")
    }

    override fun tearDown() {
        try {
            runCatching { project.service<RecorderSessionManager>().stop() }
            wsRoot.toFile().deleteRecursively()
            wsRoot2.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun manifest(id: String = "hw03", root: Path = wsRoot) = Manifest(id, "fa26", "2026-07-14T00:00:00Z", listOf("hw.py"), "ab".repeat(64))

    private fun manager() = project.service<RecorderSessionManager>()

    /** Installs the test fs-seams BEFORE the first start() of this manager instance — the
     * project-scoped DocWiring/SelectionWiring are constructed lazily on the first start()
     * and read these overrides at that point. */
    private fun installFsSeams(m: RecorderSessionManager) {
        m.localFsOfOverride = { true }
        m.nioPathOfOverride = { vf -> if (vf.name == "hw.py") wsRoot.resolve(vf.name) else wsRoot2.resolve(vf.name) }
    }

    private fun start(m: RecorderSessionManager, root: Path = wsRoot, provDir: Path = this.provDir, assignmentId: String = "hw03"): RecorderSessionManager.ActiveSession =
        m.start(
            activated = ActivatedWorkspace(manifest(assignmentId, root), provDir, root),
            recovery = RecoveryDecision.CleanStart,
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "com.aaryanmehta.provenance.recorder",
            clock = FixedClock(0),
            scheduler = NoopScheduler(),
        )

    private fun kinds(session: RecorderSessionManager.ActiveSession): List<String> {
        session.controller.flush()
        val text = String(Files.readAllBytes(session.controller.slogPath), Charsets.UTF_8)
        return (parseEntries(text) as ParseResult.Ok).entries.map { it.kind }
    }

    fun testStartOpensSessionAndSetsSeams() {
        val m = manager()
        val session = start(m)
        assertSame(session, m.activeSession)
        assertEquals("session.start", kinds(session).first())
        assertNotNull(project.service<RecorderTerminalState>().emitTerminalOpen)
        assertNotNull(project.service<RecorderGitState>().emit)
    }

    fun testCoordinatorEventsReachTheSlog() {
        val m = manager()
        val session = start(m)

        project.service<RecorderTerminalState>().emitTerminalOpen!!.invoke(
            wsRoot,
            TerminalOpenPayload(terminalId = "term-0", shell = "zsh", shellIntegration = true),
        )
        project.service<RecorderGitState>().emit!!.invoke(
            wsRoot,
            GitEventPayload(operation = "state_change", commitSha = "deadbeef"),
        )

        val ks = kinds(session)
        assertTrue("terminal.open must be recorded", ks.contains("terminal.open"))
        assertTrue("git.event must be recorded", ks.contains("git.event"))
    }

    fun testTerminalEventWithNoOwningRootIsDropped() {
        val m = manager()
        val session = start(m)
        project.service<RecorderTerminalState>().emitTerminalOpen!!.invoke(
            Files.createTempDirectory("no-owner"),
            TerminalOpenPayload(terminalId = "term-0", shell = "zsh", shellIntegration = true),
        )
        assertFalse("a terminal event with no owning session must be dropped", kinds(session).contains("terminal.open"))
    }

    fun testSecondSessionDoesNotClobberTheFirstsTerminalRouting() {
        val m = manager()
        val sessionA = start(m, root = wsRoot, provDir = provDir, assignmentId = "cats")
        val sessionB = start(m, root = wsRoot2, provDir = provDir2, assignmentId = "hog")

        project.service<RecorderTerminalState>().emitTerminalOpen!!.invoke(
            wsRoot,
            TerminalOpenPayload(terminalId = "term-a", shell = "zsh", shellIntegration = true),
        )
        project.service<RecorderTerminalState>().emitTerminalOpen!!.invoke(
            wsRoot2,
            TerminalOpenPayload(terminalId = "term-b", shell = "zsh", shellIntegration = true),
        )

        val aKinds = kinds(sessionA)
        val bKinds = kinds(sessionB)
        assertEquals("session A must see exactly its own terminal.open", 1, aKinds.count { it == "terminal.open" })
        assertEquals("session B must see exactly its own terminal.open", 1, bKinds.count { it == "terminal.open" })
    }

    fun testNoDoubleEmissionOnASingleDocChange() {
        installFsSeams(manager())
        myFixture.configureByText("hw.py", "print(1)\n")
        val m = manager()
        val session = start(m)
        val doc = myFixture.getDocument(myFixture.file)
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "x") }

        val docChanges = kinds(session).count { it == "doc.change" }
        assertEquals("a single keystroke must log exactly one doc.change", 1, docChanges)
    }

    fun testExtSnapshotIsDeliberatelyNotEmitted() {
        val m = manager()
        val session = start(m)
        assertFalse(
            "ext.snapshot must stay unwired until a public enumeration API exists",
            kinds(session).contains("ext.snapshot"),
        )
    }

    fun testExtActivateIsWiredToDynamicPluginLoad() {
        val m = manager()
        val session = start(m)
        val descriptor = java.lang.reflect.Proxy.newProxyInstance(
            com.intellij.ide.plugins.IdeaPluginDescriptor::class.java.classLoader,
            arrayOf(com.intellij.ide.plugins.IdeaPluginDescriptor::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPluginId" -> com.intellij.openapi.extensions.PluginId.getId("com.example.copilot")
                "getVersion" -> "1.2.3"
                else -> null
            }
        } as com.intellij.ide.plugins.IdeaPluginDescriptor
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus
            .syncPublisher(com.intellij.ide.plugins.DynamicPluginListener.TOPIC)
            .pluginLoaded(descriptor)

        assertTrue("ext.activate must be recorded on a mid-session plugin load", kinds(session).contains("ext.activate"))
    }

    fun testStopEndsSessionClearsSeamsAndIsIdempotent() {
        val m = manager()
        val session = start(m)
        m.stop()

        assertNull("session cleared after stop", m.activeSession)
        assertEquals("session.end", kinds(session).last())
        assertNull("terminal seam closed on stop", project.service<RecorderTerminalState>().emitTerminalOpen)
        assertNull("git seam closed on stop", project.service<RecorderGitState>().emit)

        m.stop() // idempotent, no throw
    }

    fun testStoppingOneRootLeavesTheOtherActive() {
        val m = manager()
        val sessionA = start(m, root = wsRoot, provDir = provDir, assignmentId = "cats")
        start(m, root = wsRoot2, provDir = provDir2, assignmentId = "hog")

        m.stop(wsRoot.toRealPath())

        assertNull("stopped root's session must be gone", m.activeSessions[wsRoot.toRealPath()])
        assertNotNull("the other root's session must still be active", m.activeSessions[wsRoot2.toRealPath()])
        assertEquals("session.end", kinds(sessionA).last())
    }

    fun testActiveSessionIsNullWhenMoreThanOneIsActive() {
        val m = manager()
        start(m, root = wsRoot, provDir = provDir, assignmentId = "cats")
        start(m, root = wsRoot2, provDir = provDir2, assignmentId = "hog")
        assertNull("activeSession is the single-session convenience; ambiguous with 2 active", m.activeSession)
        assertEquals(2, m.activeSessions.size)
    }

    fun testSealWithNoActiveSessionReturnsNoSessions() {
        val m = manager()
        assertTrue(m.sealActiveSession() is dev.provenance.recorder.commands.SealResult.NoSessions)
    }

    fun testNestedRootRoutesToTheInnerSessionNotTheOuter() {
        // A genuinely NESTED pair (not siblings): wsRoot is the outer assignment root, and a
        // subdirectory of it is itself a second, inner assignment root. This is the case
        // locked decision #5 ("nearest-enclosing ownership") exists for: a naive
        // "startsWith(root)" filter would match BOTH roots for anything under the inner one,
        // and the real algorithm (RecorderSessionManager.sinkFor/rootOwning's
        // maxByOrNull { it.key.nameCount }) must pick the longer (inner) prefix.
        val innerRoot = wsRoot.resolve("inner")
        Files.createDirectories(innerRoot)
        val outer = start(m = manager(), root = wsRoot, provDir = provDir, assignmentId = "outer")
        val inner = start(m = manager(), root = innerRoot, provDir = innerRoot.resolve(".provenance"), assignmentId = "inner")

        val pathUnderInner = innerRoot.resolve("hw.py")
        val pathUnderOuterOnly = wsRoot.resolve("other.py")

        val m = manager()
        assertSame(
            "a path under the inner root must route to the inner session, not the outer one",
            inner.controller,
            m.sinkFor(pathUnderInner),
        )
        assertSame(
            "a path under only the outer root must route to the outer session",
            outer.controller,
            m.sinkFor(pathUnderOuterOnly),
        )
        assertEquals(innerRoot.toRealPath(), m.rootOwning(pathUnderInner))
        assertEquals(wsRoot.toRealPath(), m.rootOwning(pathUnderOuterOnly))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :recorder:test --tests "dev.provenance.recorder.session.RecorderSessionManagerTest"`
Expected: FAIL (compile errors — `activeSessions`, `localFsOfOverride`, two-arg terminal/git callbacks, `stop(Path)` don't exist yet).

- [ ] **Step 3: Rewrite `RecordingSessionController.kt`**

Apply these changes to the existing file:

1. Drop the `localFsOf`/`nioPathOf` constructor parameters (lines 80–81 of the original) — nothing in the class needs them once `DocWiring`/`SelectionWiring` construction is removed from `init`.
2. Make the class implement `RecordableSessionSink` (from `dev.provenance.recorder.wiring`):

```kotlin
class RecordingSessionController(
    activated: ActivatedWorkspace,
    project: Project,
    ideVersion: String,
    platform: String,
    recorderVersion: String,
    recorderExtensionId: String,
    private val parentDisposable: Disposable,
    clock: Clock = SystemClock(),
    scheduler: FlushScheduler = DEFAULT_SCHEDULER,
    heartbeatIntervalMs: Long = Heartbeat.DEFAULT_INTERVAL_MS,
    recovery: RecoveryDecision = RecoveryDecision.CleanStart,
    checkpointInterval: Int = CheckpointCadence.DEFAULT_INTERVAL,
    degradedNotify: (String) -> Unit = { DegradedModeNotifier(project).notifyDegraded() },
    checkpointScopeFactory: () -> CoroutineScope = { CoroutineScope(SupervisorJob() + Dispatchers.IO) },
) : RecordableSessionSink {
    override val workspaceRoot: Path = activated.workspaceRoot
    override val pasteCorrelator: PasteCorrelator get() = pasteState.correlator ?: pasteCorrelatorField

    // ... unchanged fields (sessionId, slogPath, sessionPrivkey, writer, meta, host, heartbeat,
    // pasteTicker, pasteState, diskFullHandler, checkpointCadence, checkpointScheduler,
    // checkpointScope, ended) ...
```

3. Add a stored, non-nullable reference to the paste correlator (the old code only kept it inside `pasteState.correlator`, which is nulled at `endSession`; the sink interface's `pasteCorrelator` getter needs a value that behaves the same way DocWiring previously consumed it — via `pasteState.correlator`, which is exactly what's already nulled at end-of-session to close the paste privacy gate). Simplify: implement the interface property directly against `pasteState.correlator`:

```kotlin
    override val pasteCorrelator: PasteCorrelator? get() = pasteState.correlator
```

(Delete the `pasteCorrelatorField`/`get() = ... ?: ...` idea above — this single line is sufficient and reuses the existing privacy-gate-closing behavior verbatim: `endSession()` already sets `pasteState.correlator = null`.)

4. Add the sink methods (near the existing `private fun record(kind, data)`):

```kotlin
    override fun onDocOpen(payload: dev.provenance.core.DocOpenPayload) = record("doc.open", payload.toJsonObject())
    override fun onDocChange(payload: dev.provenance.core.DocChangePayload) {
        heartbeat.recordActivity()
        record("doc.change", payload.toJsonObject())
    }
    override fun onDocSave(payload: dev.provenance.core.DocSavePayload) = record("doc.save", payload.toJsonObject())
    override fun onDocClose(payload: dev.provenance.core.DocClosePayload) = record("doc.close", payload.toJsonObject())
    override fun onPaste(payload: dev.provenance.core.PastePayload) {
        heartbeat.recordActivity()
        record("paste", payload.toJsonObject())
    }
    override fun onSelectionChange(payload: dev.provenance.core.SelectionChangePayload) = record("selection.change", payload.toJsonObject())
```

5. **Remove** the `DocWiring(...)` construction block (Step 8 in the original `init`, the `DocWiring(project = project, provenanceDir = ..., workspaceRoot = ..., emitDocOpen = ..., ...)` call) and the `SelectionWiring(...)` construction block (Step 8c) entirely from `init`. Both are now constructed once, project-scoped, by `RecorderSessionManager` (Step 4 below), not per-session.
6. Everything else in `RecordingSessionController` (session keypair, `session.start`, disk-full handler, writer, meta, checkpoint cadence, heartbeat, clock-skew watcher, paste ticker/correlator setup, `endSession`, `flush`, `append`) is **unchanged**.

- [ ] **Step 4: Rewrite `RecorderSessionManager.kt`**

```kotlin
package dev.provenance.recorder.session

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import dev.provenance.core.Clock
import dev.provenance.core.Manifest
import dev.provenance.core.SystemClock
import dev.provenance.core.toJsonObject
import dev.provenance.recorder.commands.SealResult
import dev.provenance.recorder.commands.computeInstalledExtensionHash
import dev.provenance.recorder.commands.sealBundle
import dev.provenance.recorder.events.ExplanationTagger
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.plugin.ownPluginDescriptor
import dev.provenance.recorder.startup.NioRecoveryDeps
import dev.provenance.recorder.startup.RecoveryDecision
import dev.provenance.recorder.startup.recoverPreviousSession
import dev.provenance.recorder.watch.ExternalChangeCoordinator
import dev.provenance.recorder.watch.VfsExternalChangeListener
import dev.provenance.recorder.wiring.DocWiring
import dev.provenance.recorder.wiring.RecordableSessionSink
import dev.provenance.recorder.wiring.RecorderGitState
import dev.provenance.recorder.wiring.RecorderTerminalState
import dev.provenance.recorder.wiring.SelectionWiring
import dev.provenance.recorder.wiring.SessionRouter
import dev.provenance.recorder.wiring.isRecordablePath
import dev.provenance.recorder.wiring.snapshot.ExtActivateWiring
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

const val RECORDER_PLUGIN_ID = "com.aaryanmehta.provenance.recorder"

/**
 * Project-scoped registry of every live recording session, one per verified assignment root
 * (nested-manifest discovery: N verified manifests → N concurrent sessions). Activation
 * (RecorderActivationActivity) discovers every root and calls [startFromActivation] once per
 * root; this manager runs startup chain-recovery, constructs each root's
 * [RecordingSessionController], and — on the FIRST session in an otherwise-empty registry —
 * installs the project-scoped [DocWiring]/[SelectionWiring] routers and the terminal/git
 * routing callbacks (torn down again when the LAST session stops). Each session's own
 * ExternalChangeCoordinator remains per-session (already scoped by that session's
 * filesUnderReview, so N independent instances are cheap and correct — unlike the doc/
 * selection firehose, which must not be N independent global listeners).
 *
 * Lifecycle: a [Disposable] project service; the platform calls [dispose] on project close,
 * which stops every session (seal-safe: session.end + writer flush/dispose per session, no
 * auto-seal). Sealing stays an explicit user action (the seal AnAction, per-root).
 */
@Service(Service.Level.PROJECT)
class RecorderSessionManager(private val project: Project) : Disposable, SessionRouter {

    data class ActiveSession(
        val controller: RecordingSessionController,
        val activated: ActivatedWorkspace,
        val sessionDisposable: Disposable,
        val explanationTagger: ExplanationTagger,
    )

    private val sessions = ConcurrentHashMap<Path, ActiveSession>()

    val activeSessions: Map<Path, ActiveSession> get() = sessions.toMap()

    /** Back-compat single-session convenience: the session when exactly one root is active,
     * else null (including when more than one is active — ambiguous; use [activeSessions]). */
    val activeSession: ActiveSession? get() = sessions.values.singleOrNull()

    /** Test-only fs seams for the project-scoped DocWiring/SelectionWiring, read once when
     * they're lazily constructed on the first [start] call after the registry goes from empty
     * to non-empty. Must be set BEFORE that first [start] call in a test. */
    @TestOnly
    @Volatile
    var localFsOfOverride: ((VirtualFile) -> Boolean)? = null

    @TestOnly
    @Volatile
    var nioPathOfOverride: ((VirtualFile) -> Path?)? = null

    private class RoutedWiring(val disposable: Disposable, val docWiring: DocWiring, val selectionWiring: SelectionWiring)

    @Volatile
    private var routedWiring: RoutedWiring? = null

    private fun ensureRoutedWiring() {
        if (routedWiring != null) return
        val localFsOf = localFsOfOverride ?: { vf: VirtualFile -> vf.isInLocalFileSystem }
        val nioPathOf = nioPathOfOverride ?: { vf: VirtualFile -> runCatching { vf.toNioPath() }.getOrNull() }
        val disposable = Disposer.newDisposable(this, "provenance-routed-wiring")
        val doc = DocWiring(project, this, disposable, localFsOf = localFsOf, nioPathOf = nioPathOf)
        val sel = SelectionWiring(this, disposable, localFsOf = localFsOf, nioPathOf = nioPathOf)
        val terminalState = project.service<RecorderTerminalState>()
        terminalState.emitTerminalOpen = { cwd, payload -> routeTerminalOpen(cwd, payload) }
        terminalState.emitTerminalCommand = { cwd, payload -> routeTerminalCommand(cwd, payload) }
        project.service<RecorderGitState>().emit = { repoRoot, payload -> routeGitEvent(repoRoot, payload) }
        routedWiring = RoutedWiring(disposable, doc, sel)
    }

    private fun teardownRoutedWiringIfIdle() {
        if (sessions.isNotEmpty()) return
        val rw = routedWiring ?: return
        routedWiring = null
        Disposer.dispose(rw.disposable)
        project.service<RecorderTerminalState>().apply { emitTerminalOpen = null; emitTerminalCommand = null }
        project.service<RecorderGitState>().emit = null
    }

    private fun nearestEntry(path: Path, predicate: (Path, ActiveSession) -> Boolean): Map.Entry<Path, ActiveSession>? =
        sessions.entries.filter { (root, s) -> predicate(root, s) }.maxByOrNull { it.key.nameCount }

    /** [SessionRouter] implementation: nearest-enclosing session whose recordability
     * exclusions (workspace scope, `.provenance/`, activation manifest names, `.idea/`) admit
     * [nioPath]. Shared by DocWiring and SelectionWiring. */
    override fun sinkFor(nioPath: Path): RecordableSessionSink? =
        nearestEntry(nioPath.normalize()) { root, s -> isRecordablePath(nioPath, true, root, s.activated.provenanceDir) }
            ?.value?.controller

    /** The root (if any) whose assignment nearest-encloses [path] — no recordability
     * exclusions applied (used by the seal action to default-select the focused editor's
     * assignment, not to decide what to record). */
    fun rootOwning(path: Path): Path? {
        val normalized = runCatching { path.toRealPath() }.getOrDefault(path.normalize())
        return nearestEntry(normalized) { root, _ -> normalized.startsWith(root) }?.key
    }

    private fun sessionOwning(path: Path): ActiveSession? {
        val normalized = runCatching { path.toRealPath() }.getOrDefault(path.normalize())
        return nearestEntry(normalized) { root, _ -> normalized.startsWith(root) }?.value
    }

    private fun routeTerminalOpen(cwd: Path?, payload: dev.provenance.core.TerminalOpenPayload) {
        val session = cwd?.let(::sessionOwning) ?: return
        session.controller.append("terminal.open", payload.toJsonObject())
    }

    private fun routeTerminalCommand(cwd: Path?, payload: dev.provenance.core.TerminalCommandPayload) {
        val session = cwd?.let(::sessionOwning) ?: return
        session.controller.append("terminal.command", payload.toJsonObject())
    }

    private fun routeGitEvent(repoRoot: Path?, payload: dev.provenance.core.GitEventPayload) {
        val session = repoRoot?.let(::sessionOwning) ?: return
        session.explanationTagger.markGit()
        session.controller.append("git.event", payload.toJsonObject())
    }

    /** Production entry point, called from activation once a discovered manifest verifies.
     * No-op (logs) if a session for this root is already active. */
    suspend fun startFromActivation(root: Path, manifest: Manifest) {
        if (sessions.containsKey(root.normalize())) return
        val provenanceDir = root.resolve(".provenance")
        val recovery = recoverPreviousSession(NioRecoveryDeps(provenanceDir.toString()))
        val descriptor = ownPluginDescriptor()
        start(
            activated = ActivatedWorkspace(manifest, provenanceDir, root),
            recovery = recovery,
            ideVersion = ApplicationInfo.getInstance().fullVersion,
            platform = System.getProperty("os.name") ?: "unknown",
            recorderVersion = descriptor?.version ?: "0.0.0",
            recorderExtensionId = RECORDER_PLUGIN_ID,
        )
    }

    /** Testable core: construct the controller for [activated.workspaceRoot] and wire the
     * remaining per-session coordinators (external-change, ext.activate) into it. Ensures the
     * shared doc/selection/terminal/git routing exists (constructing it on the very first
     * session in an otherwise-empty registry). */
    fun start(
        activated: ActivatedWorkspace,
        recovery: RecoveryDecision,
        ideVersion: String,
        platform: String,
        recorderVersion: String,
        recorderExtensionId: String,
        clock: Clock = SystemClock(),
        scheduler: FlushScheduler = RecordingSessionController.DEFAULT_SCHEDULER,
        vfsDispatch: (() -> Unit) -> Unit = VfsExternalChangeListener.DEFAULT_DISPATCH,
    ): ActiveSession {
        val root = activated.workspaceRoot.normalize()
        check(sessions[root] == null) { "a recording session is already active for root $root" }
        ensureRoutedWiring()

        val sessionDisposable = Disposer.newDisposable(this, "provenance-recording-session-$root")

        val controller = RecordingSessionController(
            activated = activated,
            project = project,
            ideVersion = ideVersion,
            platform = platform,
            recorderVersion = recorderVersion,
            recorderExtensionId = recorderExtensionId,
            parentDisposable = sessionDisposable,
            clock = clock,
            scheduler = scheduler,
            recovery = recovery,
        )

        val tagger = ExplanationTagger(getNow = { clock.now() })
        wireExternalChange(controller, activated, tagger, vfsDispatch, sessionDisposable)
        wireExtActivate(controller, sessionDisposable)

        return ActiveSession(controller, activated, sessionDisposable, tagger).also { sessions[root] = it }
    }

    private fun wireExtActivate(controller: RecordingSessionController, sessionDisposable: Disposable) {
        ApplicationManager.getApplication().messageBus.connect(sessionDisposable).subscribe(
            DynamicPluginListener.TOPIC,
            ExtActivateWiring.listener { controller.append("ext.activate", it.toJsonObject()) },
        )
    }

    private fun wireExternalChange(
        controller: RecordingSessionController,
        activated: ActivatedWorkspace,
        tagger: ExplanationTagger,
        vfsDispatch: (() -> Unit) -> Unit,
        sessionDisposable: Disposable,
    ) {
        val coordinator = ExternalChangeCoordinator(
            project = project,
            workspaceRoot = activated.workspaceRoot,
            filesUnderReview = activated.manifest.filesUnderReview,
            emit = { payload ->
                val explained = payload.copy(explanation = tagger.consume() ?: payload.explanation)
                controller.append("fs.external_change", explained.toJsonObject())
            },
            vfsDispatch = vfsDispatch,
        )
        Disposer.register(sessionDisposable, coordinator)
        coordinator.start()
    }

    /** End one session (root != null) or every session (root == null — project close / test
     * teardown, preserving every existing no-arg `manager.stop()` call site). Idempotent. */
    fun stop(root: Path? = null) {
        if (root == null) {
            sessions.keys.toList().forEach(::stopOne)
        } else {
            stopOne(runCatching { root.toRealPath() }.getOrDefault(root.normalize()))
        }
    }

    private fun stopOne(root: Path) {
        val s = sessions.remove(root) ?: return
        Disposer.dispose(s.sessionDisposable)
        teardownRoutedWiringIfIdle()
    }

    @TestOnly
    @Volatile
    var extensionHashOverride: (() -> String)? = null

    /** Seal a specific assignment root's session (the seal action always specifies which
     * root once it knows there is more than one). */
    fun sealSession(
        root: Path,
        now: () -> Instant = Instant::now,
        computeExtensionHash: () -> String = extensionHashOverride ?: { computeInstalledExtensionHash(RECORDER_PLUGIN_ID) },
    ): SealResult {
        val s = sessions[runCatching { root.toRealPath() }.getOrDefault(root.normalize())] ?: return SealResult.NoSessions
        s.controller.flush()
        val m = s.activated.manifest
        return sealBundle(
            provenanceDir = s.activated.provenanceDir,
            workspaceRoot = s.activated.workspaceRoot,
            assignmentId = m.assignmentId,
            semester = m.semester,
            filesUnderReview = m.filesUnderReview,
            sessionPrivkey = s.controller.sessionPrivkey,
            computeExtensionHash = computeExtensionHash,
            outputDir = s.activated.workspaceRoot,
            now = now,
        )
    }

    /** Back-compat single-session convenience: seals the one active session, or NoSessions if
     * zero or more than one are active (an ambiguous choice belongs to the UI chooser, not
     * this method — production code no longer calls this; it's kept for existing callers/tests
     * of the single-assignment path). */
    fun sealActiveSession(
        now: () -> Instant = Instant::now,
        computeExtensionHash: () -> String = extensionHashOverride ?: { computeInstalledExtensionHash(RECORDER_PLUGIN_ID) },
    ): SealResult {
        val entry = sessions.entries.singleOrNull() ?: return SealResult.NoSessions
        return sealSession(entry.key, now, computeExtensionHash)
    }

    override fun dispose() = stop()
}
```

- [ ] **Step 5: Run the manager test suite**

Run: `./gradlew :recorder:test --tests "dev.provenance.recorder.session.RecorderSessionManagerTest"`
Expected: PASS (all 11 tests, including the two new multi-session ones and the terminal-drop-on-no-owner one).

- [ ] **Step 6: Run the full recorder module test suite to catch every downstream break**

Run: `./gradlew :recorder:test`

Expected breakage and fixes, one file at a time:

- `RecorderActivationActivity.kt` (from Task 2) currently calls `manager.startFromActivation(found.manifest)` (single-arg). Update it now to the real per-root call:
  ```kotlin
  if (resolvedRoot != null) {
      manager.startFromActivation(resolvedRoot, found.manifest)
  } else {
      LOG.info("discovered manifest at ${found.root.path} has no resolvable nio path; recording not started")
  }
  ```
  (This is the one line that was deliberately left as a placeholder in Task 2, step 5 — now fill it in for real.)
- `ManagerLifecycleGateTest.kt`, `ScopingGateTest.kt`, `SealActionGateTest.kt`, `AllSignalsLiveGateTest.kt`, `GitExternalChangeGateTest.kt`, `HeavyActivationGateTest.kt` all call `manager.start(...)` **without** `localFsOf`/`nioPathOf` (real on-disk files) — these compile and pass unchanged, since those two params are simply gone and every other named arg still matches.
- `HeavyActivationGateTest`/`ManagerLifecycleGateTest` read `manager.activeSession` — unaffected, since exactly one session is ever started in those tests and the back-compat `activeSession` getter returns it.

Iterate: run `./gradlew :recorder:test` again after each fix until the full module is green.

- [ ] **Step 7: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/session/RecorderSessionManager.kt recorder/src/main/kotlin/dev/provenance/recorder/session/RecordingSessionController.kt recorder/src/main/kotlin/dev/provenance/recorder/activation/RecorderActivationActivity.kt recorder/src/test/kotlin/dev/provenance/recorder/session/RecorderSessionManagerTest.kt
git commit --no-gpg-sign -m "feat(recorder): RecorderSessionManager becomes a multi-root session registry"
```

---

### Task 6: Wire discovery all the way through activation (regression + multi-root heavy test)

By Task 5's Step 6, `RecorderActivationActivity` already calls the real per-root `startFromActivation`. This task adds the missing **heavy, end-to-end** coverage for discovery (acceptance criterion 1) and confirms the single-assignment-at-project-root regression (criterion 6) with a real multi-session project layout, which nothing so far has exercised together.

**Files:**
- Create: `recorder/src/test/kotlin/dev/provenance/recorder/NestedManifestDiscoveryGateTest.kt`

**Interfaces:**
- Consumes: `RecorderActivationActivity()` (real, no-arg, production discoverer), `RecorderState.activeManifests`, `RecorderSessionManager.activeSessions`.

- [ ] **Step 1: Write the test**

```kotlin
package dev.provenance.recorder

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.provenance.recorder.activation.RecorderActivationActivity
import dev.provenance.recorder.activation.RecorderState
import dev.provenance.recorder.session.RecorderSessionManager
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

/**
 * HEAVY end-to-end nested-discovery gate. A real on-disk project base dir holds two sibling
 * assignment subdirectories (`cats/`, `hog/`), each with its own valid course-signed
 * `.provenance-manifest`, plus a third sibling (`bad/`) with a foreign-key-signed one. Proves,
 * through the REAL production RecorderActivationActivity (recursive discovery + per-root
 * session start), that:
 *
 *  - two verified manifests ⇒ two live sessions, each with its own `.provenance/`;
 *  - the bad-signature sibling is skipped, never starts a session, writes nothing;
 *  - (regression) a single manifest at the project base dir still behaves exactly as the
 *    pre-discovery single-assignment path did — one session, no ambiguity.
 */
class NestedManifestDiscoveryGateTest : HeavyPlatformTestCase() {

    private fun baseDir(): VirtualFile = getOrCreateProjectBaseDir()

    private fun writeManifest(relDir: String, text: String) {
        WriteAction.runAndWait<RuntimeException> {
            val dir = VfsUtil.createDirectoryIfMissing(baseDir(), relDir)
            val f = dir.findChild(".provenance-manifest") ?: dir.createChildData(this, ".provenance-manifest")
            VfsUtil.saveText(f, text)
        }
    }

    private fun runActivation() {
        runBlocking { RecorderActivationActivity().execute(project) }
    }

    override fun tearDown() {
        try {
            runCatching { project.service<RecorderSessionManager>().stop() }
        } finally {
            super.tearDown()
        }
    }

    fun testTwoSiblingManifestsStartTwoSessionsAndSkipTheBadOne() {
        writeManifest("cats", HeavyTestManifests.validManifestJson(assignmentId = "cats"))
        writeManifest("hog", HeavyTestManifests.validManifestJson(assignmentId = "hog"))
        writeManifest("bad", HeavyTestManifests.invalidSignatureManifestJson())

        runActivation()

        val state = project.service<RecorderState>()
        assertEquals(setOf("cats", "hog"), state.activeManifests.values.map { it.assignmentId }.toSet())

        val manager = project.service<RecorderSessionManager>()
        assertEquals("exactly two live sessions for the two verified siblings", 2, manager.activeSessions.size)

        val catsRoot = baseDir().toNioPath().resolve("cats").toRealPath()
        val hogRoot = baseDir().toNioPath().resolve("hog").toRealPath()
        val badRoot = baseDir().toNioPath().resolve("bad")

        assertNotNull("cats must have a live session", manager.activeSessions[catsRoot])
        assertNotNull("hog must have a live session", manager.activeSessions[hogRoot])
        assertTrue("cats/.provenance must exist", Files.isDirectory(catsRoot.resolve(".provenance")))
        assertTrue("hog/.provenance must exist", Files.isDirectory(hogRoot.resolve(".provenance")))
        assertFalse("bad/.provenance must never be created (bad signature ⇒ no session, no I/O)", Files.exists(badRoot.resolve(".provenance")))
    }

    fun testSingleManifestAtProjectBaseDirBehavesLikeBeforeDiscoveryExisted() {
        writeManifest(".", HeavyTestManifests.validManifestJson())
        // writeManifest(".", ...) writes directly under the base dir, mirroring the pre-
        // discovery single-manifest-at-workspace-root layout exactly.

        runActivation()

        val manager = project.service<RecorderSessionManager>()
        assertEquals("regression: exactly one session, no ambiguity", 1, manager.activeSessions.size)
        assertNotNull("the single-session back-compat accessor must resolve it", manager.activeSession)
        assertEquals("hw03", manager.activeSession!!.activated.manifest.assignmentId)
    }
}
```

Note: `writeManifest(".", ...)` with `VfsUtil.createDirectoryIfMissing(baseDir(), ".")` should simply return `baseDir()` itself; if that call doesn't behave as expected against the real SDK, replace it with the exact pattern `HeavyActivationGateTest` already uses (`dir.findChild(".provenance-manifest") ?: dir.createChildData(this, ".provenance-manifest")` directly against `baseDir()`, no `createDirectoryIfMissing` needed for `"."`).

- [ ] **Step 2: Run**

Run: `./gradlew :recorder:test --tests "dev.provenance.recorder.NestedManifestDiscoveryGateTest"`
Expected: PASS (2 tests).

- [ ] **Step 3: Commit**

```bash
git add recorder/src/test/kotlin/dev/provenance/recorder/NestedManifestDiscoveryGateTest.kt
git commit --no-gpg-sign -m "test(recorder): end-to-end nested sibling discovery + single-root regression"
```

---

### Task 7: Seal-time assignment chooser

**Files:**
- Modify: `recorder/src/main/kotlin/dev/provenance/recorder/commands/PrepareSubmissionBundleAction.kt`
- Modify (tests): `recorder/src/test/kotlin/dev/provenance/recorder/SealActionGateTest.kt`

**Interfaces:**
- Consumes: `RecorderSessionManager.activeSessions`, `RecorderSessionManager.sealSession(root, ...)`, `RecorderSessionManager.rootOwning(path)`.

- [ ] **Step 1: Extend `SealActionGateTest.kt` with a two-session case (write this first — it will fail against the old single-session action)**

Add this test method to the existing class (keep the existing two tests as-is; they already pass unchanged since one-session-active still takes the no-prompt path):

```kotlin
fun testTwoActiveSessionsSealsOnlyTheExplicitlyChosenOne() {
    val manager = startLiveSession() // "hw.py" under wsRoot, assignment "hw03"
    manager.extensionHashOverride = { "0".repeat(64) }

    // A second, independent assignment root + session.
    val wsRoot2 = Files.createTempDirectory("seal-action-ws2").toRealPath()
    VfsRootAccess.allowRootAccess(testRootDisposable, wsRoot2.toString())
    Files.writeString(wsRoot2.resolve("hog.py"), "print('hog')\n")
    manager.start(
        activated = ActivatedWorkspace(
            HeavyTestManifests.signedManifestObject(assignmentId = "hog"),
            wsRoot2.resolve(".provenance"),
            wsRoot2,
        ),
        recovery = RecoveryDecision.CleanStart,
        ideVersion = "2026.1.4",
        platform = "darwin-arm64",
        recorderVersion = "0.1.0",
        recorderExtensionId = "com.aaryanmehta.provenance.recorder",
        clock = FixedClock(0, Instant.parse("2026-07-14T00:00:00Z")),
        scheduler = NoopScheduler(),
        vfsDispatch = { it() },
    )

    // Seal the "hog" root directly through the manager (proves the manager side of the
    // multi-root seal path independent of the popup UI, which PlatformTestUtil cannot drive
    // headlessly) — the chooser UI itself is exercised manually per docs/manual-verification.md.
    val result = manager.sealSession(wsRoot2)
    assertTrue("sealing a specific root must succeed", result is dev.provenance.recorder.commands.SealResult.Ok)

    val hogBundles = Files.list(wsRoot2).use { s -> s.filter { it.fileName.toString().matches(Regex("hog-bundle-.*\\.zip")) }.toList() }
    assertEquals("exactly one bundle for the hog root", 1, hogBundles.size)
    val hw03Bundles = Files.list(wsRoot2).use { s -> s.filter { it.fileName.toString().contains("hw03") }.toList() }
    assertTrue("the hw03 root's bundle must never land under the hog root", hw03Bundles.isEmpty())

    // Stash for the same separate node analysis-core validation (loadBundle + runValidation)
    // the single-session test below already does — the multi-root path must produce an
    // equally analyzer-ready bundle, not just a same-named zip.
    val outDir = Paths.get("build/e2e-seal-action-multi")
    Files.createDirectories(outDir)
    val stable = outDir.resolve(hogBundles[0].fileName.toString())
    Files.copy(hogBundles[0], stable, StandardCopyOption.REPLACE_EXISTING)
    println("E2E_SEAL_ACTION_MULTI_BUNDLE_PATH=" + stable.toAbsolutePath())

    wsRoot2.toFile().deleteRecursively()
}
```

(This test covers the manager-level "seal only the chosen root" invariant directly and deterministically; it does not attempt to drive the `JBPopupFactory` chooser headlessly — see docs/manual-verification.md for the runIde manual check of the popup UI itself, added in Step 4 below.)

- [ ] **Step 2: Run to verify it currently passes (manager-level seal already works after Task 5) and update `update()`'s enablement test if needed**

Run: `./gradlew :recorder:test --tests "dev.provenance.recorder.SealActionGateTest"`
Expected: PASS already (this test only exercises `RecorderSessionManager.sealSession`, which Task 5 already implemented) — confirming the manager side is solid before changing the action's UI branch.

- [ ] **Step 3: Rewrite `PrepareSubmissionBundleAction.kt`**

```kotlin
package dev.provenance.recorder.commands

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import dev.provenance.recorder.failure.DegradedModeNotifier
import dev.provenance.recorder.session.RecorderSessionManager
import java.nio.file.Path

/**
 * "Provenance: Prepare Submission Bundle". Enabled iff at least one session is active. With
 * exactly one, seals it directly (unchanged single-assignment behavior). With more than one,
 * prompts for which assignment to seal (design.md nested-manifest discovery §4: "Seal
 * selector"), defaulting the highlighted item to the assignment owning the focused editor.
 */
class PrepareSubmissionBundleAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null && project.service<RecorderSessionManager>().activeSessions.isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = project.service<RecorderSessionManager>()
        val roots = manager.activeSessions.keys.toList()
        when {
            roots.isEmpty() -> Unit // update() already hid the action; defensive no-op
            roots.size == 1 -> sealAndNotify(project, manager, roots.first())
            else -> chooseRoot(project, manager, roots, e.dataContext) { chosen -> sealAndNotify(project, manager, chosen) }
        }
    }

    private fun chooseRoot(
        project: Project,
        manager: RecorderSessionManager,
        roots: List<Path>,
        dataContext: DataContext,
        onChosen: (Path) -> Unit,
    ) {
        val labelToRoot = LinkedHashMap<String, Path>()
        for (root in roots) {
            val assignmentId = manager.activeSessions[root]?.activated?.manifest?.assignmentId ?: root.toString()
            val relative = project.basePath
                ?.let { base -> runCatching { Path.of(base).relativize(root).toString() }.getOrNull() }
                ?: root.toString()
            labelToRoot["$assignmentId  ($relative)"] = root
        }
        val focusedRoot = FileEditorManager.getInstance(project).selectedEditor?.file
            ?.let { vf -> runCatching { vf.toNioPath() }.getOrNull() }
            ?.let { path -> manager.rootOwning(path) }
        val focusedLabel = labelToRoot.entries.firstOrNull { it.value == focusedRoot }?.key

        val builder = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(labelToRoot.keys.toList())
            .setTitle("Choose Assignment to Submit")
            .setItemChosenCallback { label -> labelToRoot[label]?.let(onChosen) }
        // Best-effort default highlight; not required by any test — verify the exact overload
        // against the real SDK and drop this call if it doesn't compile as written.
        if (focusedLabel != null) runCatching { builder.setSelectedValue(focusedLabel, true) }

        builder.createPopup().showInBestPositionFor(dataContext)
    }

    private fun sealAndNotify(project: Project, manager: RecorderSessionManager, root: Path) {
        object : Task.Backgroundable(project, "Preparing Provenance submission bundle", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = manager.sealSession(root)
                notify(project, result)
            }
        }.queue()
    }

    private fun notify(project: Project, result: SealResult) {
        val (message, type) = when (result) {
            is SealResult.Ok ->
                if (result.chainBroken || result.unreadableSession) {
                    "Provenance bundle saved to ${result.bundlePath}. Integrity issues were detected " +
                        "in the recording and will be reviewed by course staff." to NotificationType.WARNING
                } else {
                    "Provenance bundle saved to ${result.bundlePath}." to NotificationType.INFORMATION
                }
            is SealResult.NoSessions -> "No session data to seal." to NotificationType.WARNING
            is SealResult.WriteError -> "Bundle write error: ${result.message}" to NotificationType.ERROR
        }
        ApplicationManager.getApplication().invokeLater {
            Notification(DegradedModeNotifier.GROUP_ID, message, type).notify(project)
        }
    }
}
```

- [ ] **Step 4: Add a manual-verification note**

Append to `docs/manual-verification.md` (read the file first to match its existing format) a new checklist item: "Seal chooser popup — open a project with two sibling assignment manifests, run Tools → Provenance: Prepare Submission Bundle, confirm a popup lists both assignment IDs, the one under the focused editor is pre-highlighted, and choosing one produces a bundle only for that assignment." This is UI (`JBPopupFactory`) that `PlatformTestUtil` cannot drive headlessly — same category as the file's existing external-change entries.

- [ ] **Step 5: Run the full test suite for this file's area**

Run: `./gradlew :recorder:test --tests "dev.provenance.recorder.SealActionGateTest"`
Expected: PASS (3 tests, including the new two-session one).

- [ ] **Step 6: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/commands/PrepareSubmissionBundleAction.kt recorder/src/test/kotlin/dev/provenance/recorder/SealActionGateTest.kt docs/manual-verification.md
git commit --no-gpg-sign -m "feat(recorder): seal-time chooser when more than one assignment is recording"
```

---

### Task 8: Status bar reflects assignment count

**Files:**
- Modify: `recorder/src/main/kotlin/dev/provenance/recorder/statusbar/RecordingStatusBarWidget.kt`
- Modify (tests): `recorder/src/test/kotlin/dev/provenance/recorder/statusbar/RecordingStatusBarWidgetFactoryTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `RecordingStatusBarWidgetFactoryTest.kt`:

```kotlin
fun `test widget text shows assignment count when more than one is active`() {
    val state = project.service<RecorderState>()
    state.activate(java.nio.file.Paths.get("/ws-a"), manifest())
    state.activate(java.nio.file.Paths.get("/ws-b"), manifest("hw04"))
    val widget = RecordingStatusBarWidgetFactory().createWidget(project)
    val presentation = widget.getPresentation() as com.intellij.openapi.wm.StatusBarWidget.TextPresentation
    assertEquals("Provenance: recording (2 assignments)", presentation.getText())
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :recorder:test --tests "dev.provenance.recorder.statusbar.RecordingStatusBarWidgetFactoryTest"`
Expected: FAIL (`testDoesNotEndWithMismatch: expected "Provenance: recording (2 assignments)" but was "Provenance: recording"`).

- [ ] **Step 3: Update `RecordingStatusBarWidget.kt`**

```kotlin
package dev.provenance.recorder.statusbar

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import dev.provenance.recorder.activation.RecorderState
import java.awt.Component
import java.awt.event.MouseEvent

class RecordingStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        // Nothing to wire up beyond text; no click/hover state to install.
    }

    override fun dispose() {
        // No owned resources.
    }

    override fun getText(): String {
        val count = project.service<RecorderState>().activeManifests.size
        return if (count > 1) "Provenance: recording ($count assignments)" else "Provenance: recording"
    }

    override fun getTooltipText(): String = "Provenance recorder is active for this assignment."

    override fun getAlignment(): Float = Component.LEFT_ALIGNMENT

    override fun getClickConsumer(): Consumer<MouseEvent>? = null

    companion object {
        const val WIDGET_ID = "ProvenanceRecordingWidget"
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :recorder:test --tests "dev.provenance.recorder.statusbar.*"`
Expected: PASS (existing single-assignment test still asserts the exact unchanged string `"Provenance: recording"`, satisfying the regression requirement).

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/statusbar/RecordingStatusBarWidget.kt recorder/src/test/kotlin/dev/provenance/recorder/statusbar/RecordingStatusBarWidgetFactoryTest.kt
git commit --no-gpg-sign -m "feat(recorder): status bar shows assignment count when more than one is recording"
```

---

### Task 9: Full verification pass

**No new files.** This task runs the complete gate and fixes anything the per-task runs didn't catch (cross-task interaction, lint, full-module compile).

- [ ] **Step 1: Full module build + test**

Run: `./gradlew build test`
Expected: PASS across `:core` and `:recorder`. If anything red, fix it — do not weaken an assertion; if a genuine requirement conflict appears, stop and report per this plan's Global Constraints.

- [ ] **Step 2: Conformance vectors**

Check whether a dedicated conformance Gradle task exists (`./gradlew tasks --all | grep -i conformance` or inspect `core/build.gradle.kts`); if conformance tests are part of `:core:test` (per README: "`./gradlew :core:test` — format unit + conformance tests"), Step 1 already covers this. Confirm explicitly:

Run: `./gradlew :core:test --tests "*onformance*"` (adjust the test-class glob to whatever actually exists under `core/src/test/resources/conformance/` and its runner class)
Expected: PASS — this plan makes zero changes to `core/`, so conformance must be unaffected; a failure here would indicate an accidental format change and must be treated as a hard stop, not a vector edit.

- [ ] **Step 3: Re-run the specific acceptance-criteria tests as a named checklist**

Run and record PASS/FAIL for each:
- Discovery: `./gradlew :recorder:test --tests "dev.provenance.recorder.activation.ManifestDiscoveryTest"` and `--tests "dev.provenance.recorder.NestedManifestDiscoveryGateTest"`
- Routing (doc/selection nearest-ancestor, no cross-leak): `--tests "dev.provenance.recorder.wiring.DocWiringTest"`, `--tests "dev.provenance.recorder.wiring.SelectionWiringTest"`, and `--tests "dev.provenance.recorder.session.RecorderSessionManagerTest.testNestedRootRoutesToTheInnerSessionNotTheOuter"` (the real nearest-ancestor algorithm against a genuinely nested, not just sibling, pair of roots)
- Per-`.provenance/`: covered inside `NestedManifestDiscoveryGateTest`
- Seal selector: `--tests "dev.provenance.recorder.SealActionGateTest"`
- Terminal/git (attribution + no-clobber): `--tests "dev.provenance.recorder.session.RecorderSessionManagerTest"` and `--tests "dev.provenance.recorder.GitExternalChangeGateTest"`
- Regression (single-assignment-at-base-dir path unchanged): `--tests "dev.provenance.recorder.HeavyActivationGateTest"` and the second method of `NestedManifestDiscoveryGateTest`

- [ ] **Step 4: Lint/format check if the repo has one wired to Gradle**

Check `build.gradle.kts` / `settings.gradle.kts` for a ktlint/detekt task; if present, run it and fix findings. If none exists, note that explicitly in the final report (do not add a new linter — that's a dependency decision requiring approval).

- [ ] **Step 5: Final status**

Do not commit in this task beyond what Steps 1–4 required to turn green (those fixes should be scoped, small commits with their own conventional-commit messages, following the same pathspec discipline as every other task). Report the exact commands run and their pass/fail output back to the orchestrator — do not merge, push, or open a PR.
