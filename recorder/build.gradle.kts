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

// Course public key embed/revert. Mirrors the VS Code recorder's tools/embed-course-key.ts
// + `build:prod` git-checkout-revert flow: substitute the real course public key from an env
// var, build, then restore the checked-in dev key so a real key is never committed.
val coursePublicKeyFile = file("src/main/kotlin/dev/provenance/recorder/activation/CoursePublicKey.kt")
val hex64 = Regex("^[0-9a-f]{64}$")
// Matches the (possibly multi-line) `const val COURSE_PUBLIC_KEY_HEX: String = "<64 hex>"`.
val coursePublicKeyPattern = Regex("""const val COURSE_PUBLIC_KEY_HEX: String\s*=\s*"([0-9a-f]{64})"""")

tasks.register("embedCourseKey") {
    group = "provenance"
    description = "Embeds PROVENANCE_COURSE_PUBLIC_KEY_HEX into CoursePublicKey.kt for a production build."
    doLast {
        val hex = System.getenv("PROVENANCE_COURSE_PUBLIC_KEY_HEX")
            ?: throw GradleException(
                "PROVENANCE_COURSE_PUBLIC_KEY_HEX is not set. Set it to the production course " +
                    "public key (64 lowercase hex chars) and re-run.",
            )
        if (!hex64.matches(hex)) {
            throw GradleException(
                "PROVENANCE_COURSE_PUBLIC_KEY_HEX is malformed: expected 64 lowercase hex chars, " +
                    "got ${hex.length} chars.",
            )
        }
        val original = coursePublicKeyFile.readText()
        val match = coursePublicKeyPattern.find(original)
            ?: throw GradleException(
                "Could not locate COURSE_PUBLIC_KEY_HEX in $coursePublicKeyFile. The file shape may " +
                    "have drifted — update coursePublicKeyPattern or restore the file from git.",
            )
        val devKeyHex = match.groupValues[1]
        // Read the dev key from the file itself (single source of truth) and refuse to "embed"
        // it, so a misconfigured release can never silently ship the dev key.
        if (hex == devKeyHex) {
            throw GradleException(
                "PROVENANCE_COURSE_PUBLIC_KEY_HEX equals the dev key checked into the repo. " +
                    "Production builds must use a different key.",
            )
        }
        // Swap only the 64-hex constant; preserve all surrounding text (indentation, newlines).
        val rewritten = coursePublicKeyPattern.replace(original) { m -> m.value.replace(m.groupValues[1], hex) }
        coursePublicKeyFile.writeText(rewritten)
        logger.lifecycle("[embedCourseKey] Embedded production public key (public, hex): $hex")
    }
}

tasks.register<Exec>("revertCourseKey") {
    group = "provenance"
    description = "Restores CoursePublicKey.kt to its checked-in (dev-key) state via git checkout."
    commandLine("git", "checkout", "--", coursePublicKeyFile.absolutePath)
}

// extension_hash precompute. Extracts the built plugin distribution and runs core/'s
// DirectoryHash over the tree via its CLI entrypoint — the *same* function the seal command
// uses at runtime (recorder/'s ExtensionHash.kt) — so the value a student's installed plugin
// reports can be added to the analyzer allowlist before release. A dedicated resolvable
// configuration gives the CLI its full runtime classpath (core + kotlin-stdlib + deps).
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
    mainClass = "dev.provenance.core.DirectoryHashCliKt"
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

// compileKotlin must run *after* the key is embedded, so signPlugin/buildPlugin compile source
// that already contains the production key.
tasks.named("compileKotlin") { mustRunAfter("embedCourseKey") }

tasks.register("buildProd") {
    group = "provenance"
    description = "Production build: embeds the course public key, builds+signs the plugin, computes extension_hash, then ALWAYS reverts the embedded key."
    dependsOn("embedCourseKey")
    dependsOn(tasks.named("signPlugin"))
    dependsOn(computeExtensionHash)
    // finalizedBy (not a plain shell `&&` chain) runs the revert even if an earlier step fails,
    // so a failed prod build can never leave the real course key sitting in the working tree —
    // a deliberate robustness improvement over the VS Code recorder's sequential build:prod.
    finalizedBy("revertCourseKey")
}

tasks.register("publishProd") {
    group = "provenance"
    description = "Publishes the signed, prod-keyed plugin to JetBrains Marketplace. Irreversible per version — run buildProd + manual review first."
    dependsOn(tasks.named("buildProd"))
    dependsOn(tasks.named("verifyPlugin"))
    finalizedBy(tasks.named("publishPlugin"))
}
