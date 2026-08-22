package com.transfer.flash.core.security.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareFlashCryptoTest {

    private val crypto = SoftwareFlashCrypto()

    @Test
    fun `sign and verify roundtrip succeeds`() {
        val data = "flash handshake payload".toByteArray()
        val signature = crypto.sign(data)
        assertTrue(crypto.verify(signature, data, crypto.identityPublicKey.encoded))
    }

    @Test
    fun `tampered data fails verification`() {
        val data = "flash handshake payload".toByteArray()
        val signature = crypto.sign(data)
        val tampered = data.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(crypto.verify(signature, tampered, crypto.identityPublicKey.encoded))
    }

    @Test
    fun `tampered signature fails verification`() {
        val data = "flash handshake payload".toByteArray()
        val signature = crypto.sign(data)
        val tampered = signature.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertFalse(crypto.verify(tampered, data, crypto.identityPublicKey.encoded))
    }

    @Test
    fun `verification against wrong public key fails`() {
        val other = SoftwareFlashCrypto()
        val data = "flash handshake payload".toByteArray()
        val signature = crypto.sign(data)
        assertFalse(crypto.verify(signature, data, other.identityPublicKey.encoded))
    }

    @Test
    fun `malformed peer public key returns false instead of throwing`() {
        val data = "payload".toByteArray()
        val signature = crypto.sign(data)
        assertFalse(crypto.verify(byteArrayOf(1, 2, 3), data, byteArrayOf(9, 9, 9)))
    }

    @Test
    fun `both peers derive identical session keys from ephemeral ECDH`() {
        val alice = SoftwareFlashCrypto()
        val bob = SoftwareFlashCrypto()

        val ephemeralAlice = alice.generateEphemeralEcdhKeyPair()
        val ephemeralBob = bob.generateEphemeralEcdhKeyPair()

        val keyAtAlice = alice.ecdhSessionKey(ephemeralAlice.private, ephemeralBob.public)
        val keyAtBob = bob.ecdhSessionKey(ephemeralBob.private, ephemeralAlice.public)

        assertEquals(FlashCrypto.SESSION_KEY_SIZE_BYTES, keyAtAlice.size)
        assertArrayEquals(keyAtAlice, keyAtBob)
    }

    @Test
    fun `fresh ephemeral pairs yield different session keys`() {
        val kpA1 = crypto.generateEphemeralEcdhKeyPair()
        val kpB1 = crypto.generateEphemeralEcdhKeyPair()
        val key1 = crypto.ecdhSessionKey(kpA1.private, kpB1.public)

        val kpA2 = crypto.generateEphemeralEcdhKeyPair()
        val kpB2 = crypto.generateEphemeralEcdhKeyPair()
        val key2 = crypto.ecdhSessionKey(kpA2.private, kpB2.public)

        assertFalse(key1.contentEquals(key2))
    }
}
