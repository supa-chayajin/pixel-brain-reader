# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- Stabilization Pack Rules ---

# 1. Serialization (GSON)
# Critical: Keep model classes to prevent JSON parsing failures
-keep class cloud.wafflecommons.pixelbrainreader.data.model.** { *; }
-keep class cloud.wafflecommons.pixelbrainreader.data.gamification.model.** { *; }

# Gson specific
-keepattributes Signature
-keepattributes *Annotation*
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.stream.** { *; }

# 2. KeyStore & Security
# Keep SecretManager to ensure reflection-based encryption works if needed (usually safe, but defensive)
-keep class cloud.wafflecommons.pixelbrainreader.data.local.security.** { *; }

# 3. Networking (Retrofit)
# Retrofit uses reflection to generate implementation of interfaces
-keep class cloud.wafflecommons.pixelbrainreader.data.remote.** { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# 4. Dependency Injection (Hilt/Dagger)
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.internal.define.ComponentProcessor
-keep class * extends dagger.hilt.internal.define.ComponentBuilder

# 5. Connect Health (Google Health)
-keep class androidx.health.connect.** { *; }

# 6. Graphs & Charts (Vico)
-keep class com.patrykandpatrick.vico.** { *; }

# 7. Coroutines & Debugging
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.CoroutineExceptionHandler {
    <init>(...);
}

# 8. JGit (Reflection used in some parts)
-keep class org.eclipse.jgit.** { *; }

# 9. General Safety
-dontwarn javax.annotation.**
-dontwarn sun.misc.**