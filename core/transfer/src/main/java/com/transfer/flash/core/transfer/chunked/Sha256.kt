package com.transfer.flash.core.transfer.chunked

import java.security.MessageDigest

/**
 * Incremental SHA-256 helpers for the chunked transfer pipelines (C5.3–C5.6).
 *
 * Decision D3 (docs/core-upgrade-plan.md §1): SHA-256 via `java.security` — zero deps and
 * ARMv8 crypto-extension accelerated on modern Android. Used for three distinct purposes:
 *
 * 1. **Whole-file digest** — computed once by the sender (single streaming pass, see
 *    [Chunker.hashOnly]) and carried in `FILE_START.fileSha256Hex`, mirroring how LocalSend
 *    supplies the file-level `sha256` in `/prepare-upload` metadata and the receiver answers
 *    HTTP `422` on mismatch: https://github.com/localsend/protocol (§4.1, §4.2).
 * 2. **Per-chunk digest** — independent SHA-256 over each chunk payload, carried raw (32 B)
 *    in every `CHUNK` frame and verified **before** the receive sink writes (C5.5
 *    verify-before-write). Independent per-piece hashes are the established resumable-transfer
 *    practice (BitTorrent piece hashes: https://bittorrent.org/bittorrentecon.pdf; BitTorrent v2
 *    fixed 16 KiB hash-tree leaves so corrupt data is detected at block granularity and only the
 *    damaged block is re-requested: https://blog.libtorrent.org/2020/09/bittorrent-v2/). A single
 *    running digest cannot localize corruption and would force full re-transfer on resume.
 * 3. **Constant-time comparisons** of digest material via [MessageDigest.isEqual] to avoid
 *    timing side channels on hash checks ([rawEqualsConstantTime], [hexEqualsConstantTime]).
 */
object Sha256 {

    /** Length of a lowercase hexadecimal SHA-256 string ("abc" digest form). */
    const val HEX_LENGTH: Int = 64

    /** Length of a raw (binary) SHA-256 digest. */
    const val RAW_LENGTH: Int = 32

    /** One-shot digest over one or concatenated byte arrays. */
    fun digest(vararg chunks: ByteArray): ByteArray =
        instance().apply { chunks.forEach { update(it) } }.digest()

    /** One-shot digest formatted as lowercase hex. */
    fun digestHex(bytes: ByteArray): String = hex(digest(bytes))

    /** Lowercase hex formatting, consistent with the project's `*Hex` naming convention. */
    fun hex(raw: ByteArray): String {
        val sb = StringBuilder(raw.size * 2)
        for (b in raw) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** Constant-time equality of two raw digests (length-safe). */
    fun rawEqualsConstantTime(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    /** Constant-time equality of two hex-formatted digests (length-safe). */
    fun hexEqualsConstantTime(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    /**
     * True iff [value] is exactly [HEX_LENGTH] lowercase-or-uppercase hex characters.
     * Used to validate wire-supplied `FILE_START.fileSha256Hex` before trust.
     */
    fun isValidHex(value: String): Boolean {
        if (value.length != HEX_LENGTH) return false
        for (c in value) {
            val ok = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
            if (!ok) return false
        }
        return true
    }

    /** Normalizes a valid hex digest string to the canonical lowercase wire form. */
    fun normalizeHex(value: String): String {
        require(isValidHex(value)) { "not a SHA-256 hex digest: length=${value.length}" }
        return value.lowercase()
    }

    private fun instance(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * Streaming SHA-256 accumulator for single-pass hashing while chunks flow through a pipeline.
 * Not thread-safe; scope it to the owning pipeline loop.
 */
class IncrementalSha256 {

    private val digest = MessageDigest.getInstance("SHA-256")

    fun update(bytes: ByteArray) {
        digest.update(bytes)
    }

    fun update(bytes: ByteArray, offset: Int, length: Int) {
        digest.update(bytes, offset, length)
    }

    /** Finishes the digest and returns the raw 32-byte result (does not reset). */
    fun digestRaw(): ByteArray = digest.digest()

    /** Finishes the digest and returns the lowercase-hex result (does not reset). */
    fun digestHex(): String = Sha256.hex(digest.digest())

    fun reset() {
        digest.reset()
    }
}
