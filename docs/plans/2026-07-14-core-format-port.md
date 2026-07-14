# Core Format Port Implementation Plan (Plan 1 of the provjet series)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `core/` — a pure-Kotlin/JVM reimplementation of Provenance's log-format primitives (sha256, JCS canonicalization, the one hash-chaining function, NDJSON, chain validation) proven byte-for-byte identical to `log-core` via pinned conformance vectors.

**Architecture:** A standalone Gradle module (`core/`) with **zero IntelliJ Platform dependencies** — the Kotlin analogue of log-core's "no editor deps" rule. It emits JSON via kotlinx.serialization, canonicalizes with `erdtman/java-json-canonicalization` (the JVM twin of the `canonicalize` npm lib), and hashes with `java.security.MessageDigest`. A conformance test asserts the exact pinned outputs from log-core's `hash-chain.test.ts`.

**Tech Stack:** Kotlin (JVM), Gradle (Kotlin DSL), JUnit 5, kotlinx-serialization-json, `io.github.erdtman:java-json-canonicalization`.

## Plan series (context)

This is Plan 1 of a series derived from `docs/design.md`. Later plans (written when reached, because IntelliJ wiring specifics are best pinned against the live SDK):

- **Plan 1 (this):** `core/` — hashing, JCS, chain, NDJSON, chain-validator, conformance gate.
- **Plan 2:** `core/` — bundle manifest (shape + ed25519 sign), session keypair, signed checkpoints.
- **Plan 3:** plugin scaffold (IntelliJ Platform Gradle Plugin) + activation + manifest verification + status-bar widget + **sideload build for testing**.
- **Plan 4:** `doc.open/change/save/close` wiring + atomic session writer + bundle seal → first analyzer-accepted bundle.
- **Plan 5:** external-change detection (VFS — highest-risk).
- **Plan 6:** three-signal paste detection.
- **Plan 7:** terminal + git wiring + plugin snapshot.
- **Plan 8:** checkpoints wiring + chain recovery + disk-full degraded mode.
- **Plan 9:** `build:prod` course-key embedding + `extension_hash` + **Marketplace packaging** (Marketplace is the primary channel; Plan 3's sideload is for early testing only). Plus the two monorepo changes: allowlist entry + golden-vector export script.

## Global Constraints

- **Format is a fixed contract owned by the monorepo's `log-core`.** This module reproduces it; it never redefines it. If a vector cannot be matched, STOP and ask — do not edit the vector. (CLAUDE.md)
- **`core/` has zero IntelliJ / plugin dependencies.** Pure Kotlin/JVM. (design.md §9, CLAUDE.md)
- **Hash formula (PRD §5.2):** `entry.hash = sha256_hex(prev_hash_string + JCS(entry))`, where `entry` is `{seq, t, wall, kind, data}` with **no** `prev_hash`/`hash` fields.
- **Genesis prev_hash:** 64 lowercase-hex zeros.
- **All hashes are 64-char lowercase hex.**
- **Do not hand-roll JCS canonicalization.** Use the erdtman library. (CLAUDE.md)
- **Determinism:** no wall-clock / randomness in tests; assert against pinned constants.
- **Commits:** conventional prefixes, `git commit --no-gpg-sign`, no Claude co-author trailer, explicit pathspec.

### Pinned conformance vectors (from log-core `hash-chain.test.ts`)

- `sha256_hex("hello world")` = `b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9`
- `sha256_hex("")` = `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- `GENESIS_PREV_HASH` = `"0".repeat(64)`
- `chainEntry(GENESIS, {seq:0, t:0, wall:"2026-01-01T00:00:00.000Z", kind:"session.end", data:{reason:"test"}}).hash` = `d33cad1d38b90b26a2f7b1181801805233bf4332eca5bc6d4ff4e1b677683625`

---

### Task 1: Gradle scaffold + `core` module + sha256

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `core/build.gradle.kts`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `core/src/main/kotlin/dev/provenance/core/Sha256.kt`
- Test: `core/src/test/kotlin/dev/provenance/core/Sha256Test.kt`

**Interfaces:**
- Produces: `object Sha256 { fun hex(input: ByteArray): String; fun hex(input: String): String }` — returns 64-char lowercase hex. `String` overload encodes UTF-8.

- [ ] **Step 1: Create the Gradle wrapper properties**

`gradle/wrapper/gradle-wrapper.properties`:
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```
Then generate the wrapper jar/scripts: `gradle wrapper --gradle-version 8.10` (or copy from an existing project). Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` created.

- [ ] **Step 2: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "provenance-jetbrains-recorder"

include("core")
```

- [ ] **Step 3: Create the root `build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}
```

- [ ] **Step 4: Create `core/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
```

- [ ] **Step 5: Write the failing test**

`core/src/test/kotlin/dev/provenance/core/Sha256Test.kt`:
```kotlin
package dev.provenance.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Sha256Test {
    @Test
    fun `hex of hello world matches NIST vector`() {
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            Sha256.hex("hello world"),
        )
    }

    @Test
    fun `hex of empty string matches NIST vector`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(""),
        )
    }

    @Test
    fun `hex is 64 lowercase hex chars`() {
        val h = Sha256.hex("anything")
        assertEquals(64, h.length)
        assert(h.matches(Regex("^[0-9a-f]{64}$")))
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `./gradlew :core:test --tests 'dev.provenance.core.Sha256Test'`
Expected: FAIL — `Sha256` unresolved reference.

- [ ] **Step 7: Write the minimal implementation**

`core/src/main/kotlin/dev/provenance/core/Sha256.kt`:
```kotlin
package dev.provenance.core

import java.security.MessageDigest

/** SHA-256 → 64-char lowercase hex. Mirrors log-core's sha256Hex. */
object Sha256 {
    fun hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        val sb = StringBuilder(64)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }

    fun hex(input: String): String = hex(input.toByteArray(Charsets.UTF_8))
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `./gradlew :core:test --tests 'dev.provenance.core.Sha256Test'`
Expected: PASS (3 tests).

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts build.gradle.kts core/build.gradle.kts \
  gradle/ gradlew gradlew.bat \
  core/src/main/kotlin/dev/provenance/core/Sha256.kt \
  core/src/test/kotlin/dev/provenance/core/Sha256Test.kt
git commit --no-gpg-sign -m "feat(core): sha256 hex matching log-core vectors + gradle scaffold"
```

---

### Task 2: JCS canonicalization wrapper

**Files:**
- Create: `core/src/main/kotlin/dev/provenance/core/Canonical.kt`
- Test: `core/src/test/kotlin/dev/provenance/core/CanonicalTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `object Canonical { fun canonicalize(jsonText: String): String }` — takes JSON text, returns its RFC 8785 (JCS) canonical form. Throws `IllegalArgumentException` on invalid JSON.

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/dev/provenance/core/CanonicalTest.kt`:
```kotlin
package dev.provenance.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalTest {
    @Test
    fun `sorts object keys lexicographically`() {
        assertEquals(
            """{"a":2,"b":1}""",
            Canonical.canonicalize("""{"b":1,"a":2}"""),
        )
    }

    @Test
    fun `strips insignificant whitespace`() {
        assertEquals(
            """{"a":1}""",
            Canonical.canonicalize("{  \"a\" : 1  }"),
        )
    }

    @Test
    fun `sorts nested object keys`() {
        assertEquals(
            """{"outer":{"x":1,"y":2}}""",
            Canonical.canonicalize("""{"outer":{"y":2,"x":1}}"""),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests 'dev.provenance.core.CanonicalTest'`
Expected: FAIL — `Canonical` unresolved reference.

- [ ] **Step 3: Write the implementation**

`core/src/main/kotlin/dev/provenance/core/Canonical.kt`:
```kotlin
package dev.provenance.core

import org.erdtman.jcs.JsonCanonicalizer

/**
 * RFC 8785 (JCS) canonical JSON — the JVM twin of log-core's `canonicalize`.
 * Deterministic key ordering, no insignificant whitespace, canonical numbers.
 * Do not hand-roll; this is the pinned contract surface.
 */
object Canonical {
    fun canonicalize(jsonText: String): String =
        try {
            JsonCanonicalizer(jsonText).encodedString
        } catch (e: Exception) {
            throw IllegalArgumentException("canonicalize: invalid JSON input", e)
        }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests 'dev.provenance.core.CanonicalTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dev/provenance/core/Canonical.kt \
  core/src/test/kotlin/dev/provenance/core/CanonicalTest.kt
git commit --no-gpg-sign -m "feat(core): JCS canonicalization wrapper over erdtman lib"
```

---

### Task 3: Envelope model + JSON emission

**Files:**
- Create: `core/src/main/kotlin/dev/provenance/core/Envelope.kt`
- Test: `core/src/test/kotlin/dev/provenance/core/EnvelopeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class Envelope(val seq: Long, val t: Long, val wall: String, val kind: String, val data: JsonObject)` — the pre-hash entry. `data` is a `kotlinx.serialization.json.JsonObject` so arbitrary event payloads round-trip without a fixed schema.
  - `data class HashedEnvelope(... same fields ..., val prevHash: String, val hash: String)`.
  - `fun Envelope.toJsonText(): String` — serializes `{seq,t,wall,kind,data}` to JSON text (field order irrelevant; JCS re-sorts).
  - `fun HashedEnvelope.toJsonText(): String` — serializes all seven fields, using JSON keys `prev_hash` and `hash` (snake_case on the wire).

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/dev/provenance/core/EnvelopeTest.kt`:
```kotlin
package dev.provenance.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EnvelopeTest {
    private val data = buildJsonObject { put("reason", "test") }

    @Test
    fun `envelope canonicalizes to sorted-key JSON without hash fields`() {
        val env = Envelope(seq = 0, t = 0, wall = "2026-01-01T00:00:00.000Z", kind = "session.end", data = data)
        val canonical = Canonical.canonicalize(env.toJsonText())
        assertEquals(
            """{"data":{"reason":"test"},"kind":"session.end","seq":0,"t":0,"wall":"2026-01-01T00:00:00.000Z"}""",
            canonical,
        )
    }

    @Test
    fun `hashed envelope uses snake_case prev_hash and hash keys`() {
        val he = HashedEnvelope(0, 0, "2026-01-01T00:00:00.000Z", "session.end", data, prevHash = "0".repeat(64), hash = "a".repeat(64))
        val obj = Json.parseToJsonElement(he.toJsonText())
        assert(he.toJsonText().contains("\"prev_hash\""))
        assert(he.toJsonText().contains("\"hash\""))
        assertEquals(false, obj.toString().contains("prevHash"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests 'dev.provenance.core.EnvelopeTest'`
Expected: FAIL — `Envelope` unresolved reference.

- [ ] **Step 3: Write the implementation**

`core/src/main/kotlin/dev/provenance/core/Envelope.kt`:
```kotlin
package dev.provenance.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Pre-hash log entry: {seq, t, wall, kind, data}. Mirrors log-core's Envelope. */
data class Envelope(
    val seq: Long,
    val t: Long,
    val wall: String,
    val kind: String,
    val data: JsonObject,
)

/** Chained log entry: Envelope + prev_hash + hash. */
data class HashedEnvelope(
    val seq: Long,
    val t: Long,
    val wall: String,
    val kind: String,
    val data: JsonObject,
    val prevHash: String,
    val hash: String,
)

/** JSON text of the pre-hash entry. Field order is irrelevant — JCS re-sorts. */
fun Envelope.toJsonText(): String =
    buildJsonObject {
        put("seq", seq)
        put("t", t)
        put("wall", wall)
        put("kind", kind)
        put("data", data)
    }.toString()

/** JSON text of the chained entry, using on-the-wire snake_case hash keys. */
fun HashedEnvelope.toJsonText(): String =
    buildJsonObject {
        put("seq", seq)
        put("t", t)
        put("wall", wall)
        put("kind", kind)
        put("data", data)
        put("prev_hash", prevHash)
        put("hash", hash)
    }.toString()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests 'dev.provenance.core.EnvelopeTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dev/provenance/core/Envelope.kt \
  core/src/test/kotlin/dev/provenance/core/EnvelopeTest.kt
git commit --no-gpg-sign -m "feat(core): Envelope/HashedEnvelope model + JSON emission"
```

---

### Task 4: The hash chain (the pinned cross-language gate)

**Files:**
- Create: `core/src/main/kotlin/dev/provenance/core/HashChain.kt`
- Test: `core/src/test/kotlin/dev/provenance/core/HashChainTest.kt`

**Interfaces:**
- Consumes: `Envelope`, `HashedEnvelope`, `Envelope.toJsonText()` (Task 3); `Canonical.canonicalize` (Task 2); `Sha256.hex` (Task 1).
- Produces:
  - `const val GENESIS_PREV_HASH: String` = 64 zeros.
  - `fun chainEntry(prevHash: String, entry: Envelope): HashedEnvelope` — `hash = Sha256.hex(prevHash + Canonical.canonicalize(entry.toJsonText()))`.

- [ ] **Step 1: Write the failing test (includes the pinned vector)**

`core/src/test/kotlin/dev/provenance/core/HashChainTest.kt`:
```kotlin
package dev.provenance.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class HashChainTest {
    private fun sessionEnd(seq: Long, t: Long, wall: String, reason: String) =
        Envelope(seq, t, wall, "session.end", buildJsonObject { put("reason", reason) })

    @Test
    fun `genesis prev hash is 64 zeros`() {
        assertEquals("0".repeat(64), GENESIS_PREV_HASH)
    }

    @Test
    fun `chainEntry matches the log-core pinned vector`() {
        val env = sessionEnd(0, 0, "2026-01-01T00:00:00.000Z", "test")
        val result = chainEntry(GENESIS_PREV_HASH, env)
        assertEquals(
            "d33cad1d38b90b26a2f7b1181801805233bf4332eca5bc6d4ff4e1b677683625",
            result.hash,
        )
        assertEquals(GENESIS_PREV_HASH, result.prevHash)
    }

    @Test
    fun `second entry links to the first`() {
        val h0 = chainEntry(GENESIS_PREV_HASH, sessionEnd(0, 0, "2026-01-01T00:00:00.000Z", "test"))
        val h1 = chainEntry(h0.hash, sessionEnd(1, 1000, "2026-01-01T00:00:01.000Z", "test"))
        assertEquals(h0.hash, h1.prevHash)
        assertNotEquals(h0.hash, h1.hash)
    }

    @Test
    fun `differing data changes the hash`() {
        val a = chainEntry(GENESIS_PREV_HASH, sessionEnd(0, 0, "2026-01-01T00:00:00.000Z", "a"))
        val b = chainEntry(GENESIS_PREV_HASH, sessionEnd(0, 0, "2026-01-01T00:00:00.000Z", "b"))
        assertNotEquals(a.hash, b.hash)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests 'dev.provenance.core.HashChainTest'`
Expected: FAIL — `chainEntry` / `GENESIS_PREV_HASH` unresolved.

- [ ] **Step 3: Write the implementation**

`core/src/main/kotlin/dev/provenance/core/HashChain.kt`:
```kotlin
package dev.provenance.core

/** The ONE hash-chaining function (PRD §5.2). Mirrors log-core's chainEntry. */
const val GENESIS_PREV_HASH: String = "0000000000000000000000000000000000000000000000000000000000000000"

fun chainEntry(prevHash: String, entry: Envelope): HashedEnvelope {
    val canonical = Canonical.canonicalize(entry.toJsonText())
    val hash = Sha256.hex(prevHash + canonical)
    return HashedEnvelope(
        seq = entry.seq,
        t = entry.t,
        wall = entry.wall,
        kind = entry.kind,
        data = entry.data,
        prevHash = prevHash,
        hash = hash,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests 'dev.provenance.core.HashChainTest'`
Expected: PASS (4 tests). **If `chainEntry matches the log-core pinned vector` fails, the Kotlin JCS/serialization does not match log-core — STOP and diagnose canonicalization; do not change the expected hash.**

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dev/provenance/core/HashChain.kt \
  core/src/test/kotlin/dev/provenance/core/HashChainTest.kt
git commit --no-gpg-sign -m "feat(core): hash chain matching log-core pinned vector"
```

---

### Task 5: NDJSON serialization + parsing

**Files:**
- Create: `core/src/main/kotlin/dev/provenance/core/Ndjson.kt`
- Test: `core/src/test/kotlin/dev/provenance/core/NdjsonTest.kt`

**Interfaces:**
- Consumes: `HashedEnvelope`, `HashedEnvelope.toJsonText()` (Task 3); `Canonical.canonicalize` (Task 2).
- Produces:
  - `fun serializeEntry(entry: HashedEnvelope): String` — `Canonical.canonicalize(entry.toJsonText()) + "\n"`.
  - `sealed interface ParseResult { data class Ok(val entries: List<HashedEnvelope>); data class Err(val line: Int, val message: String) }`
  - `fun parseEntries(text: String): ParseResult` — splits on `\n`, skips empty lines, JSON-parses + shape-validates each (fields `seq:Long, t:Long, wall:String, kind:String, data:object, prev_hash:64hex, hash:64hex`), returns on first error (1-indexed line).

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/dev/provenance/core/NdjsonTest.kt`:
```kotlin
package dev.provenance.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NdjsonTest {
    private val entry = chainEntry(
        GENESIS_PREV_HASH,
        Envelope(0, 0, "2026-01-01T00:00:00.000Z", "session.end", buildJsonObject { put("reason", "test") }),
    )

    @Test
    fun `serializeEntry ends with a newline and is canonical`() {
        val line = serializeEntry(entry)
        assertTrue(line.endsWith("\n"))
        assertEquals(Canonical.canonicalize(entry.toJsonText()) + "\n", line)
    }

    @Test
    fun `round-trips a single entry`() {
        val result = parseEntries(serializeEntry(entry))
        result as ParseResult.Ok
        assertEquals(1, result.entries.size)
        assertEquals(entry.hash, result.entries[0].hash)
    }

    @Test
    fun `empty string parses to zero entries`() {
        val result = parseEntries("")
        result as ParseResult.Ok
        assertEquals(0, result.entries.size)
    }

    @Test
    fun `reports the failing line on invalid json`() {
        val text = serializeEntry(entry) + "not json\n"
        val result = parseEntries(text)
        result as ParseResult.Err
        assertEquals(2, result.line)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests 'dev.provenance.core.NdjsonTest'`
Expected: FAIL — `serializeEntry` / `parseEntries` / `ParseResult` unresolved.

- [ ] **Step 3: Write the implementation**

`core/src/main/kotlin/dev/provenance/core/Ndjson.kt`:
```kotlin
package dev.provenance.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.contentOrNull

private val HEX_64 = Regex("^[0-9a-f]{64}$")

sealed interface ParseResult {
    data class Ok(val entries: List<HashedEnvelope>) : ParseResult
    data class Err(val line: Int, val message: String) : ParseResult
}

/** One NDJSON line: JCS-canonical JSON + newline. Mirrors log-core serializeEntry. */
fun serializeEntry(entry: HashedEnvelope): String =
    Canonical.canonicalize(entry.toJsonText()) + "\n"

/** Parse NDJSON text into HashedEnvelopes. Returns on the first error (1-indexed line). */
fun parseEntries(text: String): ParseResult {
    if (text == "") return ParseResult.Ok(emptyList())
    val out = ArrayList<HashedEnvelope>()
    val lines = text.split("\n")
    for (i in lines.indices) {
        val line = lines[i]
        if (line.isEmpty()) continue
        val lineNumber = i + 1
        val obj = try {
            Json.parseToJsonElement(line) as? JsonObject
                ?: return ParseResult.Err(lineNumber, "not a JSON object")
        } catch (e: Exception) {
            return ParseResult.Err(lineNumber, "invalid JSON: ${e.message}")
        }
        val env = validateShape(obj) ?: return ParseResult.Err(lineNumber, "invalid shape")
        out.add(env)
    }
    return ParseResult.Ok(out)
}

private fun validateShape(obj: JsonObject): HashedEnvelope? {
    val seq = (obj["seq"]?.jsonPrimitive)?.long ?: return null
    val t = (obj["t"]?.jsonPrimitive)?.long ?: return null
    val wall = (obj["wall"]?.jsonPrimitive)?.contentOrNull ?: return null
    val kind = (obj["kind"]?.jsonPrimitive)?.contentOrNull ?: return null
    val data = obj["data"] as? JsonObject ?: return null
    val prevHash = (obj["prev_hash"]?.jsonPrimitive)?.contentOrNull ?: return null
    val hash = (obj["hash"]?.jsonPrimitive)?.contentOrNull ?: return null
    if (!HEX_64.matches(prevHash) || !HEX_64.matches(hash)) return null
    return HashedEnvelope(seq, t, wall, kind, data, prevHash, hash)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests 'dev.provenance.core.NdjsonTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dev/provenance/core/Ndjson.kt \
  core/src/test/kotlin/dev/provenance/core/NdjsonTest.kt
git commit --no-gpg-sign -m "feat(core): NDJSON serialize + parse with shape validation"
```

---

### Task 6: Chain validator

**Files:**
- Create: `core/src/main/kotlin/dev/provenance/core/ChainValidator.kt`
- Test: `core/src/test/kotlin/dev/provenance/core/ChainValidatorTest.kt`

**Interfaces:**
- Consumes: `HashedEnvelope`, `Envelope` (Task 3); `chainEntry`, `GENESIS_PREV_HASH` (Task 4).
- Produces:
  - `sealed interface ChainCheck { data object Valid; data class Broken(val seq: Long, val reason: String) }`
  - `fun validateChain(entries: List<HashedEnvelope>): ChainCheck` — verifies for each entry `i`: `prevHash == (i==0 ? GENESIS : entries[i-1].hash)` **and** the recomputed hash equals the stored hash (recompute via `chainEntry(prevHash, asEnvelope(entry)).hash`). Empty list → `Valid`.

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/dev/provenance/core/ChainValidatorTest.kt`:
```kotlin
package dev.provenance.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChainValidatorTest {
    private fun end(seq: Long) =
        Envelope(seq, seq * 1000, "2026-01-01T00:00:0${seq}.000Z", "session.end", buildJsonObject { put("reason", "x") })

    private fun goodChain(): List<HashedEnvelope> {
        val h0 = chainEntry(GENESIS_PREV_HASH, end(0))
        val h1 = chainEntry(h0.hash, end(1))
        return listOf(h0, h1)
    }

    @Test
    fun `accepts a valid chain`() {
        assertEquals(ChainCheck.Valid, validateChain(goodChain()))
    }

    @Test
    fun `accepts the empty chain`() {
        assertEquals(ChainCheck.Valid, validateChain(emptyList()))
    }

    @Test
    fun `rejects a tampered data field`() {
        val chain = goodChain().toMutableList()
        val bad = chain[1].copy(data = buildJsonObject { put("reason", "tampered") })
        chain[1] = bad
        val result = validateChain(chain)
        assert(result is ChainCheck.Broken)
        assertEquals(1L, (result as ChainCheck.Broken).seq)
    }

    @Test
    fun `rejects a broken prev-hash link`() {
        val chain = goodChain().toMutableList()
        chain[1] = chain[1].copy(prevHash = "0".repeat(64))
        assert(validateChain(chain) is ChainCheck.Broken)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests 'dev.provenance.core.ChainValidatorTest'`
Expected: FAIL — `validateChain` / `ChainCheck` unresolved.

- [ ] **Step 3: Write the implementation**

`core/src/main/kotlin/dev/provenance/core/ChainValidator.kt`:
```kotlin
package dev.provenance.core

sealed interface ChainCheck {
    data object Valid : ChainCheck
    data class Broken(val seq: Long, val reason: String) : ChainCheck
}

private fun HashedEnvelope.asEnvelope(): Envelope = Envelope(seq, t, wall, kind, data)

/** Verify prev_hash linkage and recomputed hashes across the chain (PRD §5.2). */
fun validateChain(entries: List<HashedEnvelope>): ChainCheck {
    var expectedPrev = GENESIS_PREV_HASH
    for (entry in entries) {
        if (entry.prevHash != expectedPrev) {
            return ChainCheck.Broken(entry.seq, "prev_hash mismatch")
        }
        val recomputed = chainEntry(entry.prevHash, entry.asEnvelope()).hash
        if (recomputed != entry.hash) {
            return ChainCheck.Broken(entry.seq, "hash mismatch (tampered content)")
        }
        expectedPrev = entry.hash
    }
    return ChainCheck.Valid
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests 'dev.provenance.core.ChainValidatorTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dev/provenance/core/ChainValidator.kt \
  core/src/test/kotlin/dev/provenance/core/ChainValidatorTest.kt
git commit --no-gpg-sign -m "feat(core): chain validator (prev-hash linkage + recomputed hashes)"
```

---

### Task 7: Conformance vectors resource + gate test

**Files:**
- Create: `core/src/test/resources/conformance/vectors.json`
- Create: `core/src/test/kotlin/dev/provenance/core/ConformanceTest.kt`
- Modify: `README.md` — add a "Conformance" note (how vectors are sourced/regenerated).

**Interfaces:**
- Consumes: `Sha256.hex`, `Canonical.canonicalize`, `chainEntry`, `GENESIS_PREV_HASH`, `Envelope`.
- Produces: nothing consumed downstream — this is a gate. It reads the checked-in `vectors.json` and asserts every entry.

**Note:** For Plan 1, `vectors.json` is authored by hand from log-core's pinned values (below). Plan 9 adds a monorepo export script that regenerates this file from `log-core` directly, so drift is caught mechanically.

- [ ] **Step 1: Create the vectors resource**

`core/src/test/resources/conformance/vectors.json`:
```json
{
  "source": "log-core hash-chain.test.ts (pinned)",
  "sha256": [
    { "input": "hello world", "hex": "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9" },
    { "input": "", "hex": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" }
  ],
  "chain": [
    {
      "prev_hash": "0000000000000000000000000000000000000000000000000000000000000000",
      "envelope": { "seq": 0, "t": 0, "wall": "2026-01-01T00:00:00.000Z", "kind": "session.end", "data": { "reason": "test" } },
      "hash": "d33cad1d38b90b26a2f7b1181801805233bf4332eca5bc6d4ff4e1b677683625"
    }
  ]
}
```

- [ ] **Step 2: Write the failing test**

`core/src/test/kotlin/dev/provenance/core/ConformanceTest.kt`:
```kotlin
package dev.provenance.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConformanceTest {
    private val vectors: JsonObject by lazy {
        val text = this::class.java.getResource("/conformance/vectors.json")!!.readText()
        Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `sha256 vectors match`() {
        for (v in vectors["sha256"]!!.jsonArray) {
            val o = v.jsonObject
            assertEquals(o["hex"]!!.jsonPrimitive.content, Sha256.hex(o["input"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun `chain vectors match`() {
        for (v in vectors["chain"]!!.jsonArray) {
            val o = v.jsonObject
            val e = o["envelope"]!!.jsonObject
            val env = Envelope(
                seq = e["seq"]!!.jsonPrimitive.long,
                t = e["t"]!!.jsonPrimitive.long,
                wall = e["wall"]!!.jsonPrimitive.content,
                kind = e["kind"]!!.jsonPrimitive.content,
                data = e["data"]!!.jsonObject,
            )
            val result = chainEntry(o["prev_hash"]!!.jsonPrimitive.content, env)
            assertEquals(o["hash"]!!.jsonPrimitive.content, result.hash)
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails, then passes**

Run: `./gradlew :core:test --tests 'dev.provenance.core.ConformanceTest'`
Expected: initially FAIL if any wiring is off; PASS once Tasks 1–4 are correct. This is the cross-language gate — a failure here means the Kotlin format is not identical to log-core.

- [ ] **Step 4: Add the README conformance note**

Append to `README.md` under "Documentation":
```markdown
### Conformance

`core/`'s output is verified byte-for-byte against Provenance's `log-core` via
pinned vectors in `core/src/test/resources/conformance/vectors.json`. A failing
conformance test means the format has drifted — fix the implementation, never
the vectors.
```

- [ ] **Step 5: Run the full module test suite**

Run: `./gradlew :core:test`
Expected: PASS — all suites (Sha256, Canonical, Envelope, HashChain, Ndjson, ChainValidator, Conformance) green.

- [ ] **Step 6: Commit**

```bash
git add core/src/test/resources/conformance/vectors.json \
  core/src/test/kotlin/dev/provenance/core/ConformanceTest.kt README.md
git commit --no-gpg-sign -m "test(core): cross-language conformance gate against log-core vectors"
```

---

## Self-Review

**Spec coverage (design.md §3 — the format-parity core):** sha256 (Task 1), JCS via erdtman (Task 2), envelope + emission (Task 3), the one hash chain matching the pinned vector (Task 4), NDJSON (Task 5), chain validation (Task 6), conformance gate (Task 7). Bundle manifest signing, session keypair, and checkpoints are **deliberately deferred to Plan 2** (they need ed25519 and pair naturally with the seal path) — noted in the plan series. `core/`'s zero-IntelliJ-dependency rule is enforced structurally (the module has no plugin deps).

**Placeholder scan:** none — every code step contains full Kotlin/Gradle content and every command has an expected result.

**Type consistency:** `Envelope`/`HashedEnvelope` fields and `toJsonText()` are defined in Task 3 and consumed unchanged in Tasks 4–7; `chainEntry`/`GENESIS_PREV_HASH` defined in Task 4 and reused in Tasks 6–7; `Sha256.hex`/`Canonical.canonicalize` signatures stable throughout.

**Open dependency approvals (Global Constraints):** kotlinx-serialization-json, erdtman java-json-canonicalization, JUnit 5. These are the approved core set from CLAUDE.md plus a JSON emitter — confirm before Task 1.
