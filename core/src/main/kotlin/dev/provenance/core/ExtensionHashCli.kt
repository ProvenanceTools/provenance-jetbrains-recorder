package dev.provenance.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.system.exitProcess

/**
 * Thrown when an extracted plugin distribution does not have the one shape this CLI knows how
 * to hash: a staging directory containing exactly one entry, and that entry a directory.
 */
class PluginDistributionLayoutException(message: String) : IllegalStateException(message)

/**
 * Resolve the plugin distribution root inside [stagingDir] — the directory an installed plugin
 * would *be*, not the directory it was extracted *into*.
 *
 * This distinction is the whole point of this file. A plugin distribution `.zip` has a single
 * top-level directory (`recorder/`), so extracting it into a staging directory yields
 * `staging/recorder/...`, whereas a plugin installed in an IDE is `.../plugins/recorder/` and
 * [com.intellij.ide.plugins.IdeaPluginDescriptor.getPluginPath] points at that directory itself.
 * [DirectoryHash.sha256] digests `<relative-path>\0<file-bytes>`, so hashing the staging dir
 * prefixes every relative path with `recorder/` and produces a hash no installed plugin can ever
 * reproduce. That happened: the build-time and seal-time `extension_hash` never matched.
 *
 * Fails loudly rather than guessing. Silently hashing the wrong tree level is exactly the failure
 * this function exists to prevent, and it is invisible in the output (both levels produce a
 * plausible 64-hex string).
 */
fun pluginDistributionRoot(stagingDir: Path): Path {
    if (!Files.isDirectory(stagingDir)) {
        throw PluginDistributionLayoutException(
            "extension_hash: $stagingDir is not a directory. Expected the staging directory the " +
                "plugin distribution was extracted into.",
        )
    }
    val entries = Files.list(stagingDir).use { stream ->
        stream.sorted(compareBy { it.fileName.toString() }).toList()
    }
    val singleDir = entries.singleOrNull()?.takeIf { Files.isDirectory(it) }
        ?: throw PluginDistributionLayoutException(
            "extension_hash: expected $stagingDir to contain exactly one child directory (the " +
                "plugin distribution root, e.g. 'recorder/'), but found " +
                "${entries.size} entr${if (entries.size == 1) "y" else "ies"}: " +
                entries.joinToString(", ") { "${it.fileName}${if (Files.isDirectory(it)) "/" else ""}" }
                    .ifEmpty { "<none>" } +
                ". Refusing to guess which tree level to hash: hashing the wrong level yields an " +
                "extension_hash no installed plugin can reproduce, which silently flags every " +
                "submission.",
        )
    return singleDir
}

/**
 * The build/CI-time `extension_hash`: [DirectoryHash.sha256] over the plugin distribution root
 * inside [stagingDir]. Directly testable so [ExtensionHashCliTest] need not spawn a process.
 *
 * The equality that matters — this value equals what recorder/'s `computeInstalledExtensionHash`
 * reports for the same tree — is pinned by recorder/'s `ExtensionHashTest`.
 */
fun stagedDistributionExtensionHash(stagingDir: Path): String =
    DirectoryHash.sha256(pluginDistributionRoot(stagingDir))

/**
 * CLI entrypoint invoked by Gradle (recorder/build.gradle.kts's `computeExtensionHash` task) so
 * the build-time hash and the seal-time runtime hash (recorder/'s ExtensionHash.kt) go through
 * the identical [DirectoryHash.sha256] over the identical tree level. Never reimplement the
 * algorithm, and never point this at a directory other than the extraction staging directory.
 *
 * Usage: `java -cp <core runtime classpath> dev.provenance.core.ExtensionHashCliKt <staging-dir>`
 * Prints the 64-char lowercase hex hash to stdout and nothing else, so Gradle can capture it
 * directly. A missing argument or an unexpected staging layout -> message to stderr, non-zero exit.
 */
fun main(args: Array<String>) {
    if (args.size != 1) {
        System.err.println("usage: ExtensionHashCliKt <staging-dir>")
        exitProcess(1)
    }
    val hash = try {
        stagedDistributionExtensionHash(Path(args[0]))
    } catch (e: PluginDistributionLayoutException) {
        System.err.println(e.message)
        exitProcess(2)
    }
    println(hash)
}
