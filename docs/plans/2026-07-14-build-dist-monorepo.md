# Production Build, Distribution & Monorepo Changes Implementation Plan (Plan 9 of the provjet series)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `provjet` a real production build: embed the course public key from an env var at build time (never committed), compute `extension_hash` the same reproducible way the VS Code recorder does and wire it into the seal, sign and publish the plugin to JetBrains Marketplace (the **primary** distribution channel — Plan 3's sideload build is early-testing-only), and make the two small, additive monorepo changes design.md §7 calls for: an allowlist entry and a golden-vector export script.

**Architecture:** Two repos, two conventions. In `provenance-jetbrains-recorder`: a Gradle `buildProd`/`publishProd` task chain mirroring the VS Code recorder's `build:prod` (`tools/embed-course-key.ts` → build → package → `git checkout`), using the **IntelliJ Platform Gradle Plugin**'s `signPlugin`/`verifyPlugin`/`publishPlugin`/`patchPluginXml` tasks with all secrets sourced from environment variables. `extension_hash` is computed by a Kotlin port of the VS Code recorder's `computeExtensionHash` algorithm (sorted relative-path + NUL + file-bytes, SHA-256) applied to the plugin's installed/unpacked distribution tree — **not** a literal hash of the `.zip` file's bytes, because zip byte-layout (timestamps, compression, entry order) isn't reproducible across tools, which is exactly why the VS Code recorder hashes the unpacked `dist/` tree instead of the `.vsix` bytes (see Task 1's design note). In `provenance` (the monorepo): a new `tools/export-conformance-vectors.ts` script that generates language-neutral JSON vectors + a golden bundle straight from `log-core` and `analysis-core`'s existing test-support helper, replacing Plans 1–2's hand-authored fixtures; and one allowlist entry in `known-good-extension-hashes.json`.

**Tech Stack:** Kotlin/Gradle (Kotlin DSL), IntelliJ Platform Gradle Plugin 2.x, BouncyCastle/erdtman JCS (already in `core/` per Plans 1–2). Monorepo side: Node/TypeScript (`tools/`, matching `tools/embed-course-key.ts`'s conventions), `@provenance/log-core`, `@provenance/analysis-core`'s `test-support/build-test-bundle.js` deep-export.

## Plan series (context)

Plan 9 of the series declared in Plan 1's header. Plans 3–8 (plugin scaffold, activation, doc wiring, external-change detection, paste detection, terminal/git, checkpoints) are **not yet implemented** in this repo as of this writing — only Plan 1's Gradle scaffold and a partial `core/` module exist on disk. This plan is written ahead of that work, per the series' own stated order. Several tasks below therefore declare an explicit **prerequisite guard**: if the file/module they depend on doesn't exist yet because an earlier plan hasn't been executed, the step says so and stops rather than inventing that earlier plan's design. Do not paper over a missing prerequisite by improvising it here — that is exactly the "inventing architecture" failure mode CLAUDE.md warns about.

## Global Constraints

- **Never commit a real course key.** (CLAUDE.md, design.md §6) The embed task must refuse to run without `PROVENANCE_COURSE_PUBLIC_KEY_HEX` set, must validate it's 64 lowercase hex, and — mirroring `tools/embed-course-key.ts` — must refuse if it equals whatever dev key is checked in, so a misconfigured release can never silently ship the dev key.
- **`extension_hash` = SHA-256 over the plugin's installed distribution file tree** (sorted relative path + NUL byte + file bytes), not a hash of raw `.zip` bytes. This is a **deliberate deviation from a literal reading of design.md §6's "SHA-256 of the plugin distribution .zip"** — see Task 1's design note for why, and why this is actually the more faithful port of the VS Code recorder's real behavior (`packages/recorder/src/commands/extension-hash.ts` hashes the unpacked `dist/` directory, and the allowlist's own doc-comment language ("SHA-256 of the built .vsix file") is itself loose paraphrase of that same tree-hash algorithm, confirmed by reading the implementation, not just the comment).
- **The sealed manifest is never modified after seal** (CLAUDE.md, design.md §6). `extension_hash` must be computed and embedded *before* signing, exactly once, at seal time — never patched in afterward.
- **`core/` stays free of IntelliJ Platform / plugin dependencies** (CLAUDE.md architecture rule). The tree-hashing *algorithm* (Task 1) is pure JVM (`java.nio.file`, `java.security.MessageDigest`) and belongs in `core/`; only the "find my own installed plugin directory" part (which needs `PluginManagerCore`) belongs in `recorder/`.
- **This repo never changes Provenance monorepo source except the two additive things in design.md §7** (allowlist entry, golden-vector export script) — done in the monorepo, with the monorepo's own conventions (Node/TypeScript `tools/`, `git commit --no-gpg-sign`, conventional-commit prefixes, no new dependencies without approval).
- **Monorepo edits are small and additive** (monorepo CLAUDE.md: "Stay in scope," "Small diffs"). Do not touch analyzer/server/recorder(TS) source. Do not add an `events` table, do not modify `manifest.json`/`manifest.sig` shapes, do not change the log format.
- **Commits (both repos):** conventional prefixes, `git commit --no-gpg-sign`, **no** `Co-Authored-By: Claude` trailer, explicit pathspec (never `git add -A`).
- **Real credentials are never invented, faked, or simulated as if real.** Every step that needs a real course keypair, a real JetBrains Marketplace account/token, or a real code-signing certificate is marked **REQUIRES OPERATOR SECRETS — cannot be executed autonomously**. Those steps are fully specified (exact commands, exact env vars) so a human operator can run them; an agent must stop at that step and hand off, not fabricate placeholder secrets and pretend the pipeline ran for real.
- **External API details are cited, not assumed.** Where the IntelliJ Platform Gradle Plugin's exact Kotlin DSL shape could not be pinned with full confidence from documentation research (2.x extension-block DSL vs. older task-level configuration), both forms are shown with a citation and an explicit "confirm against the pinned plugin version's docs before relying on this" callout — do not silently pick one and hope.

### Cited sources (JetBrains docs, fetched 2026-07-14)

- Tasks reference — signPlugin, verifyPlugin, publishPlugin, patchPluginXml, buildPlugin, prepareSandbox, runIde: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html
- Plugin signing — key generation (openssl), `signPlugin` properties: https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
- Publishing a plugin — Marketplace account, Personal Access Token, `publishPlugin`, channels, "first publish must be manual": https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html
- IntelliJ Platform Gradle Plugin overview (2.x, version 2.18.1 as fetched) — dependency/repository DSL: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
- `plugin.xml` reference — `<id>`, `<version>`, `<idea-version since-build until-build>`, and that these "can be skipped... if `patchPluginXml` is configured": https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html

---

## Part A — `provenance-jetbrains-recorder` (this repo)

### Task 1: `core/` — `DirectoryHash`, the reproducible tree-hash algorithm

**Design note (read before implementing):** design.md §6 says `extension_hash` = "SHA-256 of the plugin distribution `.zip`." The *actual* VS Code recorder does not hash `.vsix` bytes — `packages/recorder/src/commands/extension-hash.ts` (monorepo) walks the **unpacked `dist/` directory**, sorts entries by relative path, and hashes `<relpath>\0<bytes>` concatenated across all files. This is deliberate: zip byte layout (timestamps, compression settings, central-directory entry order) is not guaranteed reproducible across zip tools or JVM versions, so hashing zip bytes directly would make the allowlist brittle to unrelated toolchain changes. Hashing the *extracted tree* is deterministic regardless of how the archive was produced — and it's the only thing computable at runtime anyway, since the running IDE has the plugin's files on disk, not the zip that installed them. This task ports that exact algorithm to Kotlin. Treat "SHA-256 of the distribution .zip" in design.md as shorthand for "SHA-256 of the distribution's file tree" — the same shorthand the monorepo's own allowlist doc-comment uses.

**Files:**
- Create: `core/src/main/kotlin/dev/provenance/core/DirectoryHash.kt`
- Test: `core/src/test/kotlin/dev/provenance/core/DirectoryHashTest.kt`

**Interfaces:**
- Consumes: `Sha256` is *not* reused directly (streaming `MessageDigest.update` is used instead, since inputs can be large file trees) — this is a self-contained implementation, matching `extension-hash.ts` being self-contained (it doesn't import log-core's `sha256Hex`, it uses `node:crypto` directly).
- Produces: `object DirectoryHash { fun sha256(root: Path): String }` — 64-char lowercase hex. Walks `root` recursively, collects **regular files only** (symlinks and other non-regular entries are skipped — mirrors `extension-hash.ts`'s `stat.isFile()` check), sorts by path relative to `root` using ordinal string comparison (`String.compareTo`, the Kotlin/JVM equivalent of `String.prototype.localeCompare` for ASCII paths — see verification step below for why ordinal not locale-sensitive collation), then streams `<relative-path-utf8-bytes> + 0x00 + <file-bytes>` through one `MessageDigest`. Empty/missing directory → hash of empty byte sequence (`e3b0c442...b855`).

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/dev/provenance/core/DirectoryHashTest.kt`:
```kotlin
package dev.provenance.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DirectoryHashTest {

    @Test
    fun `empty directory hashes to sha256 of empty bytes`(@TempDir dir: Path) {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            DirectoryHash.sha256(dir),
        )
    }

    @Test
    fun `missing directory hashes the same as empty directory`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        assertEquals(DirectoryHash.sha256(dir), DirectoryHash.sha256(missing))
    }

    @Test
    fun `is deterministic regardless of readdir order (sorted by relative path)`(@TempDir dir: Path) {
        // Create in reverse-alphabetical order to prove sorting, not creation order, matters.
        Files.writeString(dir.resolve("z.txt"), "z-content")
        Files.writeString(dir.resolve("a.txt"), "a-content")
        val hash1 = DirectoryHash.sha256(dir)

        val dir2 = Files.createTempDirectory("dirhash-order")
        Files.writeString(dir2.resolve("a.txt"), "a-content")
        Files.writeString(dir2.resolve("z.txt"), "z-content")
        val hash2 = DirectoryHash.sha256(dir2)

        assertEquals(hash1, hash2)
    }

    @Test
    fun `nested directories are walked recursively`(@TempDir dir: Path) {
        Files.createDirectories(dir.resolve("sub"))
        Files.writeString(dir.resolve("sub/file.txt"), "nested")
        val nestedHash = DirectoryHash.sha256(dir)

        val flatDir = Files.createTempDirectory("dirhash-flat")
        Files.writeString(flatDir.resolve("file.txt"), "nested")
        val flatHash = DirectoryHash.sha256(flatDir)

        // Different relative paths ("sub/file.txt" vs "file.txt") must produce different hashes.
        assertNotEquals(nestedHash, flatHash)
    }

    @Test
    fun `changing file content changes the hash`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("a.txt"), "one")
        val h1 = DirectoryHash.sha256(dir)
        Files.writeString(dir.resolve("a.txt"), "two")
        val h2 = DirectoryHash.sha256(dir)
        assertNotEquals(h1, h2)
    }

    @Test
    fun `is 64 lowercase hex chars`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("a.txt"), "x")
        val h = DirectoryHash.sha256(dir)
        assertEquals(64, h.length)
        assert(h.matches(Regex("^[0-9a-f]{64}$")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests 'dev.provenance.core.DirectoryHashTest'`
Expected: FAIL — `DirectoryHash` unresolved reference.

- [ ] **Step 3: Write the implementation**

`core/src/main/kotlin/dev/provenance/core/DirectoryHash.kt`:
```kotlin
package dev.provenance.core

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.streams.asSequence

/**
 * Reproducible SHA-256 over an entire file tree — the Kotlin port of the VS Code
 * recorder's `computeExtensionHash` (packages/recorder/src/commands/extension-hash.ts).
 *
 * Algorithm (must match the TS original byte-for-byte in spirit, not implementation):
 *   1. Recursively walk `root`; collect regular files only (symlinks/other skipped).
 *   2. Sort by path relative to `root`, ordinal (byte-value) string comparison —
 *      NOT locale-sensitive collation, which can reorder ASCII paths differently
 *      across JVM locales. This is the determinism-critical step: two machines
 *      with different filesystem readdir orders must produce the same hash.
 *   3. For each file (sorted order): digest.update(relativePathUtf8Bytes),
 *      digest.update(0x00), digest.update(fileBytes).
 *   4. Return the hex digest. Missing/empty directory -> sha256 of zero bytes.
 *
 * Used two ways:
 *   - At seal time, over the plugin's own installed directory (recorder/ resolves
 *     that via PluginManagerCore; see ExtensionHash.kt).
 *   - At CI/build time, over the extracted contents of the signed distribution zip
 *     produced by `signPlugin` -- see recorder/build.gradle.kts's
 *     `computeExtensionHashForCi` task. Both call sites go through this one function
 *     so they can never drift from each other.
 */
object DirectoryHash {
    fun sha256(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")

        if (!Files.isDirectory(root)) {
            return bytesToHex(digest.digest())
        }

        val files: List<Path> = Files.walk(root).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() }
                .toList()
        }

        val sorted = files
            .map { it to it.relativeTo(root).toString().replace('\\', '/') }
            .sortedBy { (_, rel) -> rel } // ordinal String comparison

        for ((path, rel) in sorted) {
            digest.update(rel.toByteArray(Charsets.UTF_8))
            digest.update(byteArrayOf(0)) // NUL separator, same as the TS original
            digest.update(Files.readAllBytes(path))
        }

        return bytesToHex(digest.digest())
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests 'dev.provenance.core.DirectoryHashTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dev/provenance/core/DirectoryHash.kt \
  core/src/test/kotlin/dev/provenance/core/DirectoryHashTest.kt
git commit --no-gpg-sign -m "feat(core): reproducible directory-tree SHA-256 (extension_hash algorithm)"
```

---

### Task 2: `core/` — tiny CLI entrypoint for CI-time hashing

Gradle build logic can't easily call a Kotlin `object` mid-build without running the JVM; give `DirectoryHash` a `main()` so Gradle can invoke it as a one-shot process. This is what guarantees the CI-computed hash (Task 6) is produced by the *exact same code* as the runtime-computed hash (Task 5) — never two implementations of the same algorithm (CLAUDE.md: "There is exactly one such function").

**Files:**
- Modify: `core/build.gradle.kts` — add an `application`-style `main` if not already runnable via `java -cp`. (Simplest: no plugin needed, just document the `-cp` invocation using the existing `jar` task output, since `core/build/libs/core.jar` already exists per Task 1's build.)
- Create: `core/src/main/kotlin/dev/provenance/core/DirectoryHashCli.kt`
- Test: `core/src/test/kotlin/dev/provenance/core/DirectoryHashCliTest.kt`

**Interfaces:**
- Produces: `fun main(args: Array<String>)` in file `DirectoryHashCli.kt` (Kotlin compiles this to class `DirectoryHashCliKt` with a static `main`). Usage: `java -cp core.jar dev.provenance.core.DirectoryHashCliKt <path>` — prints the hex hash to stdout, nothing else (no trailing labels, so Gradle can capture it directly). Non-zero exit + message to stderr if the path argument is missing.

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/dev/provenance/core/DirectoryHashCliTest.kt`:
```kotlin
package dev.provenance.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DirectoryHashCliTest {
    @Test
    fun `computes the same hash as DirectoryHash directly`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("a.txt"), "hello")
        // The CLI delegates to DirectoryHash.sha256 -- this test pins that contract
        // without spawning a subprocess (subprocess behavior is exercised by the
        // Gradle task itself in Task 6's verification step).
        assertEquals(DirectoryHash.sha256(dir), directoryHashCliCompute(dir.toString()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests 'dev.provenance.core.DirectoryHashCliTest'`
Expected: FAIL — `directoryHashCliCompute` unresolved reference.

- [ ] **Step 3: Write the implementation**

`core/src/main/kotlin/dev/provenance/core/DirectoryHashCli.kt`:
```kotlin
package dev.provenance.core

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.system.exitProcess

/** Thin, directly-testable wrapper so DirectoryHashCliTest doesn't need to spawn a process. */
fun directoryHashCliCompute(path: String): String = DirectoryHash.sha256(Path(path))

/**
 * CLI entrypoint invoked by Gradle (see recorder/build.gradle.kts's
 * `computeExtensionHashForCi` task) so the CI-time hash and the runtime-time hash
 * (ExtensionHash.kt in recorder/) go through the identical DirectoryHash.sha256.
 *
 * Usage: java -cp core.jar dev.provenance.core.DirectoryHashCliKt <path>
 * Prints the 64-char hex hash to stdout and nothing else.
 */
fun main(args: Array<String>) {
    if (args.size != 1) {
        System.err.println("usage: DirectoryHashCliKt <path>")
        exitProcess(1)
    }
    println(directoryHashCliCompute(args[0]))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests 'dev.provenance.core.DirectoryHashCliTest'`
Expected: PASS (1 test).

- [ ] **Step 5: Verify the jar actually runs as a subprocess (this is what Task 6 depends on)**

```bash
./gradlew :core:jar
java -cp core/build/libs/core.jar dev.provenance.core.DirectoryHashCliKt core/src/test/resources
```
Expected: prints a single 64-char hex line, exit code 0. If `NoClassDefFoundError` for `kotlin.*` appears, the jar needs the Kotlin stdlib on the classpath too — fall back to running via `./gradlew :core:run` equivalent or add `kotlin-stdlib` explicitly to the `-cp` invocation used in Task 6's Gradle task; note whichever form works in that task's comment.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/dev/provenance/core/DirectoryHashCli.kt \
  core/src/test/kotlin/dev/provenance/core/DirectoryHashCliTest.kt
git commit --no-gpg-sign -m "feat(core): CLI entrypoint for DirectoryHash, used by CI hash precompute"
```

---

### Task 3: `recorder/` — course public key embed + revert Gradle tasks

**Prerequisite guard:** this task modifies `recorder/src/main/kotlin/dev/provenance/recorder/activation/CoursePublicKey.kt`, which is expected to exist already from Plan 3 (plugin scaffold + activation), containing a dev public key constant analogous to the VS Code recorder's checked-in dev key. **If this file does not exist when you reach this task, STOP.** Do not invent Plan 3's activation module or generate a dev keypair here — that is out of scope for a build/dist plan. Confirm Plan 3 has landed, then resume.

- [ ] **Step 0: Verify the prerequisite**

```bash
test -f recorder/src/main/kotlin/dev/provenance/recorder/activation/CoursePublicKey.kt && echo "prerequisite present" || echo "STOP: Plan 3 has not landed yet"
```

If it prints "STOP", halt this task and report back rather than continuing.

**Files:**
- Modify: `recorder/src/main/kotlin/dev/provenance/recorder/activation/CoursePublicKey.kt` (not created here — only its *shape* is pinned by the regex below; if Plan 3's actual file differs in shape, adapt the regex, not the underlying design)
- Modify: `recorder/build.gradle.kts` — add `embedCourseKey` and `revertCourseKey` tasks
- Test: none (this is a build-script task, verified by running it, mirroring how `tools/embed-course-key.ts` has no automated test either — it's exercised manually via `build:prod`)

**Assumed shape of `CoursePublicKey.kt`** (mirrors `packages/recorder/src/activation/course-public-key.ts` exactly, ported to Kotlin — single-line constant definition, single-quoted or double-quoted Kotlin string):
```kotlin
package dev.provenance.recorder.activation

/** ... doc comment mirroring the TS original ... */
const val COURSE_PUBLIC_KEY_HEX: String = "<64-hex-dev-key>"
```

- [ ] **Step 1: Add the `embedCourseKey` task to `recorder/build.gradle.kts`**

```kotlin
val coursePublicKeyFile = file("src/main/kotlin/dev/provenance/recorder/activation/CoursePublicKey.kt")
// Mirrors tools/embed-course-key.ts's DEV_PUBLIC_KEY_HEX guard. Keep in sync with
// whatever dev key Plan 3 actually checked in -- read it from the file at task
// execution time instead of hardcoding it a second place, so there is exactly one
// source of truth for "what is the dev key."
val hex64 = Regex("^[0-9a-f]{64}$")

tasks.register("embedCourseKey") {
    group = "provenance"
    description = "Embeds PROVENANCE_COURSE_PUBLIC_KEY_HEX into CoursePublicKey.kt for a production build."
    doLast {
        val hex = System.getenv("PROVENANCE_COURSE_PUBLIC_KEY_HEX")
            ?: throw GradleException(
                "PROVENANCE_COURSE_PUBLIC_KEY_HEX is not set. Set it to the production " +
                    "course public key (64 lowercase hex chars) and re-run."
            )
        if (!hex64.matches(hex)) {
            throw GradleException(
                "PROVENANCE_COURSE_PUBLIC_KEY_HEX is malformed: expected 64 lowercase hex " +
                    "chars, got ${hex.length} chars."
            )
        }
        val original = coursePublicKeyFile.readText()
        val devKeyPattern = Regex("""const val COURSE_PUBLIC_KEY_HEX: String = "([0-9a-f]{64})"""")
        val devKeyMatch = devKeyPattern.find(original)
            ?: throw GradleException(
                "Could not locate COURSE_PUBLIC_KEY_HEX in $coursePublicKeyFile. " +
                    "The file shape may have drifted -- update this task's regex or restore " +
                    "the file from git."
            )
        val devKeyHex = devKeyMatch.groupValues[1]
        if (hex == devKeyHex) {
            throw GradleException(
                "PROVENANCE_COURSE_PUBLIC_KEY_HEX equals the dev key checked into the repo. " +
                    "Production builds must use a different key."
            )
        }
        val rewritten = original.replace(devKeyPattern, """const val COURSE_PUBLIC_KEY_HEX: String = "$hex"""")
        coursePublicKeyFile.writeText(rewritten)
        logger.lifecycle("[embedCourseKey] Embedded production public key (public, hex): $hex")
    }
}

tasks.register<Exec>("revertCourseKey") {
    group = "provenance"
    description = "Restores CoursePublicKey.kt to its checked-in (dev-key) state via git checkout."
    commandLine("git", "checkout", "--", coursePublicKeyFile.absolutePath)
}
```

- [ ] **Step 2: Verify `embedCourseKey` end-to-end with a fake key, then revert**

```bash
PROVENANCE_COURSE_PUBLIC_KEY_HEX=$(python3 -c "import secrets; print(secrets.token_hex(32))") \
  ./gradlew embedCourseKey
git diff --stat recorder/src/main/kotlin/dev/provenance/recorder/activation/CoursePublicKey.kt
```
Expected: a one-line diff changing only the hex constant. Then:
```bash
./gradlew revertCourseKey
git status --porcelain recorder/src/main/kotlin/dev/provenance/recorder/activation/CoursePublicKey.kt
```
Expected: empty output (file restored).

- [ ] **Step 3: Verify the refusal paths**

```bash
./gradlew embedCourseKey  # PROVENANCE_COURSE_PUBLIC_KEY_HEX unset
```
Expected: FAILS with the "is not set" message.
```bash
PROVENANCE_COURSE_PUBLIC_KEY_HEX=not-hex ./gradlew embedCourseKey
```
Expected: FAILS with the "is malformed" message.

- [ ] **Step 4: Commit**

```bash
git add recorder/build.gradle.kts
git commit --no-gpg-sign -m "feat(recorder): embedCourseKey/revertCourseKey Gradle tasks (mirrors embed-course-key.ts)"
```

---

### Task 4: `recorder/` — runtime `extension_hash` via `PluginManagerCore`

**Prerequisite guard:** this task assumes a plugin id constant exists (or defines one here if Plan 3 hasn't pinned it — see the open question in design.md §11.4). It does **not** assume the seal command (Plan 4/8) exists; it produces a standalone, independently testable function and documents the (not-yet-existing) call site so whoever implements the seal command wires it in trivially.

**Files:**
- Create: `recorder/src/main/kotlin/dev/provenance/recorder/seal/ExtensionHash.kt`
- Test: `recorder/src/test/kotlin/dev/provenance/recorder/seal/ExtensionHashTest.kt`

**Interfaces:**
- Consumes: `DirectoryHash.sha256` (Task 1).
- Produces: `fun computeExtensionHash(pluginPath: Path): String = DirectoryHash.sha256(pluginPath)` — deliberately takes the path as a parameter rather than resolving it internally, so it's testable with `BasePlatformTestCase`/a plain temp dir without needing a real installed plugin. A second, thin function `fun computeInstalledExtensionHash(pluginId: String): String` resolves the real path via `PluginManagerCore.getPlugin(PluginId.getId(pluginId))?.pluginPath ?: error("plugin not found: $pluginId")` and delegates to the first function — this one needs IntelliJ Platform test fixtures to test, so its test is a thin smoke test only.

- [ ] **Step 1: Write the failing test**

`recorder/src/test/kotlin/dev/provenance/recorder/seal/ExtensionHashTest.kt`:
```kotlin
package dev.provenance.recorder.seal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import dev.provenance.core.DirectoryHash

class ExtensionHashTest {
    @Test
    fun `delegates to DirectoryHash for a given path`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("plugin.jar"), "fake-jar-bytes")
        assertEquals(DirectoryHash.sha256(dir), computeExtensionHash(dir))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.seal.ExtensionHashTest'`
Expected: FAIL — `computeExtensionHash` unresolved reference.

- [ ] **Step 3: Write the implementation**

`recorder/src/main/kotlin/dev/provenance/recorder/seal/ExtensionHash.kt`:
```kotlin
package dev.provenance.recorder.seal

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import dev.provenance.core.DirectoryHash
import java.nio.file.Path

/**
 * extension_hash for the bundle manifest (recorder PRD §6, design.md §6).
 *
 * Deliberately split in two:
 *   - computeExtensionHash(path): pure, IntelliJ-Platform-free, directly testable.
 *   - computeInstalledExtensionHash(pluginId): resolves this plugin's own installed
 *     directory via PluginManagerCore and delegates. This is the one call site the
 *     seal command (Plan 4/8) should use -- wire `BundleManifest(..., extensionHash =
 *     computeInstalledExtensionHash(PROVJET_PLUGIN_ID), ...)` there when that command
 *     exists. As of this file's creation, no seal command exists yet in this repo;
 *     search for `BundleManifest(` before adding a second call site.
 *
 * Both paths go through DirectoryHash.sha256 -- the same function the CI precompute
 * task (recorder/build.gradle.kts's computeExtensionHashForCi) uses over the signed
 * distribution zip's extracted contents. Never reimplement the algorithm here.
 */
fun computeExtensionHash(pluginPath: Path): String = DirectoryHash.sha256(pluginPath)

fun computeInstalledExtensionHash(pluginId: String): String {
    val plugin = PluginManagerCore.getPlugin(PluginId.getId(pluginId))
        ?: error("computeInstalledExtensionHash: plugin not found: $pluginId (is it actually installed/enabled?)")
    return computeExtensionHash(plugin.pluginPath)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :recorder:test --tests 'dev.provenance.recorder.seal.ExtensionHashTest'`
Expected: PASS (1 test). Note: `computeInstalledExtensionHash` is intentionally left without a unit test here — it needs `BasePlatformTestCase`/a running sandbox IDE to exercise `PluginManagerCore`, which is Plan 3's IntelliJ test-fixture setup. Add an integration-style test for it alongside whichever plan first stands up `BasePlatformTestCase` infrastructure; do not skip it forever, just don't invent that infrastructure here.

- [ ] **Step 5: Commit**

```bash
git add recorder/src/main/kotlin/dev/provenance/recorder/seal/ExtensionHash.kt \
  recorder/src/test/kotlin/dev/provenance/recorder/seal/ExtensionHashTest.kt
git commit --no-gpg-sign -m "feat(recorder): extension_hash via DirectoryHash over the installed plugin path"
```

---

### Task 5: `recorder/build.gradle.kts` — IntelliJ Platform Gradle Plugin config skeleton

**Prerequisite guard:** assumes the `recorder/` module already applies the IntelliJ Platform Gradle Plugin (Plan 3). If `recorder/build.gradle.kts` doesn't exist or doesn't apply `org.jetbrains.intellij.platform` yet, STOP — this task only *extends* that config with production-build/publishing concerns, it doesn't bootstrap the module.

**Files:**
- Modify: `recorder/build.gradle.kts`
- Modify (create if absent): `gradle.properties` (repo root) — non-secret defaults only (plugin id, vendor name); nothing that needs to stay out of git.

**DSL note (confidence caveat):** the IntelliJ Platform Gradle Plugin exposes both an `intellijPlatform { }` extension block (2.x, the form shown below) and direct task configuration (`tasks.signPlugin { ... }`, `tasks.patchPluginXml { ... }`, older-style but still present as of 2.x per the Plugin Signing doc). The snippets below use the 2.x extension-block form as primary since https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html confirms 2.x (v2.18.1 at fetch time) is current. **Confirm the exact DSL shape against whichever IntelliJ Platform Gradle Plugin version is actually pinned in this repo's `build.gradle.kts` before merging** — property names (`certificateChain`, `privateKey`, `password`, `token`, `channels`, `sinceBuild`, `untilBuild`, `pluginId`/`id`, `pluginVersion`/`version`) are cited verbatim from the docs fetched 2026-07-14; the exact block nesting may need adjustment for the pinned version.

- [ ] **Step 1: Add plugin identity + compatibility range to `recorder/build.gradle.kts`**

```kotlin
intellijPlatform {
    pluginConfiguration {
        // Plugin id is a PERMANENT identity once published (Marketplace + auto-update
        // channels key off it). design.md §11.4 leaves this an open question -- confirm
        // the real value with the course/operator before the FIRST Marketplace publish
        // (Task 9). Using a placeholder here is fine for signPlugin/verifyPlugin dry runs.
        id.set(providers.gradleProperty("provjet.pluginId").orElse("edu.berkeley.provenance.recorder"))
        name.set("Provenance Recorder")
        version.set(project.version.toString())

        vendor {
            name.set(providers.gradleProperty("provjet.vendorName").orElse("Provenance"))
        }

        ideaVersion {
            // com.intellij.modules.platform target (design.md §2) -- 2023.3 (build 233)
            // is a reasonable conservative floor for a platform-core-only plugin as of
            // 2026; confirm against the actual IntelliJ Platform Gradle Plugin's
            // recommended range at implementation time, and against whichever IDE
            // versions the course's students actually run.
            sinceBuild.set(providers.gradleProperty("provjet.sinceBuild").orElse("233"))
            // Leaving untilBuild unset (open-ended) is the modern recommendation so the
            // plugin doesn't silently stop working on IDE updates; only set it if a real
            // known-incompatibility is discovered.
        }
    }

    signing {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }

    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
        channels.set(listOf(providers.gradleProperty("provjet.publishChannel").orElse("default").get()))
    }

    pluginVerification {
        ides {
            recommended()
        }
        failureLevel.set(listOf(org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS))
    }
}
```

- [ ] **Step 2: Verify the skeleton evaluates (no secrets needed for this — Gradle config evaluation, not task execution)**

```bash
./gradlew :recorder:tasks --group=provenance
```
Expected: exits 0, lists `embedCourseKey`/`revertCourseKey` from Task 3 plus the standard IntelliJ Platform Gradle Plugin tasks (`signPlugin`, `verifyPlugin`, `publishPlugin`, `patchPluginXml`, `buildPlugin`). If Gradle fails to *evaluate* (not just to *run* a task needing secrets), the DSL shape is wrong for the pinned plugin version — fix the block nesting per the caveat above, don't work around it by removing config.

- [ ] **Step 3: Verify `signPlugin` fails gracefully without secrets (proves no fake fallback exists)**

```bash
unset CERTIFICATE_CHAIN PRIVATE_KEY PRIVATE_KEY_PASSWORD
./gradlew :recorder:signPlugin
```
Expected: FAILS with an error about a missing/blank certificate chain or private key — not a silent no-op, not a fake signature. This is the correct behavior; do not "fix" it by supplying a fallback dev cert.

- [ ] **Step 4: Commit**

```bash
git add recorder/build.gradle.kts gradle.properties
git commit --no-gpg-sign -m "feat(recorder): IntelliJ Platform Gradle Plugin production config (signing/publishing/patchPluginXml skeleton)"
```

---

### Task 6: CI-time `extension_hash` precompute task

**Files:**
- Modify: `recorder/build.gradle.kts` — add `computeExtensionHashForCi`

**Purpose:** produce the hash a real student's installed plugin will report, *before* publishing, so it can go into the monorepo allowlist (Part B, Task 8) ahead of / alongside the release. Must operate over the **signed** distribution (the actual artifact Marketplace serves and students install) — signing appends a signature block, which is part of what ships, so it must be part of what's hashed.

- [ ] **Step 1: Add the task**

```kotlin
val extensionHashStaging = layout.buildDirectory.dir("extensionHashStaging")

val computeExtensionHashForCi by tasks.registering(JavaExec::class) {
    group = "provenance"
    description = "Computes extension_hash over the signed plugin distribution, for the monorepo allowlist."
    dependsOn(tasks.named("signPlugin"))
    dependsOn(tasks.named("unzipSignedDistribution")) // see below

    classpath = files(project(":core").tasks.named("jar"))
    mainClass.set("dev.provenance.core.DirectoryHashCliKt")
    args(extensionHashStaging.get().asFile.absolutePath)

    standardOutput = ByteArrayOutputStream()
    doLast {
        val hash = (standardOutput as ByteArrayOutputStream).toString(Charsets.UTF_8).trim()
        val outFile = layout.buildDirectory.file("extension-hash.txt").get().asFile
        outFile.writeText(hash + "\n")
        logger.lifecycle("[computeExtensionHashForCi] extension_hash = $hash")
        logger.lifecycle("[computeExtensionHashForCi] written to: $outFile")
    }
}

val unzipSignedDistribution by tasks.registering(Copy::class) {
    group = "provenance"
    description = "Extracts the signPlugin output into a directory so DirectoryHash can walk it (mirrors what installing the plugin does)."
    dependsOn(tasks.named("signPlugin"))
    from(zipTree(tasks.named("signPlugin").map { (it as org.jetbrains.intellij.platform.gradle.tasks.SignPluginTask).signedArchiveFile }))
    into(extensionHashStaging)
}
```

*(The exact output-property name on `signPlugin` — `signedArchiveFile` per the Plugin Signing doc — must be confirmed against the pinned plugin version; if the task type/property differs, adjust `unzipSignedDistribution`'s `from(...)` accordingly. Do not hand-hash the unsigned `buildPlugin` output instead — that produces a different tree than what students install.)*

- [ ] **Step 2: Verify (requires a real or dry-run signed artifact — see Task 5 Step 3's caveat: this task cannot fully succeed without real signing secrets)**

REQUIRES OPERATOR SECRETS to run to completion — `signPlugin` needs a real certificate chain/private key. Verify the *wiring* is correct by confirming the task is registered and its dependency graph is right:
```bash
./gradlew :recorder:computeExtensionHashForCi --dry-run
```
Expected: lists `:core:jar`, `:recorder:signPlugin`, `:recorder:unzipSignedDistribution`, `:recorder:computeExtensionHashForCi` in dependency order, all marked `SKIPPED` (dry run). This confirms the plumbing without needing secrets.

- [ ] **Step 3: Commit**

```bash
git add recorder/build.gradle.kts
git commit --no-gpg-sign -m "feat(recorder): CI task to precompute extension_hash over the signed distribution"
```

---

### Task 7: `buildProd` / `publishProd` composite tasks

**Files:**
- Modify: `recorder/build.gradle.kts`

- [ ] **Step 1: Add the composite tasks**

```kotlin
val buildProd by tasks.registering {
    group = "provenance"
    description = "Production build: embeds the course public key, builds+signs the plugin, computes extension_hash, then always reverts the embedded key."
    dependsOn("embedCourseKey")
    finalizedBy("revertCourseKey") // runs even if a later step fails -- see note below
    // Ordering: embed must finish before signPlugin's inputs are compiled from source
    // containing the embedded key. buildPlugin/signPlugin already depend on compileKotlin
    // transitively; embedCourseKey must run before that compile step.
    tasks.named("compileKotlin") { mustRunAfter("embedCourseKey") }
    finalizedBy(tasks.named("computeExtensionHashForCi"))
}

val publishProd by tasks.registering {
    group = "provenance"
    description = "Publishes the signed, prod-keyed plugin to JetBrains Marketplace. Irreversible per version -- run buildProd + manual review first."
    dependsOn(buildProd)
    dependsOn(tasks.named("verifyPlugin"))
    finalizedBy(tasks.named("publishPlugin"))
}
```

**Non-obvious choice, surfaced per CLAUDE.md ("Conventions for talking to me"):** `finalizedBy("revertCourseKey")` is a deliberate improvement over the monorepo's `tools/embed-course-key.ts` + `build:prod` npm script, which is a plain sequential shell chain (`embed && build && package && git checkout`) — if any step in the middle fails, the npm script stops with the *embedded* production key left uncommitted-but-present in the working tree (recoverable by hand, but a footgun). Gradle's `finalizedBy` runs the revert task even when an earlier task in the chain fails, so this repo's version can't leave a production key sitting in the working tree after a failed build. This is a genuine behavioral difference from the pattern being mirrored, not a bug — flagging it here rather than burying it in a comment, per the working agreement.

- [ ] **Step 2: Verify task graph (no secrets required for graph-only verification)**

```bash
./gradlew :recorder:buildProd --dry-run
./gradlew :recorder:publishProd --dry-run
```
Expected: both resolve without error, listing `embedCourseKey` before compile/build steps and `revertCourseKey` as a finalizer.

- [ ] **Step 3: Commit**

```bash
git add recorder/build.gradle.kts
git commit --no-gpg-sign -m "feat(recorder): buildProd/publishProd composite Gradle tasks"
```

---

### Task 8: Marketplace publish — REQUIRES OPERATOR SECRETS, cannot be executed autonomously

This task is documentation-as-runbook, not code. It cannot be completed by an agent because every input is a real secret or a one-way decision. Write it into `README.md` under a new "Production release" section so a human operator has an exact runbook.

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Write the runbook section**

Append to `README.md`:
```markdown
## Production release (JetBrains Marketplace)

Marketplace is the primary distribution channel; the Plan 3 sideload `.zip` is for
early testing only. Every step below needs a real secret or is a one-way decision —
**an agent cannot run this section; a human operator must.**

### One-time setup (REQUIRES OPERATOR SECRETS)

1. Create/confirm a JetBrains Account, then generate a Personal Access Token at
   https://plugins.jetbrains.com/author/me/tokens ("My Tokens"). Save it as
   `PUBLISH_TOKEN` in your CI secret store. It is shown only once.
   (https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)
2. Generate a code-signing certificate + private key
   (https://plugins.jetbrains.com/docs/intellij/plugin-signing.html):
   ```bash
   openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
   openssl rsa -in private_encrypted.pem -out private.pem
   openssl req -key private.pem -new -x509 -days 365 -out chain.crt
   ```
   Store `chain.crt` contents as `CERTIFICATE_CHAIN`, `private.pem` contents as
   `PRIVATE_KEY`, and the passphrase as `PRIVATE_KEY_PASSWORD` in your CI secret
   store. Never commit these files.
3. Confirm the permanent plugin id (`provjet.pluginId` in `gradle.properties`,
   default `edu.berkeley.provenance.recorder`) with the course/operator. This is a
   one-way door once published — Marketplace identity and auto-update channels key
   off it forever.
4. **The first plugin publication must be uploaded manually** through the
   Marketplace web UI (per JetBrains docs) — `publishPlugin` automation only works
   for subsequent versions of an already-registered plugin. Build the signed zip
   locally (`./gradlew :recorder:buildProd`, output under
   `recorder/build/distributions/`) and upload it by hand the first time.

### Every release after the first

```bash
export PROVENANCE_COURSE_PUBLIC_KEY_HEX=<64-hex production course public key>
export CERTIFICATE_CHAIN=<contents of chain.crt>
export PRIVATE_KEY=<contents of private.pem>
export PRIVATE_KEY_PASSWORD=<passphrase>
export PUBLISH_TOKEN=<Marketplace personal access token>

./gradlew :recorder:publishProd
```

Bump `version` in `recorder/build.gradle.kts` before every release — Marketplace
rejects duplicate version numbers.

### After publishing: register the hash in the monorepo allowlist

`./gradlew :recorder:buildProd` leaves the computed hash at
`recorder/build/extension-hash.txt`. Copy that value into the monorepo per Part B,
Task 9 below — **every release needs a new allowlist entry**, or every submission
from that release gets flagged by `extension_hash_mismatch`.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit --no-gpg-sign -m "docs: production release runbook (Marketplace publish, operator-secrets-required)"
```

---

## Part B — `provenance` (the monorepo, `/Users/aaryanmehta/projects/provenance`)

Everything below runs **in the monorepo**, using **its** CLAUDE.md conventions (npm workspaces, `tsx`/`node --experimental-strip-types`, Vitest, `git commit --no-gpg-sign`, no `Co-Authored-By: Claude`, explicit pathspec, small diffs, no new dependencies without approval).

### Task 9: Allowlist entry for the JetBrains build hash

**Files:**
- Modify: `/Users/aaryanmehta/projects/provenance/packages/analysis-core/src/heuristics/config/known-good-extension-hashes.json`
- Modify: `/Users/aaryanmehta/projects/provenance/scripts/update-extension-hash-allowlist.mjs` (doc-comment only — see Step 2)

- [ ] **Step 1: Add the hash — REQUIRES OPERATOR SECRETS for the real value**

The allowlist entry itself is a one-line JSON array addition — mechanically trivial. The **value** requires a real hash from a real signed Marketplace build (Part A, Task 8), which requires a real course keypair and Marketplace certificate. An agent can run the *command* once handed a real hash by the operator; it cannot produce that hash itself.

```bash
cd /Users/aaryanmehta/projects/provenance
node scripts/update-extension-hash-allowlist.mjs --hash <hex-from-recorder/build/extension-hash.txt>
```

This uses the script's existing `--hash <hex>` mode (already implemented — no code change needed for this part; see the script's own `--help`). Verify:
```bash
node scripts/update-extension-hash-allowlist.mjs --show
```
Expected: the new hash appears in the printed list, with a `+` marker on the run that added it.

- [ ] **Step 2: Small, additive doc-string correction — make the allowlist producer-agnostic in wording**

The allowlist's `description` field and the update script's module doc-comment currently say the hash is "the SHA-256 of the built `.vsix` file," which is VS-Code-specific phrasing left over from before a second producer existed. The `extension_hash_mismatch` heuristic itself is already producer-agnostic (it just compares `bundle.manifest.extension_hash` against a list of strings — see `packages/analysis-core/src/heuristics/extension-hash-mismatch.ts`). Only the prose needs updating; do not touch the heuristic logic or the JSON schema.

In `known-good-extension-hashes.json`, change the `description` field from:
```
"...The actual hash is the SHA-256 of the built .vsix file (64-char lowercase hex), as emitted by the recorder into bundle.manifest.extension_hash at seal time..."
```
to:
```
"...The actual hash is a SHA-256 over the recorder's installed distribution file tree (64-char lowercase hex) -- computed by walking sorted relative paths and hashing <path>\\0<bytes> per file -- as emitted by the recorder into bundle.manifest.extension_hash at seal time. Producer-agnostic: any recorder implementation (VS Code .vsix, JetBrains plugin .zip, ...) that reproduces this exact algorithm can appear here..."
```

In `scripts/update-extension-hash-allowlist.mjs`'s module doc-comment (near the top, the paragraph starting "IMPORTANT: this script produces the hash..."), add one sentence noting the script currently only *automates* the VS Code recorder's build (`--keypair`/`build:prod` path); a JetBrains hash must be computed by that repo's own `./gradlew :recorder:buildProd` (Part A, Task 8) and added via `--hash` as shown above. Do not add JetBrains build automation into this script — this monorepo has no JVM/Gradle toolchain (design.md §9), and teaching this script to shell out to a sibling repo's Gradle build would violate "stay in scope" / "no new dependencies without asking."

- [ ] **Step 3: Run the monorepo's existing tests to confirm nothing else reads that description string structurally**

```bash
cd /Users/aaryanmehta/projects/provenance
npm run test --workspace=packages/analysis-core -- extension-hash-mismatch
```
Expected: PASS, unchanged (the heuristic doesn't parse `description`, only `hashes`).

- [ ] **Step 4: Commit (monorepo repo, explicit pathspec, per its CLAUDE.md)**

```bash
cd /Users/aaryanmehta/projects/provenance
git add packages/analysis-core/src/heuristics/config/known-good-extension-hashes.json \
  scripts/update-extension-hash-allowlist.mjs
git commit --no-gpg-sign -m "docs(analysis-core): producer-agnostic extension-hash wording; add JetBrains recorder hash"
```

---

### Task 10: Golden-vector export script (replaces Plans 1–2's hand-authored fixtures)

**Files (all in the monorepo):**
- Create: `/Users/aaryanmehta/projects/provenance/tools/export-conformance-vectors.ts`

**Purpose:** a single script, run from the monorepo, that emits everything `provjet`'s `core/` conformance suite needs as language-neutral JSON — generated straight from `log-core`'s real exported functions, not hand-transcribed (Plan 1 Task 7 and Plan 2's tasks did the latter as a stopgap and explicitly flagged this script as the fix). Mirrors `tools/embed-course-key.ts` and `tools/sign-manifest.ts`'s conventions: a `.ts` file run via `node --experimental-strip-types`, REPO_ROOT resolved from `import.meta.dirname`.

- [ ] **Step 1: Confirm `log-core` is built (the script imports the workspace package by name)**

```bash
cd /Users/aaryanmehta/projects/provenance
npm run build --workspace=packages/log-core
```
Expected: exits 0, populates `packages/log-core/dist/`.

- [ ] **Step 2: Write the script**

`/Users/aaryanmehta/projects/provenance/tools/export-conformance-vectors.ts`:
```typescript
/**
 * Export language-neutral conformance vectors from log-core + a golden bundle
 * from analysis-core's test-support builder, for provenance-jetbrains-recorder's
 * core/ conformance suite to consume.
 *
 * This REPLACES the hand-authored fixtures Plans 1-2 committed by hand in that
 * repo (core/src/test/resources/conformance/*.json) with a single generated
 * source of truth. If log-core's format or crypto framing ever changes, re-running
 * this script and re-committing its output in the jetbrains repo is how that
 * change propagates -- never hand-edit the vectors there.
 *
 * USAGE
 *   node --experimental-strip-types tools/export-conformance-vectors.ts --out <dir>
 *
 * Example (writing directly into the sibling repo on this machine):
 *   node --experimental-strip-types tools/export-conformance-vectors.ts \
 *     --out ../provenance-jetbrains-recorder/core/src/test/resources/conformance
 *
 * The --out directory is required (no hardcoded cross-repo default) so this
 * script has no assumption about where the sibling repo lives on any given
 * machine. It is created if missing. Existing files of the same name are
 * overwritten; this script owns that directory's contents.
 */

import * as fs from 'node:fs';
import * as path from 'node:path';
import * as ed from '@noble/ed25519';
import { sha512 } from '@noble/hashes/sha2.js';
import {
  sha256Hex,
  canonicalize,
  chainEntry,
  GENESIS_PREV_HASH,
  signManifest,
  signBundleManifest,
  generateSessionKeypair,
  encryptSessionPrivkey,
  signCheckpoint,
} from '@provenance/log-core';
import { buildTestBundle } from '@provenance/analysis-core/test-support/build-test-bundle.js';

// Wire sha512 for @noble/ed25519 (same pattern as build-test-bundle.ts).
ed.hashes.sha512 = sha512;
(ed.hashes as Record<string, unknown>)['sha512Async'] = (m: Uint8Array) => Promise.resolve(sha512(m));

function bytesToHex(b: Uint8Array): string {
  return Buffer.from(b).toString('hex');
}
function hexToBytes(h: string): Uint8Array {
  return new Uint8Array(Buffer.from(h, 'hex'));
}

function parseArgs(argv: string[]): { out: string } {
  const idx = argv.indexOf('--out');
  if (idx === -1 || !argv[idx + 1]) {
    console.error('usage: export-conformance-vectors.ts --out <dir>');
    process.exit(1);
  }
  return { out: argv[idx + 1] };
}

async function main() {
  const { out } = parseArgs(process.argv.slice(2));
  const outDir = path.resolve(out);
  fs.mkdirSync(outDir, { recursive: true });

  // --- 1. sha256 + hash-chain vectors (pinned, same as hash-chain.test.ts) ---
  const chainVectors = {
    source: 'log-core (generated by tools/export-conformance-vectors.ts)',
    sha256: [
      { input: 'hello world', hex: sha256Hex('hello world') },
      { input: '', hex: sha256Hex('') },
    ],
    chain: [
      {
        prev_hash: GENESIS_PREV_HASH,
        envelope: { seq: 0, t: 0, wall: '2026-01-01T00:00:00.000Z', kind: 'session.end', data: { reason: 'test' } },
        hash: chainEntry(GENESIS_PREV_HASH, {
          seq: 0, t: 0, wall: '2026-01-01T00:00:00.000Z', kind: 'session.end', data: { reason: 'test' },
        }).hash,
      },
    ],
  };
  fs.writeFileSync(path.join(outDir, 'vectors.json'), JSON.stringify(chainVectors, null, 2) + '\n');

  // --- 2. ed25519 vector (fixed key + message -> signature) ---
  const priv = new Uint8Array(32).fill(7);
  const msg = new TextEncoder().encode('{"a":1}');
  const sig = await ed.signAsync(msg, priv);
  const pub = await ed.getPublicKeyAsync(priv);
  fs.writeFileSync(
    path.join(outDir, 'ed25519.json'),
    JSON.stringify({ priv_hex: bytesToHex(priv), msg_utf8: '{"a":1}', pub_hex: bytesToHex(pub), sig_hex: bytesToHex(sig) }, null, 2) + '\n',
  );

  // --- 3. signed .provenance-manifest vector ---
  const coursePriv = new Uint8Array(32).fill(9);
  const coursePub = await ed.getPublicKeyAsync(coursePriv);
  const manifestFields = {
    assignment_id: 'hw1',
    semester: 'fa26',
    issued_at: '2026-01-01T00:00:00.000Z',
    files_under_review: ['main.py'],
  };
  const signedManifest = await signManifest(manifestFields, bytesToHex(coursePriv));
  fs.writeFileSync(
    path.join(outDir, 'manifest.json'),
    JSON.stringify({ course_pubkey_hex: bytesToHex(coursePub), manifest: signedManifest }, null, 2) + '\n',
  );

  // --- 4. signed bundle-manifest vector ---
  const sessionPriv = new Uint8Array(32).fill(11);
  const sessionPub = await ed.getPublicKeyAsync(sessionPriv);
  const bundleManifest = {
    format_version: '1.1' as const,
    assignment_id: 'hw1',
    semester: 'fa26',
    extension_hash: 'a'.repeat(64),
    sessions: [{ session_id: 's1', prev_session_id: null, slog_sha256: 'b'.repeat(64), meta_sha256: 'c'.repeat(64) }],
    submission_files: [{ path: 'main.py', status: 'present' as const, sha256: 'd'.repeat(64) }],
  };
  const signedBundle = signBundleManifest(bundleManifest, bytesToHex(sessionPriv));
  fs.writeFileSync(
    path.join(outDir, 'bundle-manifest.json'),
    JSON.stringify({ session_pubkey_hex: bytesToHex(sessionPub), ...signedBundle }, null, 2) + '\n',
  );

  // --- 5. session keypair + encrypted privkey vector (fixed salt/nonce/manifestSig) ---
  const fixedSalt = new Uint8Array(16).fill(1);
  const fixedNonce = new Uint8Array(24).fill(2);
  const manifestSig = signedManifest.sig; // reuse a real 128-hex sig for realism
  const skp = await generateSessionKeypair();
  const enc = encryptSessionPrivkey(skp.privateKey, manifestSig, fixedSalt, fixedNonce);
  fs.writeFileSync(
    path.join(outDir, 'session-keys.json'),
    JSON.stringify({ manifest_sig: manifestSig, privkey_hex: bytesToHex(skp.privateKey), encrypted: enc }, null, 2) + '\n',
  );

  // --- 6. signed checkpoint vector ---
  const checkpoint = signCheckpoint(5, sha256Hex('checkpoint-test'), bytesToHex(sessionPriv));
  fs.writeFileSync(
    path.join(outDir, 'checkpoint.json'),
    JSON.stringify({ session_pubkey_hex: bytesToHex(sessionPub), checkpoint }, null, 2) + '\n',
  );

  // --- 7. golden full bundle (built once, committed, never regenerated bit-for-bit) ---
  const golden = await buildTestBundle({
    assignmentId: 'golden-hw',
    semester: 'fa26',
    sessions: [{ eventCount: 8, appendDocSave: true }],
  });
  const zipBytes = Buffer.from(golden.zipBuffer);
  fs.writeFileSync(path.join(outDir, 'golden-bundle.zip'), zipBytes);
  fs.writeFileSync(
    path.join(outDir, 'golden-bundle.json'),
    JSON.stringify(
      {
        note: 'Sidecar for golden-bundle.zip. Parse the zip with core/'s Bundle + Ndjson + ' +
          'ChainValidator + Manifest functions and assert these values.',
        manifest: golden.manifest,
        session_pubkey_hex: golden.sessionPublicKeyHex,
      },
      null,
      2,
    ) + '\n',
  );

  console.log(`Wrote conformance vectors to ${outDir}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
```

**Note on `BuiltBundle`'s exact field names** (`golden.manifest`, `golden.sessionPublicKeyHex`, etc.): confirm against the real `BuiltBundle` type in `packages/analysis-core/src/test-support/build-test-bundle.ts` at implementation time (the file was read during this plan's research but its full type surface past line ~160 wasn't transcribed here) — adjust the destructured field names in Step 2's script to match exactly; do not guess silently if they differ.

- [ ] **Step 3: Run it against a scratch directory to verify it produces valid output**

```bash
cd /Users/aaryanmehta/projects/provenance
node --experimental-strip-types tools/export-conformance-vectors.ts --out /tmp/provjet-vectors-test
ls /tmp/provjet-vectors-test
```
Expected: `vectors.json`, `ed25519.json`, `manifest.json`, `bundle-manifest.json`, `session-keys.json`, `checkpoint.json`, `golden-bundle.zip`, `golden-bundle.json` all present and non-empty. Spot-check one: `cat /tmp/provjet-vectors-test/vectors.json` should show the same `sha256`/`chain` values already pinned in Plan 1's hand-authored `core/src/test/resources/conformance/vectors.json` (byte-identical — this is the regression check that the export script reproduces what was hand-transcribed).

- [ ] **Step 4: Commit (monorepo)**

```bash
cd /Users/aaryanmehta/projects/provenance
git add tools/export-conformance-vectors.ts
git commit --no-gpg-sign -m "feat(tools): export log-core conformance vectors + a golden bundle for provjet"
```

---

### Task 11: Regenerate `provjet`'s committed vectors from the export script

**Files (in `provenance-jetbrains-recorder`):**
- Modify: `core/src/test/resources/conformance/vectors.json`, `ed25519.json`, `manifest.json`, `bundle-manifest.json`, `session-keys.json`, `checkpoint.json` (all replaced with generated output)
- Create: `core/src/test/resources/conformance/golden-bundle.zip`, `golden-bundle.json`
- Modify: `core/src/test/kotlin/dev/provenance/core/ConformanceTest.kt` — add a golden-bundle round-trip assertion
- Modify: `README.md` — update the "Conformance" note from Plan 1 Task 7 to point at the export script instead of describing hand-authoring

- [ ] **Step 1: Regenerate**

```bash
cd /Users/aaryanmehta/projects/provenance
node --experimental-strip-types tools/export-conformance-vectors.ts \
  --out ../provenance-jetbrains-recorder/core/src/test/resources/conformance
```

- [ ] **Step 2: Diff-review the regenerated vectors against Plans 1–2's hand-authored ones**

```bash
cd /Users/aaryanmehta/projects/provenance-jetbrains-recorder
git diff --stat core/src/test/resources/conformance/
```
Expected: `vectors.json` byte-identical or near-identical (same pinned values, generated instead of transcribed); `ed25519.json`/`manifest.json`/`bundle-manifest.json`/etc. **will differ** from Plan 2's committed versions if Plan 2 used different fixed test keys than this script's fixed fill-bytes (`0x07`, `0x09`, `0x11`, etc.) — that's expected and fine, since `ConformanceTest.kt` re-reads whatever is in the file rather than hardcoding the old values. If Plan 2 is already implemented by the time this task runs, confirm `ConformanceTest.kt` has no hardcoded vector *values* (only hardcoded *field paths* into the JSON) before regenerating, or the old hardcoded values will now mismatch the new file.

- [ ] **Step 3: Add the golden-bundle conformance test**

Extend `core/src/test/kotlin/dev/provenance/core/ConformanceTest.kt` (created in Plan 1 Task 7, extended in Plan 2 Task 6) with:
```kotlin
@Test
fun `golden bundle parses and validates end to end`() {
    val zipBytes = this::class.java.getResourceAsStream("/conformance/golden-bundle.zip")!!.readBytes()
    val sidecarText = this::class.java.getResource("/conformance/golden-bundle.json")!!.readText()
    // Full zip parsing depends on core/'s bundle-loading code (a later plan's scope --
    // Plan 2 only covers manifest shape + signing, not zip extraction). If a zip loader
    // doesn't exist yet in core/ when this task runs, assert only that the manifest
    // JSON embedded in the sidecar validates via validateBundleManifestShape / signature
    // verification against session_pubkey_hex, and leave a TODO for full zip parsing
    // once core/ has a zip reader (out of scope for this plan to add).
    val sidecar = Json.parseToJsonElement(sidecarText).jsonObject
    val manifestObj = sidecar["manifest"]!!.jsonObject
    val result = validateBundleManifestShape(manifestObj.toString())
    assert(result is BundleManifestShapeResult.Ok)
}
```
*(Exact type/function names — `validateBundleManifestShape`, `BundleManifestShapeResult` — must match whatever Plan 2 Task 3 actually named them; this plan's earlier read of Plan 2 shows `Result<BundleManifest>`, adjust accordingly rather than guessing a name that doesn't exist.)*

- [ ] **Step 4: Run the full conformance suite**

```bash
cd /Users/aaryanmehta/projects/provenance-jetbrains-recorder
./gradlew :core:test --tests 'dev.provenance.core.ConformanceTest'
```
Expected: PASS. A failure here means either the export script's output doesn't match what `core/`'s Kotlin implementation produces (a real format bug — do not edit the vectors to make it pass) or the test's field-path assumptions about the sidecar JSON are wrong (fix the test, not the vectors).

- [ ] **Step 5: Update the README conformance note**

Replace Plan 1 Task 7's README paragraph:
```markdown
### Conformance

`core/`'s output is verified byte-for-byte against Provenance's `log-core` via
vectors in `core/src/test/resources/conformance/`. These are generated, not
hand-authored -- regenerate them from the monorepo with:

    cd ../provenance
    node --experimental-strip-types tools/export-conformance-vectors.ts \
      --out ../provenance-jetbrains-recorder/core/src/test/resources/conformance

Never hand-edit a vector file. A failing conformance test after regenerating
means the format has drifted -- fix core/'s implementation, never the vectors.
```

- [ ] **Step 6: Commit (this repo)**

```bash
cd /Users/aaryanmehta/projects/provenance-jetbrains-recorder
git add core/src/test/resources/conformance/ core/src/test/kotlin/dev/provenance/core/ConformanceTest.kt README.md
git commit --no-gpg-sign -m "test(core): regenerate conformance vectors from the monorepo export script"
```

---

## Self-Review

**Spec coverage against the four scope items:**
1. Gradle `build:prod`-equivalent embedding the course public key from an env var, never committed — Task 3 (`embedCourseKey`/`revertCourseKey`, mirrors `tools/embed-course-key.ts` including its refusal conditions), with `finalizedBy` making the revert more robust than the original (surfaced explicitly in Task 7).
2. `extension_hash` = SHA-256 of the distribution, wired into the seal — Tasks 1, 2, 4, 6 (pure algorithm in `core/`, CLI wrapper for CI, runtime wrapper for the seal call site, CI precompute task over the *signed* distribution). Includes an explicit, justified deviation from a literal reading of design.md §6 ("`.zip` bytes" vs. "extracted tree") with the reasoning spelled out, matching how the VS Code recorder actually behaves.
3. `signPlugin`/`verifyPlugin`/`publishPlugin` Marketplace flow with token/cert via env — Tasks 5, 6, 7, 8, with real cited property names (`certificateChain`, `privateKey`, `password`, `token`, `channels`) and a runbook for the parts that need real secrets.
4. The two monorepo changes — Task 9 (allowlist entry, using the script's existing `--hash` flag, plus a scoped producer-agnostic doc fix) and Tasks 10–11 (export script + regeneration in `provjet`).

**Placeholder scan:** Task 5's plugin-id default and Task 8's `sinceBuild` guess are explicitly flagged as needing operator confirmation, not silently assumed as final. Task 11's `ConformanceTest.kt` snippet flags its own function-name uncertainty pending Plan 2's actual implementation rather than guessing a name.

**REQUIRES OPERATOR SECRETS — cannot be executed autonomously (full list):**
- Task 6, Step 2 partially — `computeExtensionHashForCi` needs a real `signPlugin` output to run to completion (dry-run verification only is autonomous).
- Task 7 — publishing composite tasks can be graph-verified via `--dry-run` but not executed.
- Task 8 in full — real JetBrains Marketplace account/token, real code-signing certificate/private key/passphrase, real production course keypair, and the one-way plugin-id decision. This entire task is a human runbook, not agent-executable.
- Task 9, Step 1 — the *command* is mechanical; the *hash value* requires Task 8 having actually run for real.

**Fidelity note:** the IntelliJ Platform Gradle Plugin DSL snippets (Task 5) are grounded in real, cited documentation for task/property *names*, but the exact block-nesting syntax (`intellijPlatform { signing { } }` vs. direct `tasks.signPlugin { }`) could not be pinned with full confidence from the available fetches, which returned summarized rather than verbatim doc text and showed some inconsistency between pages. This is flagged inline at the point of use rather than presented as certain — an implementer should cross-check against the exact plugin version pinned in this repo's build files before relying on the block shape as written. Everything else (property *names*: `certificateChain`, `privateKey`, `password`, `token`, `channels`, `sinceBuild`, `untilBuild`, `id`, `version`) is quoted directly from the fetched pages and is high-confidence.

**Open dependency approvals:** none new — Task 1/2 add no dependency (pure `java.nio`/`java.security`). Task 4 uses `com.intellij.ide.plugins.PluginManagerCore`/`PluginId`, already implied by the IntelliJ Platform SDK dependency Plan 3 would have added. Task 10 adds no new npm dependency (uses `@provenance/log-core`, `@provenance/analysis-core`, `@noble/ed25519`, `@noble/hashes` — all already present in the monorepo).
