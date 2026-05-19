import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
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

    defaultConfig {
        applicationId = "cloud.wafflecommons.pixelbrainreader"
        minSdk = 36
        compileSdk = 36
        targetSdkPreview = "36"
        // V7 milestone: Private-Vault RAG security model, manual indexing,
        // content-fingerprint reindex, vault-rooted health sync.
        versionCode = 700
        versionName = "7.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        val key = localProperties.getProperty("geminiApiKey") ?: ""
        buildConfigField("String", "geminiApiKey", "\"$key\"")
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
    kotlinOptions {
        jvmTarget = "17"
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

    // AI Models (TFLite) must not be compressed. MediaPipe loads from the
    // APK asset path directly — compressing would break it.
    androidResources {
        noCompress += "tflite"
    }
}

// CORRECTION DU CRASH "Duplicate Class"
configurations.all {
    resolutionStrategy {
        exclude(group = "org.jetbrains", module = "annotations-java5")
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
    implementation(libs.google.ai.client)
    // Gemini Nano on-device (AICore) — privacy-first local inference, no silent cloud fallback
    implementation(libs.google.ai.edge.aicore)
    implementation(libs.mediapipe.tasks.text)
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

