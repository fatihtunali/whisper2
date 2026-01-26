package com.whisper2.app.crypto

import org.junit.Assert.*
import org.junit.Test

/**
 * Kapı 1: Key derivation deterministik mi?
 *
 * Aynı mnemonic (+ aynı passphrase) → her seferinde aynı encSeed/signSeed/contactsKey
 */
class CryptoVectorsTest {

    @Test
    fun `same mnemonic produces same seeds`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val passphrase = ""

        val a = KeyDerivation.deriveAll(mnemonic, passphrase)
        val b = KeyDerivation.deriveAll(mnemonic, passphrase)

        assertArrayEquals("encSeed must be deterministic", a.encSeed, b.encSeed)
        assertArrayEquals("signSeed must be deterministic", a.signSeed, b.signSeed)
        assertArrayEquals("contactsKey must be deterministic", a.contactsKey, b.contactsKey)

        assertEquals("encSeed must be 32 bytes", 32, a.encSeed.size)
        assertEquals("signSeed must be 32 bytes", 32, a.signSeed.size)
        assertEquals("contactsKey must be 32 bytes", 32, a.contactsKey.size)
    }

    @Test
    fun `different passphrase changes seeds`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

        val a = KeyDerivation.deriveAll(mnemonic, "")
        val b = KeyDerivation.deriveAll(mnemonic, "x")

        // En az birinin farklı olması yeterli; hepsinin farklı olması beklenir.
        val sameEnc = a.encSeed.contentEquals(b.encSeed)
        val sameSign = a.signSeed.contentEquals(b.signSeed)
        val sameContacts = a.contactsKey.contentEquals(b.contactsKey)

        assertFalse("Different passphrase must produce different seeds", sameEnc && sameSign && sameContacts)
    }

    @Test
    fun `seeds match frozen test vectors`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val keys = KeyDerivation.deriveAll(mnemonic, "")

        // Frozen test vectors from server
        val expectedEncSeedHex = "08851144b1bdf8b99c563bd408f4a613943fef2d9120397573932bd9833e0149"
        val expectedSignSeedHex = "457f5c29bc4ab25ea84b9d076fee560db80b9994725106594400e28672f3e5be"
        val expectedContactsKeyHex = "de3d0fda0659df936a71ee48cf6519da84b285344916511b5244d2ac36c23ff2"

        assertEquals("encSeed must match frozen vector", expectedEncSeedHex, bytesToHex(keys.encSeed))
        assertEquals("signSeed must match frozen vector", expectedSignSeedHex, bytesToHex(keys.signSeed))
        assertEquals("contactsKey must match frozen vector", expectedContactsKeyHex, bytesToHex(keys.contactsKey))
    }

    @Test
    fun `BIP39 seed matches frozen vector`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = BIP39.seedFromMnemonic(mnemonic, "")

        val expectedSeedHex = "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"

        assertEquals("BIP39 seed must be 64 bytes", 64, seed.size)
        assertEquals("BIP39 seed must match frozen vector", expectedSeedHex, bytesToHex(seed))
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
