package com.transfer.flash.core.network.ws

import android.util.Log
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Accepts inbound WebSocket upgrade requests for the experimental transfer track.
 * Prefers the stable port [PREFERRED_PORT] and falls back to a dynamic port when
 * it is busy — same approach as the LAN probe server.
 */
class WsTransferServer(
    private val connectionListener: WsConnection.Listener,
    private val onConnection: (WsConnection) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    var listenPort: Int = 0
        private set

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    @Synchronized
    fun start(): Int {
        if (isRunning) return listenPort
        val socket = runCatching { ServerSocket(PREFERRED_PORT) }.getOrElse { ServerSocket(0) }
        serverSocket = socket
        listenPort = socket.localPort
        acceptJob = scope.launch { acceptLoop(socket) }
        Log.i(TAG, "WS transfer server listening on port $listenPort")
        return listenPort
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        listenPort = 0
    }

    private suspend fun acceptLoop(socket: ServerSocket) = withContext(Dispatchers.IO) {
        while (isActive && !socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrElse { break }
            launch {
                runCatching { handshake(client) }
                    .onFailure { error ->
                        Log.d(TAG, "WS handshake rejected (${error.message ?: error::class.java.simpleName})")
                        runCatching { client.close() }
                    }
            }
        }
    }

    private fun handshake(socket: Socket) {
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val input = socket.getInputStream()
        val (requestLine, headers) = WebSocketCodec.parseHeaders(WebSocketCodec.readHttpHeaderBlock(input))
        val upgrade = headers["upgrade"]?.contains("websocket", ignoreCase = true) == true
        val key = headers["sec-websocket-key"]
        if (!requestLine.startsWith("GET ") || !upgrade || key.isNullOrBlank()) {
            throw IOException("Not a WebSocket upgrade request")
        }
        val response = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: ").append(WebSocketCodec.acceptKey(key)).append("\r\n")
            append("\r\n")
        }
        socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().flush()
        socket.soTimeout = 0
        val label = "${socket.inetAddress?.hostAddress ?: "?"}:${socket.port}"
        onConnection(WsConnection(socket, maskOutboundFrames = false, remoteLabel = label, listener = connectionListener))
    }

    companion object {
        const val PREFERRED_PORT = 45822
        private const val TAG = "WS"
        private const val HANDSHAKE_TIMEOUT_MS = 8_000
    }
}
