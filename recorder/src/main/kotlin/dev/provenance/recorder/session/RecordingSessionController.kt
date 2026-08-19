package dev.provenance.recorder.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.concurrency.AppExecutorUtil
import dev.provenance.core.CapturePolicy
import dev.provenance.core.Clock
import dev.provenance.core.FocusChangePayload
import dev.provenance.core.Manifest
import dev.provenance.core.RecorderDegradedPayload
import dev.provenance.core.SessionEndPayload
import dev.provenance.core.SessionResumedPayload
import dev.provenance.core.SystemClock
import dev.provenance.core.encryptSessionPrivkey
import dev.provenance.core.isEventKindCaptured
import dev.provenance.core.resolveCapturePolicy
import dev.provenance.core.generateSessionKeypair
import dev.provenance.core.toJsonObject
import dev.provenance.recorder.failure.DegradedModeNotifier
import dev.provenance.recorder.failure.DiskFullHandler
import dev.provenance.recorder.identity.CourseKeyCache
import dev.provenance.recorder.identity.IdentityOutcome
import dev.provenance.recorder.identity.PasswordSafeSecretStore
import dev.provenance.recorder.identity.SecretStore
import dev.provenance.recorder.identity.buildSessionIdentity
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.io.MetaWriter
import dev.provenance.recorder.io.SessionWriter
import dev.provenance.recorder.paste.PasteCorrelator
import dev.provenance.recorder.startup.RecoveryDecision
import dev.provenance.recorder.wiring.ActiveFileTracker
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

        // Step 5b: checkpoint cadence + ordered async sign+persist (every checkpointInterval
        // entries). drain()ed from endSession() so the last in-flight checkpoint isn't lost.
        checkpointCadence = CheckpointCadence(checkpointInterval)
        checkpointScope = checkpointScopeFactory()
        checkpointScheduler = CheckpointScheduler(
            scope = checkpointScope,
            privateKey32 = keypair.privateKey,
            appendCheckpoint = { cp -> meta.appendCheckpoint(cp) },
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
        ended = true
        try {
            host.emit("session.end", SessionEndPayload(reason).toJsonObject())
        } finally {
            // The paste privacy gate is closed by RecorderSessionManager removing this session
            // from the registry before disposal, so the path-routed resolver stops handing out
            // this session's correlator; nothing to clear here anymore.
            pasteTicker.dispose()
            heartbeat.dispose()
            runBlocking { checkpointScheduler.drain() }
            writer.dispose()
            meta.dispose()
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
