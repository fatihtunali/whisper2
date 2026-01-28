package com.whisper2.app.core

import android.util.Base64
import java.text.Normalizer

fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

fun String.normalizeNFKD(): String = Normalizer.normalize(this, Normalizer.Form.NFKD)
fun String.normalizeMnemonic(): String = normalizeNFKD().trim().replace(Regex("\\s+"), " ").lowercase()

fun ByteArray.secureEquals(other: ByteArray): Boolean {
    if (size != other.size) return false
    var result = 0
    for (i in indices) result = result or (this[i].toInt() xor other[i].toInt())
    return result == 0
}

fun ByteArray.wipe() = fill(0)
fun currentTimeMillis(): Long = System.currentTimeMillis()
fun Long.isValidTimestamp(): Boolean = kotlin.math.abs(currentTimeMillis() - this) <= Constants.TIMESTAMP_SKEW_MS
fun Int.formatDuration(): String = "%d:%02d".format(this / 60, this % 60)
fun Long.formatFileSize(): String = when {
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> "%.1f KB".format(this / 1024f)
    else -> "%.1f MB".format(this / (1024f * 1024f))
}
