package com.transfer.flash.core.network

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Public network contract for managing socket servers, peer connections, and sessions.
 */
interface FlashNetwork {
    val networkState: StateFlow<FlashNetworkState>
    val activeSessions: StateFlow<Map<FlashDeviceId, FlashSession>>

    suspend fun start(listenPort: Int = 0): FlashResult<Int>
    suspend fun stop(): FlashResult<Unit>
    suspend fun connect(device: FlashDevice): FlashResult<FlashSession>
    suspend fun connectManual(host: String, port: Int): FlashResult<FlashSession>
    suspend fun disconnect(deviceId: FlashDeviceId): FlashResult<Unit>
}
