package dev.provenance.recorder.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.concurrency.AppExecutorUtil
import dev.provenance.core.Clock
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
import dev.provenance.recorder.wiring.DocWiring
import dev.provenance.recorder.wiring.Heartbeat
import dev.provenance.recorder.wiring.paste.PasteAnomalyTicker
import dev.provenance.recorder.wiring.paste.RecorderPasteState
import com.intellij.openapi.vfs.VirtualFile
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
 * MetaWriter + SessionHost + DocWiring + Heartbeat, all tied to [parentDisposable].
 * Mirrors extension.ts's activateImpl Steps 3c–11, restricted to Plan 4's scope
 * (no paste/fs-watcher/terminal/git/ext-snapshot — those are later plans).
 *
 * The scheduler and DocWiring FS resolvers are injectable (production defaults) so the
 * controller is testable under a light BasePlatformTestCase whose files are not on the
 * local file system.
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
    localFsOf: (VirtualFile) -> Boolean = { it.isInLocalFileSystem },
    nioPathOf: (VirtualFile) -> Path? = { runCatching { it.toNioPath() }.getOrNull() },
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
) {
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
    private val pasteState: RecorderPasteState
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
                override fun applicationActivated(ideFrame: IdeFrame) = focused.set(true)
                override fun applicationDeactivated(ideFrame: IdeFrame) = focused.set(false)
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

        // Step 7b: three-signal paste detection (Plan 6). The correlator is shared
        // between the EditorPaste action wrapper (signal 2, via RecorderPasteState,
        // resolved per keystroke by the plugin.xml-registered PasteInterceptHandlerFactory)
        // and DocWiring's classifier (signal 1) + clipboard similarity (signal 3). Publishing
        // it into the project-scoped RecorderPasteState is what activates signal 2; clearing
        // it on endSession() is the privacy gate closing.
        val pasteCorrelator = PasteCorrelator(getNow = { clock.now() })
        pasteState = project.service<RecorderPasteState>()
        pasteState.correlator = pasteCorrelator

        pasteTicker = PasteAnomalyTicker(
            correlator = pasteCorrelator,
            emit = { record("paste.anomaly", it.toJsonObject()) },
            scheduler = scheduler,
        )
        Disposer.register(parentDisposable, pasteTicker)

        DocWiring(
            project = project,
            provenanceDir = activated.provenanceDir,
            workspaceRoot = activated.workspaceRoot,
            emitDocOpen = { record("doc.open", it.toJsonObject()) },
            emitDocChange = {
                heartbeat.recordActivity()
                record("doc.change", it.toJsonObject())
            },
            emitDocSave = { record("doc.save", it.toJsonObject()) },
            emitDocClose = { record("doc.close", it.toJsonObject()) },
            parentDisposable = parentDisposable,
            localFsOf = localFsOf,
            nioPathOf = nioPathOf,
            emitPaste = {
                heartbeat.recordActivity()
                record("paste", it.toJsonObject())
            },
            pasteCorrelator = pasteCorrelator,
        )

        // Ensure a graceful end if the parent is disposed without an explicit endSession.
        Disposer.register(parentDisposable) { endSession("dispose") }
    }

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
            // Close the paste privacy gate first so a late EditorPaste during teardown
            // resolves a null correlator (pure passthrough), not a disposed session.
            pasteState.correlator = null
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

    companion object {
        private val LOG = Logger.getInstance(RecordingSessionController::class.java)

        val DEFAULT_SCHEDULER: FlushScheduler = FlushScheduler { periodMs, task ->
            AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(task, periodMs, periodMs, TimeUnit.MILLISECONDS)
        }
    }
}
