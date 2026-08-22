package com.transfer.flash.core.discovery

data class FlashDiscoveryState(
    val isDiscovering: Boolean = false,
    val isAdvertising: Boolean = false,
    val advertisedPort: Int = 0,
    val statusMessage: String = "Idle",
)
