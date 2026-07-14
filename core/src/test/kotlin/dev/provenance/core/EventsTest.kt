package dev.provenance.core

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventsTest {
    @Test
    fun `session start payload emits PRD 5_1 shape with snake_case keys`() {
        val p = SessionStartPayload(
            formatVersion = "1.0", sessionId = "abc", prevSessionId = null,
            assignmentId = "hw03", assignmentSemester = "fa26",
            manifestSig = "deadbeef", machineId = "cafebabe",
            vscodeVersion = "2026.2", vscodeCommit = "", vscodePlatform = "darwin-arm64",
            recorderVersion = "0.1.0", recorderExtensionId = "edu.berkeley.provenance.recorder",
            sessionPubkey = "a".repeat(64),
        )
        val obj = p.toJsonObject()
        assertEquals("1.0", obj["format_version"]!!.jsonPrimitive.content)
        assertEquals("abc", obj["session_id"]!!.jsonPrimitive.content)
        assertEquals("hw03", obj["assignment"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("fa26", obj["assignment"]!!.jsonObject["semester"]!!.jsonPrimitive.content)
        assertEquals("deadbeef", obj["manifest_sig"]!!.jsonPrimitive.content)
        assertEquals("cafebabe", obj["machine_id"]!!.jsonPrimitive.content)
        assertEquals("2026.2", obj["vscode"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertEquals("", obj["vscode"]!!.jsonObject["commit"]!!.jsonPrimitive.content)
        assertEquals("darwin-arm64", obj["vscode"]!!.jsonObject["platform"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", obj["recorder"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertEquals("edu.berkeley.provenance.recorder", obj["recorder"]!!.jsonObject["extension_id"]!!.jsonPrimitive.content)
        assertEquals("a".repeat(64), obj["session_pubkey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `session start commit is present-and-empty not absent`() {
        val p = SessionStartPayload(
            formatVersion = "1.0", sessionId = "abc", prevSessionId = "prev",
            assignmentId = "hw03", assignmentSemester = "fa26",
            manifestSig = "deadbeef", machineId = "cafebabe",
            vscodeVersion = "2026.2", vscodeCommit = "", vscodePlatform = "linux-x64",
            recorderVersion = "0.1.0", recorderExtensionId = "id",
            sessionPubkey = "a".repeat(64),
        )
        val vscode = p.toJsonObject()["vscode"]!!.jsonObject
        assertTrue(vscode.containsKey("commit"))
        assertEquals("", vscode["commit"]!!.jsonPrimitive.content)
        assertEquals("prev", p.toJsonObject()["prev_session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `session heartbeat payload shape`() {
        val obj = SessionHeartbeatPayload(focused = true, activeFile = "hw.py", idleSinceMs = 1500).toJsonObject()
        assertEquals(true, obj["focused"]!!.jsonPrimitive.boolean)
        assertEquals("hw.py", obj["active_file"]!!.jsonPrimitive.content)
        assertEquals(1500L, obj["idle_since_ms"]!!.jsonPrimitive.long)
    }

    @Test
    fun `session end payload shape`() {
        val obj = SessionEndPayload(reason = "shutdown").toJsonObject()
        assertEquals("shutdown", obj["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `doc open payload omits content and truncated when null`() {
        val p = DocOpenPayload(path = "hw.py", sha256 = "a".repeat(64), lineCount = 10, content = null, truncated = null)
        val obj = p.toJsonObject()
        assertFalse(obj.containsKey("content"))
        assertFalse(obj.containsKey("truncated"))
        assertEquals(10L, obj["line_count"]!!.jsonPrimitive.long)
    }

    @Test
    fun `doc open payload with content inlines it`() {
        val p = DocOpenPayload(path = "hw.py", sha256 = "a".repeat(64), lineCount = 1, content = "print(1)\n", truncated = null)
        val obj = p.toJsonObject()
        assertEquals("print(1)\n", obj["content"]!!.jsonPrimitive.content)
        assertFalse(obj.containsKey("truncated"))
    }

    @Test
    fun `doc open payload with truncated true omits content`() {
        val p = DocOpenPayload(path = "big.py", sha256 = "b".repeat(64), lineCount = 9000, content = null, truncated = true)
        val obj = p.toJsonObject()
        assertFalse(obj.containsKey("content"))
        assertEquals(true, obj["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `doc change payload with one delta`() {
        val p = DocChangePayload(
            path = "hw.py",
            deltas = listOf(DocChangeDelta(Range(Position(0, 0), Position(0, 5)), "hello")),
            source = "typed",
        )
        val obj = p.toJsonObject()
        assertEquals("hw.py", obj["path"]!!.jsonPrimitive.content)
        assertEquals("typed", obj["source"]!!.jsonPrimitive.content)
        val deltas = obj["deltas"]!!.jsonArray
        assertEquals(1, deltas.size)
        val d0 = deltas[0].jsonObject
        assertEquals("hello", d0["text"]!!.jsonPrimitive.content)
        val range = d0["range"]!!.jsonObject
        assertEquals(0L, range["start"]!!.jsonObject["line"]!!.jsonPrimitive.long)
        assertEquals(0L, range["start"]!!.jsonObject["character"]!!.jsonPrimitive.long)
        assertEquals(0L, range["end"]!!.jsonObject["line"]!!.jsonPrimitive.long)
        assertEquals(5L, range["end"]!!.jsonObject["character"]!!.jsonPrimitive.long)
    }

    @Test
    fun `doc save and close payload shapes`() {
        val save = DocSavePayload(path = "hw.py", sha256 = "c".repeat(64)).toJsonObject()
        assertEquals("hw.py", save["path"]!!.jsonPrimitive.content)
        assertEquals("c".repeat(64), save["sha256"]!!.jsonPrimitive.content)
        val close = DocClosePayload(path = "hw.py").toJsonObject()
        assertEquals("hw.py", close["path"]!!.jsonPrimitive.content)
    }
}
