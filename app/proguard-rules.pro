-keep class com.sun.aurum.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**

# kotlinx-datetime (via :shared) carries optional @Serializable annotations that
# reference kotlinx-serialization, which we don't ship (the app serializes with
# org.json). Metadata-only references — safe to suppress; without this R8 fails
# the release build ("Missing class kotlinx.serialization.Serializable").
-dontwarn kotlinx.serialization.**
