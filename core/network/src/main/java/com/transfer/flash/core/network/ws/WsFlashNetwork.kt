@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import android.content.Context
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.FlashConnectionHealth
import com.transfer.flash.core.network.FlashConnectionState
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.network.FlashNetworkState
import com.transfer.flash.core.network.FlashSession
import com.transfer.flash.core.network.bridge.EndpointMemory
import com.transfer.flash.core.network.tls.TlsOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * High-performance WebSocket mesh network implementation of [FlashNetwork].
 *
 * Provides:
 * - Full-duplex persistent RFC 6455 WebSockets between all connected peers.
 * - Works identically over Wi-Fi Routers (via mDNS discovery) and Mobile Hotspots (via gateway/probe).
 * - Instant disconnect detection via socket FIN/RST and WebSocket ping/pong keepalives.
 * - Unified text (chat/signaling) and binary (chunked file transfer) transport.
 */
class WsFlashNetwork(
    private val context: Context?,
    private val localDeviceId: String,
    private val localFriendlyName: String,
    private val tlsOptions: TlsOptions? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : FlashNetwork, EndpointMemory, WsConnection.Listener {

    private val running = AtomicBoolean(false)
    private var server: WsTransferServer? = null
    private val client = WsTransferClient(context, this, tlsOptions)

    private val knownEndpoints = ConcurrentHashMap<String, Endpoint>()
    private val sessionsById = ConcurrentHashMap<FlashDeviceId, WsSession>()
    private val sessionByConnection = ConcurrentHashMap<WsConnection, WsSession>()
    private val pendingHandshakes = ConcurrentHashMap<WsConnection, CompletableDeferred<FlashDevice>>()

    private data class Endpoint(val host: String, val port: Int)

    private val _networkState = MutableStateFlow(FlashNetworkState())
    override val networkState: StateFlow<FlashNetworkState> = _networkState.asStateFlow()

    private val _activeSessions = MutableStateFlow<Map<FlashDeviceId, FlashSession>>(emptyMap())
    override val activeSessions: StateFlow<Map<FlashDeviceId, FlashSession>> = _activeSessions.asStateFlow()

    private val _connectionHealth = MutableStateFlow(FlashConnectionHealth.Offline)
    override val connectionHealth: StateFlow<FlashConnectionHealth> = _connectionHealth.asStateFlow()

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override suspend fun start(listenPort: Int): FlashResult<Int> = withContext(Dispatchers.IO) {
        if (running.get()) return@withContext FlashResult.Success(server?.listenPort ?: 0)
        running.set(true)

        val serverImpl = WsTransferServer(
            connectionListener = this@WsFlashNetwork,
            onConnection = { connection ->
                handleInboundConnection(connection)
            },
            tls = tlsOptions,
        )
        server = serverImpl
        val port = runCatching { serverImpl.start() }.getOrElse {
            running.set(false)
            return@withContext FlashResult.Failure(FlashError.NetworkUnavailable("WS server start failed: ${it.message}"))
        }

        _connectionHealth.value = FlashConnectionHealth.Connected
        refreshState()
        FlashResult.Success(port)
    }

    override suspend fun stop(): FlashResult<Unit> = withContext(Dispatchers.IO) {
        running.set(false)
        server?.stop()
        server = null

        sessionsById.values.forEach { it.disconnect("Network stopped") }
        sessionsById.clear()
        sessionByConnection.clear()
        pendingHandshakes.clear()

        _activeSessions.value = emptyMap()
        _connectionHealth.value = FlashConnectionHealth.Offline
        refreshState()
        FlashResult.Success(Unit)
    }

    // ------------------------------------------------------------------
    // Endpoint Memory (Discovery binding)
    // ------------------------------------------------------------------

    override fun rememberEndpoint(deviceId: String, host: String, port: Int) {
        knownEndpoints[deviceId] = Endpoint(host, port)
    }

    // ------------------------------------------------------------------
    // Connect / Disconnect
    // ------------------------------------------------------------------

    override suspend fun connect(device: FlashDevice): FlashResult<FlashSession> {
        val endpoint = knownEndpoints[device.id.value]
            ?: return FlashResult.Failure(FlashError.PeerUnavailable(device.id.value, "No remembered endpoint"))
        return connectManual(endpoint.host, endpoint.port)
    }

    override suspend fun connectManual(host: String, port: Int): FlashResult<FlashSession> = withContext(Dispatchers.IO) {
        if (!running.get()) {
            return@withContext FlashResult.Failure(FlashError.NetworkUnavailable("Network not started"))
        }

        val actualPort = if (port > 0) port else WsTransferServer.PREFERRED_PORT

        val connection = runCatching {
            client.connect(host, actualPort)
        }.getOrElse { error ->
            return@withContext FlashResult.Failure(FlashError.PeerUnavailable(host, error.message ?: "WS connect failed"))
        }

        val handshakeWaiter = CompletableDeferred<FlashDevice>()
        pendingHandshakes[connection] = handshakeWaiter
        connection.start()

        // Send local HELLO handshake
        val helloMsg = FlashTextFraming.encodeFields(
            HELLO_PREFIX,
            "version" to PROTOCOL_VERSION.toString(),
            "deviceId" to localDeviceId,
            "name" to localFriendlyName,
        )
        connection.sendText(helloMsg)

        // Wait for peer HELLO response
        val peerDevice = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
            handshakeWaiter.await()
        }

        pendingHandshakes.remove(connection)

        if (peerDevice == null) {
            connection.close("Handshake timeout")
            return@withContext FlashResult.Failure(FlashError.ConnectionTimeout(HANDSHAKE_TIMEOUT_MS, "WS handshake timed out"))
        }

        val session = WsSession(connection, peerDevice) { s, _ ->
            onSessionDisconnected(s)
        }

        registerSession(session)
        FlashResult.Success(session)
    }

    override suspend fun disconnect(deviceId: FlashDeviceId): FlashResult<Unit> = withContext(Dispatchers.IO) {
        val session = sessionsById.remove(deviceId)
            ?: return@withContext FlashResult.Failure(FlashError.PeerUnavailable(deviceId.value, "Session not active"))
        sessionByConnection.remove(session.connection)
        session.disconnect("Local disconnect")
        refreshState()
        FlashResult.Success(Unit)
    }

    // ------------------------------------------------------------------
    // Inbound Handshake & Session Registration
    // ------------------------------------------------------------------

    private fun handleInboundConnection(connection: WsConnection) {
        val handshakeWaiter = CompletableDeferred<FlashDevice>()
        pendingHandshakes[connection] = handshakeWaiter
        connection.start()

        scope.launch {
            val peerDevice = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                handshakeWaiter.await()
            }
            pendingHandshakes.remove(connection)

            if (peerDevice == null) {
                connection.close("Inbound handshake timeout")
                return@launch
            }

            // Reply with local HELLO
            val helloReply = FlashTextFraming.encodeFields(
                HELLO_PREFIX,
                "version" to PROTOCOL_VERSION.toString(),
                "deviceId" to localDeviceId,
                "name" to localFriendlyName,
            )
            connection.sendText(helloReply)

            val session = WsSession(connection, peerDevice) { s, _ ->
                onSessionDisconnected(s)
            }
            registerSession(session)
        }
    }

    private fun registerSession(session: WsSession) {
        sessionsById[session.peerDeviceId] = session
        sessionByConnection[session.connection] = session
        _activeSessions.value = sessionsById.toMap()
        refreshState()
    }

    private fun onSessionDisconnected(session: WsSession) {
        sessionsById.remove(session.peerDeviceId)
        sessionByConnection.remove(session.connection)
        _activeSessions.value = sessionsById.toMap()
        refreshState()
    }

    private fun refreshState() {
        val activeCount = sessionsById.size
        _networkState.update {
            it.copy(
                isRunning = server?.isRunning == true,
                localPort = server?.listenPort ?: 0,
                activePeerCount = activeCount,
            )
        }
    }

    // ------------------------------------------------------------------
    // WsConnection.Listener implementation
    // ------------------------------------------------------------------

    override fun onTextMessage(connection: WsConnection, text: String) {
        val session = sessionByConnection[connection]
        if (session != null) {
            session.onTextReceived(text)
            return
        }

        // Check if this is an incoming HELLO handshake
        val parsedFields = FlashTextFraming.parseFields(text, HELLO_PREFIX)
        if (parsedFields != null) {
            val peerDeviceId = parsedFields["deviceId"] ?: "unknown"
            val peerName = parsedFields["name"] ?: "Peer"
            val peerVersion = parsedFields["version"]?.toIntOrNull() ?: 1

            val peerDevice = FlashDevice(
                id = FlashDeviceId(peerDeviceId),
                friendlyName = peerName,
                transportType = FlashTransportType.LAN,
                presence = FlashPeerPresence.Online,
                protocolVersion = peerVersion,
            )

            pendingHandshakes[connection]?.complete(peerDevice)
        }
    }

    override fun onBinaryMessage(connection: WsConnection, data: ByteArray) {
        val session = sessionByConnection[connection]
        session?.onBinaryReceived(data)
    }

    override fun onConnectionClosed(connection: WsConnection, reason: String) {
        pendingHandshakes.remove(connection)?.completeExceptionally(Exception("Closed: $reason"))
        val session = sessionByConnection.remove(connection)
        if (session != null) {
            session.onClosed(reason)
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 2
        private const val HELLO_PREFIX = "FLASH_WS_HELLO"
        private const val HANDSHAKE_TIMEOUT_MS = 6_000L
    }
}
