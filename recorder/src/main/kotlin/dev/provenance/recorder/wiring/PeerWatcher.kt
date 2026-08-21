package dev.provenance.recorder.wiring

import dev.provenance.core.ParseResult
import dev.provenance.core.PeerObservedPayload
import dev.provenance.core.PeerObservedState
import dev.provenance.core.Sha256
import dev.provenance.core.parseEntries
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * PEER WITNESSING, write side (program spec §7 mechanism 2, collaboration spec §5.5, Tier 4.1).
 *
 * In a shared repository a `git pull` drops a partner's `.slog` into `.provenance/`. This
 * module notices, hashes it, reads its chain tip, and writes what it saw into **this**
 * recorder's own signed chain as a `peer.observed` entry. Deleting a partner's log then leaves
 * your own chain testifying that it existed, and hiding that means destroying both chains —
 * which yields a submission with no provenance at all, the loudest possible signal.
 *
 * The reader half is `core`'s `PeerObserved.kt` (the narrowing) and the monorepo's
 * `analysis-core/witness/reconcile-witnesses.ts` (the five verdicts). The writer contract this
 * module implements is pinned in the monorepo's
 * `docs/superpowers/specs/2026-08-19-program-decision-log.md`, "The writer contract — what the
 * three recorders must emit", **including the seven corrections from the VS Code
 * implementation**. Every one of them is honoured below and named where it applies; three
 * recorders describing one event three different ways is exactly the divergence the shared
 * vectors exist to prevent.
 *
 * ## 1. One watcher on the directory, not one per file
 *
 * A partner's filename is not knowable in advance — it is a uuid minted on their machine — so
 * a per-file watcher is not even expressible here. One listener on `.provenance/` is also the
 * only shape that sees a file APPEAR, which is the whole point.
 *
 * **provjet deviation, deliberate: the drain also SWEEPS the directory.** IntelliJ's VFS is a
 * *cached* layer that refreshes on window focus, so a `git pull` run in an external terminal
 * produces no VFS event at all until something triggers a refresh — the same cached-layer
 * hazard `docs/design.md` §4.5 calls the highest-risk item in this port, and the reason
 * `SaveTimeExternalChangeChecker` exists. A watcher-only port would therefore silently witness
 * nothing for the single most common way a partner's log arrives. So [enqueue] is the
 * promptness signal and [PeerFiles.list] is the source of truth, and they feed ONE queue
 * drained at ONE point. The sweep costs one directory listing per checkpoint and cannot
 * produce a duplicate observation, because an unchanged file emits nothing (correction 2).
 *
 * ## 2. Callbacks enqueue a name and return
 *
 * No I/O, no hashing, no parsing on the callback. The handler budget is <1 ms p99 (PRD §4.7),
 * and hashing a partner's multi-megabyte log on a VFS callback would blow it on the one path a
 * student notices. [enqueue] is two string comparisons and a `Set.add`.
 *
 * ## 3. The drain runs on the checkpoint cadence, plus once at teardown
 *
 * Same cadence, and the same serialized chain, as the rolling seal. **Correction 6: no
 * timer.** The contract's "or a timer, whichever is later" reads backwards — running both
 * gives whichever is *sooner* — so this follows VS Code: checkpoint plus dispose, and nothing
 * else. A long-idle session delays witnessing but never loses it, because teardown always
 * drains. Because the queue is a SET of filenames, a file touched fifty times between two
 * drains yields exactly one observation: the rate limit is structural rather than a timer.
 *
 * ## 4. This recorder's own files are excluded by path
 *
 * A chain cannot corroborate itself. `reconcileWitnesses` excludes a self-witness anyway, but
 * a recorder must not produce one: an excluded witness is noise in a staff-facing count, and
 * relying on the reader to clean up after the writer is how the two halves drift.
 *
 * ## 5. A FOREIGN FILE IS NEVER TOUCHED
 *
 * List, read and hash is the ENTIRE interaction. This module never renames, rewrites, moves,
 * truncates or deletes anything, and it holds no write-capable handle: [PeerFiles] declares
 * `list` and `read` and nothing else, so a write is unreachable rather than merely unwritten.
 * [PeerObservedState.UNPARSEABLE] is the complete response to a log that cannot be read.
 *
 * That is not a stylistic preference. Decision-log bug 2 was a startup recovery that
 * quarantined — renamed — a partner's log with no ownership check, which in a shared repo
 * destroys the victim's evidence and makes git blame them for it, and hands an attacker a way
 * to delete a partner's log by corrupting one byte of it. Watching a directory full of other
 * students' evidence is the second place that mistake could be made. It is not made here.
 *
 * ## 6. Nulls are emitted explicitly, never omitted
 *
 * `session_id` / `seq_high` / `last_hash` are the three values read out of the foreign chain,
 * and they are all-null together or all-non-null together. They are always PRESENT as keys —
 * enforced by `PeerObservedPayload.toJsonObject`, which cannot omit them — because an omitted
 * key and a `null` value canonicalize differently and therefore chain to different hashes.
 *
 * ## 7. `seq_high: 0` is a real value
 *
 * A foreign log holding only its `session.start` has `seqHigh == 0`. Every comparison here is
 * against `null` explicitly; a truthiness check would turn the shortest honest witness into a
 * malformed one.
 *
 * ## 9. `state` is descriptive, never a verdict
 *
 * [PeerObservedState.DISAPPEARED] is NOT misconduct — a `git checkout` of a branch without the
 * partner's `.slog`, or a `git stash`, removes it from the working tree — and it carries the
 * LAST state seen, which is exactly what makes the observation evidentiary. Nothing here
 * scores, flags or ranks anything, and nothing it emits names a person: a witness names a FILE
 * and a CHAIN POSITION. There is no student ref, no key, no git author and no path outside
 * `.provenance/` in the payload, and there must never be.
 *
 * ## Failure is always silent degradation
 *
 * Recording matters more than witnessing. Every read error, parse failure and unexpected
 * throwable ends as "no observation for that file this round". [drain] never throws.
 */

/** Why a read of a foreign file did not produce bytes. */
enum class ForeignReadFailure {
    /**
     * The file is not there. A checkout or a stash. The ONLY failure that may become
     * [PeerObservedState.DISAPPEARED].
     */
    GONE,

    /**
     * Everything else — a permission error, an I/O error, a file locked by another process.
     *
     * **Correction 4: this is a fact about THIS machine, not about the partner's file.**
     * Turning it into a `disappeared` observation would put a claim about somebody else's
     * artifact into a signed chain on the strength of a local failure.
     */
    UNREADABLE,
}

/** The result of reading one foreign log. */
sealed interface ForeignLogRead {
    data class Bytes(val bytes: ByteArray) : ForeignLogRead {
        // ByteArray in a data class: identity equals is fine here (nothing compares these),
        // but override so a careless `==` is not silently reference equality.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class Failed(val reason: ForeignReadFailure) : ForeignLogRead
}

/**
 * The read-only view of `.provenance/` this watcher is given.
 *
 * **Rule 5, structurally.** There is no rename, no write, no delete on this interface, so a
 * foreign file cannot be modified by any code path that goes through it — and every code path
 * here goes through it.
 */
interface PeerFiles {
    /** Basenames of the regular files currently in `.provenance/`. Empty on any failure. */
    fun list(): List<String>

    /** The exact bytes of one file by basename, or why they could not be read. */
    fun read(name: String): ForeignLogRead
}

/** The production [PeerFiles]: plain NIO reads, no VFS, no write capability. */
class NioPeerFiles(private val provenanceDir: Path) : PeerFiles {
    override fun list(): List<String> = try {
        Files.list(provenanceDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { it.fileName.toString() }
                .toList()
        }
    } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        // A `.provenance/` that cannot be listed costs witnessing, never recording.
        emptyList()
    }

    override fun read(name: String): ForeignLogRead = try {
        ForeignLogRead.Bytes(Files.readAllBytes(provenanceDir.resolve(name)))
    } catch (e: NoSuchFileException) {
        ForeignLogRead.Failed(ForeignReadFailure.GONE)
    } catch (e: NotDirectoryException) {
        ForeignLogRead.Failed(ForeignReadFailure.GONE)
    } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        // Correction 4: EACCES, EIO, a Windows sharing violation — all say something about
        // this machine, not about the partner's file.
        ForeignLogRead.Failed(ForeignReadFailure.UNREADABLE)
    }
}

/**
 * Only `.slog` files are witnessed.
 *
 * `.slog.meta` carries the session key and the checkpoints, not the chain a witness commits
 * to, and the payload's `session_id` / `seq_high` / `last_hash` are by definition reads of a
 * `.slog`. `manifest-<id>.json` / `.sig` are the rolling seal, which the loader already
 * reconciles against the logs present. Note `endsWith(".slog")` is false for `foo.slog.meta`,
 * which is the intent, and false for a quarantined `foo.slog.corrupt-<ISO>`, which is also the
 * intent — those bytes are not a chain.
 */
fun isWitnessableLogName(basename: String): Boolean = basename.endsWith(".slog")

/** What a foreign log's chain says about itself. All three values, or none. */
data class ForeignChainTip(
    val sessionId: String?,
    val seqHigh: Long?,
    val lastHash: String?,
) {
    companion object {
        /** "The chain was not read." Rule 6's all-null case. */
        val UNREAD = ForeignChainTip(null, null, null)
    }
}

/**
 * Read a foreign log's logical session id, highest `seq`, and the hash at that `seq`.
 *
 * All three or none — rule 6, and the reader's `partially_parsed` rejection. A log that parses
 * but carries no `session.start`, or whose `session_id` is not a non-empty string, cannot be
 * named, so the honest answer is that the chain was not read.
 *
 * Exported so a test can drive REAL recorder-produced logs through it rather than hand-built
 * ones.
 */
fun readForeignChainTip(text: String): ForeignChainTip {
    val parsed = parseEntries(text)
    if (parsed !is ParseResult.Ok || parsed.entries.isEmpty()) return ForeignChainTip.UNREAD

    var sessionId: String? = null
    var seqHigh: Long? = null
    var lastHash: String? = null

    for (entry in parsed.entries) {
        if (entry.kind == "session.start") {
            val id = entry.data["session_id"]
            if (id is JsonPrimitive && id.isString && id.content.isNotEmpty()) {
                sessionId = id.content
            }
        }
        // `>` against an explicit null check, never truthiness: seq 0 is a real seq.
        if (seqHigh == null || entry.seq > seqHigh) {
            seqHigh = entry.seq
            lastHash = entry.hash
        }
    }

    if (sessionId == null || seqHigh == null || lastHash == null) return ForeignChainTip.UNREAD
    return ForeignChainTip(sessionId, seqHigh, lastHash)
}

/** The last observation this session made of one foreign file. */
private data class LastSeen(
    val sha256: String,
    val bytes: Long,
    val tip: ForeignChainTip,
    /** False once a `disappeared` has been emitted, so it is emitted only once. */
    val present: Boolean,
)

/**
 * Watches `.provenance/` for other contributors' logs and emits `peer.observed`.
 *
 * Construct it with a [PeerFiles] (read-only by construction), an `isOwnFile` predicate over
 * BASENAMES, and an `emit` that MUST be synchronous — in production it is
 * `RecordingSessionController.append`, which reaches `SessionHost.emit`: that reads `prevHash`,
 * chains, and advances `seq` under a lock, with no suspension inside it.
 */
class PeerWatcher(
    private val files: PeerFiles,
    /**
     * True for a file this recorder writes itself, by BASENAME (rule 4).
     *
     * Supplied by the caller because only the session knows its own `.slog` filename uuid,
     * which is minted independently of the logical session id — the two id spaces decision-log
     * bugs 10 and 12 were both about.
     */
    private val isOwnFile: (String) -> Boolean,
    private val emit: (PeerObservedPayload) -> Unit,
    /** Reports a failure to the session's existing degradation path. Never throws. */
    private val onError: (Throwable) -> Unit = {},
) {
    /**
     * Basenames seen since the last drain. A SET, which is the rate limit: at most one
     * observation per file per drain no matter how many events arrived.
     */
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val lastSeen = ConcurrentHashMap<String, LastSeen>()

    @Volatile
    private var disposed = false

    /** Serializes drains against each other, so two never interleave their reads. */
    private val drainLock = Any()

    /**
     * THE WATCHER CALLBACK. Rule 2: this is the whole of it — two string comparisons and a
     * `Set.add`. No I/O, no hashing, no parsing, and nothing that can throw into the platform.
     *
     * Takes a basename, so nothing about a VFS path shape reaches the payload.
     */
    fun enqueue(basename: String) {
        if (disposed) return
        if (!isWitnessableLogName(basename)) return
        if (isOwnFile(basename)) return // rule 4 — never witness ourselves
        queued.add(basename)
    }

    /**
     * Observe every file queued or currently present, and emit one `peer.observed` for each
     * that has something new to say.
     *
     * Never throws. Serialized against itself, so a checkpoint drain landing while teardown
     * drains runs one after the other rather than interleaving.
     */
    fun drain() {
        if (disposed) return
        try {
            synchronized(drainLock) { drainOnce() }
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            // Witnessing is best effort and must never take a session with it.
            onError(t)
        }
    }

    /** Forget everything and stop accepting work. Idempotent. */
    fun dispose() {
        disposed = true
        queued.clear()
        lastSeen.clear()
    }

    private fun drainOnce() {
        // THE SWEEP. See the class docstring: on JetBrains the VFS is a cached layer, so the
        // directory listing — not the VFS event stream — is the source of truth for what is
        // there. Previously-seen names are re-enqueued too, because that is how a file removed
        // between drains reaches `disappeared` at all.
        for (name in files.list()) {
            enqueue(name)
        }
        for (name in lastSeen.keys) {
            enqueue(name)
        }

        // Snapshot and clear FIRST, so names arriving during the reads below are queued for the
        // NEXT drain rather than lost or observed twice. Sorted so a drain of several files
        // emits in a deterministic order.
        val batch = queued.toList().sorted()
        queued.removeAll(batch.toSet())
        if (batch.isEmpty()) return

        val payloads = ArrayList<PeerObservedPayload>(batch.size)
        for (name in batch) {
            try {
                observe(name)?.let { payloads.add(it) }
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
                // Best effort, PER FILE: one unreadable log must not cost the others.
                onError(t)
            }
        }

        if (disposed) return

        // THE CHAIN-ADVANCE SEAM. Every read, hash and parse is above this line; this loop
        // does no I/O at all and contains no suspension point. `SessionHost.emit` reads
        // `prevHash`, chains, and advances `seq`/`prevHash` under its own lock, and the
        // SessionHost concurrency fix is what makes that safe against the other emitters — the
        // heartbeat, the document path, the git executor. Introducing an I/O step or an await
        // INTO this loop would put a slow operation between two chain advances, which is the
        // shape that manufactures `chain_integrity` and `seq_gaps` findings against innocent
        // students. Pinned by PeerWatcherTest's interleaving case.
        for (payload in payloads) {
            emit(payload)
        }
    }

    /**
     * Observe ONE file. Returns the payload to emit, or null when there is nothing new — or
     * nothing honest — to say.
     *
     * Everything asynchronous or I/O-bound happens here, and nothing here emits. That split is
     * what keeps the chain advance clean; see [drainOnce].
     */
    private fun observe(name: String): PeerObservedPayload? {
        val previous = lastSeen[name]

        when (val read = files.read(name)) {
            is ForeignLogRead.Failed -> {
                // CORRECTION 4: a local read failure is not a fact about a partner's file.
                if (read.reason != ForeignReadFailure.GONE) return null
                // CORRECTION 3: `disappeared` carries the LAST STATE SEEN, so it requires a
                // prior observation. With none there is no honest digest to report, and
                // inventing one would be manufacturing evidence about a third party.
                if (previous == null || !previous.present) return null

                lastSeen[name] = previous.copy(present = false)
                return PeerObservedPayload(
                    file = name,
                    sha256 = previous.sha256,
                    bytes = previous.bytes,
                    sessionId = previous.tip.sessionId,
                    seqHigh = previous.tip.seqHigh,
                    lastHash = previous.tip.lastHash,
                    state = PeerObservedState.DISAPPEARED,
                )
            }

            is ForeignLogRead.Bytes -> {
                val digest = Sha256.hex(read.bytes)
                val size = read.bytes.size.toLong()

                // CORRECTION 2: unchanged bytes say nothing new. Skipping is the rate limit
                // doing its job — and it consumes no `seq`, because nothing is chained, so the
                // chain stays contiguous and validation check 4 sees no hole.
                if (previous != null && previous.present && previous.sha256 == digest) return null

                val tip = try {
                    readForeignChainTip(read.bytes.toString(Charsets.UTF_8))
                } catch (t: Throwable) {
                    if (t is VirtualMachineError) throw t
                    ForeignChainTip.UNREAD
                }

                // CORRECTION 5: `unparseable` REQUIRES all three chain fields null, and every
                // unreadable chain is routed HERE. Emitting `grew` with all-nulls would pass
                // the reader's narrowing while violating its intent — the state and the nulls
                // must agree, not merely be individually legal.
                val state = if (tip.sessionId == null) {
                    PeerObservedState.UNPARSEABLE
                } else {
                    stateFor(previous, size)
                }

                lastSeen[name] = LastSeen(digest, size, tip, present = true)

                return PeerObservedPayload(
                    file = name,
                    sha256 = digest,
                    bytes = size,
                    sessionId = tip.sessionId,
                    seqHigh = tip.seqHigh,
                    lastHash = tip.lastHash,
                    state = state,
                )
            }
        }
    }

    /**
     * The state this observation describes, given the previous one.
     *
     * **CORRECTION 1.** The five states do not partition reality: a rewrite that leaves the
     * length unchanged is neither `grew` nor `shrank`. It is reported as `grew`, with `bytes`
     * emitted alongside so a reader can see the length did not change. `shrank` is described in
     * the format's own notes as "catches a truncation", so reaching for it on a same-length
     * rewrite would lean a DESCRIPTIVE field toward accusation. VS Code made this choice; this
     * port makes the identical one, because three recorders describing one event three ways is
     * the divergence the shared vectors exist to prevent.
     */
    private fun stateFor(previous: LastSeen?, bytes: Long): PeerObservedState {
        if (previous == null || !previous.present) return PeerObservedState.APPEARED
        return if (bytes < previous.bytes) PeerObservedState.SHRANK else PeerObservedState.GREW
    }
}
