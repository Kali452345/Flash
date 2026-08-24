package com.transfer.flash.core.engine

import com.transfer.flash.core.discovery.FlashDiscovery
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.persistence.settings.FlashSettingsDataStore
import com.transfer.flash.core.security.trust.FlashTrustStore
import com.transfer.flash.core.transfer.FlashTransferRepository

/**
 * Top-level facade contract unifying all Flash core subsystems (C7.0 / ADR-010).
 * Exposes clean domain repository and service accessors for application ViewModels and UI layers.
 */
interface FlashEngine {
    /** Real messaging repository managing chats, outbox, read receipts, and drafts. */
    val chats: FlashChatRepository

    /** Multi-stream chunked file transfer repository and progress telemetry. */
    val transfers: FlashTransferRepository

    /** Continuous local peer discovery and service advertising across radios. */
    val discovery: FlashDiscovery

    /** Network connection manager, active sessions, and resilience engine. */
    val network: FlashNetwork

    /** Cryptographic trust store tracking verified and paired devices. */
    val trustStore: FlashTrustStore

    /** Persistent user preferences and configuration data store. */
    val settings: FlashSettingsDataStore
}

/**
 * Standard implementation of [FlashEngine] aggregating the domain subsystems.
 */
class DefaultFlashEngine(
    override val chats: FlashChatRepository,
    override val transfers: FlashTransferRepository,
    override val discovery: FlashDiscovery,
    override val network: FlashNetwork,
    override val trustStore: FlashTrustStore,
    override val settings: FlashSettingsDataStore,
) : FlashEngine
