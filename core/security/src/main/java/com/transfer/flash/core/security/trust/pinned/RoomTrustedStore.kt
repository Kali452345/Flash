package com.transfer.flash.core.security.trust.pinned

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.persistence.db.dao.TrustedPeerDao
import com.transfer.flash.core.persistence.db.entity.TrustedPeerEntity
import com.transfer.flash.core.security.trust.FlashTrustStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/** Snapshot row of a trusted peer as exposed by [RoomTrustedStore.trustedPeers]. */
data class FlashTrustedPeer(
    val deviceId: String,
    val name: String,
    val fingerprintHex: String,
    val trustedAt: Long,
)

/**
 * Room-backed trust/pin store (C2.4), implementing the legacy [FlashTrustStore]
 * interface ADDITIVELY (R4) plus the new fingerprint-pinning API.
 *
 * Follows the Android data-layer pattern of injecting the DAO through the
 * constructor, exposing `suspend` one-shot operations and `Flow` observable queries
 * (https://developer.android.com/topic/architecture/data-layer — "a Room data
 * source would receive either a data access object (DAO) or the database itself
 * as a parameter"). No internal [kotlinx.coroutines.CoroutineScope]: the store owns
 * no coroutines; lifecycle belongs to the caller (engine wiring in C7).
 *
 * ### Prefer the suspend/Flow API
 *
 * The synchronous [FlashTrustStore] methods are bridged with [runBlocking] purely
 * for backward compatibility with existing callers (R4). New code MUST use:
 * - [pin] / [isPinned] / [revoke] suspend operations
 * - [trustedPeers] observable reads
 *
 * Pin semantics: [isPinned] matches device id AND fingerprint, so a peer presenting
 * a different key fails closed (see [TofuPolicy]). DAO/Room behavior itself is
 * covered by the :core:persistence invariant suite; this class stays thin
 * (one-line delegations) so no separate Room instrumentation tests are required.
 *
 * NOTE (concurrent-agent boundary): fingerprints fed into this store are produced
 * by C2.1/C2.3 (`...core.security.crypto` / identity keying); this class treats
 * them as opaque strings.
 */
class RoomTrustedStore(
    private val dao: TrustedPeerDao,
    private val timeSource: FlashTimeSource = SystemTimeSource,
) : FlashTrustStore {

    // ---------------------------------------------------------------------
    // New pinning API (C2.4) — preferred surface.
    // ---------------------------------------------------------------------

    /** Persists/refreshes the pin binding `(deviceId, fingerprintHex)`. */
    suspend fun pin(deviceId: String, name: String, fingerprintHex: String): FlashResult<Unit> =
        try {
            dao.insert(
                TrustedPeerEntity(
                    deviceId = deviceId,
                    name = name,
                    fingerprintHex = fingerprintHex,
                    trustedAt = timeSource.nowMs(),
                ),
            )
            FlashResult.Success(Unit)
        } catch (t: Throwable) {
            FlashResult.Failure(FlashError.Unknown("Failed to pin device $deviceId", t))
        }

    /** True only when THIS device is pinned to THIS exact fingerprint (fail closed). */
    suspend fun isPinned(deviceId: String, fingerprintHex: String): Boolean =
        dao.isPinned(deviceId, fingerprintHex)

    /** Removes the pin; the peer must re-pair before being trusted again. */
    suspend fun revoke(deviceId: String): FlashResult<Unit> =
        try {
            dao.revoke(deviceId)
            FlashResult.Success(Unit)
        } catch (t: Throwable) {
            FlashResult.Failure(FlashError.Unknown("Failed to revoke device $deviceId", t))
        }

    /** Observable trusted-peer list, newest first (small table; no paging needed). */
    fun trustedPeers(): Flow<List<FlashTrustedPeer>> =
        dao.observeAll().map { rows ->
            rows.map { FlashTrustedPeer(it.deviceId, it.name, it.fingerprintHex, it.trustedAt) }
        }

    /**
     * One-time migration from a legacy [FlashTrustStore] (SharedPreferences-backed).
     * Idempotent: devices already present in Room are skipped — safe to call on every
     * startup until the legacy prefs are retired. Legacy rows carry
     * [TofuPolicy.LEGACY_UNBOUND_FINGERPRINT] (see [LegacyTrustMigration] for why a
     * legacy trust flag must never silently become a crypto pin).
     */
    suspend fun importFrom(preferencesStore: FlashTrustStore): FlashResult<Int> =
        try {
            val existing = dao.observeAll().first().mapTo(mutableSetOf()) { it.deviceId }
            val rows = LegacyTrustMigration.computeMigrations(
                existingRoomDeviceIds = existing,
                legacyPeers = preferencesStore.getTrustedPeers(),
                nowMs = timeSource.nowMs(),
            )
            rows.forEach { peer ->
                dao.insert(
                    TrustedPeerEntity(
                        deviceId = peer.deviceId,
                        name = peer.name,
                        fingerprintHex = peer.fingerprintHex,
                        trustedAt = peer.trustedAt,
                    ),
                )
            }
            FlashResult.Success(rows.size)
        } catch (t: Throwable) {
            FlashResult.Failure(FlashError.Unknown("Trust migration failed", t))
        }

    // ---------------------------------------------------------------------
    // FlashTrustStore bridge (R4 compatibility). runBlocking — see class KDoc.
    // ---------------------------------------------------------------------

    override fun isTrusted(deviceId: FlashDeviceId): Boolean = runBlocking {
        dao.observeAll().first().any { it.deviceId == deviceId.value }
    }

    override fun trustPeer(deviceId: FlashDeviceId, friendlyName: String): FlashResult<Unit> =
        runBlocking {
            // Legacy callers have no fingerprint yet: insert an unbound row that
            // TofuPolicy will re-prompt on first contact.
            pin(deviceId.value, friendlyName, TofuPolicy.LEGACY_UNBOUND_FINGERPRINT)
        }

    override fun revokeTrust(deviceId: FlashDeviceId): FlashResult<Unit> =
        runBlocking { revoke(deviceId.value) }

    override fun getTrustedPeers(): Map<FlashDeviceId, String> = runBlocking {
        dao.observeAll().first().associate { FlashDeviceId(it.deviceId) to it.name }
    }
}
