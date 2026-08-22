package com.transfer.flash.wstransfer

import android.content.Context
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.nsd.NsdFlashDiscovery
import com.transfer.flash.model.DiscoveredDevice
import com.transfer.flash.model.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Compatibility adapter for [NsdFlashDiscovery] in the experimental WebSocket transfer manager.
 */
class WsDiscovery(
    context: Context,
    localDeviceId: String,
    friendlyName: String,
    onDeviceFound: (DiscoveredDevice) -> Unit,
    onDeviceLost: (String) -> Unit,
    onStatusChanged: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = NsdFlashDiscovery(
        context = context,
        localDeviceId = FlashDeviceId(localDeviceId),
        friendlyNameProvider = { friendlyName },
        serviceType = NsdFlashDiscovery.SERVICE_TYPE_WS,
        serviceInstancePrefix = "FlashWS",
        capabilities = "ws-transfer",
        transportType = FlashTransportType.WEBSOCKET,
        protocolVersion = 1,
        onEndpointFound = { endpoint ->
            onDeviceFound(endpoint.toLegacyModel())
        },
        onEndpointLost = onDeviceLost,
        onStatusChanged = onStatusChanged,
    )

    val isDiscoveryRunning: Boolean
        get() = engine.state.value.isDiscovering

    fun startDiscovery() {
        scope.launch { engine.startDiscovery() }
    }

    fun stopDiscovery() {
        scope.launch { engine.stopDiscovery() }
    }

    fun startAdvertising(listenPort: Int) {
        scope.launch { engine.startAdvertising(listenPort) }
    }

    fun stopAdvertising() {
        scope.launch { engine.stopAdvertising() }
    }

    fun stopAll() {
        scope.launch { engine.stopAll() }
    }

    private fun FlashDiscoveredEndpoint.toLegacyModel(): DiscoveredDevice {
        return DiscoveredDevice(
            deviceId = deviceId.value,
            friendlyName = friendlyName,
            hostAddress = hostAddress,
            port = port,
            serviceName = serviceName,
            transportType = TransportType.LAN,
            protocolVersion = device.protocolVersion,
        )
    }
}
