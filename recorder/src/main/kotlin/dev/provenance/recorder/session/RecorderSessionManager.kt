package dev.provenance.recorder.session

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
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
import dev.provenance.recorder.plugin.ownPluginDescriptor
import dev.provenance.recorder.startup.NioRecoveryDeps
import dev.provenance.recorder.startup.RecoveryDecision
import dev.provenance.recorder.startup.recoverPreviousSession
import dev.provenance.recorder.watch.ExternalChangeCoordinator
import dev.provenance.recorder.watch.VfsExternalChangeListener
import dev.provenance.recorder.wiring.DocWiring
import dev.provenance.recorder.wiring.RecordableSessionSink
import dev.provenance.recorder.wiring.RecorderGitState
import dev.provenance.recorder.wiring.RecorderTerminalState
import dev.provenance.recorder.wiring.SelectionWiring
import dev.provenance.recorder.wiring.SessionRouter
import dev.provenance.recorder.wiring.isRecordablePath
import dev.provenance.recorder.wiring.snapshot.ExtActivateWiring
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The stable reverse-DNS plugin id (see plugin.xml). Marketplace/auto-update key off it. */
const val RECORDER_PLUGIN_ID = "com.aaryanmehta.provenance.recorder"

/**
 * Project-scoped registry of every live recording session, one per verified assignment root
 * (nested-manifest discovery: N verified manifests → N concurrent sessions). Activation
 * (RecorderActivationActivity) discovers every root and calls [startFromActivation] once per
 * root; this manager runs startup chain-recovery, constructs each root's
 * [RecordingSessionController], and — on the FIRST session in an otherwise-empty registry —
 * installs the project-scoped [DocWiring]/[SelectionWiring] routers and the terminal/git
 * routing callbacks (torn down again when the LAST session stops). Each session's own
 * ExternalChangeCoordinator remains per-session (already scoped by that session's
 * filesUnderReview, so N independent instances are cheap and correct — unlike the doc/
 * selection firehose, which must not be N independent global listeners).
 *
 * Lifecycle: a [Disposable] project service; the platform calls [dispose] on project close,
 * which stops every session (seal-safe: session.end + writer flush/dispose per session, no
 * auto-seal). Sealing stays an explicit user action (the seal AnAction, per-root).
 */
@Service(Service.Level.PROJECT)
class RecorderSessionManager(private val project: Project) : Disposable, SessionRouter {

    data class ActiveSession(
        val controller: RecordingSessionController,
        val activated: ActivatedWorkspace,
        val sessionDisposable: Disposable,
        val explanationTagger: ExplanationTagger,
    )

    private val sessions = ConcurrentHashMap<Path, ActiveSession>()

    val activeSessions: Map<Path, ActiveSession> get() = sessions.toMap()

    /** Back-compat single-session convenience: the session when exactly one root is active,
     * else null (including when more than one is active — ambiguous; use [activeSessions]). */
    val activeSession: ActiveSession? get() = sessions.values.singleOrNull()

    /** Test-only fs seams for the project-scoped DocWiring/SelectionWiring, read once when
     * they're lazily constructed on the first [start] call after the registry goes from empty
     * to non-empty. Must be set BEFORE that first [start] call in a test. */
    @TestOnly
    @Volatile
    var localFsOfOverride: ((VirtualFile) -> Boolean)? = null

    @TestOnly
    @Volatile
    var nioPathOfOverride: ((VirtualFile) -> Path?)? = null

    private class RoutedWiring(val disposable: Disposable, val docWiring: DocWiring, val selectionWiring: SelectionWiring)

    @Volatile
    private var routedWiring: RoutedWiring? = null

    /**
     * Construct the project-scoped doc/selection/terminal/git routing ONCE, lazily, the moment
     * the registry goes from empty to non-empty. It reads the test fs-seam overrides at this
     * point (so a test must set them before its first [start]). [teardownRoutedWiringIfIdle]
     * disposes it again when the last session stops, so a test that reuses one manager instance
     * across methods gets a fresh router (with that method's overrides) on each empty→non-empty
     * transition, never a stale one.
     */
    private fun ensureRoutedWiring() {
        if (routedWiring != null) return
        val localFsOf = localFsOfOverride ?: { vf: VirtualFile -> vf.isInLocalFileSystem }
        val nioPathOf = nioPathOfOverride ?: { vf: VirtualFile -> runCatching { vf.toNioPath() }.getOrNull() }
        val disposable = Disposer.newDisposable(this, "provenance-routed-wiring")
        val doc = DocWiring(project, this, disposable, localFsOf = localFsOf, nioPathOf = nioPathOf)
        val sel = SelectionWiring(this, disposable, localFsOf = localFsOf, nioPathOf = nioPathOf)
        val terminalState = project.service<RecorderTerminalState>()
        terminalState.emitTerminalOpen = { cwd, payload -> routeTerminalOpen(cwd, payload) }
        terminalState.emitTerminalCommand = { cwd, payload -> routeTerminalCommand(cwd, payload) }
        project.service<RecorderGitState>().emit = { repoRoot, payload -> routeGitEvent(repoRoot, payload) }
        routedWiring = RoutedWiring(disposable, doc, sel)
    }

    private fun teardownRoutedWiringIfIdle() {
        if (sessions.isNotEmpty()) return
        val rw = routedWiring ?: return
        routedWiring = null
        Disposer.dispose(rw.disposable)
        project.service<RecorderTerminalState>().apply { emitTerminalOpen = null; emitTerminalCommand = null }
        project.service<RecorderGitState>().emit = null
    }

    private fun nearestEntry(path: Path, predicate: (Path, ActiveSession) -> Boolean): Map.Entry<Path, ActiveSession>? =
        sessions.entries.filter { (root, s) -> predicate(root, s) }.maxByOrNull { it.key.nameCount }

    /** [SessionRouter] implementation: nearest-enclosing session whose recordability
     * exclusions (workspace scope, `.provenance/`, activation manifest names, `.idea/`) admit
     * [nioPath]. Shared by DocWiring and SelectionWiring. */
    override fun sinkFor(nioPath: Path): RecordableSessionSink? =
        nearestEntry(nioPath.normalize()) { root, s -> isRecordablePath(nioPath, true, root, s.activated.provenanceDir) }
            ?.value?.controller

    /** The root (if any) whose assignment nearest-encloses [path] — no recordability
     * exclusions applied (used by the seal action to default-select the focused editor's
     * assignment, not to decide what to record). */
    fun rootOwning(path: Path): Path? {
        val normalized = runCatching { path.toRealPath() }.getOrDefault(path.normalize())
        return nearestEntry(normalized) { root, _ -> normalized.startsWith(root) }?.key
    }

    private fun sessionOwning(path: Path): ActiveSession? {
        val normalized = runCatching { path.toRealPath() }.getOrDefault(path.normalize())
        return nearestEntry(normalized) { root, _ -> normalized.startsWith(root) }?.value
    }

    private fun routeTerminalOpen(cwd: Path?, payload: dev.provenance.core.TerminalOpenPayload) {
        val session = cwd?.let(::sessionOwning) ?: return
        session.controller.append("terminal.open", payload.toJsonObject())
    }

    private fun routeTerminalCommand(cwd: Path?, payload: dev.provenance.core.TerminalCommandPayload) {
        val session = cwd?.let(::sessionOwning) ?: return
        session.controller.append("terminal.command", payload.toJsonObject())
    }

    private fun routeGitEvent(repoRoot: Path?, payload: dev.provenance.core.GitEventPayload) {
        val session = repoRoot?.let(::sessionOwning) ?: return
        session.explanationTagger.markGit()
        session.controller.append("git.event", payload.toJsonObject())
    }

    /** Production entry point, called from activation once a discovered manifest verifies.
     * No-op (logs) if a session for this root is already active. */
    suspend fun startFromActivation(root: Path, manifest: Manifest) {
        if (sessions.containsKey(root.normalize())) return
        val provenanceDir = root.resolve(".provenance")
        val recovery = recoverPreviousSession(NioRecoveryDeps(provenanceDir.toString()))
        val descriptor = ownPluginDescriptor()
        start(
            activated = ActivatedWorkspace(manifest, provenanceDir, root),
            recovery = recovery,
            ideVersion = ApplicationInfo.getInstance().fullVersion,
            platform = System.getProperty("os.name") ?: "unknown",
            recorderVersion = descriptor?.version ?: "0.0.0",
            recorderExtensionId = RECORDER_PLUGIN_ID,
        )
    }

    /** Testable core: construct the controller for [activated.workspaceRoot] and wire the
     * remaining per-session coordinators (external-change, ext.activate) into it. Ensures the
     * shared doc/selection/terminal/git routing exists (constructing it on the very first
     * session in an otherwise-empty registry). */
    fun start(
        activated: ActivatedWorkspace,
        recovery: RecoveryDecision,
        ideVersion: String,
        platform: String,
        recorderVersion: String,
        recorderExtensionId: String,
        clock: Clock = SystemClock(),
        scheduler: FlushScheduler = RecordingSessionController.DEFAULT_SCHEDULER,
        vfsDispatch: (() -> Unit) -> Unit = VfsExternalChangeListener.DEFAULT_DISPATCH,
    ): ActiveSession {
        val root = activated.workspaceRoot.normalize()
        check(sessions[root] == null) { "a recording session is already active for root $root" }

        val sessionDisposable = Disposer.newDisposable(this, "provenance-recording-session-$root")

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
            recovery = recovery,
        )

        // Shared explanation tagger, one PER SESSION: git wiring marks THIS session's tagger on
        // each git.event; this session's external-change emit path consumes it so a checkout/
        // reset-driven fs.external_change carries explanation="git" (PRD §4.5). A shared/global
        // tagger would leak a git mark from one assignment into another's fs.external_change.
        val tagger = ExplanationTagger(getNow = { clock.now() })
        val session = ActiveSession(controller, activated, sessionDisposable, tagger)

        // Register the session BEFORE ensureRoutedWiring(): on the first session, that call
        // constructs the project-scoped DocWiring, whose init runs a catch-up over files already
        // open at session start and resolves each through sinkFor(). If the session weren't in
        // the registry yet, that catch-up would resolve an empty registry and silently drop the
        // pre-open file's doc.open (its listeners would still fire for later events, but the
        // already-open file's initial snapshot would be lost). Registering first closes that gap.
        sessions[root] = session
        ensureRoutedWiring()

        wireExternalChange(controller, activated, tagger, vfsDispatch, sessionDisposable)
        // NO ext.snapshot (PRD §4.4) — deliberately unwired on this host, not an oversight. Every
        // plugin-enumeration API is @ApiStatus.Internal as of 262 (Marketplace-rejected), and the
        // one public accessor cannot report a plugin's *enabled* state. wireExtActivate below only
        // fires for mid-session plugin loads, so pre-installed AI assistants go unreported.
        wireExtActivate(controller, sessionDisposable)

        return session
    }

    /**
     * ext.activate on mid-session plugin loads (recorder PRD §4.2), the JetBrains analogue of the
     * VS Code recorder's extension-activation poller. Subscribes a [DynamicPluginListener] on the
     * application message bus, tied to the session Disposable (the privacy gate: the subscription
     * exists only while this session is live). See [ExtActivateWiring] for the semantic mapping.
     */
    private fun wireExtActivate(controller: RecordingSessionController, sessionDisposable: Disposable) {
        ApplicationManager.getApplication().messageBus.connect(sessionDisposable).subscribe(
            DynamicPluginListener.TOPIC,
            ExtActivateWiring.listener { controller.append("ext.activate", it.toJsonObject()) },
        )
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

    /** End one session (root != null) or every session (root == null — project close / test
     * teardown, preserving every existing no-arg `manager.stop()` call site). Idempotent. */
    fun stop(root: Path? = null) {
        if (root == null) {
            sessions.keys.toList().forEach(::stopOne)
        } else {
            stopOne(runCatching { root.toRealPath() }.getOrDefault(root.normalize()))
        }
    }

    private fun stopOne(root: Path) {
        val s = sessions.remove(root) ?: return
        Disposer.dispose(s.sessionDisposable)
        teardownRoutedWiringIfIdle()
    }

    /**
     * Test-only stand-in for the extension-hash seam. Production resolves the hash via
     * [ownPluginDescriptor], which requires a real plugin class loader; under the test harness
     * plugin classes are loaded by `PathClassLoader`, so the descriptor is null and the hash
     * cannot be computed. Tests that drive the real seal path set this to supply a stand-in.
     * Null in production; real resolution is covered by the manual runIde pass.
     */
    @TestOnly
    @Volatile
    var extensionHashOverride: (() -> String)? = null

    /** Seal a specific assignment root's session (the seal action always specifies which
     * root once it knows there is more than one). */
    fun sealSession(
        root: Path,
        now: () -> Instant = Instant::now,
        computeExtensionHash: () -> String = extensionHashOverride ?: { computeInstalledExtensionHash(RECORDER_PLUGIN_ID) },
    ): SealResult {
        val s = sessions[runCatching { root.toRealPath() }.getOrDefault(root.normalize())] ?: return SealResult.NoSessions
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

    /** Back-compat single-session convenience: seals the one active session, or NoSessions if
     * zero or more than one are active (an ambiguous choice belongs to the UI chooser, not
     * this method — production code no longer calls this; it's kept for existing callers/tests
     * of the single-assignment path). */
    fun sealActiveSession(
        now: () -> Instant = Instant::now,
        computeExtensionHash: () -> String = extensionHashOverride ?: { computeInstalledExtensionHash(RECORDER_PLUGIN_ID) },
    ): SealResult {
        val entry = sessions.entries.singleOrNull() ?: return SealResult.NoSessions
        return sealSession(entry.key, now, computeExtensionHash)
    }

    override fun dispose() = stop()
}
