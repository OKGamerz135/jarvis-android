plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// CI sets GITHUB_RUN_NUMBER; every build gets a higher number, which is what
// the in-app updater compares against the latest GitHub release tag.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "dev.jarvis.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.jarvis.assistant"
        minSdk = 31
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"

        // Where the in-app updater looks for new builds (public repo, no token needed).
        buildConfigField("String", "UPDATE_REPO", "\"OKGamerz135/jarvis-android\"")
    }

    // A fixed key is required so a new build can install over the old one.
    // The keystore is committed on purpose: personal sideload only, not for the Play Store.
    signingConfigs {
        create("jarvis") {
            storeFile = file("jarvis.jks")
            storePassword = "jarvis-personal"
            keyAlias = "jarvis"
            keyPassword = "jarvis-personal"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("jarvis")
            isMinifyEnabled = false
        }
        debug {
            signingConfig = signingConfigs.getByName("jarvis")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
}
