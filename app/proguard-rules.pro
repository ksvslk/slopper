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

# Remove Log.d and Log.v calls from release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# Google ML Kit Text Recognition
-keep class com.google.mlkit.vision.text.** { *; }

# MediaPipe Tasks GenAI (on-device)
-keep class com.google.mediapipe.tasks.** { *; }
-keep class com.google.mediapipe.tasks.genai.** { *; }

# MediaPipe framework
-keep class com.google.mediapipe.framework.** { *; }
-keepclassmembers class * {
    @com.google.mediapipe.framework.annotations.* <methods>;
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# TensorFlow Lite (used by MediaPipe)
-keep class org.tensorflow.lite.** { *; }
-keep interface org.tensorflow.lite.** { *; }

# Protocol Buffers - Critical for MediaPipe GenAI
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite$Builder {
    public <methods>;
}

# Keep all fields in protobuf messages (prevents the modelPath_ field error)
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    *** *_;
}

# Gson (if used by MediaPipe)
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }

# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn com.google.mediapipe.framework.image.BitmapExtractor
-dontwarn com.google.mediapipe.framework.image.ByteBufferExtractor
-dontwarn com.google.mediapipe.framework.image.MPImage
-dontwarn com.google.mediapipe.framework.image.MPImageProperties
-dontwarn com.google.mediapipe.framework.image.MediaImageExtractor