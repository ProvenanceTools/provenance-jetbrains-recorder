plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    id("org.jetbrains.intellij.platform") version "2.16.0" apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}
