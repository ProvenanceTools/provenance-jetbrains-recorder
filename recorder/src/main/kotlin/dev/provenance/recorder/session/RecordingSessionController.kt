package dev.provenance.recorder.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.concurrency.AppExecutorUtil
import dev.provenance.core.Clock
import dev.provenance.core.FocusChangePayload
import dev.provenance.core.Manifest
import dev.provenance.core.RecorderDegradedPayload
import dev.provenance.core.SessionEndPayload
import dev.provenance.core.SystemClock
import dev.provenance.core.encryptSessionPrivkey
import dev.provenance.core.generateSessionKeypair
import dev.provenance.core.toJsonObject
import dev.provenance.recorder.failure.DegradedModeNotifier
import dev.provenance.recorder.failure.DiskFullHandler
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.io.MetaWriter
import dev.provenance.recorder.io.SessionWriter
import dev.provenance.recorder.paste.PasteCorrelator
import dev.provenance.recorder.startup.RecoveryDecision
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
    heartbeatIntervalMs: Long = Heartbeat.DEFAULT_INTERVAL_MS,
    /**
     * Plan 8: the startup chain-recovery decision for this workspace's .provenance dir,
     * already computed by the caller (recoverPreviousSession, via NioRecoveryDeps). This
     * controller never calls recoverPreviousSession itself — wiring recovery into
     * activation/project-open is a later integration pass; this constructor param is the
     * injectable seam that pass will fill in. Defaults to CleanStart so every existing
     * call site (no prior session to recover) is unaffected.
     */
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
        Files.createDirectories(activated.provenanceDir)
        // Step 1: session keypair.
        val keypair = generateSessionKeypair()
        sessionPrivkey = keypair.privateKey

        // Step 2: session.start payload. prev_session_id is set ONLY for a dangling prior
        // session (crash: no trailing session.end) — never for a cleanly-completed one, and
        // never for a corrupt one (corruption is surfaced via recorder.recovered_from_corruption
        // below, not chain linkage). Mirrors chain-recovery.ts's documented rule.
        val prevSessionId = prevSessionIdFor(recovery)
        val ctx = buildRecorderContext(
            manifest = activated.manifest,
            prevSessionId = prevSessionId,
            sessionId = sessionId,
            sessionPubkeyHex = keypair.publicKeyHex,
            ideVersion = ideVersion,
            platform = platform,
            recorderVersion = recorderVersion,
            recorderExtensionId = recorderExtensionId,
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
        heartbeat = Heartbeat(
            emit = { record("session.heartbeat", it.toJsonObject()) },
            clock = clock,
            focusedProvider = { focused.get() },
            getActiveFile = { FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.name },
            intervalMs = heartbeatIntervalMs,
            scheduler = scheduler,
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
     */
    private fun record(kind: String, data: kotlinx.serialization.json.JsonObject) {
        if (!ended) host.emit(kind, data)
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
