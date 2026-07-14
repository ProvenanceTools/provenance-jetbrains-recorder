package dev.provenance.recorder.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.concurrency.AppExecutorUtil
import dev.provenance.core.Clock
import dev.provenance.core.Manifest
import dev.provenance.core.SessionEndPayload
import dev.provenance.core.SystemClock
import dev.provenance.core.encryptSessionPrivkey
import dev.provenance.core.generateSessionKeypair
import dev.provenance.core.toJsonObject
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.io.MetaWriter
import dev.provenance.recorder.io.SessionWriter
import dev.provenance.recorder.wiring.DocWiring
import dev.provenance.recorder.wiring.Heartbeat
import com.intellij.openapi.vfs.VirtualFile
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
    private var ended = false

    init {
        Files.createDirectories(activated.provenanceDir)
        // Step 1: session keypair.
        val keypair = generateSessionKeypair()
        sessionPrivkey = keypair.privateKey

        // Step 2: session.start payload.
        val ctx = buildRecorderContext(
            manifest = activated.manifest,
            prevSessionId = null, // chain recovery is Plan 8
            sessionId = sessionId,
            sessionPubkeyHex = keypair.publicKeyHex,
            ideVersion = ideVersion,
            platform = platform,
            recorderVersion = recorderVersion,
            recorderExtensionId = recorderExtensionId,
        )

        // Step 3: open the .slog writer.
        slogPath = activated.provenanceDir.resolve("session-$sessionId.slog")
        writer = SessionWriter.open(slogPath, clock, scheduler)

        // Step 4: encrypt the session privkey under manifest.sig; create the meta writer.
        val enc = encryptSessionPrivkey(keypair.privateKey, activated.manifest.sig)
        meta = MetaWriter.create(
            activated.provenanceDir.resolve("session-$sessionId.slog.meta"),
            sessionId,
            keypair.publicKeyHex,
            enc,
        )

        // Step 5: session host wired to the writer.
        host = createSessionHost(sessionId, clock) { writer.append(it) }

        // Step 6: emit session.start.
        host.emit("session.start", ctx.toJsonObject())

        // Step 7: heartbeat + doc wiring, tied to parentDisposable.
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

    /** Emit session.end, flush + dispose the writer, dispose the meta + heartbeat. Idempotent. */
    fun endSession(reason: String) {
        if (ended) return
        ended = true
        try {
            host.emit("session.end", SessionEndPayload(reason).toJsonObject())
        } finally {
            heartbeat.dispose()
            writer.dispose()
            meta.dispose()
        }
    }

    /** Force a flush of buffered .slog bytes (used by tests and the seal path). */
    fun flush() = writer.flush()

    companion object {
        val DEFAULT_SCHEDULER: FlushScheduler = FlushScheduler { periodMs, task ->
            AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(task, periodMs, periodMs, TimeUnit.MILLISECONDS)
        }
    }
}
