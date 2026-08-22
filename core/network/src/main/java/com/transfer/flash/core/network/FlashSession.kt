package com.transfer.flash.core.network

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents an active, bidirectional, peer-to-peer communication session.
 */
interface FlashSession {
    val peer: FlashDevice
    val peerDeviceId: FlashDeviceId get() = peer.id
    val connectionState: StateFlow<FlashConnectionState>
    val transportType: FlashTransportType

    suspend fun send(message: ByteArray): FlashResult<Unit>
    suspend fun sendText(text: String): FlashResult<Unit> = send(text.toByteArray(Charsets.UTF_8))
    fun disconnect(reason: String = "Normal disconnect")
}
