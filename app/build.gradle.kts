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
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.jarvis.assistant"
        minSdk = 31
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"

        // Where the in-app updater looks for new builds (public repo, no token needed).
        buildConfigField("String", "UPDATE_REPO", "\"OKGamerz135/jarvis-android\"")
    }

    // Release signing comes ONLY from the environment (GitHub Secrets in CI, or your own shell). Nothing secret is
    // stored in this repository. With no signing variables the release APK is left unsigned, so it cannot be
    // installed by accident. Setting only some of the variables is an error, so a half-configured build never
    // silently falls back to something else.
    val signingVars = listOf("JARVIS_KEYSTORE_FILE", "JARVIS_KEYSTORE_PASSWORD", "JARVIS_KEY_ALIAS", "JARVIS_KEY_PASSWORD")
    val signingSet = signingVars.filter { !System.getenv(it).isNullOrBlank() }
    if (signingSet.isNotEmpty() && signingSet.size != signingVars.size) {
        throw GradleException("Incomplete release signing configuration. Missing: " + (signingVars - signingSet.toSet()).joinToString())
    }
    val hasReleaseSigning = signingSet.size == signingVars.size
    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                val keystore = file(System.getenv("JARVIS_KEYSTORE_FILE"))
                if (!keystore.isFile) throw GradleException("JARVIS_KEYSTORE_FILE does not point to a file.")
                storeFile = keystore
                storeType = "pkcs12" // production and test keys are PKCS12; never guess the format from the file
                storePassword = System.getenv("JARVIS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("JARVIS_KEY_ALIAS")
                keyPassword = System.getenv("JARVIS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
        // Debug builds use Android's own per-machine debug key. They are never published.
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // H1a-0: print the full message of a failed unit test in the CI log (Gradle's default omits it).
    testOptions {
        unitTests.all {
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
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
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    // Android's own org.json is a stub in unit tests, so tests use the real library.
    testImplementation(libs.org.json)
    // Real SQLite for the memory database tests, so the same SQL and schema run as on the phone.
    testImplementation(libs.sqlite.jdbc)
}
