package com.transfer.flash.core.security.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF (RFC 5869) with HMAC-SHA256 — extract-then-expand key derivation.
 *
 * Used to turn the raw ECDH shared secret (which is NOT uniformly random key material
 * and must never be used directly as an AES key) into a proper AES-256 session key.
 * Correctness is pinned against the official RFC 5869 Appendix A test vectors in
 * [com.transfer.flash.core.security.crypto.HkdfTest].
 *
 * Reference: https://www.rfc-editor.org/rfc/rfc5869
 */
internal object Hkdf {

    private const val HMAC_SHA256 = "HmacSHA256"
    private const val HASH_LEN_BYTES = 32

    /** HKDF-Extract(salt, IKM) -> PRK. Empty salt defaults to HashLen zero octets (RFC section 2.2). */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN_BYTES) else salt
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(effectiveSalt, HMAC_SHA256))
        return mac.doFinal(ikm)
    }

    /** HKDF-Expand(PRK, info, L) -> OKM (RFC section 2.3). */
    fun expand(prk: ByteArray, info: ByteArray, outLength: Int): ByteArray {
        require(outLength > 0) { "HKDF output length must be positive, was $outLength" }
        require(outLength <= 255 * HASH_LEN_BYTES) {
            "HKDF output length $outLength exceeds maximum ${255 * HASH_LEN_BYTES}"
        }
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(prk, HMAC_SHA256))
        var block = ByteArray(0)
        var counter = 1
        var produced = 0
        val okm = ByteArray(outLength)
        while (produced < outLength) {
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val toCopy = minOf(block.size, outLength - produced)
            System.arraycopy(block, 0, okm, produced, toCopy)
            produced += toCopy
            counter++
        }
        return okm
    }

    /** Convenience: Extract followed by Expand in one call. */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLength: Int): ByteArray =
        expand(extract(salt, ikm), info, outLength)
}
