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
