package com.transfer.flash.core.network.tcp

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashTextFraming

data class LanProbeHello(
    val protocolVersion: Int,
    val deviceId: String,
    val friendlyName: String,
)

@OptIn(FlashInternalApi::class)
@FlashInternalApi
object LanProbeMessages {
    const val HELLO_PREFIX = "FLASH_HELLO"
    const val OK_PREFIX = "FLASH_OK"
    const val DISCONNECT_PREFIX = "FLASH_DISCONNECT"
    const val PING_PREFIX = "FLASH_PING"
    const val PONG_PREFIX = "FLASH_PONG"

    fun hello(protocolVersion: Int, deviceId: String, friendlyName: String): String {
        return FlashTextFraming.encodeFields(
            HELLO_PREFIX,
            "version" to protocolVersion.toString(),
            "deviceId" to deviceId,
            "name" to friendlyName,
        )
    }

    fun ok(protocolVersion: Int, deviceId: String, friendlyName: String): String {
        return FlashTextFraming.encodeFields(
            OK_PREFIX,
            "version" to protocolVersion.toString(),
            "deviceId" to deviceId,
            "name" to friendlyName,
        )
    }

    fun disconnect(protocolVersion: Int, deviceId: String, friendlyName: String): String {
        return FlashTextFraming.encodeFields(
            DISCONNECT_PREFIX,
            "version" to protocolVersion.toString(),
            "deviceId" to deviceId,
            "name" to friendlyName,
        )
    }

    fun ping(protocolVersion: Int, deviceId: String, friendlyName: String): String {
        return FlashTextFraming.encodeFields(
            PING_PREFIX,
            "version" to protocolVersion.toString(),
            "deviceId" to deviceId,
            "name" to friendlyName,
        )
    }

    fun pong(protocolVersion: Int, deviceId: String, friendlyName: String): String {
        return FlashTextFraming.encodeFields(
            PONG_PREFIX,
            "version" to protocolVersion.toString(),
            "deviceId" to deviceId,
            "name" to friendlyName,
        )
    }

    fun parseHello(line: String): LanProbeHello? = parse(line, HELLO_PREFIX)

    fun parseOk(line: String): LanProbeHello? = parse(line, OK_PREFIX)

    fun parseDisconnect(line: String): LanProbeHello? = parse(line, DISCONNECT_PREFIX)

    fun parsePing(line: String): LanProbeHello? = parse(line, PING_PREFIX)

    fun parsePong(line: String): LanProbeHello? = parse(line, PONG_PREFIX)

    private fun parse(line: String, prefix: String): LanProbeHello? {
        val fields = FlashTextFraming.parseFields(line, prefix) ?: return null
        val version = fields["version"]?.toIntOrNull() ?: return null
        val deviceId = fields["deviceId"].orEmpty()
        val name = fields["name"].orEmpty()
        if (deviceId.isBlank() || name.isBlank()) return null

        return LanProbeHello(
            protocolVersion = version,
            deviceId = deviceId,
            friendlyName = name,
        )
    }
}
