package dev.provenance.core

import kotlin.io.path.Path
import kotlin.system.exitProcess

/** Thin, directly-testable wrapper so DirectoryHashCliTest need not spawn a process. */
fun directoryHashCliCompute(path: String): String = DirectoryHash.sha256(Path(path))

/**
 * CLI entrypoint invoked by Gradle (recorder/build.gradle.kts's `computeExtensionHash` task)
 * so the CI/build-time hash and the seal-time runtime hash (recorder/'s ExtensionHash.kt) go
 * through the identical [DirectoryHash.sha256]. Never reimplement the algorithm at either site.
 *
 * Usage: `java -cp <core runtime classpath> dev.provenance.core.DirectoryHashCliKt <path>`
 * Prints the 64-char lowercase hex hash to stdout and nothing else, so Gradle can capture it
 * directly. Missing path argument -> message to stderr, non-zero exit.
 */
fun main(args: Array<String>) {
    if (args.size != 1) {
        System.err.println("usage: DirectoryHashCliKt <path>")
        exitProcess(1)
    }
    println(directoryHashCliCompute(args[0]))
}
