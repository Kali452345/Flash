package com.transfer.flash.debug

import android.content.Context
import android.util.Log
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.core.messaging.RealFlashChatRepository
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.network.bridge.DiscoveryRouteBinder
import com.transfer.flash.core.network.ws.WsFlashNetwork
import com.transfer.flash.core.network.ws.WsSession
import com.transfer.flash.core.transfer.FlashTransferRepository
import com.transfer.flash.core.transfer.RealFlashTransferRepository
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ReceiveEvent
import com.transfer.flash.core.transfer.chunked.ReceivePipeline
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.identity.AppIdentity
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Process-wide engine holder for the debug Dev Console and background service.
 *
 * Utilizes [WsFlashNetwork] as the full-duplex WebSocket mesh network layer,
 * wired to [RealFlashChatRepository] for instant messaging and [RealFlashTransferRepository]
 * with [ReceivePipeline] for chunked binary file transfers.
 */
object DiscoveryEngineHolder {

    private const val TAG_DISCOVERY = "DISCOVERY"
    private const val TAG_TRANSFER = "TRANSFER"
    private const val TAG_CHAT = "CHAT"
    private const val TAG_WS = "WS"

    @Volatile
    private var composite: CompositeDiscovery? = null

    @Volatile
    private var network: WsFlashNetwork? = null

    @Volatile
    private var transferRepo: FlashTransferRepository? = null

    @Volatile
    private var chatRepo: FlashChatRepository? = null

    private var binderJob: Job? = null

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun current(): CompositeDiscovery? = composite

    fun currentNetwork(): FlashNetwork? = network

    fun currentTransfers(): FlashTransferRepository? = transferRepo

    fun currentChats(): FlashChatRepository? = chatRepo

    suspend fun ensureStarted(context: Context): CompositeDiscovery {
        composite?.let { return it }
        val appContext = context.applicationContext
        val identity0 = AppIdentity(appContext)
        val identity = FlashAdvertisedIdentity(
            deviceId = FlashDeviceId(
                value = identity0.deviceId.ifBlank { UUID.randomUUID().toString() },
            ),
            friendlyName = identity0.friendlyName.ifBlank { "Flash Device" },
            deviceModel = android.os.Build.MODEL ?: "unknown",
            protocolVersion = 2,
        )

        Log.i(TAG_DISCOVERY, "Starting Flash discovery with deviceId=${identity.deviceId.value} friendlyName=${identity.friendlyName}")

        val transport = com.transfer.flash.core.discovery.nsd.NsdTransport(
            context = appContext,
            apiLevel = com.transfer.flash.core.discovery.nsd.BuildNsdApiLevel,
            directory = com.transfer.flash.core.discovery.core.StandardEndpointDirectory(),
            sweep = { _ -> emptyList() },
        )
        val engine = CompositeDiscovery(transports = listOf(transport))

        val networkImpl = WsFlashNetwork(
            context = appContext,
            localDeviceId = identity.deviceId.value,
            localFriendlyName = identity.friendlyName,
        )
        binderJob = DiscoveryRouteBinder.observe(appScope, engine.discoveredEndpoints, networkImpl)

        // Start WebSocket network server
        val netStartResult = networkImpl.start(0)
        val serverPort = (netStartResult as? com.transfer.flash.core.common.result.FlashResult.Success)?.value ?: 0
        check(serverPort > 0) { "Network server failed to start: ${(netStartResult as? com.transfer.flash.core.common.result.FlashResult.Failure)?.error}" }

        Log.i(TAG_WS, "WsFlashNetwork server listening on port=$serverPort")

        engine.setMode(FlashDiscoveryMode.STANDARD)
        val result = engine.startAll(serverPort, identity)
        check(result.isSuccess) {
            "Discovery startAll failed: ${(result as? com.transfer.flash.core.common.result.FlashResult.Failure)?.error}"
        }

        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            appContext,
            com.transfer.flash.core.persistence.db.FlashDatabase::class.java,
        ).build()

        val transferImpl = RealFlashTransferRepository(
            streamChannelFactory = { channelId ->
                object : StreamChannel {
                    override val id: Int = channelId
                    override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                        val session = networkImpl.activeSessions.value.values.firstOrNull() as? WsSession
                        return if (session != null) {
                            val ok = session.connection.sendBinary(frameBytes)
                            Log.d(TAG_TRANSFER, "StreamChannel[$channelId] sent ${frameBytes.size} bytes -> $ok")
                            ok
                        } else {
                            Log.w(TAG_TRANSFER, "StreamChannel[$channelId] no active WebSocket session available")
                            false
                        }
                    }
                }
            },
            fileSourceOpener = { uriString ->
                if (uriString.startsWith("content://") || uriString.startsWith("file://")) {
                    runCatching {
                        appContext.contentResolver.openInputStream(android.net.Uri.parse(uriString))
                    }.getOrNull() ?: java.io.ByteArrayInputStream(ByteArray(1024 * 1024 * 10))
                } else {
                    java.io.ByteArrayInputStream(ByteArray(1024 * 1024 * 10))
                }
            },
            transferDao = db.transferDao(),
            transferChunkDao = db.transferChunkDao(),
        )

        val chatImpl = RealFlashChatRepository(
            localDeviceId = identity.deviceId.value,
            localDisplayName = identity.friendlyName,
            conversationDao = db.conversationDao(),
            messageDao = db.messageDao(),
            outboxDao = db.outboxDao(),
            receiptDao = db.receiptDao(),
            draftDao = db.draftDao(),
            recentSearchDao = db.recentSearchDao(),
            transportSink = { targetDeviceId, wireFrame ->
                val session = networkImpl.activeSessions.value[FlashDeviceId(targetDeviceId)] as? WsSession
                if (session != null) {
                    val frameText = when (wireFrame) {
                        is MessageWireFrame.TextMessage ->
                            "MSG:${wireFrame.localId}:${wireFrame.conversationId}:${wireFrame.senderId}:${wireFrame.senderName}:${wireFrame.sentAt}:${wireFrame.text}"
                        is MessageWireFrame.DeliveryReceipt ->
                            "ACK:${wireFrame.messageId}:${wireFrame.conversationId}:${wireFrame.memberId}:${wireFrame.deliveredAt}"
                        else -> "RAW:${wireFrame}"
                    }
                    val sent = session.connection.sendText(frameText)
                    Log.i(TAG_CHAT, "Dispatched chat wireFrame to $targetDeviceId: $frameText (success=$sent)")
                    sent
                } else {
                    Log.w(TAG_CHAT, "Failed to dispatch chat wireFrame: no active session for $targetDeviceId")
                    false
                }
            },
        )

        // Setup inbound file receiver pipeline
        val receivedDir = File(appContext.getExternalFilesDir(null), "FlashReceived").apply { mkdirs() }
        val openFileStreams = ConcurrentHashMap<String, FileOutputStream>()

        val receivePipeline = ReceivePipeline(
            sink = { index, chunkBytes ->
                val activeFile = File(receivedDir, "received_payload.bin")
                FileOutputStream(activeFile, true).use { fos ->
                    fos.write(chunkBytes)
                    fos.flush()
                }
                Log.d(TAG_TRANSFER, "Receiver sink wrote chunk $index (${chunkBytes.size} bytes)")
            },
        )

        // Observe active WebSocket sessions for incoming chat messages and binary file chunks
        appScope.launch {
            networkImpl.activeSessions.collect { sessions ->
                Log.i(TAG_WS, "Active sessions updated: count=${sessions.size} peers=${sessions.keys.map { it.value }}")
                sessions.values.forEach { session ->
                    if (session is WsSession) {
                        appScope.launch {
                            session.incomingText.collect { text ->
                                Log.i(TAG_CHAT, "Received text frame: $text")
                                if (text.startsWith("MSG:")) {
                                    val parts = text.split(":", limit = 7)
                                    if (parts.size >= 7) {
                                        chatImpl.onInboundWireFrame(
                                            MessageWireFrame.TextMessage(
                                                localId = parts[1],
                                                conversationId = parts[2],
                                                senderId = parts[3],
                                                senderName = parts[4],
                                                sentAt = parts[5].toLongOrNull() ?: System.currentTimeMillis(),
                                                text = parts[6],
                                            ),
                                        )
                                    }
                                } else if (text.startsWith("ACK:")) {
                                    val parts = text.split(":", limit = 5)
                                    if (parts.size >= 5) {
                                        chatImpl.onInboundWireFrame(
                                            MessageWireFrame.DeliveryReceipt(
                                                messageId = parts[1],
                                                conversationId = parts[2],
                                                memberId = parts[3],
                                                deliveredAt = parts[4].toLongOrNull() ?: System.currentTimeMillis(),
                                            ),
                                        )
                                    }
                                }
                            }
                        }

                        appScope.launch {
                            session.incomingBinary.collect { binaryData ->
                                Log.d(TAG_TRANSFER, "Received binary frame: ${binaryData.size} bytes")
                                // 1. First route to active senders (ACKs or COMPLETE from receiver)
                                val handledBySender = transferImpl.onInboundFrame(binaryData)
                                if (handledBySender) {
                                    Log.d(TAG_TRANSFER, "Inbound binary frame consumed by sender dispatcher")
                                    return@collect
                                }

                                // 2. If not consumed by sender, route to receiver pipeline
                                val events = receivePipeline.onFrame(binaryData)
                                for (event in events) {
                                    when (event) {
                                        is ReceiveEvent.AckBatchReady -> {
                                            Log.d(TAG_TRANSFER, "Receiver emitting ACK batch with ${event.frame.indexes.size} indexes")
                                            val ackBytes = ChunkFrame.serialize(event.frame)
                                            session.connection.sendBinary(ackBytes)
                                        }
                                        is ReceiveEvent.Completed -> {
                                            Log.i(TAG_TRANSFER, "Receiver completed file transfer: transferId=${event.frame.transferId} verified=${event.frame.verified}")
                                            val completeBytes = ChunkFrame.serialize(event.frame)
                                            session.connection.sendBinary(completeBytes)
                                        }
                                        is ReceiveEvent.Rejected -> {
                                            Log.w(TAG_TRANSFER, "Receiver rejected chunk frame: reason=${event.reason} transferId=${event.transferId}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val shouldStopNetwork = synchronized(this) {
            if (composite == null) {
                composite = engine
                network = networkImpl
                transferRepo = transferImpl
                chatRepo = chatImpl
                false
            } else {
                true
            }
        }
        if (shouldStopNetwork) {
            networkImpl.stop()
        }

        // Auto-start foreground service so screen-off or background doesn't kill the server
        runCatching { FlashBackgroundService.start(appContext) }

        return composite!!
    }

    /** Stops advertising/browsing, sessions, and releases the ephemeral port. Idempotent. */
    suspend fun stopAll() {
        val currentEngine: CompositeDiscovery?
        val currentNetwork: WsFlashNetwork?
        synchronized(this) {
            currentEngine = composite
            currentNetwork = network
            composite = null
            network = null
            transferRepo = null
            chatRepo = null
        }
        binderJob?.cancel()
        binderJob = null
        currentEngine?.stopAll()
        currentNetwork?.stop()
    }
}
