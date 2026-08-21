package dev.provenance.recorder.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.concurrency.AppExecutorUtil
import dev.provenance.core.CapturePolicy
import dev.provenance.core.Clock
import dev.provenance.core.FocusChangePayload
import dev.provenance.core.Manifest
import dev.provenance.core.ManifestSubmission
import dev.provenance.core.RecorderDegradedPayload
import dev.provenance.core.SessionEndPayload
import dev.provenance.core.SessionResumedPayload
import dev.provenance.core.SystemClock
import dev.provenance.core.encryptSessionPrivkey
import dev.provenance.core.isEventKindCaptured
import dev.provenance.core.resolveCapturePolicy
import dev.provenance.core.generateSessionKeypair
import dev.provenance.core.toJsonObject
import dev.provenance.recorder.activation.ROOT_PUBLIC_KEY_HEX
import dev.provenance.recorder.commands.computeInstalledExtensionHash
import dev.provenance.recorder.failure.DegradedModeNotifier
import dev.provenance.recorder.failure.DiskFullHandler
import dev.provenance.recorder.identity.CourseKeyCache
import dev.provenance.recorder.identity.IdentityOutcome
import dev.provenance.recorder.identity.PasswordSafeSecretStore
import dev.provenance.recorder.identity.SecretStore
import dev.provenance.recorder.identity.buildSessionIdentity
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.io.MetaWriter
import dev.provenance.recorder.io.RollingSealResult
import dev.provenance.recorder.io.SessionWriter
import dev.provenance.recorder.io.writeRollingSeal
import dev.provenance.recorder.paste.PasteCorrelator
import dev.provenance.recorder.startup.RecoveryDecision
import dev.provenance.recorder.wiring.ActiveFileTracker
import dev.provenance.recorder.wiring.NioPeerFiles
import dev.provenance.recorder.wiring.PeerFiles
import dev.provenance.recorder.wiring.PeerWatcher
import dev.provenance.recorder.wiring.ProvenanceDirVfsListener
import dev.provenance.recorder.wiring.ClockSkewWatcher
import dev.provenance.recorder.wiring.Heartbeat
import dev.provenance.recorder.wiring.RecordableSessionSink
import dev.provenance.recorder.wiring.paste.PasteAnomalyTicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What activation (Plan 3) hands off once a workspace is verified. Plan 3's
 * RecorderActivationActivity/RecorderState hold the [manifest]; the provenance dir
 * and workspace root are resolved by the caller (see [fromRecorderState]).
 */
data class ActivatedWorkspace(
    val manifest: Manifest,
    val provenanceDir: Path,
    val workspaceRoot: Path,
)

/**
 * Composes the recording session: session keypair → session.start → SessionWriter +
 * MetaWriter + SessionHost + Heartbeat, all tied to [parentDisposable]. Mirrors extension.ts's
 * activateImpl Steps 3c–11.
 *
 * As of the nested-manifest rewrite this controller is one *sink* among possibly several: the
 * project-scoped [dev.provenance.recorder.wiring.DocWiring]/[dev.provenance.recorder.wiring.SelectionWiring]
 * routers are constructed ONCE by [RecorderSessionManager] (not per-session here) and dispatch
 * each doc/selection event to the nearest-enclosing session via [RecordableSessionSink]. This
 * class therefore no longer constructs DocWiring/SelectionWiring itself; it exposes the six
 * `on*` sink methods those routers call, plus [workspaceRoot] (for relative-path resolution)
 * and [pasteCorrelator] (signals 1 & 3 of paste detection, resolved per keystroke by DocWiring).
 */
class RecordingSessionController(
    activated: ActivatedWorkspace,
    project: Project,
    ideVersion: String,
    platform: String,
    recorderVersion: String,
    recorderExtensionId: String,
    private val parentDisposable: Disposable,
    clock: Clock = SystemClock(),
    scheduler: FlushScheduler = DEFAULT_SCHEDULER,
    /**
     * Explicit heartbeat cadence, overriding the course's capture policy. Null (the
     * default) means "use the policy", whose own default is [Heartbeat.DEFAULT_INTERVAL_MS]
     * and which is already clamped to [5000, 120000] by `resolveCapturePolicy`.
     */
    heartbeatIntervalMs: Long? = null,
    /**
     * Plan 8: the startup chain-recovery decision for this workspace's .provenance dir,
     * already computed by the caller (recoverPreviousSession, via NioRecoveryDeps). This
     * controller never calls recoverPreviousSession itself — wiring recovery into
     * activation/project-open is a later integration pass; this constructor param is the
     * injectable seam that pass will fill in. Defaults to CleanStart so every existing
     * call site (no prior session to recover) is unaffected.
     */
    /**
     * Where the student's identity material lives. Injectable so unit tests can supply a
     * map without a running IDE; production uses the PasswordSafe credential vault.
     */
    secrets: SecretStore = PasswordSafeSecretStore(),
    /**
     * The application-scoped derived-key cache. Resolved defensively: under a test harness or
     * a partially-initialised container the service may be unavailable, and a missing cache
     * must degrade to direct derivation rather than fail session start — the cache is a
     * performance detail, never a correctness one.
     */
    keyCache: CourseKeyCache? = runCatching {
        ApplicationManager.getApplication()?.getService(CourseKeyCache::class.java)
    }.getOrNull(),
    recovery: RecoveryDecision = RecoveryDecision.CleanStart,
    checkpointInterval: Int = CheckpointCadence.DEFAULT_INTERVAL,
    /** Plan 8: disk-full user disclosure. Defaults to the real balloon notifier. */
    degradedNotify: (String) -> Unit = { DegradedModeNotifier(project).notifyDegraded() },
    /**
     * Plan 8: scope for the ordered async checkpoint sign+persist chain. Defaults to a
     * manually-cancelled scope (Global Constraints fallback) rather than a constructor-
     * injected platform @Service scope, to avoid a new plugin.xml service registration for
     * this plan; cancelled from endSession()/dispose alongside the rest of session teardown.
     */
    checkpointScopeFactory: () -> CoroutineScope = { CoroutineScope(SupervisorJob() + Dispatchers.IO) },
    /**
     * S3 rolling seal: the recorder's own `extension_hash`, resolved lazily and memoized for
     * the session's lifetime. [computeInstalledExtensionHash] walks the whole installed plugin
     * tree, so recomputing it per checkpoint would be the pathological version of this feature.
     * Injectable for the same reason [RecorderSessionManager.extensionHashOverride] exists: a
     * unit test has no installed plugin descriptor to resolve. A failure here is caught and
     * degrades to a skipped seal — never a failed session.
     */
    computeExtensionHash: () -> String = { computeInstalledExtensionHash(RECORDER_PLUGIN_ID) },
    /**
     * PEER WITNESSING: the read-only view of `.provenance/` the watcher is handed.
     *
     * Injectable so a test can drive the observation state machine without a real directory,
     * and — the reason it is a separate type rather than a lambda — so the ABSENCE of a write
     * operation is visible in the signature. Defaults to [NioPeerFiles] over this session's own
     * provenance directory.
     */
    peerFiles: PeerFiles? = null,
) : RecordableSessionSink {
    /** [RecordableSessionSink]: the root the routers relativize recorded paths against. */
    override val workspaceRoot: Path = activated.workspaceRoot

    /**
     * [RecordableSessionSink]: this session's own paste correlator (paste signals 1 & 3). Owned
     * per session, no longer published into a shared project-scoped slot: the project-scoped
     * DocWiring (signals 1 & 3) and the EditorPaste action wrapper (signal 2, via
     * RecorderPasteState's path-routed resolver) both reach it through this getter after the
     * router resolves THIS session as the owner of the edited path. The privacy gate is now the
     * router: once the session is removed from the registry on stop, it is never resolved again,
     * so no correlator is handed out; a late in-flight event is still dropped by [record]'s
     * `ended` guard.
     */
    override val pasteCorrelator: PasteCorrelator

    val sessionId: String = UUID.randomUUID().toString()
    val slogPath: Path

    /**
     * The active session's ed25519 private key. Held in memory for the lifetime of the
     * session so the seal command (Task 11) can sign the bundle manifest with the key
     * whose public half is recorded in session.start.session_pubkey (the analyzer's
     * check 1 verifies the manifest signature against exactly that pubkey). Mirrors how
     * extension.ts hands the active session's sessionPrivkey to sealBundle.
     */
    val sessionPrivkey: ByteArray

    /**
     * The course's capture policy, resolved from the VERIFIED manifest. `activated.manifest`
     * reached this constructor only via `evaluateManifestText`, so at 2.0 the policy inside it
     * is course-signed and root-chained; at 1.x there is no policy block and this resolves to
     * the everything-on default, i.e. exactly the pre-2.0 capture set.
     */
    private val policy: CapturePolicy

    private val writer: SessionWriter
    private val meta: MetaWriter
    private val host: SessionHost
    private val heartbeat: Heartbeat
    private val pasteTicker: PasteAnomalyTicker
    private val diskFullHandler: DiskFullHandler
    private val checkpointCadence: CheckpointCadence
    private val checkpointScheduler: CheckpointScheduler
    private val checkpointScope: CoroutineScope

    /**
     * S3 rolling seal. Null when the course has SIGNED a statement that it submits bundles;
     * see the gate in `init` for why that asymmetry is the safe one.
     */
    private val rollingSeal: RollingSealMaintainer?

    /**
     * PEER WITNESSING (program spec §7 mechanism 2). Drained on the checkpoint cadence and
     * once at teardown; see the two call sites below. Never null — witnessing is a floor
     * capability with no `policy.capture` key, so there is nothing to gate it on.
     */
    private val peerWatcher: PeerWatcher
    private var ended = false

    init {
        // Step 0: resolve the course's capture policy BEFORE anything can emit. Total by
        // construction — an absent, malformed, or out-of-range block resolves to a
        // well-defined value, so this cannot fail and cannot leave the gate undefined.
        policy = resolveCapturePolicy(activated.manifest.policy)

        Files.createDirectories(activated.provenanceDir)
        // Step 1: session keypair.
        val keypair = generateSessionKeypair()
        sessionPrivkey = keypair.privateKey

        // Step 2: session.start payload. prev_session_id is set ONLY for a dangling prior
        // session (crash: no trailing session.end) — never for a cleanly-completed one, and
        // never for a corrupt one (corruption is surfaced via recorder.recovered_from_corruption
        // below, not chain linkage). Mirrors chain-recovery.ts's documented rule.
        val prevSessionId = prevSessionIdFor(recovery)

        // Step 2a: the enrollment identity, if the student has one for this course. Assembled
        // and chain-verified before it is written; a failure at ANY point here yields no
        // identity and changes nothing else about the session. Never a reason not to record.
        val identityOutcome = buildSessionIdentity(
            manifest = activated.manifest,
            sessionPubkeyHex = keypair.publicKeyHex,
            sessionStartedAt = clock.wall(),
            secrets = secrets,
            keyCache = keyCache,
            // The 2.1 trust anchor. The stored institution_cert is root-verified against
            // this before it is used as an anchor — whoever supplies a cert supplies its
            // institution_pubkey too, so an unverified anchor proves nothing.
            rootPubkeyHex = ROOT_PUBLIC_KEY_HEX,
        )
        if (identityOutcome is IdentityOutcome.Skipped) {
            LOG.debug("provenance: session.start identity omitted: ${identityOutcome.reason}")
        }

        val ctx = buildRecorderContext(
            manifest = activated.manifest,
            prevSessionId = prevSessionId,
            sessionId = sessionId,
            sessionPubkeyHex = keypair.publicKeyHex,
            ideVersion = ideVersion,
            platform = platform,
            recorderVersion = recorderVersion,
            recorderExtensionId = recorderExtensionId,
            identity = (identityOutcome as? IdentityOutcome.Emitted)?.identity,
        )

        // Step 3: disk-full handler. Constructed before the writer so handleWriteError can be
        // passed as the writer's onError hook (mirrors extension.ts's ordering). onDegraded
        // emits recorder.degraded through the session host once it exists (forward reference,
        // populated after Step 6) — that re-entrant emit is accepted into the ring by
        // enqueue() because the kind is critical; handleWriteError is idempotent, so the
        // resulting second call from that re-entry is a no-op.
        var sessionHostEmit: ((String, JsonObject) -> Unit)? = null
        diskFullHandler = DiskFullHandler(
            onDegraded = { reason ->
                sessionHostEmit?.invoke("recorder.degraded", RecorderDegradedPayload(reason).toJsonObject())
            },
            notify = degradedNotify,
        )

        // Step 4: open the .slog writer, routing write failures to the disk-full handler.
        slogPath = activated.provenanceDir.resolve("session-$sessionId.slog")
        writer = SessionWriter.open(slogPath, clock, scheduler, onError = { e -> diskFullHandler.handleWriteError(e) })

        // Step 5: encrypt the session privkey under manifest.sig; create the meta writer.
        val enc = encryptSessionPrivkey(keypair.privateKey, activated.manifest.sig)
        meta = MetaWriter.create(
            activated.provenanceDir.resolve("session-$sessionId.slog.meta"),
            sessionId,
            keypair.publicKeyHex,
            enc,
        )

        // Step 5a: the S3 ROLLING SEAL. A git-submitted assignment has no seal step, so the
        // recorder rewrites this session's own `.provenance/manifest-<session_id>.json` +
        // `.sig` on every checkpoint — whatever gets committed is then always a valid seal of
        // that moment. See io/RollingSeal.kt.
        //
        // GATED on the course's signed submission mode, and gated to FAIL OPEN.
        //
        // `submission` is part of the 2.0 signed payload, so it is trustworthy: at 1.x,
        // manifest parsing returns before it is read and the object it produces has no
        // `submission` at all, which means BUNDLE can only ever come from a manifest the
        // course actually signed. Nothing unsigned can turn the seal off.
        //
        // The asymmetry is deliberate. Rolling where it is not needed costs two extra files in
        // `.provenance/`, and the classic manifest still wins as the bundle's manifest, so
        // nothing about a bundle-submitted course's analysis changes. NOT rolling where it IS
        // needed costs an unsealed-session defect on every session, which fails check 1 — a
        // false accusation against a student whose course simply has not migrated to a 2.0
        // manifest yet. Between "a couple of redundant files" and "an integrity finding against
        // innocent work", only one is acceptable, so the seal is suppressed only when the
        // course has signed a statement that it submits bundles.
        rollingSeal = if (activated.manifest.submission == ManifestSubmission.BUNDLE) {
            null
        } else {
            RollingSealMaintainer(
                provenanceDir = activated.provenanceDir,
                sessionId = sessionId,
                prevSessionId = prevSessionId,
                slogPath = slogPath,
                workspaceRoot = activated.workspaceRoot,
                assignmentId = activated.manifest.assignmentId,
                semester = activated.manifest.semester,
                filesUnderReview = activated.manifest.filesUnderReview,
                sessionPrivkey = keypair.privateKey,
                computeExtensionHash = computeExtensionHash,
            )
        }

        // Step 5a2: PEER WITNESSING (program spec §7 mechanism 2, collaboration spec §5.5).
        //
        // One watcher over THIS session's `.provenance/`, constructed with a read-only view of
        // it: [PeerFiles] declares `list` and `read` and nothing else, so renaming, rewriting
        // or deleting a partner's log is unreachable rather than merely unwritten. Decision-log
        // bug 2 was exactly that mistake made once already, in startup recovery, and a
        // directory full of other students' evidence is the second place it could be made.
        //
        // `isOwnFile` excludes this session's own two artifacts by BASENAME (rule 4). Only the
        // filename uuid is known to the session, and it is minted independently of the logical
        // session id — the two id spaces decision-log bugs 10 and 12 were both about — so the
        // predicate is built from the paths, never from `sessionId`.
        peerWatcher = PeerWatcher(
            files = peerFiles ?: NioPeerFiles(activated.provenanceDir),
            isOwnFile = { name ->
                name == slogPath.fileName.toString() ||
                    name == "${slogPath.fileName}.meta"
            },
            // Through `record`, the SAME guarded path every other emitter uses: dropped after
            // endSession, gated by the capture policy (peer.observed is on the floor, so the
            // gate always passes), and chained by SessionHost under its lock.
            emit = { payload -> record("peer.observed", payload.toJsonObject()) },
            // Witnessing is best effort. A failure here is logged and degrades to "no
            // observation this round" — deliberately NOT routed into the DiskFullHandler,
            // which would switch the session to a critical-events-only ring buffer and trade
            // the student's event stream for a witness.
            onError = { e -> LOG.warn("provenance: peer watcher error", e) },
        )
        // Rule 1: ONE listener on the directory, not one per file. Rule 2: its callback does no
        // I/O. It is a promptness signal only — the drain sweeps the directory itself, because
        // IntelliJ's VFS is a cached layer and a `git pull` in an external terminal produces no
        // VFS event until something refreshes. See PeerWatcher's docstring.
        ApplicationManager.getApplication().messageBus.connect(parentDisposable).subscribe(
            VirtualFileManager.VFS_CHANGES,
            ProvenanceDirVfsListener(activated.provenanceDir, peerWatcher),
        )
        Disposer.register(parentDisposable) { peerWatcher.dispose() }

        // Step 5b: checkpoint cadence + ordered async sign+persist (every checkpointInterval
        // entries). drain()ed from endSession() so the last in-flight checkpoint isn't lost.
        checkpointCadence = CheckpointCadence(checkpointInterval)
        checkpointScope = checkpointScopeFactory()
        checkpointScheduler = CheckpointScheduler(
            scope = checkpointScope,
            privateKey32 = keypair.privateKey,
            // ROLLING SEAL WRITE POINT 2 of 3: after each checkpoint lands in the `.meta`.
            //
            // Inside the checkpoint's append step, so it runs under CheckpointScheduler's own
            // mutex — one roll per checkpoint, in checkpoint order, never two at once. AFTER
            // appendCheckpoint so `meta_sha256` covers the checkpoint just written, and in a
            // `finally` so a checkpoint that failed to persist still gets the best seal
            // available. roll() never throws, so it cannot mask or displace the append's error.
            appendCheckpoint = { cp ->
                try {
                    meta.appendCheckpoint(cp)
                } finally {
                    // PEER-WITNESS DRAIN 1 of 2, on the checkpoint cadence (writer contract
                    // rule 3) and BEFORE the rolling seal, so the observations it emits are in
                    // the `.slog` that the seal about to be written commits to. All of the
                    // watcher's I/O happens here, on the checkpoint coroutine's dispatcher —
                    // never on a VFS callback. drain() never throws, so it cannot mask or
                    // displace the append's error, and it runs under CheckpointScheduler's own
                    // mutex so two drains cannot interleave.
                    try {
                        peerWatcher.drain()
                    } finally {
                        rollingSeal?.roll()
                    }
                }
            },
            onError = { e -> LOG.warn("checkpoint sign/write error", e) },
        )

        // Step 6: session host — every emitted entry is routed through the disk-full/
        // checkpoint logic shared with SessionLifecycleIntegrationTest (routeSessionEntry).
        host = createSessionHost(sessionId, clock) { entry ->
            routeSessionEntry(entry, { writer.append(it) }, diskFullHandler, checkpointCadence) { seq, hash ->
                checkpointScheduler.schedule(seq, hash)
            }
        }
        sessionHostEmit = { kind, data -> host.emit(kind, data) }

        // Step 7: emit session.start, then — if we recovered from a corrupt prior session —
        // recorder.recovered_from_corruption as the very next entry (seq 1).
        host.emit("session.start", ctx.toJsonObject())
        recoveryFollowupPayload(recovery)?.let { host.emit("recorder.recovered_from_corruption", it.toJsonObject()) }

        // ROLLING SEAL WRITE POINT 1 of 3: immediately, before the first checkpoint is
        // anywhere near due.
        //
        // Checkpoints land every `checkpointInterval` entries, so a session that records only
        // session.start would never reach one — and in a git-submitted repo that session's
        // `.slog` would be committed with no seal covering it at all. Sealing here means a
        // session is sealed from its first instant and every later rewrite is an update, never
        // the first write.
        //
        // Synchronous on purpose, alongside the keypair generation, privkey encryption and
        // `.meta` write this constructor already does: a fire-and-forget seal could outlive the
        // construction that spawned it and land in a `.provenance/` the caller (or a test's
        // teardown) has already removed.
        rollingSeal?.roll()

        // Step 8: heartbeat + doc wiring, tied to parentDisposable.
        val focused = AtomicBoolean(true)
        ApplicationManager.getApplication().messageBus.connect(parentDisposable).subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                // Feed the heartbeat's focus flag AND emit a discrete focus.change (PRD §4.2),
                // mirroring the VS Code recorder's emitFocusChange on window-state transitions.
                override fun applicationActivated(ideFrame: IdeFrame) {
                    focused.set(true)
                    record("focus.change", FocusChangePayload(gained = true).toJsonObject())
                }

                override fun applicationDeactivated(ideFrame: IdeFrame) {
                    focused.set(false)
                    record("focus.change", FocusChangePayload(gained = false).toJsonObject())
                }
            },
        )
        // active_file is served from an EDT-fed cache, NOT read off the platform on each tick:
        // the heartbeat ticks on a background scheduler thread, where walking FileEditorManager's
        // editor/tab state is unsafe and taking the read lock every 30s would contend with write
        // actions. See ActiveFileTracker.
        val activeFileTracker = ActiveFileTracker(project, parentDisposable)
        heartbeat = Heartbeat(
            emit = { record("session.heartbeat", it.toJsonObject()) },
            emitResumed = { record("session.resumed", it.toJsonObject()) },
            clock = clock,
            focusedProvider = { focused.get() },
            getActiveFile = activeFileTracker::activeFileName,
            intervalMs = heartbeatIntervalMs ?: policy.heartbeatIntervalMs,
            scheduler = scheduler,
            getWallMs = System::currentTimeMillis,
        )

        // Step 8b: clock.skew watcher (PRD §4.2) — monotonic vs wall drift. Uses the session
        // clock's monotonic reading and the JVM wall clock; the injected scheduler drives ticks.
        val clockSkewWatcher = ClockSkewWatcher(
            emit = { record("clock.skew", it.toJsonObject()) },
            getMonotonicMs = { clock.now() },
            getWallMs = { System.currentTimeMillis() },
            scheduler = scheduler,
        )
        Disposer.register(parentDisposable, clockSkewWatcher)

        // Step 7b: three-signal paste detection (Plan 6). This session owns its correlator; both
        // the EditorPaste action wrapper (signal 2, via RecorderPasteState's path-routed resolver
        // installed by RecorderSessionManager) and the project-scoped DocWiring's classifier
        // (signal 1) + clipboard similarity (signal 3) reach it through this sink's
        // [pasteCorrelator] getter once the router resolves this session as the owning one. No
        // per-session publish/clear into a shared slot anymore — the router IS the privacy gate.
        pasteCorrelator = PasteCorrelator(getNow = { clock.now() })

        pasteTicker = PasteAnomalyTicker(
            correlator = pasteCorrelator,
            emit = { record("paste.anomaly", it.toJsonObject()) },
            scheduler = scheduler,
        )
        Disposer.register(parentDisposable, pasteTicker)

        // NOTE: DocWiring / SelectionWiring are NOT constructed here anymore. They are project-
        // scoped (one global listener each), constructed once by RecorderSessionManager, and
        // route every doc/selection event to the nearest-enclosing session's sink (the six
        // on* methods below). A per-session listener would double-fire for nested/overlapping
        // assignment roots — see DocWiring's KDoc.

        // Ensure a graceful end if the parent is disposed without an explicit endSession.
        Disposer.register(parentDisposable) { endSession("dispose") }
    }

    // --- RecordableSessionSink: the doc/selection/paste event methods the project-scoped
    // DocWiring/SelectionWiring routers call once they've resolved this session as the owner.
    // Each routes through the same guarded [record] path as every other emitter (dropped after
    // endSession()). doc.change and paste also poke the heartbeat's activity clock, exactly as
    // the removed per-session DocWiring emit closures did.
    override fun onDocOpen(payload: dev.provenance.core.DocOpenPayload) = record("doc.open", payload.toJsonObject())

    override fun onDocChange(payload: dev.provenance.core.DocChangePayload) {
        heartbeat.recordActivity()
        record("doc.change", payload.toJsonObject())
    }

    override fun onDocSave(payload: dev.provenance.core.DocSavePayload) = record("doc.save", payload.toJsonObject())

    override fun onDocClose(payload: dev.provenance.core.DocClosePayload) = record("doc.close", payload.toJsonObject())

    override fun onPaste(payload: dev.provenance.core.PastePayload) {
        heartbeat.recordActivity()
        record("paste", payload.toJsonObject())
    }

    override fun onSelectionChange(payload: dev.provenance.core.SelectionChangePayload) = record("selection.change", payload.toJsonObject())

    /**
     * Route a wiring-sourced event to the session host, unless the session has already
     * ended. After endSession() the writer is disposed; late events (e.g. a doc.close
     * fired during editor/fixture teardown) must be dropped, not appended.
     *
     * **This is also the capture-policy gate, and it is the ONLY one.** Every
     * policy-controllable kind funnels here: the doc.open/doc.close/selection.change/
     * focus.change/paste emitters call it directly via the [RecordableSessionSink] methods
     * above, and terminal.open,
     * terminal.command, git.event, fs.external_change and ext.activate arrive through
     * [append], which is this same function. Nothing else can emit — `host` is private and
     * the wiring modules hold no other seam — so no present or future wiring module can
     * emit a disabled kind by forgetting a check.
     *
     * **Suppression MUST happen before [SessionHost.emit], and does.** `emit` is what
     * chains the entry and assigns its `seq`. Dropping an event *after* that point would
     * consume a sequence number and leave a hole, which validation check 4 (seq_gaps) reads as a
     * DELETED ENTRY — turning a course's privacy setting into a tamper signal against the
     * student. A policy must never be able to manufacture an accusation. Returning here,
     * before `emit` is called, is what makes a suppressed event cost nothing: no seq, no
     * chain link, no gap.
     *
     * Floor kinds are not special-cased and must not be: [isEventKindCaptured] returns true
     * for any kind with no `policy.capture` key, so the schema itself is the floor.
     *
     * The policy reaches only WHETHER a kind is emitted; it never edits a payload. An
     * `inline_content` knob that stripped the content fields off `paste` and
     * `fs.external_change` was removed for that reason — `internal_move` needs a paste's
     * content to DOWNGRADE `large_paste`, so withholding it made the system more accusatory,
     * not less. (The 64 KB inline size cap in the payload builders is a separate mechanism
     * and is untouched by any of this.)
     */
    private fun record(kind: String, data: kotlinx.serialization.json.JsonObject) {
        if (ended) return
        if (!isEventKindCaptured(kind, policy)) return
        host.emit(kind, data)
    }

    /**
     * Emit session.end, drain the last in-flight checkpoint sign+persist (so it isn't lost —
     * mirrors extension.ts's deactivate() awaiting pendingCheckpoint), flush + dispose the
     * writer, dispose the meta + heartbeat, cancel the checkpoint scope. Idempotent.
     */
    fun endSession(reason: String) {
        if (ended) return

        // PEER-WITNESS DRAIN 2 of 2, before `ended` closes the emit path and before
        // `session.end`, so the observations land INSIDE the session they belong to.
        //
        // Checkpoints fire every `checkpointInterval` entries, so a partner's log that arrived
        // after the last one would otherwise never be witnessed by this session at all — and a
        // `git pull` immediately before closing the IDE is an ordinary thing to do. This is
        // also why there is no timer: the contract's "checkpoint or a timer, whichever is
        // later" reads backwards (running both gives whichever is SOONER), and a teardown drain
        // is what makes a long-idle session merely LATE to witness rather than silent.
        //
        // Order matters and is the reverse of the write-point ordering below: this must run
        // while `record` still emits. drain() never throws.
        peerWatcher.drain()

        ended = true
        try {
            host.emit("session.end", SessionEndPayload(reason).toJsonObject())
        } finally {
            // The paste privacy gate is closed by RecorderSessionManager removing this session
            // from the registry before disposal, so the path-routed resolver stops handing out
            // this session's correlator; nothing to clear here anymore.
            pasteTicker.dispose()
            heartbeat.dispose()
            peerWatcher.dispose()
            runBlocking { checkpointScheduler.drain() }
            writer.dispose()
            meta.dispose()

            // ROLLING SEAL WRITE POINT 3 of 3: the last of the file-touching steps, so it
            // covers the fully flushed `.slog` (session.end included) and the drained `.meta`.
            //
            // `final = true` is claimable HERE AND ONLY HERE, and only because of the four
            // steps above: session.end is emitted, the last checkpoint has been drained into
            // the `.meta`, and both writers are closed. Nothing can append to either file after
            // this point, so the digests about to be signed are WHOLE-FILE commitments rather
            // than prefixes, and a reader is entitled to fail an append against them.
            //
            // The claim is made only on a path that actually reached here. Every way a session
            // can die without a clean teardown — the IDE crashing, a power cut, a full disk, a
            // read-only checkout, `.provenance/` removed by a `git checkout` — simply leaves
            // the last non-final seal in place, which a reader treats as a prefix commitment
            // with a reported unattested tail. That is a blameless coverage gap, not a tamper
            // finding, and it is why finality is claimed explicitly here rather than inferred
            // by a reader from a trailing `session.end` entry: `session.end` lives in the log,
            // and the log's completeness is the very thing in question.
            rollingSeal?.roll(isFinal = true)

            checkpointScope.cancel()
        }
    }

    /** Force a flush of buffered .slog bytes (used by tests and the seal path). */
    fun flush() = writer.flush()

    /**
     * Public append seam for coordinator-sourced events (fs.external_change / terminal.* /
     * git.event), wired by RecorderSessionManager. Routes through the exact same guarded
     * path as the internal doc.* emitters: dropped after endSession(), otherwise chained +
     * routed through the disk-full/checkpoint logic. The manager holds every such coordinator
     * on the session Disposable, so nothing calls this after the session ends in practice;
     * the `ended` guard in [record] is the belt-and-suspenders for a late teardown event.
     */
    fun append(kind: String, data: JsonObject) = record(kind, data)

    companion object {
        private val LOG = Logger.getInstance(RecordingSessionController::class.java)

        val DEFAULT_SCHEDULER: FlushScheduler = FlushScheduler { periodMs, task ->
            AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(task, periodMs, periodMs, TimeUnit.MILLISECONDS)
        }
    }
}

/**
 * Owns one session's S3 rolling seal: the memoized `extension_hash`, the mutual exclusion
 * between the three write points, and the "a seal failure is never fatal" rule.
 *
 * ## Why the lock
 *
 * The three rolls run on three different threads — the session-start roll on whatever thread
 * constructed the controller, each checkpoint roll on the checkpoint coroutine's dispatcher,
 * the teardown roll on whoever called `endSession` (often the EDT, via the Disposer). Two
 * concurrent rewrites would interleave their `.json` and `.sig` renames and could leave a
 * mismatched pair on disk — the one thing the paired atomic write exists to prevent. This is
 * a lock over the SEAL only; it is nowhere near the hash chain, whose own critical section
 * lives in [SessionHost.emit] and is untouched by any of this.
 *
 * ## Why nothing here throws
 *
 * Recording matters more than sealing. `writeRollingSeal` already returns its failures as
 * [RollingSealResult.WriteError]; the extra try/catch covers the one step outside it, the
 * `extension_hash` computation, which walks the installed plugin tree and can fail with an
 * Error (an unresolvable plugin descriptor, or IJent's `NotImplementedError` on the WSL
 * filesystem) as readily as an Exception. Either way the session records on with whichever
 * seal it last managed to write.
 *
 * Deliberately NOT routed into [dev.provenance.recorder.failure.DiskFullHandler]: that
 * switches the session to a critical-events-only ring buffer, and throwing away the student's
 * event stream because a receipt could not be rewritten would trade the recording for the
 * receipt.
 */
private class RollingSealMaintainer(
    private val provenanceDir: Path,
    private val sessionId: String,
    private val prevSessionId: String?,
    private val slogPath: Path,
    private val workspaceRoot: Path,
    private val assignmentId: String,
    private val semester: String,
    private val filesUnderReview: List<String>,
    private val sessionPrivkey: ByteArray,
    private val computeExtensionHash: () -> String,
) {
    private val lock = Any()

    /** Memoized under [lock]; the walk is far too expensive to repeat per checkpoint. */
    private var extensionHash: String? = null

    /**
     * Rewrite this session's seal to reflect the state right now. Never throws.
     *
     * @param isFinal ONLY the teardown roll may pass true — see the call site in
     *   [RecordingSessionController.endSession]. Passing it from a checkpoint would assert
     *   that a log which is about to keep growing is finished, and a reader would then read
     *   the student's own next keystroke as an append past a final seal.
     */
    fun roll(isFinal: Boolean = false) = synchronized(lock) {
        val result = try {
            val hash = extensionHash ?: computeExtensionHash().also { extensionHash = it }
            writeRollingSeal(
                provenanceDir = provenanceDir,
                sessionId = sessionId,
                prevSessionId = prevSessionId,
                slogPath = slogPath,
                workspaceRoot = workspaceRoot,
                assignmentId = assignmentId,
                semester = semester,
                filesUnderReview = filesUnderReview,
                sessionPrivkey = sessionPrivkey,
                extensionHash = hash,
                isFinal = isFinal,
            )
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            RollingSealResult.WriteError("extension hash: ${e.message ?: e.toString()}")
        }
        if (result is RollingSealResult.WriteError) {
            LOG.warn("provenance: rolling seal write error: ${result.message}")
        }
        Unit
    }

    private companion object {
        private val LOG = Logger.getInstance(RollingSealMaintainer::class.java)
    }
}
