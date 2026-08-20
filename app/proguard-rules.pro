# Keep Compose / Kotlin metadata
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable
-keep class kotlin.Metadata { *; }

# OkHttp / JSON
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# App models used by reflection / serialization
-keep class agu.analys.model.** { *; }
-keep class agu.analys.config.** { *; }
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
