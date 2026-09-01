pluginManagement {
    repositories {
        // Maven Central first: it carries the Kotlin and JetBrains artifacts, and
        // asking Google for them first only burns a round trip per dependency.
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "radar-au"

// The alert engine and API client live in a plain JVM module with no Android
// dependencies, so they can be unit tested without an emulator or an SDK.
include(":core")
include(":app")
