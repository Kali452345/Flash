package com.transfer.flash.wstransfer

import android.content.Context
import com.transfer.flash.core.security.trust.AndroidPreferencesTrustStore
import com.transfer.flash.core.security.trust.FlashTrustStore

/**
 * Remembers devices that completed a WebSocket pairing (hello exchange), so a
 * rediscovered peer is recognized immediately and can reconnect/re-share without
 * a fresh manual pairing step.
 *
 * Delegates to [AndroidPreferencesTrustStore].
 */
class WsPairingStore(
    context: Context,
    private val store: FlashTrustStore = AndroidPreferencesTrustStore(context),
) {
    fun isPaired(deviceId: String): Boolean = store.isTrusted(deviceId)

    fun markPaired(deviceId: String, friendlyName: String) {
        store.trustPeer(deviceId, friendlyName)
    }

    fun unpair(deviceId: String) {
        store.revokeTrust(deviceId)
    }
}
