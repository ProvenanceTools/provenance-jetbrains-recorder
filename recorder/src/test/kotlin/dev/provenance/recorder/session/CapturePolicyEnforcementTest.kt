package dev.provenance.recorder.session

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.ChainCheck
import dev.provenance.core.FixedClock
import dev.provenance.core.GENESIS_PREV_HASH
import dev.provenance.core.HashedEnvelope
import dev.provenance.core.Manifest
import dev.provenance.core.ParseResult
import dev.provenance.core.Position
import dev.provenance.core.Range
import dev.provenance.core.SelectionChangePayload
import dev.provenance.core.DocOpenPayload
import dev.provenance.core.FLOOR_EVENT_KINDS
import dev.provenance.core.PastePayload
import dev.provenance.core.POLICY_GATED_EVENT_KINDS
import dev.provenance.core.parseEntries
import dev.provenance.core.toJsonObject
import dev.provenance.core.validateChain
import dev.provenance.recorder.io.FlushScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture

/**
 * Capture-policy enforcement at the [RecordingSessionController.record] chokepoint
 * (program spec §4).
 *
 * The load-bearing test here is [testSuppressedEventConsumesNoSequenceNumber]. Everything
 * else is behaviour; that one is a safety property.
 */
class CapturePolicyEnforcementTest : BasePlatformTestCase() {

    /** Records every scheduling period requested, so the heartbeat cadence is observable. */
    private class CapturingScheduler : FlushScheduler {
        val periods = mutableListOf<Long>()

        override fun scheduleAtFixedRate(periodMs: Long, task: Runnable): ScheduledFuture<*> {
            periods += periodMs
            return object : ScheduledFuture<Any?> {
                override fun cancel(m: Boolean) = true
                override fun isCancelled() = false
                override fun isDone() = false
                override fun get(): Any? = null
                override fun get(t: Long, u: java.util.concurrent.TimeUnit): Any? = null
                override fun getDelay(u: java.util.concurrent.TimeUnit) = 0L
                override fun compareTo(o: java.util.concurrent.Delayed?) = 0
            }
        }
    }

    private lateinit var wsRoot: Path
    private lateinit var provDir: Path

    override fun setUp() {
        super.setUp()
        wsRoot = Files.createTempDirectory("policy-ws")
        provDir = wsRoot.resolve(".provenance")
    }

    override fun tearDown() {
        try {
            wsRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    /** A 2.0 manifest carrying [captureJson] as its `policy.capture` block. */
    private fun manifestWithCapture(captureJson: String): Manifest = Manifest(
        assignmentId = "hw03",
        semester = "fa26",
        issuedAt = "2026-07-14T00:00:00Z",
        filesUnderReview = listOf("hw.py"),
        sig = "ab".repeat(64),
        formatVersion = "2.0",
        policy = buildJsonObject {
            put("capture", Json.parseToJsonElement(captureJson).jsonObject)
        },
    )

    /** A 1.x manifest: no policy block at all. */
    private fun legacyManifest(): Manifest =
        Manifest("hw03", "fa26", "2026-07-14T00:00:00Z", listOf("hw.py"), "ab".repeat(64))

    private fun controller(
        manifest: Manifest,
        scheduler: FlushScheduler = CapturingScheduler(),
    ) = RecordingSessionController(
        activated = ActivatedWorkspace(manifest, provDir, wsRoot),
        project = project,
        ideVersion = "2026.1.4",
        platform = "darwin-arm64",
        recorderVersion = "0.1.0",
        recorderExtensionId = "com.aaryanmehta.provenance.recorder",
        parentDisposable = testRootDisposable,
        clock = FixedClock(0),
        scheduler = scheduler,
    )

    private fun readEntries(c: RecordingSessionController): List<HashedEnvelope> {
        c.flush()
        val text = String(Files.readAllBytes(c.slogPath), Charsets.UTF_8)
        return (parseEntries(text) as ParseResult.Ok).entries
    }

    private fun selection() =
        SelectionChangePayload("hw.py", Range(Position(0, 0), Position(0, 3)), wasSelection = true)

    private fun paste(text: String) = PastePayload(
        path = "hw.py",
        range = Range(Position(0, 0), Position(0, text.length.toLong())),
        length = text.toByteArray(Charsets.UTF_8).size.toLong(),
        sha256 = "cd".repeat(32),
        content = text,
    )

    private fun externalChange(content: String): JsonObject = buildJsonObject {
        put("path", "hw.py")
        put("old_hash", "00".repeat(32))
        put("new_hash", "11".repeat(32))
        put("diff_size", 12)
        put("new_content_size", content.toByteArray(Charsets.UTF_8).size)
        put("new_content", content)
    }

    // -----------------------------------------------------------------------
    // The safety property
    // -----------------------------------------------------------------------

    /**
     * **A suppressed event must consume no sequence number.**
     *
     * Suppression happens before [SessionHost.emit], which is what chains an entry and
     * assigns its `seq`. Drop an event *after* that point and the log carries a hole that
     * validation check 3 reads as a DELETED ENTRY — so a course quietly turning off
     * selection capture would manufacture a tamper finding against every student in the
     * class. This test is the guard against that failure mode: it asserts the seqs are
     * densely contiguous from 0 with no gap where the suppressed events were, and that the
     * hash chain validates end to end.
     */
    fun testSuppressedEventConsumesNoSequenceNumber() {
        val c = controller(manifestWithCapture("""{"selection_change":false,"doc_open_close":false}"""))

        // Interleave suppressed and captured events so a hole would land mid-chain.
        c.onSelectionChange(selection())
        c.onDocOpen(DocOpenPayload("hw.py", "ef".repeat(32), 3, null, null))
        c.onDocSave(dev.provenance.core.DocSavePayload("hw.py", "ef".repeat(32)))
        c.onSelectionChange(selection())
        c.onDocClose(dev.provenance.core.DocClosePayload("hw.py"))
        c.onDocSave(dev.provenance.core.DocSavePayload("hw.py", "ef".repeat(32)))

        val entries = readEntries(c)

        // The disabled kinds are absent...
        assertTrue(entries.none { it.kind == "selection.change" })
        assertTrue(entries.none { it.kind == "doc.open" })
        assertTrue(entries.none { it.kind == "doc.close" })
        // ...the floor kinds around them survived...
        assertEquals("session.start", entries[0].kind)
        assertEquals(2, entries.count { it.kind == "doc.save" })

        // ...and crucially, NO HOLE: seq is dense from 0, so nothing consumed a number on
        // its way to being dropped.
        assertEquals(entries.indices.map { it.toLong() }, entries.map { it.seq })

        // The chain itself validates — the same check the analyzer runs.
        assertTrue(
            "suppression must not break the chain",
            validateChain(entries) is ChainCheck.Valid,
        )
        var prev = GENESIS_PREV_HASH
        for (e in entries) {
            assertEquals(prev, e.prevHash)
            prev = e.hash
        }
    }

    // -----------------------------------------------------------------------
    // The floor
    // -----------------------------------------------------------------------

    /**
     * Every floor kind survives an all-off policy. The floor is enforced by the SCHEMA —
     * a floor kind has no `policy.capture` key, so "off" is not expressible — and this
     * asserts the controller does not reimplement it as a check that could drift.
     */
    fun testEveryFloorKindSurvivesAnAllOffPolicy() {
        val allOff = """{"selection_change":false,"focus_change":false,"terminal":false,""" +
            """"doc_open_close":false,"inline_content":false}"""
        val c = controller(manifestWithCapture(allOff))

        // session.start is already emitted by construction; append the rest of the floor
        // through the same chokepoint every wiring module uses.
        val appendable = FLOOR_EVENT_KINDS.filter { it != "session.start" && it != "session.end" }
        for (kind in appendable) {
            c.append(kind, buildJsonObject { put("probe", kind) })
        }

        val entries = readEntries(c)
        val kinds = entries.map { it.kind }.toSet()
        assertTrue("session.start must survive", "session.start" in kinds)
        for (kind in appendable) {
            assertTrue("floor kind $kind must survive an all-off policy", kind in kinds)
        }
        // And the gated kinds really are off, so the test above is not vacuous.
        for (kind in POLICY_GATED_EVENT_KINDS.keys) {
            c.append(kind, buildJsonObject { put("probe", kind) })
        }
        val after = readEntries(c).map { it.kind }.toSet()
        for (kind in POLICY_GATED_EVENT_KINDS.keys) {
            assertFalse("gated kind $kind must be suppressed", kind in after)
        }
    }

    /** terminal.* and git.event reach the same gate, via the manager's append() seam. */
    fun testTerminalIsGatedAndGitEventIsFloor() {
        val c = controller(manifestWithCapture("""{"terminal":false}"""))
        c.append("terminal.open", buildJsonObject { put("terminal_id", "t1") })
        c.append("terminal.command", buildJsonObject { put("command", "ls") })
        c.append("git.event", buildJsonObject { put("type", "commit") })

        val kinds = readEntries(c).map { it.kind }.toSet()
        assertFalse("terminal.open must be gated", "terminal.open" in kinds)
        assertFalse("terminal.command must be gated", "terminal.command" in kinds)
        assertTrue("git.event is on the floor and has no policy key", "git.event" in kinds)
    }

    // -----------------------------------------------------------------------
    // inline_content: a field gate, not an event gate
    // -----------------------------------------------------------------------

    fun testInlineContentOffWithholdsPasteTextButKeepsLengthAndSha() {
        val c = controller(manifestWithCapture("""{"inline_content":false}"""))
        c.onPaste(paste("secret student text"))

        val pasteEntry = readEntries(c).firstOrNull { it.kind == "paste" }
        assertNotNull("paste is a floor kind and must still fire", pasteEntry)
        val data = pasteEntry!!.data
        assertFalse("content must be withheld", "content" in data)
        assertFalse("content_head must be withheld", "content_head" in data)
        assertFalse("content_tail must be withheld", "content_tail" in data)
        // Everything the paste heuristics reason over survives.
        assertEquals(19L, data["length"]!!.jsonPrimitive.long)
        assertEquals("cd".repeat(32), data["sha256"]!!.jsonPrimitive.content)
        assertEquals("hw.py", data["path"]!!.jsonPrimitive.content)
        assertNotNull(data["range"])
    }

    fun testInlineContentOffWithholdsExternalChangeTextButKeepsSizeAndHashes() {
        val c = controller(manifestWithCapture("""{"inline_content":false}"""))
        c.append("fs.external_change", externalChange("pasted from elsewhere"))

        val entry = readEntries(c).firstOrNull { it.kind == "fs.external_change" }
        assertNotNull("fs.external_change is a floor kind and must still fire", entry)
        val data = entry!!.data
        assertFalse("new_content must be withheld", "new_content" in data)
        assertFalse("new_content_head must be withheld", "new_content_head" in data)
        assertFalse("new_content_tail must be withheld", "new_content_tail" in data)
        assertEquals(21L, data["new_content_size"]!!.jsonPrimitive.long)
        assertEquals("00".repeat(32), data["old_hash"]!!.jsonPrimitive.content)
        assertEquals("11".repeat(32), data["new_hash"]!!.jsonPrimitive.content)
        assertEquals(12L, data["diff_size"]!!.jsonPrimitive.long)
    }

    fun testInlineContentOnKeepsTheText() {
        val c = controller(manifestWithCapture("""{"inline_content":true}"""))
        c.onPaste(paste("visible text"))
        c.append("fs.external_change", externalChange("visible change"))

        val entries = readEntries(c)
        assertEquals(
            "visible text",
            entries.first { it.kind == "paste" }.data["content"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "visible change",
            entries.first { it.kind == "fs.external_change" }.data["new_content"]!!.jsonPrimitive.content,
        )
    }

    // -----------------------------------------------------------------------
    // heartbeat_interval_ms
    // -----------------------------------------------------------------------

    /**
     * The heartbeat is not the only thing on this scheduler — ClockSkewWatcher ticks at
     * 1000ms and PasteAnomalyTicker at 5000ms, both fixed. A bare `5000 in periods` would
     * therefore pass whether or not the heartbeat honoured the policy. These tests compare
     * period COUNTS against a legacy-manifest baseline instead, so each assertion turns on
     * the heartbeat's own contribution and nothing else.
     */
    private fun periodsFor(manifest: Manifest): List<Long> {
        val scheduler = CapturingScheduler()
        controller(manifest, scheduler)
        return scheduler.periods.toList()
    }

    fun testHeartbeatIntervalComesFromThePolicy() {
        val baseline = periodsFor(legacyManifest())
        val withPolicy = periodsFor(manifestWithCapture("""{"heartbeat_interval_ms":7000}"""))

        // 7000 belongs to no other ticker, so it appears exactly when the policy is honoured.
        assertEquals("baseline must not schedule 7000", 0, baseline.count { it == 7000L })
        assertEquals("the policy cadence must reach the scheduler", 1, withPolicy.count { it == 7000L })
        // ...and the 30s default it replaced is gone.
        assertEquals(1, baseline.count { it == 30_000L })
        assertEquals(0, withPolicy.count { it == 30_000L })
    }

    fun testHeartbeatIntervalIsClampedBeforeItReachesTheScheduler() {
        val baseline = periodsFor(legacyManifest())
        val clamped = periodsFor(manifestWithCapture("""{"heartbeat_interval_ms":1000}"""))

        // Deltas against the baseline, so the assertion turns on the heartbeat's own
        // contribution however many other fixed-cadence tickers share this scheduler.
        assertEquals(
            "1000 must be clamped up to the 5000 floor",
            baseline.count { it == 5000L } + 1,
            clamped.count { it == 5000L },
        )
        assertEquals(
            "the unclamped value must never be scheduled",
            baseline.count { it == 1000L },
            clamped.count { it == 1000L },
        )
        assertEquals(0, clamped.count { it == 30_000L })
    }

    // -----------------------------------------------------------------------
    // 1.x: no policy block at all
    // -----------------------------------------------------------------------

    /**
     * A 1.x manifest has no policy, which must resolve to the everything-on default — i.e.
     * exactly the pre-2.0 capture set. A course that has not migrated loses no signal.
     */
    fun testLegacyManifestCapturesEverything() {
        val scheduler = CapturingScheduler()
        val c = controller(legacyManifest(), scheduler)
        c.onSelectionChange(selection())
        c.onDocOpen(DocOpenPayload("hw.py", "ef".repeat(32), 3, null, null))
        c.onPaste(paste("kept"))
        c.append("terminal.open", buildJsonObject { put("terminal_id", "t1") })

        val entries = readEntries(c)
        val kinds = entries.map { it.kind }.toSet()
        assertTrue("selection.change" in kinds)
        assertTrue("doc.open" in kinds)
        assertTrue("terminal.open" in kinds)
        assertEquals(
            "kept",
            entries.first { it.kind == "paste" }.data["content"]!!.jsonPrimitive.content,
        )
        assertEquals("the 1.x cadence stays 30s", 1, scheduler.periods.count { it == 30_000L })
    }
}
