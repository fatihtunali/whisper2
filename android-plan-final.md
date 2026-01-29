get# Whisper2 Android Native App - Development Plan

## Executive Summary

Build a native Android app (Kotlin + Jetpack Compose) that is 100% compatible with the existing server and iOS client. The app must implement identical cryptographic operations, WebSocket protocol, and feature set.

---

## Phase 0: Development Principles (READ FIRST)

> **No mock data is allowed. All Android behaviors must match server responses byte-for-byte. Any discrepancy is treated as a bug, not a platform difference.**

### Core Principles
1. **Server is the source of truth** - Never assume, always verify against server
2. **Crypto must be identical** - Same input = same output across iOS/Android/Server
3. **Test vectors are law** - If test vectors fail, code is broken
4. **No shortcuts** - Every protocol detail matters for E2E encryption

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                           PRESENTATION                               │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Jetpack Compose UI (Screens, Components, Navigation)         │   │
│  └──────────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  ViewModels (StateFlow, UiState, Events)                      │   │
│  └──────────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────────┤
│                             DOMAIN                                   │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Use Cases / Interactors                                      │   │
│  └──────────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Domain Models (User, Message, Contact, Group, Call)          │   │
│  └──────────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────────┤
│                              DATA                                    │
│  ┌────────────────┐ ┌────────────────┐ ┌────────────────────────┐   │
│  │  WebSocket     │ │  HTTP Client   │ │  Local Storage         │   │
│  │  (OkHttp)      │ │  (Retrofit)    │ │  Room (messages/outbox)│   │
│  │                │ │                │ │  Keystore (AES wrapper)│   │
│  │                │ │                │ │  EncryptedSharedPrefs  │   │
│  └────────────────┘ └────────────────┘ └────────────────────────┘   │
├─────────────────────────────────────────────────────────────────────┤
│                             CRYPTO                                   │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  TweetNaCl (Lazysodium-android) + BIP39 + HKDF               │   │
│  └──────────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────────┤
│                           SERVICES                                   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │
│  │  Auth    │ │Messaging │ │ Contacts │ │  Calls   │ │  Push    │  │
│  │ Service  │ │ Service  │ │ Service  │ │ Service  │ │ Service  │  │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Phase 1: Project Setup & Core Infrastructure

### 1.1 Project Structure
```
android/
├── app/
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/whisper2/app/
│   │   │   ├── App.kt                    # Application class
│   │   │   ├── di/                       # Dependency Injection (Hilt)
│   │   │   │   ├── AppModule.kt
│   │   │   │   ├── CryptoModule.kt
│   │   │   │   ├── NetworkModule.kt
│   │   │   │   ├── DatabaseModule.kt
│   │   │   │   └── ServiceModule.kt
│   │   │   ├── core/                     # Core utilities
│   │   │   │   ├── Constants.kt          # FROZEN constants
│   │   │   │   ├── Errors.kt
│   │   │   │   ├── Logger.kt
│   │   │   │   └── Extensions.kt
│   │   │   ├── crypto/                   # Cryptography
│   │   │   ├── domain/                   # Domain models
│   │   │   ├── data/                     # Data layer
│   │   │   ├── services/                 # Business services
│   │   │   └── ui/                       # Compose UI
│   │   └── res/
│   └── src/test/                         # Unit tests
├── build.gradle.kts
└── settings.gradle.kts
```

### 1.2 Dependencies
```kotlin
// build.gradle.kts (app)
dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Hilt (Dependency Injection)
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // Crypto
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // Storage
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // WebRTC
    implementation("io.getstream:stream-webrtc-android:1.1.1")

    // Firebase (Push)
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
```

### 1.3 Core Constants (FROZEN - Must Match Server/iOS)
```kotlin
// core/Constants.kt
object Constants {
    // Server
    const val WS_URL = "wss://whisper2.aiakademiturkiye.com/ws"
    const val BASE_URL = "https://whisper2.aiakademiturkiye.com"

    // Protocol
    const val PROTOCOL_VERSION = 1
    const val CRYPTO_VERSION = 1

    // Crypto (FROZEN - DO NOT CHANGE)
    const val BIP39_SEED_LENGTH = 64
    const val HKDF_SALT = "whisper"
    const val ENCRYPTION_DOMAIN = "whisper/enc"
    const val SIGNING_DOMAIN = "whisper/sign"
    const val CONTACTS_DOMAIN = "whisper/contacts"
    const val BIP39_ITERATIONS = 2048
    const val BIP39_SALT = "mnemonic"

    // Nonce sizes
    const val NACL_NONCE_SIZE = 24        // XSalsa20-Poly1305 nonce
    const val NACL_KEY_SIZE = 32          // Key size for NaCl

    // Limits
    const val TIMESTAMP_SKEW_MS = 10 * 60 * 1000L  // ±10 minutes
    const val SESSION_TTL_DAYS = 7
    const val MAX_GROUP_MEMBERS = 50
    const val MAX_GROUP_TITLE = 64
    const val MAX_ATTACHMENT_SIZE = 100 * 1024 * 1024  // 100MB
    const val MAX_BACKUP_SIZE = 256 * 1024  // 256KB

    // Message Types (40+ types)
    object MsgType {
        const val REGISTER_BEGIN = "register_begin"
        const val REGISTER_CHALLENGE = "register_challenge"
        const val REGISTER_PROOF = "register_proof"
        const val REGISTER_ACK = "register_ack"
        const val SEND_MESSAGE = "send_message"
        const val MESSAGE_RECEIVED = "message_received"
        const val MESSAGE_ACCEPTED = "message_accepted"
        const val MESSAGE_DELIVERED = "message_delivered"
        const val DELIVERY_RECEIPT = "delivery_receipt"
        const val FETCH_PENDING = "fetch_pending"
        const val PENDING_MESSAGES = "pending_messages"
        const val TYPING_INDICATOR = "typing_indicator"
        const val CALL_INITIATE = "call_initiate"
        const val CALL_INCOMING = "call_incoming"
        const val CALL_ANSWER = "call_answer"
        const val CALL_RINGING = "call_ringing"
        const val CALL_ICE_CANDIDATE = "call_ice_candidate"
        const val CALL_END = "call_end"
        const val GET_TURN_CREDENTIALS = "get_turn_credentials"
        const val TURN_CREDENTIALS = "turn_credentials"
        const val GROUP_CREATE = "group_create"
        const val GROUP_UPDATE = "group_update"
        const val GROUP_EVENT = "group_event"
        const val GROUP_SEND_MESSAGE = "group_send_message"
        const val UPDATE_TOKENS = "update_tokens"
        const val TOKENS_UPDATED = "tokens_updated"
        const val SESSION_REFRESH = "session_refresh"
        const val SESSION_REFRESH_ACK = "session_refresh_ack"
        const val PING = "ping"
        const val PONG = "pong"
        const val ERROR = "error"
    }

    // Error codes
    object ErrorCode {
        const val NOT_REGISTERED = "NOT_REGISTERED"
        const val AUTH_FAILED = "AUTH_FAILED"
        const val INVALID_PAYLOAD = "INVALID_PAYLOAD"
        const val INVALID_TIMESTAMP = "INVALID_TIMESTAMP"
        const val RATE_LIMITED = "RATE_LIMITED"
    }
}
```

### 1.4 Deliverables
- [ ] Android Studio project with Kotlin DSL gradle
- [ ] Hilt dependency injection setup
- [ ] Core constants matching server/iOS exactly
- [ ] Error handling framework
- [ ] Logging infrastructure
- [ ] ProGuard/R8 rules for crypto libraries (CRITICAL for Lazysodium + JNA)

---

## Phase 2: Cryptography Layer (CRITICAL)

### 2.1 Files
```
crypto/
├── BIP39.kt              # Mnemonic generation & validation
├── BIP39WordList.kt      # 2048 English BIP39 words
├── KeyDerivation.kt      # PBKDF2 + HKDF key derivation
├── NaClBox.kt            # X25519 + XSalsa20-Poly1305
├── NaClSecretBox.kt      # Symmetric encryption
├── Signatures.kt         # Ed25519 signatures
├── CanonicalSigning.kt   # Message signing (canonical format)
├── NonceGenerator.kt     # Secure nonce generation (CRITICAL)
└── CryptoService.kt      # Main crypto orchestrator
```

### 2.2 Key Derivation Chain (MUST MATCH SERVER/iOS)
```kotlin
// KeyDerivation.kt

/**
 * Derives all keys from a BIP39 mnemonic.
 *
 * Chain:
 * Mnemonic → PBKDF2-HMAC-SHA512 → 64-byte BIP39 Seed
 *         → HKDF-SHA256 (salt="whisper")
 *         ├── info="whisper/enc"      → 32-byte encSeed    → X25519 keypair
 *         ├── info="whisper/sign"     → 32-byte signSeed   → Ed25519 keypair
 *         └── info="whisper/contacts" → 32-byte contactsKey
 */
object KeyDerivation {

    /**
     * Derives 64-byte BIP39 seed from mnemonic.
     * Uses PBKDF2-HMAC-SHA512 with salt="mnemonic" and iterations=2048.
     */
    fun seedFromMnemonic(mnemonic: String): ByteArray {
        val normalizedMnemonic = mnemonic.normalize(Normalizer.Form.NFKD)
            .trim()
            .replace(Regex("\\s+"), " ")
        val password = normalizedMnemonic.toByteArray(Charsets.UTF_8)
        val salt = "mnemonic".toByteArray(Charsets.UTF_8)

        return PBKDF2.derive(
            password = password,
            salt = salt,
            iterations = 2048,
            keyLength = 64,
            algorithm = "HmacSHA512"
        )
    }

    /**
     * Derives a key using HKDF-SHA256.
     */
    fun deriveKey(seed: ByteArray, info: String): ByteArray {
        return HKDF.expand(
            ikm = seed,
            salt = "whisper".toByteArray(Charsets.UTF_8),
            info = info.toByteArray(Charsets.UTF_8),
            length = 32
        )
    }

    /**
     * Generates X25519 keypair from 32-byte seed.
     */
    fun generateEncryptionKeyPair(seed: ByteArray): KeyPair {
        return NaClBox.generateKeyPair(seed)
    }

    /**
     * Generates Ed25519 keypair from 32-byte seed.
     */
    fun generateSigningKeyPair(seed: ByteArray): KeyPair {
        return Signatures.generateKeyPair(seed)
    }
}

data class DerivedKeys(
    val encSeed: ByteArray,
    val encKeyPair: KeyPair,
    val signSeed: ByteArray,
    val signKeyPair: KeyPair,
    val contactsKey: ByteArray
)
```

### 2.3 Nonce Generation (CRITICAL - Must Use SecureRandom)
```kotlin
// crypto/NonceGenerator.kt

import java.security.SecureRandom

/**
 * Generates cryptographically secure nonces for NaCl operations.
 *
 * CRITICAL: Always use SecureRandom, never Random or other PRNGs.
 * XSalsa20-Poly1305 requires exactly 24-byte nonces.
 */
object NonceGenerator {

    private val secureRandom = SecureRandom()

    /**
     * Generates a 24-byte nonce for XSalsa20-Poly1305 (NaCl box/secretbox).
     *
     * @return 24 random bytes from SecureRandom
     */
    fun generateNonce(): ByteArray {
        val nonce = ByteArray(Constants.NACL_NONCE_SIZE) // 24 bytes
        secureRandom.nextBytes(nonce)
        return nonce
    }

    /**
     * Generates a 32-byte key for NaCl secretbox (attachment encryption).
     *
     * @return 32 random bytes from SecureRandom
     */
    fun generateKey(): ByteArray {
        val key = ByteArray(Constants.NACL_KEY_SIZE) // 32 bytes
        secureRandom.nextBytes(key)
        return key
    }

    /**
     * Validates nonce size.
     */
    fun isValidNonce(nonce: ByteArray): Boolean {
        return nonce.size == Constants.NACL_NONCE_SIZE
    }

    /**
     * Validates key size.
     */
    fun isValidKey(key: ByteArray): Boolean {
        return key.size == Constants.NACL_KEY_SIZE
    }
}
```

### 2.4 Challenge Signing (Authentication)
```kotlin
// CanonicalSigning.kt

/**
 * Signs authentication challenge.
 * Server expects: Ed25519_Sign(SHA256(challengeBytes), privateKey)
 */
object CanonicalSigning {

    fun signChallenge(challengeBytes: ByteArray, privateKey: ByteArray): ByteArray {
        // SHA256 hash first!
        val hash = MessageDigest.getInstance("SHA-256").digest(challengeBytes)
        // Then sign the hash
        return Signatures.sign(hash, privateKey)
    }

    /**
     * Signs a message using canonical format.
     * Format:
     * v1\n
     * messageType\n
     * messageId\n
     * from\n
     * toOrGroupId\n
     * timestamp\n
     * nonceB64\n
     * ciphertextB64\n
     */
    fun signMessage(
        messageType: String,
        messageId: String,
        from: String,
        toOrGroupId: String,
        timestamp: Long,
        nonceB64: String,
        ciphertextB64: String,
        privateKey: ByteArray
    ): ByteArray {
        val canonical = buildString {
            append("v1\n")
            append("$messageType\n")
            append("$messageId\n")
            append("$from\n")
            append("$toOrGroupId\n")
            append("$timestamp\n")
            append("$nonceB64\n")
            append("$ciphertextB64\n")
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return Signatures.sign(hash, privateKey)
    }
}
```

### 2.5 Test Vectors (FROZEN)
```kotlin
// tests/CryptoVectorsTest.kt
class CryptoVectorsTest {

    @Test
    fun `test vector - key derivation matches server`() {
        // Same test mnemonic as iOS/server
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

        val seed = KeyDerivation.seedFromMnemonic(mnemonic)
        val encSeed = KeyDerivation.deriveKey(seed, Constants.ENCRYPTION_DOMAIN)
        val signSeed = KeyDerivation.deriveKey(seed, Constants.SIGNING_DOMAIN)
        val contactsKey = KeyDerivation.deriveKey(seed, Constants.CONTACTS_DOMAIN)

        val encKeyPair = KeyDerivation.generateEncryptionKeyPair(encSeed)
        val signKeyPair = KeyDerivation.generateSigningKeyPair(signSeed)

        // These MUST match iOS/server test vectors
        assertEquals(EXPECTED_ENC_PUBLIC_KEY, encKeyPair.publicKey.toBase64())
        assertEquals(EXPECTED_SIGN_PUBLIC_KEY, signKeyPair.publicKey.toBase64())
        // ... more assertions
    }

    @Test
    fun `test nonce generation produces 24 bytes`() {
        val nonce = NonceGenerator.generateNonce()
        assertEquals(24, nonce.size)
        assertTrue(NonceGenerator.isValidNonce(nonce))
    }

    @Test
    fun `test encryption round trip with fixed nonce`() {
        // Use fixed nonce for deterministic testing
        val fixedNonce = ByteArray(24) { it.toByte() }
        // Test encryption/decryption produces same result
    }
}
```

### 2.6 Deliverables
- [ ] BIP39 mnemonic generation (12/24 words)
- [ ] BIP39 seed derivation (PBKDF2-HMAC-SHA512)
- [ ] HKDF-SHA256 key derivation
- [ ] X25519 key pair generation
- [ ] Ed25519 key pair generation
- [ ] NaCl box encryption/decryption
- [ ] NaCl secretbox symmetric encryption
- [ ] Challenge signing (SHA256 + Ed25519)
- [ ] Message signing (canonical format)
- [ ] **Nonce generation with SecureRandom (24 bytes)**
- [ ] Test vectors matching iOS/server

---

## Phase 3: Networking Layer

### 3.1 Files
```
data/
├── network/
│   ├── api/
│   │   ├── WhisperApi.kt         # Retrofit HTTP API
│   │   ├── AttachmentsApi.kt     # Attachment presign endpoints
│   │   └── ApiModels.kt          # HTTP request/response models
│   └── ws/
│       ├── WsClient.kt           # WebSocket client (OkHttp)
│       ├── WsModels.kt           # WebSocket frame models
│       ├── WsMessageRouter.kt    # Message type routing
│       └── WsReconnectPolicy.kt  # Exponential backoff with state
```

### 3.2 WebSocket Frame Models
```kotlin
// ws/WsModels.kt

/**
 * Generic WebSocket frame wrapper.
 */
data class WsFrame<T>(
    val type: String,
    val requestId: String? = null,
    val payload: T
)

// Authentication
data class RegisterBeginPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val deviceId: String,
    val platform: String = "android",
    val whisperId: String? = null  // Present if recovery
)

data class RegisterChallengePayload(
    val challengeId: String,
    val challenge: String,  // base64
    val expiresAt: Long
)

data class RegisterProofPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val challengeId: String,
    val deviceId: String,
    val platform: String = "android",
    val whisperId: String? = null,
    val encPublicKey: String,   // base64
    val signPublicKey: String,  // base64
    val signature: String,      // base64
    val pushToken: String? = null  // FCM token
)

data class RegisterAckPayload(
    val success: Boolean,
    val whisperId: String,
    val sessionToken: String,
    val sessionExpiresAt: Long,
    val serverTime: Long
)

// Messaging
data class SendMessagePayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val messageId: String,
    val from: String,
    val to: String,
    val msgType: String,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val sig: String,
    val replyTo: String? = null,
    val attachment: AttachmentPointer? = null
)

data class MessageReceivedPayload(
    val messageId: String,
    val groupId: String? = null,
    val from: String,
    val to: String,
    val msgType: String,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val sig: String,
    val replyTo: String? = null,
    val attachment: AttachmentPointer? = null
)

// Calls
data class CallInitiatePayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val callId: String,
    val from: String,
    val to: String,
    val isVideo: Boolean,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,  // Encrypted SDP offer
    val sig: String
)

data class TurnCredentialsPayload(
    val urls: List<String>,
    val username: String,
    val credential: String,
    val ttl: Int
)

// ... 30+ more payload types
```

### 3.3 WebSocket Client (CRITICAL: Correct JSON Parsing)
```kotlin
// ws/WsClient.kt

@Singleton
class WsClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    private var webSocket: WebSocket? = null
    private val _connectionState = MutableStateFlow(WsConnectionState.DISCONNECTED)
    val connectionState: StateFlow<WsConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableSharedFlow<WsFrame<JsonElement>>(
        extraBufferCapacity = 100
    )
    val messages: SharedFlow<WsFrame<JsonElement>> = _messages.asSharedFlow()

    private val reconnectPolicy = WsReconnectPolicy()
    private var scope: CoroutineScope? = null

    fun connect() {
        _connectionState.value = WsConnectionState.CONNECTING
        val request = Request.Builder()
            .url(Constants.WS_URL)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = WsConnectionState.CONNECTED
                reconnectPolicy.reset()
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope?.launch {
                    try {
                        // CRITICAL: Use TypeToken for generic type preservation!
                        // Without this, payload becomes LinkedTreeMap and causes ClassCastException
                        val frameType = object : TypeToken<WsFrame<JsonElement>>() {}.type
                        val frame: WsFrame<JsonElement> = gson.fromJson(text, frameType)
                        _messages.emit(frame)
                    } catch (e: JsonSyntaxException) {
                        Logger.error("Failed to parse WebSocket message", e)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = WsConnectionState.DISCONNECTED
                attemptReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = WsConnectionState.DISCONNECTED
            }
        })
    }

    fun <T> send(frame: WsFrame<T>) {
        val json = gson.toJson(frame)
        webSocket?.send(json)
    }

    private fun startHeartbeat() {
        scope?.launch {
            while (connectionState.value == WsConnectionState.CONNECTED) {
                delay(30_000)
                send(WsFrame("ping", payload = PingPayload(System.currentTimeMillis())))
            }
        }
    }

    private fun attemptReconnect() {
        if (reconnectPolicy.shouldRetry()) {
            _connectionState.value = WsConnectionState.RECONNECTING
            scope?.launch {
                delay(reconnectPolicy.getDelayMs())
                connect()
            }
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = WsConnectionState.DISCONNECTED
    }
}

enum class WsConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, AUTH_EXPIRED
}
```

### 3.4 Reconnect Policy (Enhanced with Auth & Network States)
```kotlin
// ws/WsReconnectPolicy.kt

/**
 * Manages WebSocket reconnection with exponential backoff.
 * Handles special cases: auth expired, network offline.
 */
class WsReconnectPolicy {

    private var attemptCount = 0
    private var isAuthExpired = false
    private var isNetworkAvailable = true

    companion object {
        const val MAX_ATTEMPTS = 5
        const val BASE_DELAY_MS = 1000L
        const val MAX_DELAY_MS = 30000L
    }

    fun shouldRetry(): Boolean {
        // Never retry if auth expired - need re-authentication
        if (isAuthExpired) return false

        // Pause if network unavailable
        if (!isNetworkAvailable) return false

        return attemptCount < MAX_ATTEMPTS
    }

    fun getDelayMs(): Long {
        val delay = BASE_DELAY_MS * (1 shl attemptCount) // Exponential: 1s, 2s, 4s, 8s, 16s
        attemptCount++
        return minOf(delay, MAX_DELAY_MS)
    }

    fun reset() {
        attemptCount = 0
        isAuthExpired = false
    }

    fun markAuthExpired() {
        isAuthExpired = true
    }

    fun setNetworkAvailable(available: Boolean) {
        isNetworkAvailable = available
        if (available && !isAuthExpired) {
            // Reset attempt count when network comes back
            attemptCount = 0
        }
    }

    fun isAuthenticationRequired(): Boolean = isAuthExpired
}
```

### 3.5 HTTP API (Retrofit)
```kotlin
// api/WhisperApi.kt

interface WhisperApi {

    @GET("users/{whisperId}/keys")
    suspend fun getUserKeys(
        @Header("Authorization") token: String,
        @Path("whisperId") whisperId: String
    ): UserKeysResponse

    @PUT("backup/contacts")
    suspend fun uploadContactsBackup(
        @Header("Authorization") token: String,
        @Body backup: ContactsBackupRequest
    ): ContactsBackupResponse

    @GET("backup/contacts")
    suspend fun downloadContactsBackup(
        @Header("Authorization") token: String
    ): ContactsBackupResponse

    @DELETE("backup/contacts")
    suspend fun deleteContactsBackup(
        @Header("Authorization") token: String
    ): DeleteResponse

    @GET("health")
    suspend fun healthCheck(): HealthResponse
}

interface AttachmentsApi {

    @POST("attachments/presign/upload")
    suspend fun presignUpload(
        @Header("Authorization") token: String,
        @Body request: PresignUploadRequest
    ): PresignUploadResponse

    @POST("attachments/presign/download")
    suspend fun presignDownload(
        @Header("Authorization") token: String,
        @Body request: PresignDownloadRequest
    ): PresignDownloadResponse
}
```

### 3.6 Deliverables
- [ ] OkHttp WebSocket client with reconnection
- [ ] **TypeToken-based JSON parsing (prevents ClassCastException)**
- [ ] All 40+ WebSocket message type models
- [ ] Message routing by type
- [ ] **Exponential backoff with auth/network state handling**
- [ ] Heartbeat ping every 30 seconds
- [ ] Retrofit HTTP client for REST endpoints
- [ ] Bearer token authentication header
- [ ] Connection state management

---

## Phase 4: Data Persistence Layer

### 4.1 Files
```
data/
├── local/
│   ├── db/
│   │   ├── WhisperDatabase.kt        # Room database
│   │   ├── entities/
│   │   │   ├── MessageEntity.kt
│   │   │   ├── ConversationEntity.kt
│   │   │   ├── ContactEntity.kt
│   │   │   ├── GroupEntity.kt
│   │   │   ├── CallRecordEntity.kt
│   │   │   └── OutboxEntity.kt       # Unsent messages (survives process death)
│   │   └── dao/
│   │       ├── MessageDao.kt
│   │       ├── ConversationDao.kt
│   │       ├── ContactDao.kt
│   │       ├── OutboxDao.kt          # For offline queue persistence
│   │       └── CallRecordDao.kt
│   ├── prefs/
│   │   ├── SecureStorage.kt          # Keystore-wrapped key storage
│   │   └── AppPreferences.kt         # DataStore preferences
│   └── keystore/
│       └── KeystoreManager.kt        # Android Keystore wrapper key
```

### 4.2 Room Database
```kotlin
// db/WhisperDatabase.kt

@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        ContactEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        CallRecordEntity::class,
        OutboxEntity::class
    ],
    version = 1
)
@TypeConverters(Converters::class)
abstract class WhisperDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun contactDao(): ContactDao
    abstract fun outboxDao(): OutboxDao
    abstract fun callRecordDao(): CallRecordDao
}

// db/entities/MessageEntity.kt
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val groupId: String?,
    val from: String,
    val to: String,
    val msgType: String,              // text, image, audio, file, location
    val content: String,              // Decrypted text content (or JSON for location)
    val timestamp: Long,
    val status: String,               // pending, sent, delivered, read, failed
    val direction: String,            // incoming, outgoing
    val replyTo: String?,

    // Attachment fields (for image, audio, file)
    val attachmentBlobId: String?,    // Server blob ID
    val attachmentKey: String?,       // Decryption key (base64)
    val attachmentNonce: String?,     // Decryption nonce (base64)
    val attachmentContentType: String?,
    val attachmentSize: Long?,
    val attachmentFileName: String?,  // Original filename (for files)
    val attachmentDuration: Int?,     // Duration in seconds (for audio)
    val attachmentWidth: Int?,        // Width in pixels (for images)
    val attachmentHeight: Int?,       // Height in pixels (for images)
    val attachmentThumbnail: String?, // Base64 blurred thumbnail (for images)
    val attachmentLocalPath: String?, // Local cache path (after download/decrypt)

    // Location fields (for location messages)
    val locationLatitude: Double?,
    val locationLongitude: Double?,
    val locationAccuracy: Float?,
    val locationPlaceName: String?,
    val locationAddress: String?,

    // Timestamps
    val createdAt: Long,
    val readAt: Long?
)

// db/entities/OutboxEntity.kt - CRITICAL for offline queue
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val messageId: String,
    val to: String,
    val groupId: String?,
    val msgType: String,
    val encryptedPayload: String,  // Full SendMessagePayload as JSON
    val retryCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val createdAt: Long,
    val status: String  // pending, sending, failed
)
```

### 4.3 Local Message Storage Policy

> **IMPORTANT**: This defines how messages are persisted on the device. The server is NOT a long-term message store.

| Aspect | Policy |
|--------|--------|
| **Storage location** | Room database (SQLite) on device |
| **Storage format** | Decrypted plaintext (after signature verification) |
| **Persistence** | Survives app restarts, device reboots |
| **Deletion trigger** | Explicit user action only (logout, clear history) |
| **Server retention** | 72 hours (offline queue for delivery) |

#### What Gets Stored Locally
- **Incoming messages**: Stored after successful signature verification + decryption
- **Outgoing messages**: Stored immediately with status `pending`
- **Delivery receipts**: Update message status (`sent` → `delivered` → `read`)
- **Attachments metadata**: Key, content type, size (actual files cached separately)

#### What Does NOT Get Stored
- Raw ciphertext (discarded after decryption)
- Nonces (used only for decryption, then discarded)
- Signatures (verified, then discarded)
- Other users' private keys (never received)

#### Storage Lifecycle
```
┌─────────────────────────────────────────────────────────────┐
│                    OUTGOING MESSAGE                         │
├─────────────────────────────────────────────────────────────┤
│  1. User types message                                      │
│  2. Encrypt → store in Room (status=pending)                │
│  3. Enqueue in OutboxQueue (Room-backed)                    │
│  4. Send via WebSocket                                      │
│  5. Receive message_accepted → update status=sent           │
│  6. Receive delivery_receipt → update status=delivered/read │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                    INCOMING MESSAGE                         │
├─────────────────────────────────────────────────────────────┤
│  1. Receive message_received via WebSocket                  │
│  2. Validate timestamp (±10 min)                            │
│  3. Verify signature                                        │
│  4. Decrypt ciphertext                                      │
│  5. Store decrypted content in Room (status=delivered)      │
│  6. Send delivery_receipt to sender                         │
│  7. Display in UI                                           │
└─────────────────────────────────────────────────────────────┘
```

#### Data Deletion Scenarios
| Action | Messages Deleted? | Keys Deleted? |
|--------|-------------------|---------------|
| App killed | No | No |
| Device reboot | No | No |
| App update | No | No |
| Clear chat history | Yes (that chat only) | No |
| Logout | Yes (all) | Yes (all) |
| Uninstall app | Yes (all) | Yes (all) |

### 4.4 Secure Storage with Keystore Wrapping (CRITICAL FIX)
```kotlin
// prefs/SecureStorage.kt

/**
 * Secure storage for cryptographic keys using Android Keystore wrapping.
 *
 * CRITICAL: Private keys are wrapped with an AES key stored in Android Keystore.
 * This provides hardware-backed security (when available) and survives app updates.
 *
 * Architecture:
 * Android Keystore (AES-GCM key)
 *        ↓
 * wrap(privateKey) → wrappedKey
 *        ↓
 * EncryptedSharedPreferences stores wrappedKey
 */
@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreManager: KeystoreManager
) {
    private val prefs = EncryptedSharedPreferences.create(
        "whisper2_secure_prefs",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Private keys - wrapped with Keystore AES key
    var encPrivateKey: ByteArray?
        get() = prefs.getString(KEY_ENC_PRIVATE, null)
            ?.let { keystoreManager.unwrapKey(it.decodeBase64()) }
        set(value) {
            val wrapped = value?.let { keystoreManager.wrapKey(it) }
            prefs.edit().putString(KEY_ENC_PRIVATE, wrapped?.encodeBase64()).apply()
        }

    var signPrivateKey: ByteArray?
        get() = prefs.getString(KEY_SIGN_PRIVATE, null)
            ?.let { keystoreManager.unwrapKey(it.decodeBase64()) }
        set(value) {
            val wrapped = value?.let { keystoreManager.wrapKey(it) }
            prefs.edit().putString(KEY_SIGN_PRIVATE, wrapped?.encodeBase64()).apply()
        }

    var contactsKey: ByteArray?
        get() = prefs.getString(KEY_CONTACTS, null)
            ?.let { keystoreManager.unwrapKey(it.decodeBase64()) }
        set(value) {
            val wrapped = value?.let { keystoreManager.wrapKey(it) }
            prefs.edit().putString(KEY_CONTACTS, wrapped?.encodeBase64()).apply()
        }

    // Public keys - no wrapping needed (not secret)
    var encPublicKey: ByteArray?
        get() = prefs.getString(KEY_ENC_PUBLIC, null)?.decodeBase64()
        set(value) = prefs.edit().putString(KEY_ENC_PUBLIC, value?.encodeBase64()).apply()

    var signPublicKey: ByteArray?
        get() = prefs.getString(KEY_SIGN_PUBLIC, null)?.decodeBase64()
        set(value) = prefs.edit().putString(KEY_SIGN_PUBLIC, value?.encodeBase64()).apply()

    // Session (not wrapped - needs to be readable quickly)
    var sessionToken: String?
        get() = prefs.getString(KEY_SESSION_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_SESSION_TOKEN, value).apply()

    var whisperId: String?
        get() = prefs.getString(KEY_WHISPER_ID, null)
        set(value) = prefs.edit().putString(KEY_WHISPER_ID, value).apply()

    // Mnemonic - wrapped for extra security
    var mnemonic: String?
        get() = prefs.getString(KEY_MNEMONIC, null)
            ?.let { String(keystoreManager.unwrapKey(it.decodeBase64()), Charsets.UTF_8) }
        set(value) {
            val wrapped = value?.let { keystoreManager.wrapKey(it.toByteArray(Charsets.UTF_8)) }
            prefs.edit().putString(KEY_MNEMONIC, wrapped?.encodeBase64()).apply()
        }

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        private set

    fun clearAll() {
        prefs.edit().clear().apply()
        keystoreManager.deleteKey()  // Also delete Keystore key
    }

    companion object {
        private const val KEY_ENC_PRIVATE = "enc_private_key"
        private const val KEY_ENC_PUBLIC = "enc_public_key"
        private const val KEY_SIGN_PRIVATE = "sign_private_key"
        private const val KEY_SIGN_PUBLIC = "sign_public_key"
        private const val KEY_CONTACTS = "contacts_key"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_WHISPER_ID = "whisper_id"
        private const val KEY_MNEMONIC = "mnemonic"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
```

### 4.5 Keystore Manager (Hardware-Backed When Available)
```kotlin
// keystore/KeystoreManager.kt

/**
 * Manages AES key in Android Keystore for wrapping sensitive data.
 * Provides hardware-backed security on supported devices.
 */
@Singleton
class KeystoreManager @Inject constructor() {

    companion object {
        private const val KEYSTORE_ALIAS = "whisper2_wrapper_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    /**
     * Gets or creates the AES-GCM wrapping key.
     */
    private fun getOrCreateKey(): SecretKey {
        return if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } else {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)  // Available without user auth
                    .build()
            )
            keyGenerator.generateKey()
        }
    }

    /**
     * Wraps (encrypts) a key with the Keystore AES key.
     * Returns: IV (12 bytes) + ciphertext + tag
     */
    fun wrapKey(keyToWrap: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(keyToWrap)

        // Prepend IV to ciphertext
        return iv + ciphertext
    }

    /**
     * Unwraps (decrypts) a previously wrapped key.
     * Input: IV (12 bytes) + ciphertext + tag
     */
    fun unwrapKey(wrappedKey: ByteArray): ByteArray {
        val iv = wrappedKey.copyOfRange(0, 12)
        val ciphertext = wrappedKey.copyOfRange(12, wrappedKey.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * Deletes the wrapping key (use during logout/wipe).
     */
    fun deleteKey() {
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        }
    }
}
```

### 4.6 Deliverables
- [ ] Room database with all entities
- [ ] DAOs with Flow queries for reactivity
- [ ] **Local message storage policy (decrypted, persistent)**
- [ ] **Keystore-wrapped private key storage**
- [ ] **KeystoreManager for AES-GCM wrapping**
- [ ] **OutboxEntity for offline queue persistence**
- [ ] DataStore for app preferences
- [ ] Database migrations strategy

---

## Phase 5: Service Layer

### 5.1 Files
```
services/
├── auth/
│   ├── AuthService.kt            # Registration, login, session
│   └── SessionManager.kt         # Session state & refresh
├── messaging/
│   ├── MessagingService.kt       # Send/receive messages
│   ├── MessageStore.kt           # Message persistence
│   ├── TimestampValidator.kt     # Timestamp skew validation
│   └── OutboxQueue.kt            # Offline queue with process death survival
├── contacts/
│   ├── ContactsService.kt        # Contact management
│   ├── ContactsBackupService.kt  # Encrypted backup
│   └── KeyLookupService.kt       # Public key lookup
├── calls/
│   ├── CallService.kt            # WebRTC call management
│   ├── WebRtcService.kt          # WebRTC peer connection
│   └── TurnService.kt            # TURN credentials
├── groups/
│   └── GroupService.kt           # Group management
├── attachments/
│   ├── AttachmentService.kt      # Upload/download
│   └── AttachmentCache.kt        # Local cache
└── push/
    ├── FcmService.kt             # Firebase messaging with lifecycle
    ├── FcmTokenManager.kt        # Token refresh & sync
    └── NotificationService.kt    # Notification display
```

### 5.2 Timestamp Validator (CRITICAL - Prevents Replay Attacks)
```kotlin
// messaging/TimestampValidator.kt

/**
 * Validates message timestamps to prevent replay attacks.
 *
 * CRITICAL: Both incoming and outgoing messages must be validated.
 * Server enforces ±10 minute skew, client should too.
 */
object TimestampValidator {

    /**
     * Validates that a timestamp is within acceptable skew.
     *
     * @param timestamp The timestamp to validate (milliseconds)
     * @return true if within ±10 minutes of current time
     */
    fun isValid(timestamp: Long): Boolean {
        val now = System.currentTimeMillis()
        val diff = abs(now - timestamp)
        return diff <= Constants.TIMESTAMP_SKEW_MS
    }

    /**
     * Validates and throws if invalid.
     */
    fun validateOrThrow(timestamp: Long, context: String) {
        if (!isValid(timestamp)) {
            throw TimestampValidationException(
                "Timestamp outside valid window for $context: " +
                "diff=${abs(System.currentTimeMillis() - timestamp)}ms"
            )
        }
    }
}

class TimestampValidationException(message: String) : Exception(message)
```

### 5.3 AuthService
```kotlin
// auth/AuthService.kt

@Singleton
class AuthService @Inject constructor(
    private val wsClient: WsClient,
    private val secureStorage: SecureStorage,
    private val cryptoService: CryptoService,
    private val fcmTokenManager: FcmTokenManager
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var pendingChallenge: RegisterChallengePayload? = null

    init {
        observeWebSocketMessages()
    }

    /**
     * Register a new account with generated mnemonic.
     */
    suspend fun registerNewAccount(mnemonic: String): Result<Unit> {
        return try {
            // Derive keys from mnemonic
            val keys = cryptoService.deriveAllKeys(mnemonic)

            // Store keys securely (now with Keystore wrapping)
            secureStorage.mnemonic = mnemonic
            secureStorage.encPrivateKey = keys.encKeyPair.privateKey
            secureStorage.encPublicKey = keys.encKeyPair.publicKey
            secureStorage.signPrivateKey = keys.signKeyPair.privateKey
            secureStorage.signPublicKey = keys.signKeyPair.publicKey
            secureStorage.contactsKey = keys.contactsKey

            // Connect and begin registration
            wsClient.connect()
            awaitConnection()

            // Get FCM token for push notifications
            val fcmToken = fcmTokenManager.getToken()

            // Send register_begin
            wsClient.send(WsFrame(
                type = Constants.MsgType.REGISTER_BEGIN,
                payload = RegisterBeginPayload(
                    deviceId = secureStorage.deviceId,
                    platform = "android"
                )
            ))

            // Wait for register_ack (handled in observeWebSocketMessages)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Recover account from existing mnemonic.
     */
    suspend fun recoverAccount(mnemonic: String, whisperId: String): Result<Unit> {
        // Similar to registerNewAccount but includes whisperId
    }

    private fun observeWebSocketMessages() {
        wsClient.messages
            .onEach { frame ->
                when (frame.type) {
                    Constants.MsgType.REGISTER_CHALLENGE -> handleChallenge(frame)
                    Constants.MsgType.REGISTER_ACK -> handleAck(frame)
                    Constants.MsgType.ERROR -> handleError(frame)
                }
            }
            .launchIn(scope)
    }

    private suspend fun handleChallenge(frame: WsFrame<JsonElement>) {
        val challenge = gson.fromJson(frame.payload, RegisterChallengePayload::class.java)
        pendingChallenge = challenge

        // Sign challenge: Ed25519_Sign(SHA256(challengeBytes), privateKey)
        val challengeBytes = challenge.challenge.decodeBase64()
        val signature = CanonicalSigning.signChallenge(
            challengeBytes,
            secureStorage.signPrivateKey!!
        )

        // Get FCM token
        val fcmToken = fcmTokenManager.getToken()

        // Send proof
        wsClient.send(WsFrame(
            type = Constants.MsgType.REGISTER_PROOF,
            payload = RegisterProofPayload(
                challengeId = challenge.challengeId,
                deviceId = secureStorage.deviceId,
                encPublicKey = secureStorage.encPublicKey!!.encodeBase64(),
                signPublicKey = secureStorage.signPublicKey!!.encodeBase64(),
                signature = signature.encodeBase64(),
                pushToken = fcmToken  // Include FCM token
            )
        ))
    }

    private suspend fun handleAck(frame: WsFrame<JsonElement>) {
        val ack = gson.fromJson(frame.payload, RegisterAckPayload::class.java)
        if (ack.success) {
            secureStorage.sessionToken = ack.sessionToken
            secureStorage.whisperId = ack.whisperId
            _authState.value = AuthState.Authenticated(ack.whisperId)
        }
    }

    fun logout() {
        secureStorage.clearAll()
        wsClient.disconnect()
        _authState.value = AuthState.Unauthenticated
    }
}

sealed class AuthState {
    object Unauthenticated : AuthState()
    object Authenticating : AuthState()
    data class Authenticated(val whisperId: String) : AuthState()
    data class Error(val message: String) : AuthState()
}
```

### 5.4 MessagingService (With Timestamp Validation)
```kotlin
// messaging/MessagingService.kt

@Singleton
class MessagingService @Inject constructor(
    private val wsClient: WsClient,
    private val secureStorage: SecureStorage,
    private val cryptoService: CryptoService,
    private val contactsService: ContactsService,
    private val messageStore: MessageStore,
    private val outboxQueue: OutboxQueue
) {
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    /**
     * Send encrypted message to recipient.
     */
    suspend fun sendMessage(to: String, content: String, msgType: String = "text"): Result<Message> {
        return try {
            // Get recipient's public key
            val recipientKey = contactsService.getPublicKey(to)
                ?: return Result.failure(Exception("Recipient public key not found"))

            // Generate nonce using SecureRandom
            val nonce = NonceGenerator.generateNonce()  // 24 bytes from SecureRandom

            // Encrypt message
            val ciphertext = cryptoService.boxSeal(
                message = content.toByteArray(Charsets.UTF_8),
                recipientPublicKey = recipientKey,
                senderPrivateKey = secureStorage.encPrivateKey!!
            )

            // Create message
            val messageId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()
            val from = secureStorage.whisperId!!

            // Sign message (canonical format)
            val signature = CanonicalSigning.signMessage(
                messageType = Constants.MsgType.SEND_MESSAGE,
                messageId = messageId,
                from = from,
                toOrGroupId = to,
                timestamp = timestamp,
                nonceB64 = nonce.encodeBase64(),
                ciphertextB64 = ciphertext.encodeBase64(),
                privateKey = secureStorage.signPrivateKey!!
            )

            val payload = SendMessagePayload(
                sessionToken = secureStorage.sessionToken!!,
                messageId = messageId,
                from = from,
                to = to,
                msgType = msgType,
                timestamp = timestamp,
                nonce = nonce.encodeBase64(),
                ciphertext = ciphertext.encodeBase64(),
                sig = signature.encodeBase64()
            )

            // Store in outbox first (survives process death)
            outboxQueue.enqueue(messageId, to, null, msgType, payload)

            // Try to send via WebSocket
            if (wsClient.connectionState.value == WsConnectionState.CONNECTED) {
                wsClient.send(WsFrame(
                    type = Constants.MsgType.SEND_MESSAGE,
                    payload = payload
                ))
            }

            // Store locally as pending
            val message = Message(
                messageId = messageId,
                from = from,
                to = to,
                content = content,
                timestamp = timestamp,
                status = MessageStatus.PENDING,
                direction = MessageDirection.OUTGOING
            )
            messageStore.saveMessage(message)

            Result.success(message)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Handle incoming message with timestamp validation.
     */
    suspend fun handleIncomingMessage(payload: MessageReceivedPayload) {
        // CRITICAL: Validate timestamp first (prevents replay attacks)
        if (!TimestampValidator.isValid(payload.timestamp)) {
            Logger.warn("Rejecting message with invalid timestamp from ${payload.from}")
            return
        }

        // Verify signature
        val isValid = cryptoService.verifyMessageSignature(
            payload,
            contactsService.getSigningKey(payload.from)
        )
        if (!isValid) {
            Logger.warn("Invalid signature from ${payload.from}")
            return
        }

        // Decrypt message
        val senderPublicKey = contactsService.getPublicKey(payload.from)
        val plaintext = cryptoService.boxOpen(
            ciphertext = payload.ciphertext.decodeBase64(),
            nonce = payload.nonce.decodeBase64(),
            senderPublicKey = senderPublicKey!!,
            recipientPrivateKey = secureStorage.encPrivateKey!!
        )

        val content = plaintext.toString(Charsets.UTF_8)

        // Check if sender is a known contact
        val isKnownContact = contactsService.isContact(payload.from)

        if (isKnownContact) {
            // Store and display message
            val message = Message(
                messageId = payload.messageId,
                from = payload.from,
                to = payload.to,
                content = content,
                timestamp = payload.timestamp,
                status = MessageStatus.DELIVERED,
                direction = MessageDirection.INCOMING
            )
            messageStore.saveMessage(message)

            // Send delivery receipt
            sendDeliveryReceipt(payload.messageId, payload.from, "delivered")
        } else {
            // Store as message request
            contactsService.addMessageRequest(payload, content)
        }
    }

    private suspend fun sendDeliveryReceipt(messageId: String, to: String, status: String) {
        val timestamp = System.currentTimeMillis()

        wsClient.send(WsFrame(
            type = Constants.MsgType.DELIVERY_RECEIPT,
            payload = DeliveryReceiptPayload(
                sessionToken = secureStorage.sessionToken!!,
                messageId = messageId,
                from = secureStorage.whisperId!!,
                to = to,
                status = status,
                timestamp = timestamp
            )
        ))
    }
}
```

### 5.5 OutboxQueue (Survives Process Death)
```kotlin
// messaging/OutboxQueue.kt

/**
 * Manages offline message queue that survives app kill and process death.
 * Uses Room database for persistence.
 */
@Singleton
class OutboxQueue @Inject constructor(
    private val outboxDao: OutboxDao,
    private val wsClient: WsClient,
    private val gson: Gson
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Resume sending when connection is restored
        wsClient.connectionState
            .filter { it == WsConnectionState.CONNECTED }
            .onEach { processQueue() }
            .launchIn(scope)
    }

    /**
     * Enqueue a message for sending.
     */
    suspend fun enqueue(
        messageId: String,
        to: String,
        groupId: String?,
        msgType: String,
        payload: SendMessagePayload
    ) {
        val entity = OutboxEntity(
            messageId = messageId,
            to = to,
            groupId = groupId,
            msgType = msgType,
            encryptedPayload = gson.toJson(payload),
            createdAt = System.currentTimeMillis(),
            status = "pending"
        )
        outboxDao.insert(entity)
    }

    /**
     * Process pending messages in order.
     */
    private suspend fun processQueue() {
        val pending = outboxDao.getPending()
        for (item in pending) {
            try {
                outboxDao.updateStatus(item.messageId, "sending")

                val payload = gson.fromJson(item.encryptedPayload, SendMessagePayload::class.java)
                wsClient.send(WsFrame(
                    type = Constants.MsgType.SEND_MESSAGE,
                    payload = payload
                ))

                // Will be removed when message_accepted is received
            } catch (e: Exception) {
                Logger.error("Failed to send queued message ${item.messageId}", e)
                outboxDao.incrementRetry(item.messageId)
            }
        }
    }

    /**
     * Remove message from queue (called when server accepts).
     */
    suspend fun markSent(messageId: String) {
        outboxDao.delete(messageId)
    }

    /**
     * Mark message as failed after max retries.
     */
    suspend fun markFailed(messageId: String) {
        outboxDao.updateStatus(messageId, "failed")
    }
}
```

### 5.6 FCM Token Manager (Lifecycle Aware)
```kotlin
// push/FcmTokenManager.kt

/**
 * Manages FCM token lifecycle:
 * - Initial registration
 * - Token refresh
 * - App reinstall
 * - Permission changes
 */
@Singleton
class FcmTokenManager @Inject constructor(
    private val wsClient: WsClient,
    private val secureStorage: SecureStorage
) {
    private var currentToken: String? = null

    /**
     * Get current FCM token (fetches new one if needed).
     */
    suspend fun getToken(): String? {
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            currentToken = token
            token
        } catch (e: Exception) {
            Logger.error("Failed to get FCM token", e)
            null
        }
    }

    /**
     * Called when FCM token is refreshed (by Firebase).
     */
    suspend fun onTokenRefresh(newToken: String) {
        currentToken = newToken

        // Sync with server if authenticated
        if (secureStorage.sessionToken != null &&
            wsClient.connectionState.value == WsConnectionState.CONNECTED) {
            syncTokenWithServer(newToken)
        }
    }

    /**
     * Sync token with server via WebSocket.
     */
    suspend fun syncTokenWithServer(token: String? = currentToken) {
        token ?: return

        wsClient.send(WsFrame(
            type = Constants.MsgType.UPDATE_TOKENS,
            payload = UpdateTokensPayload(
                protocolVersion = Constants.PROTOCOL_VERSION,
                cryptoVersion = Constants.CRYPTO_VERSION,
                sessionToken = secureStorage.sessionToken!!,
                pushToken = token
            )
        ))
    }

    /**
     * Called on app start to ensure token is synced.
     */
    suspend fun ensureTokenSynced() {
        val token = getToken()
        if (token != null && secureStorage.sessionToken != null) {
            syncTokenWithServer(token)
        }
    }
}

// push/FcmService.kt
class FcmService : FirebaseMessagingService() {

    @Inject
    lateinit var fcmTokenManager: FcmTokenManager

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Token refreshed - sync with server
        CoroutineScope(Dispatchers.IO).launch {
            fcmTokenManager.onTokenRefresh(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Handle push notification
        // Wake app to fetch pending messages
    }
}
```

### 5.7 CallService
```kotlin
// calls/CallService.kt

/**
 * NOTE: First release targets foreground calls only.
 * Background calls on Android 14+ require additional work:
 * - No CallKit equivalent on Android
 * - Foreground service required
 * - Battery optimization exemptions
 */
@Singleton
class CallService @Inject constructor(
    private val wsClient: WsClient,
    private val webRtcService: WebRtcService,
    private val turnService: TurnService,
    private val cryptoService: CryptoService,
    private val secureStorage: SecureStorage,
    private val contactsService: ContactsService
) {
    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private var currentCallId: String? = null
    private var currentPeer: String? = null

    /**
     * Initiate outgoing call.
     */
    suspend fun initiateCall(to: String, isVideo: Boolean): Result<String> {
        return try {
            _callState.value = CallState.Initiating

            // Get TURN credentials
            val turnCredentials = turnService.fetchCredentials()

            // Initialize WebRTC
            webRtcService.initialize(turnCredentials)

            // Create SDP offer
            val offer = webRtcService.createOffer()

            // Encrypt offer
            val recipientKey = contactsService.getPublicKey(to)!!
            val nonce = NonceGenerator.generateNonce()
            val ciphertext = cryptoService.boxSeal(
                message = offer.toByteArray(),
                recipientPublicKey = recipientKey,
                senderPrivateKey = secureStorage.encPrivateKey!!
            )

            // Create call ID
            val callId = UUID.randomUUID().toString()
            currentCallId = callId
            currentPeer = to

            // Sign and send
            val timestamp = System.currentTimeMillis()
            val signature = CanonicalSigning.signMessage(
                messageType = Constants.MsgType.CALL_INITIATE,
                messageId = callId,
                from = secureStorage.whisperId!!,
                toOrGroupId = to,
                timestamp = timestamp,
                nonceB64 = nonce.encodeBase64(),
                ciphertextB64 = ciphertext.encodeBase64(),
                privateKey = secureStorage.signPrivateKey!!
            )

            wsClient.send(WsFrame(
                type = Constants.MsgType.CALL_INITIATE,
                payload = CallInitiatePayload(
                    sessionToken = secureStorage.sessionToken!!,
                    callId = callId,
                    from = secureStorage.whisperId!!,
                    to = to,
                    isVideo = isVideo,
                    timestamp = timestamp,
                    nonce = nonce.encodeBase64(),
                    ciphertext = ciphertext.encodeBase64(),
                    sig = signature.encodeBase64()
                )
            ))

            Result.success(callId)
        } catch (e: Exception) {
            _callState.value = CallState.Idle
            Result.failure(e)
        }
    }

    /**
     * Answer incoming call.
     */
    suspend fun answerCall(callId: String) {
        // Create SDP answer, encrypt, send call_answer
    }

    /**
     * End current call.
     */
    suspend fun endCall(reason: String = "ended") {
        currentCallId?.let { callId ->
            currentPeer?.let { peer ->
                // Send call_end
                // Cleanup WebRTC
                webRtcService.cleanup()
            }
        }
        _callState.value = CallState.Idle
        currentCallId = null
        currentPeer = null
    }

    // Mute, speaker, video controls
    fun toggleMute() = webRtcService.toggleMute()
    fun toggleSpeaker() = webRtcService.toggleSpeaker()
    fun toggleVideo() = webRtcService.toggleVideo()
}

sealed class CallState {
    object Idle : CallState()
    object Initiating : CallState()
    object Ringing : CallState()
    object Connecting : CallState()
    data class Connected(val duration: Long) : CallState()
    data class Ended(val reason: String) : CallState()
}
```

### 5.8 Call Media Behavior (Audio / Video)

> **IMPORTANT**: This section defines application-level media behavior. Protocol signaling is defined above; this clarifies how media tracks are created and managed.

#### WebRTC PeerConnection Setup
- All calls use a single WebRTC PeerConnection per call
- PeerConnectionFactory is initialized once at CallService start

#### Audio Calls
- Create **audio track only**
- **No video track** is added to SDP offer/answer
- Microphone permission required before call initiation
- Audio track is enabled immediately after creation

#### Video Calls
- Create **audio + video tracks**
- Camera is enabled **before** SDP offer creation (ensures video dimensions in SDP)
- Camera and microphone permissions required before call initiation
- Front camera is default; user can switch during call

#### Media Lifecycle
```
┌─────────────────────────────────────────────────────────────┐
│                    OUTGOING CALL                            │
├─────────────────────────────────────────────────────────────┤
│  1. Request permissions (mic, camera if video)              │
│  2. Get TURN credentials                                    │
│  3. Create PeerConnection with TURN servers                 │
│  4. Create audio track (always)                             │
│  5. Create video track (if isVideo=true, enable camera)     │
│  6. Add tracks to PeerConnection                            │
│  7. Create SDP offer                                        │
│  8. Encrypt SDP offer with recipient's public key           │
│  9. Send call_initiate                                      │
│ 10. Wait for call_ringing → call_answer                     │
│ 11. Decrypt SDP answer, setRemoteDescription                │
│ 12. Exchange ICE candidates (trickle ICE)                   │
│ 13. Connection established → start duration timer           │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                    INCOMING CALL                            │
├─────────────────────────────────────────────────────────────┤
│  1. Receive call_incoming                                   │
│  2. Decrypt SDP offer                                       │
│  3. Show incoming call UI (ring)                            │
│  4. User accepts → request permissions                      │
│  5. Get TURN credentials                                    │
│  6. Create PeerConnection with TURN servers                 │
│  7. setRemoteDescription (decrypted offer)                  │
│  8. Create audio track (always)                             │
│  9. Create video track (if offer.isVideo=true)              │
│ 10. Add tracks to PeerConnection                            │
│ 11. Create SDP answer                                       │
│ 12. Encrypt SDP answer with caller's public key             │
│ 13. Send call_answer                                        │
│ 14. Exchange ICE candidates (trickle ICE)                   │
│ 15. Connection established                                  │
└─────────────────────────────────────────────────────────────┘
```

#### Mid-Call Behavior (v1)
- **No upgrade/downgrade**: Audio-only calls cannot add video mid-call (v1 limitation)
- **Mute**: Disables audio track (`audioTrack.setEnabled(false)`)
- **Camera off**: Disables video track (`videoTrack.setEnabled(false)`)
- **Speaker toggle**: Switches between earpiece and speaker via AudioManager
- **Camera flip**: Switches between front/back camera (requires track recreation)

#### ICE Candidate Exchange
- **Trickle ICE** is used (candidates sent as discovered, not bundled)
- Each ICE candidate is:
  1. Signed with sender's signing key (canonical format)
  2. Encrypted with recipient's encryption key
  3. Sent via `call_ice_candidate` WebSocket message
- Candidates received are decrypted and added to PeerConnection

#### SDP Encryption
- All SDP offers and answers are **end-to-end encrypted**
- Encryption: NaCl box (X25519 + XSalsa20-Poly1305)
- Server cannot read call metadata beyond routing info

#### Foreground-Only Calls (v1)
- Calls are supported **only when app is in foreground**
- Incoming calls when app is backgrounded: push notification wakes app
- If user doesn't bring app to foreground within ring timeout → call ends
- **Background calls not supported in v1** (Android 14+ restrictions, no CallKit equivalent)
- Future: Foreground service with ongoing notification for active calls

#### Call End Scenarios
| Scenario | Action |
|----------|--------|
| User hangs up | Send `call_end` with reason="ended" |
| Remote hangup | Receive `call_end`, cleanup |
| Network loss | Detect ICE failure, send `call_end` reason="network_error" |
| Ring timeout (30s) | Send `call_end` reason="no_answer" |
| Declined | Receive `call_end` reason="declined" |
| Busy | Receive `call_end` reason="busy" |

#### WebRtcService Implementation Notes
```kotlin
// Key methods for WebRtcService.kt

fun createAudioTrack(): AudioTrack {
    val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
    return peerConnectionFactory.createAudioTrack("audio0", audioSource)
}

fun createVideoTrack(context: Context): VideoTrack {
    val videoCapturer = createCameraCapturer(context)
    val videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast)
    videoCapturer.initialize(
        SurfaceTextureHelper.create("CaptureThread", eglContext),
        context,
        videoSource.capturerObserver
    )
    videoCapturer.startCapture(1280, 720, 30)  // HD, 30fps
    return peerConnectionFactory.createVideoTrack("video0", videoSource)
}

fun addTracksForCall(isVideo: Boolean) {
    val audioTrack = createAudioTrack()
    peerConnection.addTrack(audioTrack, listOf("stream0"))

    if (isVideo) {
        val videoTrack = createVideoTrack(context)
        peerConnection.addTrack(videoTrack, listOf("stream0"))
    }
}
```

### 5.9 Rich Message Types (Attachments)

> **IMPORTANT**: All attachments are end-to-end encrypted before upload. Server stores only encrypted blobs. Max attachment size: 100MB.

#### Supported Message Types
| Type | msgType Value | Content | Max Size |
|------|---------------|---------|----------|
| Text | `text` | Plain text | 64KB |
| Image | `image` | JPEG, PNG, HEIC, WebP | 100MB |
| Audio | `audio` | Voice note (AAC/M4A) | 100MB |
| File | `file` | Any file type | 100MB |
| Location | `location` | Lat/lng coordinates | N/A |

---

#### 5.9.1 Attachment Encryption Flow

All attachments (images, audio, files) follow this encryption flow:

```
┌─────────────────────────────────────────────────────────────┐
│                    UPLOAD FLOW                              │
├─────────────────────────────────────────────────────────────┤
│  1. Generate random 32-byte attachment key                  │
│  2. Generate random 24-byte nonce                           │
│  3. Encrypt file with NaCl secretbox (XSalsa20-Poly1305)    │
│  4. Request presigned upload URL from server                │
│  5. Upload encrypted blob to DigitalOcean Spaces            │
│  6. Send message with AttachmentPointer                     │
│     - key: attachment key (encrypted in message)            │
│     - blobId: server-assigned blob ID                       │
│     - contentType: MIME type                                │
│     - size: original file size                              │
│     - fileName: original filename (for files)               │
│     - duration: audio duration in seconds (for audio)       │
│     - width/height: dimensions (for images)                 │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                    DOWNLOAD FLOW                            │
├─────────────────────────────────────────────────────────────┤
│  1. Receive message with AttachmentPointer                  │
│  2. Extract attachment key from decrypted message           │
│  3. Request presigned download URL from server              │
│  4. Download encrypted blob                                 │
│  5. Decrypt with NaCl secretbox using attachment key        │
│  6. Cache decrypted file locally                            │
│  7. Display/play based on content type                      │
└─────────────────────────────────────────────────────────────┘
```

#### AttachmentPointer Model
```kotlin
data class AttachmentPointer(
    val blobId: String,           // Server-assigned blob ID
    val key: String,              // Base64 attachment encryption key
    val nonce: String,            // Base64 nonce used for encryption
    val contentType: String,      // MIME type (image/jpeg, audio/aac, etc.)
    val size: Long,               // Original file size in bytes
    val fileName: String? = null, // Original filename (for file type)
    val duration: Int? = null,    // Duration in seconds (for audio)
    val width: Int? = null,       // Width in pixels (for images)
    val height: Int? = null,      // Height in pixels (for images)
    val thumbnail: String? = null // Base64 blurred thumbnail (for images)
)
```

---

#### 5.9.2 Image Messages (Pictures)

##### Sending Images
```kotlin
suspend fun sendImage(
    to: String,
    imageUri: Uri,
    caption: String? = null
): Result<Message> {
    // 1. Load and compress image
    val bitmap = loadBitmap(imageUri)
    val compressed = compressImage(bitmap, quality = 85, maxDimension = 2048)

    // 2. Generate thumbnail (blurred, max 100x100)
    val thumbnail = generateThumbnail(bitmap, size = 100)
    val thumbnailBase64 = Base64.encodeToString(thumbnail, Base64.NO_WRAP)

    // 3. Encrypt image
    val attachmentKey = NonceGenerator.generateKey()  // 32 bytes
    val nonce = NonceGenerator.generateNonce()        // 24 bytes
    val encrypted = NaClSecretBox.seal(compressed, nonce, attachmentKey)

    // 4. Upload encrypted blob
    val presign = api.presignUpload(sessionToken, PresignUploadRequest(
        contentType = "image/jpeg",
        size = encrypted.size
    ))
    uploadToSpaces(presign.uploadUrl, encrypted)

    // 5. Create attachment pointer
    val attachment = AttachmentPointer(
        blobId = presign.blobId,
        key = attachmentKey.encodeBase64(),
        nonce = nonce.encodeBase64(),
        contentType = "image/jpeg",
        size = compressed.size.toLong(),
        width = bitmap.width,
        height = bitmap.height,
        thumbnail = thumbnailBase64
    )

    // 6. Send message with attachment
    return sendMessage(to, caption ?: "", msgType = "image", attachment = attachment)
}
```

##### Receiving Images
```kotlin
suspend fun loadImage(attachment: AttachmentPointer): Bitmap {
    // 1. Check cache first
    val cached = attachmentCache.get(attachment.blobId)
    if (cached != null) return BitmapFactory.decodeByteArray(cached, 0, cached.size)

    // 2. Download encrypted blob
    val presign = api.presignDownload(sessionToken, PresignDownloadRequest(
        blobId = attachment.blobId
    ))
    val encrypted = downloadFromSpaces(presign.downloadUrl)

    // 3. Decrypt
    val decrypted = NaClSecretBox.open(
        encrypted,
        attachment.nonce.decodeBase64(),
        attachment.key.decodeBase64()
    )

    // 4. Cache and decode
    attachmentCache.put(attachment.blobId, decrypted)
    return BitmapFactory.decodeByteArray(decrypted, 0, decrypted.size)
}
```

##### Image UI Component
```kotlin
@Composable
fun ImageMessageBubble(
    message: Message,
    attachment: AttachmentPointer,
    onClick: () -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(attachment.blobId) {
        // Show blurred thumbnail while loading
        loading = true
        bitmap = attachmentService.loadImage(attachment)
        loading = false
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        if (loading && attachment.thumbnail != null) {
            // Show blurred thumbnail placeholder
            val thumbnailBitmap = remember {
                Base64.decode(attachment.thumbnail, Base64.DEFAULT)
                    .let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            Image(
                bitmap = thumbnailBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.blur(8.dp)
            )
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Image",
                contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = 250.dp)
            )
        }

        // Caption overlay if present
        if (message.content.isNotEmpty()) {
            Text(
                text = message.content,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(8.dp),
                color = Color.White
            )
        }
    }
}
```

---

#### 5.9.3 Audio Messages (Voice Notes)

##### Recording Audio
```kotlin
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTime: Long = 0

    fun startRecording(): File {
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        outputFile = file

        mediaRecorder = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        startTime = System.currentTimeMillis()
        return file
    }

    fun stopRecording(): Pair<File, Int> {
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null

        val duration = ((System.currentTimeMillis() - startTime) / 1000).toInt()
        return Pair(outputFile!!, duration)
    }

    fun cancelRecording() {
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null
        outputFile?.delete()
    }

    fun getAmplitude(): Int = mediaRecorder?.maxAmplitude ?: 0
}
```

##### Sending Audio
```kotlin
suspend fun sendAudioMessage(
    to: String,
    audioFile: File,
    durationSeconds: Int
): Result<Message> {
    // 1. Read audio file
    val audioBytes = audioFile.readBytes()

    // 2. Encrypt audio
    val attachmentKey = NonceGenerator.generateKey()
    val nonce = NonceGenerator.generateNonce()
    val encrypted = NaClSecretBox.seal(audioBytes, nonce, attachmentKey)

    // 3. Upload
    val presign = api.presignUpload(sessionToken, PresignUploadRequest(
        contentType = "audio/mp4",
        size = encrypted.size
    ))
    uploadToSpaces(presign.uploadUrl, encrypted)

    // 4. Create attachment pointer with duration
    val attachment = AttachmentPointer(
        blobId = presign.blobId,
        key = attachmentKey.encodeBase64(),
        nonce = nonce.encodeBase64(),
        contentType = "audio/mp4",
        size = audioBytes.size.toLong(),
        duration = durationSeconds
    )

    // 5. Send message
    return sendMessage(to, "", msgType = "audio", attachment = attachment)
}
```

##### Audio Playback
```kotlin
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attachmentService: AttachmentService
) {
    private var mediaPlayer: MediaPlayer? = null
    private var currentBlobId: String? = null

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    suspend fun play(attachment: AttachmentPointer) {
        // Stop current playback if different audio
        if (currentBlobId != attachment.blobId) {
            stop()
        }

        if (mediaPlayer == null) {
            _playbackState.value = PlaybackState.Loading

            // Download and decrypt
            val audioBytes = attachmentService.downloadAttachment(attachment)

            // Write to temp file for MediaPlayer
            val tempFile = File(context.cacheDir, "playback_${attachment.blobId}.m4a")
            tempFile.writeBytes(audioBytes)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    _playbackState.value = PlaybackState.Completed
                    _progress.value = 0f
                }
            }
            currentBlobId = attachment.blobId
        }

        mediaPlayer?.start()
        _playbackState.value = PlaybackState.Playing
        startProgressUpdates()
    }

    fun pause() {
        mediaPlayer?.pause()
        _playbackState.value = PlaybackState.Paused
    }

    fun stop() {
        mediaPlayer?.release()
        mediaPlayer = null
        currentBlobId = null
        _playbackState.value = PlaybackState.Idle
        _progress.value = 0f
    }

    fun seekTo(position: Float) {
        mediaPlayer?.let {
            val ms = (position * it.duration).toInt()
            it.seekTo(ms)
        }
    }

    private fun startProgressUpdates() {
        CoroutineScope(Dispatchers.Main).launch {
            while (playbackState.value == PlaybackState.Playing) {
                mediaPlayer?.let {
                    _progress.value = it.currentPosition.toFloat() / it.duration
                }
                delay(100)
            }
        }
    }
}

sealed class PlaybackState {
    object Idle : PlaybackState()
    object Loading : PlaybackState()
    object Playing : PlaybackState()
    object Paused : PlaybackState()
    object Completed : PlaybackState()
}
```

##### Audio Message UI Component
```kotlin
@Composable
fun AudioMessageBubble(
    message: Message,
    attachment: AttachmentPointer,
    isOutgoing: Boolean,
    audioPlayer: AudioPlayer
) {
    val playbackState by audioPlayer.playbackState.collectAsState()
    val progress by audioPlayer.progress.collectAsState()
    val isThisPlaying = playbackState == PlaybackState.Playing &&
                        audioPlayer.currentBlobId == attachment.blobId

    Row(
        modifier = Modifier
            .background(
                if (isOutgoing) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(16.dp)
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Play/Pause button
        IconButton(
            onClick = {
                if (isThisPlaying) audioPlayer.pause()
                else audioPlayer.play(attachment)
            }
        ) {
            Icon(
                imageVector = if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isThisPlaying) "Pause" else "Play"
            )
        }

        // Waveform / Progress bar
        Column(modifier = Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Duration
            Text(
                text = formatDuration(attachment.duration ?: 0),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

fun formatDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}
```

---

#### 5.9.4 File Messages (Documents)

##### Sending Files
```kotlin
suspend fun sendFile(
    to: String,
    fileUri: Uri,
    fileName: String,
    mimeType: String
): Result<Message> {
    // 1. Read file
    val inputStream = context.contentResolver.openInputStream(fileUri)
    val fileBytes = inputStream?.readBytes() ?: throw Exception("Cannot read file")

    // 2. Check size limit
    if (fileBytes.size > Constants.MAX_ATTACHMENT_SIZE) {
        return Result.failure(Exception("File too large (max 100MB)"))
    }

    // 3. Encrypt file
    val attachmentKey = NonceGenerator.generateKey()
    val nonce = NonceGenerator.generateNonce()
    val encrypted = NaClSecretBox.seal(fileBytes, nonce, attachmentKey)

    // 4. Upload
    val presign = api.presignUpload(sessionToken, PresignUploadRequest(
        contentType = mimeType,
        size = encrypted.size
    ))
    uploadToSpaces(presign.uploadUrl, encrypted)

    // 5. Create attachment pointer with filename
    val attachment = AttachmentPointer(
        blobId = presign.blobId,
        key = attachmentKey.encodeBase64(),
        nonce = nonce.encodeBase64(),
        contentType = mimeType,
        size = fileBytes.size.toLong(),
        fileName = fileName
    )

    // 6. Send message
    return sendMessage(to, "", msgType = "file", attachment = attachment)
}
```

##### Downloading Files
```kotlin
suspend fun downloadFile(attachment: AttachmentPointer): File {
    // 1. Download and decrypt
    val decrypted = attachmentService.downloadAttachment(attachment)

    // 2. Save to Downloads folder
    val fileName = attachment.fileName ?: "file_${attachment.blobId}"
    val file = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        fileName
    )
    file.writeBytes(decrypted)

    // 3. Notify MediaScanner
    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)

    return file
}
```

##### File Message UI Component
```kotlin
@Composable
fun FileMessageBubble(
    message: Message,
    attachment: AttachmentPointer,
    isOutgoing: Boolean,
    onDownload: () -> Unit
) {
    var downloading by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .background(
                if (isOutgoing) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp)
            )
            .clickable { onDownload() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // File icon based on type
        Icon(
            imageVector = getFileIcon(attachment.contentType),
            contentDescription = "File",
            modifier = Modifier.size(40.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.fileName ?: "Unknown file",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatFileSize(attachment.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (downloading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Download"
            )
        }
    }
}

fun getFileIcon(contentType: String): ImageVector {
    return when {
        contentType.startsWith("application/pdf") -> Icons.Default.PictureAsPdf
        contentType.startsWith("application/zip") -> Icons.Default.FolderZip
        contentType.startsWith("text/") -> Icons.Default.Description
        contentType.startsWith("application/vnd.ms-excel") ||
        contentType.contains("spreadsheet") -> Icons.Default.TableChart
        contentType.startsWith("application/msword") ||
        contentType.contains("document") -> Icons.Default.Article
        else -> Icons.Default.InsertDriveFile
    }
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
        else -> "%.1f GB".format(bytes / (1024f * 1024f * 1024f))
    }
}
```

---

#### 5.9.5 Location Messages

> **Note**: Location messages do NOT use the attachment system. Location is embedded in the message content as encrypted JSON.

##### Location Payload
```kotlin
data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,      // Accuracy in meters
    val altitude: Double? = null,     // Altitude in meters
    val placeName: String? = null,    // Optional place name
    val address: String? = null       // Optional address
)
```

##### Sending Location
```kotlin
suspend fun sendLocation(
    to: String,
    latitude: Double,
    longitude: Double,
    placeName: String? = null
): Result<Message> {
    val locationPayload = LocationPayload(
        latitude = latitude,
        longitude = longitude,
        placeName = placeName
    )

    // Content is JSON representation of location
    val content = gson.toJson(locationPayload)

    // Send as location message type (no attachment)
    return sendMessage(to, content, msgType = "location")
}

// Get current location
suspend fun getCurrentLocation(): Location? {
    return suspendCancellableCoroutine { continuation ->
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                continuation.resume(location)
            }
            .addOnFailureListener {
                continuation.resume(null)
            }
    }
}
```

##### Location Message UI Component
```kotlin
@Composable
fun LocationMessageBubble(
    message: Message,
    isOutgoing: Boolean,
    onOpenInMaps: (Double, Double) -> Unit
) {
    val location = remember(message.content) {
        gson.fromJson(message.content, LocationPayload::class.java)
    }

    Column(
        modifier = Modifier
            .background(
                if (isOutgoing) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp)
            )
            .clickable { onOpenInMaps(location.latitude, location.longitude) }
            .padding(4.dp)
    ) {
        // Static map preview
        Box(
            modifier = Modifier
                .size(200.dp, 150.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            // Option 1: Google Static Maps API
            AsyncImage(
                model = buildStaticMapUrl(location.latitude, location.longitude),
                contentDescription = "Map",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // Pin icon overlay
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = Color.Red,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
            )
        }

        // Place name and coordinates
        Column(modifier = Modifier.padding(8.dp)) {
            if (location.placeName != null) {
                Text(
                    text = location.placeName,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = "%.6f, %.6f".format(location.latitude, location.longitude),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun buildStaticMapUrl(lat: Double, lng: Double): String {
    // Use OpenStreetMap static tiles or Google Static Maps
    return "https://maps.googleapis.com/maps/api/staticmap?" +
           "center=$lat,$lng&zoom=15&size=400x300&markers=$lat,$lng" +
           "&key=${BuildConfig.MAPS_API_KEY}"
}

// Open in external maps app
fun openInMaps(context: Context, lat: Double, lng: Double) {
    val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
    val intent = Intent(Intent.ACTION_VIEW, uri)
    context.startActivity(intent)
}
```

---

#### 5.9.6 Attachment Cache

> **IMPORTANT**: Cache stores **DECRYPTED** files (not encrypted blobs). This means:
> - Files are usable immediately without decryption
> - Cache is in app's private storage (not accessible to other apps)
> - Cache is cleared on logout/uninstall
> - Device compromise = cached files exposed (acceptable tradeoff for UX)

```kotlin
/**
 * LRU cache for DECRYPTED attachments.
 *
 * Storage: Decrypted plaintext files (NOT encrypted blobs)
 * Location: App's private cache directory (context.cacheDir)
 * Security: Protected by Android's app sandbox
 *
 * Prevents re-downloading and re-decrypting frequently accessed files.
 */
@Singleton
class AttachmentCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MAX_MEMORY_CACHE_SIZE = 50 * 1024 * 1024  // 50MB in memory
        const val MAX_DISK_CACHE_SIZE = 500 * 1024 * 1024L  // 500MB on disk
    }

    // In-memory LRU cache for thumbnails and small files
    private val memoryCache = object : LruCache<String, ByteArray>(MAX_MEMORY_CACHE_SIZE) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    // Disk cache directory
    private val diskCacheDir = File(context.cacheDir, "attachments")

    init {
        diskCacheDir.mkdirs()
        cleanupOldFiles()
    }

    fun get(blobId: String): ByteArray? {
        // Check memory first
        memoryCache.get(blobId)?.let { return it }

        // Check disk
        val file = File(diskCacheDir, blobId)
        if (file.exists()) {
            val bytes = file.readBytes()
            // Promote to memory cache if small enough
            if (bytes.size < 5 * 1024 * 1024) {
                memoryCache.put(blobId, bytes)
            }
            return bytes
        }

        return null
    }

    fun put(blobId: String, data: ByteArray) {
        // Put in memory cache if small enough
        if (data.size < 5 * 1024 * 1024) {
            memoryCache.put(blobId, data)
        }

        // Always write to disk
        val file = File(diskCacheDir, blobId)
        file.writeBytes(data)
    }

    fun clear() {
        memoryCache.evictAll()
        diskCacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun cleanupOldFiles() {
        // Remove files older than 7 days or if cache exceeds max size
        val maxAge = 7 * 24 * 60 * 60 * 1000L // 7 days
        val now = System.currentTimeMillis()

        var totalSize = 0L
        val files = diskCacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return

        for (file in files.reversed()) {
            totalSize += file.length()
            if (now - file.lastModified() > maxAge || totalSize > MAX_DISK_CACHE_SIZE) {
                file.delete()
            }
        }
    }
}
```

---

#### 5.9.7 Message Input Bar with Attachments

```kotlin
@Composable
fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
    onAttachFile: () -> Unit,
    onAttachLocation: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    isRecording: Boolean,
    recordingDuration: Int
) {
    var showAttachmentMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Attachment button
        IconButton(onClick = { showAttachmentMenu = true }) {
            Icon(Icons.Default.Add, contentDescription = "Attach")
        }

        if (isRecording) {
            // Recording UI
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.Red
                )
                Text(
                    text = formatDuration(recordingDuration),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Text("Slide to cancel ←", color = Color.Gray)
            }

            IconButton(onClick = onStopRecording) {
                Icon(Icons.Default.Send, contentDescription = "Send voice note")
            }
        } else {
            // Text input
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent
                )
            )

            if (value.isEmpty()) {
                // Voice note button (when no text)
                IconButton(
                    onClick = onStartRecording,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { onStartRecording() }
                        )
                    }
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Voice note")
                }
            } else {
                // Send button (when has text)
                IconButton(onClick = onSend) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }

    // Attachment menu dropdown
    DropdownMenu(
        expanded = showAttachmentMenu,
        onDismissRequest = { showAttachmentMenu = false }
    ) {
        DropdownMenuItem(
            text = { Text("Photo") },
            leadingIcon = { Icon(Icons.Default.Image, null) },
            onClick = { showAttachmentMenu = false; onAttachImage() }
        )
        DropdownMenuItem(
            text = { Text("File") },
            leadingIcon = { Icon(Icons.Default.InsertDriveFile, null) },
            onClick = { showAttachmentMenu = false; onAttachFile() }
        )
        DropdownMenuItem(
            text = { Text("Location") },
            leadingIcon = { Icon(Icons.Default.LocationOn, null) },
            onClick = { showAttachmentMenu = false; onAttachLocation() }
        )
    }
}
```

### 5.10 Deliverables
- [ ] AuthService (registration, recovery, session refresh)
- [ ] **TimestampValidator (±10 min skew check)**
- [ ] MessagingService (send, receive, delivery receipts)
- [ ] **OutboxQueue with Room persistence (survives process death)**
- [ ] ContactsService (CRUD, blocking, key lookup)
- [ ] ContactsBackupService (encrypted backup/restore)
- [ ] CallService (WebRTC signaling, foreground only for v1)
- [ ] WebRtcService (peer connection management)
- [ ] **Call media tracks: audio-only vs audio+video creation**
- [ ] **Trickle ICE candidate exchange with E2E encryption**
- [ ] **SDP offer/answer E2E encryption**
- [ ] **Call end handling (all scenarios)**
- [ ] GroupService (create, update, messaging)
- [ ] AttachmentService (encrypted upload/download)
- [ ] **Image messages (send, receive, thumbnail, full view)**
- [ ] **Audio messages (record, send, receive, playback)**
- [ ] **File messages (send, receive, download)**
- [ ] **Location messages (send, receive, map preview)**
- [ ] **AttachmentCache (memory + disk LRU cache)**
- [ ] **AudioRecorder (voice note recording)**
- [ ] **AudioPlayer (voice note playback with progress)**
- [ ] **FcmTokenManager (token lifecycle management)**
- [ ] FcmService (push token management)
- [ ] NotificationService (notification display)

---

## Phase 6: UI Layer (Jetpack Compose)

### 6.1 Files
```
ui/
├── navigation/
│   └── WhisperNavigation.kt      # NavHost & routes
├── theme/
│   ├── WhisperTheme.kt
│   ├── Colors.kt
│   └── Type.kt
├── screens/
│   ├── auth/
│   │   ├── WelcomeScreen.kt
│   │   ├── CreateAccountScreen.kt
│   │   ├── SeedPhraseScreen.kt
│   │   └── RecoverAccountScreen.kt
│   ├── conversations/
│   │   └── ConversationsScreen.kt
│   ├── chat/
│   │   └── ChatScreen.kt
│   ├── contacts/
│   │   ├── ContactsScreen.kt
│   │   ├── ContactProfileScreen.kt
│   │   └── AddContactScreen.kt
│   ├── calls/
│   │   ├── CallsHistoryScreen.kt
│   │   └── CallScreen.kt
│   ├── groups/
│   │   ├── GroupsScreen.kt
│   │   └── GroupChatScreen.kt
│   └── settings/
│       ├── SettingsScreen.kt
│       └── ProfileScreen.kt
├── components/
│   ├── MessageBubble.kt
│   ├── ImageMessageBubble.kt
│   ├── AudioMessageBubble.kt
│   ├── FileMessageBubble.kt
│   ├── LocationMessageBubble.kt
│   ├── MessageInputBar.kt
│   ├── ChatRow.kt
│   ├── ContactRow.kt
│   ├── CallRow.kt
│   └── ConnectionIndicator.kt
└── viewmodels/
    ├── AuthViewModel.kt
    ├── ConversationsViewModel.kt
    ├── ChatViewModel.kt
    ├── ContactsViewModel.kt
    ├── CallsViewModel.kt
    └── SettingsViewModel.kt
```

### 6.2 Navigation
```kotlin
// navigation/WhisperNavigation.kt

sealed class Screen(val route: String) {
    // Auth
    object Welcome : Screen("welcome")
    object CreateAccount : Screen("create_account")
    object SeedPhrase : Screen("seed_phrase/{mnemonic}")
    object RecoverAccount : Screen("recover_account")

    // Main
    object Conversations : Screen("conversations")
    object Chat : Screen("chat/{conversationId}")
    object Contacts : Screen("contacts")
    object ContactProfile : Screen("contact/{whisperId}")
    object AddContact : Screen("add_contact")
    object Calls : Screen("calls")
    object Call : Screen("call/{callId}")
    object Settings : Screen("settings")
    object Profile : Screen("profile")
}

@Composable
fun WhisperNavigation(
    authState: AuthState,
    navController: NavHostController = rememberNavController()
) {
    val startDestination = when (authState) {
        is AuthState.Authenticated -> Screen.Conversations.route
        else -> Screen.Welcome.route
    }

    NavHost(navController = navController, startDestination = startDestination) {
        // Auth flow
        composable(Screen.Welcome.route) { WelcomeScreen(navController) }
        composable(Screen.CreateAccount.route) { CreateAccountScreen(navController) }
        composable(Screen.SeedPhrase.route) { SeedPhraseScreen(navController) }
        composable(Screen.RecoverAccount.route) { RecoverAccountScreen(navController) }

        // Main app flow
        composable(Screen.Conversations.route) { ConversationsScreen(navController) }
        composable(Screen.Chat.route) { ChatScreen(navController) }
        composable(Screen.Contacts.route) { ContactsScreen(navController) }
        composable(Screen.ContactProfile.route) { ContactProfileScreen(navController) }
        composable(Screen.AddContact.route) { AddContactScreen(navController) }
        composable(Screen.Calls.route) { CallsHistoryScreen(navController) }
        composable(Screen.Call.route) { CallScreen(navController) }
        composable(Screen.Settings.route) { SettingsScreen(navController) }
        composable(Screen.Profile.route) { ProfileScreen(navController) }
    }
}
```

### 6.3 Key Screens

#### Welcome Screen
```kotlin
@Composable
fun WelcomeScreen(navController: NavController) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Whisper2", style = MaterialTheme.typography.headlineLarge)
        Text("End-to-end encrypted messaging", style = MaterialTheme.typography.bodyMedium)

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = { navController.navigate(Screen.CreateAccount.route) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Create New Account")
        }

        OutlinedButton(
            onClick = { navController.navigate(Screen.RecoverAccount.route) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Recover Existing Account")
        }
    }
}
```

#### Chat Screen
```kotlin
@Composable
fun ChatScreen(
    navController: NavController,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.contactName) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.initiateCall(false) }) {
                        Icon(Icons.Default.Call, contentDescription = "Voice Call")
                    }
                    IconButton(onClick = { viewModel.initiateCall(true) }) {
                        Icon(Icons.Default.VideoCall, contentDescription = "Video Call")
                    }
                }
            )
        },
        bottomBar = {
            MessageInputBar(
                value = uiState.inputText,
                onValueChange = viewModel::onInputChange,
                onSend = viewModel::sendMessage,
                onAttachment = viewModel::selectAttachment
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            reverseLayout = true
        ) {
            items(messages, key = { it.messageId }) { message ->
                MessageBubble(
                    message = message,
                    isOutgoing = message.direction == MessageDirection.OUTGOING
                )
            }
        }
    }
}
```

### 6.4 Deliverables
- [ ] Material 3 theme with dark mode
- [ ] Navigation graph with all routes
- [ ] Auth screens (Welcome, Create, SeedPhrase, Recover)
- [ ] Conversations list screen
- [ ] Chat screen with message bubbles
- [ ] **ImageMessageBubble (thumbnail, full view, caption)**
- [ ] **AudioMessageBubble (play/pause, progress, duration)**
- [ ] **FileMessageBubble (icon, name, size, download)**
- [ ] **LocationMessageBubble (map preview, open in maps)**
- [ ] **MessageInputBar with attachment menu**
- [ ] **Voice recording UI (hold to record, slide to cancel)**
- [ ] **Image viewer (pinch zoom, swipe dismiss)**
- [ ] Contacts list screen
- [ ] Contact profile screen
- [ ] Add contact screen (QR + WhisperID)
- [ ] Call history screen
- [ ] Active call screen (controls, timer)
- [ ] Settings screen
- [ ] Profile screen
- [ ] Reusable components (MessageBubble, InputBar, etc.)

---

## Phase 7: Testing & Quality Assurance

### 7.1 Test Categories

#### Unit Tests
```kotlin
// Crypto test vectors (MUST PASS)
class CryptoVectorsTest {
    @Test fun `key derivation matches server`()
    @Test fun `challenge signing produces valid signature`()
    @Test fun `message signing canonical format`()
    @Test fun `encryption round trip`()
    @Test fun `nonce is 24 bytes from SecureRandom`()
}

// Timestamp validation
class TimestampValidatorTest {
    @Test fun `accepts timestamp within window`()
    @Test fun `rejects timestamp outside window`()
    @Test fun `handles edge cases`()
}

// Service tests
class AuthServiceTest {
    @Test fun `registration flow completes successfully`()
    @Test fun `recovery with existing mnemonic`()
    @Test fun `session refresh before expiry`()
}

class MessagingServiceTest {
    @Test fun `send message encrypts correctly`()
    @Test fun `receive message decrypts correctly`()
    @Test fun `invalid signature rejected`()
    @Test fun `invalid timestamp rejected`()
}

class OutboxQueueTest {
    @Test fun `survives process death`()
    @Test fun `resumes on reconnect`()
    @Test fun `maintains message order`()
}
```

#### Integration Tests
```kotlin
class ServerIntegrationTest {
    @Test fun `full registration flow against server`()
    @Test fun `send and receive message E2E`()
    @Test fun `contacts backup and restore`()
    @Test fun `call signaling flow`()
}
```

### 7.2 Deliverables
- [ ] Crypto test vectors (100% pass required)
- [ ] Unit tests for all services
- [ ] **Timestamp validation tests**
- [ ] **Outbox queue persistence tests**
- [ ] Integration tests against production server
- [ ] UI tests with Compose testing
- [ ] Performance benchmarks
- [ ] Security audit checklist

---

## Phase Summary & Timeline

| Phase | Description | Dependencies | Key Deliverables |
|-------|-------------|--------------|------------------|
| **0** | Principles | None | No mock data policy |
| **1** | Project Setup | None | Gradle, Hilt, Constants |
| **2** | Crypto Layer | Phase 1 | BIP39, HKDF, NaCl, Signatures, **SecureRandom nonces** |
| **3** | Networking | Phase 1, 2 | WebSocket, HTTP, Models, **TypeToken parsing** |
| **4** | Persistence | Phase 1 | Room, **Keystore-wrapped keys**, **Outbox persistence** |
| **5** | Services | Phase 2, 3, 4 | Auth, Messaging, Calls, **Timestamp validation**, **FCM lifecycle** |
| **6** | UI Layer | Phase 5 | All Compose screens |
| **7** | Testing | All phases | Test vectors, Integration |

---

## Critical Success Criteria

1. **Crypto Compatibility**: Test vectors MUST match iOS/server exactly
2. **Protocol Compliance**: All 40+ message types implemented correctly
3. **Signature Format**: Canonical signing with SHA256 pre-hash
4. **Timestamp Validation**: ±10 minute skew check on ALL messages (incoming & outgoing)
5. **Session Management**: Token refresh before 7-day expiry
6. **Offline Support**: Outbox queue survives process death
7. **Push Notifications**: FCM token synced on registration, refresh, and reconnect
8. **Key Security**: Private keys wrapped with Keystore AES key

---

## Critical Fixes Summary

| Issue | Risk | Solution |
|-------|------|----------|
| Raw private key storage | Key corruption on app update | Keystore AES-GCM wrapping |
| Generic JSON parsing | ClassCastException | TypeToken for WsFrame parsing |
| Nonce generation | Weak randomness | SecureRandom (24 bytes) |
| Timestamp validation | Replay attacks | ±10 min check on all messages |
| FCM token lifecycle | Push failures | Sync on refresh/reinstall |
| WebSocket reconnect | Infinite loops | Auth expired / network state handling |
| Outbox queue | Message loss on kill | Room database persistence |

---

## Known Limitations (v1)

| Feature | Status | Notes |
|---------|--------|-------|
| Background calls | Foreground only | Android 14+ restrictions, no CallKit equivalent |
| Biometric unlock | Not included | Can add post-launch |
| Multi-device | Not supported | Single active device (server enforced) |

---

## Server API Reference

### WebSocket Endpoint
- URL: `wss://whisper2.aiakademiturkiye.com/ws`
- Max frame size: 512KB

### HTTP Endpoints
- Base URL: `https://whisper2.aiakademiturkiye.com`
- `GET /users/{whisperId}/keys` - Get user's public keys
- `PUT /backup/contacts` - Upload encrypted contacts backup
- `GET /backup/contacts` - Download contacts backup
- `DELETE /backup/contacts` - Delete backup
- `POST /attachments/presign/upload` - Get presigned upload URL
- `POST /attachments/presign/download` - Get presigned download URL
- `GET /health` - Health check

### Authentication Header
```
Authorization: Bearer <sessionToken>
```

---

## iOS Feature Parity Checklist

| Feature | iOS | Android |
|---------|-----|---------|
| BIP39 Mnemonic | ✅ | [ ] |
| Key Derivation (HKDF) | ✅ | [ ] |
| X25519 Encryption | ✅ | [ ] |
| Ed25519 Signatures | ✅ | [ ] |
| WebSocket Connection | ✅ | [ ] |
| User Registration | ✅ | [ ] |
| Account Recovery | ✅ | [ ] |
| Send/Receive Messages | ✅ | [ ] |
| Delivery Receipts | ✅ | [ ] |
| Typing Indicators | ✅ | [ ] |
| Contact Management | ✅ | [ ] |
| Contact Backup | ✅ | [ ] |
| Message Requests | ✅ | [ ] |
| Block/Unblock | ✅ | [ ] |
| Voice Calls | ✅ | [ ] |
| Video Calls | ✅ | [ ] |
| Group Creation | ✅ | [ ] |
| Group Messaging | ✅ | [ ] |
| Attachments | ✅ | [ ] |
| Push Notifications | ✅ | [ ] |
| Offline Queue | ✅ | [ ] |
| QR Code Scanning | ✅ | [ ] |

---

## Notes

- All crypto constants are FROZEN and must not be changed
- WhisperID format: `WSP-XXXX-XXXX-XXXX` (server-assigned)
- Session tokens expire in 7 days
- Messages stored on server for 72 hours (offline queue)
- Max attachment size: 100MB
- Max backup size: 256KB
