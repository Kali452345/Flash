package com.transfer.flash.core.discovery

import com.transfer.flash.core.common.result.FlashResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Public discovery interface for peer advertising and scanning across local network mediums.
 */
interface FlashDiscovery {
    val state: StateFlow<FlashDiscoveryState>
    val discoveredEndpoints: StateFlow<List<FlashDiscoveredEndpoint>>

    suspend fun startDiscovery(): FlashResult<Unit>
    suspend fun stopDiscovery(): FlashResult<Unit>
    suspend fun startAdvertising(listenPort: Int): FlashResult<Unit>
    suspend fun stopAdvertising(): FlashResult<Unit>
    suspend fun stopAll(): FlashResult<Unit>
}
