package dev.provenance.recorder.session

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.FixedClock
import dev.provenance.core.GitEventPayload
import dev.provenance.core.Manifest
import dev.provenance.core.ParseResult
import dev.provenance.core.TerminalOpenPayload
import dev.provenance.core.parseEntries
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.startup.RecoveryDecision
import dev.provenance.recorder.wiring.RecorderGitState
import dev.provenance.recorder.wiring.RecorderTerminalState
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture

/**
 * Lifecycle + seam-wiring test for RecorderSessionManager (the final integration pass). Proves,
 * headlessly: start() opens a live session (session.start on disk), the terminal/git emit seams
 * are opened while active and closed on stop(), coordinator-sourced events (terminal.open,
 * git.event) reach the controller's .slog, a normal doc edit is logged exactly once (no
 * double-emission from the feeder + DocWiring coexisting), and stop() ends the session
 * idempotently.
 */
class RecorderSessionManagerTest : BasePlatformTestCase() {
    private class NoopScheduler : FlushScheduler {
        override fun scheduleAtFixedRate(periodMs: Long, task: Runnable): ScheduledFuture<*> =
            object : ScheduledFuture<Any?> {
                override fun cancel(m: Boolean) = true
                override fun isCancelled() = false
                override fun isDone() = false
                override fun get(): Any? = null
                override fun get(t: Long, u: java.util.concurrent.TimeUnit): Any? = null
                override fun getDelay(u: java.util.concurrent.TimeUnit) = 0L
                override fun compareTo(other: java.util.concurrent.Delayed?) = 0
            }
    }

    private lateinit var wsRoot: Path
    private lateinit var provDir: Path

    override fun setUp() {
        super.setUp()
        wsRoot = Files.createTempDirectory("mgr-ws")
        provDir = wsRoot.resolve(".provenance")
    }

    override fun tearDown() {
        try {
            // BasePlatformTestCase reuses one light project across the class's methods, so an
            // un-stopped session (its global DocWiring listener, its seams) would leak into the
            // next test. Stop it here to keep each method isolated.
            runCatching { project.service<RecorderSessionManager>().stop() }
            wsRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun manifest() = Manifest("hw03", "fa26", "2026-07-14T00:00:00Z", listOf("hw.py"), "ab".repeat(64))

    private fun manager() = project.service<RecorderSessionManager>()

    private fun start(m: RecorderSessionManager): RecorderSessionManager.ActiveSession =
        m.start(
            activated = ActivatedWorkspace(manifest(), provDir, wsRoot),
            recovery = RecoveryDecision.CleanStart,
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "com.aaryanmehta.provenance.recorder",
            clock = FixedClock(0),
            scheduler = NoopScheduler(),
            localFsOf = { true },
            nioPathOf = { vf -> wsRoot.resolve(vf.name) },
        )

    private fun kinds(session: RecorderSessionManager.ActiveSession): List<String> {
        session.controller.flush()
        val text = String(Files.readAllBytes(session.controller.slogPath), Charsets.UTF_8)
        return (parseEntries(text) as ParseResult.Ok).entries.map { it.kind }
    }

    fun testStartOpensSessionAndSetsSeams() {
        val m = manager()
        val session = start(m)
        assertSame(session, m.activeSession)
        assertEquals("session.start", kinds(session).first())

        // Terminal + git privacy gates are open while the session is active.
        assertNotNull(project.service<RecorderTerminalState>().emitTerminalOpen)
        assertNotNull(project.service<RecorderGitState>().emit)
    }

    fun testCoordinatorEventsReachTheSlog() {
        val m = manager()
        val session = start(m)

        project.service<RecorderTerminalState>().emitTerminalOpen!!.invoke(
            TerminalOpenPayload(terminalId = "term-0", shell = "zsh", shellIntegration = true),
        )
        project.service<RecorderGitState>().emit!!.invoke(
            GitEventPayload(operation = "state_change", commitSha = "deadbeef"),
        )

        val ks = kinds(session)
        assertTrue("terminal.open must be recorded", ks.contains("terminal.open"))
        assertTrue("git.event must be recorded", ks.contains("git.event"))
    }

    fun testNoDoubleEmissionOnASingleDocChange() {
        myFixture.configureByText("hw.py", "print(1)\n")
        val m = manager()
        val session = start(m)
        val doc = myFixture.getDocument(myFixture.file)
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, "x") }

        val docChanges = kinds(session).count { it == "doc.change" }
        assertEquals("a single keystroke must log exactly one doc.change", 1, docChanges)
    }

    fun testExtSnapshotIsDeliberatelyNotEmitted() {
        val m = manager()
        val session = start(m)
        // Pins a DELIBERATE product gap, not desired behavior. Emitting ext.snapshot needs
        // plugin enumeration, and every enumeration API is @ApiStatus.Internal as of 262 —
        // which the Marketplace rejects. The lone public accessor, isPluginInstalled(), is
        // `enabled || installed` and cannot report enabled state, so a probe-based snapshot
        // would have to guess a field that ai_extension_active keys on.
        //
        // The cost this pins: a pre-installed AI assistant is invisible to this host
        // (ext.activate only fires on mid-session loads). When JetBrains ships a public
        // enumeration API, delete this test and restore the wiring + its emit test.
        assertFalse(
            "ext.snapshot must stay unwired until a public enumeration API exists",
            kinds(session).contains("ext.snapshot"),
        )
    }

    fun testExtActivateIsWiredToDynamicPluginLoad() {
        val m = manager()
        val session = start(m)
        // Simulate a mid-session plugin load on the application bus the manager subscribed to.
        val descriptor = java.lang.reflect.Proxy.newProxyInstance(
            com.intellij.ide.plugins.IdeaPluginDescriptor::class.java.classLoader,
            arrayOf(com.intellij.ide.plugins.IdeaPluginDescriptor::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPluginId" -> com.intellij.openapi.extensions.PluginId.getId("com.example.copilot")
                "getVersion" -> "1.2.3"
                else -> null
            }
        } as com.intellij.ide.plugins.IdeaPluginDescriptor
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus
            .syncPublisher(com.intellij.ide.plugins.DynamicPluginListener.TOPIC)
            .pluginLoaded(descriptor)

        assertTrue("ext.activate must be recorded on a mid-session plugin load", kinds(session).contains("ext.activate"))
    }

    fun testStopEndsSessionClearsSeamsAndIsIdempotent() {
        val m = manager()
        val session = start(m)
        m.stop()

        assertNull("session cleared after stop", m.activeSession)
        assertEquals("session.end", kinds(session).last())
        assertNull("terminal seam closed on stop", project.service<RecorderTerminalState>().emitTerminalOpen)
        assertNull("git seam closed on stop", project.service<RecorderGitState>().emit)

        m.stop() // idempotent, no throw
    }

    fun testSealWithNoActiveSessionReturnsNoSessions() {
        val m = manager()
        assertTrue(m.sealActiveSession() is dev.provenance.recorder.commands.SealResult.NoSessions)
    }
}
