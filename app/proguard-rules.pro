# TeleDrive ProGuard Rules

# Keep TDLib JNI classes
-keep class org.drinkless.tdlib.** { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Room entities
-keep class com.teledrive.app.data.db.entity.** { *; }

# Keep Native / Rust JNI methods
-keep class com.teledrive.app.ai.RustFaceEngine { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep ZXing QR classes
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**


