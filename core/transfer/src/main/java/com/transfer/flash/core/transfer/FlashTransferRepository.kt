package com.transfer.flash.core.transfer

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferId
import kotlinx.coroutines.flow.StateFlow

/**
 * Public domain contract for orchestrating file transfers across peers.
 */
interface FlashTransferRepository {
    val activeTransfers: StateFlow<List<FlashTransfer>>

    suspend fun sendFile(
        targetDevice: FlashDevice,
        fileUri: String,
        displayName: String,
        fileSize: Long,
    ): FlashResult<FlashTransferId>

    suspend fun pauseTransfer(transferId: FlashTransferId): FlashResult<Unit>
    suspend fun resumeTransfer(transferId: FlashTransferId): FlashResult<Unit>
    suspend fun cancelTransfer(transferId: FlashTransferId): FlashResult<Unit>

    fun onInboundFrame(bytes: ByteArray): Boolean = false

    /**
     * Receive-side tracking so inbound transfers surface in [activeTransfers] like sends do.
     * Called by the engine when the receive pipeline accepts a FILE_START.
     */
    fun onIncomingStarted(
        transferId: String,
        fileId: String,
        fileName: String,
        totalBytes: Long,
        peerName: String,
        peerDeviceId: String? = null,
    ) = Unit

    /** Progress tick for an inbound transfer (bytes verified + written). */
    fun onIncomingProgress(transferId: String, bytesDone: Long) = Unit

    /** Terminal success for an inbound transfer; [verified] reflects the whole-file recheck. */
    fun onIncomingCompleted(transferId: String, verified: Boolean) = Unit

    /** Terminal failure for an inbound transfer (e.g. unrecoverable rejection). */
    fun onIncomingFailed(transferId: String, reason: String) = Unit
}
