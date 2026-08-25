package dev.provenance.recorder.commands

import dev.provenance.core.HashedEnvelope
import dev.provenance.core.parseRollingManifestFilename
import kotlinx.serialization.json.JsonPrimitive

/**
 * THE ORPHAN GUARD — "what goes in the zip must be openable".
 *
 * `analysis-core`'s loader does not grade a bundle session by session. It pairs
 * `session-<uuid>.slog` with `session-<uuid>.slog.meta` by filename and rejects THE WHOLE
 * BUNDLE if either half is missing (`orphaned_meta` / `orphaned_slog`) before a single
 * validation check runs; it reports `first_event_not_session_start` (actualKind `none`) for a
 * `.slog` with no bytes in it; and `reconcileRollingSealsWithSessions` fails check 1
 * (`manifest_sig`) for the whole bundle on a `manifest-<id>.json` whose session is not
 * present (`no_session_log`), because the pubkey that would verify it lives in that session's
 * own `session.start`.
 *
 * So ONE stray file costs a student every session they recorded. That blast radius is what
 * makes this a guard rather than a tidy-up.
 *
 * ## The strays are real, not hypothetical
 *
 *  * **A zero-byte `.slog`.** `SessionWriter.open` creates the file eagerly with
 *    `CREATE|APPEND`, and `BufferPolicy` will not flush a single small entry — so between
 *    `session.start` and the first flush the log exists and is empty. A session killed in
 *    that window (a crash, a power cut, a force-quit) leaves exactly that behind.
 *  * **An orphaned `.slog.meta`.** `ChainRecovery` quarantines a damaged `.slog` to
 *    `.corrupt-<ts>`, which the zip step excludes, and leaves the `.slog.meta` under its
 *    original name. The salvage path itself produced an unopenable bundle.
 *  * **An orphaned `manifest-<id>.json` / `.sig`.** The rolling seal is a THIRD per-session
 *    artifact, written EAGERLY at write point 1 — after `session.start` is emitted, before
 *    the first flush — and rewritten at every checkpoint and at teardown. It therefore
 *    outlives every reason the two rules above have for dropping a session, including the
 *    quarantine path, where no unflushed session is involved at all.
 *
 * ## The rule
 *
 * An artifact the analyzer could not open is **dropped from the ZIP and reported**. Never an
 * abort: a seal that dies costs a whole submission, a student cannot fix this at 11pm, and
 * the analyzer detects problems from the evidence that IS there. Never silent either: every
 * drop surfaces as a warning at the seal call site, so a student learns that a session was
 * left out here rather than in an integrity meeting.
 *
 * A dropped `.slog` leaves the SIGNED MANIFEST too. Those two must agree — a manifest naming
 * a session whose file is absent is just another way to make the bundle unopenable.
 *
 * **Drop from the ZIP only.** Nothing here deletes or renames anything on disk. The on-disk
 * `.provenance/` is exactly what a git-submitted repo is read from, so removing a rolling
 * seal there would destroy the only seal that submission has; and in a shared repo the file
 * may be a partner's evidence. A partner's live session is packed WITH its seal, because
 * their `.slog`, `.slog.meta` and `manifest-<id>.json` all pair up.
 *
 * **Dropping cannot manufacture a finding.** `unsealed_session` is reported only for a
 * session whose log IS in the bundle and has no seal covering it. This guard only ever drops
 * a seal whose session's log is NOT packed, so no packed session loses its cover — in the
 * classic shape (where `manifest.json` covers everything anyway) or the git shape.
 *
 * Pure: name lists in, name lists out, one injected size lookup. Both the seal command and
 * the cross-implementation gate's git-submission packer go through these functions, so the
 * gate cannot drift from what ships.
 */

/** True for the two per-session log filenames the loader pairs by name. */
private fun isSessionFile(name: String): Boolean = name.endsWith(".slog") || name.endsWith(".slog.meta")

/** Files that were never part of a submission: staging writes and quarantined logs. */
private fun isExcludedByName(name: String): Boolean = name.endsWith(".tmp") || name.contains(".corrupt-")

/**
 * The LOGICAL session id: `session.start.data.session_id`.
 *
 * NOT the `.slog` filename's uuid. They are two different things — the analyzer reconciles a
 * rolling manifest against the ids it parses out of `session.start`, and the manifest's own
 * `sessions[].session_id` comes from here too — so this is the only id a caller may key on.
 * One function, so the seal and the gate cannot key on different ones.
 */
internal fun logicalSessionIdOf(entries: List<HashedEnvelope>): String? {
    val first = entries.firstOrNull() ?: return null
    if (first.kind != "session.start") return null
    return strOrNull(first.data["session_id"])
}

/** The `prev_session_id` beside it, read from the same entry under the same rules. */
internal fun prevSessionIdOf(entries: List<HashedEnvelope>): String? {
    val first = entries.firstOrNull() ?: return null
    if (first.kind != "session.start") return null
    return strOrNull(first.data["prev_session_id"])
}

private fun strOrNull(elem: kotlinx.serialization.json.JsonElement?): String? {
    val prim = elem as? JsonPrimitive ?: return null
    if (!prim.isString) return null
    return prim.content
}

/** Which session logs may be packed, and what had to be left behind to get there. */
internal data class PackableSessions(
    /** `.slog` names, sorted: paired with a `.slog.meta` and carrying at least one byte. */
    val slogNames: List<String>,
    /** Both halves of every packable session — the zip filter's allow-list for session files. */
    val names: Set<String>,
    /** A `.slog` with no `.slog.meta` beside it. */
    val orphanedSlog: Boolean,
    /** A `.slog.meta` whose `.slog` is absent, or was quarantined out from under it. */
    val orphanedMeta: Boolean,
    /** A `.slog` of zero bytes — a session that started and never flushed. */
    val emptySession: Boolean,
)

/**
 * Decide which sessions this bundle can carry.
 *
 * @param entryNames every entry name in `.provenance/`, in any order.
 * @param sizeOf byte size of one entry by name, or a negative number when it cannot be
 *   determined. Unknown is deliberately NOT treated as empty: dropping a session because we
 *   failed to stat it would discard a student's evidence over a transient IO error, and the
 *   caller's own read step fails loudly on a file it genuinely cannot read.
 */
internal fun selectPackableSessions(entryNames: Collection<String>, sizeOf: (String) -> Long): PackableSessions {
    val present = entryNames.toHashSet()
    var orphanedSlog = false
    var emptySession = false
    val slogNames = ArrayList<String>()

    for (name in entryNames.sorted()) {
        if (!name.endsWith(".slog") || isExcludedByName(name)) continue
        when {
            !present.contains("$name.meta") -> orphanedSlog = true
            sizeOf(name) == 0L -> emptySession = true
            else -> slogNames.add(name)
        }
    }

    val packable = HashSet<String>(slogNames.size * 2)
    for (name in slogNames) {
        packable.add(name)
        packable.add("$name.meta")
    }

    // Anything left claiming to be a session's meta half is unpaired: its `.slog` is gone, was
    // quarantined, or was itself dropped just above.
    var orphanedMeta = false
    for (name in entryNames) {
        if (name.endsWith(".slog.meta") && !isExcludedByName(name) && !packable.contains(name)) orphanedMeta = true
    }

    return PackableSessions(slogNames, packable, orphanedSlog, orphanedMeta, emptySession)
}

/** The zip's entry list, and whether a rolling seal had to be left out to produce it. */
internal data class ZipSelection(val names: List<String>, val orphanedRollingSeal: Boolean)

/**
 * Filter `.provenance/` down to what may go in the archive.
 *
 * @param entryNames every entry name in `.provenance/` (after the classic manifest has been
 *   written, if this bundle has one).
 * @param packableSessionFiles [PackableSessions.names] — session files not in it are dropped.
 * @param packedSessionIds the LOGICAL ids of the sessions actually being packed. A rolling
 *   manifest naming anything else seals a recording this bundle does not carry.
 */
internal fun selectZipEntries(
    entryNames: Collection<String>,
    packableSessionFiles: Set<String>,
    packedSessionIds: Set<String>,
): ZipSelection {
    val names = ArrayList<String>(entryNames.size)
    var orphanedRollingSeal = false

    for (name in entryNames.sorted()) {
        if (isExcludedByName(name)) continue
        if (isSessionFile(name) && !packableSessionFiles.contains(name)) continue

        // Null for `manifest.json` / `manifest.sig` — the CLASSIC seal, always packed — and for
        // every other name. Each half of a rolling pair matches independently and so is judged
        // on its own pass, which is what keeps the pair consistent: a `.sig` without its `.json`
        // vouches for nothing, and a `.json` without its `.sig` is an unsigned claim.
        val rolling = parseRollingManifestFilename(name)
        if (rolling != null && !packedSessionIds.contains(rolling.sessionId)) {
            orphanedRollingSeal = true
            continue
        }

        names.add(name)
    }

    return ZipSelection(names, orphanedRollingSeal)
}
