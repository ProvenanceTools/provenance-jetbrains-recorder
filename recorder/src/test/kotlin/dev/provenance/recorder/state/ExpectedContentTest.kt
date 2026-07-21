package dev.provenance.recorder.state

import dev.provenance.core.Sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // ---- recent-state ring (PRD §4.5 external-change race tolerance) -------

    private fun ExpectedContent.append(text: String) = applyDelta(Delta(content.length, 0, text))

    @Test
    fun `the initial content counts as a recent state`() {
        assertTrue(ExpectedContent("hello").hasRecentHash(Sha256.hex("hello")))
    }

    @Test
    fun `states the model passed through are recognised, states it never held are not`() {
        val ec = ExpectedContent("a")
        ec.append("b")
        ec.append("c")

        assertEquals("abc", ec.content)
        assertTrue(ec.hasRecentHash(Sha256.hex("a")))
        assertTrue(ec.hasRecentHash(Sha256.hex("ab")))
        assertTrue(ec.hasRecentHash(Sha256.hex("abc")))
        assertFalse(ec.hasRecentHash(Sha256.hex("something else entirely")))
    }

    @Test
    fun `applyDeltas records only the resulting state, not the states between deltas`() {
        val ec = ExpectedContent("a")
        ec.applyDeltas(listOf(Delta(1, 0, "b"), Delta(2, 0, "c")))

        assertEquals("abc", ec.content)
        assertTrue(ec.hasRecentHash(Sha256.hex("abc")))
        // "ab" existed only between the two deltas of one change event — never observable
        // as buffer content, so it must not widen the tolerance window.
        assertFalse(ec.hasRecentHash(Sha256.hex("ab")))
    }

    @Test
    fun `reset records the content it was given`() {
        val ec = ExpectedContent("a")
        ec.reset("external rewrite")
        assertTrue(ec.hasRecentHash(Sha256.hex("external rewrite")))
    }

    @Test
    fun `the ring is bounded and evicts the oldest states`() {
        val ec = ExpectedContent("")
        repeat(ExpectedContent.RECENT_HASH_RING_SIZE + 1) { ec.append("x") }

        assertFalse("the initial state has been evicted", ec.hasRecentHash(Sha256.hex("")))
        assertTrue(ec.hasRecentHash(Sha256.hex("x".repeat(ExpectedContent.RECENT_HASH_RING_SIZE + 1))))
        assertTrue("the oldest surviving state is retained", ec.hasRecentHash(Sha256.hex("xx")))
    }

    @Test
    fun `a no-op change does not consume a ring slot`() {
        val ec = ExpectedContent("seed")
        repeat(ExpectedContent.RECENT_HASH_RING_SIZE - 1) { ec.append("x") }
        assertTrue(ec.hasRecentHash(Sha256.hex("seed")))

        ec.applyDeltas(emptyList())
        ec.applyDeltas(emptyList())
        assertTrue("an empty delta list must not evict anything", ec.hasRecentHash(Sha256.hex("seed")))
    }
}
