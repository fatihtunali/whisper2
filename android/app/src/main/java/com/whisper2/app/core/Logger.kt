package com.whisper2.app.core

import android.util.Log
import com.whisper2.app.BuildConfig

object Logger {
    private const val TAG = "Whisper2"
    private val isDebug = BuildConfig.DEBUG

    fun d(msg: String) { if (isDebug) Log.d(TAG, msg) }
    fun i(msg: String) = Log.i(TAG, msg)
    fun w(msg: String) = Log.w(TAG, msg)
    fun e(msg: String, t: Throwable? = null) = if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)

    // Important logs that should show even in release builds
    fun ws(msg: String) = Log.i(TAG, "[WS] $msg")
    fun crypto(msg: String) { if (isDebug) d("[CRYPTO] $msg") }
    fun auth(msg: String) = Log.i(TAG, "[AUTH] $msg")
    fun call(msg: String) = Log.i(TAG, "[CALL] $msg")

    fun redact(v: String): String = if (v.length <= 8) "***" else "${v.take(4)}...${v.takeLast(4)}"
}
