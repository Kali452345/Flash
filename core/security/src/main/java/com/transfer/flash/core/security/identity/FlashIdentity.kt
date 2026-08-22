package com.transfer.flash.core.security.identity

import com.transfer.flash.core.common.model.FlashDeviceId

/**
 * Represents the persistent local device identity used across network discovery,
 * pairing, and transfer sessions.
 */
data class FlashIdentity(
    val deviceId: FlashDeviceId,
    val friendlyName: String,
)
