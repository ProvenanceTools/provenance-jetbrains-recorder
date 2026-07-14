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
