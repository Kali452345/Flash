package com.transfer.flash.core.discovery.core

import com.transfer.flash.core.common.model.FlashDeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtCodecTest {

    private val identity = FlashAdvertisedIdentity(
        deviceId = FlashDeviceId("dev-abc-123"),
        friendlyName = "Pixel Flash",
        deviceModel = "Pixel 9",
        protocolVersion = 3,
    )

    @Test
    fun encode_producesContractKeySet() {
        val attrs = TxtCodec.encode(identity)
        assertEquals(
            setOf("device_id", "name", "model", "proto"),
            attrs.keys,
        )
        assertEquals("dev-abc-123", attrs["device_id"])
        assertEquals("Pixel Flash", attrs["name"])
        assertEquals("Pixel 9", attrs["model"])
        assertEquals("3", attrs["proto"])
    }

    @Test
    fun roundtrip_encodeThenDecode_preservesIdentity() {
        val decoded = TxtCodec.decode(TxtCodec.encode(identity))
        assertEquals(identity, decoded)
    }

    @Test
    fun decode_missingDeviceId_returnsNull() {
        assertNull(TxtCodec.decode(mapOf("name" to "x", "proto" to "1")))
    }

    @Test
    fun decode_blankDeviceId_returnsNull() {
        assertNull(TxtCodec.decode(mapOf("device_id" to "   ", "proto" to "1")))
    }

    @Test
    fun decode_missingOrInvalidProto_returnsNullWithoutThrowing() {
        assertNull(TxtCodec.decode(mapOf("device_id" to "d1")))
        assertNull(TxtCodec.decode(mapOf("device_id" to "d1", "proto" to "not-a-number")))
        assertNull(TxtCodec.decode(mapOf("device_id" to "d1", "proto" to "")))
    }

    @Test
    fun decode_trimsWhitespaceAroundValues() {
        val decoded = TxtCodec.decode(
            mapOf(
                "device_id" to " dev-1 ",
                "name" to " My Phone ",
                "model" to " Model X ",
                "proto" to " 2 ",
            ),
        )
        assertEquals("dev-1", decoded?.deviceId?.value)
        assertEquals("My Phone", decoded?.friendlyName)
        assertEquals("Model X", decoded?.deviceModel)
        assertEquals(2, decoded?.protocolVersion)
    }

    @Test
    fun decode_ignoresUnknownKeys_forwardCompatible() {
        val decoded = TxtCodec.decode(
            mapOf(
                "device_id" to "d1",
                "proto" to "1",
                "future_key" to "whatever",
            ),
        )
        assertTrue(decoded != null)
        assertEquals("", decoded?.friendlyName)
        assertEquals("", decoded?.deviceModel)
    }
}
