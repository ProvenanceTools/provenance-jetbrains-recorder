package dev.provenance.recorder.session

import dev.provenance.core.FixedClock
import dev.provenance.core.GENESIS_PREV_HASH
import dev.provenance.core.HashedEnvelope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SessionHostTest {
    private fun data(v: String) = buildJsonObject { put("v", v) }

    @Test
    fun `first emit uses genesis prev hash and chains to second`() {
        val out = mutableListOf<HashedEnvelope>()
        val host = createSessionHost("s1", FixedClock(0, Instant.parse("2026-07-14T00:00:00Z"))) { out.add(it) }
        val e0 = host.emit("session.start", data("a"))
        val e1 = host.emit("doc.open", data("b"))
        assertEquals(GENESIS_PREV_HASH, e0.prevHash)
        assertEquals(e0.hash, e1.prevHash)
        assertEquals(0L, e0.seq)
        assertEquals(1L, e1.seq)
        assertEquals(listOf(e0, e1), out)
    }

    @Test
    fun `t is monotonic ms since session start`() {
        val clock = FixedClock(1000, Instant.parse("2026-07-14T00:00:00Z"))
        val host = createSessionHost("s1", clock) {}
        val e0 = host.emit("session.start", data("a")) // t = 0
        clock.advance(250)
        val e1 = host.emit("doc.change", data("b")) // t = 250
        clock.advance(750)
        val e2 = host.emit("doc.change", data("c")) // t = 1000
        assertEquals(0L, e0.t)
        assertEquals(250L, e1.t)
        assertEquals(1000L, e2.t)
    }

    @Test
    fun `onEntry throwing does not stop seq and prevHash from advancing`() {
        var throwOnce = true
        val host = createSessionHost("s1", FixedClock(0)) {
            if (throwOnce) {
                throwOnce = false
                throw RuntimeException("sink boom")
            }
        }
        assertThrows(RuntimeException::class.java) { host.emit("session.start", data("a")) }
        // State advanced despite the throw: next emit chains from seq 1 with a real prev hash.
        val e1 = host.emit("doc.open", data("b"))
        assertEquals(1L, e1.seq)
        assertNotEquals(GENESIS_PREV_HASH, e1.prevHash)
        assertEquals(2L, host.seq)
    }

    @Test
    fun `sessionId and seq accessors reflect state`() {
        val host = createSessionHost("sess-xyz", FixedClock(0)) {}
        assertEquals("sess-xyz", host.sessionId)
        assertEquals(0L, host.seq)
        host.emit("session.start", data("a"))
        assertEquals(1L, host.seq)
        assertTrue(host.tStartMs == 0L)
    }
}
