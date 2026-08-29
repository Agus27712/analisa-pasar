# Keep Compose / Kotlin metadata
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable
-keep class kotlin.Metadata { *; }

# OkHttp / JSON
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Common annotations noise
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn org.codehaus.mojo.animalsniffer.**

# Moshi
-keep class com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**

# Security Crypto (EncryptedSharedPreferences)
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# Timber
-keep class timber.log.** { *; }
-dontwarn timber.log.**

# Room
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# App models & Engine logic
-keep class agu.analys.model.** { *; }
-keep class agu.analys.config.** { *; }
-keep class agu.analys.engine.** { *; }
-keep class agu.analys.trading.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Prevent stripping of BuildConfig
-keep class agu.analys.BuildConfig { *; }
