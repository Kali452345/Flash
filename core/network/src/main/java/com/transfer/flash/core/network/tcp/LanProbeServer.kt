@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.tcp

import android.util.Log
import com.transfer.flash.core.common.annotation.FlashInternalApi
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LanProbeServer(
    private val deviceId: String,
    private val friendlyName: String,
    private val onPeerProbed: (LanProbeHello, String, LanSession) -> Unit = { _, _, _ -> },
    private val onPeerDisconnected: (LanProbeHello) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    suspend fun start(): Int = withContext(Dispatchers.IO) {
        if (running.get()) return@withContext port

        val socket = createServerSocket().apply {
            reuseAddress = true
            soTimeout = ACCEPT_TIMEOUT_MS
        }
        serverSocket = socket
        running.set(true)
        acceptJob = scope.launch {
            acceptLoop(socket)
        }
        Log.i(TAG, "LAN probe server listening on port=${socket.localPort}")
        socket.localPort
    }

    fun stop() {
        running.set(false)
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (running.get() && scope.isActive) {
            val client = try {
                socket.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (error: Exception) {
                if (running.get()) {
                    Log.w(TAG, "LAN probe accept failed", error)
                }
                break
            }

            scope.launch {
                handleClient(client)
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        val client = socket
        try {
            client.soTimeout = READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = PrintWriter(client.getOutputStream(), true)
            val line = reader.readLine()
            val disconnect = line?.let(LanProbeMessages::parseDisconnect)
            if (disconnect != null) {
                onPeerDisconnected(disconnect)
                Log.i(TAG, "LAN peer disconnected peerId=${disconnect.deviceId} peerName=${disconnect.friendlyName}")
                client.close()
                return@withContext
            }

            val hello = line?.let(LanProbeMessages::parseHello)
            if (hello == null) {
                Log.w(TAG, "LAN probe rejected malformed hello from ${client.inetAddress.hostAddress}")
                client.close()
                return@withContext
            }
            writer.println(LanProbeMessages.ok(PROTOCOL_VERSION, deviceId, friendlyName))
            val session = LanSession(
                socket = client,
                reader = reader,
                writer = writer,
                localDeviceId = deviceId,
                localFriendlyName = friendlyName,
                peerInfo = hello,
                onDisconnected = { peer, _ -> onPeerDisconnected(peer) },
            ).also { it.start() }
            onPeerProbed(hello, client.inetAddress.hostAddress.orEmpty(), session)
            Log.i(TAG, "LAN probe completed peerId=${hello.deviceId} peerName=${hello.friendlyName}")
        } catch (error: Exception) {
            Log.w(TAG, "LAN probe client handling failed", error)
            runCatching { client.close() }
        }
    }

    private fun createServerSocket(): ServerSocket {
        return runCatching { ServerSocket(DEFAULT_PORT) }
            .onFailure { error ->
                Log.w(TAG, "Default LAN probe port=$DEFAULT_PORT unavailable, falling back to dynamic port", error)
            }
            .getOrElse { ServerSocket(0) }
    }

    companion object {
        const val TAG = "LAN"
        const val PROTOCOL_VERSION = 1
        const val DEFAULT_PORT = 45821
        const val ACCEPT_TIMEOUT_MS = 1_000
        const val READ_TIMEOUT_MS = 3_000
    }
}
