import java.util.Properties

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

// The Android app is only wired in when there is an SDK to build it against.
// Without this, `gradle :core:test` fails at configuration time on a machine
// with no Android SDK — including CI, which is exactly where the engine tests
// most need to run.
val sdkDir: String? = sequenceOf(
    System.getenv("ANDROID_HOME"),
    System.getenv("ANDROID_SDK_ROOT"),
    file("local.properties").takeIf { it.exists() }?.let { propertiesFile ->
        Properties().apply { propertiesFile.inputStream().use { load(it) } }.getProperty("sdk.dir")
    },
).firstOrNull { !it.isNullOrBlank() }

if (sdkDir != null) {
    include(":app")
} else {
    gradle.rootProject {
        logger.lifecycle("Android SDK not found — skipping :app. Core still builds and tests.")
    }
}
