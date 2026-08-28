# Kotlin serialization metadata used by provider and local API DTOs.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Room generated implementations are referenced through generated code; retain DB class names for diagnostics.
-keep class * extends androidx.room.RoomDatabase { *; }

# Ktor uses ServiceLoader/provider metadata for engines and plugins.
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# LiteRT-LM crosses JNI/native boundaries. Preserve public runtime entry points.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
