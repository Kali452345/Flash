package com.transfer.flash.core.discovery.core

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import kotlinx.coroutines.flow.Flow

/**
 * Identity payload a device advertises about itself (plan C3.2).
 * Carried in radio-specific form: NSD TXT records today; BLE adv payload /
 * Wi-Fi Aware publish extras later. [deviceModel] feeds the Nearby tab rows;
 * [protocolVersion] lets peers hide incompatible versions pre-connection.
 */
data class FlashAdvertisedIdentity(
    val deviceId: FlashDeviceId,
    val friendlyName: String,
    val deviceModel: String,
    val protocolVersion: Int,
)

/**
 * Radio-agnostic discovery events (plan C3.1 target abstraction).
 *
 * Semantics every implementation MUST honor:
 * - [Found] exactly once per peer per browsing session, then [Updated] on any
 *   address/name/version change.
 * - [Lost] when the radio reports loss AND when the presence sweeper ages an
 *   endpoint out (plan C3.5) — consumers must treat both identically.
 * - Events are hot flows; subscribers see only live traffic (no replay).
 */
sealed interface FlashTransportEvent {
    data class Found(val endpoint: FlashDiscoveredEndpoint) : FlashTransportEvent
    data class Updated(val endpoint: FlashDiscoveredEndpoint) : FlashTransportEvent
    data class Lost(val deviceId: FlashDeviceId, val serviceName: String?) : FlashTransportEvent
    data class StateChanged(val browsing: Boolean, val message: String) : FlashTransportEvent
}

/**
 * Seam behind which every radio transport lives (plan C3.1). One implementation
 * per medium: NSD/LAN now (see `nsd` package), Wi-Fi Direct / Wi-Fi Aware /
 * BLE-presence later (plan C3.6-C3.8). Implementations own their radio lifecycle
 * but MUST NOT hold UI types.
 */
interface FlashRadioTransport {
    /** Transport identifier used by [CompositeDiscovery] for priority/dedup reporting. */
    val transportName: String

    val events: Flow<FlashTransportEvent>

    /**
     * Advertise this device using its own identity details until [stop].
     * Idempotent: re-invoking while advertising updates the advertised record.
     */
    suspend fun startAdvertising(port: Int, identity: FlashAdvertisedIdentity): FlashResult<Unit>

    /**
     * Browse CONTINUOUSLY until [stop]: peers already present appear within
     * seconds; peers joining later appear without any caller action; internal
     * start failures restart with capped retries (plan C3.3).
     */
    suspend fun startBrowsing(): FlashResult<Unit>

    /** Stops advertising and browsing and releases radio resources. Idempotent. */
    suspend fun stop(): FlashResult<Unit>
}
