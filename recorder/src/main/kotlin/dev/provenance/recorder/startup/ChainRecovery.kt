package dev.provenance.recorder.startup

import dev.provenance.core.ChainCheck
import dev.provenance.core.ParseResult
import dev.provenance.core.parseEntries
import dev.provenance.core.validateChain
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * Startup chain recovery — pure decision logic behind an injected filesystem seam.
 *
 * Direct 1:1 port of provenance/packages/recorder/src/startup/chain-recovery.ts. That
 * file's header comment is the authoritative rationale for three decisions honored here:
 *   1. Multiple .slog files → take the alphabetically last one (any deterministic
 *      tie-break suffices; alphabetical-last is simple, deterministic, testable, and
 *      avoids the TOCTOU risk of mtime stat() calls).
 *   2. prev_session_id linkage is set ONLY for a dangling prior session (crash: no
 *      trailing session.end). A cleanly-completed prior session is not linked; a corrupt
 *      one is surfaced via quarantine + recorder.recovered_from_corruption, never linkage.
 *   3. Corruption does NOT emit chain.broken. That kind stays reserved for a live session
 *      detecting its own chain breaking mid-stream. Recovery quarantines the file
 *      (`<slog>.corrupt-<ISO>`) and reports the quarantined path.
 * Do not deviate from that rationale.
 */

sealed interface RecoveryDecision {
    data object CleanStart : RecoveryDecision
    data class PreviousSessionComplete(val prevSessionId: String) : RecoveryDecision
    data class PreviousSessionDangling(val prevSessionId: String, val danglingPath: String) : RecoveryDecision
    data class PreviousSessionCorrupt(val quarantinedPath: String) : RecoveryDecision
}

sealed interface SlogReadResult {
    data class Ok(val text: String) : SlogReadResult

    /** reason is "not_found" | "read_error". */
    data class Err(val reason: String) : SlogReadResult
}

/**
 * Injection seam — the real java.nio-backed implementation is NioRecoveryDeps. Everything
 * in recoverPreviousSession is testable with an in-memory fake, without touching disk.
 */
interface RecoveryDeps {
    val provenanceDir: String

    suspend fun readSlogFile(path: String): SlogReadResult

    suspend fun rename(from: String, to: String)

    suspend fun listSlogFiles(dir: String): List<String>

    fun now(): Instant
}

private fun quarantinePath(slogPath: String, now: Instant): String =
    "$slogPath.corrupt-${now.toString().replace(Regex("[:.]"), "-")}"

/**
 * Inspect provenanceDir for a previous session and return a recovery decision.
 * Side effect: if the chain is invalid (or unreadable/unparsable/malformed-header), renames
 * the .slog to `<slog>.corrupt-<ISO>` (quarantine) before returning PreviousSessionCorrupt.
 */
suspend fun recoverPreviousSession(deps: RecoveryDeps): RecoveryDecision {
    val slogFiles = deps.listSlogFiles(deps.provenanceDir).filter { it.endsWith(".slog") }.sorted()
    if (slogFiles.isEmpty()) return RecoveryDecision.CleanStart

    // Alphabetically last — see chain-recovery.ts header comment for the tie-break rationale.
    val slogPath = "${deps.provenanceDir}/${slogFiles.last()}"

    suspend fun quarantine(): RecoveryDecision.PreviousSessionCorrupt {
        val quarantined = quarantinePath(slogPath, deps.now())
        deps.rename(slogPath, quarantined)
        return RecoveryDecision.PreviousSessionCorrupt(quarantined)
    }

    val readResult = deps.readSlogFile(slogPath)
    if (readResult !is SlogReadResult.Ok) return quarantine()

    val parseResult = parseEntries(readResult.text)
    if (parseResult !is ParseResult.Ok) return quarantine()

    val entries = parseResult.entries
    if (validateChain(entries) !is ChainCheck.Valid) return quarantine()

    val first = entries.firstOrNull() ?: return quarantine()
    if (first.kind != "session.start") return quarantine()
    val prevSessionId = first.data["session_id"]?.jsonPrimitive?.contentOrNull ?: return quarantine()

    val last = entries.lastOrNull()
    val isComplete = last != null && last.kind == "session.end"

    return if (isComplete) {
        RecoveryDecision.PreviousSessionComplete(prevSessionId)
    } else {
        RecoveryDecision.PreviousSessionDangling(prevSessionId, slogPath)
    }
}
