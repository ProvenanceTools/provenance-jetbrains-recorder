import java.io.ByteArrayOutputStream
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":core"))
    // core exposes kotlinx-serialization JsonObject in its public API (Envelope.data,
    // *Payload.toJsonObject()) but depends on it via `implementation`, so it is not
    // on our compile classpath transitively — declare it here too. Pinned to core's
    // 1.11.0 (settled build matrix). No serialization plugin needed: we only call
    // library builders (buildJsonObject/put), never define @Serializable types.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)

        // Compile-time visibility only, for the optional terminal/git wiring (Plan 7).
        // These plugins ship with the platform SDK we already depend on — bundledPlugins
        // just exposes their types on the compile classpath; they are NOT hard runtime
        // dependencies. Runtime gating is done structurally via the optional
        // <depends optional="true" config-file="..."> entries in plugin.xml, so a class
        // referencing terminal/Git4Idea types is never classloaded on an IDE lacking them.
        // 'org.jetbrains.plugins.terminal' provides the Reworked Terminal API
        // (com.intellij.terminal.frontend.* / org.jetbrains.plugins.terminal.view.*);
        // 'Git4Idea' provides git4idea.repo.*.
        bundledPlugins("org.jetbrains.plugins.terminal", "Git4Idea")
    }

    // The IntelliJ Platform test framework (BasePlatformTestCase and friends) is
    // JUnit 4 / JUnit 3 (junit.framework.TestCase)-based, and mixing in the JUnit 5
    // Platform launcher makes IntelliJ's auto-registered JUnit5TestSessionListener
    // fail to instantiate. We therefore run the whole recorder suite on JUnit 4 —
    // matching the official IntelliJ Platform Gradle Plugin code samples — and do NOT
    // call useJUnitPlatform(). Pure (non-platform) tests use org.junit.Test too.
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Open-ended until-build is the modern recommendation: the plugin targets
            // com.intellij.modules.platform only, so it should keep loading on future IDE
            // builds instead of silently expiring. patchPluginXml (which derives its
            // sinceBuild/untilBuild/version from this block) then emits no until-build.
            // Override by setting `pluginUntilBuild` in gradle.properties only if a real
            // known incompatibility is discovered.
            if (providers.gradleProperty("pluginUntilBuild").isPresent) {
                untilBuild = providers.gradleProperty("pluginUntilBuild")
            } else {
                untilBuild = provider { null }
            }
        }
    }

    // --- Production distribution / Marketplace publishing (Plan 9) ---
    // All secrets come from environment variables; nothing is committed. verifyPlugin needs
    // no secrets and can be run in CI. signPlugin/publishPlugin require real operator secrets
    // (see README "Production release") and MUST NOT be run without them — the tasks fail
    // hard on a blank/missing certificate or token rather than producing a fake signature.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Marketplace release channel. "default" is the public stable channel; a named
        // channel (e.g. "eap") ships an early-access build only users who add that channel see.
        channels = listOf(providers.gradleProperty("provjet.publishChannel").orElse("default").get())
    }

    pluginVerification {
        failureLevel = listOf(VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS)
        ides {
            recommended()
        }
    }
}

// Mirror core/: only JDK 25 is installed and toolchain auto-provisioning is not
// configured, so compile with the running JDK targeting JVM 17 bytecode rather
// than pinning a jvmToolchain(17) Gradle could not resolve.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// ---------------------------------------------------------------------------
// Plan 9: production build — course-key embedding, extension_hash, publishing
// ---------------------------------------------------------------------------

// Trust-anchor embed/revert. Mirrors the VS Code recorder's tools/embed-root-key.ts
// + `build:prod` git-checkout-revert flow: substitute the real public keys from env vars,
// build, then restore the checked-in dev keys so a real key is never committed.
//
// Manifest 2.0 made this TWO constants rather than one:
//
//   ROOT_PUBLIC_KEY_HEX          the trust anchor for 2.0 chain verification. Required —
//                                without it the plugin verifies nothing at 2.0.
//   LEGACY_COURSE_PUBLIC_KEY_HEX the grandfathered pre-2.0 course key, used only for 1.x
//                                manifests. OPTIONAL, and optional on purpose: omitting the
//                                variable is how the constant eventually retires. A build
//                                with no 1.x manifests left in the field simply ships the
//                                dev value, which no real manifest can satisfy.
//
// Each constant keeps the same single-line 64-hex literal shape, so one regex per constant
// name applies to each independently.
data class TrustAnchor(
    /** Human-readable name for log lines and error messages. */
    val label: String,
    val file: File,
    val constantName: String,
    val envVar: String,
    /** Name of the Kotlin file-facade class the constant compiles into. */
    val facadeClassName: String,
    /** A build without this variable set is a build error. */
    val required: Boolean,
)

val hex64 = Regex("^[0-9a-f]{64}$")

/** Matches the (possibly multi-line) `const val <NAME>: String = "<64 hex>"`. */
fun trustAnchorPattern(constantName: String): Regex =
    Regex("""const val $constantName: String\s*=\s*"([0-9a-f]{64})"""")

val activationSourceDir = file("src/main/kotlin/dev/provenance/recorder/activation")

val trustAnchors = listOf(
    TrustAnchor(
        label = "root public key",
        file = activationSourceDir.resolve("RootPublicKey.kt"),
        constantName = "ROOT_PUBLIC_KEY_HEX",
        envVar = "PROVENANCE_ROOT_PUBLIC_KEY_HEX",
        facadeClassName = "RootPublicKeyKt",
        required = true,
    ),
    TrustAnchor(
        label = "legacy course public key",
        file = activationSourceDir.resolve("LegacyCoursePublicKey.kt"),
        constantName = "LEGACY_COURSE_PUBLIC_KEY_HEX",
        envVar = "PROVENANCE_LEGACY_COURSE_PUBLIC_KEY_HEX",
        facadeClassName = "LegacyCoursePublicKeyKt",
        required = false,
    ),
)

/** The dev key currently checked into [anchor]'s source file. Single source of truth. */
fun devKeyOf(anchor: TrustAnchor): String =
    trustAnchorPattern(anchor.constantName).find(anchor.file.readText())?.groupValues?.get(1)
        ?: throw GradleException(
            "Could not locate ${anchor.constantName} in ${anchor.file}. The file shape may have " +
                "drifted — update trustAnchorPattern or restore the file from git.",
        )

tasks.register("embedTrustAnchors") {
    group = "provenance"
    description = "Embeds PROVENANCE_ROOT_PUBLIC_KEY_HEX (and optionally " +
        "PROVENANCE_LEGACY_COURSE_PUBLIC_KEY_HEX) into the activation key constants."
    // Declaring the rewritten sources as outputs is load-bearing, not bookkeeping. Without it
    // Gradle does not know this task writes those files, so compileKotlin's up-to-date
    // check can be answered from a file snapshot taken before the rewrite — it then skips
    // recompiling and the DEV key survives into a "production" build. Observed for real: two
    // consecutive buildProd runs produced different artifacts, and only the second contained the
    // course key. mustRunAfter alone does not fix this; it orders execution but does not
    // invalidate the snapshot.
    outputs.files(trustAnchors.map { it.file })
    // The embedded values come from the environment, which Gradle cannot fingerprint — never
    // let this task be considered up-to-date.
    outputs.upToDateWhen { false }
    doLast {
        for (anchor in trustAnchors) {
            val hex = System.getenv(anchor.envVar)
            if (hex == null) {
                if (anchor.required) {
                    throw GradleException(
                        "${anchor.envVar} is not set. Set it to the production ${anchor.label} " +
                            "(64 lowercase hex chars) and re-run.",
                    )
                }
                // Optional and absent: leave the checked-in dev value in place. This is the
                // documented retirement path for the legacy key, not an oversight — say so
                // loudly rather than passing over it in silence.
                logger.lifecycle(
                    "[embedTrustAnchors] ${anchor.envVar} not set — shipping the checked-in dev " +
                        "${anchor.label}. Manifest 1.x files signed by a real course key will NOT " +
                        "activate in this build.",
                )
                continue
            }
            if (!hex64.matches(hex)) {
                throw GradleException(
                    "${anchor.envVar} is malformed: expected 64 lowercase hex chars, " +
                        "got ${hex.length} chars.",
                )
            }
            val devKeyHex = devKeyOf(anchor)
            // Read the dev key from the file itself and refuse to "embed" it, so a misconfigured
            // release can never silently ship a dev key.
            if (hex == devKeyHex) {
                throw GradleException(
                    "${anchor.envVar} equals the dev ${anchor.label} checked into the repo. " +
                        "Production builds must use a different key.",
                )
            }
            val original = anchor.file.readText()
            val pattern = trustAnchorPattern(anchor.constantName)
            // Swap only the 64-hex constant; preserve all surrounding text (indentation, newlines).
            val rewritten = pattern.replace(original) { m -> m.value.replace(m.groupValues[1], hex) }
            anchor.file.writeText(rewritten)
            logger.lifecycle("[embedTrustAnchors] Embedded production ${anchor.label} (public, hex): $hex")
        }
    }
}

tasks.register<Exec>("revertTrustAnchors") {
    group = "provenance"
    description = "Restores the activation key constants to their checked-in (dev-key) state via git checkout."
    commandLine(listOf("git", "checkout", "--") + trustAnchors.map { it.file.absolutePath })
}

// extension_hash precompute. Extracts the built plugin distribution and runs core/'s
// DirectoryHash over the tree via its CLI entrypoint — the *same* function the seal command
// uses at runtime (recorder/'s ExtensionHash.kt) — so the value a student's installed plugin
// reports can be added to the analyzer allowlist before release. A dedicated resolvable
// configuration gives the CLI its full runtime classpath (core + kotlin-stdlib + deps).
//
// Sharing the function is necessary but NOT sufficient: the two call sites must also hash the
// same *tree level*. The distribution .zip's single top-level entry is `recorder/`, so the
// staging directory below contains `staging/recorder/...` while an installed plugin's
// pluginPath IS the `recorder/` directory. Handing the CLI the staging dir therefore digested
// every path as `recorder/lib/...` and produced a hash no installed plugin could reproduce.
// The CLI now takes the staging dir and resolves the distribution root itself
// (core/'s `pluginDistributionRoot`), failing the build if the layout is not the expected
// single child directory — the level is no longer a choice this build script can get wrong.
val directoryHashCliClasspath: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    directoryHashCliClasspath(project(":core"))
}

val extensionHashStaging = layout.buildDirectory.dir("extensionHashStaging")

val unpackDistributionForHash = tasks.register<Sync>("unpackDistributionForHash") {
    group = "provenance"
    description = "Extracts the built plugin distribution so DirectoryHash can walk it (mirrors installing the plugin)."
    dependsOn(tasks.named("buildPlugin"))
    from(zipTree(tasks.named("buildPlugin").map { (it as Zip).archiveFile }))
    into(extensionHashStaging)
}

val computeExtensionHash = tasks.register<JavaExec>("computeExtensionHash") {
    group = "provenance"
    description = "Computes extension_hash (reproducible dir-tree SHA-256) over the built plugin distribution, for the analyzer allowlist."
    dependsOn(unpackDistributionForHash)
    classpath = directoryHashCliClasspath
    mainClass = "dev.provenance.core.ExtensionHashCliKt"
    // Deliberately the *staging* dir, not a path built here: the CLI resolves the distribution
    // root inside it and fails loudly on an unexpected layout, so this build script cannot
    // silently drift back to hashing the wrong tree level.
    args(extensionHashStaging.get().asFile.absolutePath)
    val capture = ByteArrayOutputStream()
    standardOutput = capture
    doLast {
        val hash = capture.toString(Charsets.UTF_8.name()).trim()
        val outFile = layout.buildDirectory.file("extension-hash.txt").get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(hash + "\n")
        logger.lifecycle("[computeExtensionHash] extension_hash = $hash")
        logger.lifecycle("[computeExtensionHash] written to: $outFile")
    }
}

// compileKotlin must run *after* the keys are embedded, so signPlugin/buildPlugin compile source
// that already contains the production keys.
tasks.named("compileKotlin") { mustRunAfter("embedTrustAnchors") }

// Never sign an artifact that has not been proven to carry the right keys: a signed dev-key zip
// is exactly the thing that must not exist, since it is the one that looks ready to upload.
tasks.named("signPlugin") { mustRunAfter("verifyEmbeddedTrustAnchors") }

// Last line of defence for the release: assert the *built artifact* actually carries the keys.
// Everything upstream (task ordering, up-to-date checks) is a means to that end, and when it
// silently failed the result was a signed, publishable plugin trusting the repo's public dev key —
// which would refuse every real course-signed manifest and so record nothing, for every student.
// A key that is wrong here is not recoverable after publication, so fail the build instead.
//
// The check runs per anchor and is scoped to whichever anchors were actually supplied: the legacy
// course key is optional, so a build that omitted it is verified only for the root key. What is
// NOT optional is that a *supplied* key must be present and its dev counterpart absent — an
// anchor silently dropping out of coverage is how a dev-key build ships.
val verifyEmbeddedTrustAnchors = tasks.register("verifyEmbeddedTrustAnchors") {
    group = "provenance"
    description = "Fails the build unless the compiled plugin distribution embeds every supplied trust anchor."
    dependsOn(unpackDistributionForHash)
    outputs.upToDateWhen { false }
    doLast {
        val staging = extensionHashStaging.get().asFile
        for (anchor in trustAnchors) {
            val expected = System.getenv(anchor.envVar)
            if (expected == null) {
                if (anchor.required) throw GradleException("${anchor.envVar} is not set.")
                logger.lifecycle(
                    "[verifyEmbeddedTrustAnchors] ${anchor.envVar} not set — skipping the " +
                        "${anchor.label} check; this build intentionally ships its dev value.",
                )
                continue
            }
            val classFileName = "${anchor.facadeClassName}.class"
            val classFile = staging.walkTopDown().firstOrNull {
                it.isFile && it.name == classFileName
            } ?: run {
                // The constant is compiled into the plugin jar inside the distribution.
                val jar = staging.walkTopDown().firstOrNull {
                    it.isFile && it.name.startsWith("recorder-") && it.extension == "jar"
                } ?: throw GradleException("Could not find the recorder jar under $staging to verify the embedded keys.")
                zipTree(jar).matching { include("**/$classFileName") }.singleOrNull()
                    ?: throw GradleException("Could not find $classFileName inside $jar.")
            }
            val bytes = classFile.readBytes().toString(Charsets.ISO_8859_1)
            if (!bytes.contains(expected)) {
                throw GradleException(
                    "The built plugin does NOT embed the expected ${anchor.label} ($expected). The " +
                        "build may have reused stale compiled output — run `./gradlew :recorder:clean` " +
                        "and rebuild. Refusing to produce a release artifact with the wrong key.",
                )
            }
            val devKeyHex = devKeyOf(anchor)
            if (bytes.contains(devKeyHex) && devKeyHex != expected) {
                throw GradleException(
                    "The built plugin embeds the DEV ${anchor.label} ($devKeyHex). Refusing to release it.",
                )
            }
            logger.lifecycle("[verifyEmbeddedTrustAnchors] Verified: distribution embeds ${anchor.label} $expected")
        }
    }
}

tasks.register("buildProd") {
    group = "provenance"
    description = "Production build: embeds the trust anchors, builds+signs the plugin, computes extension_hash, then ALWAYS reverts the embedded keys."
    dependsOn("embedTrustAnchors")
    dependsOn(tasks.named("signPlugin"))
    dependsOn(computeExtensionHash)
    dependsOn(verifyEmbeddedTrustAnchors)
    // finalizedBy (not a plain shell `&&` chain) runs the revert even if an earlier step fails,
    // so a failed prod build can never leave a real key sitting in the working tree —
    // a deliberate robustness improvement over the VS Code recorder's sequential build:prod.
    finalizedBy("revertTrustAnchors")
}

tasks.register("publishProd") {
    group = "provenance"
    description = "Publishes the signed, prod-keyed plugin to JetBrains Marketplace. Irreversible per version — run buildProd + manual review first."
    dependsOn(tasks.named("buildProd"))
    dependsOn(tasks.named("verifyPlugin"))
    finalizedBy(tasks.named("publishPlugin"))
}
