plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.audoiboo.tracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.audoiboo.tracker"
        minSdk = 23
        targetSdk = 37
        versionCode = 110
        versionName = "1.1.0-dev"
    }

    signingConfigs {
        create("stableRelease") {
            storeFile = rootProject.file(".github/keys/audoiboo-release.jks")
            storePassword = "Audoiboo2026"
            keyAlias = "audoiboo"
            keyPassword = "Audoiboo2026"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stableRelease")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableRelease")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
