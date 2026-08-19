package dev.provenance.recorder.activation

import dev.provenance.core.MANIFEST_FORMAT_VERSION_2
import dev.provenance.core.Manifest
import dev.provenance.core.ManifestChain
import dev.provenance.core.ManifestParse
import dev.provenance.core.manifestFormatVersion
import dev.provenance.core.parseManifest
import dev.provenance.core.verifyManifest
import dev.provenance.core.verifyManifestChain

/** Activation decision for one candidate manifest file. Never throws. */
sealed interface ManifestActivation {
    data class Active(val manifest: Manifest) : ManifestActivation
    data class Inactive(val reason: String) : ManifestActivation
}

/**
 * Pure parse+verify of manifest file text, routed on the manifest's format version.
 * Zero IntelliJ imports — mirrors the VS Code recorder's loadAndVerifyManifest
 * Steps 2-3 (manifest-loader.ts), split out so it's testable without any platform seam.
 * PRD §4.1: "If the signature doesn't verify, the extension does nothing."
 *
 * **2.0** walks the full trust chain (root → `course_cert` → manifest) against
 * [rootPubkeyHex]. **Missing or 1.0** takes the legacy path: a plain signature
 * check against [legacyCoursePubkeyHex], byte-for-byte as it behaved before
 * Manifest 2.0 existed. Routing, not fallback — a 2.0 manifest that fails the
 * chain is never retried against the legacy key, because that would restore
 * exactly the downgrade the chain's step 0 exists to close.
 *
 * An expired `course_cert` does NOT block activation (program spec §4): silently
 * halting capture for a whole class is a worse failure for an integrity tool than
 * recording under a stale key. The chain reports the window on its success value;
 * the analyzer decides. Stamping that expiry into `session.start` is separate work.
 *
 * [legacyCoursePubkeyHex] leads the parameter list because it is the key every
 * pre-existing call site passes positionally, and keeping that meaning is what
 * makes the 1.x path provably unchanged. It is nonetheless the deprecated anchor —
 * see [LEGACY_COURSE_PUBLIC_KEY_HEX].
 */
fun evaluateManifestText(
    text: String,
    legacyCoursePubkeyHex: String = LEGACY_COURSE_PUBLIC_KEY_HEX,
    rootPubkeyHex: String = ROOT_PUBLIC_KEY_HEX,
): ManifestActivation {
    val parsed = parseManifest(text)
    if (parsed is ManifestParse.Err) {
        return ManifestActivation.Inactive("parse_error")
    }
    val manifest = (parsed as ManifestParse.Ok).manifest

    if (manifestFormatVersion(manifest) == MANIFEST_FORMAT_VERSION_2) {
        return when (val chain = verifyManifestChain(manifest, rootPubkeyHex)) {
            is ManifestChain.Ok -> ManifestActivation.Active(manifest)
            is ManifestChain.Err -> ManifestActivation.Inactive("chain_${chain.kind}")
        }
    }

    return if (verifyManifest(manifest, legacyCoursePubkeyHex)) {
        ManifestActivation.Active(manifest)
    } else {
        ManifestActivation.Inactive("signature_invalid")
    }
}
