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
 * WHICH `.slog` this operates on is decided entirely by `SlogOwnership.kt`: eligible files
 * only, latest `session.start.wall` among them. Read that file before changing anything
 * here; a partner's `.slog` must never reach this module's quarantine.
 *
 * Two decisions live here, and the VS Code twin
 * (provenance/packages/recorder/src/startup/chain-recovery.ts) is their rationale:
 *   1. prev_session_id linkage is set ONLY for a dangling prior session (crash: no
 *      trailing session.end). A cleanly-completed prior session is not linked; a corrupt
 *      one is surfaced via quarantine + recorder.recovered_from_corruption, never linkage.
 *   2. Corruption does NOT emit chain.broken. That kind stays reserved for a live session
 *      detecting its own chain breaking mid-stream. Recovery quarantines the file
 *      (`<slog>.corrupt-<ISO>`) and reports the quarantined path.
 *
 * ORDERING CONSTRAINT ON CALLERS: recovery must run AFTER this session's identity has been
 * built, because [RecoveryDeps.ownStudentRef] is the whole ownership signal and it comes
 * from that identity. Recovering with a null ref in an enrolled student's shared repo is
 * the bug this module was rewritten to fix, not a degraded-but-acceptable mode. See
 * `RecorderSessionManager.startFromActivation`.
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

    /**
     * `identity.enrollment.student_ref` of the session that is STARTING, or null when this
     * recorder holds no verifying enrollment for this course.
     *
     * This is the whole ownership signal — see [SlogOwnership]. Defaulted so callers and
     * tests that predate the enrollment work keep exactly the behaviour they had (the
     * unenrolled path).
     */
    val ownStudentRef: String? get() = null
}

/**
 * Inspect provenanceDir for a previous session and return a recovery decision.
 *
 * Side effect: if the SELECTED .slog is invalid (or unreadable/unparsable/malformed-header),
 * renames it to `<slog>.corrupt-<ISO>` (quarantine) before returning PreviousSessionCorrupt
 * — but only ever a file this recorder is entitled to touch. A file belonging to another
 * contributor is never read past its first line, never selected, never linked, and never
 * renamed (`SlogOwnership.kt`).
 */
suspend fun recoverPreviousSession(deps: RecoveryDeps): RecoveryDecision {
    val slogFiles = deps.listSlogFiles(deps.provenanceDir).filter { it.endsWith(".slog") }.sorted()
    if (slogFiles.isEmpty()) return RecoveryDecision.CleanStart

    // Most recent ELIGIBLE session by session.start wall. Null means the whole directory
    // belongs to other contributors: start clean and leave every one of their files exactly
    // where it is.
    val picked = selectEligible(slogFiles, deps.provenanceDir, deps::readSlogFile, deps.ownStudentRef)
        ?: return RecoveryDecision.CleanStart

    val slogPath = "${deps.provenanceDir}/${picked.filename}"

    // The one quarantine call site, on the selected path only.
    suspend fun quarantine(): RecoveryDecision.PreviousSessionCorrupt {
        val quarantined = "$slogPath.corrupt-${deps.now().toString().replace(Regex("[:.]"), "-")}"
        deps.rename(slogPath, quarantined)
        return RecoveryDecision.PreviousSessionCorrupt(quarantined)
    }

    // Reuse the text selection already read; only re-read on the eligible fallback.
    val text = picked.text ?: when (val r = deps.readSlogFile(slogPath)) {
        is SlogReadResult.Ok -> r.text
        is SlogReadResult.Err -> return quarantine()
    }

    val parseResult = parseEntries(text)
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
