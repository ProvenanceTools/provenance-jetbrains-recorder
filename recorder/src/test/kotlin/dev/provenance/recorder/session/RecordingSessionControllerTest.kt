package dev.provenance.recorder.session

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.FixedClock
import dev.provenance.core.Manifest
import dev.provenance.core.ParseResult
import dev.provenance.core.GENESIS_PREV_HASH
import dev.provenance.core.parseEntries
import dev.provenance.recorder.events.buildDocChangeDelta
import dev.provenance.recorder.events.buildDocChangePayload
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

    private fun controller(
        m: Manifest = manifest(),
        secrets: dev.provenance.recorder.identity.SecretStore =
            dev.provenance.recorder.identity.FakeSecretStore(),
    ) = RecordingSessionController(
        activated = ActivatedWorkspace(m, provDir, wsRoot),
        project = project,
        ideVersion = "2026.1.4",
        platform = "darwin-arm64",
        recorderVersion = "0.1.0",
        recorderExtensionId = "com.aaryanmehta.provenance.recorder",
        parentDisposable = testRootDisposable,
        clock = FixedClock(0),
        scheduler = NoopScheduler(),
        secrets = secrets,
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

    // A doc.change delivered to the controller's RecordableSessionSink surface is appended and
    // hash-chained after session.start. Real typing -> doc.change now flows through the project-
    // scoped DocWiring the manager owns (that end-to-end keystroke path is covered by
    // RecorderSessionManagerTest.testNoDoubleEmissionOnASingleDocChange); the controller no
    // longer constructs DocWiring itself, so this unit test drives the sink method directly.
    fun testDocChangeThroughSinkIsChained() {
        val c = controller()
        c.onDocChange(buildDocChangePayload("hw.py", buildDocChangeDelta(0, 8, 0, 8, "x")))
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

    // -----------------------------------------------------------------------
    // Identity rule 1: an identity failure NEVER blocks recording (program spec §5a)
    // -----------------------------------------------------------------------

    /**
     * The end-to-end proof of rule 1, at the level that actually matters: not "does
     * buildSessionIdentity return Skipped" but "does a real session still RECORD".
     *
     * The credential vault throws on every access — a locked macOS keychain, a headless Linux
     * box with no libsecret. The student is enrolled, the manifest is a 2.0 one with a
     * course_cert, so an identity is genuinely expected here; it just cannot be assembled.
     * For an integrity tool, silently not recording is a worse failure than recording under an
     * incomplete credential, so the session must come up, chain intact, with `identity` simply
     * ABSENT — not null, not an empty object.
     */
    fun testAThrowingSecretStoreStillProducesARecordingSession() {
        val throwing = dev.provenance.recorder.identity.FakeSecretStore().apply {
            failure = IllegalStateException("keyring locked")
        }
        val c = controller(
            m = dev.provenance.recorder.identity.EnrollmentFixtures.manifest(),
            secrets = throwing,
        )

        // The session records: a doc.change after session.start is appended and chained.
        c.onDocChange(buildDocChangePayload("hw.py", buildDocChangeDelta(0, 8, 0, 8, "x")))
        val entries = readEntries(c)

        assertEquals("session.start", entries[0].kind)
        assertNotNull("the session must still record real events", entries.firstOrNull { it.kind == "doc.change" })

        // Chain intact end to end.
        var prev = GENESIS_PREV_HASH
        for (e in entries) {
            assertEquals(prev, e.prevHash)
            prev = e.hash
        }
        assertEquals(dev.provenance.core.ChainCheck.Valid, dev.provenance.core.validateChain(entries))

        // `identity` is OMITTED, never present-and-empty.
        assertFalse(
            "an unbuildable identity must be absent, not null and not an empty object",
            "identity" in entries[0].data,
        )
    }

    /**
     * The same guarantee for the ordinary pre-enrollment state: an empty vault on a 2.0
     * assignment records exactly as before, with no identity.
     */
    fun testANotEnrolledStudentRecordsWithoutAnIdentity() {
        val c = controller(m = dev.provenance.recorder.identity.EnrollmentFixtures.manifest())
        val entries = readEntries(c)
        assertEquals("session.start", entries[0].kind)
        assertFalse("identity" in entries[0].data)
        assertEquals(dev.provenance.core.ChainCheck.Valid, dev.provenance.core.validateChain(entries))
    }

    /**
     * And the positive control, so the two tests above cannot pass merely because this
     * controller never emits an identity at all: a genuinely enrolled student on the same 2.0
     * manifest DOES get a chain-verified identity block written into session.start.
     */
    fun testAnEnrolledStudentEmitsIdentityIntoSessionStart() {
        val c = controller(
            m = dev.provenance.recorder.identity.EnrollmentFixtures.manifest(),
            secrets = dev.provenance.recorder.identity.EnrollmentFixtures.enrolledStore(),
        )
        val entries = readEntries(c)
        val identity = entries[0].data["identity"]
        assertNotNull("an enrolled student must get an identity block", identity)
        assertEquals(
            setOf("enrollment", "enrollment_cert", "session_pubkey_sig"),
            identity!!.jsonObject.keys,
        )
        // It binds THIS session's pubkey.
        val sessionPubkey = entries[0].data["session_pubkey"]!!.jsonPrimitive.content
        assertTrue(
            dev.provenance.core.verifySessionPubkeySig(
                dev.provenance.core.SessionPubkeyBinding(
                    courseId = dev.provenance.recorder.identity.EnrollmentFixtures.COURSE_ID,
                    studentRef = dev.provenance.recorder.identity.EnrollmentFixtures.STUDENT_REF,
                    sessionPubkey = sessionPubkey,
                ),
                identity.jsonObject["session_pubkey_sig"]!!.jsonPrimitive.content,
                dev.provenance.recorder.identity.EnrollmentFixtures.studentPubkeyHex(),
            ),
        )
    }
}
