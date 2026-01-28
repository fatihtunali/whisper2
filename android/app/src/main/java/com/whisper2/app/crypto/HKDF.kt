package com.whisper2.app.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

/**
 * HKDF-SHA256 implementation per RFC 5869.
 * Used for key derivation from BIP39 seed.
 */
object HKDF {

    private const val HASH_LEN = 32 // SHA-256 output

    /**
     * HKDF-Extract: Extract a pseudorandom key from input keying material.
     */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF-Expand: Expand the pseudorandom key to desired length.
     */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val n = ceil(length.toDouble() / HASH_LEN).toInt()
        val okm = ByteArray(n * HASH_LEN)
        var t = ByteArray(0)

        for (i in 1..n) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            System.arraycopy(t, 0, okm, (i - 1) * HASH_LEN, HASH_LEN)
        }

        return okm.copyOf(length)
    }

    /**
     * Full HKDF: Extract then Expand.
     */
    fun deriveKey(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = extract(salt, ikm)
        return expand(prk, info, length)
    }
}
