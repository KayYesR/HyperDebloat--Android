plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release builds are signed in GitHub Actions from repository secrets (see .github/workflows/build.yml).
val keystoreFile = System.getenv("HD_KEYSTORE_FILE")

android {
    namespace = "io.github.kayyesr.hyperdebloat"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.kayyesr.hyperdebloat"
        // Wireless debugging pairing exists from Android 11 on.
        minSdk = 30
        targetSdk = 35
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 8
        versionName = (project.findProperty("versionName") as String?) ?: "0.6.0"
        // Phones only; leaving out the x86 copies of the TLS library keeps the APK smaller.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("HD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HD_KEY_ALIAS")
                keyPassword = System.getenv("HD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Shrinking is off: the ADB and crypto libraries rely on reflection.
            isMinifyEnabled = false
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Screenshot tests write PNGs to app/build/outputs/roborazzi when run.
            all {
                it.systemProperty("roborazzi.test.record", "true")
                // Optional: a folder holding Robolectric's android-all jar, for builds without Maven Central access.
                System.getenv("ROBOLECTRIC_JARS")?.let { dir ->
                    it.systemProperty("robolectric.offline", "true")
                    it.systemProperty("robolectric.dependency.dir", dir)
                }
            }
        }
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/versions/**",
                "META-INF/*.kotlin_module",
                "org/bouncycastle/pqc/**",
                "org/bouncycastle/x509/*.properties",
                "org/bouncycastle/pkix/*.properties",
            )
        }
    }
}

dependencies {
    // Wireless debugging: pairing and an ADB connection to the phone's own adbd.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Screenshot tests of the screens, rendered on the JVM (no phone needed).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.39.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.39.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
