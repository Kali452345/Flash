package com.transfer.flash.core.discovery.core

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint

/**
 * Pure, radio-agnostic endpoint bookkeeping (plan C3.3 + C3.5).
 *
 * Responsibilities:
 * - dedup by [FlashDeviceId] across repeated radio callbacks,
 * - track `firstSeenAt` / `lastSeenAt` per endpoint,
 * - classify each applied radio event as Found / Updated (diff output),
 * - age endpoints out: [sweepExpired] returns peers whose staleness exceeds the
 *   grace window so callers emit Lost identically to radio-reported loss.
 *
 * NOT thread-safe by itself: owners guard it (single dispatcher or lock) and map
 * returned diffs onto event flows. Fully JVM-testable; no Android types.
 */
interface EndpointDirectory {
    data class Entry(
        val endpoint: FlashDiscoveredEndpoint,
        val firstSeenAtMs: Long,
        val lastSeenAtMs: Long,
    )

    sealed interface Diff {
        data class Found(val entry: Entry) : Diff
        data class Updated(val entry: Entry, val previous: Entry) : Diff
        data class Lost(val deviceId: FlashDeviceId) : Diff
        data object Unchanged : Diff
    }

    /** Applies a radio sighting; Found for new peers, Updated on any field change. */
    fun applySeen(endpoint: FlashDiscoveredEndpoint, nowMs: Long): Diff

    /** Removes a peer explicitly (radio goodbye); Unchanged when unknown. */
    fun applyLost(deviceId: FlashDeviceId): Diff

    /** Ages out stale entries; one [Diff.Lost] per aged-out peer. */
    fun sweepExpired(graceWindowMs: Long, nowMs: Long): List<Diff.Lost>

    fun snapshot(): List<Entry>

    fun get(deviceId: FlashDeviceId): Entry?
}
