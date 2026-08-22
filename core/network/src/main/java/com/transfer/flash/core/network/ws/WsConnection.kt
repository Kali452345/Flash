package com.transfer.flash.core.network.ws

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One live WebSocket connection after the HTTP upgrade handshake completed.
 *
 * Reads run on a Dispatchers.IO coroutine; writes are serialized through a lock
 * so file chunks and control frames from different threads can never interleave.
 * Listener callbacks fire on the read thread — implementations must be thread-safe.
 */
class WsConnection(
    private val socket: Socket,
    private val maskOutboundFrames: Boolean,
    val remoteLabel: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onTextMessage(connection: WsConnection, text: String)
        fun onBinaryMessage(connection: WsConnection, data: ByteArray)
        fun onConnectionClosed(connection: WsConnection, reason: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    val isOpen: Boolean
        get() = !closed.get()

    fun start() {
        scope.launch { readLoop() }
    }

    fun sendText(text: String): Boolean {
        return send(WebSocketCodec.OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))
    }

    /** Fire-and-forget text send that is safe to call from any thread (including main). */
    fun sendTextAsync(text: String) {
        scope.launch { sendText(text) }
    }

    fun sendBinary(data: ByteArray): Boolean {
        return send(WebSocketCodec.OPCODE_BINARY, data)
    }

    /**
     * Closes the connection. The close frame and socket close run on the connection scope
     * so this is safe to call from any thread (StrictMode forbids network writes on main).
     * The listener callback fires immediately; the peer observes the close frame or EOF.
     */
    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        scope.launch {
            runCatching {
                synchronized(writeLock) {
                    WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_CLOSE, ByteArray(0), maskOutboundFrames)
                }
            }
            runCatching { socket.close() }
            scope.cancel()
        }
        listener.onConnectionClosed(this, reason)
    }

    private fun send(opcode: Int, payload: ByteArray): Boolean {
        if (closed.get()) return false
        return runCatching {
            synchronized(writeLock) {
                WebSocketCodec.writeFrame(output, opcode, payload, maskOutboundFrames)
            }
        }.onFailure { error ->
            if (!closed.get()) {
                Log.w(TAG, "WS write failed remote=$remoteLabel", error)
                close("Write failed")
            }
        }.isSuccess
    }

    private suspend fun readLoop() {
        try {
            while (!closed.get() && scope.isActive) {
                when (val message = WebSocketCodec.readMessage(input)) {
                    is WebSocketCodec.Message.Text -> listener.onTextMessage(this, message.text)
                    is WebSocketCodec.Message.Binary -> listener.onBinaryMessage(this, message.data)
                    is WebSocketCodec.Message.Ping -> send(WebSocketCodec.OPCODE_PONG, message.payload)
                    is WebSocketCodec.Message.Pong -> Unit
                    is WebSocketCodec.Message.Close -> {
                        close(if (message.reason.isNotBlank()) message.reason else "Peer closed connection (${message.code})")
                        return
                    }
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                Log.d(TAG, "WS read loop ended remote=$remoteLabel (${error.message ?: error::class.java.simpleName})")
                close("Connection error: ${error.message ?: error::class.java.simpleName}")
            }
        } finally {
            if (!closed.get()) {
                close("EOF")
            }
        }
    }

    companion object {
        private const val TAG = "WS"
    }
}
