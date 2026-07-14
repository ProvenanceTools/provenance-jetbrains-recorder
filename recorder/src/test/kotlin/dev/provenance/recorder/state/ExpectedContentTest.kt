package dev.provenance.recorder.state

import dev.provenance.core.Sha256
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure test — no IntelliJ types, so this runs as a plain JUnit 4 unit test (the
 * recorder module runs on JUnit 4; see build.gradle.kts). Ports the intent of the
 * VS Code suite (state/expected-content.test.ts) adapted to offset-based deltas.
 */
class ExpectedContentTest {
    @Test
    fun `hash matches Sha256 of initial content`() {
        val ec = ExpectedContent("hello")
        assertEquals(Sha256.hex("hello"), ec.hash)
    }

    @Test
    fun `applyDelta splices at offset and invalidates the cached hash`() {
        val ec = ExpectedContent("hello world")
        ec.applyDelta(Delta(offset = 5, oldLength = 6, newText = " there"))
        assertEquals("hello there", ec.content)
        assertEquals(Sha256.hex("hello there"), ec.hash)
    }

    @Test
    fun `applyDeltas applies in order`() {
        val ec = ExpectedContent("abc")
        ec.applyDeltas(listOf(Delta(0, 1, "X"), Delta(1, 1, "Y")))
        assertEquals("XYc", ec.content)
    }

    @Test
    fun `reset replaces content and hash`() {
        val ec = ExpectedContent("abc")
        ec.reset("xyz")
        assertEquals("xyz", ec.content)
        assertEquals(Sha256.hex("xyz"), ec.hash)
    }

    @Test
    fun `lineCount counts newlines plus one, zero for empty`() {
        assertEquals(0, ExpectedContent("").lineCount)
        assertEquals(1, ExpectedContent("no newline").lineCount)
        assertEquals(3, ExpectedContent("a\nb\nc").lineCount)
        assertEquals(2, ExpectedContent("trailing\n").lineCount)
    }
}
