# Whisper 2 - Android Native App

End-to-end encrypted voice and video calling app built with Kotlin, Jetpack Compose, and WebRTC.

**Tags:** `webrtc` `android` `video-call` `kotlin` `jetpack-compose` `peer-to-peer` `e2e-encryption` `voip` `stream-webrtc` `video-decoder-fix`

---

## WebRTC Video Call Fix (January 2026)

If you're experiencing issues with video calls where:
- Remote video doesn't show (only local video in PiP works)
- App crashes during video calls
- Video decoder JNI errors in logs

### The Solution

**Use Stream WebRTC version 1.3.9 or later** (not older versions like 1.3.0):

```kotlin
// build.gradle.kts
dependencies {
    // WebRTC - Stream WebRTC (latest stable with m125 patches)
    implementation("io.getstream:stream-webrtc-android:1.3.9")
}
```

### Why This Matters

1. **Stream WebRTC 1.3.9** includes m125 patches that fix video decoder factory JNI issues
2. Older versions (1.3.0) have broken `SoftwareVideoDecoderFactory` and `HardwareVideoDecoderFactory`
3. Other libraries like `webrtc-sdk` and `threema-webrtc` require `jni_zero` dependency and crash on startup

### Additional Fixes Applied

1. **Hold MediaStream Reference** - Prevents garbage collection from disposing video tracks:
   ```kotlin
   // In your CallService/WebRTC handler
   private var remoteMediaStream: MediaStream? = null

   override fun onAddStream(stream: MediaStream?) {
       // CRITICAL: Store reference to prevent GC
       remoteMediaStream = stream
       // ... handle tracks
   }
   ```

2. **Prefer H.264 Codec** - Better hardware decoder support on most Android devices:
   ```kotlin
   // Reorder SDP to prefer H.264 over VP8
   private fun preferH264Codec(sdp: String): String {
       // Move H.264 payload type to front of m=video line
   }
   ```

3. **Use DefaultVideoDecoderFactory** - Works reliably with Stream WebRTC 1.3.9:
   ```kotlin
   val decoderFactory = DefaultVideoDecoderFactory(eglContext)
   ```

### Libraries That DON'T Work (as of Jan 2026)

| Library | Issue |
|---------|-------|
| `io.getstream:stream-webrtc-android:1.3.0` | Video decoder JNI crashes |
| `io.github.webrtc-sdk:android:137.7151.05` | Missing `jni_zero` - app won't open |
| `ch.threema:webrtc-android:144.0.0` | Missing `jni_zero` - app won't open |

### Working Configuration

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.getstream:stream-webrtc-android:1.3.9")
}
```

```kotlin
// CallService.kt - PeerConnectionFactory setup
val eglBase = EglBase.create()
val eglContext = eglBase.eglBaseContext

val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, false)
val decoderFactory = DefaultVideoDecoderFactory(eglContext)

peerConnectionFactory = PeerConnectionFactory.builder()
    .setVideoEncoderFactory(encoderFactory)
    .setVideoDecoderFactory(decoderFactory)
    .createPeerConnectionFactory()
```

## Build

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires keystore.properties)
./gradlew assembleRelease
```

## Requirements

- Android SDK 26+ (Android 8.0 Oreo)
- Kotlin 1.9+
- Java 17

## Keywords (for searchability)

WebRTC Android video not working, remote video not showing, WebRTC video decoder crash,
SoftwareVideoDecoderFactory crash, HardwareVideoDecoderFactory JNI error, DefaultVideoDecoderFactory,
stream-webrtc-android video fix, MediaStream garbage collected, VideoTrack disposed,
Android WebRTC 2025 2026, peer-to-peer video call Android, WebRTC Kotlin Compose,
onAddStream video track null, remote video black screen, WebRTC m125 patches,
jni_zero ClassNotFoundException, webrtc-sdk crash, threema-webrtc crash

## License

Proprietary - All rights reserved
