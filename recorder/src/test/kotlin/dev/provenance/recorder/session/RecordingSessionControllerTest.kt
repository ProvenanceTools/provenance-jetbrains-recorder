package dev.provenance.recorder.session

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.FixedClock
import dev.provenance.core.Manifest
import dev.provenance.core.ParseResult
import dev.provenance.core.GENESIS_PREV_HASH
import dev.provenance.core.parseEntries
import dev.provenance.core.toJsonObject
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

    /**
     * A scheduler that keeps every task it is handed instead of running it, so a test can fire a
     * specific periodic task by its period. The rotation idle poll (§3.3) is the only task here
     * whose period a test chooses, so filtering by period identifies it unambiguously — and
     * running captured tasks blindly would also tick the heartbeat, the clock-skew watcher and the
     * paste ticker, which have nothing to do with rotation.
     */
    private class CapturingScheduler : FlushScheduler {
        val tasks = mutableListOf<Pair<Long, Runnable>>()
        val cancelled = mutableSetOf<Long>()

        override fun scheduleAtFixedRate(periodMs: Long, task: Runnable): ScheduledFuture<*> {
            tasks.add(periodMs to task)
            return object : ScheduledFuture<Any?> {
                override fun cancel(m: Boolean): Boolean {
                    cancelled.add(periodMs)
                    return true
                }

                override fun isCancelled() = periodMs in cancelled
                override fun isDone() = periodMs in cancelled
                override fun get(): Any? = null
                override fun get(t: Long, u: java.util.concurrent.TimeUnit): Any? = null
                override fun getDelay(u: java.util.concurrent.TimeUnit) = 0L
                override fun compareTo(o: java.util.concurrent.Delayed?) = 0
            }
        }

        /** Run every task registered with [periodMs]; there must be exactly one. */
        fun tick(periodMs: Long) {
            val matching = tasks.filter { it.first == periodMs }
            if (matching.size != 1) throw AssertionError("expected exactly one task at ${periodMs}ms, got ${matching.size}")
            matching.single().second.run()
        }

        fun hasTaskAt(periodMs: Long) = tasks.any { it.first == periodMs }
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
        maxSlogBytes: Long = RecordingSessionController.ROTATE_AT_BYTES,
        rotateIdleQuietMs: Long = RecordingSessionController.ROTATE_IDLE_QUIET_MS,
        rotateHardCeilingBytes: Long = RecordingSessionController.ROTATE_HARD_CEILING_BYTES,
        onRotationNeeded: ((String) -> Unit)? = null,
        clock: FixedClock = FixedClock(0),
        scheduler: FlushScheduler = NoopScheduler(),
    ) = RecordingSessionController(
        activated = ActivatedWorkspace(m, provDir, wsRoot),
        project = project,
        ideVersion = "2026.1.4",
        platform = "darwin-arm64",
        recorderVersion = "0.1.0",
        recorderExtensionId = "com.aaryanmehta.provenance.recorder",
        parentDisposable = testRootDisposable,
        clock = clock,
        scheduler = scheduler,
        secrets = secrets,
        checkpointInterval = checkpointInterval,
        computeExtensionHash = computeExtensionHash,
        maxSlogBytes = maxSlogBytes,
        rotateIdleQuietMs = rotateIdleQuietMs,
        rotateHardCeilingBytes = rotateHardCeilingBytes,
        onRotationNeeded = onRotationNeeded,
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

    // REGRESSION: doc.save must hash the bytes handed to the sink (what reached disk), and the
    // save-time external-change check must land BEFORE it. See
    // RecordableSessionSink.onSaveObserved and the analyzer's save-path signature in
    // reconstruct-file.ts, which matches an fs.external_change on the doc.save that FOLLOWS it.
    fun testSaveObservedRecordsTheOnDiskHash() {
        val c = controller()
        val onDisk = "print(1)\n"
        c.onSaveObserved("hw.py", onDisk)
        val save = readEntries(c).single { it.kind == "doc.save" }
        assertEquals("hw.py", save.data["path"]!!.jsonPrimitive.content)
        assertEquals(dev.provenance.core.Sha256.hex(onDisk), save.data["sha256"]!!.jsonPrimitive.content)
    }

    fun testSaveObservedRunsTheExternalChangeCheckBeforeRecordingDocSave() {
        val c = controller()
        val onDisk = "print(1)\n"
        c.setSaveObserver { rel, content ->
            // Stand-in for ExternalChangeCoordinator.checkSavedContent: it emits through the
            // controller's own append seam, exactly as the real one does.
            c.append(
                "fs.external_change",
                dev.provenance.core.FsExternalChangePayload(
                    path = rel,
                    oldHash = "00".repeat(32),
                    newHash = dev.provenance.core.Sha256.hex(content),
                    diffSize = 1,
                    operation = "modify",
                ).toJsonObject(),
            )
        }
        c.onSaveObserved("hw.py", onDisk)

        val kinds = readEntries(c).map { it.kind }
        val external = kinds.indexOf("fs.external_change")
        val save = kinds.indexOf("doc.save")
        assertTrue("expected both events", external >= 0 && save >= 0)
        assertTrue("fs.external_change must precede its doc.save", external < save)
        // And the pair must describe the SAME bytes — the signature matches on hash equality.
        val entries = readEntries(c)
        assertEquals(
            entries[external].data["new_hash"]!!.jsonPrimitive.content,
            entries[save].data["sha256"]!!.jsonPrimitive.content,
        )
    }

    // A check that throws must never cost the session its doc.save: a missing save is a hole in
    // the on-disk history the analyzer reads, a missing annotation is not.
    fun testSaveObservedStillRecordsDocSaveWhenTheCheckThrows() {
        val c = controller()
        c.setSaveObserver { _, _ -> throw IllegalStateException("boom") }
        c.onSaveObserved("hw.py", "print(1)\n")
        assertEquals(1, readEntries(c).count { it.kind == "doc.save" })
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


    // -----------------------------------------------------------------------
    // SIZE ROTATION (recorder PRD §4.6) — the controller's half: notice that the
    // `.slog` has passed the threshold, and ask to be rotated. Performing the swap is
    // RecorderSessionManager.rotate's job (covered in RecorderSessionManagerTest).
    // -----------------------------------------------------------------------

    private fun typing(text: String) =
        buildDocChangePayload("hw.py", buildDocChangeDelta(0, 8, 0, 8, text))

    private fun gitEvent() =
        dev.provenance.core.GitEventPayload(operation = "state_change", commitSha = "deadbeef").toJsonObject()

    /**
     * The size is read ONLY when the checkpoint cadence fires — never per appended entry.
     * `doc.change` handlers must stay under 1 ms p99 (PRD §4.7), and `session.start` alone
     * already exceeds the 512-byte threshold used here, so a per-entry check would have
     * requested rotation on the very first entry.
     *
     * The entry that trips the cadence here is deliberately NOT a `doc.change`: after the idle
     * gate (§3.3) a `doc.change` resets the quiet window it is measured against, so a burst can
     * never rotate on its own final keystroke. The clock is advanced first to represent the
     * student having stopped typing.
     */
    fun testRequestsRotationOnceTheLogPassesTheThresholdAtCheckpointCadence() {
        val rotations = mutableListOf<String>()
        val clock = FixedClock(0)
        val c = controller(
            checkpointInterval = 10,
            maxSlogBytes = 512L,
            rotateIdleQuietMs = 100L,
            onRotationNeeded = { endedId -> rotations.add(endedId) },
            clock = clock,
        )
        // session.start is the cadence's first entry, so nine more trip it at interval 10.
        repeat(8) { c.onDocChange(typing("x")) }
        assertTrue("the size must not even be read below the cadence", rotations.isEmpty())
        c.flush()
        assertTrue("and the log is already over the threshold", Files.size(c.slogPath) > 512L)

        clock.advance(100L)
        c.append("git.event", gitEvent())
        assertEquals("the cadence firing over the threshold must request rotation", listOf(c.sessionId), rotations)
        c.endSession("rotate")
    }

    // -----------------------------------------------------------------------
    // THE IDLE GATE (design §3.3). End-then-start DROPS any event that lands inside the
    // predecessor's teardown window, and the analyzer compares session A's side of a seam as a
    // RECONSTRUCTION FROM A'S EVENTS against session B's first doc.open, which is a LIVE BUFFER
    // READ, by exact string equality — so one lost keystroke is reported, at confidence 0.85, as
    // the student editing the file outside the recorder. Rotating only while nothing is being
    // typed is what makes the seam empty by construction instead of by hope.
    // -----------------------------------------------------------------------

    /**
     * Crossing the threshold mid-burst ARMS the rotation and does not perform it; it fires only
     * once a full quiet window has passed with no `doc.change`.
     */
    fun testCrossingTheThresholdMidBurstArmsTheRotationButDoesNotRotate() {
        val rotations = mutableListOf<String>()
        val clock = FixedClock(0)
        val sched = CapturingScheduler()
        val c = controller(
            checkpointInterval = 10,
            maxSlogBytes = 512L,
            rotateIdleQuietMs = 7_331L,
            onRotationNeeded = { rotations.add(it) },
            clock = clock,
            scheduler = sched,
        )

        // A burst that crosses the threshold: the cadence fires, the log is over 512 bytes, and
        // the student is still typing.
        repeat(20) { c.onDocChange(typing("x")) }
        c.flush()
        assertTrue("the log must be over the threshold", Files.size(c.slogPath) > 512L)
        assertTrue("a mid-burst crossing must NOT rotate", rotations.isEmpty())
        assertTrue("but it must arm the quiet-window poll", sched.hasTaskAt(7_331L))

        // The poll firing while the burst is still recent changes nothing.
        clock.advance(7_330L)
        sched.tick(7_331L)
        assertTrue("one millisecond short of the quiet window is still a burst", rotations.isEmpty())

        // A full quiet window later, it fires — once.
        clock.advance(1L)
        sched.tick(7_331L)
        assertEquals("the rotation must fire once the session goes quiet", listOf(c.sessionId), rotations)
        sched.tick(7_331L)
        assertEquals("and only once", 1, rotations.size)
        assertTrue("the poll must be cancelled once it has fired", 7_331L in sched.cancelled)
        c.endSession("rotate")
    }

    /**
     * THE HARD CEILING, and the one rotation path that can still lose an edit.
     *
     * A student who never pauses would otherwise defer the rotation forever and push the `.slog`
     * past GitHub's refusal limit — an unpushable submission, which is the worse outcome. So past
     * [RecordingSessionController.ROTATE_HARD_CEILING_BYTES] the recorder rotates mid-burst.
     */
    fun testATypingSessionPastTheHardCeilingRotatesWithoutEverGoingQuiet() {
        val rotations = mutableListOf<String>()
        val clock = FixedClock(0) // never advanced: the student never stops typing
        val sched = CapturingScheduler()
        val c = controller(
            checkpointInterval = 5,
            maxSlogBytes = 512L,
            rotateHardCeilingBytes = 20_000L,
            rotateIdleQuietMs = 7_331L,
            onRotationNeeded = { rotations.add(it) },
            clock = clock,
            scheduler = sched,
        )

        // Over the threshold but under the ceiling: armed, not rotated.
        repeat(10) { c.onDocChange(typing("x")) }
        c.flush()
        assertTrue("over the threshold, under the ceiling, still typing: no rotation", rotations.isEmpty())
        assertTrue("the fixture must actually be in the deferred band", Files.size(c.slogPath) in 513L..19_999L)

        // Keep typing until the ceiling is passed.
        while (run { c.flush(); Files.size(c.slogPath) } < 20_000L) {
            repeat(5) { c.onDocChange(typing("x")) }
        }
        assertEquals("past the hard ceiling a session rotates mid-burst", listOf(c.sessionId), rotations)
        c.endSession("rotate")
    }

    /**
     * A session that has recorded no `doc.change` at all counts as quiet — there is no in-flight
     * burst to lose an edit out of — so it rotates at the first cadence over the threshold without
     * waiting for a window it could never observe.
     */
    fun testASessionThatHasNeverRecordedADocChangeIsAlreadyQuiet() {
        val rotations = mutableListOf<String>()
        val c = controller(
            checkpointInterval = 3,
            maxSlogBytes = 512L,
            rotateIdleQuietMs = 7_331L,
            onRotationNeeded = { rotations.add(it) },
        )
        repeat(2) { c.append("git.event", gitEvent()) }
        assertEquals(listOf(c.sessionId), rotations)
        c.endSession("rotate")
    }

    /** A session under the threshold is never rotated, however many checkpoints it reaches. */
    fun testASmallLogIsNeverRotated() {
        val rotations = mutableListOf<String>()
        val c = controller(
            checkpointInterval = 2,
            maxSlogBytes = RecordingSessionController.ROTATE_AT_BYTES,
            onRotationNeeded = { rotations.add(it) },
        )
        repeat(20) { c.onDocChange(typing("x")) }
        assertTrue(rotations.isEmpty())
    }

    /**
     * Requested at most once per session. The swap is asynchronous — the manager ends this
     * session on another thread — so entries can keep arriving and tripping the cadence in
     * between, and a second request would start a second successor for the same root.
     */
    fun testRotationIsRequestedOnlyOncePerSession() {
        val rotations = mutableListOf<String>()
        val clock = FixedClock(0)
        val c = controller(
            checkpointInterval = 2,
            maxSlogBytes = 512L,
            rotateIdleQuietMs = 100L,
            onRotationNeeded = { rotations.add(it) },
            clock = clock,
        )
        repeat(20) { c.onDocChange(typing("x")) }
        assertTrue("the burst itself must not rotate", rotations.isEmpty())
        // The student stops, then more non-typing entries keep tripping the cadence.
        clock.advance(100L)
        repeat(10) { c.append("git.event", gitEvent()) }
        assertEquals("exactly one rotation request, not one per checkpoint", 1, rotations.size)
        c.endSession("rotate")
    }

    /** The threshold is 40 MiB: under GitHub's 100 MB push refusal and its 50 MB warning. */
    fun testTheRotationThresholdIs40MiB() {
        assertEquals(40L * 1024 * 1024, RecordingSessionController.ROTATE_AT_BYTES)
    }

    /** The idle gate and the hard ceiling, as the design fixes them (§3.3). */
    fun testTheIdleGateAndHardCeilingConstants() {
        assertEquals(2000L, RecordingSessionController.ROTATE_IDLE_QUIET_MS)
        assertEquals(48L * 1024 * 1024, RecordingSessionController.ROTATE_HARD_CEILING_BYTES)
        assertTrue(
            "the ceiling must sit above the threshold, or the idle gate could never defer anything",
            RecordingSessionController.ROTATE_HARD_CEILING_BYTES > RecordingSessionController.ROTATE_AT_BYTES,
        )
    }

    /** `rotate` goes through the ordinary teardown path, so the log still ends cleanly. */
    fun testEndSessionWithTheRotateReasonIsAnOrdinaryCleanEnd() {
        val c = controller()
        c.onDocChange(typing("x"))
        c.endSession("rotate")
        val entries = readEntries(c)
        assertEquals("session.end", entries.last().kind)
        assertEquals("rotate", entries.last().data["reason"]!!.jsonPrimitive.content)
        assertEquals(dev.provenance.core.ChainCheck.Valid, dev.provenance.core.validateChain(entries))
        // And a rotated session's seal is final, exactly like any other clean end.
        assertEquals(true, rollingManifest(c)!!["final"]!!.jsonPrimitive.boolean)
    }

    /**
     * A doc.change racing an OFF-THREAD `endSession` must never append past the end of the log.
     *
     * Rotation is what makes this reachable: every other teardown is either on the EDT (the
     * Disposer hook) or a rare manual action, whereas rotation ends the session from a pooled
     * thread automatically — and the only way a log reaches 40 MiB is a student typing fast, so
     * the two interleave maximally. The guard in `record` and the latch in `endSession` therefore
     * share one lock. Without it an emitter can pass `if (ended) return`, block, and then append
     * to a writer that has since been disposed: `writer.append` throws, `routeSessionEntry` feeds
     * it to the DiskFullHandler, and the student gets a FALSE disk-full balloon on a healthy disk.
     * Landing on the other side of the window is no better — the sealed log would carry an entry
     * AFTER `session.end`.
     *
     * HONEST LIMIT ON THIS TEST: it asserts the invariant, and it is the only test that runs an
     * emitter concurrently with an off-thread teardown at all — but it does NOT fail when the lock
     * is removed. I checked: with the lock stripped and a `Thread.yield()` inserted between the
     * guard and the emit, eight iterations x three runs all still passed, because the gap between
     * `ended = true` and `writer.dispose()` is a long stretch of teardown work that an emitter
     * almost always clears. The lock's value is that it makes the invariant a guarantee rather
     * than a probability; this test's value is that it pins the invariant and would catch a
     * coarser regression. Forcing the bad interleaving deterministically would need a test-only
     * hook between the guard and the emit, which is production surface added for a test.
     */
    fun testAnEmitRacingAnOffThreadEndSessionNeverAppendsPastTheEnd() {
        repeat(8) {
            val c = controller()
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val emitting = java.util.concurrent.CountDownLatch(1)
            val thrown = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            val typist = Thread {
                while (!stop.get()) {
                    try {
                        c.onDocChange(typing("x"))
                    } catch (t: Throwable) {
                        thrown.add(t)
                    }
                    emitting.countDown()
                }
            }
            typist.start()
            try {
                // No sleep: wait until the other thread is demonstrably emitting, so the end
                // genuinely overlaps it, then end from THIS thread as a rotation would.
                assertTrue(emitting.await(10, java.util.concurrent.TimeUnit.SECONDS))
                c.endSession("rotate")
            } finally {
                stop.set(true)
                typist.join(10_000)
            }

            assertTrue("an emit must never throw out of the sink: $thrown", thrown.isEmpty())
            val entries = readEntries(c)
            assertEquals("session.end must be the LAST entry", "session.end", entries.last().kind)
            assertFalse(
                "a healthy disk must not be reported degraded by the teardown race",
                entries.any { it.kind == "recorder.degraded" },
            )
            assertEquals(dev.provenance.core.ChainCheck.Valid, dev.provenance.core.validateChain(entries))
            provDir.toFile().deleteRecursively()
        }
    }

    /**
     * Concurrent `endSession` calls must produce EXACTLY ONE `session.end`.
     *
     * Newly reachable because of rotation: the swap ends the session from a pooled thread while the
     * Disposer hook can end the same one on the EDT, so two teardowns can genuinely arrive at once.
     * Two `session.end` entries in one log is a format artifact in evidence — an entry after the
     * log's own end — and it would also run the teardown twice.
     *
     * A [java.util.concurrent.CyclicBarrier] releases every thread into `endSession` at the same
     * instant, which is as close to deterministic as this gets without a production seam.
     *
     * HONEST LIMIT, measured rather than assumed: this test does NOT fail when the re-check inside
     * the `emitLock` block is removed. I instrumented it — with the re-check deleted, the log still
     * contains exactly one `session.end`. The reason is that `endSession`'s FIRST statement is its
     * own `if (ended) return`, and `peerWatcher.drain()` sits between that read and the latch while
     * being monitor-serialized internally: the first thread through latches before any other thread
     * has re-read `ended`, so the rest bail at the outer check. Widening the window via the
     * `peerFiles` seam (parking inside `drain`) deadlocks, because `drain` takes its lock outside
     * the parked call.
     *
     * The re-check is still correct and is kept: the JVM puts no bound on how long a thread can be
     * descheduled between passing the outer read and reaching the latch, so the window is real even
     * though it is not reachable on demand. Demonstrating it would need a test-only hook between
     * the two, and removing the outer check to make the latch the only gate would trade a genuinely
     * more defensive structure (no redundant drain on a second call) for testability. What this
     * test does buy is the only coverage of eight concurrent teardowns of one session, and it pins
     * the invariant against a coarser regression.
     */
    fun testConcurrentEndSessionCallsEmitExactlyOneSessionEnd() {
        val threadCount = 8
        val c = controller()
        c.onDocChange(typing("x"))

        val barrier = java.util.concurrent.CyclicBarrier(threadCount)
        val thrown = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val threads = (0 until threadCount).map { i ->
            Thread {
                try {
                    barrier.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    c.endSession(if (i == 0) "rotate" else "dispose")
                } catch (t: Throwable) {
                    thrown.add(t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(30_000) }

        assertTrue("no endSession call may throw: $thrown", thrown.isEmpty())
        val entries = readEntries(c)
        assertEquals(
            "exactly one session.end, however many threads end the session at once",
            1,
            entries.count { it.kind == "session.end" },
        )
        assertEquals("and it must be the last entry", "session.end", entries.last().kind)
        assertEquals(dev.provenance.core.ChainCheck.Valid, dev.provenance.core.validateChain(entries))
    }

    private companion object {
        /** Stand-in for the installed plugin tree's hash; a unit fixture has no plugin. */
        private const val EXT_HASH = "1111111111111111111111111111111111111111111111111111111111111111"
    }
}
