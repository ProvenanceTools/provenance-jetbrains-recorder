package dev.provenance.recorder

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.ChainCheck
import dev.provenance.core.CourseCert
import dev.provenance.core.Ed25519
import dev.provenance.core.FixedClock
import dev.provenance.core.Manifest
import dev.provenance.core.ManifestCollaboration
import dev.provenance.core.ManifestScope
import dev.provenance.core.ManifestSubmission
import dev.provenance.core.ParseResult
import dev.provenance.core.Sha256
import dev.provenance.core.parseEntries
import dev.provenance.core.rollingManifestFilenames
import dev.provenance.core.signCourseCert
import dev.provenance.core.signManifest
import dev.provenance.core.validateChain
import dev.provenance.core.verifyManifestChain
import dev.provenance.core.ManifestChain
import dev.provenance.recorder.commands.SealResult
import dev.provenance.recorder.commands.logicalSessionIdOf
import dev.provenance.recorder.commands.selectPackableSessions
import dev.provenance.recorder.commands.selectZipEntries
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.session.ActivatedWorkspace
import dev.provenance.recorder.session.RecorderSessionManager
import dev.provenance.recorder.session.RecorderSessionManager.ActiveSession
import dev.provenance.recorder.session.RecordingSessionController
import dev.provenance.recorder.startup.RecoveryDecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * THE PRODUCER HALF OF THE CROSS-IMPLEMENTATION GATE.
 *
 * This drives the REAL recorder against a REAL on-disk workspace — the real
 * [RecorderSessionManager], session host, writer, VFS/document wiring, rolling-seal writer
 * and seal command — and leaves two genuine submission archives under
 * `build/e2e-cross-impl/`:
 *
 *   * `classic-bundle.zip` — the `sealBundle` shape: `manifest.json` + `manifest.sig`,
 *     written when the course has SIGNED that it submits bundles.
 *   * `rolling-bundle.zip` — the git-submitted shape: no classic manifest at all, one
 *     `manifest-<session>.json` / `.sig` pair per session, exactly as a committed
 *     `.provenance/` directory looks in a cloned repo.
 *
 * `scripts/e2e/run_e2e.sh` then hands both to the REAL monorepo `analysis-core`
 * (`loadBundle` + `runValidation`) and requires all eight PRD §5.4 checks to pass.
 *
 * ## Two sessions, one of them torn down before its first flush
 *
 * Both archives are produced from a `.provenance/` carrying TWO real sessions: a normal one,
 * and one killed between `session.start` and the first flush. That second shape is the one
 * this gate could not express, and not expressing it is what let an unopenable bundle ship.
 * See [startSessionTornDownBeforeItsFirstFlush] for what it leaves on disk and why each of the
 * four properties that hid it is kept intact for the normal session.
 *
 * ## Why a real on-disk workspace, and not `configureByText`
 *
 * The fixture's `configureByText` file lives in the in-memory test VFS, outside the recorded
 * workspace root, so the recorder's own scoping gate drops every event for it — correctly.
 * A bundle produced that way carries no `doc.save` for the reviewed file, which makes check 8
 * (`submitted_code_match`) report **skipped**, and a gate that accepts a skip accepts a
 * producer that simply omitted the evidence. Recording against a real root is what makes the
 * eighth check mean something.
 *
 * ## Why the two halves of the gate are split at this seam
 *
 * The Node side is deliberately NOT invoked from here. Making a Gradle test shell out to a
 * sibling repository would turn `./gradlew test` into a cross-repo build dependency: a
 * checkout without the monorepo, or with an unbuilt one, would go red for a reason that has
 * nothing to do with this repository. `run_e2e.sh` skips loudly instead. What this test owns
 * is the part that must ALWAYS hold — that the recorder produces both shapes and that they
 * are self-consistent on the Kotlin side.
 *
 * ## Why the gate exists at all
 *
 * provjet was the only recorder without an automated one. provnvim has
 * `scripts/e2e/run_e2e.sh`; the monorepo has `tools/recorder-seal-conformance.test.ts` for
 * VS Code. Here the Node-side validation was only *mentioned* in a comment on
 * `EndToEndRecoveryValidationTest` and run by hand. That asymmetry is what left the VS Code
 * recorder's written output unvalidated until recently, and cross-implementation testing is
 * the class of test that has caught the most defects on this project — including one the
 * analyzer's own suite asserted was impossible.
 */
class CrossImplementationBundleTest : BasePlatformTestCase() {

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

    override fun setUp() {
        super.setUp()
        // Canonical (/private) form so the platform's allowed-roots guard accepts it —
        // mirrors AllSignalsLiveGateTest.
        wsRoot = Files.createTempDirectory("provjet-e2e-ws").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, wsRoot.toString())
    }

    override fun tearDown() {
        try {
            runCatching { project.service<RecorderSessionManager>().stop() }
            wsRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /**
     * The root-signed course certificate this gate's manifests chain to.
     *
     * A fixture root key, not the recorder's embedded one: `analysis-core` takes the root
     * public key as a PARAMETER precisely because one deployment's root is not another's, so
     * a gate that needed the production root would be asserting something about key
     * distribution rather than about the format. The public half is written out beside the
     * archives for the Node half to pass in.
     */
    private fun courseCert(): CourseCert {
        val unsigned = CourseCert(
            courseId = COURSE_ID,
            coursePubkey = Ed25519.bytesToHex(Ed25519.publicKeyOf(COURSE_PRIV)),
            validFrom = "2026-06-01",
            validUntil = "2027-06-01",
            rootSig = "",
        )
        return unsigned.copy(rootSig = signCourseCert(unsigned, ROOT_PRIV))
    }

    /**
     * A genuinely signed **Manifest 2.0**, chaining root → course_cert → manifest.
     *
     * 2.0 rather than 1.x for two independent reasons, both learned from this gate going red:
     *
     *  1. `submission` only EXISTS at 2.0. A 1.x manifest carrying it is, to a reader, a
     *     manifest modified after signing — the `manifest_downgrade` detection says so in as
     *     many words, because a 1.x signature covers four fields and those are not among them.
     *  2. `submission: bundle` is the only thing that suppresses the rolling seal, and the
     *     suppression is deliberately gated on a SIGNED statement. So the classic shape is
     *     not reachable at all without a real 2.0 manifest — which is exactly the design.
     *
     * `policy` is an EMPTY object, which resolves to the default everything-captured policy.
     * Empty rather than absent because a 2.0 manifest without a `policy` block does not
     * parse — the block is part of what 2.0 means. This gate is about the eight checks, not
     * about capture gating.
     */
    private fun manifest(submission: ManifestSubmission): Manifest {
        val unsigned = Manifest(
            assignmentId = ASSIGNMENT_ID,
            semester = SEMESTER,
            issuedAt = "2026-09-08T00:00:00Z",
            filesUnderReview = listOf(REVIEWED_FILE),
            sig = "",
            formatVersion = "2.0",
            courseId = COURSE_ID,
            collaboration = ManifestCollaboration.SOLO,
            submission = submission,
            scope = if (submission == ManifestSubmission.GIT) {
                ManifestScope.REPO
            } else {
                ManifestScope.DIRECTORY
            },
            policy = Json.parseToJsonElement("{}").jsonObject,
            courseCert = courseCert(),
        )
        return unsigned.copy(sig = signManifest(unsigned, COURSE_PRIV))
    }

    private fun vfFor(name: String, content: String): VirtualFile {
        Files.writeString(wsRoot.resolve(name), content)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(wsRoot.resolve(name))!!
    }

    /**
     * Record one real session against the real workspace: open the reviewed file, edit it,
     * save it.
     *
     * Saving goes through the platform, so the bytes land on disk under [wsRoot] AND a
     * `doc.save` carrying their hash lands in the log. That pairing is what check 8 compares,
     * and producing it through the real save path rather than by writing the file by hand is
     * the whole point of a cross-implementation gate.
     */
    private fun record(submission: ManifestSubmission): ActiveSession {
        val provDir = wsRoot.resolve(".provenance")
        val vf = vfFor(REVIEWED_FILE, "def solve():\n    pass\n")
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }

        val manager = project.service<RecorderSessionManager>()
        val session = manager.start(
            activated = ActivatedWorkspace(manifest(submission), provDir, wsRoot),
            recovery = RecoveryDecision.CleanStart,
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "com.aaryanmehta.provenance.recorder",
            clock = FixedClock(0, Instant.parse("2026-09-08T00:00:00Z")),
            scheduler = NoopScheduler(),
            vfsDispatch = { it() },
            // The real resolver walks the INSTALLED plugin directory, which no test harness
            // has. Left to its default the rolling seal would degrade to "skipped" and this
            // gate would ship an archive with no seal in it at all.
            computeExtensionHash = { EXTENSION_HASH },
        )

        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(doc.textLength, "solve()\n")
        }
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().saveDocument(doc) }
        }

        session.controller.flush()
        return session
    }

    private fun assertRecordedWell(session: ActiveSession) {
        session.controller.flush()
        val text = String(Files.readAllBytes(session.controller.slogPath), Charsets.UTF_8)
        val parsed = parseEntries(text)
        assertTrue("the produced .slog must parse: $parsed", parsed is ParseResult.Ok)
        val entries = (parsed as ParseResult.Ok).entries
        assertEquals(ChainCheck.Valid, validateChain(entries))
        // Check 8 on the analyzer side needs a recorded save for the reviewed file. Asserting
        // it here means a producer regression is a red Kotlin test, not a mysteriously
        // 'skipped' check in a gate somebody may not have run.
        assertTrue(
            "the reviewed file must have a recorded doc.save",
            entries.any { it.kind == "doc.save" },
        )
    }

    // -----------------------------------------------------------------------
    // The shape this gate could not previously express
    // -----------------------------------------------------------------------

    /**
     * A SECOND, REAL session against the same `.provenance/`, torn down before its first flush.
     *
     * This is the shape that let an unopenable bundle ship. Four independent properties of this
     * gate hid it, and all four are legitimate for the rest of what it tests: a fresh temp
     * workspace per method (only ever ONE session), an explicit `controller.flush()`,
     * `recovery = RecoveryDecision.CleanStart` passed directly (so quarantine never runs), and a
     * clean `endSession`. Nothing here weakens those — the normal session still has every one of
     * them. This just adds the session that does not.
     *
     * Everything about it is real: the real controller, the real `SessionWriter`, the real
     * `MetaWriter`, the real rolling-seal writer. `SessionWriter.open` creates the `.slog`
     * eagerly with CREATE|APPEND and `BufferPolicy` will not flush one small entry, so after
     * `session.start` the log exists and is EMPTY. That is the window rolling seal write point 1
     * fires in, and the window a crash, a power cut or a force-quit lands in.
     *
     * Deliberately not registered with [RecorderSessionManager]: it is a second session in one
     * directory, which is what the seal has to survive, and going through the manager would also
     * make `sealActiveSession` ambiguous. It gets its own Disposable so the test can end it at a
     * chosen moment rather than at teardown.
     */
    private class UnflushedSession(val controller: RecordingSessionController, val disposable: Disposable)

    private fun startSessionTornDownBeforeItsFirstFlush(submission: ManifestSubmission): UnflushedSession {
        val disposable = Disposer.newDisposable(testRootDisposable, "provjet-e2e-unflushed-session")
        val controller = RecordingSessionController(
            activated = ActivatedWorkspace(manifest(submission), wsRoot.resolve(".provenance"), wsRoot),
            project = project,
            ideVersion = "2026.1.4",
            platform = "darwin-arm64",
            recorderVersion = "0.1.0",
            recorderExtensionId = "com.aaryanmehta.provenance.recorder",
            parentDisposable = disposable,
            clock = FixedClock(0, Instant.parse("2026-09-08T00:00:00Z")),
            scheduler = NoopScheduler(),
            computeExtensionHash = { EXTENSION_HASH },
        )
        // NO flush(), NO endSession(). The whole point.
        assertEquals(
            "the fixture must actually be pre-first-flush: a zero-byte .slog on disk",
            0L,
            Files.size(controller.slogPath),
        )
        return UnflushedSession(controller, disposable)
    }

    /**
     * THE LOADER'S OWN INVARIANT, asserted directly rather than as a filename list: every rolling
     * seal in the archive names a session whose log is in the archive too.
     *
     * Read out of the packed bytes — logical ids from each `.slog`'s `session.start`, seal ids
     * from the `manifest-<id>` filenames — because that is exactly the comparison
     * `reconcileRollingSealsWithSessions` makes before failing check 1 for the whole bundle.
     */
    private fun assertEveryRollingSealNamesAPackedSession(zipPath: Path) {
        val contents = zipEntryContents(zipPath)
        val packedIds = contents.entries
            .filter { it.key.endsWith(".slog") }
            .mapNotNull { (_, bytes) ->
                (parseEntries(String(bytes, Charsets.UTF_8)) as? ParseResult.Ok)?.let { logicalSessionIdOf(it.entries) }
            }
            .toSet()
        val sealIds = contents.keys.mapNotNull { name ->
            Regex("^manifest-([0-9a-f-]+)\\.(json|sig)$").matchEntire(name)?.groupValues?.get(1)
        }.toSet()
        for (id in sealIds) {
            assertTrue(
                "manifest-$id.* is in the archive but session $id's log is not — analysis-core " +
                    "reports no_session_log and fails check 1 for the WHOLE bundle. Packed: $packedIds",
                packedIds.contains(id),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Shape 1: the classic sealed bundle
    // -----------------------------------------------------------------------

    fun testProducesAClassicSealedBundleForTheCrossImplementationGate() {
        val manager = project.service<RecorderSessionManager>()
        val session = record(ManifestSubmission.BUNDLE)
        assertRecordedWell(session)

        // A second session in the same directory, killed before its first flush. Its zero-byte
        // `.slog` used to be PACKED, and written into the SIGNED manifest as a null-id entry —
        // which makes the whole archive unopenable (first_event_not_session_start, "none").
        val unflushed = startSessionTornDownBeforeItsFirstFlush(ManifestSubmission.BUNDLE)

        val result = manager.sealActiveSession(
            now = { Instant.parse("2026-09-08T12:00:00Z") },
            computeExtensionHash = { EXTENSION_HASH },
        )
        Disposer.dispose(unflushed.disposable)
        assertTrue("seal failed: $result", result is SealResult.Ok)
        val ok = result as SealResult.Ok
        assertFalse("the sealed chain must be intact", ok.chainBroken)
        assertFalse("the sealed session must be readable", ok.unreadableSession)
        assertTrue("the dropped session must be reported, never silently omitted", ok.emptySession)

        // Copied to a stable name out of the temp workspace (deleted in tearDown); the shell
        // gate looks the archive up by path rather than parsing this test's output.
        val gatePath = freshOutDir("classic").resolve(CLASSIC_BUNDLE)
        Files.copy(ok.bundlePath, gatePath)
        writeRootPubkey()

        val names = zipEntryNames(gatePath)
        assertTrue("a classic bundle carries manifest.json", names.contains("manifest.json"))
        assertTrue("a classic bundle carries manifest.sig", names.contains("manifest.sig"))
        assertTrue("the reviewed file must be in the archive", names.contains(REVIEWED_FILE))
        // The course SIGNED `submission: bundle`, so no rolling seal was written. If one
        // appeared the archive would be the both-shapes bundle, not the classic one this half
        // of the gate exists to cover.
        assertTrue(
            "no rolling manifest may appear in the classic shape: $names",
            names.none { it.startsWith("manifest-") },
        )
        // The unopenable half stays out — log and meta together, since either one alone is an
        // orphan the loader rejects the whole bundle over.
        val ghost = unflushed.controller.slogPath.fileName.toString()
        assertFalse("a zero-byte log must not be packed: $names", names.contains(ghost))
        assertFalse("nor its meta, which would then be orphaned", names.contains("$ghost.meta"))
        // ...and it is not in the SIGNED manifest either. Those two must agree.
        val manifestJson = String(zipEntryContents(gatePath)["manifest.json"]!!, Charsets.UTF_8)
        assertFalse(
            "the dropped session must not be named in the signed manifest",
            manifestJson.contains(unflushed.controller.sessionId),
        )
        assertFalse(
            "nor appear as a null-id session entry",
            manifestJson.contains("\"session_id\":null"),
        )
        // Never deleted, only left out: a git-submitted `.provenance/` is read straight off disk.
        assertTrue("the log must survive on disk", Files.exists(unflushed.controller.slogPath))
        assertEveryRollingSealNamesAPackedSession(gatePath)
        println("PROVJET_E2E_CLASSIC_BUNDLE=" + gatePath.toAbsolutePath())
    }

    // -----------------------------------------------------------------------
    // Shape 2: the rolling-sealed, git-submitted repo
    // -----------------------------------------------------------------------

    fun testProducesARollingSealedRepoArchiveForTheCrossImplementationGate() {
        val session = record(ManifestSubmission.GIT)
        assertRecordedWell(session)

        val provDir = wsRoot.resolve(".provenance")

        // The full defect, in the shape that ships: a second session in the same directory,
        // killed before its first flush. It leaves THREE artifacts behind — a zero-byte `.slog`,
        // its `.slog.meta`, and the rolling seal write point 1 signed over the empty log.
        val unflushed = startSessionTornDownBeforeItsFirstFlush(ManifestSubmission.GIT)
        val ghostSeal = rollingManifestFilenames(unflushed.controller.sessionId)

        // WRITE POINT 1 MUST HAVE FIRED FOR IT ANYWAY. This is the assertion that stands between
        // this defect and the tempting "fix" of making the session-start roll conditional on a
        // non-empty log: a zero-event session MUST still be sealed on disk, or a git-submitted
        // repo — which has no seal step at all — reports `unsealed_session` against a student who
        // did nothing wrong. Removing write point 1 fails the gate on exactly this line.
        assertTrue(
            "a zero-event session must still be sealed on disk by write point 1",
            Files.exists(provDir.resolve(ghostSeal.json)) && Files.exists(provDir.resolve(ghostSeal.sig)),
        )

        // Teardown is what writes the FINAL seal: both writers are closed first, so the
        // digests it signs are whole-file commitments rather than a prefix.
        session.controller.endSession("submit")

        val expected = rollingManifestFilenames(session.controller.sessionId)
        assertTrue(
            "the rolling seal must have been written for this session",
            Files.exists(provDir.resolve(expected.json)),
        )

        val gatePath = freshOutDir("rolling").resolve(ROLLING_BUNDLE)
        zipRepo(provDir, wsRoot, listOf(REVIEWED_FILE), gatePath)
        Disposer.dispose(unflushed.disposable)
        writeRootPubkey()

        val names = zipEntryNames(gatePath)
        // The git shape has NO classic manifest to fall back on, so every seal in it is
        // load-bearing and a stale one fails check 1 for the whole archive.
        val ghostLog = unflushed.controller.slogPath.fileName.toString()
        assertFalse("a zero-byte log must not be packed: $names", names.contains(ghostLog))
        assertFalse("nor its meta, which would then be orphaned", names.contains("$ghostLog.meta"))
        assertFalse("nor the seal that names it: $names", names.contains(ghostSeal.json))
        assertFalse(names.contains(ghostSeal.sig))
        // Never deleted, only left out — the on-disk seal IS the git submission's evidence.
        assertTrue("the seal must survive on disk", Files.exists(provDir.resolve(ghostSeal.json)))
        // No classic seal WHATSOEVER — that is what makes this the git-submitted shape and
        // forces the loader down the rolling path.
        assertFalse("a rolling archive carries no manifest.json", names.contains("manifest.json"))
        assertFalse("a rolling archive carries no manifest.sig", names.contains("manifest.sig"))
        assertTrue(names.contains(expected.json))
        assertTrue(names.contains(expected.sig))
        assertTrue(names.contains(REVIEWED_FILE))
        assertEveryRollingSealNamesAPackedSession(gatePath)
        println("PROVJET_E2E_ROLLING_BUNDLE=" + gatePath.toAbsolutePath())
    }

    // -----------------------------------------------------------------------
    // The trust chain the gate hands to the analyzer
    // -----------------------------------------------------------------------

    /**
     * The manifest these archives embed must chain to the root key written beside them.
     *
     * Without this, a fixture whose signature silently stopped verifying would show up on the
     * Node side as check 2 reporting `skipped` or `fail` — a confusing cross-repo failure for
     * a defect that lives entirely in this file.
     */
    fun testTheGateManifestChainsToTheRootKeyItPublishes() {
        for (submission in listOf(ManifestSubmission.BUNDLE, ManifestSubmission.GIT)) {
            val chain = verifyManifestChain(manifest(submission), ROOT_PUBKEY_HEX)
            assertTrue("manifest chain did not verify: $chain", chain is ManifestChain.Ok)
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun freshOutDir(name: String): Path {
        val dir = Paths.get(OUT_ROOT).resolve(name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        return dir
    }

    /** The root public key the Node half must configure, so check 2 can verify the chain. */
    private fun writeRootPubkey() {
        val dir = Paths.get(OUT_ROOT)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(ROOT_PUBKEY_FILE), ROOT_PUBKEY_HEX)
    }

    /**
     * Zip a recorded repository the way a git submission arrives: every packable `.provenance/`
     * file at the archive root, plus the submitted sources at their workspace-relative paths.
     *
     * Test-only glue, and only the packaging — every byte inside came from the real recorder.
     *
     * WHICH FILES ARE PACKABLE IS NOT DECIDED HERE. This used to carry its own copy of
     * `sealBundle`'s `.tmp` / `.corrupt-` filter, with a comment asserting the two matched.
     * They matched right up until the seal grew an orphan guard, at which point a duplicated
     * filter would have meant the gate stopped judging what ships. So both callers now go
     * through the same [selectPackableSessions] / [selectZipEntries] — parity by construction
     * rather than by comment.
     *
     * The git shape needs the guard at least as badly as the classic one: it has no
     * `manifest.json` to fall back on, so every rolling seal in it is load-bearing and a stale
     * one fails check 1 for the whole archive.
     */
    private fun zipRepo(
        provenanceDir: Path,
        workspaceRoot: Path,
        submissionFiles: List<String>,
        outputPath: Path,
    ) {
        Files.createDirectories(outputPath.parent)
        val names = Files.list(provenanceDir).use { s ->
            s.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.sorted().toList()
        }
        val packable = selectPackableSessions(names) { n ->
            runCatching { Files.size(provenanceDir.resolve(n)) }.getOrDefault(-1L)
        }
        // The LOGICAL ids of the packed logs — `session.start.data.session_id`, never the
        // filename uuid. Same rule and same function the seal keys its guard on.
        val packedSessionIds = packable.slogNames.mapNotNull { n ->
            val text = String(Files.readAllBytes(provenanceDir.resolve(n)), Charsets.UTF_8)
            (parseEntries(text) as? ParseResult.Ok)?.let { logicalSessionIdOf(it.entries) }
        }.toSet()
        val selection = selectZipEntries(names, packable.names, packedSessionIds)

        ZipOutputStream(Files.newOutputStream(outputPath)).use { zip ->
            for (name in selection.names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(Files.readAllBytes(provenanceDir.resolve(name)))
                zip.closeEntry()
            }
            for (rel in submissionFiles) {
                val abs = workspaceRoot.resolve(rel)
                if (!Files.isRegularFile(abs)) continue
                zip.putNextEntry(ZipEntry(rel))
                zip.write(Files.readAllBytes(abs))
                zip.closeEntry()
            }
        }
    }

    private fun zipEntryNames(zipPath: Path): Set<String> = zipEntryContents(zipPath).keys

    private fun zipEntryContents(zipPath: Path): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(Files.newInputStream(zipPath)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                out[e.name] = zin.readBytes()
                zin.closeEntry()
            }
        }
        return out
    }

    private companion object {
        const val ASSIGNMENT_ID = "hw03"
        const val SEMESTER = "fa26"
        const val COURSE_ID = "berkeley-cs61b"
        const val REVIEWED_FILE = "hw.py"

        /** Fixture keys. Fixed seeds, so the gate is reproducible run to run. */
        val ROOT_PRIV: ByteArray = ByteArray(32) { 0x51 }
        val COURSE_PRIV: ByteArray = Ed25519.hexToBytes(
            "e1cd3820d5d4867defcd98e4436a80d92e99db284451b7595e75a66a4e8c7b75",
        )
        val ROOT_PUBKEY_HEX: String = Ed25519.bytesToHex(Ed25519.publicKeyOf(ROOT_PRIV))

        /**
         * A fixed, reproducible stand-in for the installed plugin's tree hash. It does not
         * have to be on the analyzer's allowlist: the allowlist is a HEURISTIC, not one of
         * the eight validation checks, and this gate is about the eight.
         */
        val EXTENSION_HASH: String = Sha256.hex("provjet-cross-impl-gate")

        /** Relative to the `recorder/` module directory, which is the test working dir. */
        const val OUT_ROOT = "build/e2e-cross-impl"
        const val CLASSIC_BUNDLE = "classic-bundle.zip"
        const val ROLLING_BUNDLE = "rolling-bundle.zip"
        const val ROOT_PUBKEY_FILE = "root-pubkey.txt"
    }
}
