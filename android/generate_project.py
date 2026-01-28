#!/usr/bin/env python3
"""
Whisper2 Android Project Generator
Generates all project files based on android-plan-final.md specification.
"""

import os
from pathlib import Path

# Base path for Android project
BASE_PATH = Path(r"C:/Users/fatih/Desktop/whisper 2/android")
APP_PATH = BASE_PATH / "app"
SRC_MAIN = APP_PATH / "src/main"
SRC_TEST = APP_PATH / "src/test"
JAVA_PATH = SRC_MAIN / "java/com/whisper2/app"
RES_PATH = SRC_MAIN / "res"

# Package directories to create
PACKAGES = [
    "core",
    "crypto",
    "di",
    "domain/model",
    "data/local/db",
    "data/local/db/entities",
    "data/local/db/dao",
    "data/local/prefs",
    "data/local/keystore",
    "data/network/ws",
    "data/network/api",
    "services/auth",
    "services/messaging",
    "services/contacts",
    "services/calls",
    "services/groups",
    "services/attachments",
    "services/push",
    "ui",
    "ui/navigation",
    "ui/theme",
    "ui/screens/auth",
    "ui/screens/conversations",
    "ui/screens/chat",
    "ui/screens/contacts",
    "ui/screens/calls",
    "ui/screens/groups",
    "ui/screens/settings",
    "ui/components",
    "ui/viewmodels",
]

def create_directories():
    """Create all necessary directories."""
    dirs = [
        BASE_PATH,
        APP_PATH,
        SRC_MAIN,
        SRC_TEST / "java/com/whisper2/app",
        RES_PATH / "values",
        RES_PATH / "xml",
        RES_PATH / "mipmap-hdpi",
        RES_PATH / "mipmap-mdpi",
        RES_PATH / "mipmap-xhdpi",
        RES_PATH / "mipmap-xxhdpi",
        RES_PATH / "mipmap-xxxhdpi",
        APP_PATH / "schemas",
    ]

    for pkg in PACKAGES:
        dirs.append(JAVA_PATH / pkg)

    for d in dirs:
        d.mkdir(parents=True, exist_ok=True)
        print(f"Created: {d}")

def write_file(path: Path, content: str):
    """Write content to file."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8')
    print(f"Written: {path}")

# ============================================================================
# GRADLE FILES
# ============================================================================

SETTINGS_GRADLE = '''pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Whisper2"
include(":app")
'''

ROOT_BUILD_GRADLE = '''// Root build.gradle.kts
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.google.dagger.hilt.android") version "2.54" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
'''

APP_BUILD_GRADLE = '''plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.whisper2.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.whisper2.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.54")
    ksp("com.google.dagger:hilt-compiler:2.54")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Crypto - Lazysodium
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.15.0@aar")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore & Security
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // WebRTC
    implementation("io.getstream:stream-webrtc-android:1.3.0")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Image Loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // QR Code
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.google.truth:truth:1.4.4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.01.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
'''

PROGUARD_RULES = '''# Whisper2 ProGuard Rules

# Lazysodium + JNA (CRITICAL for crypto)
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class com.goterl.lazysodium.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Retrofit + OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keepattributes Signature, Exceptions, *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * { @retrofit2.http.* <methods>; }

# Gson
-keep class com.google.gson.** { *; }
-keep class com.whisper2.app.data.network.ws.** { *; }
-keep class com.whisper2.app.data.network.api.** { *; }
-keep class com.whisper2.app.domain.model.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }

# WebRTC
-keep class org.webrtc.** { *; }

# Kotlin
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { public <methods>; }
'''

# ============================================================================
# ANDROID MANIFEST
# ============================================================================

ANDROID_MANIFEST = '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

    <uses-feature android:name="android.hardware.camera" android:required="false" />
    <uses-feature android:name="android.hardware.microphone" android:required="true" />

    <application
        android:name=".App"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Whisper2"
        android:networkSecurityConfig="@xml/network_security_config"
        tools:targetApi="35">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:theme="@style/Theme.Whisper2"
            android:windowSoftInputMode="adjustResize"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".services.push.FcmService"
            android:exported="false">
            <intent-filter>
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>

        <service
            android:name=".services.calls.CallForegroundService"
            android:exported="false"
            android:foregroundServiceType="phoneCall|microphone|camera" />

        <meta-data
            android:name="com.google.firebase.messaging.default_notification_channel_id"
            android:value="whisper2_messages" />

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

    </application>
</manifest>
'''

# ============================================================================
# RESOURCE FILES
# ============================================================================

STRINGS_XML = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Whisper2</string>
    <string name="notification_channel_messages">Messages</string>
    <string name="notification_channel_calls">Calls</string>
</resources>
'''

THEMES_XML = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Whisper2" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">true</item>
    </style>
</resources>
'''

DATA_EXTRACTION_RULES = '''<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="." />
        <exclude domain="database" path="." />
        <exclude domain="file" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="." />
        <exclude domain="database" path="." />
    </device-transfer>
</data-extraction-rules>
'''

NETWORK_SECURITY_CONFIG = '''<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">whisper2.aiakademiturkiye.com</domain>
    </domain-config>
</network-security-config>
'''

FILE_PATHS = '''<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="attachments" path="attachments/" />
    <files-path name="files" path="." />
    <external-files-path name="external_files" path="." />
</paths>
'''

# ============================================================================
# KOTLIN SOURCE FILES - CORE
# ============================================================================

CONSTANTS_KT = '''package com.whisper2.app.core

/**
 * FROZEN CONSTANTS - DO NOT MODIFY
 * Must match server and iOS client exactly.
 */
object Constants {

    // Server
    const val WS_URL = "wss://whisper2.aiakademiturkiye.com/ws"
    const val BASE_URL = "https://whisper2.aiakademiturkiye.com"

    // Protocol
    const val PROTOCOL_VERSION = 1
    const val CRYPTO_VERSION = 1

    // Crypto (FROZEN)
    const val BIP39_SEED_LENGTH = 64
    const val BIP39_ITERATIONS = 2048
    const val BIP39_SALT = "mnemonic"
    const val HKDF_SALT = "whisper"
    const val ENCRYPTION_DOMAIN = "whisper/enc"
    const val SIGNING_DOMAIN = "whisper/sign"
    const val CONTACTS_DOMAIN = "whisper/contacts"

    // NaCl sizes
    const val NACL_NONCE_SIZE = 24
    const val NACL_KEY_SIZE = 32
    const val NACL_PUBLIC_KEY_SIZE = 32
    const val NACL_SECRET_KEY_SIZE = 32
    const val NACL_SIGN_SECRET_KEY_SIZE = 64
    const val NACL_SIGNATURE_SIZE = 64
    const val NACL_BOX_MAC_SIZE = 16

    // Limits
    const val TIMESTAMP_SKEW_MS = 10 * 60 * 1000L
    const val SESSION_TTL_DAYS = 7
    const val MAX_GROUP_MEMBERS = 50
    const val MAX_GROUP_TITLE = 64
    const val MAX_ATTACHMENT_SIZE = 100 * 1024 * 1024L
    const val MAX_BACKUP_SIZE = 256 * 1024
    const val MAX_MESSAGE_SIZE = 64 * 1024

    // Timing
    const val HEARTBEAT_INTERVAL_MS = 30_000L
    const val RECONNECT_BASE_DELAY_MS = 1000L
    const val RECONNECT_MAX_DELAY_MS = 30_000L
    const val RECONNECT_MAX_ATTEMPTS = 5
    const val CALL_RING_TIMEOUT_MS = 30_000L

    // Message Types
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
        const val GROUP_CREATED = "group_created"
        const val GROUP_UPDATE = "group_update"
        const val GROUP_EVENT = "group_event"
        const val GROUP_SEND_MESSAGE = "group_send_message"
        const val GROUP_MESSAGE_RECEIVED = "group_message_received"
        const val UPDATE_TOKENS = "update_tokens"
        const val TOKENS_UPDATED = "tokens_updated"
        const val SESSION_REFRESH = "session_refresh"
        const val SESSION_REFRESH_ACK = "session_refresh_ack"
        const val PING = "ping"
        const val PONG = "pong"
        const val ERROR = "error"
    }

    object ContentType {
        const val TEXT = "text"
        const val IMAGE = "image"
        const val AUDIO = "audio"
        const val FILE = "file"
        const val LOCATION = "location"
    }

    object ErrorCode {
        const val NOT_REGISTERED = "NOT_REGISTERED"
        const val AUTH_FAILED = "AUTH_FAILED"
        const val INVALID_PAYLOAD = "INVALID_PAYLOAD"
        const val INVALID_TIMESTAMP = "INVALID_TIMESTAMP"
        const val INVALID_SIGNATURE = "INVALID_SIGNATURE"
        const val RATE_LIMITED = "RATE_LIMITED"
        const val USER_NOT_FOUND = "USER_NOT_FOUND"
        const val SESSION_EXPIRED = "SESSION_EXPIRED"
        const val BLOCKED = "BLOCKED"
    }

    object CallEndReason {
        const val ENDED = "ended"
        const val DECLINED = "declined"
        const val BUSY = "busy"
        const val NO_ANSWER = "no_answer"
        const val NETWORK_ERROR = "network_error"
    }

    object MessageStatus {
        const val PENDING = "pending"
        const val SENDING = "sending"
        const val SENT = "sent"
        const val DELIVERED = "delivered"
        const val READ = "read"
        const val FAILED = "failed"
    }

    object Direction {
        const val INCOMING = "incoming"
        const val OUTGOING = "outgoing"
    }

    // Storage
    const val DATABASE_NAME = "whisper2_db"
    const val SECURE_PREFS_NAME = "whisper2_secure_prefs"
    const val KEYSTORE_ALIAS = "whisper2_wrapper_key"
    const val ATTACHMENT_CACHE_DIR = "attachments"
    const val MAX_MEMORY_CACHE_SIZE = 50 * 1024 * 1024
    const val MAX_DISK_CACHE_SIZE = 500 * 1024 * 1024L

    const val PLATFORM = "android"
}
'''

ERRORS_KT = '''package com.whisper2.app.core

sealed class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
class InvalidMnemonicException(message: String = "Invalid mnemonic phrase") : CryptoException(message)
class KeyDerivationException(message: String, cause: Throwable? = null) : CryptoException(message, cause)
class EncryptionException(message: String, cause: Throwable? = null) : CryptoException(message, cause)
class DecryptionException(message: String = "Decryption failed") : CryptoException(message)
class SignatureVerificationException(message: String = "Invalid signature") : CryptoException(message)

sealed class ProtocolException(message: String, val errorCode: String? = null) : Exception(message)
class TimestampValidationException(message: String = "Timestamp outside valid window") : ProtocolException(message, Constants.ErrorCode.INVALID_TIMESTAMP)
class AuthenticationException(message: String = "Authentication failed", errorCode: String = Constants.ErrorCode.AUTH_FAILED) : ProtocolException(message, errorCode)
class SessionExpiredException(message: String = "Session expired") : ProtocolException(message, Constants.ErrorCode.SESSION_EXPIRED)
class UserNotFoundException(whisperId: String) : ProtocolException("User not found: $whisperId", Constants.ErrorCode.USER_NOT_FOUND)
class UserBlockedException(whisperId: String) : ProtocolException("User is blocked: $whisperId", Constants.ErrorCode.BLOCKED)
class RateLimitedException(message: String = "Rate limited") : ProtocolException(message, Constants.ErrorCode.RATE_LIMITED)
class InvalidPayloadException(message: String = "Invalid payload") : ProtocolException(message, Constants.ErrorCode.INVALID_PAYLOAD)

sealed class NetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)
class WebSocketConnectionException(message: String = "WebSocket connection failed", cause: Throwable? = null) : NetworkException(message, cause)
class WebSocketDisconnectedException(message: String = "WebSocket disconnected") : NetworkException(message)
class HttpException(val statusCode: Int, message: String) : NetworkException("HTTP $statusCode: $message")

sealed class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)
class SecureStorageException(message: String, cause: Throwable? = null) : StorageException(message, cause)
class DatabaseException(message: String, cause: Throwable? = null) : StorageException(message, cause)

sealed class AttachmentException(message: String, cause: Throwable? = null) : Exception(message, cause)
class AttachmentTooLargeException(size: Long, maxSize: Long = Constants.MAX_ATTACHMENT_SIZE) : AttachmentException("Attachment size ($size) exceeds max ($maxSize)")
class AttachmentUploadException(message: String, cause: Throwable? = null) : AttachmentException(message, cause)
class AttachmentDownloadException(message: String, cause: Throwable? = null) : AttachmentException(message, cause)

sealed class CallException(message: String) : Exception(message)
class CallInitiationException(message: String = "Failed to initiate call") : CallException(message)
class CallRejectedException(reason: String) : CallException("Call rejected: $reason")
class WebRtcException(message: String) : CallException(message)
'''

LOGGER_KT = '''package com.whisper2.app.core

import android.util.Log
import com.whisper2.app.BuildConfig

object Logger {
    private const val TAG = "Whisper2"
    private val isDebug = BuildConfig.DEBUG

    fun debug(message: String, tag: String = TAG) {
        if (isDebug) Log.d(tag, message)
    }

    fun info(message: String, tag: String = TAG) = Log.i(tag, message)
    fun warn(message: String, tag: String = TAG) = Log.w(tag, message)

    fun error(message: String, throwable: Throwable? = null, tag: String = TAG) {
        if (throwable != null) Log.e(tag, message, throwable)
        else Log.e(tag, message)
    }

    fun ws(message: String) = debug("[WS] $message", "Whisper2-WS")
    fun crypto(message: String) { if (isDebug) debug("[CRYPTO] $message", "Whisper2-Crypto") }
    fun auth(message: String) = debug("[AUTH] $message", "Whisper2-Auth")
    fun call(message: String) = debug("[CALL] $message", "Whisper2-Call")

    fun redact(value: String): String {
        if (value.length <= 8) return "[REDACTED]"
        return if (isDebug) "${value.take(4)}...${value.takeLast(4)}" else "[REDACTED]"
    }
}
'''

EXTENSIONS_KT = '''package com.whisper2.app.core

import android.util.Base64
import java.text.Normalizer

fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.normalizeNFKD(): String = Normalizer.normalize(this, Normalizer.Form.NFKD)
fun String.normalizeMnemonic(): String = this.normalizeNFKD().trim().replace(Regex("\\\\s+"), " ").lowercase()

fun ByteArray.secureEquals(other: ByteArray): Boolean {
    if (this.size != other.size) return false
    var result = 0
    for (i in indices) result = result or (this[i].toInt() xor other[i].toInt())
    return result == 0
}

fun ByteArray.wipe() = fill(0)
fun currentTimeMillis(): Long = System.currentTimeMillis()

fun Long.isValidTimestamp(): Boolean {
    val diff = kotlin.math.abs(currentTimeMillis() - this)
    return diff <= Constants.TIMESTAMP_SKEW_MS
}

fun Int.formatDuration(): String {
    val minutes = this / 60
    val seconds = this % 60
    return "%d:%02d".format(minutes, seconds)
}

fun Long.formatFileSize(): String = when {
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> "%.1f KB".format(this / 1024f)
    this < 1024 * 1024 * 1024 -> "%.1f MB".format(this / (1024f * 1024f))
    else -> "%.1f GB".format(this / (1024f * 1024f * 1024f))
}
'''

# ============================================================================
# APPLICATION CLASS
# ============================================================================

APP_KT = '''package com.whisper2.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.whisper2.app.core.Logger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Logger.info("Whisper2 Application starting")
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val messagesChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                enableLights(true)
            }

            val callsChannel = NotificationChannel(
                CHANNEL_CALLS,
                getString(R.string.notification_channel_calls),
                NotificationManager.IMPORTANCE_MAX
            ).apply {
                enableVibration(true)
                setSound(null, null)
            }

            notificationManager.createNotificationChannels(listOf(messagesChannel, callsChannel))
        }
    }

    companion object {
        const val CHANNEL_MESSAGES = "whisper2_messages"
        const val CHANNEL_CALLS = "whisper2_calls"
    }
}
'''

# ============================================================================
# DI MODULES
# ============================================================================

APP_MODULE_KT = '''package com.whisper2.app.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class MainDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DefaultDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideGson(): Gson = GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").create()

    @Provides @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides @Singleton @ApplicationScope
    fun provideApplicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)
}
'''

NETWORK_MODULE_KT = '''package com.whisper2.app.di

import com.google.gson.Gson
import com.whisper2.app.BuildConfig
import com.whisper2.app.core.Constants
import com.whisper2.app.data.network.api.AttachmentsApi
import com.whisper2.app.data.network.api.WhisperApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class WebSocketClient
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class HttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton @WebSocketClient
    fun provideWebSocketOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides @Singleton @HttpClient
    fun provideHttpOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            })
        }
        return builder.build()
    }

    @Provides @Singleton
    fun provideRetrofit(@HttpClient okHttpClient: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(Constants.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides @Singleton
    fun provideWhisperApi(retrofit: Retrofit): WhisperApi = retrofit.create(WhisperApi::class.java)

    @Provides @Singleton
    fun provideAttachmentsApi(retrofit: Retrofit): AttachmentsApi = retrofit.create(AttachmentsApi::class.java)
}
'''

DATABASE_MODULE_KT = '''package com.whisper2.app.di

import android.content.Context
import androidx.room.Room
import com.whisper2.app.core.Constants
import com.whisper2.app.data.local.db.WhisperDatabase
import com.whisper2.app.data.local.db.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WhisperDatabase =
        Room.databaseBuilder(context, WhisperDatabase::class.java, Constants.DATABASE_NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideMessageDao(db: WhisperDatabase): MessageDao = db.messageDao()
    @Provides fun provideConversationDao(db: WhisperDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideContactDao(db: WhisperDatabase): ContactDao = db.contactDao()
    @Provides fun provideGroupDao(db: WhisperDatabase): GroupDao = db.groupDao()
    @Provides fun provideOutboxDao(db: WhisperDatabase): OutboxDao = db.outboxDao()
    @Provides fun provideCallRecordDao(db: WhisperDatabase): CallRecordDao = db.callRecordDao()
}
'''

CRYPTO_MODULE_KT = '''package com.whisper2.app.di

import android.content.Context
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.whisper2.app.data.local.keystore.KeystoreManager
import com.whisper2.app.data.local.prefs.SecureStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.SecureRandom
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    @Provides @Singleton
    fun provideLazySodium(): LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())

    @Provides @Singleton
    fun provideSecureRandom(): SecureRandom = SecureRandom()

    @Provides @Singleton
    fun provideKeystoreManager(): KeystoreManager = KeystoreManager()

    @Provides @Singleton
    fun provideSecureStorage(
        @ApplicationContext context: Context,
        keystoreManager: KeystoreManager
    ): SecureStorage = SecureStorage(context, keystoreManager)
}
'''

# ============================================================================
# MAIN FUNCTION
# ============================================================================

def generate_all():
    """Generate all project files."""
    print("=" * 60)
    print("Whisper2 Android Project Generator")
    print("=" * 60)

    # Create directories
    print("\n[1/3] Creating directories...")
    create_directories()

    # Write Gradle files
    print("\n[2/3] Writing Gradle files...")
    write_file(BASE_PATH / "settings.gradle.kts", SETTINGS_GRADLE)
    write_file(BASE_PATH / "build.gradle.kts", ROOT_BUILD_GRADLE)
    write_file(APP_PATH / "build.gradle.kts", APP_BUILD_GRADLE)
    write_file(APP_PATH / "proguard-rules.pro", PROGUARD_RULES)

    # Write Android resources
    print("\n[3/3] Writing source files...")
    write_file(SRC_MAIN / "AndroidManifest.xml", ANDROID_MANIFEST)
    write_file(RES_PATH / "values/strings.xml", STRINGS_XML)
    write_file(RES_PATH / "values/themes.xml", THEMES_XML)
    write_file(RES_PATH / "xml/data_extraction_rules.xml", DATA_EXTRACTION_RULES)
    write_file(RES_PATH / "xml/network_security_config.xml", NETWORK_SECURITY_CONFIG)
    write_file(RES_PATH / "xml/file_paths.xml", FILE_PATHS)

    # Write Kotlin source files
    write_file(JAVA_PATH / "core/Constants.kt", CONSTANTS_KT)
    write_file(JAVA_PATH / "core/Errors.kt", ERRORS_KT)
    write_file(JAVA_PATH / "core/Logger.kt", LOGGER_KT)
    write_file(JAVA_PATH / "core/Extensions.kt", EXTENSIONS_KT)
    write_file(JAVA_PATH / "App.kt", APP_KT)
    write_file(JAVA_PATH / "di/AppModule.kt", APP_MODULE_KT)
    write_file(JAVA_PATH / "di/NetworkModule.kt", NETWORK_MODULE_KT)
    write_file(JAVA_PATH / "di/DatabaseModule.kt", DATABASE_MODULE_KT)
    write_file(JAVA_PATH / "di/CryptoModule.kt", CRYPTO_MODULE_KT)

    print("\n" + "=" * 60)
    print("Phase 1 complete! Project structure created.")
    print("=" * 60)

if __name__ == "__main__":
    generate_all()
