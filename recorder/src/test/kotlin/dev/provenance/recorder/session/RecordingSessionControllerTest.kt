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
import kotlinx.serialization.json.jsonArray
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
        checkpointInterval: Int = CheckpointCadence.DEFAULT_INTERVAL,
        computeExtensionHash: () -> String = { EXT_HASH },
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
        checkpointInterval = checkpointInterval,
        computeExtensionHash = computeExtensionHash,
        // Unconfined + a real Job so a scheduled checkpoint runs INLINE on the calling thread
        // (its Mutex is uncontended here), making the checkpoint-driven rolling seal
        // deterministic instead of a sleep-and-hope. cancel() still needs the Job.
        checkpointScopeFactory = {
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined,
            )
        },
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

    // -----------------------------------------------------------------------
    // The S3 ROLLING SEAL — three write points, and who may claim finality
    // -----------------------------------------------------------------------
    //
    // A git-submitted assignment has no seal step: the student pushes, the grader clones,
    // nothing runs "Prepare Submission Bundle". So the recorder maintains the seal itself and
    // whatever is committed is always a valid seal of that moment. Format bytes are pinned in
    // core's ConformanceTest; file behaviour in RollingSealTest. What is pinned HERE is the
    // WIRING: that the three rolls happen where they are supposed to, and nowhere else.

    private fun rollingManifest(c: RecordingSessionController): kotlinx.serialization.json.JsonObject? {
        val path = provDir.resolve(dev.provenance.core.rollingManifestFilenames(c.sessionId).json)
        if (!Files.exists(path)) return null
        return kotlinx.serialization.json.Json
            .parseToJsonElement(String(Files.readAllBytes(path), Charsets.UTF_8)).jsonObject
    }

    /**
     * WRITE POINT 1. A session that only ever records `session.start` never reaches a
     * checkpoint, so without this roll its `.slog` would be committed with no seal covering
     * it at all — an unsealed-session defect against a student who simply worked briefly.
     */
    fun testTheSealExistsFromTheSessionsFirstInstant() {
        val c = controller()
        val m = rollingManifest(c)
        assertNotNull("a session must be sealed from its first instant", m)
        m!!
        assertEquals("1.2", m["format_version"]!!.jsonPrimitive.content)
        assertEquals(c.sessionId, m["sessions"]!!.jsonArray.single().jsonObject["session_id"]!!.jsonPrimitive.content)

        // Signed by THIS session's own ephemeral key — the one session.start publishes.
        val pubHex = readEntries(c)[0].data["session_pubkey"]!!.jsonPrimitive.content
        val sigPath = provDir.resolve(dev.provenance.core.rollingManifestFilenames(c.sessionId).sig)
        assertTrue(
            dev.provenance.core.Ed25519.verify(
                dev.provenance.core.Ed25519.hexToBytes(String(Files.readAllBytes(sigPath), Charsets.UTF_8)),
                Files.readAllBytes(provDir.resolve(dev.provenance.core.rollingManifestFilenames(c.sessionId).json)),
                dev.provenance.core.Ed25519.hexToBytes(pubHex),
            ),
        )
    }

    /**
     * WRITE POINT 2: after each checkpoint LANDS IN THE `.meta`, so `meta_sha256` covers the
     * checkpoint just written. Observed through that digest, which is the thing that changes.
     */
    fun testACheckpointRewritesTheSeal() {
        val c = controller(checkpointInterval = 2)
        val atStart = rollingManifest(c)!!["sessions"]!!.jsonArray.single().jsonObject["meta_sha256"]!!.jsonPrimitive.content

        // session.start is entry 0; two more entries trip the cadence at interval 2.
        c.onDocChange(buildDocChangePayload("hw.py", buildDocChangeDelta(0, 8, 0, 8, "x")))
        c.onDocChange(buildDocChangePayload("hw.py", buildDocChangeDelta(0, 8, 0, 8, "y")))

        val afterCheckpoint = rollingManifest(c)!!["sessions"]!!.jsonArray.single().jsonObject
        assertFalse(
            "the checkpoint roll must re-cover the .meta",
            atStart == afterCheckpoint["meta_sha256"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "meta_sha256 must be the .meta as it stands on disk",
            dev.provenance.core.Sha256.hex(Files.readAllBytes(Path.of("${c.slogPath}.meta"))),
            afterCheckpoint["meta_sha256"]!!.jsonPrimitive.content,
        )
        // Still not final: the log is still growing.
        assertNull(rollingManifest(c)!!["final"])
    }

    /**
     * WRITE POINT 3, and the ONLY place `final` may be claimed.
     *
     * Before teardown the seal must NOT be final — the student is still typing, and a reader
     * entitled to whole-file semantics would read their next keystroke as an append past a
     * finished log. After a clean teardown it must be, and its `slog_sha256` must be the
     * WHOLE flushed file, `session.end` included.
     */
    fun testFinalIsClaimedOnlyByACleanTeardown() {
        val c = controller()
        assertNull("a live session's seal must never be final", rollingManifest(c)!!["final"])

        c.endSession("shutdown")

        val m = rollingManifest(c)!!
        assertEquals(true, m["final"]!!.jsonPrimitive.boolean)
        assertEquals(
            "a final seal commits to the WHOLE log",
            dev.provenance.core.Sha256.hex(Files.readAllBytes(c.slogPath)),
            m["sessions"]!!.jsonArray.single().jsonObject["slog_sha256"]!!.jsonPrimitive.content,
        )
        // The claim is inside the signed payload, so it cannot be added or stripped without
        // this session's private key.
        val pubHex = readEntries(c)[0].data["session_pubkey"]!!.jsonPrimitive.content
        val names = dev.provenance.core.rollingManifestFilenames(c.sessionId)
        assertTrue(
            dev.provenance.core.Ed25519.verify(
                dev.provenance.core.Ed25519.hexToBytes(String(Files.readAllBytes(provDir.resolve(names.sig)), Charsets.UTF_8)),
                Files.readAllBytes(provDir.resolve(names.json)),
                dev.provenance.core.Ed25519.hexToBytes(pubHex),
            ),
        )
    }

    /** The rolling seal is additive: it never writes the classic seal's two filenames. */
    fun testTheClassicSealIsNeverWrittenByTheRollingPath() {
        val c = controller()
        c.onDocChange(buildDocChangePayload("hw.py", buildDocChangeDelta(0, 8, 0, 8, "x")))
        c.endSession("shutdown")
        assertFalse(Files.exists(provDir.resolve("manifest.json")))
        assertFalse(Files.exists(provDir.resolve("manifest.sig")))
    }

    /**
     * Recording matters more than sealing. The `extension_hash` walk fails with an Error
     * (IJent's NotImplementedError, or an unresolvable plugin descriptor) — the session must
     * come up, record, chain, and end cleanly, with no seal and no crash.
     */
    fun testASealFailureNeverStopsRecording() {
        val c = controller(computeExtensionHash = { throw NotImplementedError("FILE_WALK") })
        c.onDocChange(buildDocChangePayload("hw.py", buildDocChangeDelta(0, 8, 0, 8, "x")))
        c.endSession("shutdown")

        val entries = readEntries(c)
        assertEquals("session.start", entries[0].kind)
        assertNotNull("the session must still record", entries.firstOrNull { it.kind == "doc.change" })
        assertEquals("session.end", entries.last().kind)
        assertEquals(dev.provenance.core.ChainCheck.Valid, dev.provenance.core.validateChain(entries))
        assertNull("a failed seal writes nothing", rollingManifest(c))
    }

    /**
     * The gate, and its ASYMMETRY. A course that has SIGNED `submission: bundle` has a seal
     * step, so the rolling seal is redundant and suppressed. Everything else — including
     * every 1.x manifest, which cannot say anything about submission — keeps it, because
     * rolling where it is not needed costs two files, while NOT rolling where it is needed
     * costs an integrity finding against a student whose course has not migrated yet.
     */
    fun testABundleSubmissionCourseIsNotRolled() {
        val bundleCourse = manifest().copy(
            formatVersion = "2.0",
            submission = dev.provenance.core.ManifestSubmission.BUNDLE,
        )
        val c = controller(m = bundleCourse)
        assertNull(rollingManifest(c))
        c.endSession("shutdown")
        assertNull(rollingManifest(c))

        // Positive control: the same manifest declaring git submission IS rolled, so the test
        // above cannot pass merely because this fixture never seals.
        wsRoot.toFile().deleteRecursively()
        val gitCourse = bundleCourse.copy(submission = dev.provenance.core.ManifestSubmission.GIT)
        assertNotNull(rollingManifest(controller(m = gitCourse)))
    }


    private companion object {
        /** Stand-in for the installed plugin tree's hash; a unit fixture has no plugin. */
        private const val EXT_HASH = "1111111111111111111111111111111111111111111111111111111111111111"
    }
}
