plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

android {
    namespace = "au.radar.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "au.radar.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Read from local.properties or the environment rather than committed,
        // so the token never lands in the repository.
        buildConfigField(
            "String",
            "RADAR_BASE_URL",
            "\"${project.findProperty("radarBaseUrl") ?: System.getenv("RADAR_BASE_URL") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "RADAR_APP_TOKEN",
            "\"${project.findProperty("radarAppToken") ?: System.getenv("RADAR_APP_TOKEN") ?: ""}\"",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets["main"].java.srcDir("src/main/kotlin")
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    // Fused location gives far better speed and bearing than raw GPS.
    implementation("com.google.android.gms:play-services-location:21.3.0")

    implementation("org.maplibre.gl:android-sdk:11.5.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
