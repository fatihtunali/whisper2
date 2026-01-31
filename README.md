# Whisper2

A secure, end-to-end encrypted messaging platform with voice and video calling capabilities. Built with privacy-first architecture using modern cryptographic standards.

![Platform](https://img.shields.io/badge/Platform-iOS%20%7C%20Android-blue)
![License](https://img.shields.io/badge/License-Proprietary-red)
![Version](https://img.shields.io/badge/Version-1.0.77-green)

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Cryptography](#cryptography)
- [Protocol](#protocol)
- [Server](#server)
- [iOS Client](#ios-client)
- [Android Client](#android-client)
- [WebRTC Configuration](#webrtc-configuration)
- [Security](#security)
- [Troubleshooting](#troubleshooting)

---

## Overview

Whisper2 is a cross-platform secure messaging application that prioritizes user privacy through end-to-end encryption. All messages, calls, and attachments are encrypted on the client device before transmission, ensuring that only the intended recipients can access the content.

### Key Principles

- **Zero-Knowledge Architecture**: The server cannot read message content
- **Forward Secrecy**: Unique encryption keys per message
- **Cross-Platform Recovery**: Mnemonic-based key recovery across devices
- **Decentralized Identity**: WhisperID generated from cryptographic keys

---

## Features

### Messaging
- End-to-end encrypted text messages
- Voice messages with waveform visualization
- Image, video, and file attachments (up to 100MB)
- Location sharing
- Reply to messages
- Delete for everyone
- Typing indicators
- Read receipts
- Message requests for unknown contacts

### Calls
- End-to-end encrypted voice calls
- End-to-end encrypted video calls
- WebRTC-based with TURN relay for reliability
- CallKit integration (iOS)
- Telecom integration (Android)

### Groups
- End-to-end encrypted group messaging
- Up to 50 members per group
- Admin controls (add/remove members)
- Group invitations

### Privacy & Security
- Biometric authentication (Face ID, Touch ID, Fingerprint)
- App lock with PIN/biometric
- Disappearing messages
- Contact backup (encrypted)
- Presence visibility controls
- Block users

### Additional Features
- Push notifications (APNS for iOS, FCM for Android)
- QR code contact sharing
- Chat themes and customization
- Font size settings
- Dark mode support
- In-app updates

---

## Architecture

```
whisper2/
├── server/                 # Node.js/TypeScript WebSocket server
│   ├── src/
│   │   ├── db/            # PostgreSQL & Redis data layer
│   │   ├── handlers/      # WebSocket & HTTP handlers
│   │   ├── schemas/       # JSON schema validation
│   │   ├── services/      # Business logic services
│   │   ├── types/         # Protocol type definitions
│   │   └── utils/         # Crypto, logging, validation
│   └── tests/             # Integration tests
│
├── ios/                    # Swift/SwiftUI iOS client
│   ├── App/               # App entry & coordination
│   ├── Core/              # Constants, errors, extensions
│   ├── Crypto/            # TweetNaCl cryptography
│   ├── Models/            # Data models
│   ├── Protocol/          # WebSocket protocol types
│   ├── Services/          # Business logic services
│   ├── ViewModels/        # MVVM view models
│   └── Views/             # SwiftUI views
│
└── android/                # Kotlin/Jetpack Compose Android client
    └── app/src/main/java/com/whisper2/app/
        ├── core/          # Constants, helpers, extensions
        ├── crypto/        # Lazysodium cryptography
        ├── data/          # Room database, network, preferences
        ├── di/            # Hilt dependency injection
        ├── services/      # Business logic services
        ├── ui/            # Compose UI components
        └── utils/         # Utility classes
```

### Technology Stack

| Component | Technology |
|-----------|------------|
| **Server** | Node.js 20+, TypeScript, WebSocket |
| **Database** | PostgreSQL 15+, Redis 7+ |
| **iOS** | Swift 5.9+, SwiftUI, iOS 17+ |
| **Android** | Kotlin 1.9+, Jetpack Compose, Android 8+ (API 26) |
| **Crypto** | libsodium (server), TweetNaCl (iOS), Lazysodium (Android) |
| **WebRTC** | Native WebRTC (iOS), Stream WebRTC (Android) |
| **Push** | APNS (iOS), FCM (Android) |
| **Storage** | DigitalOcean Spaces (S3-compatible) |

---

## Cryptography

Whisper2 uses industry-standard cryptographic algorithms:

### Key Derivation

```
Mnemonic (12/24 words)
    │
    ▼ PBKDF2-HMAC-SHA512 (salt="mnemonic", iterations=2048)
    │
64-byte BIP39 Seed
    │
    ▼ HKDF-SHA256 (IKM=64-byte seed, salt="whisper")
    │
    ├─► info="whisper/enc"      → 32-byte encSeed    → X25519 keypair
    ├─► info="whisper/sign"     → 32-byte signSeed   → Ed25519 keypair
    └─► info="whisper/contacts" → 32-byte contactsKey
```

### Algorithms

| Purpose | Algorithm | Key Size |
|---------|-----------|----------|
| Key Exchange | X25519 (Curve25519) | 32 bytes |
| Message Encryption | XSalsa20-Poly1305 | 32 bytes |
| Message Signing | Ed25519 | 64 bytes (private) |
| Key Derivation | HKDF-SHA256 | Variable |
| Seed Generation | PBKDF2-HMAC-SHA512 | 64 bytes |

### Encryption Flow

1. **Sender** encrypts message with recipient's X25519 public key
2. **Nonce** (24 bytes) generated randomly for each message
3. **Ciphertext** = XSalsa20-Poly1305(plaintext, nonce, sharedSecret)
4. **Signature** = Ed25519(SHA256(canonicalMessage), signingKey)
5. **Server** relays encrypted payload without access to plaintext

### Canonical Message Format (for signing)

```
v1\n
messageType\n
messageId\n
from\n
toOrGroupId\n
timestamp\n
nonceB64\n
ciphertextB64\n
```

---

## Protocol

### WebSocket Frame Structure

```json
{
  "type": "message_type",
  "requestId": "optional-uuid",
  "payload": { ... }
}
```

### Protocol Constants

| Constant | Value | Description |
|----------|-------|-------------|
| `PROTOCOL_VERSION` | 1 | Protocol version |
| `CRYPTO_VERSION` | 1 | Cryptography version |
| `TIMESTAMP_SKEW_MS` | 600,000 | ±10 minute tolerance |
| `SESSION_TTL_DAYS` | 7 | Session validity |
| `HEARTBEAT_INTERVAL_MS` | 30,000 | WebSocket keepalive |

### Message Types

#### Authentication
- `register_begin` → `register_challenge` → `register_proof` → `register_ack`
- `session_refresh` → `session_refresh_ack`
- `logout`
- `delete_account` → `account_deleted`

#### Messaging
- `send_message` → `message_accepted`
- `message_received`
- `delivery_receipt` → `message_delivered`
- `fetch_pending` → `pending_messages`
- `delete_message` → `message_deleted`
- `typing` → `typing_notification`

#### Groups
- `group_create` → `group_event`
- `group_update` → `group_event`
- `group_send_message`
- `group_invite_response`

#### Calls
- `get_turn_credentials` → `turn_credentials`
- `call_initiate` → `call_incoming`
- `call_answer`
- `call_ice_candidate`
- `call_ringing`
- `call_end`

#### System
- `ping` → `pong`
- `update_tokens`
- `presence_update`
- `error`

### WhisperID Format

```
WSP-XXXX-XXXX-XXXX
```
- Base32 encoding (A-Z, 2-7)
- Generated server-side from public key hash
- Unique, permanent user identifier

---

## Server

### Components

| Service | Description |
|---------|-------------|
| `AuthService` | Registration, challenge-response, sessions |
| `MessageRouter` | Message routing and delivery |
| `GroupService` | Group management and messaging |
| `CallService` | WebRTC signaling, TURN credentials |
| `PushService` | APNS/FCM push notifications |
| `AttachmentService` | Encrypted file upload/download |
| `ConnectionManager` | WebSocket connection management |
| `RateLimiter` | Request rate limiting |

### Database Schema

**PostgreSQL Tables:**
- `users` - User accounts and public keys
- `sessions` - Active session tokens
- `contacts_backup` - Encrypted contacts backup
- `pending_messages` - Undelivered messages
- `groups` - Group metadata
- `group_members` - Group membership

**Redis Keys:**
- `ws:{whisperId}` - WebSocket connection mapping
- `presence:{whisperId}` - Online status
- `ratelimit:*` - Rate limiting counters

### Dependencies

```json
{
  "ws": "WebSocket server",
  "pg": "PostgreSQL client",
  "ioredis": "Redis client",
  "sodium-native": "Cryptography",
  "firebase-admin": "FCM push notifications",
  "@aws-sdk/client-s3": "S3-compatible storage",
  "ajv": "JSON schema validation",
  "pino": "Logging"
}
```

### Build & Run

```bash
cd server

# Install dependencies
npm install

# Build TypeScript
npm run build

# Run migrations
npm run migrate

# Start server
npm start

# Development mode
npm run dev
```

### Tests

```bash
# Run all tests
npm test

# Individual test suites
npm run test:step2  # Messaging
npm run test:step3  # Contacts backup
npm run test:step4  # Attachments
npm run test:step5  # Groups
npm run test:step6  # Push notifications
npm run test:step7  # Calls
```

---

## iOS Client

### Requirements

- Xcode 15+
- iOS 17.0+
- Swift 5.9+
- Apple Developer Account (for push notifications)

### Architecture

- **MVVM** pattern with SwiftUI
- **Async/Await** for concurrency
- **SwiftData** for local persistence
- **Keychain** for secure storage

### Key Services

| Service | Description |
|---------|-------------|
| `AuthService` | Authentication and registration |
| `WebSocketService` | WebSocket connection management |
| `MessagingService` | Message send/receive |
| `CallService` | WebRTC calls with CallKit |
| `CryptoService` | Encryption/decryption/signing |
| `PushNotificationService` | APNS handling |
| `AttachmentService` | File upload/download |
| `ContactsService` | Contact management |
| `GroupService` | Group operations |

### Cryptography

Uses **TweetNaCl** via TweetNaClx Swift package:
- X25519 key exchange
- XSalsa20-Poly1305 encryption
- Ed25519 signatures

### Build & Run

1. Open `ios/Whisper2.xcodeproj` in Xcode
2. Select development team in Signing & Capabilities
3. Configure push notification entitlements
4. Build and run on device (push requires physical device)

### Capabilities Required

- Push Notifications
- Background Modes (VoIP, Audio, Remote notifications)
- Keychain Sharing
- App Groups

---

## Android Client

### Requirements

- Android Studio Hedgehog+
- Android SDK 26+ (Android 8.0 Oreo)
- Kotlin 1.9+
- Java 17

### Architecture

- **MVVM** pattern with Jetpack Compose
- **Hilt** for dependency injection
- **Room** for local database
- **Coroutines/Flow** for async operations
- **DataStore** for preferences

### Key Services

| Service | Description |
|---------|-------------|
| `AuthService` | Authentication and registration |
| `WsClient` | WebSocket with auto-reconnect |
| `MessagingService` | Message send/receive |
| `CallService` | WebRTC with Telecom integration |
| `CryptoService` | Lazysodium cryptography |
| `FcmService` | Firebase Cloud Messaging |
| `AttachmentService` | File upload/download |
| `ContactsService` | Contact management |
| `GroupService` | Group operations |

### Background Services

| Service | Purpose |
|---------|---------|
| `ConnectionForegroundService` | Persistent WebSocket connection |
| `CallForegroundService` | Active call management |
| `MessageSyncWorker` | Periodic message sync (WorkManager) |
| `BootReceiver` | Service restart after reboot |

### Cryptography

Uses **Lazysodium-Android** (libsodium wrapper):
- X25519 key exchange
- XSalsa20-Poly1305 encryption
- Ed25519 signatures

### Build & Run

```bash
cd android

# Debug build
./gradlew assembleDebug

# Release build (requires keystore)
./gradlew assembleRelease

# Install to device
adb install -r app/build/outputs/apk/release/app-release.apk

# Build AAB for Play Store
./gradlew bundleRelease
```

### Permissions

```xml
<!-- Core -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Background -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

<!-- Calls -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.MANAGE_OWN_CALLS" />

<!-- Other -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

### Database Migrations

The app uses Room with versioned migrations:

| Version | Changes |
|---------|---------|
| 1 → 2 | Add unreadCount to groups |
| 2 → 3 | Add disappearing messages support |
| 3 → 4 | Add group invites table |
| 4 → 5 | Add avatar support |
| 5 → 6 | Add chat themes |
| 6 → 7 | Add presence columns (isOnline, lastSeen) |

---

## WebRTC Configuration

Both iOS and Android use identical WebRTC configuration for consistency:

### ICE Configuration

```kotlin
// Android
val config = PeerConnection.RTCConfiguration(iceServers).apply {
    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    iceTransportsType = PeerConnection.IceTransportsType.RELAY  // TURN only
    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
    iceCandidatePoolSize = 1
}
```

```swift
// iOS
let config = RTCConfiguration()
config.sdpSemantics = .unifiedPlan
config.continualGatheringPolicy = .gatherContinually
config.iceTransportPolicy = .relay  // TURN only
config.bundlePolicy = .maxBundle
config.rtcpMuxPolicy = .require
config.iceCandidatePoolSize = 1
```

### Why TURN Relay Only?

| Reason | Benefit |
|--------|---------|
| **Reliability** | Works through all firewalls and NAT types |
| **Consistency** | Same behavior across all networks |
| **Privacy** | User IP addresses not exposed to peers |
| **Debugging** | Easier to troubleshoot connection issues |

### TURN Server (coturn)

Required ports:

| Port | Protocol | Purpose |
|------|----------|---------|
| 3479 | TCP/UDP | TURN (plain) |
| 5350 | TCP | TURNS (TLS) |
| 49152-65535 | UDP | Relay ports |

---

## Security

### Best Practices

1. **Never commit secrets** (.env, API keys, keystores)
2. **TURN credentials are time-limited** (configurable TTL)
3. **All messages are end-to-end encrypted**
4. **Keys derived from BIP39 mnemonic** (user-controlled)
5. **Server cannot read message content**
6. **Biometric protection** for app access
7. **Certificate pinning** recommended for production

### Frozen Constants

These values are frozen and must match across all platforms:

```
HKDF Salt: "whisper"
Encryption Domain: "whisper/enc"
Signing Domain: "whisper/sign"
Contacts Domain: "whisper/contacts"
BIP39 Salt: "mnemonic"
BIP39 Iterations: 2048
```

Changing these will break cross-platform key recovery.

### Test Vectors

Server and clients include identical test vectors to ensure cryptographic compatibility. Run tests after any crypto-related changes.

---

## Troubleshooting

### Connection Issues

1. **Check server status** - Verify WebSocket endpoint is reachable
2. **Check network** - Ensure stable internet connection
3. **Check firewall** - Allow WebSocket (WSS) traffic
4. **Check logs** - Review client and server logs for errors

### Android Background Disconnects

1. **Battery optimization** - Exempt app from battery optimization
2. **Manufacturer settings**:
   - Samsung: Disable "Put unused apps to sleep"
   - Xiaomi: Enable "Autostart", disable "Battery saver"
   - Huawei: Enable "Ignore battery optimizations"
   - OnePlus: Disable "Adaptive Battery"
3. **Check foreground service** - Verify notification is showing

### Call Connection Failures

1. **TURN server** - Verify coturn is running and accessible
2. **Credentials** - Check TURN credentials are valid
3. **Firewall** - Ensure TURN ports are open
4. **Logs** - Check for ICE candidate errors

### Push Notification Issues

**iOS:**
- Verify APNS certificates are valid
- Check entitlements configuration
- Test on physical device (simulator doesn't support push)

**Android:**
- Verify FCM configuration
- Check google-services.json is present
- Exempt from battery optimization

### Database Migration Crashes

If app crashes after update with migration errors:
1. Check migration is properly defined
2. Verify version number is incremented
3. Test migration on existing database

---

## Project Statistics

| Component | Files | Lines (approx) |
|-----------|-------|----------------|
| Server | 28 TypeScript | ~5,000 |
| iOS | 73 Swift | ~15,000 |
| Android | 108 Kotlin | ~20,000 |
| **Total** | **209** | **~40,000** |

---

## License

Proprietary - All Rights Reserved

---

## Version History

### v1.0.77 (January 2026)
- Database migration fixes
- Build error corrections
- Play Store release preparation

### v1.0.76 (January 2026)
- iOS audio message improvements
- WebSocket stability enhancements
- Settings view updates

### v1.0.75 (January 2026)
- Android foreground service improvements
- Connection state management fixes
- Battery optimization helper

### Earlier Versions
- WebRTC TURN relay configuration
- Group messaging implementation
- Attachment upload/download
- Push notification integration
- Biometric authentication
- Initial release

---

*Built with security and privacy in mind.*
