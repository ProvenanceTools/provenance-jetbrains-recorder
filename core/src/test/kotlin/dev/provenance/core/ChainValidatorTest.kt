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
