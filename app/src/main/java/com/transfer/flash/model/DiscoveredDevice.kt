package com.transfer.flash.model

enum class TransportType {
    LAN,
    WIFI_DIRECT,
}

data class DiscoveredDevice(
    val deviceId: String,
    val friendlyName: String,
    val hostAddress: String,
    val port: Int,
    val serviceName: String,
    val transportType: TransportType = TransportType.LAN,
    val protocolVersion: Int = 1,
)
