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
import dev.provenance.core.SessionKeypair
import dev.provenance.core.SystemClock
import dev.provenance.core.generateSessionKeypair
import dev.provenance.core.scopeFromManifest
import dev.provenance.core.toJsonObject
import com.intellij.openapi.diagnostic.Logger
import dev.provenance.recorder.activation.ROOT_PUBLIC_KEY_HEX
import dev.provenance.recorder.activation.degradedReason
import dev.provenance.recorder.commands.SealResult
import dev.provenance.recorder.commands.computeInstalledExtensionHash
import dev.provenance.recorder.commands.sealBundle
import dev.provenance.recorder.events.ExplanationTagger
import dev.provenance.recorder.identity.CourseKeyCache
import dev.provenance.recorder.activation.RecorderState
import dev.provenance.recorder.identity.IdentityOutcome
import dev.provenance.recorder.identity.PasswordSafeSecretStore
import dev.provenance.recorder.identity.buildSessionIdentity
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
import dev.provenance.recorder.wiring.runOnEdtAndWait
import dev.provenance.recorder.wiring.sameAncestryLine
import dev.provenance.recorder.wiring.paste.RecorderPasteState
import dev.provenance.recorder.wiring.snapshot.ExtActivateWiring
import kotlinx.coroutines.runBlocking
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
     * point (so a test must set them before its first [start]), and takes [vfsDispatch] from
     * whichever [start] call constructs it. [teardownRoutedWiringIfIdle]
     * disposes it again when the last session stops, so a test that reuses one manager instance
     * across methods gets a fresh router (with that method's overrides) on each empty→non-empty
     * transition, never a stale one.
     */
    private fun ensureRoutedWiring(vfsDispatch: (() -> Unit) -> Unit) {
        if (routedWiring != null) return
        val localFsOf = localFsOfOverride ?: { vf: VirtualFile -> vf.isInLocalFileSystem }
        val nioPathOf = nioPathOfOverride ?: { vf: VirtualFile -> runCatching { vf.toNioPath() }.getOrNull() }
        val disposable = Disposer.newDisposable(this, "provenance-routed-wiring")
        val doc = DocWiring(
            project, this, disposable,
            localFsOf = localFsOf, nioPathOf = nioPathOf,
            // Same seam the coordinator gets, for the same reason: DocWiring's post-save
            // listener also hops off the write action before reading content, and a test that
            // wants to observe the resulting doc.save has to be able to run that hop inline.
            // Only the FIRST session's value is used — this wiring is project-scoped, exactly
            // like the localFsOf/nioPathOf overrides above.
            vfsDispatch = vfsDispatch,
        )
        val sel = SelectionWiring(this, disposable, localFsOf = localFsOf, nioPathOf = nioPathOf)
        val terminalState = project.service<RecorderTerminalState>()
        terminalState.emitTerminalOpen = { cwd, payload -> routeTerminalOpen(cwd, payload) }
        terminalState.emitTerminalCommand = { cwd, payload -> routeTerminalCommand(cwd, payload) }
        project.service<RecorderGitState>().apply {
            emit = { repoRoot, payload -> routeGitEvent(repoRoot, payload) }
            // Separate seam so the tag lands at the state change, not after the async
            // commit-graph read the emit path now performs. See RecorderGitState.markGit.
            // Marks EVERY owning session (see sessionsOwningRepo) — a repository above the
            // assignment root can own more than one concurrently-recording session at once.
            markGit = { repoRoot -> repoRoot?.let(::sessionsOwningRepo)?.forEach { it.explanationTagger.markGit() } }
        }
        // Paste signal 2 (the EditorPaste action wrapper) is routed by path the same way, so
        // concurrent sessions don't clobber a shared slot: the nearest-enclosing session's own
        // pasteCorrelator, or null when no session owns the pasted-into file (privacy gate).
        project.service<RecorderPasteState>().resolveCorrelator = { path -> path?.let(::sinkFor)?.pasteCorrelator }
        routedWiring = RoutedWiring(disposable, doc, sel)
    }

    private fun teardownRoutedWiringIfIdle() {
        if (sessions.isNotEmpty()) return
        val rw = routedWiring ?: return
        routedWiring = null
        Disposer.dispose(rw.disposable)
        project.service<RecorderTerminalState>().apply { emitTerminalOpen = null; emitTerminalCommand = null }
        project.service<RecorderGitState>().apply { emit = null; markGit = null }
        project.service<RecorderPasteState>().resolveCorrelator = null
    }

    private fun nearestEntry(predicate: (Path, ActiveSession) -> Boolean): Map.Entry<Path, ActiveSession>? =
        sessions.entries.filter { (root, s) -> predicate(root, s) }.maxByOrNull { it.key.nameCount }

    /** [SessionRouter] implementation: nearest-enclosing session whose recordability
     * exclusions (workspace scope, `.provenance/`, activation manifest names, `.idea/`) admit
     * [nioPath]. Shared by DocWiring and SelectionWiring. */
    override fun sinkFor(nioPath: Path): RecordableSessionSink? =
        nearestEntry { root, s -> isRecordablePath(nioPath, true, root, s.activated.provenanceDir) }
            ?.value?.controller

    /** The root (if any) whose assignment nearest-encloses [path] — no recordability
     * exclusions applied (used by the seal action to default-select the focused editor's
     * assignment, not to decide what to record). */
    fun rootOwning(path: Path): Path? {
        val normalized = runCatching { path.toRealPath() }.getOrDefault(path.normalize())
        return nearestEntry { root, _ -> normalized.startsWith(root) }?.key
    }

    private fun sessionOwning(path: Path): ActiveSession? {
        val normalized = runCatching { path.toRealPath() }.getOrDefault(path.normalize())
        return nearestEntry { root, _ -> normalized.startsWith(root) }?.value
    }

    /**
     * Every currently-active session that owns a `git.event` from a repository rooted at
     * [repoRoot] — decision-log bug 3's fix (`docs/superpowers/specs/2026-08-19-program-decision-
     * log.md`), ported from the monorepo VS Code recorder's `isRepoOwnedByRoot`
     * (`session-router.ts`). Unlike [sessionOwning] (a single-file containment lookup used for
     * terminal cwd routing, where "nearest enclosing root" is the only sensible answer), a git
     * repository root can be related to an assignment root in TWO directions:
     *
     *  - **repo AT-OR-BELOW a session root** (a submodule, or the ordinary non-nested case):
     *    [sessionOwning]'s containment rule already gets this right — only the NEAREST such root
     *    wins, so a repository nested inside a *nested* assignment root still routes to the
     *    nearest root rather than to its parent.
     *  - **repo ABOVE a session root** (one shared class repository, each assignment a
     *    subdirectory beneath it — the standard multi-course layout `ManifestDiscovery` is built
     *    to find). A plain containment check can never match this direction — the repo root does
     *    not descend from any assignment root, so [sessionOwning] returns null and every
     *    `git.event` for the session is silently dropped. This is EXACTLY decision-log bug 3's
     *    shape. Here every concurrently-recording session whose root descends from [repoRoot]
     *    owns it, not only the nearest: two sibling assignments in the same shared repo (say
     *    `course/hw1/` and `course/hw2/`, both actively recording) both see the same commit as
     *    their own — fail toward more evidence, per the monorepo fix; deduplicating across
     *    sessions is the analyzer's job, not the recorder's.
     *
     * [dev.provenance.recorder.wiring.git.GitCapabilityProbe.decideGitCapture] mirrors this same
     * two-direction relationship (via the shared [sameAncestryLine]) for the
     * `session.start.git_capture` capability report, so the two never disagree about which
     * direction counts as "owned" — it just cannot see sibling session roots to apply the
     * nearest-wins refinement below, so it answers the coarser question of whether ANY visible
     * repository lies on this session's ancestry line.
     */
    private fun sessionsOwningRepo(repoRoot: Path): List<ActiveSession> {
        val normalized = runCatching { repoRoot.toRealPath() }.getOrDefault(repoRoot.normalize())
        val nearestBelow = nearestEntry { root, _ -> normalized.startsWith(root) }?.key
        return sessions.entries
            .filter { (root, _) ->
                sameAncestryLine(normalized, root) && (root == nearestBelow || root.startsWith(normalized))
            }
            .map { it.value }
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
        val owners = repoRoot?.let(::sessionsOwningRepo) ?: return
        for (session in owners) {
            session.explanationTagger.markGit()
            session.controller.append("git.event", payload.toJsonObject())
        }
    }

    /**
     * Production entry point, called from activation once a discovered manifest verifies.
     * No-op if a session for this root is already active.
     *
     * ORDER MATTERS, and it is the whole point of this method's shape:
     *
     *  1. session keypair — the student key countersigns exactly this public key;
     *  2. session identity — chain-verified, and the ONLY source of `student_ref`;
     *  3. chain recovery, handed that `student_ref`;
     *  4. the controller, handed all three.
     *
     * Recovery used to run FIRST, with no identity in existence and therefore no way to
     * tell this student's `.slog` files from a partner's in a shared, committed
     * `.provenance/`. It selected whichever file sorted last and quarantined it (renamed to
     * `<slog>.corrupt-<ts>`) if it failed to read, parse or chain-validate — deleting a
     * partner's evidence from the submission, with git history showing the innocent student
     * doing it. Moving identity ahead of recovery is what closes that; see `SlogOwnership.kt`.
     *
     * Nothing between steps 1 and 3 consumes the recovery result, and `prev_session_id` is
     * not read until `session.start` is built, so the move is behaviour-preserving apart
     * from the ownership gate itself.
     *
     * `ownStudentRef` is null whenever the identity was not emitted — not enrolled, no
     * keyring, a lapsed cert. That is the common case today, it is handled explicitly inside
     * `recoverPreviousSession`, and it must never throw or block recording.
     */
    suspend fun startFromActivation(
        root: Path,
        manifest: Manifest,
        /**
         * SIZE ROTATION (recorder PRD §4.6): the id of the session this one succeeds, when this
         * start is a rotation rather than an activation. See
         * [RecordingSessionController.prevSessionIdOverride] for why a cleanly-rotated
         * predecessor has to name itself instead of being discovered by chain recovery.
         */
        prevSessionIdOverride: String? = null,
    ) {
        if (sessions.containsKey(root.normalize())) return
        val provenanceDir = root.resolve(".provenance")
        val clock = SystemClock()

        val keypair = generateSessionKeypair()
        val identityOutcome = buildSessionIdentity(
            manifest = manifest,
            sessionPubkeyHex = keypair.publicKeyHex,
            // Windows are judged against the session's own start instant, never wall-clock
            // now, so an archived bundle still reads correctly years later.
            sessionStartedAt = clock.wall(),
            secrets = PasswordSafeSecretStore(),
            // Resolved defensively for the same reason the controller does: a missing cache
            // must degrade to direct derivation, never fail session start.
            keyCache = runCatching {
                ApplicationManager.getApplication()?.getService(CourseKeyCache::class.java)
            }.getOrNull(),
            rootPubkeyHex = ROOT_PUBLIC_KEY_HEX,
        )
        val ownStudentRef = (identityOutcome as? IdentityOutcome.Emitted)?.verified?.studentRef

        // Kept, not just logged: this is the only place that knows whether the student is
        // enrolled, and the status bar + the one-time nudge both read it back out of
        // RecorderState. Recorded before `start()` so the widget refresh that follows
        // activation already sees it.
        runCatching { project.service<RecorderState>().recordIdentity(root, identityOutcome) }
            .onFailure { LOG.warn("could not record the identity outcome for the status bar", it) }

        val recovery = recoverPreviousSession(NioRecoveryDeps(provenanceDir.toString(), ownStudentRef))
        val descriptor = ownPluginDescriptor()
        start(
            activated = ActivatedWorkspace(manifest, provenanceDir, root),
            recovery = recovery,
            ideVersion = ApplicationInfo.getInstance().fullVersion,
            platform = System.getProperty("os.name") ?: "unknown",
            recorderVersion = descriptor?.version ?: "0.0.0",
            recorderExtensionId = RECORDER_PLUGIN_ID,
            clock = clock,
            preparedKeypair = keypair,
            preparedIdentity = identityOutcome,
            prevSessionIdOverride = prevSessionIdOverride,
        )
    }

    /** What [restartSessions] managed to do, per root. Both lists are in registry order. */
    data class RestartReport(val restarted: List<Path>, val failed: List<Path>) {
        val attempted: Int get() = restarted.size + failed.size
    }

    /**
     * Stop and start every live session, so each one rebuilds its `session.start`.
     *
     * Exists for exactly one caller: importing an enrollment credential mid-session. The
     * identity block is assembled ONCE, in [startFromActivation], before the session exists —
     * so a credential imported into a running session changes nothing about it, and every event
     * until the student next reopens the project lands in a bundle the analyzer will file under
     * nobody. Restarting is what makes the import take effect now.
     *
     * **This is not a new lifecycle.** Each root goes through the ordinary [stop] →
     * [startFromActivation] pair, in that order, with the manifest the session was already
     * running under (already verified at activation — re-discovering it here would re-walk the
     * VFS to reach the same object). So the old session gets its normal `session.end` + flush +
     * writer/meta close via its session Disposable, the new session runs the same chain recovery
     * every start runs, and `prev_session_id` follows the same rule as any other restart: set
     * only for a DANGLING prior session, which a cleanly stopped one is not.
     *
     * **A failed start does not throw and does not stay quiet.** The root is marked degraded in
     * [dev.provenance.recorder.activation.RecorderState], exactly as a failed start during
     * activation is, so the status bar reads "not recording (error)" instead of continuing to
     * claim it is recording. The caller reports the roots in [RestartReport.failed] and tells
     * the student to reopen the project. There is no way to keep the old session instead: its
     * writer is closed by then, and [start] refuses a second session for a live root.
     *
     * @param starter the per-root start, injectable so tests can drive the failure path.
     */
    suspend fun restartSessions(
        starter: suspend (Path, Manifest) -> Unit = { root, manifest -> startFromActivation(root, manifest) },
    ): RestartReport {
        // Snapshot first: the registry is mutated by every stop/start below.
        val open = sessions.entries.map { it.key to it.value.activated.manifest }
        val restarted = mutableListOf<Path>()
        val failed = mutableListOf<Path>()
        for ((root, manifest) in open) {
            stop(root)
            try {
                starter(root, manifest)
                restarted.add(root)
            } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                // The IDE is going down or the caller was cancelled. Not a recording failure,
                // and swallowing it would break structured concurrency.
                throw c
            } catch (t: Throwable) {
                // Per-root isolation, same rule as activation: one root that cannot come back
                // must not stop the others from coming back.
                LOG.warn("could not restart recording for $root after an identity import", t)
                failed.add(root)
                runCatching { project.service<RecorderState>().markDegraded(root, degradedReason(t)) }
                    .onFailure { LOG.warn("could not mark $root degraded after a failed restart", it) }
            }
        }
        return RestartReport(restarted, failed)
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
        /**
         * S3 rolling seal: how the session resolves its own `extension_hash`. Threaded through
         * the same [extensionHashOverride] the seal command uses, so a test that can make the
         * classic seal work can make the rolling seal work too, with one override.
         */
        computeExtensionHash: () -> String = extensionHashOverride ?: { computeInstalledExtensionHash(RECORDER_PLUGIN_ID) },
        /**
         * The keypair and identity [startFromActivation] already had to build so that chain
         * recovery could be handed a real `student_ref`. Null for the many tests that inject
         * a [RecoveryDecision] directly — those do not go through the ownership path, so the
         * controller makes its own, exactly as before.
         */
        preparedKeypair: SessionKeypair? = null,
        preparedIdentity: IdentityOutcome? = null,
        /** See [startFromActivation]'s parameter of the same name. */
        prevSessionIdOverride: String? = null,
        /**
         * SIZE ROTATION (recorder PRD §4.6): the rotation threshold for this session. Production
         * always takes the default; a test overrides it so the threshold is reachable in a
         * handful of entries. Deliberately NOT threaded through [startFromActivation], so a
         * rotation's successor always gets the production threshold and a test cannot
         * accidentally build a rotation cascade.
         */
        maxSlogBytes: Long = RecordingSessionController.ROTATE_AT_BYTES,
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
            computeExtensionHash = computeExtensionHash,
            preparedKeypair = preparedKeypair,
            preparedIdentity = preparedIdentity,
            prevSessionIdOverride = prevSessionIdOverride,
            maxSlogBytes = maxSlogBytes,
            // SIZE ROTATION (recorder PRD §4.6). Handed to a pooled thread and not awaited: this
            // callback fires from inside the controller's entry-routing path (on the checkpoint
            // cadence), which may be the EDT inside a write action, and `rotate` ends a session,
            // writes a final seal and starts a successor — none of which may happen there.
            onRotationNeeded = { endedId -> launchRotation(root, endedId) },
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
        //
        // All three steps run as ONE EDT unit (see runOnEdtAndWait). Putting the session in the
        // registry is what makes sinkFor() resolve for its files, so from that instant a write
        // action would be logged as a doc.change — and until the catch-up has run, that file has
        // no doc.open baseline for replay to apply the delta to. On the EDT no write action can
        // land in between. Everything above this point (keypair, SessionWriter, session.start) is
        // blocking IO and deliberately stays OFF the EDT.
        runOnEdtAndWait {
            sessions[root] = session
            ensureRoutedWiring(vfsDispatch)

            // Catch up doc.open for files already open under THIS root, on EVERY start() — not
            // just the first (which constructs DocWiring and runs its init-time catch-up).
            // ensureRoutedWiring short-circuits after the first session, so a later session whose
            // root already has open files would otherwise never get their doc.open baseline.
            // DocWiring's seenPaths de-dup (keyed by absolute path) makes this idempotent:
            // already-caught-up files are not re-emitted.
            routedWiring?.docWiring?.catchUpOpenFiles()
        }

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
            scope = scopeFromManifest(activated.manifest),
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
        // Now that the coordinator (and its ExpectedContentRegistry) exists, wire the
        // controller's live cap reader to it — see RecordingSessionController.scopeCappedProvider.
        controller.setScopeCappedProvider { coordinator.registry.capHit() }
        // And the save-time check the doc.save path runs before it records the save. This is the
        // "true post-save hook" ExternalChangeCoordinator.checkSavedContent was written for: the
        // trigger is DocWiring's post-write VFS listener, which owns the ordering between
        // fs.external_change and the doc.save that follows it.
        controller.setSaveObserver { rel, onDisk -> coordinator.checkSavedContent(rel, onDisk) }
    }

    // -------------------------------------------------------------------------------------
    // SIZE ROTATION (recorder PRD §4.6)
    //
    // A `submission: "git"` assignment commits `.provenance/` to a GitHub repo, and GitHub
    // refuses a push containing a file over 100 MB. So a session whose `.slog` passes
    // RecordingSessionController.ROTATE_AT_BYTES is ended and immediately succeeded by a fresh
    // session in the same scope, chained to it by `prev_session_id`.
    // -------------------------------------------------------------------------------------

    /** Roots with a rotation in flight — the re-entrancy guard for [rotate]. */
    private val rotating = ConcurrentHashMap.newKeySet<Path>()

    /**
     * Completes when the rotation in flight for a root has finished, whether it swapped or gave
     * up. Registered BEFORE the pooled task is submitted, so the task cannot complete and
     * deregister itself before the entry exists. Exists so a test can wait for the swap
     * deterministically (`PlatformTestUtil.waitForFuture` pumps the EDT queue, which the swap's
     * own EDT hops need) instead of sleeping.
     */
    private val rotationTasks = ConcurrentHashMap<Path, java.util.concurrent.CompletableFuture<Void?>>()

    /** The rotation in flight for [root], if any. Test seam; null in steady state. */
    @TestOnly
    fun rotationInFlight(root: Path): java.util.concurrent.Future<*>? = rotationTasks[root.normalize()]

    private fun launchRotation(root: Path, endedSessionId: String) {
        val normalized = root.normalize()
        val done = java.util.concurrent.CompletableFuture<Void?>()
        rotationTasks[normalized] = done
        // One-shot and bounded, not a long-lived background task, and its shutdown path is
        // [stop] rather than an interrupt: project close empties the registry first, so a
        // rotation that has not begun yet finds no session and returns. Interrupting one that HAS
        // begun could leave a session ended with no successor and no final seal, which is
        // strictly worse than letting a bounded teardown finish.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                runBlocking { rotate(normalized, endedSessionId) }
            } catch (t: Throwable) {
                // A failed rotation must never take the IDE — or the student's next keystroke —
                // with it. At worst the root stops recording, which is the same outcome (and the
                // same RecorderState story) as a failed restart.
                LOG.warn("provenance: could not rotate the session at $normalized", t)
            } finally {
                rotationTasks.remove(normalized, done)
                done.complete(null)
            }
        }
    }

    /**
     * Rotate the session at [root]: end it with `session.end{reason:"rotate"}`, seal it, then
     * start its successor linked by `prev_session_id`.
     *
     * END-THEN-START, never the reverse: the old session's doc/selection wiring is detached only
     * during its teardown, and [start] refuses a second session for a live root — starting first
     * would record one keystroke into two logs, or simply throw.
     *
     * `endSession("rotate")` is called EXPLICITLY, before [stop]. [stop] reaches teardown through
     * the session Disposable, whose hook passes `"dispose"`; `endSession` is idempotent, so the
     * explicit call wins and the Disposer's becomes a no-op. That is what puts the real reason in
     * the log instead of a shutdown-shaped one.
     *
     * [DocWiring.forgetRoot] is what makes the successor emit its own `doc.open` baselines: that
     * wiring is project-scoped, so with another root still recording it survives this rotation
     * with our files still marked seen. See its KDoc.
     *
     * @param expectedSessionId when non-null, rotate only if the live session is still the one
     *   that asked — a rotation whose root was stopped and restarted in the meantime is stale and
     *   must not end the innocent successor.
     */
    suspend fun rotate(root: Path, expectedSessionId: String? = null) {
        val normalized = root.normalize()
        if (!rotating.add(normalized)) return
        try {
            val current = sessions[normalized] ?: return
            val endedId = current.controller.sessionId
            if (expectedSessionId != null && expectedSessionId != endedId) return
            val manifest = current.activated.manifest
            current.controller.endSession("rotate")
            stop(normalized)
            routedWiring?.docWiring?.forgetRoot(normalized)
            startFromActivation(normalized, manifest, prevSessionIdOverride = endedId)
        } finally {
            rotating.remove(normalized)
        }
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
            scope = scopeFromManifest(m),
            sessionPrivkey = s.controller.sessionPrivkey,
            computeExtensionHash = computeExtensionHash,
            scopeCapped = s.controller.scopeCapped(),
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

    /**
     * Project close. Stopping every session IS the rotation shutdown path (CLAUDE.md: no
     * background task without an explicit one): [rotate] reads the registry, so a rotation that
     * has not begun yet finds nothing and returns, and one already in flight is a bounded
     * teardown+start that is better left to finish than interrupted. See [launchRotation].
     */
    override fun dispose() = stop()

    companion object {
        private val LOG = Logger.getInstance(RecorderSessionManager::class.java)
    }
}
