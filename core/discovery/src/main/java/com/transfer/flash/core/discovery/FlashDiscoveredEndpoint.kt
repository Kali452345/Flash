package com.transfer.flash.core.discovery

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType

data class FlashDiscoveredEndpoint(
    val device: FlashDevice,
    val hostAddress: String,
    val port: Int,
    val serviceName: String,
) {
    val deviceId: FlashDeviceId get() = device.id
    val friendlyName: String get() = device.friendlyName
    val transportType: FlashTransportType get() = device.transportType
}
