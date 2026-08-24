package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.FlashConnectionState
import com.transfer.flash.core.network.FlashSession
import com.transfer.flash.core.network.FrameAck
import com.transfer.flash.core.network.FrameAckStage
import java.util.UUID
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FlashSession implementation backed by an active RFC 6455 [WsConnection].
 *
 * Supports:
 * - Direct UTF-8 text messages (JSON chat frames / control signaling)
 * - Raw binary messages (file chunk frames)
 * - Automatic Keepalive via WebSocket ping/pong
 * - Instant disconnect event forwarding
 */
class WsSession(
    val connection: WsConnection,
    override val peer: FlashDevice,
    private val onDisconnected: (WsSession, String) -> Unit = { _, _ -> },
) : FlashSession {

    override val peerDeviceId: FlashDeviceId = peer.id
    override val transportType: FlashTransportType = FlashTransportType.LAN

    private val _connectionState = MutableStateFlow(FlashConnectionState.Connected)
    override val connectionState: StateFlow<FlashConnectionState> = _connectionState.asStateFlow()

    private val _frameAcks = MutableSharedFlow<FrameAck>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val frameAcks: SharedFlow<FrameAck> = _frameAcks.asSharedFlow()

    private val _incomingText = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val incomingText: SharedFlow<String> = _incomingText.asSharedFlow()

    private val _incomingBinary = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val incomingBinary: SharedFlow<ByteArray> = _incomingBinary.asSharedFlow()

    override suspend fun send(message: ByteArray): FlashResult<Unit> {
        val ok = connection.sendBinary(message)
        return if (ok) {
            _frameAcks.tryEmit(FrameAck(UUID.randomUUID().toString(), FrameAckStage.SocketWritten, System.currentTimeMillis()))
            FlashResult.Success(Unit)
        } else {
            FlashResult.Failure(FlashError.TransferFailed(peerDeviceId.value, "WebSocket sendBinary failed"))
        }
    }

    override suspend fun sendText(text: String): FlashResult<Unit> {
        val ok = connection.sendText(text)
        return if (ok) {
            _frameAcks.tryEmit(FrameAck(UUID.randomUUID().toString(), FrameAckStage.SocketWritten, System.currentTimeMillis()))
            FlashResult.Success(Unit)
        } else {
            FlashResult.Failure(FlashError.TransferFailed(peerDeviceId.value, "WebSocket sendText failed"))
        }
    }

    override fun disconnect(reason: String) {
        _connectionState.value = FlashConnectionState.Disconnected
        connection.close(reason)
        onDisconnected(this, reason)
    }

    fun onTextReceived(text: String) {
        _incomingText.tryEmit(text)
    }

    fun onBinaryReceived(data: ByteArray) {
        _incomingBinary.tryEmit(data)
    }

    fun onClosed(reason: String) {
        _connectionState.value = FlashConnectionState.Disconnected
        onDisconnected(this, reason)
    }
}
