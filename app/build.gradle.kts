plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.atlas.virtualspace"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.atlas.virtualspace"
        minSdk = 33  // Android 13+
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-alpha"

        // Support both 32-bit and 64-bit for game compatibility
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room schema export
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        // VectorDrawables support
        vectorDrawables {
            useSupportLibrary = true
        }

        // CMake native build configuration
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            buildConfigField("boolean", "ENABLE_LOGCAT", "true")
            buildConfigField("boolean", "ENABLE_HOOK_DEBUG", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = true
            buildConfigField("boolean", "ENABLE_LOGCAT", "false")
            buildConfigField("boolean", "ENABLE_HOOK_DEBUG", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        viewBinding = false
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-Xcontext-receivers"
        )
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
        disable += "MissingTranslation"
    }

    // Split APKs by ABI for smaller downloads
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }
}

dependencies {
    // ─── AndroidX Core ────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.window:window:1.3.0")

    // ─── Compose BOM (2024.12.01) ────────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime-tracing")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ─── Lifecycle ───────────────────────────────────────────
    val lifecycleVersion = "2.8.7"
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-process:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-service:$lifecycleVersion")

    // ─── Navigation ──────────────────────────────────────────
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // ─── Hilt (DI) ───────────────────────────────────────────
    implementation("com.google.dagger:hilt-android:2.54")
    ksp("com.google.dagger:hilt-compiler:2.54")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // ─── Room (Database) ─────────────────────────────────────
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // ─── Coroutines & Flow ───────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ─── DataStore ───────────────────────────────────────────
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ─── Pine (Method Hooking - No Root, Android 13+) ────────
    implementation("top.canyie.pine:core:0.3.0")
    implementation("top.canyie.pine:xposed:0.2.0")

    // ─── Shizuku (Elevated Operations Without Root) ──────────
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
    implementation("dev.rikka.hidden:stub:4.3.0")

    // ─── APK Parsing ─────────────────────────────────────────
    implementation("net.dongliu:apk-parser:2.6.10")
    implementation("com.google.code.gson:gson:2.11.0")

    // ─── XAPK / ZIP handling ─────────────────────────────────
    implementation("org.apache.commons:commons-compress:1.27.1")

    // ─── Logging ─────────────────────────────────────────────
    implementation("com.jakewharton.timber:timber:5.0.1")

    // ─── File Picker ─────────────────────────────────────────
    implementation("com.darkrockstudios:mpfilepicker:3.1.0")

    // ─── Coil (Image Loading) ────────────────────────────────
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")

    // ─── Accompanist ─────────────────────────────────────────
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.36.0")

    // ─── LibreLocator (GG Compatibility) ─────────────────────
    // Memory map exposure for GameGuardian compatibility
    implementation("com.squareup.okio:okio:3.9.1")

    // ─── Testing ─────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(composeBom)
}
