plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "org.audoiboo.tracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.audoiboo.tracker"
        minSdk = 23
        targetSdk = 37
        versionCode = 113
        versionName = "1.1.3-dev"
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
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-common:1.8.0")
    implementation("com.google.guava:guava:33.4.8-android")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("org.jsoup:jsoup:1.21.2")

    implementation("androidx.room:room-runtime:2.8.0")
    implementation("androidx.room:room-ktx:2.8.0")
    implementation("androidx.room:room-paging:2.8.0")
    kapt("androidx.room:room-compiler:2.8.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.paging:paging-runtime-ktx:3.3.6")
    implementation("androidx.paging:paging-compose:3.3.6")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
