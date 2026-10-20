# Luna ProGuard Rules

# Desert Ant Labs JNI & Native Bindings (Ear, Voz, Clear)
-keep class ai.desertant.** { *; }
-keepclassmembers class ai.desertant.** {
    native <methods>;
    *;
}
-keepnames class ai.desertant.**
-keep interface ai.desertant.** { *; }

# Sherpa-ONNX Neural TTS JNI Bindings
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** {
    native <methods>;
    *;
}
-keepnames class com.k2fsa.sherpa.onnx.**

# LiteRT / TensorFlow Lite Runtime & Delegates
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ultrapg.luna.**$$serializer { *; }
-keepclassmembers class com.ultrapg.luna.** {
    *** Companion;
}
-keepclasseswithmembers class com.ultrapg.luna.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp & Commons Compress
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.osgi.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
