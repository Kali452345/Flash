package com.transfer.flash.discovery

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
 * Compatibility adapter for [NsdFlashDiscovery] in the legacy LAN controller.
 */
class LanDiscovery(
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
        serviceType = NsdFlashDiscovery.SERVICE_TYPE_LAN,
        serviceInstancePrefix = "Flash",
        capabilities = "probe",
        transportType = FlashTransportType.LAN,
        protocolVersion = 1,
        onEndpointFound = { endpoint ->
            onDeviceFound(endpoint.toLegacyModel())
        },
        onEndpointLost = onDeviceLost,
        onStatusChanged = onStatusChanged,
    )

    fun start(listenPort: Int) {
        scope.launch {
            engine.startAdvertising(listenPort)
            engine.startDiscovery()
        }
    }

    fun stop() {
        scope.launch {
            engine.stopAll()
        }
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
