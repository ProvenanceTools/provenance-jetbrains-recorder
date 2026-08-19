package dev.provenance.recorder.session

import dev.provenance.core.HostInfo
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
        // This is the LOG format version, not the manifest's. It stays "1.0": the
        // session.start 2.0 additions below are additive optional fields, and the VS
        // Code recorder likewise did not bump it. Bumping it here would invalidate
        // every reader that gates on it while changing nothing about the envelope.
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
        // The FULL manifest — signed payload + sig + course_cert (program spec §5).
        // This is what turns validation check 2 into a real check: an analyzer can
        // otherwise only compare manifest_sig across sessions for equality, because the
        // signed payload never enters the bundle. Carrying the whole manifest lets it
        // walk root -> course -> manifest -> session entirely offline, and it is how the
        // certificate's validity window reaches the analyzer at all — an expired cert
        // does not stop the recorder (program spec §4), so course_cert + issued_at
        // travelling here are what let the analyzer re-run checkCertWindow and decide.
        //
        // Emitted for 1.x manifests too: additive, and a 1.x manifest's parsed form
        // carries no 2.0-only fields, so nothing unsigned can ride along.
        //
        // MUST already have passed activation (evaluateManifestText). Passing an
        // unverified manifest here would put an unverified trust chain into a signed
        // chain, which reads downstream as proof it never was.
        manifest = manifest,
        // `host` replaces the VS Code-shaped `vscode` block (program spec §5). `vscode`
        // is retained above, populated, so 1.x readers keep working through the
        // reader-before-writer migration (program spec §9); a later change drops it.
        host = HostInfo(
            editor = "jetbrains",
            editorVersion = ideVersion,
            // "" is permitted. The IDE build number is available to the plugin, but it is
            // not what editor_build means for the VS Code writer (a build/commit id it
            // cannot expose), and inventing a differently-shaped value across recorders
            // would make the field unusable for cross-host comparison. Left empty until
            // the field's cross-recorder semantics are pinned.
            editorBuild = "",
            platform = platform,
        ),
        // NOTE: `identity` is deliberately NOT emitted. Enrollment tokens and the student
        // per-course key are sub-project S2 and do not exist yet.
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
