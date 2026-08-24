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
}
