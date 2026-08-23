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

    private var serverSocket: ServerSocket? = null
    private var binderJob: kotlinx.coroutines.Job? = null

    fun current(): CompositeDiscovery? = composite

    fun currentNetwork(): DefaultFlashNetwork? = network

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

        val socket = ServerSocket(0).also { serverSocket = it }
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

        engine.setMode(FlashDiscoveryMode.STANDARD)
        val result = engine.startAll(socket.localPort, identity)
        check(result.isSuccess) {
            "Discovery startAll failed: ${(result as? com.transfer.flash.core.common.result.FlashResult.Failure)?.error}"
        }

        val alreadyRunning = synchronized(this) {
            if (composite == null) {
                composite = engine
                network = networkImpl
                false
            } else {
                true
            }
        }
        if (alreadyRunning) {
            networkImpl.stop()
        } else {
            // Network server listens too (independent port) so peers can reach us.
            networkImpl.start()
        }
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
