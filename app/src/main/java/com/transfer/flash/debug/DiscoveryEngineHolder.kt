package com.transfer.flash.debug

import android.content.Context
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.DiscoveryModePolicy
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.network.DefaultFlashNetwork
import com.transfer.flash.core.network.bridge.DiscoveryRouteBinder
import com.transfer.flash.identity.AppIdentity
import java.net.ServerSocket
import java.util.UUID

/**
 * Process-wide engine holder for the debug Dev Console and background service
 * (P3.5/D-M4 + E; extended in option 2 with the real network layer).
 *
 * Provisional wiring: the durable engine facade lands in C7 (`:core:engine`).
 *
 * Owns a throwaway [ServerSocket] on an ephemeral port so NSD advertises a
 * real, connectable endpoint. Also constructs [DefaultFlashNetwork] and binds
 * discovery endpoints into its route memory via [DiscoveryRouteBinder] so the
 * console can connect to discovered peers by deviceId.
 */
object DiscoveryEngineHolder {

    @Volatile
    private var composite: CompositeDiscovery? = null

    @Volatile
    private var network: DefaultFlashNetwork? = null

    @Volatile
    private var transferRepo: com.transfer.flash.core.transfer.FlashTransferRepository? = null

    @Volatile
    private var chatRepo: com.transfer.flash.core.messaging.FlashChatRepository? = null

    private var serverSocket: ServerSocket? = null
    private var binderJob: kotlinx.coroutines.Job? = null

    fun current(): CompositeDiscovery? = composite

    fun currentNetwork(): DefaultFlashNetwork? = network

    fun currentTransfers(): com.transfer.flash.core.transfer.FlashTransferRepository? = transferRepo

    fun currentChats(): com.transfer.flash.core.messaging.FlashChatRepository? = chatRepo

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

        val transport = com.transfer.flash.core.discovery.nsd.NsdTransport(
            context = appContext,
            apiLevel = com.transfer.flash.core.discovery.nsd.BuildNsdApiLevel,
            directory = com.transfer.flash.core.discovery.core.StandardEndpointDirectory(),
            sweep = { _ -> emptyList() },
        )
        val engine = CompositeDiscovery(transports = listOf(transport))

        val networkImpl = DefaultFlashNetwork(
            context = appContext,
            localDeviceId = identity.deviceId.value,
            localFriendlyName = identity.friendlyName,
            reconnectPolicy = com.transfer.flash.core.network.resilience.ReconnectPolicy(
                random01 = { kotlin.random.Random.nextDouble() },
            ),
        )
        binderJob = DiscoveryRouteBinder.observe(appScope, engine.discoveredEndpoints, networkImpl)

        // Start the network server FIRST on an ephemeral port, so we know the EXACT port to advertise
        val netStartResult = networkImpl.start(0)
        val serverPort = (netStartResult as? com.transfer.flash.core.common.result.FlashResult.Success)?.value ?: 0
        check(serverPort > 0) { "Network server failed to start: ${(netStartResult as? com.transfer.flash.core.common.result.FlashResult.Failure)?.error}" }

        engine.setMode(FlashDiscoveryMode.STANDARD)
        val result = engine.startAll(serverPort, identity)
        check(result.isSuccess) {
            "Discovery startAll failed: ${(result as? com.transfer.flash.core.common.result.FlashResult.Failure)?.error}"
        }

        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            appContext,
            com.transfer.flash.core.persistence.db.FlashDatabase::class.java,
        ).build()

        val transferImpl = com.transfer.flash.core.transfer.RealFlashTransferRepository(
            streamChannelFactory = { channelId ->
                object : com.transfer.flash.core.transfer.multistream.StreamChannel {
                    override val id: Int = channelId
                    override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                        // In real test transfer, simulate network latency of chunks or push through active session
                        kotlinx.coroutines.delay(10)
                        return true
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

        val chatImpl = com.transfer.flash.core.messaging.RealFlashChatRepository(
            localDeviceId = identity.deviceId.value,
            localDisplayName = identity.friendlyName,
            conversationDao = db.conversationDao(),
            messageDao = db.messageDao(),
            outboxDao = db.outboxDao(),
            receiptDao = db.receiptDao(),
            draftDao = db.draftDao(),
            recentSearchDao = db.recentSearchDao(),
        )

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

    private val appScope by lazy {
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        )
    }

    /** Stops advertising/browsing, sessions, and releases the ephemeral port. Idempotent. */
    suspend fun stopAll() {
        val currentEngine: CompositeDiscovery?
        val currentNetwork: DefaultFlashNetwork?
        var socket: ServerSocket? = null
        synchronized(this) {
            currentEngine = composite
            currentNetwork = network
            composite = null
            network = null
            transferRepo = null
            chatRepo = null
            socket = serverSocket
            serverSocket = null
        }
        binderJob?.cancel()
        binderJob = null
        currentEngine?.stopAll()
        currentNetwork?.stop()
        runCatching { socket?.close() }
    }
}
