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
import dev.provenance.recorder.wiring.paste.RecorderPasteState
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture

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
    private lateinit var wsRoot2: Path
    private lateinit var provDir2: Path

    override fun setUp() {
        super.setUp()
        // .toRealPath() so the roots match the registry's realpath-resolved keys and the
        // toRealPath()-based lookups below — on macOS the OS tmpdir is symlinked (/var ->
        // /private/var), so a raw createTempDirectory() path differs from its real path.
        // Every existing manager gate test (ManagerLifecycleGateTest, ScopingGateTest, ...)
        // realpaths its root for the same reason.
        wsRoot = Files.createTempDirectory("mgr-ws").toRealPath()
        provDir = wsRoot.resolve(".provenance")
        wsRoot2 = Files.createTempDirectory("mgr-ws2").toRealPath()
        provDir2 = wsRoot2.resolve(".provenance")
    }

    override fun tearDown() {
        try {
            runCatching { project.service<RecorderSessionManager>().stop() }
            wsRoot.toFile().deleteRecursively()
            wsRoot2.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun manifest(id: String = "hw03", root: Path = wsRoot) = Manifest(id, "fa26", "2026-07-14T00:00:00Z", listOf("hw.py"), "ab".repeat(64))

    private fun manager() = project.service<RecorderSessionManager>()

    /** Installs the test fs-seams BEFORE the first start() of this manager instance — the
     * project-scoped DocWiring/SelectionWiring are constructed lazily on the first start()
     * and read these overrides at that point. */
    private fun installFsSeams(m: RecorderSessionManager) {
        m.localFsOfOverride = { true }
        m.nioPathOfOverride = { vf -> if (vf.name == "hw.py") wsRoot.resolve(vf.name) else wsRoot2.resolve(vf.name) }
    }

    private fun start(m: RecorderSessionManager, root: Path = wsRoot, provDir: Path = this.provDir, assignmentId: String = "hw03"): RecorderSessionManager.ActiveSession =
        m.start(
            activated = ActivatedWorkspace(manifest(assignmentId, root), provDir, root),
            recovery = RecoveryDecision.CleanStart,
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "com.aaryanmehta.provenance.recorder",
            clock = FixedClock(0),
            scheduler = NoopScheduler(),
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
        assertNotNull(project.service<RecorderTerminalState>().emitTerminalOpen)
        assertNotNull(project.service<RecorderGitState>().emit)
    }

    fun testCoordinatorEventsReachTheSlog() {
        val m = manager()
        val session = start(m)

        project.service<RecorderTerminalState>().emitTerminalOpen!!.invoke(
            wsRoot,
            TerminalOpenPayload(terminalId = "term-0", shell = "zsh", shellIntegration = true),
        )
        project.service<RecorderGitState>().emit!!.invoke(
            wsRoot,
            GitEventPayload(operation = "state_change", commitSha = "deadbeef"),
        )

        val ks = kinds(session)
        assertTrue("terminal.open must be recorded", ks.contains("terminal.open"))
        assertTrue("git.event must be recorded", ks.contains("git.event"))
    }

    fun testTerminalEventWithNoOwningRootIsDropped() {
        val m = manager()
        val session = start(m)
        project.service<RecorderTerminalState>().emitTerminalOpen!!.invoke(
            Files.createTempDirectory("no-owner"),
            TerminalOpenPayload(terminalId = "term-0", shell = "zsh", shellIntegration = true),
        )
        assertFalse("a terminal event with no owning session must be dropped", kinds(session).contains("terminal.open"))
    }

    fun testSecondSessionDoesNotClobberTheFirstsTerminalRouting() {
        val m = manager()
        val sessionA = start(m, root = wsRoot, provDir = provDir, assignmentId = "cats")
        val sessionB = start(m, root = wsRoot2, provDir = provDir2, assignmentId = "hog")

        project.service<RecorderTerminalState>().emitTerminalOpen!!.invoke(
            wsRoot,
            TerminalOpenPayload(terminalId = "term-a", shell = "zsh", shellIntegration = true),
        )
        project.service<RecorderTerminalState>().emitTerminalOpen!!.invoke(
            wsRoot2,
            TerminalOpenPayload(terminalId = "term-b", shell = "zsh", shellIntegration = true),
        )

        val aKinds = kinds(sessionA)
        val bKinds = kinds(sessionB)
        assertEquals("session A must see exactly its own terminal.open", 1, aKinds.count { it == "terminal.open" })
        assertEquals("session B must see exactly its own terminal.open", 1, bKinds.count { it == "terminal.open" })
    }

    fun testNoDoubleEmissionOnASingleDocChange() {
        installFsSeams(manager())
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
        assertFalse(
            "ext.snapshot must stay unwired until a public enumeration API exists",
            kinds(session).contains("ext.snapshot"),
        )
    }

    fun testExtActivateIsWiredToDynamicPluginLoad() {
        val m = manager()
        val session = start(m)
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

    fun testStoppingOneRootLeavesTheOtherActive() {
        val m = manager()
        val sessionA = start(m, root = wsRoot, provDir = provDir, assignmentId = "cats")
        start(m, root = wsRoot2, provDir = provDir2, assignmentId = "hog")

        m.stop(wsRoot.toRealPath())

        assertNull("stopped root's session must be gone", m.activeSessions[wsRoot.toRealPath()])
        assertNotNull("the other root's session must still be active", m.activeSessions[wsRoot2.toRealPath()])
        assertEquals("session.end", kinds(sessionA).last())
    }

    fun testPasteCorrelatorResolvesToTheOwningSessionNotAClobberedSlot() {
        // Sibling of testSecondSessionDoesNotClobberTheFirstsTerminalRouting, for paste signal 2:
        // the project-scoped RecorderPasteState is a path-routed resolver, not a single slot, so a
        // second session's start must NOT clobber the first's correlator, and each pasted-into
        // path must resolve its OWN session's correlator.
        val m = manager()
        val sessionA = start(m, root = wsRoot, provDir = provDir, assignmentId = "cats")
        val sessionB = start(m, root = wsRoot2, provDir = provDir2, assignmentId = "hog")

        val resolve = project.service<RecorderPasteState>().resolveCorrelator
        assertNotNull("resolver must be installed while a session is active", resolve)
        assertSame(
            "a paste under root A must resolve A's own correlator",
            sessionA.controller.pasteCorrelator,
            resolve!!.invoke(wsRoot.resolve("hw.py")),
        )
        assertSame(
            "a paste under root B must resolve B's own correlator (B did not clobber A)",
            sessionB.controller.pasteCorrelator,
            resolve.invoke(wsRoot2.resolve("hw.py")),
        )

        // Stopping B must NOT null out A's still-live correlator resolution (the shared-slot bug).
        m.stop(wsRoot2.toRealPath())
        val afterStop = project.service<RecorderPasteState>().resolveCorrelator
        assertNotNull("A is still active, so the resolver stays installed", afterStop)
        assertSame(
            "stopping B must leave A's correlator resolution intact",
            sessionA.controller.pasteCorrelator,
            afterStop!!.invoke(wsRoot.resolve("hw.py")),
        )
        assertNull(
            "B's path no longer resolves any correlator once B is stopped",
            afterStop.invoke(wsRoot2.resolve("hw.py")),
        )
    }

    fun testLaterStartedSessionCatchesUpItsAlreadyOpenFiles() {
        // doc.open catch-up must fire per session start, not only for the first: the project-scoped
        // DocWiring is constructed once (on session A), so a later session B whose root already has
        // an open file would otherwise never get that file's doc.open baseline.
        installFsSeams(manager())
        val fileA = myFixture.addFileToProject("hw.py", "print(1)\n").virtualFile
        val fileB = myFixture.addFileToProject("hog.py", "print(2)\n").virtualFile

        val m = manager()
        myFixture.openFileInEditor(fileA)
        val sessionA = start(m, root = wsRoot, provDir = provDir, assignmentId = "cats")

        // B's file is already open in the editor BEFORE B starts.
        myFixture.openFileInEditor(fileB)
        val sessionB = start(m, root = wsRoot2, provDir = provDir2, assignmentId = "hog")

        assertTrue(
            "session A's already-open file must be caught up (unchanged first-session behavior)",
            kinds(sessionA).contains("doc.open"),
        )
        assertTrue(
            "session B's already-open file must be caught up on B's own start()",
            kinds(sessionB).contains("doc.open"),
        )
    }

    fun testActiveSessionIsNullWhenMoreThanOneIsActive() {
        val m = manager()
        start(m, root = wsRoot, provDir = provDir, assignmentId = "cats")
        start(m, root = wsRoot2, provDir = provDir2, assignmentId = "hog")
        assertNull("activeSession is the single-session convenience; ambiguous with 2 active", m.activeSession)
        assertEquals(2, m.activeSessions.size)
    }

    fun testSealWithNoActiveSessionReturnsNoSessions() {
        val m = manager()
        assertTrue(m.sealActiveSession() is dev.provenance.recorder.commands.SealResult.NoSessions)
    }

    fun testNestedRootRoutesToTheInnerSessionNotTheOuter() {
        // A genuinely NESTED pair (not siblings): wsRoot is the outer assignment root, and a
        // subdirectory of it is itself a second, inner assignment root. This is the case
        // locked decision #5 ("nearest-enclosing ownership") exists for: a naive
        // "startsWith(root)" filter would match BOTH roots for anything under the inner one,
        // and the real algorithm (RecorderSessionManager.sinkFor/rootOwning's
        // maxByOrNull { it.key.nameCount }) must pick the longer (inner) prefix.
        val innerRoot = wsRoot.resolve("inner")
        Files.createDirectories(innerRoot)
        val outer = start(m = manager(), root = wsRoot, provDir = provDir, assignmentId = "outer")
        val inner = start(m = manager(), root = innerRoot, provDir = innerRoot.resolve(".provenance"), assignmentId = "inner")

        val pathUnderInner = innerRoot.resolve("hw.py")
        val pathUnderOuterOnly = wsRoot.resolve("other.py")

        val m = manager()
        assertSame(
            "a path under the inner root must route to the inner session, not the outer one",
            inner.controller,
            m.sinkFor(pathUnderInner),
        )
        assertSame(
            "a path under only the outer root must route to the outer session",
            outer.controller,
            m.sinkFor(pathUnderOuterOnly),
        )
        assertEquals(innerRoot.toRealPath(), m.rootOwning(pathUnderInner))
        assertEquals(wsRoot.toRealPath(), m.rootOwning(pathUnderOuterOnly))
    }
}
