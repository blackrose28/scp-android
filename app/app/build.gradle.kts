plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "run.scp.mobile.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "run.scp.mobile"
        minSdk = 26 // scpmobile.aar declares 23; Android Keystore AES-GCM + java.time need 26 here
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // Release ships arm64-v8a only (the default scpmobile.aar build).
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug {
            // Also package x86_64 so debug builds run on x86_64 emulators. This needs an AAR built with
            // SCP_TARGETS=android/arm64,android/amd64 (see go/build-aar.sh); with an arm64-only AAR the
            // extra filter is a no-op.
            ndk { abiFilters += "x86_64" }
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // gomobile binding (run.scp.mobile.scpmobile.*): DialDirect, OpenShell, key generation.
    implementation(files("libs/scpmobile.aar"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
