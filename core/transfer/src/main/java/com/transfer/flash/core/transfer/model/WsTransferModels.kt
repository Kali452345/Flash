package com.transfer.flash.core.transfer.model

import com.transfer.flash.core.network.ws.WsTransferServer

enum class WsTransferDirection { SENDING, RECEIVING }

enum class WsTransferStatus { ACTIVE, COMPLETED, FAILED }

data class WsPeer(
    val deviceId: String,
    val friendlyName: String,
    val address: String,
    val outbound: Boolean,
)

data class WsTransferItem(
    val id: String,
    val peerName: String,
    val fileName: String,
    val direction: WsTransferDirection,
    val bytesDone: Long,
    val bytesTotal: Long,
    val status: WsTransferStatus,
    val detail: String = "",
    val filePath: String? = null,
)

data class WsDiscoveredDevice(
    val deviceId: String,
    val friendlyName: String,
    val address: String,
    val paired: Boolean,
    val connected: Boolean,
    val connecting: Boolean,
)

data class WsTransferUiState(
    val localDeviceId: String = "",
    val friendlyName: String = "",
    val isServerRunning: Boolean = false,
    val listenPort: Int = 0,
    val localAddresses: List<String> = emptyList(),
    val status: String = "Server stopped",
    val hostInput: String = "",
    val portInput: String = WsTransferServer.PREFERRED_PORT.toString(),
    val isConnecting: Boolean = false,
    val lastConnectResult: String? = null,
    val peers: List<WsPeer> = emptyList(),
    val discovered: List<WsDiscoveredDevice> = emptyList(),
    val transfers: List<WsTransferItem> = emptyList(),
)
