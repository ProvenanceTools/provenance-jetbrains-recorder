# Plugin Scaffold, Activation & Manifest Verification Implementation Plan (Plan 3 of the provjet series)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the `recorder/` Gradle module — an IntelliJ Platform plugin, built with the IntelliJ Platform Gradle Plugin (2.x), targeting `com.intellij.modules.platform` so it runs across all JetBrains IDEs — that activates **only** when the workspace root has a `.provenance-manifest` (or `provenance-manifest`) file whose ed25519 signature verifies against the embedded course public key (recorder PRD §4.1), shows the always-visible "Provenance: recording" status-bar widget once active, and does nothing (no recording, no UI, no disk writes) otherwise. Ship a sideload-able `buildPlugin` `.zip` for manual install/testing.

**Architecture:** `recorder/` depends on `core/` (Plans 1–2) for the format primitives — `Manifest`, `parseManifest`, `verifyManifest` — and on the IntelliJ Platform SDK for wiring. Mirrors the VS Code recorder's `activation/` module (`packages/recorder/src/activation/{manifest-loader,course-public-key,course-keys,status-bar}.ts`) one-for-one, but split so the crypto/shape logic (`evaluateManifestText`) is a pure Kotlin function tested with plain JUnit, and only the VFS/Project plumbing around it is tested with IntelliJ's `BasePlatformTestCase` — mirrors CLAUDE.md's "test the event→log-entry transform as a pure function, separately from the platform wiring." Activation runs as a `ProjectActivity` (the current `com.intellij.postStartupActivity` implementation, superseding the deprecated `StartupActivity`), stores result in a light project service (`RecorderState`), and a `StatusBarWidgetFactory` consults that service's `isAvailable(project)` to decide whether to render the widget — refreshed via `StatusBarWidgetsManager.updateAllWidgets()` once the (asynchronous) manifest check completes.

**Tech Stack:** Kotlin/JVM, IntelliJ Platform Gradle Plugin 2.x (`org.jetbrains.intellij.platform`), IntelliJ Platform SDK (`com.intellij.modules.platform` target), JUnit 5 (pure-Kotlin tests) + IntelliJ `testFramework(TestFrameworkType.Platform)` / `BasePlatformTestCase` (platform-seam tests). Builds on Plan 1's `core/` module and Plan 2's `Manifest`/`parseManifest`/`verifyManifest`.

## Plan series (context)

This is Plan 3 of the series derived from `docs/design.md`.

- **Plan 1 (done):** `core/` — hashing, JCS, chain, NDJSON, chain-validator, conformance gate.
- **Plan 2 (done):** `core/` — bundle manifest (shape + ed25519 sign), session keypair, signed checkpoints, and the `parseManifest`/`verifyManifest` activation-gate primitives this plan consumes.
- **Plan 3 (this):** plugin scaffold (IntelliJ Platform Gradle Plugin) + activation + manifest verification + status-bar widget + **sideload build for testing**.
- **Plan 4:** `doc.open/change/save/close` wiring + atomic session writer + bundle seal → first analyzer-accepted bundle.
- **Plan 5:** external-change detection (VFS — highest-risk).
- **Plan 6:** three-signal paste detection.
- **Plan 7:** terminal + git wiring + plugin snapshot.
- **Plan 8:** checkpoints wiring + chain recovery + disk-full degraded mode.
- **Plan 9:** `build:prod` course-key embedding + `extension_hash` + **Marketplace packaging** (out of scope here; this plan's sideload `.zip` is for early testing only). Plus the two monorepo changes: allowlist entry + golden-vector export script.

## Global Constraints

(Inherits Plan 1/2's Global Constraints: format is a fixed contract owned by `log-core`; `core/` stays IntelliJ-free; do not hand-roll JCS; determinism in tests; `git commit --no-gpg-sign`, no Claude co-author trailer, explicit pathspec.) Additional, for this plan:

- **Activation is the privacy gate (PRD §4.1, CLAUDE.md).** "If the signature doesn't verify, the extension does nothing (no recording, no UI noise)." No file reads, no status-bar item, no log directory created, until `evaluateManifestText`/`loadAndVerifyManifest` returns `Active`.
- **`recorder/` depends on `core/` and the IntelliJ Platform SDK only.** No new third-party Gradle dependencies without approval (CLAUDE.md "No new dependencies without asking").
- **Test the transform separately from the wiring.** Manifest parse+verify logic (already proven in Plan 2's `core/` conformance suite) is invoked, not reimplemented, here. This plan's own tests cover: (a) the thin Kotlin glue that turns a `ParseResult`/`Boolean` into an activation decision, pure and IntelliJ-free; (b) the VFS/Project plumbing, via `BasePlatformTestCase`.
- **Never commit a real course key.** `CoursePublicKey.kt` ships the same dev keypair pattern as the VS Code recorder (`packages/recorder/src/activation/course-public-key.ts`) — a placeholder dev-key constant, swapped at `build:prod` time (Plan 9). Do not hardcode a production key in this plan.
- **Plugin id is provisional.** `docs/design.md` §11 open question 4 lists `edu.berkeley.provenance.recorder` as the leading candidate but explicitly leaves it undecided. This plan uses it as a placeholder (consistent with the dev-key placeholder pattern) — flag for confirmation before Plan 9 (Marketplace identity is permanent once published).
- **IntelliJ API/version specifics not independently confirmed by running Gradle in this environment are marked `VERIFY AT EXECUTION`.** Research below is from `plugins.jetbrains.com/docs/intellij` (fetched/searched 2026-07-14); the fetch summarizer returned inconsistent version numbers across calls (evidence the tool paraphrases rather than quotes verbatim), so treat cited *version numbers* as a starting point, not gospel — confirm against the live docs / IntelliJ Platform Explorer before pinning `build.gradle.kts`.

## IntelliJ Platform APIs settled on for this plan (with sources)

| Concern | API | Doc |
|---|---|---|
| Build tooling | IntelliJ Platform Gradle Plugin 2.x, id `org.jetbrains.intellij.platform` | [tools-intellij-platform-gradle-plugin.html](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html) |
| Gradle config detail | `intellijPlatform { }` dependencies/repositories DSL | [configuring-gradle.html](https://plugins.jetbrains.com/docs/intellij/configuring-gradle.html) |
| New-project layout reference | Plugin Template / New Project wizard output shape | [creating-plugin-project.html](https://plugins.jetbrains.com/docs/intellij/creating-plugin-project.html) |
| `plugin.xml` shape | `<idea-plugin>`, `<id>`, `<name>`, `<vendor>`, `<depends>`, `<idea-version since-build>`, `<extensions defaultExtensionNs="com.intellij">` | [plugin-configuration-file.html](https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html) |
| Run-across-all-IDEs target | `com.intellij.modules.platform` | [dev-alternate-products.html](https://plugins.jetbrains.com/docs/intellij/dev-alternate-products.html) |
| Extension point catalogue | full EP list (used to confirm `postStartupActivity`, `statusBarWidgetFactory` IDs) | [intellij-platform-extension-point-list.html](https://plugins.jetbrains.com/docs/intellij/intellij-platform-extension-point-list.html) |
| Project-open activation | `ProjectActivity` (Kotlin, `suspend fun execute`) registered under EP `com.intellij.postStartupActivity` — current replacement for the deprecated `StartupActivity` | search result citing JetBrains SDK guidance; EP id confirmed against the EP list page above. **VERIFY AT EXECUTION** against `plugin-components.html` / current SDK samples. |
| Reading a workspace-root file | `Project.guessProjectDir()` + `VirtualFile.findChild(name)` + `VfsUtilCore.loadText(virtualFile)` | [virtual-file-system.html](https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html), [virtual-file.html](https://plugins.jetbrains.com/docs/intellij/virtual-file.html) |
| Status bar widget | `StatusBarWidgetFactory` + `StatusBarWidget` + `StatusBarWidget.TextPresentation`, EP `com.intellij.statusBarWidgetFactory` | [status-bar-widgets.html](https://plugins.jetbrains.com/docs/intellij/status-bar-widgets.html) |
| Refreshing the widget after async activation | `StatusBarWidgetsManager.updateAllWidgets()` (project service) | **VERIFY AT EXECUTION** — not directly quoted from a fetched page; confirm exact class/method against `status-bar-widgets.html` "Force Update" guidance or platform source before relying on it. |
| Project-scoped state | light service, `@Service(Service.Level.PROJECT)`, retrieved via `project.service<T>()` | [plugin-services.html](https://plugins.jetbrains.com/docs/intellij/plugin-services.html) |
| Teardown | `Disposable` interface; platform auto-disposes services and widgets that implement it | [disposers.html](https://plugins.jetbrains.com/docs/intellij/disposers.html) |
| Headless plugin tests | `BasePlatformTestCase` (`com.intellij.testFramework.fixtures`), Gradle `testFramework(TestFrameworkType.Platform)` dependency | [testing-plugins.html](https://plugins.jetbrains.com/docs/intellij/testing-plugins.html); dependency wiring per `tools-intellij-platform-gradle-plugin-testing-extension.html` (found via search, not independently fetched — **VERIFY AT EXECUTION**) |
| API discovery during implementation | IntelliJ Platform Explorer (`jb.gg/ipe`) — cross-plugin EP usage search | [explore-api.html](https://plugins.jetbrains.com/docs/intellij/explore-api.html) |

**Toolchain baseline (VERIFY AT EXECUTION before Task 1):** research surfaced JDK 17 as the baseline for IntelliJ Platform 2022.3–2024.x targets, JDK 21 for 2025.1+; Gradle ≥ 8.13 for the 2.x Gradle plugin. This plan defaults to **JDK 17** and an **IntelliJ Community (`intellijIdeaCommunity`) 2024.2.x** compile target as the conservative, currently-documented pairing — confirm the actual current stable IC release and its required JDK against `https://www.jetbrains.com/idea/download/other.html` or the IntelliJ Platform Explorer before running Task 1, and adjust `jvmToolchain(...)` / the `intellijIdeaCommunity(...)` version string accordingly. This is a build-config detail, not a format decision, so adjusting it does not require the "stop and ask" escalation — just note what was actually used in the Task 1 commit.

---

### Task 1: `recorder/` Gradle module scaffold + `plugin.xml` skeleton

**Files:**
- Modify: `settings.gradle.kts` — add `include("recorder")`.
- Modify: `build.gradle.kts` (root) — add `id("org.jetbrains.intellij.platform") version "<latest 2.x>" apply false` to the `plugins {}` block.
- Create: `recorder/build.gradle.kts`
- Create: `recorder/src/main/resources/META-INF/plugin.xml`
- Create: `recorder/gradle.properties` (or reuse root `gradle.properties` — see Step 1) for `pluginGroup`, `pluginVersion`, `pluginSinceBuild`, `platformVersion`.

**Interfaces:**
- Produces: a buildable, emptily-functional plugin module. No Kotlin source yet beyond what's needed to compile — this task's success criterion is `./gradlew :recorder:buildPlugin` producing a `.zip`, not any recorder behavior.

- [ ] **Step 1: Add/confirm root `gradle.properties`**

If `gradle.properties` does not already exist at the repo root, create it:
```properties
pluginGroup = dev.provenance.recorder
pluginName = provenance-recorder
pluginVersion = 0.1.0
# VERIFY AT EXECUTION: pick the actual current stable IC build number range.
pluginSinceBuild = 233
platformVersion = 2024.2
```
These are read by `recorder/build.gradle.kts` below so the version/compat numbers live in one place (matches the JetBrains Plugin Template convention).

- [ ] **Step 2: Update `settings.gradle.kts`**

```kotlin
rootProject.name = "provenance-jetbrains-recorder"

include("core")
include("recorder")
```

- [ ] **Step 3: Add the IntelliJ Platform Gradle Plugin to the root `plugins {}` block**

`build.gradle.kts` (root) — add one line, keep the rest:
```kotlin
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.intellij.platform") version "2.2.1" apply false // VERIFY AT EXECUTION: pin to the actual latest 2.x release
}

allprojects {
    repositories {
        mavenCentral()
    }
}
```

- [ ] **Step 4: Create `recorder/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":core"))

    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        bundledModule("com.intellij.modules.platform") // VERIFY AT EXECUTION: confirm this is how 2.x expresses a platform-module dependency vs. plugin.xml `<depends>` alone
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17) // VERIFY AT EXECUTION against platformVersion's required JDK
}
```

**VERIFY AT EXECUTION note:** the exact DSL member names (`intellijIdeaCommunity(...)`, `bundledModule(...)`, `testFramework(...)`, `pluginConfiguration { }`) are as documented/found via search on 2026-07-14; the Gradle plugin's Kotlin DSL surface has churned across 2.x minor releases (`configuring-gradle.html`). If any of these don't resolve, check `https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html` and `configuring-gradle.html` for the current member names before inventing alternatives — this is exactly the kind of drift CLAUDE.md says to stop and reconcile against source, not route around.

- [ ] **Step 5: Create the `plugin.xml` skeleton**

`recorder/src/main/resources/META-INF/plugin.xml`:
```xml
<idea-plugin>
    <id>edu.berkeley.provenance.recorder</id>
    <name>Provenance Recorder</name>
    <vendor email="provenance@example.edu" url="https://example.edu">Provenance</vendor>

    <description><![CDATA[
    Records a tamper-evident log of editing activity for an activated course
    assignment. Activates only on a workspace with a valid, course-signed
    .provenance-manifest. See the Provenance project documentation.
    ]]></description>

    <depends>com.intellij.modules.platform</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- postStartupActivity / statusBarWidgetFactory registrations land in Tasks 5 and 6 -->
    </extensions>
</idea-plugin>
```
`<version>` and `<idea-version since-build>` are intentionally omitted from the source file — Step 4's `pluginConfiguration { }` block patches them in from `gradle.properties` at build time (`patchPluginXml`, run automatically before `buildPlugin`/`runIde`). **VERIFY AT EXECUTION** that this patching actually happens with 2.x's default task graph; if not, add the values directly to the XML above.

- [ ] **Step 6: Build and verify the empty plugin packages**

Run: `./gradlew :recorder:buildPlugin`
Expected: BUILD SUCCESSFUL; `recorder/build/distributions/provenance-recorder-0.1.0.zip` (or similarly-versioned name) exists. **VERIFY AT EXECUTION** — this is the first real Gradle invocation against the IntelliJ Platform Gradle Plugin in this repo; expect to iterate on Steps 3–5 if resolution fails (missing repository, wrong DSL member, JDK mismatch). Do not silently downgrade platform version to "whatever works" without noting it in the commit message.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties \
  recorder/build.gradle.kts recorder/src/main/resources/META-INF/plugin.xml
git commit --no-gpg-sign -m "feat(recorder): plugin module scaffold targeting com.intellij.modules.platform"
```

---

### Task 2: Course public key placeholder (dev key)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/activation/CoursePublicKey.kt`
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/activation/CourseKeys.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/activation/CoursePublicKeyTest.kt`

**Interfaces:**
- Produces: `const val COURSE_PUBLIC_KEY_HEX: String` in `CoursePublicKey.kt` — 64 lowercase-hex chars (32-byte ed25519 pubkey). `CourseKeys.kt` re-exports it, mirroring the VS Code recorder's two-file split (`course-public-key.ts` holds the swappable constant; `course-keys.ts` is the stable import surface other modules use) so a future `build:prod`-equivalent Gradle task (Plan 9) can patch just one small file and `git checkout` it back afterward.

This mirrors `packages/recorder/src/activation/course-public-key.ts` and `course-keys.ts` exactly — same rationale, same file split, same "never commit a real course key" rule.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.provenance.recorder.activation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoursePublicKeyTest {
    @Test
    fun `course public key is 64 lowercase hex chars`() {
        assertEquals(64, COURSE_PUBLIC_KEY_HEX.length)
        assertTrue(COURSE_PUBLIC_KEY_HEX.matches(Regex("^[0-9a-f]{64}$")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.activation.CoursePublicKeyTest'`
Expected: FAIL — `COURSE_PUBLIC_KEY_HEX` unresolved reference.

- [ ] **Step 3: Write the implementation**

`CoursePublicKey.kt`:
```kotlin
package dev.provenance.recorder.activation

/**
 * The course's offline-signing public key, hex-encoded ed25519 (32 bytes => 64 hex chars).
 *
 * The constant below is a DEV placeholder. It exists so local development and
 * integration tests can sign+verify test manifests without a real course key.
 * To produce a production build with the real course public key, a Plan 9
 * Gradle task substitutes this constant from an env var, builds, then
 * `git checkout`'s this file to restore the dev key — mirrors the VS Code
 * recorder's `tools/embed-course-key.ts` flow. Never commit a real course key here.
 */
const val COURSE_PUBLIC_KEY_HEX: String =
    "46f91d5902c53816110b05ddedd2b8caa95b452d51e696f5327b52bf90bf483"
```
(64 hex chars — copy the digit count carefully; the VS Code recorder's dev key constant is the reference value if a byte-identical placeholder is wanted, though it need not match since this repo's dev signing keypair is independent.)

`CourseKeys.kt`:
```kotlin
package dev.provenance.recorder.activation

/**
 * The course public key is the verification anchor for every `.provenance-manifest`
 * manifest the recorder loads (PRD §4.1). Re-exported from a tiny sibling file so a
 * future production build task can swap that file in place without touching anything
 * else that imports COURSE_PUBLIC_KEY_HEX. See CoursePublicKey.kt.
 */
// Kotlin has no re-export syntax as clean as TS `export { X } from './y'`; this file
// exists for structural parity with the VS Code recorder's course-keys.ts and as the
// documented "stable import surface" — callers should still import COURSE_PUBLIC_KEY_HEX
// directly from this package (both symbols resolve to the same value).
```
**Note:** unlike TypeScript, Kotlin top-level `const val` in a package is already a single stable import path (`dev.provenance.recorder.activation.COURSE_PUBLIC_KEY_HEX`), so a literal re-export file adds no functional value in Kotlin — it exists only for structural parity with the two-file VS Code split. If this feels like pure cargo-culting during implementation, it's fine to drop `CourseKeys.kt` and note the deviation; this is a documentation/parity choice, not a format or architecture rule.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.activation.CoursePublicKeyTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/activation/CoursePublicKey.kt \
  recorder/src/main/kotlin/dev/provenance/recorder/activation/CourseKeys.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/activation/CoursePublicKeyTest.kt
git commit --no-gpg-sign -m "feat(recorder): dev course public key placeholder"
```

---

### Task 3: Pure manifest-activation decision (`evaluateManifestText`)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/activation/ManifestActivation.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/activation/ManifestActivationTest.kt`

**Interfaces:**
- Consumes: `dev.provenance.core.Manifest`, `dev.provenance.core.ManifestParse`, `dev.provenance.core.parseManifest`, `dev.provenance.core.verifyManifest` (Plan 2 Task 2).
- Produces:
  - `sealed interface ManifestActivation { data class Active(val manifest: Manifest) : ManifestActivation; data class Inactive(val reason: String) : ManifestActivation }`
  - `fun evaluateManifestText(text: String, coursePubkeyHex: String): ManifestActivation` — parses, then verifies; returns `Inactive` with a short machine-readable reason (`"parse_error"`, `"signature_invalid"`) on any failure, never throws. **Zero IntelliJ imports** — this is the "test the transform as a pure function" half of activation, exactly mirroring `loadAndVerifyManifest`'s Step 2/3 split in the VS Code recorder's `manifest-loader.ts`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.provenance.recorder.activation

import dev.provenance.core.Ed25519
import dev.provenance.core.canonicalize // adjust import to Plan 1/2's actual Canonical.canonicalize path if `canonicalize` isn't a top-level re-export
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManifestActivationTest {
    private fun signedManifestJson(
        assignmentId: String = "hw03",
        privkey: ByteArray,
    ): String {
        val payload = dev.provenance.core.Canonical.canonicalize(
            buildJsonObject {
                put("assignment_id", assignmentId)
                put("semester", "fa26")
                put("issued_at", "2026-09-15T00:00:00Z")
                put("files_under_review", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("hw03.py")) })
            }.toString(),
        )
        val sig = dev.provenance.core.Ed25519.bytesToHex(
            dev.provenance.core.Ed25519.sign(payload.toByteArray(Charsets.UTF_8), privkey),
        )
        return buildJsonObject {
            put("assignment_id", assignmentId)
            put("semester", "fa26")
            put("issued_at", "2026-09-15T00:00:00Z")
            put("files_under_review", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("hw03.py")) })
            put("sig", sig)
        }.toString()
    }

    @Test
    fun `valid signature yields Active with the parsed manifest`() {
        val (priv, pub) = dev.provenance.core.Ed25519.generateKeypair()
        val text = signedManifestJson(privkey = priv)
        val result = evaluateManifestText(text, dev.provenance.core.Ed25519.bytesToHex(pub))
        assertTrue(result is ManifestActivation.Active)
        assertEquals("hw03", (result as ManifestActivation.Active).manifest.assignmentId)
    }

    @Test
    fun `wrong pubkey yields Inactive signature_invalid`() {
        val (priv, _) = dev.provenance.core.Ed25519.generateKeypair()
        val (_, otherPub) = dev.provenance.core.Ed25519.generateKeypair()
        val text = signedManifestJson(privkey = priv)
        val result = evaluateManifestText(text, dev.provenance.core.Ed25519.bytesToHex(otherPub))
        assertTrue(result is ManifestActivation.Inactive)
        assertEquals("signature_invalid", (result as ManifestActivation.Inactive).reason)
    }

    @Test
    fun `malformed json yields Inactive parse_error, never throws`() {
        val result = evaluateManifestText("not json", "a".repeat(64))
        assertTrue(result is ManifestActivation.Inactive)
        assertEquals("parse_error", (result as ManifestActivation.Inactive).reason)
    }

    @Test
    fun `well-formed but tampered field yields Inactive signature_invalid`() {
        val (priv, pub) = dev.provenance.core.Ed25519.generateKeypair()
        val text = signedManifestJson(assignmentId = "hw03", privkey = priv)
            .replace("\"hw03\"", "\"hw04\"") // tamper after signing
        val result = evaluateManifestText(text, dev.provenance.core.Ed25519.bytesToHex(pub))
        assertTrue(result is ManifestActivation.Inactive)
    }
}
```
**Note on the test helper:** this hand-rolls a signed manifest using Plan 2's `Ed25519`/`Canonical` primitives rather than a fixture, since Plan 2 didn't ship a `signManifest` convenience function (only `verifyManifest`). If `core/` already grew one by the time this task starts, prefer it and delete the inline helper — check `core/src/main/kotlin/dev/provenance/core/Manifest.kt` first.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.activation.ManifestActivationTest'`
Expected: FAIL — `evaluateManifestText` / `ManifestActivation` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package dev.provenance.recorder.activation

import dev.provenance.core.Manifest
import dev.provenance.core.ManifestParse
import dev.provenance.core.parseManifest
import dev.provenance.core.verifyManifest

/** Activation decision for one candidate manifest file. Never throws. */
sealed interface ManifestActivation {
    data class Active(val manifest: Manifest) : ManifestActivation
    data class Inactive(val reason: String) : ManifestActivation
}

/**
 * Pure parse+verify of manifest file text against the course public key.
 * Zero IntelliJ imports — mirrors the VS Code recorder's loadAndVerifyManifest
 * Steps 2-3 (manifest-loader.ts), split out so it's testable without any platform seam.
 * PRD §4.1: "If the signature doesn't verify, the extension does nothing."
 */
fun evaluateManifestText(text: String, coursePubkeyHex: String): ManifestActivation {
    val parsed = parseManifest(text)
    if (parsed is ManifestParse.Err) {
        return ManifestActivation.Inactive("parse_error")
    }
    val manifest = (parsed as ManifestParse.Ok).manifest
    return if (verifyManifest(manifest, coursePubkeyHex)) {
        ManifestActivation.Active(manifest)
    } else {
        ManifestActivation.Inactive("signature_invalid")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.activation.ManifestActivationTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/activation/ManifestActivation.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/activation/ManifestActivationTest.kt
git commit --no-gpg-sign -m "feat(recorder): pure manifest-activation decision (parse+verify, no IntelliJ deps)"
```

---

### Task 4: VFS manifest loader (the platform seam)

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/activation/ManifestLoader.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/activation/ManifestLoaderTest.kt`

**Interfaces:**
- Consumes: `ManifestActivation`, `evaluateManifestText` (Task 3); `COURSE_PUBLIC_KEY_HEX` (Task 2).
- Produces:
  - `val MANIFEST_FILE_NAMES: List<String> = listOf(".provenance-manifest", "provenance-manifest")` — dotfile preferred, mirrors `manifest-loader.ts`'s `MANIFEST_FILE_NAMES` precedence.
  - `fun loadAndVerifyManifest(baseDir: VirtualFile, coursePubkeyHex: String = COURSE_PUBLIC_KEY_HEX): ManifestActivation` — tries each candidate name via `baseDir.findChild(name)` in order, reads the first hit with `VfsUtilCore.loadText`, delegates to `evaluateManifestText`. Returns `Inactive("no_manifest_file")` if neither exists, `Inactive("read_error")` on an IOException.
  - `fun loadAndVerifyManifest(project: Project, coursePubkeyHex: String = COURSE_PUBLIC_KEY_HEX): ManifestActivation` — thin wrapper: `project.guessProjectDir()` (or `Inactive("no_project_dir")`) then delegates to the `VirtualFile` overload.

**Test intent:** this is the platform-facing half — test it with `BasePlatformTestCase`, using `myFixture.tempDirFixture`/`myFixture.addFileToProject` to materialize real `VirtualFile`s, per CLAUDE.md's "mock at the seam" — the seam here *is* the VFS, so we exercise the real VFS in a headless test fixture rather than mocking `VirtualFile`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.provenance.recorder.activation

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ManifestLoaderTest : BasePlatformTestCase() {

    private fun signedManifestJson(privkey: ByteArray, assignmentId: String = "hw03"): String {
        // Same inline signing helper as ManifestActivationTest (Task 3) — consider
        // extracting to a shared test-fixtures file if duplicated a third time.
        val payload = dev.provenance.core.Canonical.canonicalize(
            """{"assignment_id":"$assignmentId","semester":"fa26","issued_at":"2026-09-15T00:00:00Z","files_under_review":["hw03.py"]}""",
        )
        val sig = dev.provenance.core.Ed25519.bytesToHex(
            dev.provenance.core.Ed25519.sign(payload.toByteArray(Charsets.UTF_8), privkey),
        )
        return """{"assignment_id":"$assignmentId","semester":"fa26","issued_at":"2026-09-15T00:00:00Z","files_under_review":["hw03.py"],"sig":"$sig"}"""
    }

    fun `test returns Inactive no_manifest_file when neither name exists`() {
        val baseDir = myFixture.tempDirFixture.getFile(".")!!
        val result = loadAndVerifyManifest(baseDir, "a".repeat(64))
        assertTrue(result is ManifestActivation.Inactive)
        assertEquals("no_manifest_file", (result as ManifestActivation.Inactive).reason)
    }

    fun `test returns Active for a valid dotfile manifest`() {
        val (priv, pub) = dev.provenance.core.Ed25519.generateKeypair()
        myFixture.addFileToProject(".provenance-manifest", signedManifestJson(priv))
        val baseDir = myFixture.tempDirFixture.getFile(".")!!
        val result = loadAndVerifyManifest(baseDir, dev.provenance.core.Ed25519.bytesToHex(pub))
        assertTrue(result is ManifestActivation.Active)
    }

    fun `test prefers dotfile over plain name when both present`() {
        val (priv, pub) = dev.provenance.core.Ed25519.generateKeypair()
        myFixture.addFileToProject(".provenance-manifest", signedManifestJson(priv, assignmentId = "dot"))
        myFixture.addFileToProject("provenance-manifest", signedManifestJson(priv, assignmentId = "plain"))
        val baseDir = myFixture.tempDirFixture.getFile(".")!!
        val result = loadAndVerifyManifest(baseDir, dev.provenance.core.Ed25519.bytesToHex(pub))
        assertTrue(result is ManifestActivation.Active)
        assertEquals("dot", (result as ManifestActivation.Active).manifest.assignmentId)
    }

    fun `test project overload returns Inactive when project dir has no manifest`() {
        val result = loadAndVerifyManifest(project, "a".repeat(64))
        assertTrue(result is ManifestActivation.Inactive)
    }
}
```
**VERIFY AT EXECUTION:** `myFixture.tempDirFixture.getFile(".")` as "the project's base VirtualFile directory" is the expected idiom for `BasePlatformTestCase`'s light fixture, but the exact accessor (`tempDirFixture.getFile(".")` vs `myFixture.file.parent` vs `LightPlatformTestCase.getSourceRoot()`) may need adjustment once run against a real IntelliJ test sandbox — this is exactly the kind of platform-fixture detail that's unverifiable without executing Gradle. If it doesn't compile, check `testing-plugins.html` and IntelliJ Platform Explorer usages of `BasePlatformTestCase` + `addFileToProject` for the current idiom.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.activation.ManifestLoaderTest'`
Expected: FAIL — `loadAndVerifyManifest` unresolved (and/or fixture-API compile errors to resolve per the VERIFY note above).

- [ ] **Step 3: Write the implementation**

```kotlin
package dev.provenance.recorder.activation

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/** Candidate manifest file names, in precedence order (dotfile canonical; plain form is a fallback). */
val MANIFEST_FILE_NAMES: List<String> = listOf(".provenance-manifest", "provenance-manifest")

/**
 * Read, parse, and verify the manifest file in the given base directory.
 * PRD §4.1: try each candidate name in precedence order; only Inactive("no_manifest_file")
 * if none exist. Never throws.
 */
fun loadAndVerifyManifest(
    baseDir: VirtualFile,
    coursePubkeyHex: String = COURSE_PUBLIC_KEY_HEX,
): ManifestActivation {
    for (name in MANIFEST_FILE_NAMES) {
        val file = baseDir.findChild(name) ?: continue
        val text = try {
            VfsUtilCore.loadText(file)
        } catch (e: IOException) {
            return ManifestActivation.Inactive("read_error")
        }
        return evaluateManifestText(text, coursePubkeyHex)
    }
    return ManifestActivation.Inactive("no_manifest_file")
}

/** Project-level convenience wrapper — resolves the workspace root, then delegates. */
fun loadAndVerifyManifest(
    project: Project,
    coursePubkeyHex: String = COURSE_PUBLIC_KEY_HEX,
): ManifestActivation {
    val baseDir = project.guessProjectDir() ?: return ManifestActivation.Inactive("no_project_dir")
    return loadAndVerifyManifest(baseDir, coursePubkeyHex)
}
```
**VERIFY AT EXECUTION:** `com.intellij.openapi.project.guessProjectDir` is a Kotlin extension function in the platform SDK (`ProjectUtil.kt` historically); confirm the import path resolves in the pinned platform version. No forced VFS refresh is performed here — the assumption is the manifest file is already on disk before the IDE opens the project, so the platform's own project-open VFS scan should have it. If real-world testing shows a stale-cache miss (manifest dropped into the workspace by tooling *after* the IDE's initial scan but before this activity runs), revisit with `LocalFileSystem.getInstance().refreshAndFindFileByPath(...)` — flagged here rather than added speculatively, per CLAUDE.md's "don't invent architecture," and because design.md scopes the VFS-cache-timing risk specifically to Plan 5's external-change detection, not one-time activation reads.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.activation.ManifestLoaderTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/activation/ManifestLoader.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/activation/ManifestLoaderTest.kt
git commit --no-gpg-sign -m "feat(recorder): VFS manifest loader (platform seam over evaluateManifestText)"
```

---

### Task 5: `RecorderState` project service + `ProjectActivity` activation wiring

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/activation/RecorderState.kt`
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/activation/RecorderActivationActivity.kt`
- Modify: `recorder/src/main/resources/META-INF/plugin.xml` — register the activity under `postStartupActivity`.
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/activation/RecorderStateTest.kt`

**Interfaces:**
- Consumes: `loadAndVerifyManifest(Project, ...)`, `ManifestActivation` (Task 4).
- Produces:
  - `@Service(Service.Level.PROJECT) class RecorderState { val manifest: Manifest?; val isActive: Boolean; fun activate(m: Manifest); fun deactivate() }` — light service, retrieved via `project.service<RecorderState>()`.
  - `class RecorderActivationActivity : ProjectActivity { override suspend fun execute(project: Project) }` — calls `loadAndVerifyManifest(project)`, updates `RecorderState`, then asks the status bar to re-check widget availability (Task 6 depends on this call existing, even though the widget factory doesn't exist until Task 6 — guard the call so Task 5 compiles standalone, e.g. behind a small `refreshStatusBar(project)` function this task defines and Task 6 doesn't need to touch).

**Test intent:** `RecorderState` is a trivial holder — test it directly as a service via `BasePlatformTestCase` (`project.service<RecorderState>()`), asserting `isActive` flips on `activate`/`deactivate`. The `ProjectActivity` itself is thin glue over already-tested pieces (Task 4's loader + this task's state) — cover it with one `BasePlatformTestCase` integration-style test that drops a valid manifest into the fixture project and asserts `RecorderState.isActive` becomes true after invoking `execute` directly (not via real project-open timing, which is unverifiable in a unit test).

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.provenance.recorder.activation

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

class RecorderStateTest : BasePlatformTestCase() {

    fun `test isActive is false by default`() {
        val state = project.service<RecorderState>()
        assertFalse(state.isActive)
    }

    fun `test activate then isActive is true, manifest is stored`() {
        val state = project.service<RecorderState>()
        val (priv, _) = dev.provenance.core.Ed25519.generateKeypair()
        // Build a minimal Manifest directly rather than round-tripping through JSON —
        // RecorderState doesn't care how the Manifest was produced.
        val manifest = dev.provenance.core.Manifest(
            assignmentId = "hw03",
            semester = "fa26",
            issuedAt = "2026-09-15T00:00:00Z",
            filesUnderReview = listOf("hw03.py"),
            sig = "a".repeat(128),
        )
        state.activate(manifest)
        assertTrue(state.isActive)
        assertEquals("hw03", state.manifest?.assignmentId)
    }

    fun `test deactivate clears manifest`() {
        val state = project.service<RecorderState>()
        val manifest = dev.provenance.core.Manifest("hw03", "fa26", "2026-09-15T00:00:00Z", listOf("hw03.py"), "a".repeat(128))
        state.activate(manifest)
        state.deactivate()
        assertFalse(state.isActive)
        assertNull(state.manifest)
    }

    fun `test RecorderActivationActivity activates state for a valid manifest`() = runBlocking {
        val (priv, pub) = dev.provenance.core.Ed25519.generateKeypair()
        val payload = dev.provenance.core.Canonical.canonicalize(
            """{"assignment_id":"hw03","semester":"fa26","issued_at":"2026-09-15T00:00:00Z","files_under_review":["hw03.py"]}""",
        )
        val sig = dev.provenance.core.Ed25519.bytesToHex(
            dev.provenance.core.Ed25519.sign(payload.toByteArray(Charsets.UTF_8), priv),
        )
        myFixture.addFileToProject(
            ".provenance-manifest",
            """{"assignment_id":"hw03","semester":"fa26","issued_at":"2026-09-15T00:00:00Z","files_under_review":["hw03.py"],"sig":"$sig"}""",
        )
        // Task 2's dev COURSE_PUBLIC_KEY_HEX won't match this test's freshly generated
        // keypair — RecorderActivationActivity must accept an injectable pubkey for tests,
        // OR this test asserts Inactive-with-dev-key and a separate lower-level test (Task 4)
        // already proves the Active path. Resolve this seam explicitly in Step 3 below.
        RecorderActivationActivity().execute(project)
        // Assertion depends on which seam choice Step 3 makes — see note there.
    }
}
```
**Design note surfaced by this test:** `ProjectActivity.execute(project: Project)` has a platform-fixed signature — it cannot take an injectable pubkey parameter the way `ActivateDeps` does in the VS Code recorder's `activateImpl`. Two options: (a) accept that `RecorderActivationActivity` always uses `COURSE_PUBLIC_KEY_HEX` and test activation end-to-end only via the already-covered `loadAndVerifyManifest(project, customPubkey)` path (Task 4), keeping the activity test to "does it call the loader and update state" using a *fake* loader injected via a constructor parameter with a default; or (b) give `RecorderActivationActivity` an internal `@JvmOverloads`-style testing constructor. **Recommendation:** do (a) — add a constructor parameter `loader: (Project, String) -> ManifestActivation = ::loadAndVerifyManifest` (defaulted to production wiring), so the test can inject a fake that returns `Active` deterministically without needing real ed25519 signing in this test file. Rewrite the last test above accordingly once implementing.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.activation.RecorderStateTest'`
Expected: FAIL — `RecorderState` / `RecorderActivationActivity` unresolved.

- [ ] **Step 3: Write the implementation**

`RecorderState.kt`:
```kotlin
package dev.provenance.recorder.activation

import com.intellij.openapi.components.Service
import dev.provenance.core.Manifest

/**
 * Project-scoped activation state: whether this workspace's manifest verified,
 * and if so, the manifest itself. Consulted by RecordingStatusBarWidgetFactory
 * (Task 6) to decide whether to show the "Provenance: recording" widget.
 * PRD §4.1 / CLAUDE.md: activation is the privacy gate — everything else in the
 * plugin should eventually consult this before recording anything (Plan 4+).
 */
@Service(Service.Level.PROJECT)
class RecorderState {
    @Volatile
    var manifest: Manifest? = null
        private set

    val isActive: Boolean get() = manifest != null

    fun activate(m: Manifest) {
        manifest = m
    }

    fun deactivate() {
        manifest = null
    }
}
```

`RecorderActivationActivity.kt`:
```kotlin
package dev.provenance.recorder.activation

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Runs once per project open. PRD §4.1: activate only when the workspace-root
 * manifest verifies; otherwise do nothing observable (no state change beyond
 * RecorderState staying inactive, no I/O, no UI).
 *
 * `loader` is injectable for tests; production uses the real VFS-backed
 * loadAndVerifyManifest(Project, String) from Task 4.
 */
class RecorderActivationActivity(
    private val loader: (Project, String) -> ManifestActivation = ::loadAndVerifyManifest,
) : ProjectActivity {
    override suspend fun execute(project: Project) {
        val result = loader(project, COURSE_PUBLIC_KEY_HEX)
        val state = project.service<RecorderState>()
        when (result) {
            is ManifestActivation.Active -> state.activate(result.manifest)
            is ManifestActivation.Inactive -> state.deactivate()
        }
        refreshStatusBarWidget(project)
    }
}

/**
 * Re-checks status-bar widget availability after the (asynchronous) activation
 * decision lands. Extracted to its own function so this file compiles before
 * Task 6's widget factory exists, and so Task 6 doesn't need to touch this file.
 * VERIFY AT EXECUTION: StatusBarWidgetsManager.updateAllWidgets() is the
 * researched API for this; confirm against status-bar-widgets.html /
 * StatusBarWidgetsManager source before relying on it.
 */
internal fun refreshStatusBarWidget(project: Project) {
    project.service<com.intellij.openapi.wm.StatusBarWidgetsManager>().updateAllWidgets()
}
```
`RecorderActivationActivity()`'s no-arg constructor (using the default `loader` param) is what `plugin.xml` instantiates in production — the platform instantiates extension classes via a no-arg (or otherwise DI-resolvable) constructor, so the default-parameter constructor must remain the only one the platform sees. **VERIFY AT EXECUTION** that a Kotlin default-parameter primary constructor satisfies the platform's instantiation requirements for `ProjectActivity` — if not, split into a no-arg public constructor plus an internal test-only secondary constructor.

- [ ] **Step 4: Register the activity in `plugin.xml`**

```xml
<extensions defaultExtensionNs="com.intellij">
    <postStartupActivity implementation="dev.provenance.recorder.activation.RecorderActivationActivity"/>
</extensions>
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.activation.RecorderStateTest'`
Expected: PASS, once the last test is rewritten per the Step 1 design note to inject a fake loader instead of a real signed manifest.

- [ ] **Step 6: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/activation/RecorderState.kt \
  recorder/src/main/kotlin/dev/provenance/recorder/activation/RecorderActivationActivity.kt \
  recorder/src/main/resources/META-INF/plugin.xml \
  recorder/src/test/kotlin/dev/provenance/recorder/activation/RecorderStateTest.kt
git commit --no-gpg-sign -m "feat(recorder): RecorderState service + ProjectActivity activation wiring"
```

---

### Task 6: Status-bar widget ("Provenance: recording")

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/statusbar/RecordingStatusBarWidget.kt`
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/statusbar/RecordingStatusBarWidgetFactory.kt`
- Modify: `recorder/src/main/resources/META-INF/plugin.xml` — register `statusBarWidgetFactory`.
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/statusbar/RecordingStatusBarWidgetFactoryTest.kt`

**Interfaces:**
- Consumes: `RecorderState` (Task 5).
- Produces:
  - `class RecordingStatusBarWidget(project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation` — `ID() = "ProvenanceRecordingWidget"`, `getText() = "Provenance: recording"`, `getTooltipText() = "Provenance recorder is active for this assignment."`, no click action (`getClickConsumer() = null`), `dispose()` is a documented no-op (nothing owned yet — Plan 4+ may need to release something here; keeping the hook per CLAUDE.md's "every listener/timer has a dispose() hook" rule even when currently empty).
  - `class RecordingStatusBarWidgetFactory : StatusBarWidgetFactory` — `getId()` matches the widget's `ID()`; `isAvailable(project) = project.service<RecorderState>().isActive`; `createWidget(project) = RecordingStatusBarWidget(project)`; `disposeWidget(widget) = widget.dispose()`; `canBeEnabledOn(statusBar) = true`.

**Test intent:** `BasePlatformTestCase`. Assert `factory.isAvailable(project)` is `false` before `RecorderState.activate(...)`, `true` after — this is the load-bearing behavior (PRD §4.1's "no UI noise" when inactive). Assert the created widget's `getText()` matches the PRD-quoted string exactly (`"Provenance: recording"` — recorder PRD §4.1 quotes this verbatim, so it's a spec requirement, not a style choice).

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.provenance.recorder.statusbar

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.recorder.activation.RecorderState

class RecordingStatusBarWidgetFactoryTest : BasePlatformTestCase() {

    fun `test widget is not available before activation`() {
        val factory = RecordingStatusBarWidgetFactory()
        assertFalse(factory.isAvailable(project))
    }

    fun `test widget is available after activation`() {
        val manifest = dev.provenance.core.Manifest("hw03", "fa26", "2026-09-15T00:00:00Z", listOf("hw03.py"), "a".repeat(128))
        project.service<RecorderState>().activate(manifest)
        val factory = RecordingStatusBarWidgetFactory()
        assertTrue(factory.isAvailable(project))
    }

    fun `test widget text matches the PRD-specified indicator string`() {
        val widget = RecordingStatusBarWidgetFactory().createWidget(project)
        val presentation = widget.getPresentation() as com.intellij.openapi.wm.StatusBarWidget.TextPresentation
        assertEquals("Provenance: recording", presentation.getText())
    }

    fun `test factory id matches widget ID`() {
        val factory = RecordingStatusBarWidgetFactory()
        val widget = factory.createWidget(project)
        assertEquals(factory.getId(), widget.ID())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.statusbar.RecordingStatusBarWidgetFactoryTest'`
Expected: FAIL — `RecordingStatusBarWidgetFactory` unresolved.

- [ ] **Step 3: Write the implementation**

`RecordingStatusBarWidget.kt`:
```kotlin
package dev.provenance.recorder.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import java.awt.event.MouseEvent
import java.util.function.Consumer

/**
 * Non-dismissible status bar item indicating that recording is active.
 * PRD §4.1: "shows a non-dismissible status bar item ('Provenance: recording')
 * so the student is always aware that telemetry is active."
 * Mirrors packages/recorder/src/activation/status-bar.ts.
 */
class RecordingStatusBarWidget(@Suppress("unused") private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        // Nothing to wire up beyond text; no click/hover state to install.
    }

    override fun dispose() {
        // No owned resources yet. Kept as an explicit hook (CLAUDE.md: every
        // listener/timer/watcher has a dispose() path) for when Plan 4+ wires
        // this widget to live session state.
    }

    override fun getText(): String = "Provenance: recording"

    override fun getTooltipText(): String = "Provenance recorder is active for this assignment."

    override fun getAlignment(): Float = java.awt.Component.LEFT_ALIGNMENT

    override fun getClickConsumer(): Consumer<MouseEvent>? = null

    companion object {
        const val WIDGET_ID = "ProvenanceRecordingWidget"
    }
}
```

`RecordingStatusBarWidgetFactory.kt`:
```kotlin
package dev.provenance.recorder.statusbar

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import dev.provenance.recorder.activation.RecorderState

/**
 * Registers the always-visible recording indicator. isAvailable() is the
 * activation gate for the widget itself: PRD §4.1 requires "no UI noise" when
 * the workspace manifest didn't verify, so the widget must not render at all
 * (not render-then-hide) until RecorderState.isActive is true.
 */
class RecordingStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = RecordingStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = "Provenance Recording Indicator"

    override fun isAvailable(project: Project): Boolean =
        project.service<RecorderState>().isActive

    override fun createWidget(project: Project): StatusBarWidget =
        RecordingStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
```

- [ ] **Step 4: Register the widget factory in `plugin.xml`**

```xml
<extensions defaultExtensionNs="com.intellij">
    <postStartupActivity implementation="dev.provenance.recorder.activation.RecorderActivationActivity"/>
    <statusBarWidgetFactory
        id="ProvenanceRecordingWidget"
        implementation="dev.provenance.recorder.statusbar.RecordingStatusBarWidgetFactory"
        order="first"/>
</extensions>
```
The `id` attribute must match `RecordingStatusBarWidget.WIDGET_ID` exactly (per `status-bar-widgets.html`'s stated requirement that the XML `id` matches `StatusBarWidgetFactory.getId()`).

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.statusbar.RecordingStatusBarWidgetFactoryTest'`
Expected: PASS (4 tests).

- [ ] **Step 6: Run the full `recorder/` test suite**

Run: `./gradlew :recorder:test`
Expected: PASS — all of Tasks 2–6's suites green.

- [ ] **Step 7: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/statusbar/ \
  recorder/src/main/resources/META-INF/plugin.xml \
  recorder/src/test/kotlin/dev/provenance/recorder/statusbar/RecordingStatusBarWidgetFactoryTest.kt
git commit --no-gpg-sign -m "feat(recorder): always-visible Provenance recording status-bar widget"
```

---

### Task 7: Sideload build + manual verification

**Files:**
- Modify: `README.md` — replace the placeholder "Build tasks are placeholders" note with real instructions.
- No new source files — this task packages and manually exercises what Tasks 1–6 built.

**Goal of this task:** produce `recorder/build/distributions/*.zip` and confirm, by hand, that a real IDE loads it, shows no UI on a plain folder, and shows the widget on a folder with a valid `.provenance-manifest`. This is the PRD §4.1 activation behavior end-to-end, outside the headless test fixtures.

- [ ] **Step 1: Build the sideload artifact**

Run: `./gradlew :recorder:buildPlugin`
Expected: BUILD SUCCESSFUL; a `.zip` under `recorder/build/distributions/`.

- [ ] **Step 2: Manual verification — inactive workspace**

Install the `.zip` into a real JetBrains IDE (any IDE that satisfies `com.intellij.modules.platform` — IntelliJ IDEA Community is the simplest to have on hand): Settings/Preferences → Plugins → gear icon → Install Plugin from Disk → select the `.zip` → restart.

Open a plain folder with no `.provenance-manifest`. Expected: no status bar item, no `.provenance/` directory created, nothing in the IDE log referencing Provenance beyond (at most) a debug-level "no manifest" trace. **VERIFY AT EXECUTION** — this is the actual product behavior check; record the result in the task's commit message or a follow-up note if anything deviates (e.g., the widget briefly flashes before `isAvailable` is re-checked — would indicate Task 6's gating needs revisiting).

- [ ] **Step 3: Manual verification — active workspace**

Sign a test manifest with the Task 2 dev keypair (or reuse Plan 2's `core/src/test/resources/conformance/manifest.json` fixture if its pubkey happens to match `COURSE_PUBLIC_KEY_HEX` — otherwise generate one, e.g. via a small throwaway `main()` calling `core`'s `Ed25519`/`signManifest`-equivalent). Drop it as `.provenance-manifest` at the root of a scratch folder, open that folder in the sideloaded IDE.

Expected: the "Provenance: recording" status bar item appears within a few seconds of project open (async `ProjectActivity` + widget refresh). **VERIFY AT EXECUTION.**

- [ ] **Step 4: `runIde` as the faster dev loop**

Note (no action needed beyond documenting): `./gradlew :recorder:runIde` launches a sandboxed IDE instance with the plugin pre-loaded, avoiding the manual install-from-disk cycle for iteration during Plans 4+. Prefer it over repeated sideload installs once past this initial verification.

- [ ] **Step 5: Update `README.md`**

Replace:
```markdown
(Build tasks are placeholders until the Gradle project lands; see
`docs/design.md` §8 for the build sequence.)
```
with:
```markdown
As of Plan 3, `./gradlew :recorder:buildPlugin` / `:recorder:runIde` / `:recorder:test`
are real. The plugin activates only on a workspace with a valid, course-signed
`.provenance-manifest` at the root (PRD §4.1) — on any other folder it does nothing
observable. To sideload for manual testing: Settings → Plugins → gear icon →
Install Plugin from Disk → the `.zip` from `recorder/build/distributions/`.
Marketplace publishing and the production course-key embedding are Plan 9.
```

- [ ] **Step 6: Commit**

```bash
git add README.md
git commit --no-gpg-sign -m "docs: real build/sideload instructions after plugin scaffold lands"
```

---

## Self-Review

**Spec coverage (design.md §8 step 2 — "activation + manifest verification + status-bar widget"):** Gradle/plugin.xml scaffold targeting `com.intellij.modules.platform` (Task 1), dev course-key placeholder mirroring the VS Code recorder's swap-at-build pattern (Task 2), pure parse+verify decision function with zero IntelliJ deps (Task 3), the VFS platform seam over it (Task 4), `ProjectActivity` activation wiring + project-scoped state (Task 5), the always-visible, activation-gated status bar widget (Task 6), and a sideload `.zip` plus manual verification (Task 7, explicitly scoped separately from Plan 9's Marketplace packaging per the task brief). `doc.*` event wiring, the session writer, hash-chain-backed log writes, and bundle seal are explicitly **out of scope** — Plan 4.

**Placeholder scan:** all Kotlin/XML in Tasks 1–7 is complete, runnable code, not sketches. The exceptions are intentional and labeled: `pluginSinceBuild`/`platformVersion`/Gradle-plugin-version numbers (Task 1, explicitly flagged `VERIFY AT EXECUTION` because they're fast-moving and this plan can't run Gradle to confirm them), and the `myFixture.tempDirFixture.getFile(".")` test-fixture idiom (Task 4, flagged for the same reason).

**Type consistency:** `ManifestActivation` (Task 3) is consumed unchanged by Task 4's `loadAndVerifyManifest` overloads, Task 5's `RecorderActivationActivity`, and implicitly by Task 6 via `RecorderState.isActive`. `Manifest`/`ManifestParse`/`parseManifest`/`verifyManifest` are Plan 2 Task 2 types/functions, used here without modification (no changes to `core/`). `COURSE_PUBLIC_KEY_HEX` (Task 2) is the sole default pubkey threaded through Tasks 4–5.

**Fidelity note (required by task brief):** this plan's IntelliJ Platform API research came from `WebFetch`/`WebSearch` against `plugins.jetbrains.com/docs/intellij`, not from running Gradle or an IDE against real source. Two classes of risk:
1. **High confidence, doc-quoted:** `plugin.xml` shape, `com.intellij.modules.platform` targeting, `StatusBarWidgetFactory`/`StatusBarWidget.TextPresentation` method names, `@Service(Service.Level.PROJECT)` + `project.service<T>()`, `Disposable`/`Disposer.register`, `BasePlatformTestCase` as the headless test base — these came back consistently across independent fetches and match the settled JetBrains SDK docs.
2. **Lower confidence, flagged `VERIFY AT EXECUTION` inline:** exact `build.gradle.kts` DSL member names for the 2.x Gradle plugin (`intellijIdeaCommunity(...)`, `bundledModule(...)`, `testFramework(TestFrameworkType.Platform)`, `pluginConfiguration { }`), the current stable platform/Gradle/JDK version triple, `StatusBarWidgetsManager.updateAllWidgets()` as the async-refresh trigger, and the `postStartupActivity` EP id for `ProjectActivity` vs. the deprecated `StartupActivity`. The `WebFetch` tool's summaries were internally inconsistent across calls on version numbers specifically (e.g. citing IC `2024.3`, `2025.2.6.1`, and `2026.1.4` in different snippets for what should be one current release) — treated as evidence the fetch summarizer paraphrases rather than quotes verbatim, hence the blanket caution on any *number* in this plan rather than just the ones explicitly flagged.

**Open questions carried forward (non-blocking, per design.md §11):** plugin id `edu.berkeley.provenance.recorder` is a placeholder (design.md open question 4); Marketplace vs. sideload-only distribution is still open (design.md open question 2) — this plan only builds the sideload path, deliberately deferring the Marketplace decision to Plan 9.
