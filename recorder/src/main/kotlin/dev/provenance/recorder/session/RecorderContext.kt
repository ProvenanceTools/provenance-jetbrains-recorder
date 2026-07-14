package dev.provenance.recorder.session

import dev.provenance.core.Manifest
import dev.provenance.core.SessionStartPayload
import dev.provenance.core.Sha256

/**
 * Builds the session.start payload (PRD §5.1). All environment-dependent lookups
 * (IDE version, plugin id, hostname, username) are PARAMETERS, not internal calls,
 * so this function is pure and unit-testable without any IntelliJ SDK or JVM
 * environment dependency — a thin wrapper (Task 12) supplies the real values.
 * Ported from recorder-context.ts, restructured for the plan's testing split.
 */
fun buildRecorderContext(
    manifest: Manifest,
    prevSessionId: String?,
    sessionId: String,
    sessionPubkeyHex: String,
    ideVersion: String,
    platform: String,
    recorderVersion: String,
    recorderExtensionId: String,
    hostnameProvider: () -> String? = ::defaultHostname,
    usernameProvider: () -> String = { System.getProperty("user.name") ?: "unknown" },
): SessionStartPayload {
    // A silent empty-string hostname would make machine_id collide across different
    // machines with the same username, defeating its purpose — fall back to "unknown".
    val hostname = hostnameProvider() ?: "unknown"
    val username = usernameProvider()
    return SessionStartPayload(
        formatVersion = "1.0",
        sessionId = sessionId,
        prevSessionId = prevSessionId,
        assignmentId = manifest.assignmentId,
        assignmentSemester = manifest.semester,
        manifestSig = manifest.sig,
        machineId = computeMachineId(hostname, username, sessionId),
        vscodeVersion = ideVersion,
        // The IDE build commit hash is not part of the editor-generic mapping; emit "".
        vscodeCommit = "",
        vscodePlatform = platform,
        recorderVersion = recorderVersion,
        recorderExtensionId = recorderExtensionId,
        sessionPubkey = sessionPubkeyHex,
    )
}

/**
 * machine_id = sha256(hostname:username:sessionId). Session-id-salted to prevent
 * cross-assignment correlation. Direct port of computeMachineId in recorder-context.ts.
 */
fun computeMachineId(hostname: String, username: String, sessionId: String): String =
    Sha256.hex("$hostname:$username:$sessionId")

/**
 * Non-blocking hostname lookup (Global Constraints): InetAddress.getLocalHost() can
 * hang behind some VPNs, so read env vars only. Returns null if neither is set.
 */
fun defaultHostname(): String? = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
