package com.transfer.flash.core.transfer.model

@JvmInline
value class FlashTransferId(val value: String)

enum class FlashTransferDirection {
    Sending,
    Receiving,
}

enum class FlashTransferState {
    Offered,
    Queued,
    Transferring,
    Paused,
    Verifying,
    Completed,
    Failed,
    Cancelled,
}

data class FlashTransfer(
    val id: FlashTransferId,
    val peerName: String,
    val fileName: String,
    val direction: FlashTransferDirection,
    val bytesDone: Long,
    val bytesTotal: Long,
    val state: FlashTransferState,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = 0L,
    val errorMessage: String? = null,
)
