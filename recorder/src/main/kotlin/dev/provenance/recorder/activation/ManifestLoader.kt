package dev.provenance.recorder.activation

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/** Candidate manifest file names, in precedence order (dotfile canonical; plain form is a fallback). */
val MANIFEST_FILE_NAMES: List<String> = listOf(".provenance-manifest", "provenance-manifest")

/**
 * Read, parse, and verify the manifest file in the given base directory.
 * PRD §4.1: try each candidate name in precedence order; only Inactive("no_manifest_file")
 * if none exist. Never throws.
 */
fun loadAndVerifyManifest(
    baseDir: VirtualFile,
    coursePubkeyHex: String = COURSE_PUBLIC_KEY_HEX,
): ManifestActivation {
    for (name in MANIFEST_FILE_NAMES) {
        val file = baseDir.findChild(name) ?: continue
        val text = try {
            VfsUtilCore.loadText(file)
        } catch (e: IOException) {
            return ManifestActivation.Inactive("read_error")
        }
        return evaluateManifestText(text, coursePubkeyHex)
    }
    return ManifestActivation.Inactive("no_manifest_file")
}

/** Project-level convenience wrapper — resolves the workspace root, then delegates. */
fun loadAndVerifyManifest(
    project: Project,
    coursePubkeyHex: String = COURSE_PUBLIC_KEY_HEX,
): ManifestActivation {
    val baseDir = project.guessProjectDir() ?: return ManifestActivation.Inactive("no_project_dir")
    return loadAndVerifyManifest(baseDir, coursePubkeyHex)
}
