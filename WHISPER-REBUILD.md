# Whisper Rebuild — Viable, No Loose Ends (Native iOS + Native Android + New Server)

This document is the **single, frozen source of truth** for rebuilding Whisper end-to-end:
- **Native iOS**
- **Native Android**
- **New Server** (WebSocket gateway + Admin HTTP)
- **E2E encrypted messaging + encrypted attachments + groups + push + voice/video calls**
- **Production operability** (metrics, structured logs, rate limits, bans/reports, backups)

> **Design stance:** Whisper optimizes for **identity continuity**, **recoverability**, and **operational reliability** under mobile constraints. It does **not** aim for nation-state resistance, traffic-analysis resistance, or perfect forward secrecy in this rebuild.

---

## 0) Definition of “Real Running App”

Whisper is “real” when:

- A user can **create an identity** (seed phrase shown once), **recover it** on another device, and keep the same Whisper ID.
- **1:1 chat works daily**: text + images + voice notes + files, with **offline delivery**, **dedupe**, and **correct receipts**.
- **Push notifications work** reliably when the app is **backgrounded/killed** (both platforms).
- **Groups work** with correct membership changes and stable delivery (pairwise fanout only).
- **Calls work**: voice + video, with **native call UI**, background incoming call support, and TURN fallback.
- Server is operable: **health, metrics, structured logs, rate limits, bans/reports, backups**, safe deploys.

---

## 1) Threat Model (Explicit)

### Protected against
- Passive network eavesdropping (TLS + E2E content encryption).
- Server compromise (server cannot read message bodies or attachment plaintext).
- Identity spoofing (signature-based registration + **mandatory signed messages** + **mandatory signed call signaling**).
- Unauthorized critical actions (require signature from account keys).

### Not protected against
- Compromised device (malware/unlocked/jailbroken/rooted).
- Traffic analysis (who talks to whom, timing, sizes).
- Forward secrecy (no Double Ratchet / no per-message ratchet by default).

### Metadata the server will see
- Whisper IDs (routing), timestamps, ciphertext sizes, presence events, group membership for routing, call signaling events.

---

## 2) System Architecture

### Components
1) **iOS App** — SwiftUI + MVVM + Combine  
2) **Android App** — Kotlin + Jetpack Compose + MVVM + Hilt  
3) **Whisper Server**
   - WebSocket Gateway (messaging + presence + signaling)
   - HTTP Admin API (health, metrics, bans/reports, attachment presign + download)
4) **PostgreSQL** — durable minimal metadata (users/devices/groups/abuse/audit/attachments)
5) **Redis** — presence + pending queues + rate limits + session state + call state + pub/sub
6) **Object Storage** (S3-compatible) — encrypted blobs for attachments
7) **Push Providers** — APNs + FCM (+ iOS VoIP push for calls)
8) **TURN/STUN** (coturn) — WebRTC NAT traversal reliability

### Data flows
**Messaging:** sender encrypts + signs → server routes/queues → recipient verifies signature + decrypts.  
**Attachments:** client encrypts blob → uploads encrypted blob → sends message pointer + encrypted file key.  
**Calls:** signaling via WS (**signed**) → media via WebRTC P2P (TURN when needed) → native call UI.

---

## 3) Non‑Negotiable Contracts (Freeze These First)

Everything works or fails on whether these contracts are **stable** and **identical** across iOS/Android/server.

### 3.1 Protocol Contract (WebSocket + HTTP)

Create:
- `docs/protocol.md` (human-readable)
- `server/src/types/protocol.ts` (machine-readable)
- `server/src/schemas/*.json` (JSON Schema) + server validation (AJV)
- iOS/Android models mirrored exactly (sealed types / enums)

**Rule:** Server **rejects invalid payloads** with `INVALID_PAYLOAD` before business logic.

#### Canonical WS frame
All WS frames are:
```json
{
  "type": "string",
  "requestId": "uuid-optional",
  "payload": {}
}
```

#### Standard server → client error
```json
{
  "type": "error",
  "payload": {
    "code": "INVALID_PAYLOAD|AUTH_FAILED|RATE_LIMITED|...",
    "message": "human readable",
    "requestId": "optional"
  }
}
```

#### Mandatory versioning (prevents future breakage)
Every privileged client message payload MUST include:
```json
{
  "protocolVersion": 1,
  "cryptoVersion": 1
}
```
Server rejects unknown versions.

#### Standard error codes (frozen)
- NOT_REGISTERED
- AUTH_FAILED
- INVALID_PAYLOAD
- INVALID_TIMESTAMP
- RATE_LIMITED
- USER_BANNED
- NOT_FOUND
- FORBIDDEN
- INTERNAL_ERROR

---

### 3.2 Identity + Device Model (No ambiguity)

#### Whisper ID format (frozen)
- Format: `WSP-XXXX-XXXX-XXXX`
- X are Base32 characters (A–Z,2–7) excluding ambiguous chars if desired.
- Include a checksum (e.g., 2 chars) inside the last block or a separate suffix.
- Server is the authority for final acceptance (to prevent collisions).

#### Device ID (frozen)
- Each install generates `deviceId = UUIDv4` once and persists in secure storage.
- All sessions bind to `{whisperId, deviceId}`.

---

### 3.3 Authentication + Sessions (Critical, single-active-device)

✅ **Single-active-device only.**  
A new successful login for the same Whisper ID invalidates old sessions and disconnects the old device.

#### Registration/login flow (frozen)
1) Client → `register_begin`
```json
{
  "type": "register_begin",
  "payload": { "protocolVersion": 1, "cryptoVersion": 1, "whisperId": "optional", "deviceId": "uuid", "platform": "ios|android" }
}
```

2) Server → `register_challenge`
```json
{
  "type": "register_challenge",
  "payload": { "challengeId": "uuid", "challenge": "base64(32)", "expiresAt": 1730000000000 }
}
```

3) Client → `register_proof`
```json
{
  "type": "register_proof",
  "payload": {
    "protocolVersion": 1,
    "cryptoVersion": 1,
    "challengeId": "uuid",
    "deviceId": "uuid",
    "platform": "ios|android",
    "whisperId": "WSP-.... (if recovery)",
    "encPublicKey": "base64",
    "signPublicKey": "base64",
    "signature": "base64(ed25519(sign(challengeBytes)))",
    "pushToken": "optional",
    "voipToken": "optional (iOS)"
  }
}
```

4) Server verifies:
- challenge exists + not expired + single-use (delete after verify)
- signature verifies using `signPublicKey`
- if whisperId exists: public keys must match identity (or reject)
- invalidate previous sessions for this whisperId (single-active-device)

Server → `register_ack`:
```json
{
  "type": "register_ack",
  "payload": { "success": true, "whisperId": "WSP-....", "sessionToken": "opaque", "sessionExpiresAt": 1730000000000, "serverTime": 1730000000000 }
}
```

#### Session usage (frozen)
- **All privileged client messages MUST include `sessionToken` in payload**.
- Server rejects privileged messages without a valid session with `NOT_REGISTERED`.

#### Session refresh (mandatory to avoid sudden expiry)
- Client sends `session_refresh` when `sessionExpiresAt - now < 24h`
- Server returns a new token and invalidates old token.

#### Logout (mandatory)
- Client sends `logout` → server invalidates sessionToken.

#### Session storage (server)
- Redis: `session:{sessionToken} -> {whisperId, deviceId, platform} TTL=7d`
- Redis: `challenge:{challengeId} -> challengeBytes TTL=60s`

---

### 3.4 Key Derivation (Exact)

#### Seed phrase → deterministic keys (frozen)
- Mnemonic: BIP39 12 words
- BIP39 seed:
`seed = PBKDF2(mnemonic, "mnemonic"+passphrase, 2048, 64 bytes)`

Derive two independent seeds using HKDF:
- `encSeed = HKDF-SHA256(seed, salt="whisper", info="whisper/enc", len=32)`
- `signSeed = HKDF-SHA256(seed, salt="whisper", info="whisper/sign", len=32)`

Then:
- X25519 keypair from `encSeed`
- Ed25519 keypair from `signSeed`

Encodings:
- All binary fields are base64.
- Nonce lengths validated:
  - `box nonce`: 24 bytes
  - `secretbox nonce`: 24 bytes

---

### 3.5 Canonical Signing (MANDATORY, frozen)

Do NOT sign raw JSON. Do NOT rely on “sorted keys” implementations.

#### Canonical bytes for signature
Compute:
```
signed_bytes = UTF8(
  "v1\n" +
  messageType + "\n" +
  messageId + "\n" +
  from + "\n" +
  toOrGroupId + "\n" +
  timestampMillis + "\n" +
  base64(nonce) + "\n" +
  base64(ciphertext) + "\n"
)
sig = Ed25519_Sign(SHA256(signed_bytes), senderSignPrivateKey)
```

- `messageType` ∈ { `send_message`, `group_send_message`, `call_initiate`, `call_answer`, `call_ice_candidate`, `call_end` }
- `toOrGroupId`: recipient whisperId for 1:1, groupId for group, peer whisperId for calls

#### Timestamp skew tolerance
Server accepts timestamps within **±10 minutes** of serverTime.
- Outside window → reject with `INVALID_TIMESTAMP`.
- Server returns `serverTime` in `register_ack` and in every `pong`.

---

### 3.6 Contact / Key Lookup (MANDATORY, avoids “how do I encrypt?” gap)

When adding a contact by Whisper ID, the client needs the recipient’s public keys.

Add HTTP endpoint:
- `GET /users/{whisperId}/keys` (auth required)
Returns:
```json
{ "whisperId": "WSP-...", "encPublicKey": "base64", "signPublicKey": "base64", "status": "active|banned" }
```

Rules:
- Keys are immutable for an identity in this rebuild (no key rotation).
- If status is banned, client should block initiating new messages.

---

## 4) Messaging (1:1) — Encryption + Signature + Receipts

### 4.1 Send message (client → server)
```json
{
  "type": "send_message",
  "payload": {
    "protocolVersion": 1,
    "cryptoVersion": 1,
    "sessionToken": "opaque",
    "messageId": "uuid",
    "from": "WSP-....",
    "to": "WSP-....",
    "msgType": "text|image|voice|file|system",
    "timestamp": 1730000000000,
    "nonce": "base64(24)",
    "ciphertext": "base64",
    "sig": "base64",
    "replyTo": "uuid-optional",
    "reactions": { "👍": ["WSP-..."] },
    "attachment": null
  }
}
```

Server MUST:
- validate schema
- validate sessionToken -> whisperId
- validate from == session whisperId
- validate timestamp window
- validate signature using sender signPublicKey
- apply rate limits
- accept idempotently by messageId

Server → sender immediate ACK:
```json
{ "type": "message_accepted", "payload": { "messageId": "uuid", "status": "sent" } }
```

### 4.2 Deliver to recipient (server → client)
```json
{
  "type": "message_received",
  "payload": {
    "messageId": "uuid",
    "from": "WSP-....",
    "to": "WSP-....",
    "msgType": "text|image|voice|file|system",
    "timestamp": 1730000000000,
    "nonce": "base64(24)",
    "ciphertext": "base64",
    "sig": "base64",
    "replyTo": "uuid-optional",
    "reactions": {},
    "attachment": null
  }
}
```

Recipient MUST:
- verify signature
- decrypt
- store
- then send delivery receipt

### 4.3 Delivery receipt (recipient → server)
```json
{
  "type": "delivery_receipt",
  "payload": {
    "protocolVersion": 1,
    "cryptoVersion": 1,
    "sessionToken": "opaque",
    "messageId": "uuid",
    "from": "WSP-recipient",
    "to": "WSP-sender",
    "status": "delivered|read",
    "timestamp": 1730000000000
  }
}
```

Server forwards receipt to sender:
```json
{ "type": "message_delivered", "payload": { "messageId": "uuid", "status": "delivered|read", "timestamp": 1730000000000 } }
```

---

## 5) Offline Delivery + Paging + Dedupe (Deterministic)

### 5.1 Server pending queue
- If recipient is offline, server stores the message in `pending:{recipient}` with TTL 72h.
- Pending is removed only when recipient sends `delivery_receipt` (delivered).

### 5.2 Fetch pending (client → server)
```json
{ "type": "fetch_pending", "payload": { "protocolVersion": 1, "cryptoVersion": 1, "sessionToken": "opaque", "cursor": "optional", "limit": 50 } }
```

Server:
```json
{ "type": "pending_messages", "payload": { "messages": [ ...message_received payloads... ], "nextCursor": "optional" } }
```

### 5.3 Client-side state machine (frozen)
- queued (persisted) → sending → sent(server ACK) → delivered(peer receipt) → read(optional)

### 5.4 Retry policy
- exponential backoff + jitter
- max retries 10
- queue persists across restarts

### 5.5 Dedupe
- Store recent processed messageIds (bounded, e.g. last 10k per conversation)
- Ignore duplicates safely

### 5.6 Server idempotency
- `send_message` is idempotent by messageId (return same ACK, no re-queue).

---

## 6) Attachments (Presigned Upload + Encrypted Blob) + GC

### 6.1 Presign upload (HTTP)
`POST /attachments/presign` (auth)
```json
{ "contentType": "image/jpeg", "sizeBytes": 123456, "purpose": "message_attachment" }
```

Response:
```json
{ "uploadUrl": "https://s3/...signed...", "objectKey": "att/2026/01/uuid.bin", "expiresAt": 1730000000000, "headers": { "Content-Type": "application/octet-stream" } }
```

### 6.2 Encrypt + upload (client)
- `fileKey = random(32)`
- `fileNonce = random(24)`
- `fileCiphertext = secretbox(fileBytes, fileNonce, fileKey)`
- upload ciphertext to S3 using `uploadUrl`

### 6.3 Attachment pointer inside message
Encrypt `fileKey` to recipient:
- `fileKeyBox = box(fileKey, keyNonce(24), recipientEncPublicKey, senderEncPrivateKey)`

Payload:
```json
{
  "attachment": {
    "objectKey": "att/...bin",
    "contentType": "image/jpeg",
    "ciphertextSize": 123456,
    "fileNonce": "base64(24)",
    "fileKeyBox": { "nonce": "base64(24)", "ciphertext": "base64" }
  }
}
```

### 6.4 Download (HTTP)
`GET /attachments/{objectKey}/download` (auth) returns a short-lived signed URL:
```json
{ "downloadUrl": "https://s3/...signed...", "expiresAt": 1730000000000 }
```

### 6.5 Attachment GC (MANDATORY)
Store attachment rows in Postgres (`attachments` table) and delete when:
- all intended recipients have ACKed delivery **OR**
- TTL expires (e.g. 30 days)

Provide admin/manual trigger endpoint:
- `POST /admin/attachments/gc/run`

---

## 7) Groups (FROZEN: Pairwise Fanout Only)

✅ **Groups MUST use pairwise fanout encryption only. No group key in this rebuild.**

### Group create/update (WS)
- `group_create {title, memberIds[]}`
- `group_update {addMembers[], removeMembers[], title?}`

Server stores:
- `groups`, `group_members`

### Group send (client)
- For each member `m`: encrypt & sign a per-recipient envelope where `to = memberId` and include `groupId` in payload.

Group message payload example:
```json
{
  "type": "group_send_message",
  "payload": {
    "protocolVersion": 1,
    "cryptoVersion": 1,
    "sessionToken": "opaque",
    "groupId": "grp-uuid",
    "messageId": "uuid",
    "from": "WSP-sender",
    "to": "WSP-member",
    "msgType": "text|image|voice|file",
    "timestamp": 1730000000000,
    "nonce": "base64(24)",
    "ciphertext": "base64",
    "sig": "base64",
    "attachment": null
  }
}
```

Server validates membership (sender is a member) and routes per recipient.

---

## 8) Push Notifications (Wake-up only)

Push is **wake-up**, not transport. Transport is WS + pending queue.

- On offline delivery: server sends push to recipient
- On push: app connects WS + fetch_pending

Token updates:
- `update_tokens` is lightweight; no re-register.

---

## 9) Calls (Voice + Video) — Signed Signaling + TURN + Native UI

### 9.1 Signaling messages (WS) — MUST be signed
- call_initiate (offer)
- call_answer (answer)
- call_ice_candidate
- call_end
- call_ringing (server → caller)

All contain:
- sessionToken
- callId
- from/to
- timestamp
- nonce + ciphertext where ciphertext is the SDP/candidate blob (optional to encrypt, but MUST be signed)

**Rule:** Server rejects signaling without valid sig and valid session.

### 9.2 TURN credentials
- `get_turn_credentials` returns time-limited TURN URLs/username/credential.

### 9.3 Native call UI
- iOS: CallKit + VoIP push for incoming calls
- Android: ConnectionService + full-screen intent for incoming calls

---

## 10) Presence + Typing (Bounded, Rate-Limited)

Presence:
- only on connect/disconnect/timeout
- only sent to direct peers (contacts or active conversation peers)
- rate limited

Typing:
- only within active conversation
- max 1 event per 2 seconds per peer

---

## 11) Limits (Mandatory)

Define and enforce:
- MAX_WS_FRAME_BYTES: 512_000
- MAX_TEXT_BYTES (plaintext): 8_000
- MAX_IMAGE_BYTES (ciphertext): 15_000_000
- MAX_VOICE_BYTES (ciphertext): 25_000_000
- MAX_FILE_BYTES (ciphertext): 50_000_000
- PENDING_TTL_HOURS: 72
- FETCH_PENDING_LIMIT_DEFAULT: 50
- SESSION_TTL_DAYS: 7
- CHALLENGE_TTL_SECONDS: 60
- TIMESTAMP_SKEW_MS: 600_000 (±10 minutes)

---

## 12) Server Design (Concrete)

### 12.1 Module map (frozen)
Transport:
- WsGateway (AJV validate → auth check → dispatch)
- HttpAdmin (health/ready/metrics/admin endpoints)

Domain services:
- AuthService (challenges + sessions + refresh + logout)
- ConnectionManager (connId ↔ whisperId)
- PresenceService
- MessageRouter
- PendingQueue (paging)
- PushService (coalesce + retry/backoff)
- GroupService (membership enforcement)
- CallSignalingService
- TurnCredentialService
- RateLimiter
- AbuseService (bans/reports)
- AuditService

### 12.2 Storage
Postgres tables:
- users
- devices
- groups
- group_members
- bans
- reports
- audit_events
- attachments

Redis keys:
- presence:{whisperId}
- pending:{whisperId}
- ratelimit:{key}:{action}
- session:{token}
- call:{callId}
- challenge:{challengeId}

---

## 13) Observability & Ops (Mandatory)

Logs:
- JSON logs with connId, whisperId, deviceId, messageId, event, latencyMs, code
- Never log tokens, keys, plaintext, full ciphertext (only sizes)

Metrics:
- ws connections active
- pending queue size
- routing latency p50/p95/p99
- push success/fail
- call setup success/fail
- error rate by code

Health:
- GET /health
- GET /ready
- GET /metrics

Rate limiting:
- per IP + per whisperId
- for register, send_message, fetch_pending, update_tokens, group ops, call ops

---

## 14) Backup / Restore Policy (FROZEN)

✅ **Seed phrase restores:**
- **Identity** (Whisper ID + keys)
- **Contact list** (encrypted backup)

❌ **Seed phrase does NOT restore:**
- message history
- attachments
- media cache

### 14.1 Contacts Backup Model (Zero‑knowledge)

**Goal:** Restore the contact list without giving the server readable social graph data.

**Approach:** The server stores a single **encrypted contacts blob** per Whisper ID.

- Server stores: `ciphertext`, `nonce`, `updatedAt`, `sizeBytes`
- Server cannot read inside the blob.
- Client derives a dedicated backup key from the same BIP39 seed, using domain separation.

#### Contacts backup key (frozen)
- `contactsKey = HKDF-SHA256(seed, salt="whisper", info="whisper/contacts", len=32)`

#### Blob encryption (frozen)
- `nonce = random(24 bytes)`
- `ciphertext = secretbox(contactsJsonBytes, nonce, contactsKey)`
- Upload `{nonce, ciphertext}` (both base64).

#### Contacts JSON schema (inside encrypted blob)
```json
{
  "version": 1,
  "exportedAt": 1730000000000,
  "contacts": [
    {
      "whisperId": "WSP-....",
      "addedAt": 1730000000000,
      "displayName": "optional",
      "notes": "optional",
      "isBlocked": false,
      "isPinned": false,
      "isMuted": false
    }
  ]
}
```

Rules:
- **No messages. No conversation metadata. No last-seen logs** inside the backup.
- Avatar is **local-only** by default (optional to include as a URL, but not recommended).

### 14.2 Contacts Backup Endpoints (MANDATORY)

These are part of the viable project spec.

#### Upload contacts backup
- `PUT /backup/contacts` (auth required)
Request:
```json
{
  "nonce": "base64(24)",
  "ciphertext": "base64",
  "cryptoVersion": 1,
  "protocolVersion": 1
}
```
Response:
```json
{ "success": true, "updatedAt": 1730000000000 }
```

#### Download contacts backup
- `GET /backup/contacts` (auth required)
Response:
```json
{
  "nonce": "base64(24)",
  "ciphertext": "base64",
  "updatedAt": 1730000000000
}
```

#### Delete contacts backup (optional but recommended)
- `DELETE /backup/contacts` (auth required)

### 14.3 When to upload
Client uploads the encrypted contacts blob when:
- a contact is added/removed/blocked/unblocked
- contact metadata changes (displayName/pin/mute)
- on app background (debounced) or once per X minutes if changes pending

### 14.4 Server storage
Store in Postgres table:
- `contact_backups(whisper_id PK, nonce_b64, ciphertext_b64, updated_at, size_bytes)`

### 14.5 Disaster stance
- If backup blob is lost server-side, user can still recover identity with seed phrase, but contacts won’t restore.
- This is acceptable; communicate clearly in UI.

Server backups:
- Postgres daily backups + retention
- Redis is non-authoritative (safe to lose)

---

## 15) Minimal Admin API (Frozen)
- GET `/health`
- GET `/ready`
- GET `/metrics`
- GET `/admin/stats` (auth)
- GET `/admin/reports` (auth)
- POST `/admin/reports/{id}/review` (auth)
- POST `/admin/ban` (auth)
- POST `/admin/unban` (auth)
- GET `/admin/bans` (auth)
- POST `/attachments/presign` (auth)
- GET `/attachments/{objectKey}/download` (auth)
- POST `/admin/attachments/gc/run` (auth)
- PUT `/backup/contacts` (auth)
- GET `/backup/contacts` (auth)
- DELETE `/backup/contacts` (auth, optional)

---

## 16) Build Order (Single integrated plan)

1) Freeze contracts + schemas + crypto vectors.
2) Build server core (auth/sessions, routing, pending, push, receipts, limits, metrics).
3) Build iOS full client.
4) Build Android full client (mirror behavior).
5) Harden (admin workflows, GC, load tests, disaster playbook).
