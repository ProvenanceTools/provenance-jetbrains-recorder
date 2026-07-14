package dev.provenance.recorder.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic port test (JUnit 4) mirroring the monorepo's explanation-tags.test.ts. The
 * clock is an injected `() -> Long`, so no IntelliJ Platform and no wall-clock dependency.
 */
class ExplanationTaggerTest {
    private var now: Long = 0
    private fun tagger(windowMs: Long = 2000) = ExplanationTagger(getNow = { now }, windowMs = windowMs)

    @Test
    fun `consume returns null when nothing marked`() {
        assertNull(tagger().consume())
    }

    @Test
    fun `markGit then consume within the window yields git`() {
        val t = tagger()
        t.markGit()
        assertEquals("git", t.consume())
    }

    @Test
    fun `markFormatter then consume within the window yields formatter`() {
        val t = tagger()
        t.markFormatter()
        assertEquals("formatter", t.consume())
    }

    @Test
    fun `consume is once-only — a second consume returns null`() {
        val t = tagger()
        t.markGit()
        assertEquals("git", t.consume())
        assertNull("one explanation explains exactly one external change", t.consume())
    }

    @Test
    fun `a tag older than the window is expired and cleared`() {
        val t = tagger(windowMs = 2000)
        t.markGit()
        now += 2000 // elapsed == windowMs → expired (>= boundary, mirrors TS)
        assertNull(t.consume())
        // Still cleared afterwards.
        now += 1
        assertNull(t.consume())
    }

    @Test
    fun `a tag just inside the window is still returned`() {
        val t = tagger(windowMs = 2000)
        t.markGit()
        now += 1999
        assertEquals("git", t.consume())
    }

    @Test
    fun `the latest mark wins`() {
        val t = tagger()
        t.markFormatter()
        t.markGit()
        assertEquals("git", t.consume())
    }
}
