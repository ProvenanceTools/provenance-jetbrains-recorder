package dev.provenance.recorder.session

import dev.provenance.core.GitCaptureCapability
import dev.provenance.core.HostInfo
import dev.provenance.core.Manifest
import dev.provenance.core.SessionFileScope
import dev.provenance.core.SessionIdentity
import dev.provenance.core.SessionStartPayload
import dev.provenance.core.Sha256
import dev.provenance.core.WitnessCaptureCapability
import dev.provenance.core.buildFileScope

/**
 * The cap on [SessionFileScope.watched], in entries. Ported from recorder-context.ts's
 * `FILE_SCOPE_MAX_ENTRIES`.
 *
 * Today's resolver is the manifest's own `files_under_review`, a hand-authored course list of a
 * handful of files, so this never bites. It exists because the field's whole value is that a
 * consumer can read a path's ABSENCE from the list as "not watched", and an unbounded list
 * inside a single hash-chained entry is not something a future `scope: 'repo'` resolver should
 * be able to produce by accident. When the cap bites, the recorder emits `complete: false`, and
 * every reader then downgrades absence to _unknown_ rather than to _not watched_.
 *
 * Part of the writer contract: the three recorders must cap at the same number, or two ports
 * disagree about when `complete` goes false. **Must match log-core's own constant.**
 */
const val FILE_SCOPE_MAX_ENTRIES: Int = 4096

/**
 * Resolve the EFFECTIVE FILE SET this session will watch — collaboration spec §5.6 item 1,
 * S25. Direct port of `resolveFileScope` in recorder-context.ts.
 *
 * Today this is the identity function over `manifest.files_under_review`: that list is already
 * assignment-root-relative by construction (the same category of path `doc.open.path` already
 * carries), so it needs no further resolution — see the TS docstring for why this is still the
 * right field to publish rather than a mere copy of the manifest. [buildFileScope] rejects an
 * absolute path, a colon, or a `..` segment; a course that put one in its manifest gets the
 * field OMITTED (returns null) rather than an unsafe path written into a signed log — S14(b).
 */
fun resolveFileScope(filesUnderReview: List<String>): SessionFileScope? {
    val complete = filesUnderReview.size <= FILE_SCOPE_MAX_ENTRIES
    val watched = if (complete) filesUnderReview else filesUnderReview.take(FILE_SCOPE_MAX_ENTRIES)
    return buildFileScope(watched, complete)
}

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
    /**
     * The student's verified enrollment identity, or null when there is none to emit
     * (program spec §5, §S2). Null is the ordinary pre-enrollment state and is never an
     * error: the field is simply omitted and the session records exactly as before.
     * Assembled and CHAIN-VERIFIED by `buildSessionIdentity` before it reaches here.
     */
    identity: SessionIdentity? = null,
    /** §5.6 item 2 — see `GitCapabilityProbe.kt`. Null omits the field. */
    gitCapture: GitCaptureCapability? = null,
    /** §5.6 item 3 — see the probe in `RecordingSessionController.init`. Null omits the field. */
    witnessCapture: WitnessCaptureCapability? = null,
    /** §5.6 item 1 — see [resolveFileScope]. Null omits the field. */
    fileScope: SessionFileScope? = null,
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
        // Omitted entirely when absent — never present-and-empty. An unverifiable identity
        // claim inside a signed, hash-chained entry is permanent, so emitting nothing is
        // strictly better than emitting something broken.
        identity = identity,
        gitCapture = gitCapture,
        witnessCapture = witnessCapture,
        fileScope = fileScope,
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
