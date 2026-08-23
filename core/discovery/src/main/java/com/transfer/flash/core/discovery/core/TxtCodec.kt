package com.transfer.flash.core.discovery.core

import com.transfer.flash.core.common.model.FlashDeviceId

/**
 * The cross-radio TXT-record contract (plan C3.2).
 *
 * Every radio transport (NSD/LAN today; Wi-Fi Direct, Wi-Fi Aware, BLE later)
 * MUST carry its advertised identity using exactly this key set so any radio
 * can decode any peer's advertisement without radio-specific knowledge:
 *
 * | Key         | Meaning                                | Required |
 * |-------------|----------------------------------------|----------|
 * | `device_id` | Stable unique device id                | yes      |
 * | `name`      | User-facing friendly name              | no       |
 * | `model`     | Device model string (UI row subtitle)  | no       |
 * | `proto`     | Flash protocol version (decimal int)   | yes      |
 *
 * Unknown extra keys are ignored (forward compatibility). Values are trimmed;
 * [decode] returns null when `device_id` is missing/blank or when `proto`
 * cannot be parsed as an Int (never throws on hostile input).
 */
object TxtCodec {

    const val KEY_DEVICE_ID = "device_id"
    const val KEY_NAME = "name"
    const val KEY_MODEL = "model"
    const val KEY_PROTO = "proto"

    /** Encodes an identity into transport-agnostic TXT attributes. */
    fun encode(identity: FlashAdvertisedIdentity): Map<String, String> = mapOf(
        KEY_DEVICE_ID to identity.deviceId.value.trim(),
        KEY_NAME to identity.friendlyName.trim(),
        KEY_MODEL to identity.deviceModel.trim(),
        KEY_PROTO to identity.protocolVersion.toString(),
    )

    /**
     * Decodes TXT attributes into an identity.
     * Returns null when `device_id` is missing/blank or `proto` is not a valid
     * integer; missing optional keys default to empty strings.
     */
    fun decode(attrs: Map<String, String>): FlashAdvertisedIdentity? {
        val deviceId = attrs[KEY_DEVICE_ID]?.trim().orEmpty()
        if (deviceId.isBlank()) return null
        val protocolVersion = attrs[KEY_PROTO]?.trim()?.toIntOrNull() ?: return null
        return FlashAdvertisedIdentity(
            deviceId = FlashDeviceId(deviceId),
            friendlyName = attrs[KEY_NAME]?.trim().orEmpty(),
            deviceModel = attrs[KEY_MODEL]?.trim().orEmpty(),
            protocolVersion = protocolVersion,
        )
    }
}
