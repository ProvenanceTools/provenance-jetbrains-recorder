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
