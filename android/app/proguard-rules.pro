# JNA
-keep class com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.platform.win32.**

# LazySodium
-keep class com.goterl.lazysodium.** { *; }

# Native methods
-keepclasseswithmembernames class * { native <methods>; }

# Data models and service payloads
-keep class com.whisper2.app.data.network.** { *; }
-keep class com.whisper2.app.domain.model.** { *; }
-keep class com.whisper2.app.services.groups.*Payload { *; }
-keep class com.whisper2.app.services.groups.RecipientEnvelope { *; }
-keep class com.whisper2.app.services.groups.ServerGroup { *; }
-keep class com.whisper2.app.services.groups.ServerGroupMember { *; }
-keep class com.whisper2.app.services.calls.*Payload { *; }
-keep class com.whisper2.app.services.auth.*Payload { *; }

# Keep all data classes used with Gson serialization
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

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
