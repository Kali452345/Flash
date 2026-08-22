package com.transfer.flash.lan

import android.content.Context
import com.transfer.flash.discovery.LanDiscovery
import com.transfer.flash.identity.AppIdentity
import com.transfer.flash.model.DiscoveredDevice
import com.transfer.flash.core.network.tcp.LanConnectionProbe
import com.transfer.flash.core.network.tcp.LanProbeHello
import com.transfer.flash.core.network.tcp.LanProbeServer
import com.transfer.flash.core.network.tcp.LanSession
import com.transfer.flash.core.network.util.LocalNetworkAddresses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LanUiState(
    val localDeviceId: String = "",
    val friendlyName: String = "",
    val isRunning: Boolean = false,
    val listenPort: Int = 0,
    val localAddresses: List<String> = emptyList(),
    val status: String = "LAN idle",
    val devices: List<DiscoveredDevice> = emptyList(),
    val lastProbeResult: String? = null,
    val connectionStates: Map<String, PeerConnectionState> = emptyMap(),
    val manualHost: String = "",
    val manualPort: String = "",
    val manualConnectionState: PeerConnectionState = PeerConnectionState.IDLE,
    val manualConnectionResult: String? = null,
    val manualConnectedDeviceId: String? = null,
)

enum class PeerConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    FAILED,
}

class LanController(context: Context) {
    private val appContext = context.applicationContext
    private val identity = AppIdentity(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val localNetworkAddresses = LocalNetworkAddresses(appContext)
    private val sessions = mutableMapOf<String, LanSession>()
    private val probeServer = LanProbeServer(
        deviceId = identity.deviceId,
        friendlyName = identity.friendlyName,
        onPeerProbed = ::markInboundPeerConnected,
        onPeerDisconnected = ::markPeerDisconnected,
    )
    private val connectionProbe = LanConnectionProbe(appContext, identity.deviceId, identity.friendlyName)
    private val discovery = LanDiscovery(
        context = appContext,
        localDeviceId = identity.deviceId,
        friendlyName = identity.friendlyName,
        onDeviceFound = ::upsertDevice,
        onDeviceLost = ::removeDevice,
        onStatusChanged = ::setStatus,
    )

    private val _state = MutableStateFlow(
        LanUiState(
            localDeviceId = identity.deviceId,
            friendlyName = identity.friendlyName,
        )
    )
    val state: StateFlow<LanUiState> = _state

    fun startLan() {
        if (_state.value.isRunning) return

        scope.launch {
            _state.update {
                it.copy(
                    connectionStates = emptyMap(),
                    manualConnectionState = PeerConnectionState.IDLE,
                    manualConnectionResult = null,
                    manualConnectedDeviceId = null,
                    lastProbeResult = null,
                )
            }
            runCatching {
                val port = probeServer.start()
                discovery.start(port)
                _state.update {
                    it.copy(
                        isRunning = true,
                        listenPort = port,
                        localAddresses = localNetworkAddresses.ipv4Addresses(),
                        status = "LAN ready on port $port",
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isRunning = false,
                        status = "LAN start failed: ${error.message ?: error::class.java.simpleName}",
                    )
                }
            }
        }
    }

    fun stopLan() {
        closeAllSessions()
        discovery.stop()
        probeServer.stop()
        _state.update {
            it.copy(
                isRunning = false,
                listenPort = 0,
                localAddresses = emptyList(),
                devices = emptyList(),
                connectionStates = emptyMap(),
                manualConnectionState = PeerConnectionState.IDLE,
                manualConnectionResult = null,
                manualConnectedDeviceId = null,
                lastProbeResult = null,
                status = "LAN stopped",
            )
        }
    }

    fun probe(device: DiscoveredDevice) {
        scope.launch {
            setDeviceConnectionState(device.deviceId, PeerConnectionState.CONNECTING)
            _state.update { it.copy(lastProbeResult = "Connecting to ${device.friendlyName}...") }
            val result = connectionProbe.connectSession(device.hostAddress, device.port, device.deviceId, ::markSessionDisconnected)
            _state.update { currentState ->
                currentState.copy(
                    connectionStates = currentState.connectionStates
                        .plus(device.deviceId to result.fold(
                            onSuccess = { session ->
                                sessions[session.peerInfo.deviceId] = session
                                PeerConnectionState.CONNECTED
                            },
                            onFailure = { PeerConnectionState.FAILED },
                        )),
                    lastProbeResult = result.fold(
                        onSuccess = { session ->
                            "Connected to ${session.peer.friendlyName} with protocol v${session.peer.protocolVersion}"
                        },
                        onFailure = { error ->
                            "Connection failed: ${error.message ?: error::class.java.simpleName}"
                        },
                    )
                )
            }
        }
    }

    fun disconnect(device: DiscoveredDevice) {
        val session = sessions.remove(device.deviceId)
        if (session != null) {
            session.disconnect()
        } else {
            scope.launch {
                connectionProbe.notifyDisconnect(device.hostAddress, device.port, device.deviceId)
            }
        }
        _state.update {
            it.copy(
                connectionStates = it.connectionStates.minus(device.deviceId),
                lastProbeResult = "Disconnected from ${device.friendlyName}",
            )
        }
    }

    fun disconnectManual() {
        val connectedDeviceId = _state.value.manualConnectedDeviceId
        if (connectedDeviceId != null) {
            sessions.remove(connectedDeviceId)?.disconnect()
        }
        _state.update {
            it.copy(
                manualConnectionState = PeerConnectionState.IDLE,
                manualConnectionResult = null,
                manualConnectedDeviceId = null,
            )
        }
    }

    fun updateManualHost(value: String) {
        _state.update { it.copy(manualHost = value.trim()) }
    }

    fun updateManualPort(value: String) {
        _state.update { it.copy(manualPort = value.filter(Char::isDigit).take(5)) }
    }

    fun connectManual() {
        val current = _state.value
        val host = current.manualHost.trim()
        val port = current.manualPort.toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65_535) {
            _state.update {
                it.copy(
                    manualConnectionState = PeerConnectionState.FAILED,
                    manualConnectionResult = "Enter a valid IP address and port.",
                )
            }
            return
        }

        val manualDevice = DiscoveredDevice(
            deviceId = "manual:$host:$port",
            friendlyName = "$host:$port",
            hostAddress = host,
            port = port,
            serviceName = "manual:$host:$port",
        )

        scope.launch {
            _state.update {
                it.copy(
                    manualConnectionState = PeerConnectionState.CONNECTING,
                    manualConnectionResult = "Connecting to $host:$port...",
                )
            }
            val result = connectionProbe.connectSession(host, port, "manual:$host:$port", ::markSessionDisconnected)
            _state.update { currentState ->
                currentState.copy(
                    manualConnectionState = result.fold(
                        onSuccess = { session ->
                            sessions[session.peerInfo.deviceId] = session
                            PeerConnectionState.CONNECTED
                        },
                        onFailure = { PeerConnectionState.FAILED },
                    ),
                    manualConnectionResult = result.fold(
                        onSuccess = { session ->
                            "Connected to ${session.peer.friendlyName} with protocol v${session.peer.protocolVersion}"
                        },
                        onFailure = { error ->
                            "Manual connection failed: ${error.message ?: error::class.java.simpleName}"
                        },
                    ),
                    manualConnectedDeviceId = result.getOrNull()?.peerInfo?.deviceId,
                    connectionStates = result.fold(
                        onSuccess = { session ->
                            currentState.connectionStates.plus(session.peerInfo.deviceId to PeerConnectionState.CONNECTED)
                        },
                        onFailure = { currentState.connectionStates },
                    ),
                )
            }
        }
    }

    fun close() {
        stopLan()
    }

    private fun upsertDevice(device: DiscoveredDevice) {
        _state.update { current ->
            val nextDevices = current.devices
                .filterNot { it.deviceId == device.deviceId || it.serviceName == device.serviceName }
                .plus(device)
                .sortedBy { it.friendlyName.lowercase() }
            current.copy(devices = nextDevices)
        }
    }

    private fun setDeviceConnectionState(deviceId: String, connectionState: PeerConnectionState) {
        _state.update {
            it.copy(connectionStates = it.connectionStates.plus(deviceId to connectionState))
        }
    }

    private fun markInboundPeerConnected(
        hello: LanProbeHello,
        hostAddress: String,
        session: LanSession,
    ) {
        sessions[hello.deviceId] = session
        _state.update { current ->
            val knownDevice = current.devices.firstOrNull { it.deviceId == hello.deviceId }
            val nextDevices = if (knownDevice == null) {
                current.devices.plus(
                    DiscoveredDevice(
                        deviceId = hello.deviceId,
                        friendlyName = hello.friendlyName,
                        hostAddress = hostAddress,
                        port = 0,
                        serviceName = "inbound:${hello.deviceId}",
                        protocolVersion = hello.protocolVersion,
                    )
                ).sortedBy { it.friendlyName.lowercase() }
            } else {
                current.devices
            }

            current.copy(
                devices = nextDevices,
                connectionStates = current.connectionStates.plus(hello.deviceId to PeerConnectionState.CONNECTED),
                lastProbeResult = "Accepted connection from ${hello.friendlyName}",
            )
        }
    }

    private fun markPeerDisconnected(hello: LanProbeHello) {
        sessions.remove(hello.deviceId)
        _state.update {
            it.copy(
                connectionStates = it.connectionStates.minus(hello.deviceId),
                manualConnectionState = if (it.manualConnectedDeviceId == hello.deviceId) {
                    PeerConnectionState.IDLE
                } else {
                    it.manualConnectionState
                },
                manualConnectionResult = if (it.manualConnectedDeviceId == hello.deviceId) {
                    null
                } else {
                    it.manualConnectionResult
                },
                manualConnectedDeviceId = if (it.manualConnectedDeviceId == hello.deviceId) {
                    null
                } else {
                    it.manualConnectedDeviceId
                },
                lastProbeResult = "${hello.friendlyName} disconnected",
            )
        }
    }

    private fun markSessionDisconnected(hello: LanProbeHello, reason: String) {
        sessions.remove(hello.deviceId)
        _state.update {
            it.copy(
                connectionStates = it.connectionStates.minus(hello.deviceId),
                manualConnectionState = if (it.manualConnectedDeviceId == hello.deviceId) {
                    PeerConnectionState.IDLE
                } else {
                    it.manualConnectionState
                },
                manualConnectionResult = if (it.manualConnectedDeviceId == hello.deviceId) {
                    null
                } else {
                    it.manualConnectionResult
                },
                manualConnectedDeviceId = if (it.manualConnectedDeviceId == hello.deviceId) {
                    null
                } else {
                    it.manualConnectedDeviceId
                },
                lastProbeResult = reason,
            )
        }
    }

    private fun removeDevice(serviceName: String) {
        _state.update { current ->
            val lostDevice = current.devices.firstOrNull { it.serviceName == serviceName }
            lostDevice?.let { sessions.remove(it.deviceId)?.close("${it.friendlyName} disappeared") }
            current.copy(
                devices = current.devices.filterNot { it.serviceName == serviceName },
                connectionStates = lostDevice?.let { current.connectionStates.minus(it.deviceId) }
                    ?: current.connectionStates,
            )
        }
    }

    private fun setStatus(status: String) {
        _state.update { it.copy(status = status) }
    }

    private fun closeAllSessions() {
        sessions.values.toList().forEach { session ->
            session.close("LAN stopped")
        }
        sessions.clear()
    }
}
