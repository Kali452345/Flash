package com.transfer.flash.debug

import android.content.Context
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.DiscoveryModePolicy
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.discovery.nsd.NsdTransport
import com.transfer.flash.identity.AppIdentity
import java.net.ServerSocket
import java.util.UUID

/**
 * Process-wide discovery engine holder for the debug Dev Console and the
 * background service (P3.5/D-M4 + E). Provisional wiring: the real engine
 * facade lands in C7 (`:core:engine`) — this holder is explicitly a bridge,
 * NOT the long-term construction point.
 *
 * Owns a throwaway [ServerSocket] on an ephemeral port so NSD advertises a
 * real, connectable endpoint; C4/C5 will replace it with the actual transfer
 * listener.
 */
object DiscoveryEngineHolder {

    @Volatile
    private var composite: CompositeDiscovery? = null

    private var serverSocket: ServerSocket? = null

    fun current(): CompositeDiscovery? = composite

    /**
     * Returns the running engine, creating it (and starting STANDARD-mode
     * advertise+browse) on first use. Identity: persisted app identity for
     * device id/name, Build.MODEL as model, a stable-per-install UUID suffix
     * keeps device ids unique across emulator farms sharing a name.
     */
    suspend fun ensureStarted(context: Context): CompositeDiscovery {
        composite?.let { return it }
        val engine: CompositeDiscovery
        val socket: ServerSocket
        val identity: FlashAdvertisedIdentity
        synchronized(this) {
            composite?.let { return it }
            socket = ServerSocket(0).also { serverSocket = it }
            val identity0 = AppIdentity(context.applicationContext)
            identity = FlashAdvertisedIdentity(
                deviceId = FlashDeviceId(
                    value = identity0.deviceId.ifBlank { UUID.randomUUID().toString() },
                ),
                friendlyName = identity0.friendlyName.ifBlank { "Flash Device" },
                deviceModel = android.os.Build.MODEL ?: "unknown",
                protocolVersion = 2,
            )
            val transport = NsdTransport(
                context = context.applicationContext,
                apiLevel = com.transfer.flash.core.discovery.nsd.BuildNsdApiLevel,
                directory = com.transfer.flash.core.discovery.core.StandardEndpointDirectory(),
                sweep = { _ -> emptyList() },
            )
            engine = CompositeDiscovery(transports = listOf(transport))
        }
        // Suspend work stays OUTSIDE the critical section.
        engine.setMode(FlashDiscoveryMode.STANDARD)
        val result = engine.startAll(socket.localPort, identity)
        check(result.isSuccess) {
            "Discovery startAll failed: ${(result as? com.transfer.flash.core.common.result.FlashResult.Failure)?.error}"
        }
        synchronized(this) { composite = engine }
        return engine
    }

    /** Stops advertising/browsing and releases the ephemeral port. Idempotent. */
    suspend fun stopAll() {
        val current: CompositeDiscovery?
        var socket: ServerSocket? = null
        synchronized(this) {
            current = composite
            composite = null
            socket = serverSocket
            serverSocket = null
        }
        current?.stopAll()
        runCatching { socket?.close() }
    }
}
