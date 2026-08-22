@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.tcp

import android.util.Log
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.FlashConnectionState
import com.transfer.flash.core.network.FlashSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class LanSession(
    private val socket: Socket,
    private val reader: BufferedReader,
    private val writer: PrintWriter,
    private val localDeviceId: String,
    private val localFriendlyName: String,
    val peerInfo: LanProbeHello,
    private val onDisconnected: (LanProbeHello, String) -> Unit,
) : FlashSession {

    constructor(
        socket: Socket,
        reader: BufferedReader,
        writer: PrintWriter,
        localDeviceId: String,
        localFriendlyName: String,
        peer: LanProbeHello,
        onDisconnected: (LanProbeHello, String) -> Unit,
        @Suppress("UNUSED_PARAMETER") marker: Unit = Unit,
    ) : this(socket, reader, writer, localDeviceId, localFriendlyName, peer, onDisconnected)

    override val peerDeviceId: FlashDeviceId = FlashDeviceId(peerInfo.deviceId)

    override val peer: FlashDevice = FlashDevice(
        id = peerDeviceId,
        friendlyName = peerInfo.friendlyName,
        transportType = FlashTransportType.LAN,
        presence = FlashPeerPresence.Online,
        protocolVersion = peerInfo.protocolVersion,
    )

    override val transportType: FlashTransportType = FlashTransportType.LAN

    private val _connectionState = MutableStateFlow(FlashConnectionState.Connected)
    override val connectionState: StateFlow<FlashConnectionState> = _connectionState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private var readJob: Job? = null
    private var heartbeatJob: Job? = null

    fun start() {
        if (closed.get()) return
        readJob = scope.launch { readLoop() }
        heartbeatJob = scope.launch { heartbeatLoop() }
    }

    override suspend fun send(message: ByteArray): FlashResult<Unit> = withContext(Dispatchers.IO) {
        if (closed.get()) return@withContext FlashResult.Failure(FlashError.PeerUnavailable(peerDeviceId.value, "Session closed"))
        return@withContext runCatching {
            val text = String(message, Charsets.UTF_8)
            writer.println(text)
            if (writer.checkError()) throw java.io.IOException("Socket write checkError failed")
            FlashResult.Success(Unit)
        }.getOrElse { error ->
            close("Write failed: ${error.message}")
            FlashResult.Failure(FlashError.TransferFailed("session", error.message ?: "Write failed"))
        }
    }

    override fun disconnect(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        _connectionState.value = FlashConnectionState.Disconnecting
        runCatching {
            writer.println(LanProbeMessages.disconnect(PROTOCOL_VERSION, localDeviceId, localFriendlyName))
        }
        closeSocket()
        _connectionState.value = FlashConnectionState.Disconnected
        onDisconnected(peerInfo, reason)
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        closeSocket()
        _connectionState.value = FlashConnectionState.Disconnected
        onDisconnected(peerInfo, reason)
    }

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        try {
            while (!closed.get() && scope.isActive) {
                val line = reader.readLine() ?: break
                when {
                    LanProbeMessages.parsePing(line) != null -> {
                        writer.println(LanProbeMessages.pong(PROTOCOL_VERSION, localDeviceId, localFriendlyName))
                    }

                    LanProbeMessages.parsePong(line) != null -> {
                        Log.d(TAG, "LAN session heartbeat peerId=${peerInfo.deviceId}")
                    }

                    LanProbeMessages.parseDisconnect(line) != null -> {
                        close("${peerInfo.friendlyName} disconnected")
                        return@withContext
                    }

                    else -> Log.w(TAG, "LAN session ignored unknown message from peerId=${peerInfo.deviceId}")
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                Log.w(TAG, "LAN session read failed peerId=${peerInfo.deviceId}", error)
            }
        } finally {
            if (!closed.get()) {
                close("${peerInfo.friendlyName} disconnected")
            }
        }
    }

    private suspend fun heartbeatLoop() = withContext(Dispatchers.IO) {
        while (!closed.get() && scope.isActive) {
            delay(HEARTBEAT_INTERVAL_MS)
            runCatching {
                writer.println(LanProbeMessages.ping(PROTOCOL_VERSION, localDeviceId, localFriendlyName))
                if (writer.checkError()) error("Heartbeat write failed")
            }.onFailure { error ->
                if (!closed.get()) {
                    Log.w(TAG, "LAN session heartbeat failed peerId=${peerInfo.deviceId}", error)
                    close("${peerInfo.friendlyName} disconnected")
                }
            }
        }
    }

    private fun closeSocket() {
        readJob?.cancel()
        heartbeatJob?.cancel()
        runCatching { socket.close() }
    }

    companion object {
        fun create(
            socket: Socket,
            localDeviceId: String,
            localFriendlyName: String,
            peer: LanProbeHello,
            onDisconnected: (LanProbeHello, String) -> Unit,
        ): LanSession {
            return LanSession(
                socket = socket,
                reader = BufferedReader(InputStreamReader(socket.getInputStream())),
                writer = PrintWriter(socket.getOutputStream(), true),
                localDeviceId = localDeviceId,
                localFriendlyName = localFriendlyName,
                peerInfo = peer,
                onDisconnected = onDisconnected,
            )
        }

        const val PROTOCOL_VERSION = 1
        private const val TAG = "LAN"
        private const val HEARTBEAT_INTERVAL_MS = 3_000L
    }
}
