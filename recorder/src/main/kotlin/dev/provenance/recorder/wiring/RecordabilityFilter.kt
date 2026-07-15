package dev.provenance.recorder.wiring

import java.nio.file.Path

/**
 * The candidate assignment-manifest filenames the recorder must never record
 * (self-recording-loop hazard). Kept in sync with the activation loader's
 * MANIFEST_FILE_NAMES; duplicated here as a plain constant so this pure filter has
 * zero IntelliJ imports.
 */
val MANIFEST_FILE_NAMES_FILTER: Set<String> = setOf(".provenance-manifest", "provenance-manifest")

/**
 * The IDE's own project-settings directory, at the workspace root. Excluded because the
 * IDE creates and churns it (.gitignore on project create, workspace.xml on UI activity)
 * with no student action behind it — recording it would attribute IDE noise to the student
 * and burn firehose budget. This exclusion has no counterpart in doc-wiring.ts: VS Code
 * never auto-generates .vscode/, so the parity source had no reason to filter it.
 */
const val IDE_SETTINGS_DIR_NAME: String = ".idea"

/**
 * Pure recordability decision (mirrors isRecordable()/isProvenanceArtifact() in
 * doc-wiring.ts). No IntelliJ types — the platform seam resolves a VirtualFile to
 * (isLocalFs, nioPath) and calls this. A file is recordable iff:
 *  - it lives on the local file system (not an in-memory/light/diff/http VFS file),
 *  - it resolves to a real nio path,
 *  - that path is under the workspace root,
 *  - it is NOT inside the .provenance/ directory,
 *  - it is NOT one of the activation manifest files, and
 *  - it is NOT inside the workspace-root .idea/ directory.
 */
fun isRecordablePath(
    nioPath: Path?,
    isLocalFs: Boolean,
    workspaceRoot: Path,
    provenanceDir: Path,
    manifestNames: Set<String> = MANIFEST_FILE_NAMES_FILTER,
): Boolean {
    if (!isLocalFs) return false
    if (nioPath == null) return false
    val normalized = nioPath.normalize()
    val root = workspaceRoot.normalize()
    if (!normalized.startsWith(root)) return false
    if (normalized.startsWith(provenanceDir.normalize())) return false
    if (normalized.fileName?.toString() in manifestNames) return false
    if (normalized.startsWith(root.resolve(IDE_SETTINGS_DIR_NAME))) return false
    return true
}
