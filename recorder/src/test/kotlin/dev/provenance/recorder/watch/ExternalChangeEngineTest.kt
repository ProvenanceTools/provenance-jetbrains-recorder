package dev.provenance.recorder.watch

import dev.provenance.core.Sha256
import dev.provenance.recorder.state.ExpectedContentRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JUnit 4 test — the heart of external-change detection with NO IntelliJ types.
 * Every direction/dedup/payload/create/delete/reload decision is exercised here as a
 * pure function (CLAUDE.md: "test the direction/dedup/payload logic as pure functions
 * separate from VFS wiring"). Mirrors the intent of fs-watcher.test.ts +
 * external-change-detector.test.ts.
 */
class ExternalChangeEngineTest {
    private fun engine(vararg watched: String) =
        ExternalChangeEngine(ExpectedContentRegistry(watched.toList()))

    // ---- scope gate --------------------------------------------------------

    @Test
    fun `unwatched paths are ignored on every path`() {
        val e = engine("a.txt")
        assertNull(e.onSavedContent("other.txt", "x"))
        assertNull(e.onExternalModify("other.txt", "x"))
        assertNull(e.onExternalCreate("other.txt", "x"))
        assertNull(e.onExternalDelete("other.txt"))
        assertNull(e.onReload("other.txt", "x"))
    }

    // ---- path 1: save-time -------------------------------------------------

    @Test
    fun `save with no prior open is ignored`() {
        val e = engine("a.txt")
        assertNull(e.onSavedContent("a.txt", "content")) // never opened → nothing to compare
    }

    @Test
    fun `clean save does not emit and does not disturb the model`() {
        val e = engine("a.txt")
        e.registry.getOrCreate("a.txt", "hello")
        assertNull(e.onSavedContent("a.txt", "hello"))
        assertEquals("hello", e.registry.get("a.txt")!!.content)
    }

    @Test
    fun `save that diverged from expected emits modify with old=expected new=disk and resets`() {
        val e = engine("a.txt")
        e.registry.getOrCreate("a.txt", "editor model")
        val p = e.onSavedContent("a.txt", "formatter rewrote this")!!
        assertEquals("a.txt", p.path)
        assertEquals("modify", p.operation)
        assertEquals(Sha256.hex("editor model"), p.oldHash)      // direction: old = expected
        assertEquals(Sha256.hex("formatter rewrote this"), p.newHash) // new = disk
        assertEquals("formatter rewrote this", p.newContent)     // small → inlined
        // reset so the next comparison chains from disk reality:
        assertEquals("formatter rewrote this", e.registry.get("a.txt")!!.content)
    }

    // ---- path 2: external modify ------------------------------------------

    @Test
    fun `external modify with no prior open is skipped`() {
        val e = engine("a.txt")
        assertNull(e.onExternalModify("a.txt", "cli wrote this")) // no baseline
    }

    @Test
    fun `external modify emits with correct direction and resets the model`() {
        val e = engine("a.txt")
        e.registry.getOrCreate("a.txt", "student typed")
        val p = e.onExternalModify("a.txt", "claude cli output")!!
        assertEquals("modify", p.operation)
        assertEquals(Sha256.hex("student typed"), p.oldHash)
        assertEquals(Sha256.hex("claude cli output"), p.newHash)
        assertTrue(p.oldHash != p.newHash)
        assertEquals("claude cli output", e.registry.get("a.txt")!!.content)
    }

    @Test
    fun `external modify with identical content does not emit`() {
        val e = engine("a.txt")
        e.registry.getOrCreate("a.txt", "same")
        assertNull(e.onExternalModify("a.txt", "same")) // touched but unchanged
    }

    // ---- create ------------------------------------------------------------

    @Test
    fun `create with no baseline emits operation create with empty old_hash`() {
        val e = engine("new.txt")
        val p = e.onExternalCreate("new.txt", "brand new")!!
        assertEquals("create", p.operation)
        assertEquals("", p.oldHash)
        assertEquals(Sha256.hex("brand new"), p.newHash)
        assertEquals("brand new".length, p.diffSize) // abs(len - 0)
        assertEquals("brand new", p.newContent)
        // seeded so subsequent edits chain from this baseline:
        assertEquals("brand new", e.registry.get("new.txt")!!.content)
    }

    @Test
    fun `create when doc-open already seeded the registry becomes a modify against that baseline`() {
        val e = engine("a.txt")
        e.registry.getOrCreate("a.txt", "opened baseline")
        val p = e.onExternalCreate("a.txt", "diverged on disk")!!
        assertEquals("modify", p.operation)
        assertEquals(Sha256.hex("opened baseline"), p.oldHash)
        assertEquals(Sha256.hex("diverged on disk"), p.newHash)
    }

    @Test
    fun `create when doc-open baseline matches disk is silent`() {
        val e = engine("a.txt")
        e.registry.getOrCreate("a.txt", "identical")
        assertNull(e.onExternalCreate("a.txt", "identical"))
    }

    // ---- delete ------------------------------------------------------------

    @Test
    fun `delete of a tracked file emits with old=expected new empty and drops the entry`() {
        val e = engine("a.txt")
        e.registry.getOrCreate("a.txt", "was here")
        val p = e.onExternalDelete("a.txt")!!
        assertEquals("delete", p.operation)
        assertEquals(Sha256.hex("was here"), p.oldHash)
        assertEquals("", p.newHash)
        assertEquals("was here".length, p.diffSize)
        assertNull(p.newContent) // no content on delete
        assertNull(p.newContentSize)
        assertNull(e.registry.get("a.txt")) // dropped
    }

    @Test
    fun `delete of a watched-but-never-opened file still emits with empty hashes`() {
        val e = engine("a.txt")
        val p = e.onExternalDelete("a.txt")!!
        assertEquals("delete", p.operation)
        assertEquals("", p.oldHash)
        assertEquals("", p.newHash)
        assertEquals(0, p.diffSize)
    }

    // ---- path 3: reload ----------------------------------------------------

    @Test
    fun `reload with no prior entry seeds the registry silently`() {
        val e = engine("a.txt")
        assertNull(e.onReload("a.txt", "reloaded content"))
        assertEquals("reloaded content", e.registry.get("a.txt")!!.content)
    }

    @Test
    fun `reload that diverged from expected emits modify and resets`() {
        val e = engine("a.txt")
        e.registry.getOrCreate("a.txt", "editor buffer")
        val p = e.onReload("a.txt", "silently reloaded from disk")!!
        assertEquals("modify", p.operation)
        assertEquals(Sha256.hex("editor buffer"), p.oldHash)
        assertEquals(Sha256.hex("silently reloaded from disk"), p.newHash)
        assertEquals("silently reloaded from disk", e.registry.get("a.txt")!!.content)
    }

    @Test
    fun `reload matching the expected model is silent`() {
        val e = engine("a.txt")
        e.registry.getOrCreate("a.txt", "unchanged")
        assertNull(e.onReload("a.txt", "unchanged"))
    }

    // ---- large content truncation carried through --------------------------

    @Test
    fun `large external content is truncated to head and tail on the payload`() {
        val e = engine("big.txt")
        e.registry.getOrCreate("big.txt", "small")
        val big = "x".repeat(5000)
        val p = e.onExternalModify("big.txt", big)!!
        assertNull(p.newContent)
        assertEquals(5000, p.newContentSize)
        assertEquals("x".repeat(512), p.newContentHead)
        assertEquals("x".repeat(512), p.newContentTail)
    }

    // ---- explanation stays null in Plan 5 ---------------------------------

    @Test
    fun `explanation is null in Plan 5 for all emissions`() {
        val e = engine("a.txt")
        e.registry.getOrCreate("a.txt", "base")
        assertNull(e.onExternalModify("a.txt", "changed")!!.explanation)
    }
}
