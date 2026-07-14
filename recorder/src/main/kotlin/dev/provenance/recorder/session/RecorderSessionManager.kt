package dev.provenance.recorder.session

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import dev.provenance.core.Clock
import dev.provenance.core.Manifest
import dev.provenance.core.SystemClock
import dev.provenance.core.toJsonObject
import dev.provenance.recorder.commands.SealResult
import dev.provenance.recorder.commands.computeInstalledExtensionHash
import dev.provenance.recorder.commands.sealBundle
import dev.provenance.recorder.events.ExplanationTagger
import dev.provenance.recorder.io.FlushScheduler
import dev.provenance.recorder.startup.NioRecoveryDeps
import dev.provenance.recorder.startup.RecoveryDecision
import dev.provenance.recorder.startup.recoverPreviousSession
import dev.provenance.recorder.watch.ExternalChangeCoordinator
import dev.provenance.recorder.watch.VfsExternalChangeListener
import dev.provenance.recorder.wiring.snapshot.ExtActivateWiring
import dev.provenance.recorder.wiring.snapshot.PluginSnapshotWiring
import dev.provenance.recorder.wiring.RecorderGitState
import dev.provenance.recorder.wiring.RecorderTerminalState
import java.nio.file.Path
import java.time.Instant

/** The stable reverse-DNS plugin id (see plugin.xml). Marketplace/auto-update key off it. */
const val RECORDER_PLUGIN_ID = "edu.berkeley.provenance.recorder"

/**
 * Project-scoped owner of the *one* live recording session (CLAUDE.md: "one controller per
 * open project"). Activation (RecorderActivationActivity) verifies the manifest and then calls
 * [startFromActivation]; this manager runs startup chain-recovery, constructs the
 * [RecordingSessionController], and wires the remaining coordinators (external-change,
 * terminal, git) into that live controller's append path — the assembly the VS Code recorder
 * performs inline in extension.ts Steps 11–16.
 *
 * Lifecycle: this is a [Disposable] project service, so the platform calls [dispose] on project
 * close, which stops (seals-safe: session.end + writer flush/dispose, no auto-seal) and tears
 * down every listener/scheduler. Sealing stays an explicit user action (the seal AnAction).
 *
 * The core wiring lives in [start], which takes every environment value + injectable seam as a
 * parameter, so a BasePlatformTestCase can drive a full live session deterministically (the
 * all-signals-live gate). [startFromActivation] is the thin production wrapper that resolves the
 * real IDE version / OS / plugin version / workspace root and the recovery decision.
 */
@Service(Service.Level.PROJECT)
class RecorderSessionManager(private val project: Project) : Disposable {

    data class ActiveSession(
        val controller: RecordingSessionController,
        val activated: ActivatedWorkspace,
        val sessionDisposable: Disposable,
    )

    @Volatile
    var activeSession: ActiveSession? = null
        private set

    /**
     * Production entry point, called from activation once the manifest verifies. Resolves the
     * workspace root + provenance dir, runs the startup chain-recovery decision against a real
     * filesystem (via NioRecoveryDeps), and starts the session. No-op (logs) if the workspace
     * root can't be resolved or a session is already active for this project.
     */
    suspend fun startFromActivation(manifest: Manifest) {
        if (activeSession != null) return
        val root = project.guessProjectDir()?.let { runCatching { it.toNioPath() }.getOrNull() }
        if (root == null) {
            LOG.info("no resolvable workspace root; recording not started")
            return
        }
        val workspaceRoot = runCatching { root.toRealPath() }.getOrDefault(root.normalize())
        val provenanceDir = workspaceRoot.resolve(".provenance")

        val recovery = recoverPreviousSession(NioRecoveryDeps(provenanceDir.toString()))

        val descriptor = PluginManagerCore.getPlugin(PluginId.getId(RECORDER_PLUGIN_ID))
        start(
            activated = ActivatedWorkspace(manifest, provenanceDir, workspaceRoot),
            recovery = recovery,
            ideVersion = ApplicationInfo.getInstance().fullVersion,
            platform = System.getProperty("os.name") ?: "unknown",
            recorderVersion = descriptor?.version ?: "0.0.0",
            recorderExtensionId = RECORDER_PLUGIN_ID,
        )
    }

    /**
     * Testable core: construct the controller and wire every coordinator into it, all tied to a
     * fresh session Disposable that is a child of this manager (so project close, or an explicit
     * [stop], tears the whole session down in one shot). Returns the session so the seal action
     * (and tests) can reach the controller + workspace.
     */
    fun start(
        activated: ActivatedWorkspace,
        recovery: RecoveryDecision,
        ideVersion: String,
        platform: String,
        recorderVersion: String,
        recorderExtensionId: String,
        clock: Clock = SystemClock(),
        scheduler: FlushScheduler = RecordingSessionController.DEFAULT_SCHEDULER,
        localFsOf: (VirtualFile) -> Boolean = { it.isInLocalFileSystem },
        nioPathOf: (VirtualFile) -> Path? = { runCatching { it.toNioPath() }.getOrNull() },
        vfsDispatch: (() -> Unit) -> Unit = VfsExternalChangeListener.DEFAULT_DISPATCH,
    ): ActiveSession {
        check(activeSession == null) { "a recording session is already active for this project" }

        val sessionDisposable = Disposer.newDisposable(this, "provenance-recording-session")

        val controller = RecordingSessionController(
            activated = activated,
            project = project,
            ideVersion = ideVersion,
            platform = platform,
            recorderVersion = recorderVersion,
            recorderExtensionId = recorderExtensionId,
            parentDisposable = sessionDisposable,
            clock = clock,
            scheduler = scheduler,
            localFsOf = localFsOf,
            nioPathOf = nioPathOf,
            recovery = recovery,
        )

        // Shared explanation tagger: git wiring marks it on each git.event; the external-change
        // emit path consumes it so a checkout/reset-driven fs.external_change carries
        // explanation="git" instead of reading as an unexplained edit (PRD §4.5).
        val tagger = ExplanationTagger(getNow = { clock.now() })

        wireExternalChange(controller, activated, tagger, vfsDispatch, sessionDisposable)
        wireTerminalAndGit(controller, tagger, sessionDisposable)
        wireExtSnapshot(controller, scheduler, sessionDisposable)
        wireExtActivate(controller, sessionDisposable)

        return ActiveSession(controller, activated, sessionDisposable).also { activeSession = it }
    }

    /**
     * ext.activate on mid-session plugin loads (recorder PRD §4.2), the JetBrains analogue of the
     * VS Code recorder's extension-activation poller. Subscribes a [DynamicPluginListener] on the
     * application message bus, tied to the session Disposable (the privacy gate: the subscription
     * exists only while a session is live). See [ExtActivateWiring] for the semantic mapping.
     */
    private fun wireExtActivate(controller: RecordingSessionController, sessionDisposable: Disposable) {
        ApplicationManager.getApplication().messageBus.connect(sessionDisposable).subscribe(
            DynamicPluginListener.TOPIC,
            ExtActivateWiring.listener { controller.append("ext.activate", it.toJsonObject()) },
        )
    }

    /**
     * ext.snapshot enumeration of installed IntelliJ plugins (recorder PRD §4.4), mirroring the
     * VS Code recorder's extension-snapshot.ts: one snapshot immediately at session start, then a
     * periodic re-emit every 5 min. [PluginManagerCore] is core-platform (always present), so no
     * optional-dependency gating — this always-on signal lives directly on the session Disposable.
     * The periodic tick uses the same injected [FlushScheduler] as Heartbeat/PasteAnomalyTicker.
     */
    private fun wireExtSnapshot(
        controller: RecordingSessionController,
        scheduler: FlushScheduler,
        sessionDisposable: Disposable,
    ) {
        val snapshot = PluginSnapshotWiring.fromPlatform(
            emit = { controller.append("ext.snapshot", it.toJsonObject()) },
            scheduler = scheduler,
        )
        Disposer.register(sessionDisposable, snapshot)
    }

    private fun wireExternalChange(
        controller: RecordingSessionController,
        activated: ActivatedWorkspace,
        tagger: ExplanationTagger,
        vfsDispatch: (() -> Unit) -> Unit,
        sessionDisposable: Disposable,
    ) {
        val coordinator = ExternalChangeCoordinator(
            project = project,
            workspaceRoot = activated.workspaceRoot,
            filesUnderReview = activated.manifest.filesUnderReview,
            emit = { payload ->
                // Consume once per external change (mirrors fs-watcher.ts): a recent git mark
                // explains this change; otherwise keep whatever the payload already carried (null).
                val explained = payload.copy(explanation = tagger.consume() ?: payload.explanation)
                controller.append("fs.external_change", explained.toJsonObject())
            },
            vfsDispatch = vfsDispatch,
        )
        Disposer.register(sessionDisposable, coordinator)
        coordinator.start()
    }

    private fun wireTerminalAndGit(
        controller: RecordingSessionController,
        tagger: ExplanationTagger,
        sessionDisposable: Disposable,
    ) {
        // Terminal + git emit seams are long-lived project services whose subscriptions are set
        // up by the (structurally-gated) startup activities; the null emit callback is the
        // privacy gate. Opening it here activates recording; the teardown callback closes it.
        val terminalState = project.service<RecorderTerminalState>()
        terminalState.emitTerminalOpen = { controller.append("terminal.open", it.toJsonObject()) }
        terminalState.emitTerminalCommand = { controller.append("terminal.command", it.toJsonObject()) }

        val gitState = project.service<RecorderGitState>()
        gitState.emit = { payload ->
            tagger.markGit()
            controller.append("git.event", payload.toJsonObject())
        }

        Disposer.register(sessionDisposable) {
            terminalState.emitTerminalOpen = null
            terminalState.emitTerminalCommand = null
            gitState.emit = null
        }
    }

    /**
     * End the live session (seal-safe): disposing the session Disposable runs the controller's
     * endSession (session.end + flush/dispose), the external-change coordinator's teardown, and
     * the terminal/git seam-clear. Idempotent. Sealing is a separate, explicit user action.
     */
    fun stop() {
        val s = activeSession ?: return
        activeSession = null
        Disposer.dispose(s.sessionDisposable)
    }

    /**
     * Seal the live session into a submission bundle (PRD §4.6 / §5.3), the work behind the
     * "Prepare Submission Bundle" action. Flushes the writer first (so pending .slog bytes are
     * on disk), then runs the untouched pure [sealBundle] with this session's manifest-derived
     * assignment/semester/files, the in-memory session private key, and the installed
     * distribution's extension hash. Returns [SealResult.NoSessions] if nothing is active.
     * The [computeExtensionHash]/[now] seams are injectable for deterministic tests.
     */
    fun sealActiveSession(
        now: () -> Instant = Instant::now,
        computeExtensionHash: () -> String = { computeInstalledExtensionHash(RECORDER_PLUGIN_ID) },
    ): SealResult {
        val s = activeSession ?: return SealResult.NoSessions
        s.controller.flush()
        val m = s.activated.manifest
        return sealBundle(
            provenanceDir = s.activated.provenanceDir,
            workspaceRoot = s.activated.workspaceRoot,
            assignmentId = m.assignmentId,
            semester = m.semester,
            filesUnderReview = m.filesUnderReview,
            sessionPrivkey = s.controller.sessionPrivkey,
            computeExtensionHash = computeExtensionHash,
            outputDir = s.activated.workspaceRoot,
            now = now,
        )
    }

    override fun dispose() = stop()

    companion object {
        private val LOG = Logger.getInstance(RecorderSessionManager::class.java)
    }
}
