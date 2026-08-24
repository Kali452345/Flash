package com.transfer.flash.core.transfer.multistream

import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ChunkPlan
import com.transfer.flash.core.transfer.chunked.ChunkSource
import com.transfer.flash.core.transfer.chunked.Chunker
import com.transfer.flash.core.transfer.chunked.ChunkStream
import com.transfer.flash.core.transfer.chunked.FileMeta
import com.transfer.flash.core.transfer.chunked.ResumeBitVector
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Send-side orchestrator for C5.7 MULTI-STREAM transfer — **v3**, modeled on proven segmented-
 * download architecture (media-downloader `SegmentedDownloader`, compared 2026-08-23; full defect
 * history for v1/v2 in ERROR-013).
 *
 * ## Design — each point fixes a concrete v1 defect
 *
 * - **Static assignment** (`idx % N`): the single materializer routes each serialized chunk to a
 *   per-worker feed channel. No dynamic claiming, shared cursor, or exclusive end-game owner —
 *   the three structures behind v1's Heisenberg race.
 * - **Single materializer** owns all [ChunkStream] reading (streams are strictly sequential),
 *   eliminating read-under-lock and double-materialization races.
 * - **Lock-free hot path**: confirmed bytes/chunks live in atomics touched by ACK ingestion;
 *   per-worker state is owned by exactly one coroutine. Tiny synchronized blocks guard snapshots.
 * - **Throttled progress publisher job** (10 ms) — same shape as SegmentedDownloader's.
 * - **Receiver-authoritative completion**: resolution happens on the receiver's COMPLETE frame
 *   (`verified`), with fallbacks: local-coverage grace expiry, all-channels-dead fail-fast.
 * - **At-least-once wire, exactly-once write**: dead workers redistribute unconfirmed frames into
 *   a shared queue drained by survivors; the receive pipeline dedups duplicates.
 * - **Late ingestion safe**: inbound frames remain processable after any resolution outcome.
 *
 * ## Concurrent multi-peer transfers
 *
 * Instances hold no global/static mutable state — run as many simultaneously as needed.
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
    @Suppress("UNUSED_PARAMETER") endGameChunks: Int = END_GAME_CHUNKS,
    private val speedWindowMs: Long = MultiStreamProgress.DEFAULT_WINDOW_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onCompleteFrame: ((ByteArray) -> Unit)? = null,
    /** Local-coverage fallback delay before resolving without the receiver COMPLETE frame. */
    private val completeGraceMs: Long = DEFAULT_COMPLETE_GRACE_MS,
) {
    init {
        require(streamCount in 1..MAX_STREAMS) { "streamCount must be in 1..$MAX_STREAMS" }
        require(completeGraceMs >= 0) { "completeGraceMs must be >= 0" }
    }

    private val plan: ChunkPlan = chunker.plan(meta, requestedChunkSize)
    private val resumeDone: List<Int> = doneIndexes.filter { it in 0 until plan.totalChunks }
    private val pendingIndexes: List<Int> =
        (0 until plan.totalChunks).filter { it !in resumeDone }
    private val resumedBytes: Long = resumeDone.sumOf { chunkBytes(it) }

    private val _progress = MutableStateFlow(
        MultiStreamProgress(bytesDone = resumedBytes, totalBytes = meta.totalBytes),
    )

    /** Aggregate telemetry across ALL streams (UI-016 card feed). Monotonic in bytesDone. */
    val progress: StateFlow<MultiStreamProgress> = _progress.asStateFlow()

    // ---- lock-free shared state -----------------------------------------------------------------

    private val confirmedVector = ResumeBitVector(plan.totalChunks).also { it.reconcile(resumeDone) }
    private val confirmedBytes = AtomicLong(resumedBytes)
    private val confirmedCount = AtomicInteger(resumeDone.size)
    private val chunksSentTotal = AtomicInteger(0)
    private val bytesSentTotal = AtomicLong(0L)
    private val aliveWorkers = AtomicInteger(0)
    private val started = AtomicBoolean(false)

    @Volatile private var receiverVerifiedField: Boolean? = null
    @Volatile private var coverageReachedAtMs: Long? = null
    @Volatile private var resolvedDigest: String = ""

    // Terminal bookkeeping: tiny critical sections, never held across I/O.
    private val terminalLock = Any()
    private var terminalResult: MultiStreamResult? = null
    private var terminalDeferred: CompletableDeferred<MultiStreamResult>? = null
    private val deadIds = java.util.Collections.synchronizedList(mutableListOf<Int>())

    private val rateMeter = RollingRateMeter(nowMs, speedWindowMs)
    private val completeEmittedOnce = AtomicBoolean(false)
    @Volatile private var completeFrameBytesHolder: ByteArray? = null

    private data class PreparedFrame(val index: Int, val frameBytes: ByteArray)

    // ---- public API ------------------------------------------------------------------------------

    /**
     * Runs the entire multi-stream transfer. Single-use. Inbound receiver feedback (`ACK_BATCH`
     * / `COMPLETE` arriving on any channel) must be pushed via [onInboundFrame].
     */
    suspend fun send(): MultiStreamResult {
        check(started.compareAndSet(false, true)) { "MultiStreamDispatcher is single-use" }
        resolvedDigest = fileSha256Hex?.let(Sha256::normalizeHex) ?: chunker.hashOnly(source)

        return coroutineScope {
            val deferred = CompletableDeferred<MultiStreamResult>()
            terminalDeferred = deferred

            if (pendingIndexes.isEmpty()) {
                deferred.complete(resolvedCompleted(chunksSent = 0))
                return@coroutineScope deferred.await()
            }

            val effectiveStreams = streamCount.coerceIn(1, pendingIndexes.size)
            aliveWorkers.set(effectiveStreams)

            val feeds = List(effectiveStreams) { Channel<PreparedFrame>(Channel.UNLIMITED) }
            val shared = Channel<PreparedFrame>(Channel.UNLIMITED)
            val ownFeedsOpen = AtomicInteger(effectiveStreams)

            // Watcher: throttled progress publishing + non-inline resolutions
            // (local-coverage grace expiry, all-channels-dead fail-fast).
            launch(workerDispatcher) {
                while (isActive && !deferred.isCompleted) {
                    publishProgress()
                    maybeResolveFromState(deferred, forceCoverageResolve = false)
                    delay(WATCH_POLL_MS)
                }
            }

            // Materializer: sole reader of the strictly-sequential ChunkStream; closes feeds done.
            launch(workerDispatcher) {
                try {
                    var pos = 0
                    var stream: ChunkStream? = null
                    try {
                        for (index in pendingIndexes) {
                            val s = stream ?: chunker.openChunkStream(
                                source, meta, plan, resolvedDigest,
                            ).also { stream = it }
                            while (pos < index) {
                                s.next() // defensive skip
                                pos++
                            }
                            val frame = s.next()
                            pos++
                            feeds[index % effectiveStreams].send(
                                PreparedFrame(index, ChunkFrame.serialize(frame)),
                            )
                        }
                    } finally {
                        stream?.closeQuietly()
                    }
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        failWith(deferred, "source read failed: ${e.message}")
                    }
                } finally {
                    feeds.forEach { it.close() }
                }
            }

            // Announce the transfer on EVERY live channel: the receiver pipeline registers its
            // session on FILE_START — chunks arriving first would be rejected UNKNOWN_TRANSFER.
            val channels = (0 until effectiveStreams).map { id ->
                val channel = try {
                    factory.open(id)
                        ?: return@coroutineScope failWith(
                            deferred, "channel factory refused stream $id",
                        )
                } catch (t: Throwable) {
                    return@coroutineScope failWith(
                        deferred, "channel open failed: ${t.message}",
                    )
                }
                val startOk = runCatching {
                    channel.sendFrame(ChunkFrame.serialize(chunker.fileStart(meta, plan, resolvedDigest)))
                }.getOrDefault(false)
                if (!startOk) {
                    synchronized(terminalLock) { deadIds.add(id) }
                }
                Pair(channel, startOk)
            }
            val workers = channels.mapIndexed { idx, (channel, startOk) ->
                launch(workerDispatcher) {
                    runWorker(idx, feeds[idx], shared, ownFeedsOpen, deferred, channel, startOk)
                }
            }
            workers.joinAll()

            // Deterministic final resolution (watcher may have resolved already; first-wins).
            maybeResolveFromState(deferred, forceCoverageResolve = true)
            deferred.await().also { publishProgress() }
        }
    }

    /**
     * Feeds one receiver frame that arrived on [channelId]. Returns true when consumed;
     * stale/unknown frames are ignored so late ACKs never crash the session. Safe to call after
     * resolution — late authoritative frames still update bookkeeping/emission.
     */
    fun onInboundFrame(channelId: Int, bytes: ByteArray): Boolean =
        when (val frame = ChunkFrame.parse(bytes)) {
            is ChunkFrame.AckBatch -> ingestAckBatch(frame)
            is ChunkFrame.Complete -> ingestComplete(frame)
            else -> false
        }

    /** Snapshot: distinct chunk indexes the receiver has confirmed so far. */
    fun confirmedCountSnapshot(): Int = confirmedCount.get()

    /** Snapshot: channel ids marked dead during the session. */
    fun deadChannelsSnapshot(): List<Int> = synchronized(terminalLock) { deadIds.toList() }

    // ---- inbound feedback -------------------------------------------------------------------------

    private fun ingestAckBatch(frame: ChunkFrame.AckBatch): Boolean {
        if (frame.transferId != meta.transferId || frame.fileId != meta.fileId) return false
        markRangeConfirmed(frame.indexes)
        val covered = confirmedCount.get() >= plan.totalChunks
        if (covered && completeGraceMs == 0L) {
            emitCompleteFrameOnce(receiverVerifiedField ?: true)
        }
        publishProgress()
        // Coverage may have just completed (e.g., manually-fed sessions): resolve now.
        terminalDeferred?.let { maybeResolveFromState(it, forceCoverageResolve = false) }
        return true
    }

    private fun ingestComplete(frame: ChunkFrame.Complete): Boolean {
        if (frame.transferId != meta.transferId || frame.fileId != meta.fileId) return false
        receiverVerifiedField = frame.verified
        markRangeConfirmed((0 until plan.totalChunks).toList()) // receiver is authoritative
        emitCompleteFrameOnce(frame.verified)
        publishProgress()
        terminalDeferred?.let { maybeResolveFromState(it, forceCoverageResolve = true) }
        return true
    }

    private fun markRangeConfirmed(indexes: List<Int>) {
        synchronized(terminalLock) {
            indexes.forEach { idx ->
                if (!confirmedVector.isReceived(idx)) {
                    confirmedVector.markReceived(idx)
                    confirmedBytes.addAndGet(chunkBytes(idx))
                    confirmedCount.incrementAndGet()
                }
            }
            if (confirmedCount.get() >= plan.totalChunks && coverageReachedAtMs == null) {
                coverageReachedAtMs = nowMs()
            }
        }
        publishProgress()
    }

    // ---- resolution --------------------------------------------------------------------------------

    private fun maybeResolveFromState(
        deferred: CompletableDeferred<MultiStreamResult>,
        forceCoverageResolve: Boolean,
    ) {
        if (deferred.isCompleted) return
        val covered = confirmedCount.get() >= plan.totalChunks
        val coverageAge = coverageReachedAtMs?.let { nowMs() - it } ?: 0L

        val completed = when {
            covered && receiverVerifiedField != null -> resolvedCompleted(chunksSentTotal.get())
            covered && (forceCoverageResolve || coverageAge >= completeGraceMs) ->
                resolvedCompleted(chunksSentTotal.get(), verified = receiverVerifiedField)
            else -> null
        }
        if (completed != null) {
            deferred.complete(completed)
            return
        }
        if (!covered && aliveWorkers.get() == 0) {
            deferred.complete(failedLocked("all channels failed"))
        }
    }

    private fun resolvedCompleted(
        chunksSent: Int,
        verified: Boolean? = receiverVerifiedField,
    ): MultiStreamResult.Completed {
        emitCompleteFrameOnce(verified ?: true)
        return MultiStreamResult.Completed(
            totalChunks = plan.totalChunks,
            chunksSent = chunksSent,
            chunksSkippedResume = plan.totalChunks - pendingIndexes.size,
            bytesSent = bytesSentTotal.get(),
            bytesSkippedResume = resumedBytes,
            fileSha256Hex = resolvedDigest,
            verified = verified,
            deadChannelIds = synchronized(terminalLock) { deadIds.toList() },
            completeFrameBytes = completeFrameBytesHolder,
        )
    }

    private fun failedLocked(reason: String): MultiStreamResult.Failed =
        MultiStreamResult.Failed(
            reason = reason,
            deadChannelIds = synchronized(terminalLock) { deadIds.toList() },
            unconfirmedIndexes = confirmedVector.missingIndexes(),
        )

    private fun failWith(
        deferred: CompletableDeferred<MultiStreamResult>,
        reason: String,
    ): MultiStreamResult {
        val result = failedLocked(reason)
        deferred.complete(result)
        return result
    }

    private fun emitCompleteFrameOnce(verified: Boolean): ByteArray? =
        if (completeEmittedOnce.compareAndSet(false, true)) {
            val bytes = ChunkFrame.serialize(ChunkFrame.Complete(meta.transferId, meta.fileId, verified))
            completeFrameBytesHolder = bytes
            onCompleteFrame?.invoke(bytes)
            bytes
        } else completeFrameBytesHolder

    // ---- workers -----------------------------------------------------------------------------------

    /**
     * Phase 1 drains this worker's assigned feed. On wire failure the worker marks itself dead,
     * redistributes the failed frame plus everything left of its assignment into the shared
     * queue, and exits. Healthy workers drain their feed to natural closure, then help drain
     * redistributed work until it too closes (last own-feed consumer closes it).
     */
    private suspend fun runWorker(
        id: Int,
        ownFeed: Channel<PreparedFrame>,
        shared: Channel<PreparedFrame>,
        ownFeedsOpen: AtomicInteger,
        deferred: CompletableDeferred<MultiStreamResult>,
        wire: StreamChannel,
        startOk: Boolean,
    ) {
        var sent = 0
        var failed = 0
        // Phase 1: assigned frames. A wire failure flips this worker to "dead": everything
        // left of its assignment (including the failed frame) redistributes into `shared`,
        // and it stops touching its broken wire entirely.
        var dead = !startOk
        if (dead) {
            synchronized(terminalLock) {
                if (!deadIds.contains(id)) deadIds.add(id)
            }
        }
        for (prepared in ownFeed) {
            val ok = if (!dead) {
                chunksSentTotal.incrementAndGet()
                bytesSentTotal.addAndGet(chunkBytes(prepared.index))
                val success = runCatching { wire.sendFrame(prepared.frameBytes) }.getOrDefault(false)
                if (!success) {
                    chunksSentTotal.decrementAndGet()
                    bytesSentTotal.addAndGet(-chunkBytes(prepared.index))
                }
                success
            } else {
                false
            }

            if (ok) {
                sent++
            } else {
                failed++
                if (!dead) {
                    dead = true
                    synchronized(terminalLock) {
                        if (!deadIds.contains(id)) deadIds.add(id)
                    }
                }
                shared.send(prepared)
            }
        }
        if (ownFeedsOpen.decrementAndGet() == 0) {
            shared.close()
        }

        if (dead) {
            aliveWorkers.decrementAndGet()
            failIfAllChannelsDead(deferred)
            return
        }

        // Phase 2: help survivors drain redistributed frames until the shared queue closes
        // (closed by whichever worker consumed the last own-feed slot).
        try {
            for (prepared in shared) {
                chunksSentTotal.incrementAndGet()
                bytesSentTotal.addAndGet(chunkBytes(prepared.index))
                val ok = runCatching { wire.sendFrame(prepared.frameBytes) }.getOrDefault(false)
                if (!ok) {
                    chunksSentTotal.decrementAndGet()
                    bytesSentTotal.addAndGet(-chunkBytes(prepared.index))
                    shared.send(prepared) // hand back; another survivor takes it
                    aliveWorkers.decrementAndGet()
                    failIfAllChannelsDead(deferred)
                    return
                }
            }
        } finally {
            aliveWorkers.decrementAndGet()
        }
    }

    private fun failIfAllChannelsDead(deferred: CompletableDeferred<MultiStreamResult>) {
        val alive = aliveWorkers.get()
        if (alive == 0 && !deferred.isCompleted) {
            val covered = confirmedCount.get() >= plan.totalChunks
            if (!covered) {
                deferred.complete(failedLocked("all channels failed"))
            }
        }
    }

    private fun publishProgress() {
        val done = confirmedBytes.get()
        rateMeter.record(done)
        val rate = rateMeter.instantBytesPerSec(nowMs())
        val eta = if (rate > 0.0 && done < meta.totalBytes) ((meta.totalBytes - done) / rate * 1000.0).toLong() else -1L
        _progress.value = MultiStreamProgress(done, meta.totalBytes, rate, eta)
    }

    private fun chunkBytes(index: Int): Long {
        val start = index.toLong() * plan.chunkSize
        return minOf(plan.chunkSize.toLong(), meta.totalBytes - start)
    }

    private companion object {
        const val MAX_STREAMS = 4
        const val DEFAULT_STREAM_COUNT = 2

        @Suppress("UNUSED")
        const val END_GAME_CHUNKS = 8

        const val WATCH_POLL_MS = 10L
        const val DEFAULT_COMPLETE_GRACE_MS = 2_000L
    }
}

private fun ChunkStream.closeQuietly() {
    try {
        close()
    } catch (_: RuntimeException) {
        // Best-effort close on teardown paths; never masks the original outcome.
    }
}
