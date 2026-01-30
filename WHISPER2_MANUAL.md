# Whisper2 - Secure E2EE Messaging Platform

## Project Manual v1.0

---

## Table of Contents

1. [Overview](#overview)
2. [Security Architecture](#security-architecture)
3. [Cryptographic Primitives](#cryptographic-primitives)
4. [Key Management](#key-management)
5. [Protocol Specification](#protocol-specification)
6. [Server Architecture](#server-architecture)
7. [iOS Client Architecture](#ios-client-architecture)
8. [WebSocket API Reference](#websocket-api-reference)
9. [Deployment Guide](#deployment-guide)
10. [Security Considerations](#security-considerations)

---

## Overview

Whisper2 is a secure end-to-end encrypted (E2EE) messaging platform featuring:

- **Direct Messaging** - Private 1:1 encrypted conversations
- **Group Messaging** - Encrypted group chats
- **Voice Calls** - WebRTC-based encrypted audio calls
- **Video Calls** - WebRTC-based encrypted video calls
- **File Sharing** - Encrypted attachments (images, files, voice messages)
- **Cross-Platform** - iOS client with Android planned

### Core Principles

1. **Zero Knowledge** - Server never has access to plaintext messages or private keys
2. **Client-Side Encryption** - All encryption/decryption happens on device
3. **Cryptographic Identity** - Users identified by cryptographic keys, not phone numbers
4. **Recoverable Accounts** - BIP39 mnemonic allows account recovery on any device

---

## Security Architecture

### End-to-End Encryption Model

```
┌─────────────────┐                    ┌─────────────────┐
│   Alice (iOS)   │                    │    Bob (iOS)    │
│                 │                    │                 │
│  ┌───────────┐  │                    │  ┌───────────┐  │
│  │ Plaintext │  │                    │  │ Plaintext │  │
│  └─────┬─────┘  │                    │  └─────▲─────┘  │
│        │        │                    │        │        │
│        ▼        │                    │        │        │
│  ┌───────────┐  │                    │  ┌───────────┐  │
│  │  Encrypt  │  │                    │  │  Decrypt  │  │
│  │  (NaCl)   │  │                    │  │  (NaCl)   │  │
│  └─────┬─────┘  │                    │  └─────▲─────┘  │
│        │        │                    │        │        │
└────────┼────────┘                    └────────┼────────┘
         │                                      │
         │         ┌─────────────────┐          │
         └────────►│     Server      │──────────┘
                   │  (Encrypted     │
                   │   Blobs Only)   │
                   └─────────────────┘
```

### What the Server Sees

| Data | Server Access |
|------|---------------|
| Message content | Encrypted ciphertext only |
| Attachments | Encrypted blobs only |
| Call audio/video | Never routed through server (P2P) |
| Contact list | Encrypted backup only |
| WhisperID | Yes (routing identifier) |
| Timestamps | Yes (for message delivery) |
| IP addresses | Yes (connection metadata) |

### What the Server Cannot Do

- Read message content
- Decrypt attachments
- Listen to calls
- Forge messages (Ed25519 signatures)
- Recover user private keys

---

## Cryptographic Primitives

### Algorithms Used

| Purpose | Algorithm | Library |
|---------|-----------|---------|
| Key Exchange | X25519 (Curve25519) | TweetNaCl |
| Symmetric Encryption | XSalsa20-Poly1305 | TweetNaCl (NaCl Box) |
| Digital Signatures | Ed25519 | TweetNaCl |
| Key Derivation | HKDF-SHA256 | CryptoKit |
| Seed Generation | PBKDF2-HMAC-SHA512 | CryptoKit |
| Mnemonic | BIP39 (2048 words) | Custom |

### NaCl Box (Authenticated Encryption)

```
Encryption:
  ciphertext = NaCl.box(plaintext, nonce, recipientPublicKey, senderPrivateKey)

Decryption:
  plaintext = NaCl.box.open(ciphertext, nonce, senderPublicKey, recipientPrivateKey)
```

- **Nonce**: 24 bytes, randomly generated per message
- **Authentication**: Poly1305 MAC prevents tampering
- **Key Agreement**: X25519 ECDH derives shared secret

### Ed25519 Signatures

All messages are signed to ensure:
- **Authenticity** - Message came from claimed sender
- **Integrity** - Message wasn't modified in transit
- **Non-repudiation** - Sender cannot deny sending

```
Canonical Message Format (for signing):
  v1\n
  {messageType}\n
  {messageId}\n
  {from}\n
  {to}\n
  {timestamp}\n
  {nonceBase64}\n
  {ciphertextBase64}\n

Signature = Ed25519.sign(SHA256(canonicalBytes), privateKey)
```

---

## Key Management

### Key Derivation Chain

```
┌─────────────────────────────────────────────────────────────┐
│                    BIP39 Mnemonic                           │
│         (12 or 24 words from 2048-word list)                │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼ PBKDF2-HMAC-SHA512
                          │ (salt="mnemonic", iterations=2048)
                          │
┌─────────────────────────┴───────────────────────────────────┐
│                   64-byte BIP39 Seed                        │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼ HKDF-SHA256 (salt="whisper")
                          │
        ┌─────────────────┼─────────────────┐
        │                 │                 │
        ▼                 ▼                 ▼
   info="whisper/enc" info="whisper/sign" info="whisper/contacts"
        │                 │                 │
        ▼                 ▼                 ▼
┌───────────────┐ ┌───────────────┐ ┌───────────────┐
│ encSeed (32B) │ │signSeed (32B) │ │contactsKey    │
│       │       │ │       │       │ │    (32B)      │
│       ▼       │ │       ▼       │ └───────────────┘
│ X25519 Keypair│ │Ed25519 Keypair│   (for encrypted
│ (enc pub/priv)│ │(sign pub/priv)│    contact backup)
└───────────────┘ └───────────────┘
```

### Key Types

| Key | Purpose | Algorithm | Storage |
|-----|---------|-----------|---------|
| Encryption Private Key | Decrypt incoming messages | X25519 | Keychain (secure) |
| Encryption Public Key | Shared with contacts | X25519 | Server + contacts |
| Signing Private Key | Sign outgoing messages | Ed25519 | Keychain (secure) |
| Signing Public Key | Verify signatures | Ed25519 | Server + contacts |
| Contacts Key | Encrypt contact backup | Symmetric | Keychain (secure) |

### Account Recovery

Users can recover their account on any device using their BIP39 mnemonic:

1. Enter 12/24 word mnemonic
2. Derive all keys deterministically
3. Register with server using existing WhisperID
4. Download and decrypt contact backup
5. Fetch pending messages

**Important**: The mnemonic IS the account. Anyone with the mnemonic has full access.

---

## Protocol Specification

### WebSocket Frame Format

All communication uses JSON over WebSocket:

```json
{
  "type": "message_type",
  "requestId": "optional-uuid",
  "payload": { ... }
}
```

### Authentication Flow

```
┌────────┐                                    ┌────────┐
│ Client │                                    │ Server │
└───┬────┘                                    └───┬────┘
    │                                             │
    │ 1. register_begin                           │
    │    {deviceId, platform, whisperId?}         │
    │────────────────────────────────────────────►│
    │                                             │
    │ 2. register_challenge                       │
    │    {challengeId, challenge, expiresAt}      │
    │◄────────────────────────────────────────────│
    │                                             │
    │ 3. register_proof                           │
    │    {challengeId, signature, publicKeys}     │
    │────────────────────────────────────────────►│
    │                                             │
    │ 4. register_ack                             │
    │    {whisperId, sessionToken, expiresAt}     │
    │◄────────────────────────────────────────────│
    │                                             │
```

### Message Flow

```
┌───────┐                 ┌────────┐                 ┌─────┐
│ Alice │                 │ Server │                 │ Bob │
└───┬───┘                 └───┬────┘                 └──┬──┘
    │                         │                         │
    │ 1. send_message         │                         │
    │    (encrypted+signed)   │                         │
    │────────────────────────►│                         │
    │                         │                         │
    │ 2. message_accepted     │                         │
    │    {messageId, status}  │                         │
    │◄────────────────────────│                         │
    │                         │                         │
    │                         │ 3. message_received     │
    │                         │    (encrypted+signed)   │
    │                         │────────────────────────►│
    │                         │                         │
    │                         │ 4. delivery_receipt     │
    │                         │    {status: delivered}  │
    │                         │◄────────────────────────│
    │                         │                         │
    │ 5. message_delivered    │                         │
    │◄────────────────────────│                         │
    │                         │                         │
```

### Call Signaling Flow

```
┌────────┐                  ┌────────┐                  ┌─────┐
│ Caller │                  │ Server │                  │Callee│
└───┬────┘                  └───┬────┘                  └──┬──┘
    │                           │                          │
    │ 1. get_turn_credentials   │                          │
    │──────────────────────────►│                          │
    │                           │                          │
    │ 2. turn_credentials       │                          │
    │◄──────────────────────────│                          │
    │                           │                          │
    │ 3. call_initiate          │                          │
    │    (encrypted SDP offer)  │                          │
    │──────────────────────────►│                          │
    │                           │ 4. call_incoming         │
    │                           │    (+ VoIP push)         │
    │                           │─────────────────────────►│
    │                           │                          │
    │                           │ 5. call_answer           │
    │                           │    (encrypted SDP answer)│
    │                           │◄─────────────────────────│
    │ 6. call_answer            │                          │
    │◄──────────────────────────│                          │
    │                           │                          │
    │ 7. ICE candidates (encrypted, bidirectional)         │
    │◄─────────────────────────►│◄────────────────────────►│
    │                           │                          │
    │ 8. P2P WebRTC Connection Established                 │
    │◄════════════════════════════════════════════════════►│
    │                           │                          │
```

---

## Server Architecture

### Technology Stack

| Component | Technology |
|-----------|------------|
| Runtime | Node.js (TypeScript) |
| WebSocket | ws library |
| Database | PostgreSQL |
| Cache/Pub-Sub | Redis |
| File Storage | DigitalOcean Spaces (S3-compatible) |
| Push Notifications | APNS (iOS), FCM (Android) |
| TURN Server | Cloudflare TURN |
| Process Manager | PM2 |
| SSL | Let's Encrypt via Nginx |

### Server Directory Structure

```
server/
├── src/
│   ├── index.ts              # Entry point
│   ├── handlers/
│   │   ├── WsGateway.ts      # WebSocket message router
│   │   └── HttpAdmin.ts      # Admin HTTP endpoints
│   ├── services/
│   │   ├── AuthService.ts    # Authentication logic
│   │   ├── MessageRouter.ts  # Message delivery
│   │   ├── CallService.ts    # Call signaling
│   │   ├── GroupService.ts   # Group management
│   │   ├── PushService.ts    # Push notifications
│   │   └── AttachmentService.ts
│   ├── db/
│   │   ├── postgres.ts       # PostgreSQL connection
│   │   └── redis.ts          # Redis connection
│   ├── schemas/              # Payload validation
│   └── types/
│       └── protocol.ts       # Protocol type definitions
├── tests/                    # Integration tests
└── package.json
```

### Database Schema (Key Tables)

```sql
-- Users table
CREATE TABLE users (
    whisper_id VARCHAR(20) PRIMARY KEY,
    enc_public_key TEXT NOT NULL,
    sign_public_key TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Sessions table
CREATE TABLE sessions (
    session_token VARCHAR(64) PRIMARY KEY,
    whisper_id VARCHAR(20) REFERENCES users,
    device_id UUID NOT NULL,
    expires_at TIMESTAMP NOT NULL
);

-- Pending messages (offline delivery)
CREATE TABLE pending_messages (
    id SERIAL PRIMARY KEY,
    recipient_id VARCHAR(20) REFERENCES users,
    message_data JSONB NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Groups
CREATE TABLE groups (
    group_id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    created_by VARCHAR(20) REFERENCES users,
    created_at TIMESTAMP DEFAULT NOW()
);
```

---

## iOS Client Architecture

### Technology Stack

| Component | Technology |
|-----------|------------|
| Language | Swift 5.9+ |
| UI Framework | SwiftUI |
| Minimum iOS | 17.0 |
| Crypto | TweetNaCl + CryptoKit |
| Networking | URLSession WebSocket |
| Storage | Keychain + UserDefaults |
| Calls | WebRTC (via WebRTC.framework) |
| Push | APNS + PushKit (VoIP) |

### iOS Directory Structure

```
ios/
├── App/
│   ├── Whisper2App.swift     # App entry point
│   ├── AppCoordinator.swift  # Navigation coordinator
│   └── ContentView.swift     # Root view
├── Core/
│   ├── Constants.swift       # App constants
│   ├── Errors.swift          # Error types
│   └── Extensions.swift      # Swift extensions
├── Crypto/
│   ├── CryptoService.swift   # Main crypto orchestrator
│   ├── KeyDerivation.swift   # BIP39 + HKDF
│   └── BIP39WordList.swift   # 2048 mnemonic words
├── Services/
│   ├── AuthService.swift     # Authentication
│   ├── WebSocketService.swift# WebSocket client
│   ├── MessagingService.swift# Message handling
│   ├── CallService.swift     # WebRTC calls
│   ├── CallKitManager.swift  # iOS CallKit integration
│   ├── ContactsService.swift # Contact management
│   ├── GroupService.swift    # Group chat
│   ├── AttachmentService.swift
│   ├── KeychainService.swift # Secure storage
│   └── PushNotificationService.swift
├── Models/
│   ├── User.swift
│   ├── Message.swift
│   ├── Contact.swift
│   ├── Conversation.swift
│   └── Group.swift
├── ViewModels/
│   ├── AuthViewModel.swift
│   ├── ChatViewModel.swift
│   └── ContactsViewModel.swift
├── Views/
│   ├── Auth/                 # Login, registration
│   ├── Main/                 # Chats, contacts
│   ├── Call/                 # Audio/video call UI
│   ├── Group/                # Group chat UI
│   ├── Settings/             # Settings screens
│   └── Components/           # Reusable UI components
└── Protocol/
    ├── WsFrame.swift         # WebSocket frame types
    ├── MessageTypes.swift    # Message payload types
    └── ProtocolTypes.swift   # Protocol constants
```

### Key Services

#### CryptoService

Handles all cryptographic operations:
- Key derivation from mnemonic
- Message encryption/decryption (NaCl Box)
- Message signing/verification (Ed25519)
- Canonical message formatting

#### WebSocketService

Manages WebSocket connection:
- Auto-reconnection with exponential backoff
- Message queuing during disconnection
- Ping/pong keepalive
- Connection state publishing

#### CallService

Manages WebRTC calls:
- Peer connection setup
- ICE candidate exchange
- Audio/video track management
- CallKit integration for native call UI

---

## WebSocket API Reference

### Message Types

#### Authentication

| Type | Direction | Description |
|------|-----------|-------------|
| `register_begin` | C→S | Start registration/login |
| `register_challenge` | S→C | Server challenge |
| `register_proof` | C→S | Signed challenge response |
| `register_ack` | S→C | Session token |
| `session_refresh` | C→S | Refresh expiring session |
| `logout` | C→S | End session |

#### Messaging

| Type | Direction | Description |
|------|-----------|-------------|
| `send_message` | C→S | Send encrypted message |
| `message_accepted` | S→C | Server accepted message |
| `message_received` | S→C | Incoming message |
| `delivery_receipt` | C→S | Delivered/read status |
| `message_delivered` | S→C | Delivery confirmation |
| `fetch_pending` | C→S | Get offline messages |
| `pending_messages` | S→C | Offline message batch |

#### Calls

| Type | Direction | Description |
|------|-----------|-------------|
| `get_turn_credentials` | C→S | Request TURN server creds |
| `turn_credentials` | S→C | TURN server credentials |
| `call_initiate` | C→S | Start call (encrypted SDP) |
| `call_incoming` | S→C | Incoming call notification |
| `call_answer` | C→S | Accept call (encrypted SDP) |
| `call_ice_candidate` | C↔S | ICE candidate exchange |
| `call_end` | C→S | End call |
| `call_ringing` | C→S | Callee device is ringing |

#### Groups

| Type | Direction | Description |
|------|-----------|-------------|
| `group_create` | C→S | Create new group |
| `group_update` | C→S | Add/remove members |
| `group_send_message` | C→S | Send group message |
| `group_event` | S→C | Group update notification |

---

## Deployment Guide

### Server Requirements

- Ubuntu 20.04+ or Debian 11+
- Node.js 18+
- PostgreSQL 14+
- Redis 6+
- Nginx (reverse proxy)
- SSL certificate

### Server Deployment

```bash
# 1. Clone repository
git clone https://github.com/fatihtunali/whisper2.git
cd whisper2/server

# 2. Install dependencies
npm install

# 3. Configure environment
cp .env.example .env
# Edit .env with your settings

# 4. Build TypeScript
npm run build

# 5. Run database migrations
npm run migrate

# 6. Start with PM2
pm2 start dist/index.js --name whisper2

# 7. Configure Nginx reverse proxy
# (see nginx.conf.example)
```

### Environment Variables

```bash
# Server
PORT=3051
NODE_ENV=production

# Database
DATABASE_URL=postgresql://user:pass@localhost:5432/whisper2
REDIS_URL=redis://localhost:6379

# Push Notifications
APNS_KEY_ID=your_key_id
APNS_TEAM_ID=your_team_id
APNS_BUNDLE_ID=com.yourapp.whisper2

# TURN Server
TURN_SECRET=your_turn_secret
TURN_URLS=turn:turn.example.com:3478

# Storage (S3-compatible)
SPACES_ENDPOINT=https://nyc3.digitaloceanspaces.com
SPACES_BUCKET=whisper2-attachments
SPACES_ACCESS_KEY=your_access_key
SPACES_SECRET_KEY=your_secret_key
```

### iOS Build

1. Open `ios/` folder in Xcode
2. Configure signing & capabilities
3. Add required capabilities:
   - Push Notifications
   - Background Modes (VoIP, fetch, remote-notification)
   - App Groups
4. Build and archive for App Store

---

## Security Considerations

### Current Security Level

| Feature | Status | Notes |
|---------|--------|-------|
| End-to-End Encryption | ✅ Implemented | NaCl Box |
| Message Authentication | ✅ Implemented | Ed25519 signatures |
| Perfect Forward Secrecy | ⚠️ Not implemented | Static keys |
| Key Verification | ⚠️ Not implemented | No safety numbers |
| Metadata Protection | ⚠️ Limited | Server sees routing info |
| Sealed Sender | ❌ Not implemented | Server knows sender |

### Recommendations for Future Versions

1. **Double Ratchet Protocol** - Implement Signal-style key ratcheting for forward secrecy
2. **Safety Numbers** - Allow users to verify contact keys out-of-band
3. **Sealed Sender** - Hide sender identity from server
4. **Disappearing Messages** - Auto-delete messages after time period
5. **Key Transparency** - Publish key directory for auditing
6. **Security Audit** - Professional third-party security audit

### Threat Model

**Protected Against:**
- Server compromise (messages remain encrypted)
- Network eavesdropping (TLS + E2EE)
- Message tampering (Ed25519 signatures)
- Replay attacks (timestamps + message IDs)

**Not Protected Against:**
- Compromised device (attacker has full access)
- Compromised mnemonic (full account takeover)
- Traffic analysis (timing, message sizes)
- Legal compulsion (server metadata)

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | Jan 2026 | Initial release |

---

## License

Whisper2 is proprietary software. All rights reserved.

---

## Contact

- **Developer**: Fatih Tunali
- **Email**: [your-email]
- **GitHub**: https://github.com/fatihtunali/whisper2

---

*This document was generated for Whisper2 v1.0*
