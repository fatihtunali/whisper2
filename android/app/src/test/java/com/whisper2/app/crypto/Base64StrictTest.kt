package com.whisper2.app.crypto

import com.whisper2.app.core.utils.Base64Strict
import org.junit.Assert.*
import org.junit.Test

/**
 * Kapı 2: Base64 strict kuralları doğru mu?
 *
 * - padded base64 only
 * - length % 4 == 0
 * - round-trip (encode(decode(x)) == x canonical)
 * - nonce decode = 24 byte değilse FAIL
 */
class Base64StrictTest {

    @Test
    fun `rejects unpadded base64`() {
        val unpadded = "AQIDBA" // padding yok (should be AQIDBA==)
        assertThrows(IllegalArgumentException::class.java) {
            Base64Strict.decode(unpadded)
        }
    }

    @Test
    fun `rejects length not multiple of 4`() {
        val bad = "AAAAA" // 5 characters
        assertThrows(IllegalArgumentException::class.java) {
            Base64Strict.decode(bad)
        }
    }

    @Test
    fun `rejects invalid characters`() {
        val invalid = "ABC!DEF=" // ! is invalid
        assertThrows(IllegalArgumentException::class.java) {
            Base64Strict.decode(invalid)
        }
    }

    @Test
    fun `roundtrip works`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val b64 = Base64Strict.encode(bytes)
        val out = Base64Strict.decode(b64)
        assertArrayEquals("Roundtrip must preserve data", bytes, out)
    }

    @Test
    fun `roundtrip is canonical`() {
        val original = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64Strict.encode(original)
        val decoded = Base64Strict.decode(encoded)
        val reEncoded = Base64Strict.encode(decoded)

        assertEquals("Roundtrip must be canonical", encoded, reEncoded)
    }

    @Test
    fun `nonce must be 24 bytes - valid`() {
        val nonce24 = ByteArray(24) { 1 }
        val b64 = Base64Strict.encode(nonce24)
        val decoded = Base64Strict.decode(b64)

        assertEquals("Decoded nonce must be 24 bytes", 24, decoded.size)

        // Should not throw
        Base64Strict.assertNonce24(decoded)
    }

    @Test
    fun `nonce must be 24 bytes - invalid 23 bytes`() {
        val nonce23 = ByteArray(23) { 1 }
        val b64 = Base64Strict.encode(nonce23)
        val decoded = Base64Strict.decode(b64)

        assertEquals("Decoded should be 23 bytes", 23, decoded.size)

        // assertNonce24 must FAIL
        assertThrows(IllegalArgumentException::class.java) {
            Base64Strict.assertNonce24(decoded)
        }
    }

    @Test
    fun `nonce must be 24 bytes - invalid 25 bytes`() {
        val nonce25 = ByteArray(25) { 1 }

        assertThrows(IllegalArgumentException::class.java) {
            Base64Strict.assertNonce24(nonce25)
        }
    }

    @Test
    fun `accepts valid padded base64`() {
        // These are all valid padded base64
        val valid = listOf(
            "AAAA",           // 3 bytes
            "AQIDBA==",       // 4 bytes
            "SGVsbG8=",       // "Hello"
            ""                // empty is valid
        )

        for (b64 in valid) {
            assertTrue("$b64 should be valid", Base64Strict.isValid(b64))
            // Should not throw
            Base64Strict.decode(b64)
        }
    }

    @Test
    fun `decodeWithLength enforces length`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val b64 = Base64Strict.encode(bytes)

        // Correct length
        val decoded = Base64Strict.decodeWithLength(b64, 4)
        assertArrayEquals(bytes, decoded)

        // Wrong length
        assertThrows(IllegalArgumentException::class.java) {
            Base64Strict.decodeWithLength(b64, 5)
        }
    }
}
