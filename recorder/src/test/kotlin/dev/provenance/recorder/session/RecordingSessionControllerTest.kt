package dev.provenance.recorder.session

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.FixedClock
import dev.provenance.core.Manifest
import dev.provenance.core.ParseResult
import dev.provenance.core.GENESIS_PREV_HASH
import dev.provenance.core.parseEntries
import dev.provenance.recorder.io.FlushScheduler
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture

class RecordingSessionControllerTest : BasePlatformTestCase() {
    private class NoopScheduler : FlushScheduler {
        override fun scheduleAtFixedRate(periodMs: Long, task: Runnable): ScheduledFuture<*> =
            object : ScheduledFuture<Any?> {
                override fun cancel(m: Boolean) = true
                override fun isCancelled() = false
                override fun isDone() = false
                override fun get(): Any? = null
                override fun get(t: Long, u: java.util.concurrent.TimeUnit): Any? = null
                override fun getDelay(u: java.util.concurrent.TimeUnit) = 0L
                override fun compareTo(o: java.util.concurrent.Delayed?) = 0
            }
    }

    private lateinit var wsRoot: Path
    private lateinit var provDir: Path

    override fun setUp() {
        super.setUp()
        wsRoot = Files.createTempDirectory("ctrl-ws")
        provDir = wsRoot.resolve(".provenance")
    }

    override fun tearDown() {
        try {
            wsRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun manifest() = Manifest("hw03", "fa26", "2026-07-14T00:00:00Z", listOf("hw.py"), "ab".repeat(64))

    private fun controller() = RecordingSessionController(
        activated = ActivatedWorkspace(manifest(), provDir, wsRoot),
        project = project,
        ideVersion = "2026.1.4",
        platform = "darwin-arm64",
        recorderVersion = "0.1.0",
        recorderExtensionId = "com.provenance.recorder",
        parentDisposable = testRootDisposable,
        clock = FixedClock(0),
        scheduler = NoopScheduler(),
        localFsOf = { true },
        nioPathOf = { vf -> wsRoot.resolve(vf.name) },
    )

    private fun readEntries(c: RecordingSessionController): List<dev.provenance.core.HashedEnvelope> {
        c.flush()
        val text = String(Files.readAllBytes(c.slogPath), Charsets.UTF_8)
        return (parseEntries(text) as ParseResult.Ok).entries
    }

    fun testSessionStartIsFirstEntry() {
        val c = controller()
        val entries = readEntries(c)
        assertTrue(entries.isNotEmpty())
        val first = entries[0]
        assertEquals("session.start", first.kind)
        assertEquals(0L, first.seq)
        assertEquals(GENESIS_PREV_HASH, first.prevHash)
        assertEquals("hw03", first.data["assignment"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        // session_pubkey present (64 hex) and manifest_sig bound.
        assertEquals(64, first.data["session_pubkey"]!!.jsonPrimitive.content.length)
        assertEquals("ab".repeat(64), first.data["manifest_sig"]!!.jsonPrimitive.content)
    }

    fun testTypingProducesChainedDocChange() {
        myFixture.configureByText("hw.py", "print(1)\n")
        val c = controller()
        val doc = myFixture.getDocument(myFixture.file)
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "x") }
        val entries = readEntries(c)
        // Chain intact across all emitted entries.
        var prev = GENESIS_PREV_HASH
        for (e in entries) {
            assertEquals(prev, e.prevHash)
            prev = e.hash
        }
        val change = entries.firstOrNull { it.kind == "doc.change" }
        assertNotNull("expected a doc.change entry", change)
        assertEquals("session.start", entries[0].kind)
    }

    fun testFocusTransitionsEmitDiscreteFocusChangeEvents() {
        val c = controller()
        // The light fixture has no real IdeFrame; the listener ignores the frame arg, so a
        // no-op proxy satisfies the non-null parameter without a real window.
        val frame = java.lang.reflect.Proxy.newProxyInstance(
            com.intellij.openapi.wm.IdeFrame::class.java.classLoader,
            arrayOf(com.intellij.openapi.wm.IdeFrame::class.java),
        ) { _, _, _ -> null } as com.intellij.openapi.wm.IdeFrame
        val publisher = com.intellij.openapi.application.ApplicationManager.getApplication()
            .messageBus.syncPublisher(com.intellij.openapi.application.ApplicationActivationListener.TOPIC)
        publisher.applicationDeactivated(frame)
        publisher.applicationActivated(frame)

        val focus = readEntries(c).filter { it.kind == "focus.change" }
        assertEquals("both transitions must emit a discrete focus.change", 2, focus.size)
        assertEquals(false, focus[0].data["gained"]!!.jsonPrimitive.boolean)
        assertEquals(true, focus[1].data["gained"]!!.jsonPrimitive.boolean)
    }

    fun testEndSessionAppendsSessionEndAndWriterUnusable() {
        val c = controller()
        c.endSession("shutdown")
        val entries = readEntries(c)
        assertEquals("session.end", entries.last().kind)
        // Writer is disposed → a second endSession is a no-op (idempotent), no throw.
        c.endSession("again")
    }
}
