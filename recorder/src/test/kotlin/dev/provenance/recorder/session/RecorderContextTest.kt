package dev.provenance.recorder.session

import dev.provenance.core.Manifest
import dev.provenance.core.Sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecorderContextTest {
    private fun manifest() = Manifest(
        assignmentId = "hw03",
        semester = "fa26",
        issuedAt = "2026-09-15T00:00:00Z",
        filesUnderReview = listOf("hw03.py"),
        sig = "ab".repeat(64),
    )

    @Test
    fun `buildRecorderContext produces the expected payload with fixed inputs`() {
        val p = buildRecorderContext(
            manifest = manifest(),
            prevSessionId = null,
            sessionId = "sess-1",
            sessionPubkeyHex = "d".repeat(64),
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "edu.berkeley.provenance.recorder",
            hostnameProvider = { "host-1" },
            usernameProvider = { "alice" },
        )
        assertEquals("1.0", p.formatVersion)
        assertEquals("sess-1", p.sessionId)
        assertNull(p.prevSessionId)
        assertEquals("hw03", p.assignmentId)
        assertEquals("fa26", p.assignmentSemester)
        assertEquals("ab".repeat(64), p.manifestSig)
        assertEquals("2026.1.4", p.vscodeVersion)
        assertEquals("", p.vscodeCommit)
        assertEquals("darwin-arm64", p.vscodePlatform)
        assertEquals("0.1.0", p.recorderVersion)
        assertEquals("edu.berkeley.provenance.recorder", p.recorderExtensionId)
        assertEquals("d".repeat(64), p.sessionPubkey)
        assertEquals(Sha256.hex("host-1:alice:sess-1"), p.machineId)
    }

    @Test
    fun `computeMachineId is deterministic and salted by each input`() {
        val base = computeMachineId("h", "u", "s")
        assertEquals(base, computeMachineId("h", "u", "s"))
        assertNotEquals(base, computeMachineId("h2", "u", "s"))
        assertNotEquals(base, computeMachineId("h", "u2", "s"))
        assertNotEquals(base, computeMachineId("h", "u", "s2"))
    }

    @Test
    fun `absent hostname falls back to unknown component`() {
        val p = buildRecorderContext(
            manifest = manifest(),
            prevSessionId = "prev",
            sessionId = "sess-2",
            sessionPubkeyHex = "d".repeat(64),
            ideVersion = "x",
            platform = "y",
            recorderVersion = "v",
            recorderExtensionId = "id",
            hostnameProvider = { null },
            usernameProvider = { "bob" },
        )
        assertEquals("prev", p.prevSessionId)
        assertEquals(Sha256.hex("unknown:bob:sess-2"), p.machineId)
    }
}
