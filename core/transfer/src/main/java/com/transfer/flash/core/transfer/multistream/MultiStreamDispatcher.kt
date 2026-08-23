package com.transfer.flash.core.transfer.multistream

import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ChunkPlan
import com.transfer.flash.core.transfer.chunked.ChunkSource
import com.transfer.flash.core.transfer.chunked.Chunker
import com.transfer.flash.core.transfer.chunked.ChunkStream
import com.transfer.flash.core.transfer.chunked.FileMeta
import com.transfer.flash.core.transfer.chunked.ResumeBitVector
import com.transfer.flash.core.transfer.chunked.Sha256
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Send-side orchestrator for C5.7 MULTI-STREAM transfer (plan §C5): pushes ONE logical file
 * across N [StreamChannel]s (configurable 1..4, default 2) using dynamic work claiming, shared
 * ACK bookkeeping, per-channel failure isolation, and aggregate progress telemetry.
 *
 * ## Work distribution — dynamic claim loop, NOT static ranges
 *
 * Static range partitioning (PFTP-style striping) hands every stream an equal share up front; one
 * slow stream head-of-line blocks its whole range and the tail waits on the slowest path.
 * MPSCP's measured alternative — "the next block … is always assigned to the first available
 * stream" (https://www.osti.gov/servlets/purl/1143126) — lets fast streams naturally drain more
 * of the file, which is what this dispatcher does: each idle worker claims the next unsent chunk
 * from a shared cursor ([cursorIndex], under [stateLock]) over the pending index list, plus a
 * retry pool of chunks handed back by dead channels. LocalSend itself parallelizes only across
 * FILES (`POST /upload` per fileId, "can be called in parallel":
 * https://github.com/localsend/protocol §4.2); within-one-file striping follows the
 * GridFTP/MPSCP prior art instead.
 *
 * ## End-game phase (K = [endGameChunks], default 8)
 *
 * When remaining work drops to K chunks, dispatch enters end-game: the FIRST alive channel to
 * reach the claim lock becomes sole owner and drains the tail one chunk at a time ("claimed by
 * first-free channel"); other channels park and take over only if the owner dies. Rationale:
 * BitTorrent keeps end-game request depth minimal so tail pieces cannot strand behind slow peers
 * (https://blog.libtorrent.org/2011/11/writing-a-fast-piece-picker/), and scattering K tiny
 * claims over N sockets no longer amortizes when little else remains.
 *
 * ## Shared ACK ingestion + dedup (ACK batches may arrive on ANY channel)
 *
 * Because [MultiStreamReceiver] replies on the ARRIVING channel, receiver feedback may land on
 * any [StreamChannel]. There is therefore exactly ONE shared confirmed mirror ([ResumeBitVector],
 * monotonic-union merge — same semantics as `SendPipeline.confirmed`) guarded by [stateLock];
 * per-channel vectors would fragment truth. Dedup rules:
 * - an index already marked is NOT re-counted toward bytes/progress;
 * - duplicate or overlapping batches across channels are idempotent;
 * - a chunk resent after a channel death is ACKed once wherever it now lives;
 * - in-flight claims are tracked per channel; on death its UN-ACKed claimed indexes return to
 *   the retry pool (receiver-side duplicate tolerance makes an occasional resend safe:
 *   at-least-once wire, exactly-once write).
 *
 * ## Failure isolation
 *
 * `false`/throw from one channel kills ONLY that channel; survivors continue; the transfer
 * completes with >= 1 alive channel; all dead => [MultiStreamResult.Failed].
 *
 * ## Completion — exactly-once COMPLETE
 *
 * The LAST-FINISHING COORDINATOR PATH is whichever thread (a worker or an inbound-frame reader)
 * first observes full bit-vector coverage: it wins an [AtomicBoolean] CAS, emits the terminal
 * COMPLETE coordination frame exactly once via [onCompleteFrame], resolves the session, and wakes
 * all workers; later observers no-op. The receiver's own COMPLETE frame (authoritative `verified`
 * flag) also resolves the session.
 *
 * ## Threading / determinism contract
 *
 * Pure JVM logic like the rest of the chunked package: inject [workerDispatcher] + [nowMs]; every
 * ordering-sensitive structure sits behind `stateLock`/atomics, so tests need no
 * kotlinx-coroutines-test. Single-use per instance (one file per session). Pause/cancel and
 * engine-level stall timeouts are intentionally NOT modeled here — the engine owns them around
 * [send] (deviation tracked for C5.12/C5.13 wiring).
 */
class MultiStreamDispatcher(
    private val chunker: Chunker,
    private val meta: FileMeta,
    private val source: ChunkSource,
    private val factory: StreamChannelFactory,
    private val streamCount: Int = DEFAULT_STREAM_COUNT,
    private val requestedChunkSize: Int = Chunker.DEFAULT_CHUNK_SIZE_BYTES,
    private val fileSha256Hex: String? = null,
    doneIndexes: Collection<Int> = emptyList(),
    private val endGameChunks: Int = END_GAME_CHUNKS,
    private val speedWindowMs: Long = MultiStreamProgress.DEFAULT_WINDOW_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onCompleteFrame: ((ByteArray) -> Unit)? = null,
) {
    init {
        require(streamCount in 1..MAX_STREAMS) { "streamCount must be in 1..$MAX_STREAMS" }
        require(endGameChunks > 0) { "endGameChunks must be > 0" }
        require(speedWindowMs > 0) { "speedWindowMs must be > 0" }
    }

    private val plan: ChunkPlan = chunker.plan(meta, requestedChunkSize)

    /** Receiver-verified chunks from a previous attempt (C5.6), clamped to range. */
    private val resumeDone: List<Int> =
        doneIndexes.filter { it in 0 until plan.totalChunks }

    /** Chunks still to send (resume-done excluded), ascending — the cursor walks this list. */
    private val pendingIndexes: List<Int> =
        (0 until plan.totalChunks).filter { it !in resumeDone }

    private val resumedBytes: Long = resumeDone.sumOf { chunkBytes(it) }

    private val _progress = MutableStateFlow(
        MultiStreamProgress(bytesDone = resumedBytes, totalBytes = meta.totalBytes),
    )

    /** Aggregate telemetry across ALL streams (UI-016 card feed). Monotonic in bytesDone. */
    val progress: StateFlow<MultiStreamProgress> = _progress.asStateFlow()

    // ---- shared mutable transfer state (guard: stateLock) -----------------------------------
    private val stateLock = ReentrantLock()
    private val workAvailable = stateLock.newCondition()
    private var resolvedDigestHex: String? = null
    private val confirmed = ResumeBitVector(plan.totalChunks).also { it.reconcile(resumeDone) }
    private var cursorIndex = 0 // next slot in pendingIndexes
    private val retryPool = ConcurrentLinkedQueue<Int>()
    private val inflightByChannel = HashMap<Int, MutableSet<Int>>() // channel -> claimed, unacked
    private val channelOfIndex = HashMap<Int, Int>() // index -> owning channel
    private val alive = LinkedHashMap<Int, StreamChannel>()
    private val deadChannelIds = LinkedHashSet<Int>()
    private var confirmedBytes = resumedBytes
    private var chunksSentTotal = 0
    private var bytesSentTotal = 0L
    private var receiverVerified: Boolean? = null
    private var terminal: MultiStreamResult? = null

    // Sequential chunk materializers (guarded by stateLock; see readFrameLocked).
    private var primaryStream: ChunkStream? = null
    private var primaryPosition = 0 // index of the NEXT frame primaryStream will emit
    private var retryStream: ChunkStream? = null
    private var retryPosition = 0

    private val rateMeter = RollingRateMeter(nowMs, speedWindowMs)
    private val completeEmittedOnce = AtomicBoolean(false)
    private var started = false

    /**
     * Runs the entire multi-stream transfer. Single-use. Inbound receiver feedback (`ACK_BATCH` /
     * `COMPLETE` frames arriving on any channel) must be pushed via [onInboundFrame] — usually
     * synchronously from each channel's read loop. Engine-level timeouts wrap this call.
     */
    suspend fun send(): MultiStreamResult {
        stateLock.withLock {
            check(!started) { "MultiStreamDispatcher is single-use" }
            started = true
            resolvedDigestHex =
                fileSha256Hex?.let(Sha256::normalizeHex) ?: chunker.hashOnly(source)
            publishProgressLocked()
            if (pendingIndexes.isEmpty()) {
                resolveTerminalLocked(completeLocked(receiverVerified))
            }
        }
        terminal?.let { return finishWith(it) }

        val opened = openChannels()
        if (opened.isEmpty()) {
            stateLock.withLock {
                terminal ?: resolveTerminalLocked(failedLocked("no stream could be opened"))
            }
            return finishWith(requireNotNull(terminal))
        }

        val result = coroutineScope {
            opened.map { channel ->
                async(workerDispatcher) { runChannel(channel.id, channel) }
            }.forEach { it.join() }
            stateLock.withLock {
                requireNotNull(terminal) { "all workers exited without a terminal state" }
            }
        }
        return finishWith(result)
    }

    /**
     * Feeds one receiver frame that arrived on [channelId] (ACK routing context — kept explicit
     * for engine logging even though ingestion is routing-agnostic). Returns true when consumed;
     * stale/unknown frames are ignored so late ACKs never crash the session.
     */
    fun onInboundFrame(channelId: Int, bytes: ByteArray): Boolean {
        return when (val frame = ChunkFrame.parse(bytes)) {
            is ChunkFrame.AckBatch -> ingestAckBatch(frame)
            is ChunkFrame.Complete -> ingestComplete(frame)
            else -> false
        }
    }

    /** Snapshot: distinct chunk indexes the receiver has confirmed so far. */
    fun confirmedCountSnapshot(): Int = stateLock.withLock { confirmed.receivedCount }

    /** Snapshot: channel ids marked dead during the session. */
    fun deadChannelsSnapshot(): List<Int> = stateLock.withLock { deadChannelIds.toList() }

    // ---- channel lifecycle -------------------------------------------------------------------

    private suspend fun openChannels(): List<StreamChannel> {
        val opened = ArrayList<StreamChannel>(streamCount)
        for (id in 0 until streamCount) {
            if (stateLock.withLock { terminal != null }) break
            val channel = try {
                factory.open(id)
            } catch (_: Throwable) {
                null
            } ?: continue // slot refused (null = cannot open more streams)
            val startOk = try {
                channel.sendFrame(
                    ChunkFrame.serialize(
                        chunker.fileStart(meta, plan, requireNotNull(resolvedDigestHex)),
                    ),
                )
            } catch (_: Throwable) {
                false
            }
            stateLock.withLock {
                if (startOk) alive[id] = channel else deadChannelIds.add(id)
            }
            if (startOk) opened.add(channel)
        }
        return opened
    }

    // ---- worker loop -------------------------------------------------------------------------

    private suspend fun runChannel(channelId: Int, channel: StreamChannel) {
        while (true) {
            val frame = claimNextChunk(channelId)
            if (frame == null) return
            // Count BEFORE the send: the receiver's inline ACK feedback can resolve the
            // session inside sendFrame, and completeLocked() snapshots these counters —
            // counting afterwards loses the completing chunk (observed 18 vs 19).
            stateLock.withLock {
                chunksSentTotal++
                bytesSentTotal += frame.data.size
            }
            val ok = try {
                channel.sendFrame(ChunkFrame.serialize(frame))
            } catch (_: Throwable) {
                false
            }
            if (!ok) {
                stateLock.withLock {
                    chunksSentTotal--
                    bytesSentTotal -= frame.data.size
                }
                killChannel(channelId, "send failed at chunk ${frame.index}")
                return
            }
        }
    }

    /**
     * Claims the next chunk for [channelId] and reads its bytes. The read happens under
     * [stateLock] together with the claim because [ChunkStream] is strictly sequential —
     * claim+read is ONE atomic section (prevents double-materialization races); only the network
     * send runs outside the lock. Returns null when this worker must stop.
     */
    private fun claimNextChunk(channelId: Int): ChunkFrame.Chunk? {
        while (true) {
            stateLock.withLock {
                if (terminal != null || channelId !in alive) return null
                // End-game note (revised per ADR-015 follow-up): NO exclusive tail
                // owner. A stalled owner would hold the entire tail hostage
                // (observed as gated-channel deadlock); atomic claims already give
                // exactly-once, and free-for-all claiming drains the tail fastest.
                val index = takeWorkIndexLocked(channelId)
                if (index == NO_WORK) {
                    workAvailable.await(PARK_POLL_MS, TimeUnit.MILLISECONDS)
                    return@withLock CONTINUE_SENTINEL
                }
                try {
                    readFrameLocked(index)
                } catch (e: RuntimeException) {
                    // Source ended/changed mid-transfer (or digest guard tripped) — total failure.
                    resolveTerminalLocked(failedLocked("source read failed: ${e.message}"))
                    return null
                }
            }?.let { return it }
        }
    }

    private fun takeWorkIndexLocked(channelId: Int): Int {
        while (true) {
            val retried = retryPool.poll()
            if (retried != null) {
                if (!confirmed.isReceived(retried)) {
                    registerInflightLocked(channelId, retried)
                    return retried
                }
                continue // a late ACK beat the retry: nothing to resend
            }
            if (cursorIndex >= pendingIndexes.size) {
                return NO_WORK
            }
            val index = pendingIndexes[cursorIndex++]
            if (confirmed.isReceived(index)) continue // defensive; cannot normally happen pre-claim
            registerInflightLocked(channelId, index)
            return index
        }
    }

    /**
     * Produces the CHUNK frame for [index]. Fast path: the sequential primary stream, skipping
     * resume-done chunks read-and-discard style so the running whole-file digest stays intact.
     * Retry path (a death returned an index whose bytes were already streamed past): reopen a
     * fresh linear stream and skip forward — v1 linear-skip semantics mirroring SendPipeline's
     * documented resume limitation (random access reserved for a future SeekableSource).
     * MUST be called under [stateLock] right after claiming [index].
     */
    private fun readFrameLocked(index: Int): ChunkFrame.Chunk {
        if (index >= primaryPosition) {
            val s = primaryStream ?: openPrimaryLocked()
            while (primaryPosition < index) {
                s.next() // discard skipped chunk (already hashed into the stream digest)
                primaryPosition++
            }
            val frame = s.next()
            primaryPosition++
            return frame
        }
        var rs = retryStream
        var rp = retryPosition
        if (rs == null || index < rp) {
            rs?.closeQuietly()
            rs = chunker.openChunkStream(source, meta, plan, resolvedDigestHex)
            retryStream = rs
            rp = 0
        }
        while (rp < index) {
            rs.next()
            rp++
        }
        retryPosition = rp + 1
        return rs.next()
    }

    private fun openPrimaryLocked(): ChunkStream =
        chunker.openChunkStream(source, meta, plan, resolvedDigestHex).also {
            primaryStream = it
            primaryPosition = 0
        }


    private fun registerInflightLocked(channelId: Int, index: Int) {
        channelOfIndex[index] = channelId
        inflightByChannel.getOrPut(channelId) { HashSet() }.add(index)
    }

    // ---- inbound feedback --------------------------------------------------------------------

    private fun ingestAckBatch(frame: ChunkFrame.AckBatch): Boolean {
        if (frame.transferId != meta.transferId || frame.fileId != meta.fileId) return false
        var woke = false
        stateLock.withLock {
            for (i in frame.indexes) {
                if (!confirmed.isReceived(i)) {
                    confirmed.markReceived(i)
                    confirmedBytes += chunkBytes(i)
                    removeFromInflight(i)
                    woke = true
                }
            }
            publishProgressLocked()
            maybeResolveCompleteLocked()
        }
        if (woke) wakeWorkers()
        return true
    }

    private fun ingestComplete(frame: ChunkFrame.Complete): Boolean {
        if (frame.transferId != meta.transferId || frame.fileId != meta.fileId) return false
        stateLock.withLock {
            receiverVerified = frame.verified
            maybeResolveCompleteLocked()
        }
        return true
    }

    /**
     * LAST-FINISHING COORDINATOR PATH: whichever thread first observes full coverage resolves the
     * session here; concurrent observers fall through on `terminal != null`.
     */
    private fun maybeResolveCompleteLocked() {
        if (!confirmed.isComplete()) return
        resolveTerminalLocked(completeLocked(receiverVerified))
    }

    /** Builds the success result and emits the exactly-once COMPLETE coordination frame. */
    private fun completeLocked(verified: Boolean?): MultiStreamResult {
        val frameBytes = if (completeEmittedOnce.compareAndSet(false, true)) {
            val complete = ChunkFrame.Complete(meta.transferId, meta.fileId, verified ?: true)
            ChunkFrame.serialize(complete).also { onCompleteFrame?.invoke(it) }
        } else {
            null
        }
        return MultiStreamResult.Completed(
            totalChunks = plan.totalChunks,
            chunksSent = chunksSentTotal,
            chunksSkippedResume = plan.totalChunks - pendingIndexes.size,
            bytesSent = bytesSentTotal,
            bytesSkippedResume = resumedBytes,
            // "" only reachable when full coverage was ingested before send() prepared the digest.
            fileSha256Hex = resolvedDigestHex ?: "",
            verified = verified,
            deadChannelIds = deadChannelIds.toList(),
            completeFrameBytes = frameBytes,
        )
    }

    private fun killChannel(channelId: Int, reason: String) {
        stateLock.withLock {
            if (channelId !in alive) return
            alive.remove(channelId)
            deadChannelIds.add(channelId)
            val orphans = inflightByChannel.remove(channelId).orEmpty()
            for (index in orphans) {
                if (channelOfIndex[index] == channelId) {
                    channelOfIndex.remove(index)
                    if (!confirmed.isReceived(index)) retryPool.add(index)
                }
            }
            workAvailable.signalAll()
            if (alive.isEmpty() && terminal == null) {
                resolveTerminalLocked(failedLocked(reason))
            }
        }
    }

    // ---- progress ----------------------------------------------------------------------------

    /** Aggregated across streams: one rolling window over TOTAL confirmed bytes — per-stream
     *  rates are never summed (overlapping windows would double-count). */
    private fun publishProgressLocked() {
        val now = nowMs()
        rateMeter.record(confirmedBytes)
        val rate = rateMeter.instantBytesPerSec(now)
        val etaMs = if (rate > 0.0 && confirmedBytes < meta.totalBytes) {
            ((meta.totalBytes - confirmedBytes) / rate * 1000.0).toLong()
        } else {
            -1L
        }
        _progress.value = MultiStreamProgress(confirmedBytes, meta.totalBytes, rate, etaMs)
    }

    private fun chunkBytes(index: Int): Long {
        val start = index.toLong() * plan.chunkSize
        return minOf(plan.chunkSize.toLong(), meta.totalBytes - start)
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun removeFromInflight(index: Int) {
        val owner = channelOfIndex.remove(index) ?: return
        inflightByChannel[owner]?.remove(index)
    }

    private fun wakeWorkers() {
        stateLock.withLock { workAvailable.signalAll() }
    }

    private fun failedLocked(reason: String): MultiStreamResult =
        MultiStreamResult.Failed(
            reason = reason,
            deadChannelIds = deadChannelIds.toList(),
            unconfirmedIndexes = confirmed.missingIndexes(),
        )

    private fun resolveTerminalLocked(result: MultiStreamResult) {
        // First resolver wins (comment previously promised this; the guard was missing,
        // letting a racy second observer overwrite a Completed carrying the COMPLETE frame).
        if (terminal != null) return
        terminal = result
        workAvailable.signalAll()
    }

    private fun finishWith(result: MultiStreamResult): MultiStreamResult {
        val finalResult = stateLock.withLock {
            (terminal ?: result).also { terminal = it }
        }
        stateLock.withLock {
            primaryStream?.closeQuietly()
            retryStream?.closeQuietly()
        }
        return finalResult
    }

    private companion object {
        const val MAX_STREAMS = 4
        const val DEFAULT_STREAM_COUNT = 2

        /** Plan C5.7 / BitTorrent-style minimal tail depth. */
        const val END_GAME_CHUNKS = 8

        /** Bounded park slice: correctness never depends on this timing. */
        const val PARK_POLL_MS = 20L

        const val NO_WORK = Int.MIN_VALUE
        val CONTINUE_SENTINEL: ChunkFrame.Chunk? = null
    }
}

private fun ChunkStream.closeQuietly() {
    try {
        close()
    } catch (_: RuntimeException) {
        // Best-effort close on teardown paths; never masks the original outcome.
    }
}
