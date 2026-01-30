# Whisper2

End-to-end encrypted messaging and voice/video calling application with native iOS and Android clients.

## Features

- End-to-end encrypted messaging (X25519 key exchange, XSalsa20-Poly1305)
- Voice and video calls via WebRTC
- Group messaging
- Contact backup (encrypted)
- Push notifications (APNS for iOS, FCM for Android)
- Biometric authentication

## Architecture

```
whisper2/
├── server/         # Node.js/TypeScript WebSocket server
├── ios/            # Swift/SwiftUI iOS client
└── android/        # Kotlin/Jetpack Compose Android client
```

---

## Server Setup

### TURN Server Configuration (coturn)

The application uses a TURN server for WebRTC NAT traversal. All calls are routed through TURN relay for reliability.

#### Install coturn

```bash
sudo apt update
sudo apt install coturn
```

#### Enable coturn service

```bash
sudo nano /etc/default/coturn
# Uncomment: TURNSERVER_ENABLED=1
```

#### Configure /etc/turnserver.conf

```ini
# Network
listening-port=3479
tls-listening-port=5350
listening-ip=YOUR_SERVER_PUBLIC_IP
external-ip=YOUR_SERVER_PUBLIC_IP
relay-ip=YOUR_SERVER_PUBLIC_IP

# Domain
realm=turn.yourdomain.com
server-name=turn.yourdomain.com

# Authentication (use static-auth-secret for WebRTC)
use-auth-secret
static-auth-secret=YOUR_SECRET_HERE

# TLS (required for TURNS)
cert=/etc/letsencrypt/live/turn.yourdomain.com/fullchain.pem
pkey=/etc/letsencrypt/live/turn.yourdomain.com/privkey.pem

# Relay ports
min-port=49152
max-port=65535

# Security
no-multicast-peers
no-cli

# Logging
log-file=/var/log/turnserver.log
verbose
```

#### Generate SSL Certificate

```bash
sudo certbot certonly --standalone -d turn.yourdomain.com
```

#### Firewall Rules

Open the following ports:

| Port | Protocol | Purpose |
|------|----------|---------|
| 3479 | TCP/UDP | TURN (plain) |
| 5350 | TCP | TURNS (TLS) |
| 49152-65535 | UDP | Relay ports |

For DigitalOcean Cloud Firewall:
```bash
doctl compute firewall update YOUR_FIREWALL_ID \
  --inbound-rules "protocol:tcp,ports:3479,address:0.0.0.0/0 protocol:udp,ports:3479,address:0.0.0.0/0 protocol:tcp,ports:5350,address:0.0.0.0/0 protocol:udp,ports:49152-65535,address:0.0.0.0/0"
```

#### Start coturn

```bash
sudo systemctl restart coturn
sudo systemctl enable coturn
```

#### Test TURN Server

```bash
# Test from remote machine
turnutils_uclient -T -u testuser -w testpass YOUR_SERVER_IP -p 3479
```

### Server Environment Variables

Create `/home/whisper2/server/.env`:

```env
# TURN Server
TURN_URLS=turn:turn.yourdomain.com:3479?transport=udp,turn:turn.yourdomain.com:3479?transport=tcp,turns:turn.yourdomain.com:5350?transport=tcp
TURN_SECRET=your_turn_secret_here
TURN_TTL_SECONDS=600

# Other server config...
```

---

## iOS App Setup

### WebRTC Configuration

The iOS app is configured to use TURN relay only for maximum compatibility:

**Location:** `ios/Services/CallService.swift`

```swift
// Force all traffic through TURN relay - no direct P2P connections
config.iceTransportPolicy = .relay

// ICE Reliability settings
config.bundlePolicy = .maxBundle          // Bundle all media into single transport
config.rtcpMuxPolicy = .require           // Multiplex RTP/RTCP on same port
config.iceCandidatePoolSize = 1           // Pre-gather candidates for faster connection
config.sdpSemantics = .unifiedPlan
config.continualGatheringPolicy = .gatherContinually
```

### Build Instructions

1. Open `ios/Whisper2.xcworkspace` in Xcode
2. Select your development team in Signing & Capabilities
3. Build and run on device (push notifications require physical device)

---

## Android App Setup

### WebRTC Configuration

The Android app mirrors iOS settings for consistency:

**Location:** `android/app/src/main/java/com/whisper2/app/services/calls/CallService.kt`

```kotlin
val config = PeerConnection.RTCConfiguration(iceServers).apply {
    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY

    // Force all traffic through TURN relay - no direct P2P connections
    iceTransportsType = PeerConnection.IceTransportsType.RELAY

    // ICE Reliability settings
    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
    iceCandidatePoolSize = 1
}
```

### Background Connection (Important for Notifications)

The Android app includes several mechanisms to maintain WebSocket connection in background:

#### 1. Connection Foreground Service

A foreground service keeps the app alive in background:

**Location:** `android/app/src/main/java/com/whisper2/app/services/connection/ConnectionForegroundService.kt`

- Shows persistent "Connected" notification
- Monitors WebSocket state and triggers reconnect
- Uses wake locks during reconnection
- Restarts automatically if killed (START_STICKY)

#### 2. WorkManager Periodic Sync

Fallback mechanism if foreground service is killed:

**Location:** `android/app/src/main/java/com/whisper2/app/services/sync/MessageSyncWorker.kt`

- Runs every 15 minutes (WorkManager minimum)
- Triggers reconnect if disconnected
- Network-aware (only runs when connected)

#### 3. Boot Receiver

Restarts services after device reboot:

**Location:** `android/app/src/main/java/com/whisper2/app/services/connection/BootReceiver.kt`

#### 4. Wake Locks

Wake locks prevent Android from killing the process during critical operations:

- `WsClient.kt` - During WebSocket connection/reconnection
- `FcmService.kt` - During FCM message handling
- `ConnectionForegroundService.kt` - During reconnection attempts
- `MessageSyncWorker.kt` - During sync work

### Battery Optimization Exemption

For best notification reliability, users should exempt the app from battery optimization:

**Helper:** `android/app/src/main/java/com/whisper2/app/utils/BatteryOptimizationHelper.kt`

#### How Users Can Enable:

**Option 1: Via App (Recommended)**
- The app can request exemption via system dialog
- Call `BatteryOptimizationHelper.requestBatteryOptimizationExemption(context)`

**Option 2: Manual**
1. Go to **Settings > Apps > Whisper2 > Battery**
2. Select **Unrestricted** or **Don't optimize**

Or:
1. Go to **Settings > Battery > Battery optimization**
2. Select **All apps** from dropdown
3. Find **Whisper2** and select **Don't optimize**

### Required Permissions

The following permissions are required for background operation:

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

### Build Instructions

```bash
cd android

# Debug build
./gradlew assembleDebug

# Release build (requires keystore configuration)
./gradlew assembleRelease
```

### Requirements

- Android SDK 26+ (Android 8.0 Oreo)
- Kotlin 1.9+
- Java 17

---

## WebRTC ICE Configuration Explained

| Setting | Value | Purpose |
|---------|-------|---------|
| `iceTransportPolicy` | `relay` | Force all traffic through TURN server (no direct P2P) |
| `bundlePolicy` | `maxBundle` | Bundle all media streams into single transport |
| `rtcpMuxPolicy` | `require` | Multiplex RTP and RTCP on same port |
| `iceCandidatePoolSize` | `1` | Pre-gather ICE candidates for faster connection |
| `continualGatheringPolicy` | `gatherContinually` | Keep gathering candidates even after connection |

### Why Relay-Only?

- **Reliability**: TURN relay works through all firewalls and NAT types
- **Consistency**: Same behavior across all network conditions
- **Privacy**: User IPs are not exposed to peers
- **Debugging**: Easier to troubleshoot connection issues

---

## Troubleshooting

### TURN Server Not Reachable

1. Check firewall rules (both OS and cloud provider)
2. Verify coturn is running: `systemctl status coturn`
3. Check coturn logs: `tail -f /var/log/turnserver.log`
4. Test with turnutils: `turnutils_uclient -T -u user -w pass SERVER_IP -p 3479`

### Android App Disconnects in Background

1. **Enable battery optimization exemption** (see above)
2. **Disable aggressive battery saving** on device
3. **Check manufacturer-specific settings**:
   - Samsung: Disable "Put unused apps to sleep"
   - Xiaomi: Enable "Autostart", disable "Battery saver"
   - Huawei: Enable "Ignore battery optimizations"
   - OnePlus: Disable "Adaptive Battery"

### Calls Not Connecting

1. Check TURN server is accessible
2. Verify TURN credentials are correct in server `.env`
3. Check WebSocket connection is established
4. Look for ICE candidate errors in logs

### Push Notifications Not Working (Android)

1. Ensure FCM is configured correctly
2. Check battery optimization exemption is enabled
3. Verify foreground service is running (check notification)
4. Test with app in foreground first

---

## Security Notes

- Never commit secrets (`.env`, API keys, TURN secrets) to git
- TURN credentials are time-limited (TTL configurable)
- All messaging is end-to-end encrypted
- Keys are derived from mnemonic using BIP39/HKDF

---

## License

Proprietary - All rights reserved

---

## Recent Updates (January 2026)

### TURN Server Consolidation
- Consolidated multiple TURN domains to single `turn.aiakademiturkiye.com`
- Configured coturn with proper TLS support
- Updated server and client configurations

### WebRTC Reliability Improvements
- Forced TURN relay-only mode (no direct P2P)
- Added ICE reliability settings (bundle policy, RTCP mux)
- Applied H.264 codec preference for better hardware support

### Android Background Connection Fixes
- Added `ConnectionForegroundService` for persistent WebSocket
- Added `MessageSyncWorker` for periodic background sync
- Added wake locks throughout connection lifecycle
- Added `BootReceiver` for service restart after reboot
- Added `BatteryOptimizationHelper` for exemption requests

---

## Keywords (for searchability)

**Tags:** `webrtc` `turn-server` `coturn` `encrypted-messaging` `e2e-encryption` `voip` `video-call` `android` `ios` `swift` `kotlin` `jetpack-compose` `swiftui` `websocket` `push-notifications` `fcm` `apns` `nacl` `x25519` `end-to-end-encryption`

WebRTC TURN server setup, coturn configuration 2025 2026, Android app disconnects in background fix,
Android foreground service WebSocket, wake lock Android background, battery optimization exemption Android,
WebRTC relay only mode, iceTransportPolicy relay, WebRTC ICE configuration, TURN server not working fix,
Android WorkManager background sync, TURN over TLS TURNS configuration, WebRTC NAT traversal,
encrypted voice call app, encrypted video call app, secure messaging app, private messenger,
Android notification not working background, iOS VoIP push notifications, WebSocket keep alive Android,
coturn authentication setup, TURN credential generation, WebRTC peer connection fails fix,
Android Doze mode WebSocket, battery saver kills app fix, foreground service data sync Android 14
