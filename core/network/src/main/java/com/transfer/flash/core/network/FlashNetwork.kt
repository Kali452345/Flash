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

    /**
     * Aggregated connection health for UI-030/UI-044 (plan C4.7). Additive —
     * implementations wire it to [ReconnectEngine]/session signals in P4.
     */
    val connectionHealth: StateFlow<FlashConnectionHealth>

    suspend fun start(listenPort: Int = 0): FlashResult<Int>
    suspend fun stop(): FlashResult<Unit>
    suspend fun connect(device: FlashDevice): FlashResult<FlashSession>
    suspend fun connectManual(host: String, port: Int): FlashResult<FlashSession>
    suspend fun disconnect(deviceId: FlashDeviceId): FlashResult<Unit>

    /**
     * Manual reconnect trigger wired to the connection banner Retry button
     * (plan C4.7). Returns false when there is nothing to retry (no recent
     * failure / no candidate endpoints).
     */
    fun retryConnection(): Boolean = false
}
