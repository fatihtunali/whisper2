# JNA
-keep class com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.platform.win32.**

# LazySodium
-keep class com.goterl.lazysodium.** { *; }

# Native methods
-keepclasseswithmembernames class * { native <methods>; }

# Data models
-keep class com.whisper2.app.data.network.** { *; }
-keep class com.whisper2.app.domain.model.** { *; }

# WebRTC - comprehensive rules to prevent R8 from stripping JNI methods
-keep class org.webrtc.** { *; }
-keep class io.getstream.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# WebRTC native library loading - critical for AAB
-keep class org.webrtc.NativeLibrary { *; }
-keep class org.webrtc.JniCommon { *; }
-keep class org.webrtc.JniHelper { *; }
-keep class org.webrtc.WebRtcClassLoader { *; }

# WebRTC video encoder/decoder factories - must not be stripped
-keep class org.webrtc.DefaultVideoEncoderFactory { *; }
-keep class org.webrtc.DefaultVideoDecoderFactory { *; }
-keep class org.webrtc.HardwareVideoEncoderFactory { *; }
-keep class org.webrtc.SoftwareVideoEncoderFactory { *; }
-keep class org.webrtc.HardwareVideoDecoderFactory { *; }
-keep class org.webrtc.SoftwareVideoDecoderFactory { *; }
-keep class org.webrtc.MediaCodecVideoEncoder { *; }
-keep class org.webrtc.MediaCodecVideoDecoder { *; }
-keep class org.webrtc.VideoCodecInfo { *; }

# WebRTC EGL context - required for video rendering
-keep class org.webrtc.EglBase** { *; }
-keep class org.webrtc.GlUtil { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
