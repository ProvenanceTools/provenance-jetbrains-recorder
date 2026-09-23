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
import dev.provenance.core.GitCaptureCapability
import dev.provenance.core.Manifest
import dev.provenance.core.ManifestSubmission
import dev.provenance.core.RecorderDegradedPayload
import dev.provenance.core.ResolvedScope
import dev.provenance.core.SessionEndPayload
import dev.provenance.core.SessionKeypair
import dev.provenance.core.SessionResumedPayload
import dev.provenance.core.SystemClock
import dev.provenance.core.WitnessCaptureCapability
import dev.provenance.core.encryptSessionPrivkey
import dev.provenance.core.isEventKindCaptured
import dev.provenance.core.resolveCapturePolicy
import dev.provenance.core.generateSessionKeypair
import dev.provenance.core.scopeFromManifest
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
import dev.provenance.recorder.wiring.git.probeGitCapture
import dev.provenance.recorder.wiring.paste.PasteAnomalyTicker
import dev.provenance.recorder.wiring.probeWitnessCapture
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
    private val clock: Clock = SystemClock(),
    private val scheduler: FlushScheduler = DEFAULT_SCHEDULER,
    /**
     * Explicit heartbeat cadence, overriding the course's capture policy. Null (the
     * default) means "use the policy", whose own default is [Heartbeat.DEFAULT_INTERVAL_MS]
     * and which is already clamped to [5000, 120000] by `resolveCapturePolicy`.
     */
    heartbeatIntervalMs: Long? = null,
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
    /**
     * The startup chain-recovery decision for this workspace's `.provenance` dir, already
     * computed by the caller (`recoverPreviousSession`, via `NioRecoveryDeps`). This
     * controller never calls `recoverPreviousSession` itself, and must not: recovery has to
     * run AFTER this session's identity exists so it can tell our own `.slog` files from a
     * partner's, and the identity is built by the caller for exactly that reason (see
     * [preparedIdentity]). Defaults to CleanStart, for call sites with no prior session.
     */
    recovery: RecoveryDecision = RecoveryDecision.CleanStart,
    checkpointInterval: Int = CheckpointCadence.DEFAULT_INTERVAL,
    /**
     * SIZE ROTATION (recorder PRD §4.6): rotate this session once its `.slog` passes this many
     * bytes. Overridable only so a test can reach the threshold with a handful of entries
     * instead of 40 MiB of them — production always takes the default.
     */
    private val maxSlogBytes: Long = ROTATE_AT_BYTES,
    /**
     * SIZE ROTATION — THE IDLE GATE (design §3.3). Once [maxSlogBytes] is crossed the rotation is
     * ARMED, and it only fires after this many milliseconds with no CONTENT-MUTATING event
     * ([CONTENT_MUTATING_KINDS]: `doc.change`, `paste`, `fs.external_change`).
     *
     * This is not a politeness knob; it is what keeps the recorder from accusing the student.
     * End-then-start means any keystroke landing inside the predecessor's teardown window is
     * DROPPED, and the analyzer's `inter_session_external_change` compares A's side as a
     * RECONSTRUCTION FROM A'S EVENT STREAM against B's first `doc.open.content`, which is a LIVE
     * BUFFER READ, by exact string equality. So one lost character makes the two differ and the
     * heuristic reports, at confidence 0.85, that the student edited the file outside the
     * recorder — and an external write dropped the same way is a whole-file divergence, reported
     * at HIGH severity. Rotating only while the file is not changing makes "nothing changed during
     * teardown" a property of WHEN we rotate rather than a hope about how fast teardown is.
     *
     * Overridable only so a test can reach a quiet window without waiting two real seconds.
     */
    private val rotateIdleQuietMs: Long = ROTATE_IDLE_QUIET_MS,
    /**
     * SIZE ROTATION — THE HARD CEILING (design §3.3). A log this large rotates even if the
     * session has never gone quiet.
     *
     * A student who types continuously for hours would otherwise defer the rotation forever and
     * push the `.slog` past GitHub's refusal limit, i.e. make their own submission unpushable —
     * the worse outcome. **This is the only path on which a rotation can lose a KEYSTROKE** — the
     * quiet gate makes the keyboard safe and nothing else. It does NOT make an external writer
     * safe: a formatter daemon, a build tool, or a partner's `git pull` is not synchronised to the
     * student's pause, so an `fs.external_change` landing inside any teardown window is dropped
     * and produces a false `inter_session_external_change` — at HIGH severity, since an external
     * write rewrites a whole file. The gate even concentrates rotations into the moments the
     * student is idle, which is when background repo activity is most likely. Skipping chain
     * recovery (see [RecorderSessionManager.startFromActivation]) shrinks that window to a flush
     * plus a seal, but it does not close it. Stated here rather than hidden, because a reader of
     * an accusatory flag deserves to know this path exists.
     */
    private val rotateHardCeilingBytes: Long = ROTATE_HARD_CEILING_BYTES,
    /**
     * SIZE ROTATION: called ONCE, with THIS session's logical `session_id`, the first time the
     * `.slog` is over [maxSlogBytes] AND the idle gate above opens. The callee
     * ([RecorderSessionManager.rotate]) ends this session with `session.end{reason:"rotate"}`
     * and starts its successor with `prev_session_id` set to the id handed over here. Null (the
     * default) means "never rotate", which is what every test that constructs a controller
     * directly wants.
     *
     * It is invoked from inside the entry-routing path, or from the idle poll's scheduler thread,
     * so it must not block: the real callee hands the swap to a pooled thread and returns
     * immediately.
     */
    private val onRotationNeeded: ((String) -> Unit)? = null,
    /**
     * SIZE ROTATION: `session.start.prev_session_id` for this session, when the caller knows it
     * for a reason chain recovery cannot see.
     *
     * Recovery deliberately links only a DANGLING prior session (see [prevSessionIdFor] and
     * ChainRecovery.kt): a crash with no trailing `session.end`. A rotated predecessor ends
     * cleanly, so recovery reports `PreviousSessionComplete` and contributes no link — and
     * faking a dangling decision to get one would mislabel a clean end as a crash, which the
     * analyzer reads as an integrity signal. So a clean rotation states its predecessor here
     * instead, and neither RecoveryLinkage.kt nor ChainRecovery.kt changes.
     */
    prevSessionIdOverride: String? = null,
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
    /**
     * OWNERSHIP-AWARE RECOVERY ORDERING. This session's keypair and identity, when the
     * caller already had to build them.
     *
     * `recoverPreviousSession` needs this session's `student_ref` to tell our own `.slog`
     * from a partner's in a shared, committed `.provenance/` — and `student_ref` comes from
     * the identity, which countersigns the session keypair. So on the production path
     * ([RecorderSessionManager.startFromActivation]) the order is keypair → identity →
     * recovery → controller, and both results arrive here already made. Recovering with a
     * null ref instead would leave the evidence-destruction bug open, so this is not an
     * optimisation.
     *
     * Null (the default) means "make them here", which is what every test that constructs a
     * controller directly, and every caller with no prior session to recover, does.
     */
    preparedKeypair: SessionKeypair? = null,
    /** See [preparedKeypair]. Must have been built against that keypair's public half. */
    preparedIdentity: IdentityOutcome? = null,
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
     * The LIVE `scope_capped` bit, read at every seal (rolling AND classic).
     *
     * Set once by [RecorderSessionManager.wireExternalChange] right after it constructs this
     * session's [dev.provenance.recorder.watch.ExternalChangeCoordinator] — which owns the
     * [dev.provenance.recorder.state.ExpectedContentRegistry] this reads
     * [dev.provenance.recorder.state.ExpectedContentRegistry.capHit] from. That coordinator
     * does not exist yet at THIS constructor's own first rolling-seal write point (see Step
     * 5a below and [RollingSealMaintainer]), which is fine: a session with nothing tracked
     * yet cannot have capped. Defaults to "never capped" until wired.
     */
    @Volatile
    private var scopeCappedProvider: () -> Boolean = { false }

    /** Called once by [RecorderSessionManager] after the coordinator (and its registry) exist. */
    fun setScopeCappedProvider(provider: () -> Boolean) {
        scopeCappedProvider = provider
    }

    /**
     * The save-time external-change check, run by [onSaveObserved] BEFORE it records doc.save.
     *
     * Wired by [RecorderSessionManager.wireExternalChange] to this session's
     * ExternalChangeCoordinator, for the same reason [scopeCappedProvider] is: that coordinator
     * does not exist yet when this controller is constructed. Until it is wired, a save records
     * doc.save and nothing else — the correct degradation, since a session with no
     * expected-content model has no baseline to call a write external against.
     */
    @Volatile
    private var saveObserver: (String, String) -> Unit = { _, _ -> }

    /** Called once by [RecorderSessionManager], alongside [setScopeCappedProvider]. */
    fun setSaveObserver(observer: (String, String) -> Unit) {
        saveObserver = observer
    }

    /** The live cap bit, for the CLASSIC seal ([RecorderSessionManager.sealSession]). */
    fun scopeCapped(): Boolean = scopeCappedProvider()

    /**
     * PEER WITNESSING (program spec §7 mechanism 2). Drained on the checkpoint cadence and
     * once at teardown; see the two call sites below. Never null — witnessing is a floor
     * capability with no `policy.capture` key, so there is nothing to gate it on.
     */
    private val peerWatcher: PeerWatcher

    /**
     * Serializes the `ended` CHECK-AND-EMIT in [record] against the `ended` LATCH-AND-EMIT in
     * [endSession], so a session cannot be ended between an emitter's guard and its append.
     *
     * Size rotation is why this is needed now. Every other teardown either runs on the EDT (the
     * Disposer hook) or is a rare manual action (`restartSessions`), whereas rotation calls
     * `endSession` from a POOLED thread automatically — and the only way a log reaches 40 MiB is
     * a student typing fast, so the two are maximally likely to interleave. Without this lock a
     * doc.change could pass `if (ended) return`, block, and then append to a writer that has
     * since been disposed: `writer.append` throws IllegalStateException, `routeSessionEntry`
     * routes it to [DiskFullHandler.handleWriteError], and the student gets a FALSE disk-full
     * balloon and a degraded session. Landing on the other side of the window is no better — the
     * sealed log would carry an entry after `session.end`, i.e. a chain-order artifact in
     * evidence.
     *
     * Always taken BEFORE [SessionHost]'s own lock, never the reverse, so the two cannot
     * deadlock; a JVM monitor is re-entrant, so the disk-full handler's `recorder.degraded`
     * re-entry from inside `onEntry` still works. It is deliberately NOT held across [endSession]'s
     * teardown (the checkpoint drain, the writer/meta close, the final seal): once `ended` is
     * latched and `session.end` emitted inside the lock, no further entry can reach the writer,
     * so holding it longer would only stall the EDT for the drain's duration.
     */
    private val emitLock = Any()

    /** Read and written under [emitLock]; @Volatile so the teardown path's own reads are safe. */
    @Volatile
    private var ended = false

    /**
     * SIZE ROTATION: has [onRotationNeeded] already been called? Latched with a CAS, so a session
     * asks to be rotated at most once — the swap is asynchronous, so the cadence can fire again
     * (over the threshold, since the log only grows) before this session is torn down, and the
     * idle poll can fire concurrently with it on another thread. A second request would start a
     * second successor for the same root.
     */
    private val rotationRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * SIZE ROTATION — THE IDLE GATE: guards [idlePoll]'s at-most-once creation and cancellation.
     *
     * Deliberately NOT [emitLock]. The poll runs on a scheduler thread and the size check runs
     * inside the emit path, which already holds [emitLock]; making the poll take that lock would
     * put a timer in front of the student's keystrokes for no gain. This lock protects nothing
     * but the [ScheduledFuture] reference, and nothing that takes it does I/O.
     */
    private val rotationGate = Any()

    /**
     * SIZE ROTATION — THE IDLE GATE: monotonic time of the last CONTENT-MUTATING event this
     * session recorded ([CONTENT_MUTATING_KINDS]), or [Long.MIN_VALUE] if it has recorded none.
     *
     * Content-mutating, not "typing". The heuristic this gate exists to defuse compares file
     * CONTENT and does not care which event changed it, so quieting only the keyboard would leave
     * the very event class the flag is named after unguarded. See [CONTENT_MUTATING_KINDS].
     *
     * Maintained in [record] — the single funnel every emitted event passes through, for the same
     * reason the capture-policy gate lives there: a future wiring module cannot forget it. The
     * whole per-event cost is a set membership test on an interned literal and, only on a match,
     * one `clock.now()` read plus one volatile store — against an entry path that already does JCS
     * canonicalization, SHA-256 and a buffered write, so the `doc.change` p99 < 1 ms budget
     * (PRD §4.7) is not in play. (That clock read is this gate's own; it is not the one
     * [SessionHost] takes for `t`.) The IDLE COMPARISON itself never happens here; it happens on the
     * checkpoint cadence and on [idlePoll], both off the event path.
     *
     * A session that has recorded no content-mutating event at all counts as quiet: there is no
     * in-flight change to lose an edit out of.
     */
    @Volatile
    private var lastContentChangeAtMs: Long = Long.MIN_VALUE

    /**
     * SIZE ROTATION — THE IDLE GATE: has the rotation been ARMED (the size threshold crossed at
     * least once)? Latched, and deliberately NOT cleared when the poll is stopped.
     *
     * Separate from [idlePoll] precisely so that abandoning a rotation is expressible: the degraded
     * branch in [considerRotation] stops the poll while leaving this set, so nothing can ever
     * re-arm this session. `idlePoll != null` would have conflated "armed" with "a timer is
     * currently scheduled", and a stopped poll would then silently re-arm on the next cadence.
     */
    @Volatile
    private var rotationArmed = false

    /**
     * SIZE ROTATION — THE IDLE GATE: the armed rotation's quiet-window poll, or null.
     *
     * Required, not a belt-and-braces addition: the size check rides the checkpoint cadence, which
     * is driven by ENTRIES. The moment the student stops typing the entry stream all but stops
     * too (heartbeats are minutes apart), so a cadence-only re-check would notice the quiet window
     * only after ~100 further entries — i.e. potentially not for an hour, which is exactly the
     * wrong direction for a log already over 40 MiB. The poll exists so that "stopped typing" is
     * itself the trigger. Created at most once per session, cancelled by [endSession].
     */
    @Volatile
    private var idlePoll: java.util.concurrent.ScheduledFuture<*>? = null

    init {
        // Step 0: resolve the course's capture policy BEFORE anything can emit. Total by
        // construction — an absent, malformed, or out-of-range block resolves to a
        // well-defined value, so this cannot fail and cannot leave the gate undefined.
        policy = resolveCapturePolicy(activated.manifest.policy)

        Files.createDirectories(activated.provenanceDir)

        // §5.6 item 3 — witness_capture (collaboration spec, D16 context). See
        // WitnessCapabilityProbe.kt for why this probes the directory listing rather than the
        // VFS_CHANGES subscription a few steps below.
        val witnessCapture: WitnessCaptureCapability = probeWitnessCapture(activated.provenanceDir) { e ->
            LOG.warn("provenance: .provenance/ not listable; peer witnessing unavailable", e)
        }

        // §5.6 item 2 — git_capture. See GitCapabilityProbe.kt for exactly what this can and
        // cannot report on this host, and how NOT_OWNED is reached via a reflective
        // GitRepositoryManager read scoped to THIS session's own workspace root.
        val gitCapture: GitCaptureCapability? = probeGitCapture(project, activated.workspaceRoot)

        // §5.6 item 1 — file_scope, the effective resolved file set (S25). Pure; no platform
        // dependency at all.
        val fileScope = resolveFileScope(activated.manifest.filesUnderReview)

        // Step 1: session keypair. Supplied by the caller on the production path, because
        // the identity that countersigns exactly this public key has to exist BEFORE
        // startup recovery runs (see [preparedKeypair]); generated here for the many test
        // call sites that construct a controller directly.
        val keypair = preparedKeypair ?: generateSessionKeypair()
        sessionPrivkey = keypair.privateKey

        // Step 2: session.start payload. prev_session_id is set ONLY for a dangling prior
        // session (crash: no trailing session.end) — never for a cleanly-completed one, and
        // never for a corrupt one (corruption is surfaced via recorder.recovered_from_corruption
        // below, not chain linkage). Mirrors chain-recovery.ts's documented rule.
        //
        // The one exception is a SIZE ROTATION, whose predecessor ended cleanly and so is
        // invisible to recovery — it names itself through prevSessionIdOverride. See that
        // parameter's KDoc for why this is an override rather than a faked recovery decision.
        val prevSessionId = prevSessionIdOverride ?: prevSessionIdFor(recovery)

        // Step 2a: the enrollment identity, if the student has one for this course. Assembled
        // and chain-verified before it is written; a failure at ANY point here yields no
        // identity and changes nothing else about the session. Never a reason not to record.
        //
        // On the production path this was already built by the caller, BEFORE chain recovery,
        // because recovery needs its `student_ref` to tell our own `.slog` files from a
        // partner's (see [preparedIdentity]). Rebuilding it here would derive the student key
        // a second time for no benefit, so the caller's result is reused verbatim.
        val identityOutcome = preparedIdentity ?: buildSessionIdentity(
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
            // INFO, not DEBUG. This session will produce a permanently unattributed bundle, and
            // DEBUG is off by default — so the reason existed nowhere a student or a grader could
            // ever reach it. The student-facing half of the same fact is the status-bar suffix and
            // tooltip (see `EnrollNudge.identitySkipAdvice`); this is the copy staff can read out
            // of idea.log when the student reports it. Once per session start, so not chatty.
            LOG.info("provenance: session.start identity omitted: ${identityOutcome.reason}")
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
            gitCapture = gitCapture,
            witnessCapture = witnessCapture,
            fileScope = fileScope,
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
                scope = scopeFromManifest(activated.manifest),
                sessionPrivkey = keypair.privateKey,
                computeExtensionHash = computeExtensionHash,
                // A closure over THIS controller's var, not its value at construction time:
                // read live at every roll, so a cap that bites after session start (or after
                // RecorderSessionManager wires the real provider in, moments after this
                // constructor returns) is still reflected on the very next checkpoint.
                scopeCapped = { scopeCappedProvider() },
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
                // SIZE ROTATION (PRD §4.6): the size is read HERE, on the checkpoint cadence,
                // and never per appended entry — a doc.change handler must stay under 1 ms p99
                // (§4.7), and this reads a volatile counter rather than stat()ing the file for
                // the same reason. The degraded branch in routeSessionEntry returns BEFORE this
                // lambda can run, so a disk-full session never rotates: required, because
                // rotation writes two new files and seals the old one, which is precisely what
                // a session that just failed to write cannot do.
                considerRotation()
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

    /**
     * The editor finished writing [relativePath]; [onDiskContent] is what landed on disk.
     *
     * Emits at most two events, and the ORDER between them is a contract, not a detail:
     * fs.external_change first (when the write diverged from the expected-content model —
     * format-on-save, or a save racing an external write), then doc.save carrying the hash of
     * this exact content. The analyzer's save-path signature (`reconstruct-file.ts`) matches an
     * fs.external_change whose `new_hash` equals the sha256 of the doc.save that immediately
     * follows it; emitting them the other way round, or hashing a second, separately-read
     * snapshot, breaks that pairing.
     *
     * The observer is the session's ExternalChangeCoordinator, wired by
     * [RecorderSessionManager.wireExternalChange]. It is never allowed to cost us the doc.save:
     * a save the recorder does not record is a hole in the on-disk history the analyzer reads,
     * which is a worse outcome than a missing external-change annotation.
     */
    override fun onSaveObserved(relativePath: String, onDiskContent: String) {
        runCatching { saveObserver(relativePath, onDiskContent) }
            .onFailure { LOG.warn("save-time external-change check failed for $relativePath", it) }
        record(
            "doc.save",
            dev.provenance.recorder.events.buildDocSavePayload(
                relativePath,
                dev.provenance.core.Sha256.hex(onDiskContent),
            ).toJsonObject(),
        )
    }

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
    /**
     * SIZE ROTATION (design §3.2, §3.3): decide whether this session should be rotated NOW.
     *
     * Called from exactly two places, both off the `doc.change` path: the checkpoint-cadence hook
     * (which is what bounds the size read to once per [CheckpointCadence] entries) and
     * [idlePoll]. Reads a volatile byte counter and a volatile timestamp; never stats the file,
     * never takes [emitLock], never blocks.
     *
     * Three outcomes:
     *
     *  - degraded → never, and the poll is stopped;
     *  - under [maxSlogBytes] → nothing at all, not even a timer;
     *  - over it, mid-burst → ARM: start the quiet-window poll and return without rotating;
     *  - over it and quiet (or over [rotateHardCeilingBytes]) → request the rotation, once.
     */
    private fun considerRotation() {
        // No callee means "never rotate" (the default for every directly-constructed controller),
        // so there is nothing to arm and no reason to create a timer.
        if (onRotationNeeded == null) return
        if (rotationRequested.get() || ended) return

        // DEGRADED ABANDONS THE ROTATION (design §3.2). This is a real check, not a restatement
        // of the structural one, and it lives HERE — at the single point of commit that BOTH
        // triggers pass through — rather than at either call site. The cadence path was protected
        // structurally (routeSessionEntry's degraded branch returns before the checkpoint lambda
        // can run) and the poll simply was not: guarding call sites individually is how that hole
        // existed, and a third trigger added later would repeat it.
        //
        // The reachable sequence: a session crosses [maxSlogBytes] mid-burst and arms the poll
        // while healthy; the disk then fills; the student pauses to read the disk-full
        // notification — which IS the quiet window this gate waits for — and the still-live poll
        // commits with `bytesAppended` frozen above the threshold. That calls
        // `endSession("rotate")` on a session whose writes are failing, so its `session.end` is
        // ENQUEUED into the degraded ring instead of written, while teardown proceeds anyway to
        // the rolling seal's `final = true` claim — a log sealed as COMPLETE that is missing its
        // own terminal entry — and a successor starts recording onto the same full disk. A
        // falsely-`final` seal is an evidence-integrity defect, worse than anything rotation was
        // meant to solve.
        //
        // ABANDON, DO NOT DEFER. Degraded is one-way (DiskFullHandler: "no auto-recovery loop, no
        // probe timer"), so it never becomes healthy again without an IDE restart. The poll is
        // stopped and [rotationArmed] is deliberately LEFT SET, so nothing can re-arm; a deferred
        // rotation would be one that waits forever while pretending it might still happen.
        //
        // Accepted consequence, spec'd in §3.2 and identical in all three ports: a degraded
        // session's log can exceed [maxSlogBytes], and in the extreme GitHub's 50 MB warning.
        // That is the right trade — a degraded session writes almost nothing, so it barely grows,
        // and an oversized log is recoverable whereas a falsely-`final` seal is not. There is
        // deliberately NO second ceiling to compensate.
        if (diskFullHandler.degraded) {
            stopIdlePoll()
            return
        }

        val bytes = writer.bytesAppended
        if (bytes < maxSlogBytes) return

        // Armed from the first crossing, so the quiet window is being watched for even if no
        // further entry ever trips the cadence.
        armIdlePoll()

        val last = lastContentChangeAtMs
        val quiet = last == Long.MIN_VALUE || clock.now() - last >= rotateIdleQuietMs
        // THE ONE LOSSY PATH, stated plainly: past the hard ceiling we rotate mid-burst, which can
        // drop a keystroke inside the teardown window and so can produce a false
        // `inter_session_external_change` finding against the student. It is accepted only because
        // the alternative is an unpushable submission. See [rotateHardCeilingBytes].
        if (!quiet && bytes < rotateHardCeilingBytes) return

        // CAS, not a plain write: the cadence hook and the poll can reach this concurrently, and
        // two requests would start two successors for one root.
        if (!rotationRequested.compareAndSet(false, true)) return
        stopIdlePoll()
        onRotationNeeded.invoke(sessionId)
    }

    /**
     * Arm the rotation and start its quiet-window poll, at most once per session.
     *
     * Keyed on [rotationArmed], never on `idlePoll != null`: a rotation that was ABANDONED while
     * degraded has no poll but is still armed, and must not be re-armed by the next cadence.
     */
    private fun armIdlePoll() {
        synchronized(rotationGate) {
            if (rotationArmed) return
            rotationArmed = true
            // One period = one quiet window: the rotation therefore lands between one and two
            // quiet windows after the typing stops. For a log that has been growing for days,
            // paying up to two extra seconds to know the seam is empty is not a trade worth
            // tuning, and a shorter period would only add wakeups.
            idlePoll = scheduler.scheduleAtFixedRate(rotateIdleQuietMs.coerceAtLeast(1L)) {
                // A throw here would kill the scheduled task silently and strand the armed
                // rotation, so it is contained and logged.
                runCatching { considerRotation() }
                    .onFailure { LOG.warn("provenance: rotation idle poll failed", it) }
            }
        }
    }

    /**
     * Stop the quiet-window poll, leaving [rotationArmed] set so nothing re-arms. Idempotent, safe
     * after teardown, and safe to call from the poll's own thread (`cancel(false)` lets the
     * in-flight run finish and prevents every later one).
     */
    private fun stopIdlePoll() {
        synchronized(rotationGate) {
            idlePoll?.cancel(false)
            idlePoll = null
        }
    }

    private fun record(kind: String, data: kotlinx.serialization.json.JsonObject) {
        // The guard and the emit are ONE critical section — see [emitLock]. Testing `ended` and
        // then emitting non-atomically let an entry reach a disposed writer.
        synchronized(emitLock) {
            if (ended) return
            if (!isEventKindCaptured(kind, policy)) return
            // SIZE ROTATION — THE IDLE GATE (design §3.3). Updated here, for the same reason the
            // policy gate is here: this is the ONE funnel every emitted event passes through, so
            // no present or future wiring module can change file content without the gate
            // noticing. After the policy check, so the gate tracks what was actually RECORDED —
            // though all three kinds are on the capture floor, so the two orders agree (there is a
            // test pinning that).
            if (kind in CONTENT_MUTATING_KINDS) lastContentChangeAtMs = clock.now()
            host.emit(kind, data)
        }
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

        // THE LATCH, under the same lock [record]'s guard-and-emit takes — see [emitLock]. An
        // emitter that already holds it finishes its append first; one that arrives after sees
        // `ended` and drops. So by the time this block returns, no further entry can reach the
        // writer, which is what makes the teardown below (and `session.end` being the last entry)
        // safe without holding the lock across either — holding it longer would only stall
        // whichever thread is typing for the duration of the checkpoint drain and the final seal.
        //
        // The `ended` re-check inside is not redundant with the one above: two threads can reach
        // here at once now that rotation ends a session off the EDT while the Disposer hook may
        // end the same one on it, and a double `session.end` would be an entry after the log's
        // own end.
        synchronized(emitLock) {
            if (ended) return
            ended = true
        }
        try {
            host.emit("session.end", SessionEndPayload(reason).toJsonObject())
        } finally {
            // The paste privacy gate is closed by RecorderSessionManager removing this session
            // from the registry before disposal, so the path-routed resolver stops handing out
            // this session's correlator; nothing to clear here anymore.
            // SIZE ROTATION: the armed rotation's quiet-window poll, if any. Its own
            // [considerRotation] would return on the `ended` guard anyway, but a background task
            // with no shutdown path is not something this codebase leaves lying around.
            stopIdlePoll()
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

    /**
     * THIS session's real disk-full handler, for tests that need to drive the degraded transition.
     *
     * Read-only, and deliberately the real instance rather than an injected stand-in: a test that
     * supplied its own handler would lose the `onDegraded` → `recorder.degraded` wiring built in
     * the constructor, and so would be testing a different object than production runs. There is
     * no other way in: degradation is reached through a write failure on an already-open
     * FileChannel, which a unit test cannot provoke.
     */
    val diskFullHandlerForTest: DiskFullHandler
        @org.jetbrains.annotations.TestOnly get() = diskFullHandler

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

        /**
         * Rotate a session once its `.slog` passes this size (recorder PRD §4.6).
         * `submission: "git"` assignments commit `.provenance/` to a GitHub repo; GitHub warns
         * at 50 MB and REFUSES a push containing a file over 100 MB, so a single long-lived
         * session's log could otherwise make a student's submission unpushable.
         */
        const val ROTATE_AT_BYTES: Long = 40L * 1024 * 1024

        /**
         * How long a session must have recorded no content-mutating event before an armed
         * rotation fires
         * (design §3.3). Students pause constantly, so in practice this costs nothing; what it
         * buys is an EMPTY SEAM, and an empty seam is what keeps a rotation from being read as
         * the student editing the file outside the recorder. See [rotateIdleQuietMs].
         */
        const val ROTATE_IDLE_QUIET_MS: Long = 2000

        /**
         * The events the idle gate treats as "the file just changed" (design §3.3).
         *
         * **Why these three.** The gate exists to keep a rotation seam empty, and the thing it is
         * protecting against — `inter_session_external_change` — compares session A's
         * RECONSTRUCTED content against session B's live `doc.open` buffer read, by exact string
         * equality. It therefore does not care WHICH event changed the content:
         *
         *  - `doc.change` — typing, the obvious case;
         *  - `paste` — a single-shot paste is its own event kind in this recorder, so a student who
         *    pauses to read documentation (legitimately opening the gate) and then hits paste as
         *    the rotation fires would have that paste dropped. A paste is large, so the resulting
         *    divergence clears `highSeverityCharsChanged` and the false finding is reported at
         *    HIGH severity;
         *  - `fs.external_change` — a formatter-on-save or a `git checkout` is a whole-file
         *    rewrite, i.e. the same false finding and worse. Quieting only the keyboard would leave
         *    unguarded exactly the event class the flag is named after. These are rare, so
         *    resetting on them costs nothing in practice.
         *
         * **Why NOT the others, and this matters more than it looks.** `session.heartbeat` fires on
         * a timer whether or not the student is present, so admitting it would mean an idle session
         * NEVER reaches a quiet window — which would silently make the hard ceiling the only
         * rotation path there is, i.e. would convert the one lossy path from an exception into the
         * rule. `doc.save` writes content that was already recorded by the `doc.change`s that
         * produced it, and `doc.open` is a baseline READ, not a mutation; `selection.change`,
         * `focus.change`, `git.event`, `terminal.*` and the `recorder.*` kinds do not touch file
         * content at all. Adding a kind here is safe only if it can change a file's bytes.
         */
        val CONTENT_MUTATING_KINDS: Set<String> = setOf("doc.change", "paste", "fs.external_change")

        /**
         * The size at which a rotation stops waiting for a quiet window (design §3.3). A
         * continuous-typing session must not grow without bound — an unpushable repo is the worse
         * outcome — and this is the one rotation path that can still drop an edit. See
         * [rotateHardCeilingBytes].
         */
        const val ROTATE_HARD_CEILING_BYTES: Long = 48L * 1024 * 1024

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
    private val scope: ResolvedScope,
    private val sessionPrivkey: ByteArray,
    private val computeExtensionHash: () -> String,
    /** Read live at every [roll] — see [RecordingSessionController.scopeCappedProvider]. */
    private val scopeCapped: () -> Boolean,
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
                scope = scope,
                sessionPrivkey = sessionPrivkey,
                extensionHash = hash,
                isFinal = isFinal,
                scopeCapped = scopeCapped(),
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
