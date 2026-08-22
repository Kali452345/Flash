package com.transfer.flash.core.network.ws

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens an outbound WebSocket connection to a peer's [WsTransferServer].
 * Routes through the active Wi-Fi/Ethernet network like `LanConnectionProbe`,
 * so the socket lands on the same LAN interface as the peer.
 */
class WsTransferClient(
    context: Context,
    private val connectionListener: WsConnection.Listener,
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun connect(host: String, port: Int): WsConnection = withContext(Dispatchers.IO) {
        val network = findLanNetwork()
        Log.i(
            TAG,
            "WS connecting address=$host:$port network=${network?.networkHandle ?: "default"}",
        )
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val key = WebSocketCodec.newClientKey()
            val request = buildString {
                append("GET /flash-ws HTTP/1.1\r\n")
                append("Host: ").append(host).append(':').append(port).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val (statusLine, headers) =
                WebSocketCodec.parseHeaders(WebSocketCodec.readHttpHeaderBlock(socket.getInputStream()))
            if (!statusLine.contains(" 101")) {
                throw IOException("WebSocket upgrade refused: $statusLine")
            }
            val accept = headers["sec-websocket-accept"]
                ?: throw IOException("Missing Sec-WebSocket-Accept header")
            if (accept != WebSocketCodec.acceptKey(key)) {
                throw IOException("Bad Sec-WebSocket-Accept header")
            }
            socket.soTimeout = 0
            WsConnection(socket, maskOutboundFrames = true, remoteLabel = "$host:$port", listener = connectionListener)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun findLanNetwork(): Network? {
        return connectivityManager.allNetworks.firstOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }
    }

    companion object {
        const val TAG = "WS"
        const val CONNECT_TIMEOUT_MS = 4_000
        const val HANDSHAKE_TIMEOUT_MS = 8_000
    }
}
