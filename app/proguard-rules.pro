# ============================================================================
# Pixel Brain Reader — R8 / ProGuard rules
#
# Layout: each section keeps just what the runtime touches reflectively or
# via ServiceLoader. Everything else is fair game for R8 to shrink/obfuscate.
# ============================================================================

# -----------------------------------------------------------------------------
# Crash / debugging
# -----------------------------------------------------------------------------
# Keep stack traces useful when symbolicated. SourceFile is renamed to "SourceFile"
# so the obfuscation map (in build/outputs/mapping/release/mapping.txt) is the
# only way to deobfuscate — i.e. attackers reading a crash log can't recover
# original class names from stack traces alone.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Reflection metadata used by every Kotlin reflective lookup (serialization,
# Hilt, Room, etc.). Cheap; required.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations,AnnotationDefault
-keepattributes Exceptions

# -----------------------------------------------------------------------------
# Log stripping (release builds only — debug runs untouched)
# -----------------------------------------------------------------------------
# The default `proguard-android-optimize.txt` does NOT strip Log.d / Log.v.
# We do it ourselves so RAG_DEBUG / FileAudit / Cortex traces never reach
# the release-build Logcat. Log.i / Log.w / Log.e are preserved — code that
# logs sensitive paths at Log.i must be downgraded to Log.d first.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}

# -----------------------------------------------------------------------------
# kotlinx.serialization
# -----------------------------------------------------------------------------
# @Serializable classes have compile-time-generated `$serializer` companions
# that the runtime locates by reflection. Without these rules R8 obfuscates
# the companion names and decodeFromString explodes with MissingFieldException.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep every kotlinx.serialization generated companion + serializer for our app.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclasseswithmembers class **$$serializer {
    *** descriptor;
}
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# Keep our serializable DTOs explicitly (defensive — we have a lot of them
# scattered across packages).
-keep @kotlinx.serialization.Serializable class cloud.wafflecommons.pixelbrainreader.** { *; }
-keep class cloud.wafflecommons.pixelbrainreader.**$$serializer { *; }

# -----------------------------------------------------------------------------
# Gson (legacy paths in HabitRepository + a few caches)
# -----------------------------------------------------------------------------
-keep class cloud.wafflecommons.pixelbrainreader.data.model.** { *; }
-keep class cloud.wafflecommons.pixelbrainreader.data.gamification.model.** { *; }
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.** { *; }
-keep class com.google.gson.stream.** { *; }
-dontwarn com.google.gson.**
# Generic TypeToken support
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# -----------------------------------------------------------------------------
# Room
# -----------------------------------------------------------------------------
# Entity field names map directly to SQLite columns. R8 renaming them breaks
# the schema. Keep all entities and their constructors; Room's generated
# Impl classes are kept automatically because they're directly referenced.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.TypeConverter class * { *; }
-keepclassmembers class * {
    @androidx.room.TypeConverter *;
}
-keepclassmembers @androidx.room.Entity class * {
    <init>(...);
    <fields>;
}
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# Our entity package — defensive shotgun keep for column-name stability.
-keep class cloud.wafflecommons.pixelbrainreader.data.local.entity.** { *; }

# -----------------------------------------------------------------------------
# Hilt / Dagger
# -----------------------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ApplicationComponentManager
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.lifecycle.HiltViewModelFactory
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
    @javax.inject.Inject <methods>;
}

# WorkManager + Hilt's @HiltWorker factory binding — runtime reflective lookup.
-keep @androidx.hilt.work.HiltWorker class * { *; }
-keep class * extends androidx.work.CoroutineWorker { <init>(...); }
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class androidx.hilt.work.HiltWorkerFactory { *; }
-keepclassmembers class * {
    @dagger.assisted.AssistedInject <init>(...);
}

# WorkManager also instantiates InputMergers reflectively by class NAME with a
# no-arg constructor — Glance's widget-session jobs use OverwritingInputMerger.
# R8 stripped that constructor in release: every widget re-render job died with
# NoSuchMethodException (widgets froze, v10 RC0 dogfood find). Same reflective
# pattern for Glance ActionCallbacks (widget tap handlers).
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class androidx.work.OverwritingInputMerger { <init>(); }
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# -----------------------------------------------------------------------------
# Jetpack Compose (mostly handled by AGP — defensive only)
# -----------------------------------------------------------------------------
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keep @androidx.compose.runtime.Stable class *
-keep @androidx.compose.runtime.Immutable class *
-dontwarn androidx.compose.**

# -----------------------------------------------------------------------------
# Retrofit (we use it for a couple of REST clients)
# -----------------------------------------------------------------------------
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# Keep our DTOs / Retrofit interfaces for safety (these are deserialized).
-keep class cloud.wafflecommons.pixelbrainreader.data.remote.model.** { *; }
-keep interface cloud.wafflecommons.pixelbrainreader.data.remote.api.** { *; }

# OpenMeteo weather DTOs live DIRECTLY in data.remote (not data.remote.model),
# so the wildcards above miss them. Gson maps the JSON keys (daily, weathercode,
# temperature_2m_max, …) to these fields by reflection; without an explicit keep,
# R8 renames them and parsing silently yields null → weather fails in RELEASE only.
-keep class cloud.wafflecommons.pixelbrainreader.data.remote.OpenMeteoResponse { *; }
-keep class cloud.wafflecommons.pixelbrainreader.data.remote.DailyUnits { *; }

# Two more Gson-reflected types OUTSIDE the kept packages (same failure class as
# OpenMeteo): DailyHealthMetrics is Gson().fromJson'd in AutomateHabitsUseCase /
# ApplyHealthSynergyUseCase, and the Attribute enum round-trips through a Gson
# TypeToken map in GamificationPreferences. R8 renaming fields/enum constants
# breaks both silently — and since that JSON is persisted, a rename also corrupts
# stored data across builds.
-keep class cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics { *; }
-keep class cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute { *; }

# -----------------------------------------------------------------------------
# Security (EncryptedSharedPreferences / CryptoManager)
# -----------------------------------------------------------------------------
-keep class cloud.wafflecommons.pixelbrainreader.data.local.security.** { *; }
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# -----------------------------------------------------------------------------
# Health Connect
# -----------------------------------------------------------------------------
-keep class androidx.health.connect.** { *; }
-keep class androidx.health.platform.** { *; }
-dontwarn androidx.health.**

# -----------------------------------------------------------------------------
# JGit (Eclipse) — uses java.util.ServiceLoader for transport providers
# -----------------------------------------------------------------------------
-keep class org.eclipse.jgit.** { *; }
-keep interface org.eclipse.jgit.** { *; }
-keepnames class org.eclipse.jgit.transport.**
# ServiceLoader entries are resources, not classes — preserve them.
# (AGP's resource shrinker can drop META-INF/services without this).
-keep class * implements org.eclipse.jgit.transport.Transport
-keep class * implements org.eclipse.jgit.lib.ConfigConstants
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**

# -----------------------------------------------------------------------------
# flexmark-java html→markdown converter (web import / universal collector)
# -----------------------------------------------------------------------------
# Flexmark resolves its extensions and DataKey option holders reflectively; R8
# stripping/renaming them breaks the ACTION_SEND / pixelbrain://import reader
# pipeline ONLY in release builds (debug has R8 off).
-keep class com.vladsch.flexmark.** { *; }
-dontwarn com.vladsch.flexmark.**

# -----------------------------------------------------------------------------
# MediaPipe Tasks (TFLite text embedder)
# -----------------------------------------------------------------------------
-keep class com.google.mediapipe.** { *; }
-keep interface com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# -----------------------------------------------------------------------------
# On-device RAG embedder: TensorFlow Lite interpreter + DJL HuggingFace tokenizer
# -----------------------------------------------------------------------------
# Both cross a JNI boundary (libtensorflowlite_jni.so / libdjl_tokenizer.so) and
# resolve Java classes + methods by fully-qualified NAME from native code; DJL
# additionally uses java.util.ServiceLoader for engine/tokenizer discovery. R8
# renaming those classes breaks the native lookup and throws UnsatisfiedLinkError
# / NoSuchMethodError ONLY in release (debug has R8 off). VectorSearchEngine.kt
# depends on both, so without these keeps the entire local RAG pipeline (indexing
# + Oracle search) crashes after minification. TFLite 2.16.1 ships partial
# consumer rules; we pin it explicitly anyway as zero-risk insurance.
-keep class org.tensorflow.lite.** { *; }
-keepclasseswithmembernames class org.tensorflow.lite.** { native <methods>; }
-dontwarn org.tensorflow.**

-keep class ai.djl.** { *; }
-keep interface ai.djl.** { *; }
-keepclasseswithmembernames class ai.djl.** { native <methods>; }
-dontwarn ai.djl.**

# -----------------------------------------------------------------------------
# Google ML Kit GenAI (Gemini Nano) + Google AI Edge AICore
# -----------------------------------------------------------------------------
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-keep class com.google.ai.edge.aicore.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.ai.edge.aicore.**

# -----------------------------------------------------------------------------
# Google Auth / Identity / Calendar / Tasks
# -----------------------------------------------------------------------------
-keep class com.google.android.gms.** { *; }
-keep class com.google.android.libraries.identity.** { *; }
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.calendar.** { *; }
-keep class com.google.api.services.tasks.** { *; }
-keep class com.google.api.client.googleapis.** { *; }
-keepclassmembers class com.google.api.** {
    @com.google.api.client.util.Key <fields>;
}
-dontwarn com.google.api.**
-dontwarn com.google.android.gms.**
-dontwarn com.google.android.libraries.identity.**

# -----------------------------------------------------------------------------
# Credential Manager (androidx.credentials) + Sign in with Google (googleid)
# -----------------------------------------------------------------------------
# CredentialManager resolves provider implementations REFLECTIVELY:
#   1. ServiceLoader scans META-INF/services for CredentialProvider entries,
#      which point at androidx.credentials.playservices.CredentialProviderPlayServicesImpl.
#   2. GoogleIdTokenCredential.createFrom(Bundle) matches the response by class
#      name + reads BUNDLE_KEY_* constants via reflection.
# When R8 obfuscates ANY of these, release builds fail with one of:
#   - NoCredentialException("No credentials available")
#   - GetCredentialUnknownException
#   - IllegalArgumentException inside createFrom(...) -> credential decoding
#   - "Failed to load provider" at CredentialManager.getCredential
# Debug builds never see this because R8 is off there.
-keep class androidx.credentials.** { *; }
-keep interface androidx.credentials.** { *; }
-keep class androidx.credentials.playservices.** { *; }
-keep interface androidx.credentials.playservices.** { *; }
-keep class * extends androidx.credentials.Credential { *; }
-keep class * extends androidx.credentials.CredentialOption { *; }
# NOTE: `-keepresources` is NOT a valid R8/ProGuard option — it hard-fails the
# release build ("Unknown option"). The META-INF/services/CredentialProvider
# ServiceLoader entry is preserved automatically: R8 models ServiceLoader.load()
# and keeps the service file as long as the implementation class is kept with its
# original name, which the `androidx.credentials.playservices.**` keep above does.
-dontwarn androidx.credentials.**

# GetGoogleIdOption / GetSignInWithGoogleOption / GoogleIdTokenCredential.
# The static `createFrom` factories and the BUNDLE_KEY_* / TYPE_* string
# constants are read reflectively from the credential response Bundle.
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep interface com.google.android.libraries.identity.googleid.** { *; }
-keepclassmembers class com.google.android.libraries.identity.googleid.** {
    public static *** createFrom(...);
    public static final java.lang.String BUNDLE_KEY_*;
    public static final java.lang.String TYPE_*;
    <init>(...);
}
-dontwarn com.google.android.libraries.identity.googleid.**

# CredentialManager exception classes are matched by FQN by the framework
# to translate Binder errors back into typed exceptions. R8 renaming them
# turns every failure into "Unknown".
-keep class androidx.credentials.exceptions.** { *; }

# Play Services Auth subsurfaces explicitly (covered by the gms.** wildcard
# above, but the Credential Manager ↔ Play Services bridge reads these by
# FQN so they're worth pinning).
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.auth.api.identity.** { *; }
-keep class com.google.android.gms.common.api.** { *; }
-keep class com.google.android.gms.tasks.** { *; }

# -----------------------------------------------------------------------------
# kaml (Kotlin YAML — built on kotlinx.serialization + SnakeYAML)
# -----------------------------------------------------------------------------
-keep class com.charleskorn.kaml.** { *; }
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**
-dontwarn com.charleskorn.kaml.**

# -----------------------------------------------------------------------------
# Vico charts (has its own consumer rules; keep for safety)
# -----------------------------------------------------------------------------
-keep class com.patrykandpatrick.vico.** { *; }
-dontwarn com.patrykandpatrick.vico.**

# -----------------------------------------------------------------------------
# Markwon (Markdown rendering)
# -----------------------------------------------------------------------------
-keep class io.noties.markwon.** { *; }
-keep class io.noties.prism4j.** { *; }
-dontwarn io.noties.**

# -----------------------------------------------------------------------------
# Coroutines internals (defensive — most rules ship via consumer-proguard)
# -----------------------------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.CoroutineExceptionHandler {
    <init>(...);
}
-dontwarn kotlinx.coroutines.**

# -----------------------------------------------------------------------------
# Misc warnings to silence (clean log on R8 pass)
# -----------------------------------------------------------------------------
-dontwarn javax.annotation.**
-dontwarn sun.misc.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Desktop JDK classes referenced by Google API Client / AutoValue transitive
# deps but never invoked at runtime on Android. R8 errors on them by default.
-dontwarn javax.lang.model.**
-dontwarn javax.naming.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
-dontwarn org.apache.http.**

# commons-compress (transitive via JGit) references commons-lang3 in its tar
# archiver code path, which we neither bundle nor invoke. R8 errors on the
# missing class by default; this acknowledges it is intentionally absent.
-dontwarn org.apache.commons.lang3.**
