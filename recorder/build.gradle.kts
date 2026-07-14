import org.jetbrains.intellij.platform.gradle.TestFrameworkType
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
        }
    }
}

// Mirror core/: only JDK 25 is installed and toolchain auto-provisioning is not
// configured, so compile with the running JDK targeting JVM 17 bytecode rather
// than pinning a jvmToolchain(17) Gradle could not resolve.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
