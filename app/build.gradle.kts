plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// A fixed signing key, so every build installs as an update over the last one.
// CI decodes it from the SIGNING_KEYSTORE_BASE64 / SIGNING_PASSWORD secrets;
// without it, release builds fall back to the (per-machine) debug key.
val signingKeystore: String? = System.getenv("SIGNING_KEYSTORE_PATH")
val signingPassword: String? = System.getenv("SIGNING_PASSWORD")

android {
    namespace = "app.contactscleaner"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.contactscleaner"
        minSdk = 26
        targetSdk = 35
        // Each CI run gets a higher version, so phones always see it as an update.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.2.$build"
    }

    signingConfigs {
        if (signingKeystore != null && signingPassword != null) {
            create("fixed") {
                storeFile = file(signingKeystore)
                storePassword = signingPassword
                keyAlias = "contacts-cleaner"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("fixed") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
