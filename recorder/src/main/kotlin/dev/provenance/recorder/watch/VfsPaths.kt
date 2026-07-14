package dev.provenance.recorder.watch

import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Resolve [vf] to a workspace-relative '/'-separated path, or null if it can't be a
 * watched session file. Computed the SAME way as DocWiring.relativePath so the key a
 * doc.open/doc.change seeds the registry under matches the key the fs listeners look up:
 * nio path, normalized, relativized against [workspaceRoot], backslashes → '/'.
 *
 * Returns null for non-local (in-memory/jar/http) files and for paths that escape the
 * workspace root (relativize yields a leading ".."), so the engine's isWatched gate never
 * even sees out-of-workspace events.
 */
fun relativePathOf(vf: VirtualFile, workspaceRoot: Path): String? {
    if (!vf.isInLocalFileSystem) return null
    val nio = runCatching { vf.toNioPath() }.getOrNull() ?: return null
    val rel = runCatching {
        workspaceRoot.normalize().relativize(nio.normalize()).toString().replace('\\', '/')
    }.getOrNull() ?: return null
    if (rel.isEmpty() || rel == ".." || rel.startsWith("../")) return null
    return rel
}

/** Read a VFS file's content as UTF-8 text. VFS-mediated (contentsToByteArray) so it
 *  reflects a just-completed async-VFS save even before the physical write finishes. */
fun readVfsText(vf: VirtualFile): String = String(vf.contentsToByteArray(), Charsets.UTF_8)
