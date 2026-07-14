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

    @Test
    fun `fs external_change payload emits snake_case keys and omits null optionals`() {
        val p = FsExternalChangePayload(
            path = "hw.py",
            oldHash = "a".repeat(64),
            newHash = "b".repeat(64),
            diffSize = 7,
        )
        val obj = p.toJsonObject()
        assertEquals("hw.py", obj["path"]!!.jsonPrimitive.content)
        assertEquals("a".repeat(64), obj["old_hash"]!!.jsonPrimitive.content)
        assertEquals("b".repeat(64), obj["new_hash"]!!.jsonPrimitive.content)
        assertEquals(7L, obj["diff_size"]!!.jsonPrimitive.long)
        // Null optionals must be absent, not JSON null.
        assertFalse(obj.containsKey("explanation"))
        assertFalse(obj.containsKey("operation"))
        assertFalse(obj.containsKey("new_content_size"))
        assertFalse(obj.containsKey("new_content"))
        assertFalse(obj.containsKey("new_content_head"))
        assertFalse(obj.containsKey("new_content_tail"))
    }

    @Test
    fun `terminal open payload has exactly the pinned fields`() {
        val obj = TerminalOpenPayload("term-0", "/bin/zsh", true).toJsonObject()
        assertEquals("term-0", obj["terminal_id"]!!.jsonPrimitive.content)
        assertEquals("/bin/zsh", obj["shell"]!!.jsonPrimitive.content)
        assertEquals(true, obj["shell_integration"]!!.jsonPrimitive.boolean)
        assertEquals(setOf("terminal_id", "shell", "shell_integration"), obj.keys)
    }

    @Test
    fun `terminal command payload omits exit_code when null`() {
        val obj = TerminalCommandPayload("term-0", "ls -la", null).toJsonObject()
        assertEquals("term-0", obj["terminal_id"]!!.jsonPrimitive.content)
        assertEquals("ls -la", obj["command"]!!.jsonPrimitive.content)
        assertFalse(obj.containsKey("exit_code"))
        assertEquals(setOf("terminal_id", "command"), obj.keys)
    }

    @Test
    fun `terminal command payload includes exit_code when present`() {
        val obj = TerminalCommandPayload("term-1", "pytest", 1).toJsonObject()
        assertEquals(1L, obj["exit_code"]!!.jsonPrimitive.long)
        assertEquals(setOf("terminal_id", "command", "exit_code"), obj.keys)
    }

    @Test
    fun `git event omits commit_sha when null`() {
        val obj = GitEventPayload("state_change", null).toJsonObject()
        assertEquals("state_change", obj["operation"]!!.jsonPrimitive.content)
        assertFalse(obj.containsKey("commit_sha"))
        assertEquals(setOf("operation"), obj.keys)
    }

    @Test
    fun `git event includes commit_sha when present`() {
        val obj = GitEventPayload("state_change", "deadbeef").toJsonObject()
        assertEquals("deadbeef", obj["commit_sha"]!!.jsonPrimitive.content)
        assertEquals(setOf("operation", "commit_sha"), obj.keys)
    }

    @Test
    fun `ext snapshot builds extensions array with id, version, enabled per entry`() {
        val obj = ExtSnapshotPayload(
            listOf(
                ExtSnapshotEntry("Git4Idea", "261.1", true),
                ExtSnapshotEntry("org.jetbrains.plugins.terminal", "261.1", false),
            ),
        ).toJsonObject()
        val extensions = obj["extensions"]!!.jsonArray
        assertEquals(2, extensions.size)
        val first = extensions[0].jsonObject
        assertEquals("Git4Idea", first["id"]!!.jsonPrimitive.content)
        assertEquals("261.1", first["version"]!!.jsonPrimitive.content)
        assertEquals(true, first["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(false, extensions[1].jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(setOf("extensions"), obj.keys)
    }

    @Test
    fun `ext snapshot with empty list yields empty extensions array`() {
        val obj = ExtSnapshotPayload(emptyList()).toJsonObject()
        assertEquals(0, obj["extensions"]!!.jsonArray.size)
    }

    @Test
    fun `fs external_change payload emits all optional fields when present`() {
        val p = FsExternalChangePayload(
            path = "big.txt",
            oldHash = "a".repeat(64),
            newHash = "b".repeat(64),
            diffSize = 100,
            operation = "modify",
            newContentSize = 5000,
            newContentHead = "head",
            newContentTail = "tail",
        )
        val obj = p.toJsonObject()
        assertEquals("modify", obj["operation"]!!.jsonPrimitive.content)
        assertEquals(5000L, obj["new_content_size"]!!.jsonPrimitive.long)
        assertEquals("head", obj["new_content_head"]!!.jsonPrimitive.content)
        assertEquals("tail", obj["new_content_tail"]!!.jsonPrimitive.content)
        assertFalse(obj.containsKey("new_content"))
    }
}
