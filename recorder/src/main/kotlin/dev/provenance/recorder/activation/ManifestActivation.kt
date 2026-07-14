package dev.provenance.recorder.activation

import dev.provenance.core.Manifest
import dev.provenance.core.ManifestParse
import dev.provenance.core.parseManifest
import dev.provenance.core.verifyManifest

/** Activation decision for one candidate manifest file. Never throws. */
sealed interface ManifestActivation {
    data class Active(val manifest: Manifest) : ManifestActivation
    data class Inactive(val reason: String) : ManifestActivation
}

/**
 * Pure parse+verify of manifest file text against the course public key.
 * Zero IntelliJ imports — mirrors the VS Code recorder's loadAndVerifyManifest
 * Steps 2-3 (manifest-loader.ts), split out so it's testable without any platform seam.
 * PRD §4.1: "If the signature doesn't verify, the extension does nothing."
 */
fun evaluateManifestText(text: String, coursePubkeyHex: String): ManifestActivation {
    val parsed = parseManifest(text)
    if (parsed is ManifestParse.Err) {
        return ManifestActivation.Inactive("parse_error")
    }
    val manifest = (parsed as ManifestParse.Ok).manifest
    return if (verifyManifest(manifest, coursePubkeyHex)) {
        ManifestActivation.Active(manifest)
    } else {
        ManifestActivation.Inactive("signature_invalid")
    }
}
