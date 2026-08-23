package com.transfer.flash.core.transfer.wslegacy

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.security.trust.FlashTrustStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit test for the relocated [WsPairingStore] delegation logic.
 * Possible because C5.1 moved Context out of the constructor (store is injected),
 * so no Android runtime is required.
 */
class WsPairingStoreTest {

    private class FakeTrustStore : FlashTrustStore {
        val trusted = mutableMapOf<FlashDeviceId, String>()
        var revoked = mutableListOf<FlashDeviceId>()

        override fun isTrusted(deviceId: FlashDeviceId): Boolean = trusted.containsKey(deviceId)

        override fun trustPeer(deviceId: FlashDeviceId, friendlyName: String): FlashResult<Unit> {
            trusted[deviceId] = friendlyName
            return FlashResult.Success(Unit)
        }

        override fun revokeTrust(deviceId: FlashDeviceId): FlashResult<Unit> {
            if (trusted.remove(deviceId) != null) revoked += deviceId
            return FlashResult.Success(Unit)
        }

        override fun getTrustedPeers(): Map<FlashDeviceId, String> = trusted.toMap()
    }

    @Test
    fun markPaired_then_isPaired_is_true_and_name_reaches_store() {
        val store = FakeTrustStore()
        val pairing = WsPairingStore(store)

        pairing.markPaired("device-a", "Pixel A")

        assertTrue(pairing.isPaired("device-a"))
        assertEquals("Pixel A", store.trusted[FlashDeviceId("device-a")])
    }

    @Test
    fun unpaired_device_reports_not_paired() {
        val pairing = WsPairingStore(FakeTrustStore())

        assertFalse(pairing.isPaired("device-b"))
    }

    @Test
    fun unpair_revokes_trust_in_store() {
        val store = FakeTrustStore()
        val pairing = WsPairingStore(store)
        pairing.markPaired("device-c", "Pixel C")

        pairing.unpair("device-c")

        assertFalse(pairing.isPaired("device-c"))
        assertEquals(listOf(FlashDeviceId("device-c")), store.revoked)
    }
}
