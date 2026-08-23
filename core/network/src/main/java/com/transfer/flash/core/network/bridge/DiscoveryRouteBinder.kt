package com.transfer.flash.core.network.bridge

import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Sink side of the C3→C4 seam: endpoint memory (implemented by DefaultFlashNetwork). */
fun interface EndpointMemory {
    fun rememberEndpoint(deviceId: String, host: String, port: Int)
}

/**
 * C3→C4 seam (plan §3 A12 / C4): binds discovered endpoints into the network
 * layer's route memory so `connect(device)` resolves host/port by deviceId.
 *
 * Pure plumbing, JVM-testable: flows/scope injected.
 */
object DiscoveryRouteBinder {

    /** Binds one snapshot immediately (also used per-emission by [observe]). */
    fun bindAll(memory: EndpointMemory, endpoints: List<FlashDiscoveredEndpoint>) {
        endpoints.forEach { ep -> memory.rememberEndpoint(ep.deviceId.value, ep.hostAddress, ep.port) }
    }

    /**
     * Keeps [memory] updated for as long as [scope] lives. Conflation-safe:
     * every emission re-binds the FULL snapshot, so missed intermediate states
     * never leave stale routes behind (routes are additive; Lost handling is
     * the network layer's session concern).
     */
    fun observe(
        scope: kotlinx.coroutines.CoroutineScope,
        endpoints: StateFlow<List<FlashDiscoveredEndpoint>>,
        memory: EndpointMemory,
    ): Job = scope.launch {
        endpoints.collect { bindAll(memory, it) }
    }
}
