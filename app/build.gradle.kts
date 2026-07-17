import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.googleServices)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "cloud.wafflecommons.pixelbrainreader"

    // NDK r26d. The actual native code in this project is a no-op stub
    // (`src/main/cpp/stub.cpp`). It exists ONLY to make AGP bundle
    // libc++_shared.so, which DJL's `libdjl_tokenizer.so` dynamically
    // links against. AGP auto-installs this NDK on first build if missing.
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "cloud.wafflecommons.pixelbrainreader"
        minSdk = 36
        compileSdk = 37
        targetSdk = 36
        // 8.0 milestone: full Material 3 Expressive redesign (theme, motion, haptics,
        // Expressive components) + a major toolchain jump (AGP 9.2.1 / Kotlin 2.2.10 /
        // Compose 1.12 / compileSdk 37), Life Stats revamp, reminder-notification overhaul.
        // versionName is the single source of truth — surfaced in Settings via
        // BuildConfig.VERSION_NAME (no more hardcoded string to drift).
        versionCode = 920
        versionName = "9.2.0"

        // GitHub OAuth "Device Flow" client id (public — no secret needed for device flow).
        // Register an OAuth App at github.com/settings/developers, enable Device Flow, and
        // put its client id in local.properties as `githubOauthClientId=...`. Empty by
        // default → the "Login with GitHub" button is simply hidden and PAT login is used.
        buildConfigField(
            "String",
            "GITHUB_OAUTH_CLIENT_ID",
            "\"${localProperties.getProperty("githubOauthClientId", "")}\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // The stub native library at src/main/cpp triggers libc++_shared.so
        // packaging. We only ship for the four ABIs Android currently supports
        // — without this filter, AGP would also try to build for emulator
        // variants we don't care about.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // Force dynamic linkage against the C++ STL. With the default
                // c++_static, AGP doesn't bundle libc++_shared.so — and our
                // stub library is too trivial to trigger automatic packaging.
                // DJL's libdjl_tokenizer.so needs the shared STL at runtime.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Local release signing — credentials come from local.properties (gitignored).
    // Required keys: storeFile, storePassword, keyAlias, keyPassword.
    // Falls back to debug signing if any field is missing so debug builds keep working.
    signingConfigs {
        create("release") {
            val storeFilePath = localProperties.getProperty("storeFile")
            val storePwd = localProperties.getProperty("storePassword")
            val alias = localProperties.getProperty("keyAlias")
            val keyPwd = localProperties.getProperty("keyPassword")
            if (!storeFilePath.isNullOrBlank() && !storePwd.isNullOrBlank()
                && !alias.isNullOrBlank() && !keyPwd.isNullOrBlank()
            ) {
                storeFile = file(storeFilePath)
                storePassword = storePwd
                keyAlias = alias
                keyPassword = keyPwd
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // If local.properties supplies signing credentials, use them; else
            // AGP falls back to the debug keystore (clearly logged at build time).
            val releaseConfig = signingConfigs.getByName("release")
            signingConfig = if (releaseConfig.storeFile != null) releaseConfig
                            else signingConfigs.getByName("debug")
        }
        debug {
            // Debug builds stay unminified for fast iteration.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE.txt"
        }
    }

    // Make android.util.Log return defaults instead of throwing in JVM unit tests
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    // AI Models (TFLite) must not be compressed. We mmap them from disk
    // after staging to cacheDir; the AAPT compression heuristic must not
    // touch them. The tokenizer JSON is just a config blob — compressible.
    androidResources {
        noCompress += "tflite"
    }
}

// Built-in Kotlin (AGP 9): compiler options live here now, not android.kotlinOptions.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// CORRECTION DU CRASH "Duplicate Class"
configurations.all {
    resolutionStrategy {
        exclude(group = "org.jetbrains", module = "annotations-java5")
        // material3 1.5.0-alpha23 requires Compose 1.12.0-alpha03. Force the whole
        // Compose core to that version so BOM-pinned artifacts (ui-tooling, ui-test,
        // etc.) don't skew against the version material3 drags in.
        eachDependency {
            if (requested.group in setOf(
                    "androidx.compose.ui",
                    "androidx.compose.foundation",
                    "androidx.compose.runtime",
                    "androidx.compose.animation"
                )
            ) {
                useVersion("1.12.0-alpha03")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)


    // Fold & Adaptive
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)
    implementation(libs.androidx.material3.adaptive.navigation)
    implementation(libs.androidx.material3.adaptive.navigation.suite)
    implementation(libs.androidx.window)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.compose.remote.creation.core)
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    
    // WorkManager & Hilt Work
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Utils
    implementation(libs.coil.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.health.connect.client)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.health.connect)

    // Images & Markdown
    implementation(libs.coil.compose)
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.linkify)
    implementation(libs.markwon.image)
    // Syntax Highlighting
    implementation(libs.markwon.syntax.highlight)
    implementation(libs.prism4j)
    
    // JGit (Local-First Version Control)
    implementation(libs.jgit)

    // Location
    implementation(libs.play.services.location)

    // HTML Parsing & Conversion (Phase B: Universal Collector)
    implementation(libs.jsoup)
    implementation(libs.flexmark.html2md.converter)
    implementation(libs.markwon.html)

    // AI Core & MediaPipe (V4.0: Neural Vault)
    implementation(libs.kotlinx.coroutines.play.services)
    // Gemini Nano on-device (AICore) — privacy-first local inference, no silent cloud fallback
    implementation(libs.google.ai.edge.aicore)
    implementation(libs.mediapipe.tasks.text)
    // Phase-3 local embedder: raw TFLite + HuggingFace tokenizer.
    // Replaces MediaPipe TextEmbedder for the multilingual MiniLM model.
    implementation(libs.tensorflow.lite)
    implementation(libs.djl.huggingface.tokenizers)
    implementation(libs.djl.android.tokenizer.native)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.mlkit.genai.proofreading)
    implementation(libs.mlkit.genai.rewriting)
    implementation(libs.mlkit.genai.summarization)

    // Home Screen Widget (Jetpack Glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)

    // RSS Parser
    implementation(libs.rssparser)

    // YAML & Serialization
    implementation(libs.snakeyaml)
    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.json)

    // Charting (Vico)
    implementation(libs.vico.compose.m3)
    implementation(libs.vico.core)
    implementation(libs.vico.views)

    // Sensory Polish
    implementation(libs.konfetti.compose)

    // Google Auth & Ecosystem
    implementation(libs.play.services.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.google.api.client.android)
    implementation(libs.google.api.services.calendar)
    implementation(libs.google.api.services.tasks)
}

