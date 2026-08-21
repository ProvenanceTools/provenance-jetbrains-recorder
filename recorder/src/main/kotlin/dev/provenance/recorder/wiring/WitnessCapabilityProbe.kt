package dev.provenance.recorder.wiring

import dev.provenance.core.WitnessCaptureCapability
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `session.start.witness_capture` CAPABILITY REPORT (collaboration spec §5.6 item 3), write
 * side.
 *
 * ## Why this probes the directory LISTING, not the VFS watcher
 *
 * The VS Code recorder derives `witness_capture` from whether its ONE `.provenance/`
 * `FileSystemWatcher` could be created — see `session-registry.ts`'s step 3c-quater: "the
 * watcher the peer-witnessing wiring will use is created HERE, once, and whether it could be
 * created is the answer." That is a faithful report there because the watcher IS VS Code's only
 * witnessing mechanism.
 *
 * [PeerWatcher]'s own docstring (rule 1, "provjet deviation") names something different as this
 * port's source of truth: IntelliJ's VFS is a cached layer that does not notice a `git pull` run
 * in an external terminal, so [PeerWatcher.drain] SWEEPS the directory via [PeerFiles.list] on
 * every checkpoint — the VFS_CHANGES subscription in `RecordingSessionController.init` is only a
 * PROMPTNESS signal on top of that sweep, not a requirement for it. A session whose VFS
 * subscription failed to install but whose `.provenance/` is still listable would still witness
 * every partner log, merely a little later (at the next checkpoint rather than the instant the
 * file appears) — so probing the subscription would report [WitnessCaptureCapability.UNAVAILABLE]
 * for a session that can, in fact, still witness everything. This probes the listing instead,
 * which is the one thing that actually gates whether an observation can happen at all: if
 * `.provenance/` cannot be listed, neither the watcher NOR the sweep can see anything in it.
 *
 * Called once, at session start, right after [dir] is created — mirrors the VS Code recorder's
 * "probe once, before the first entry is chained" rule. Never throws: every failure — the
 * directory vanishing between creation and this call, a permissions error, a filesystem that
 * does not support directory streams (WSL/IJent edge cases the writer already treats specially
 * elsewhere) — is [WitnessCaptureCapability.UNAVAILABLE], and [onError] is best-effort logging
 * only, exactly as [PeerWatcher.onError] is never routed into the disk-full degradation path.
 */
fun probeWitnessCapture(dir: Path, onError: (Throwable) -> Unit = {}): WitnessCaptureCapability =
    try {
        Files.newDirectoryStream(dir).use { }
        WitnessCaptureCapability.AVAILABLE
    } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        onError(t)
        WitnessCaptureCapability.UNAVAILABLE
    }
