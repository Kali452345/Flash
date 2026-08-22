package com.transfer.flash.core.common.result

/**
 * Well-typed, exhaustive sealed hierarchy representing failure reasons across Flash operations.
 */
sealed interface FlashError {
    /** Network connectivity is missing or unavailable. */
    data class NetworkUnavailable(val message: String? = null) : FlashError

    /** Targeted peer device is unreachable or disconnected. */
    data class PeerUnavailable(val deviceId: String, val message: String? = null) : FlashError

    /** An operation or connection attempt timed out. */
    data class ConnectionTimeout(val timeoutMs: Long, val message: String? = null) : FlashError

    /** Protocol version mismatch between peers. */
    data class ProtocolMismatch(val expected: Int, val actual: Int) : FlashError

    /** File transfer or chunking operation failed. */
    data class TransferFailed(val transferId: String, val reason: String) : FlashError

    /** Cryptographic hash or byte-count verification failed. */
    data class VerificationFailed(val expectedHash: String, val actualHash: String) : FlashError

    /** Filesystem or Storage Access Framework I/O error. */
    data class StorageError(val message: String, val cause: Throwable? = null) : FlashError

    /** Operation was explicitly cancelled by the user or system. */
    data class Cancelled(val reason: String? = null) : FlashError

    /** General or unexpected error. */
    data class Unknown(val message: String, val cause: Throwable? = null) : FlashError
}
