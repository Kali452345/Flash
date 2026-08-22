package com.transfer.flash.core.security.trust

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult

/**
 * Persistent store for trusted/paired peer devices.
 * Tracks peers that have completed visual verification or pairing handshakes.
 */
interface FlashTrustStore {
    /** Returns true if [deviceId] is registered as a trusted/paired peer. */
    fun isTrusted(deviceId: FlashDeviceId): Boolean

    /** Convenience overload checking trust by raw string device ID. */
    fun isTrusted(deviceId: String): Boolean = isTrusted(FlashDeviceId(deviceId))

    /** Persists [deviceId] with its known [friendlyName] as a trusted peer. */
    fun trustPeer(deviceId: FlashDeviceId, friendlyName: String): FlashResult<Unit>

    /** Convenience overload trusting peer by raw string device ID. */
    fun trustPeer(deviceId: String, friendlyName: String): FlashResult<Unit> =
        trustPeer(FlashDeviceId(deviceId), friendlyName)

    /** Revokes trust from [deviceId], requiring a new pairing step on next connection. */
    fun revokeTrust(deviceId: FlashDeviceId): FlashResult<Unit>

    /** Convenience overload revoking trust by raw string device ID. */
    fun revokeTrust(deviceId: String): FlashResult<Unit> = revokeTrust(FlashDeviceId(deviceId))

    /** Returns all currently trusted peers mapped by device ID to friendly name. */
    fun getTrustedPeers(): Map<FlashDeviceId, String>
}
