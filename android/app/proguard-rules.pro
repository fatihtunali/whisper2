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

# WebRTC
-keep class org.webrtc.** { *; }

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
