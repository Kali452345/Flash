package com.transfer.flash.core.transfer.multistream

/**
 * Aggregate transfer telemetry for one multi-stream session (C5.7), shaped for the UI-016 file
 * card: confirmed byte progress, live speed over a rolling window, ETA.
 *
 * @param bytesDone receiver-confirmed bytes (resume-seeded chunks included); monotonically
 *   non-decreasing because it is derived from a union-only bit-vector.
 * @param totalBytes logical file size; `bytesDone == totalBytes` at completion.
 * @param instantBytesPerSec rolling-window throughput aggregated across ALL streams
 *   (`bytesDone` deltas divided by wall-clock deltas — never summed per-stream rates, which
 *   double-counts overlapping windows). `-1.0` until two samples exist.
 * @param etaMs `(totalBytes - bytesDone) / rate`; `-1` when unknown/stalled/completed.
 */
data class MultiStreamProgress(
    val bytesDone: Long,
    val totalBytes: Long,
    val instantBytesPerSec: Double = -1.0,
    val etaMs: Long = -1L,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (bytesDone.toDouble() / totalBytes).toFloat()

    companion object {

        /** Default telemetry window (plan C5.7: aggregate throughput accounting, 2 s rolling). */
        const val DEFAULT_WINDOW_MS: Long = 2_000L
    }
}

/**
 * Rolling-window byte-delta rate estimator shared by all streams of one session. Deterministic:
 * the clock is injected, so JVM tests drive it with a fake timeline.
 *
 * Rate semantics follow the cumulative-sample pattern (Δbytes / Δtime between the oldest sample
 * still inside the window and now — NOT a fixed-window denominator, which under-reports right
 * after start): see prior art in BucketCat's SpeedWindow and the unsloth transfer-stats sampler
 * (window span from oldest surviving sample; stability gating).
 */
internal class RollingRateMeter(
    private val nowMs: () -> Long,
    private val windowMs: Long = MultiStreamProgress.DEFAULT_WINDOW_MS,
) {
    init {
        require(windowMs > 0) { "windowMs must be > 0" }
    }

    private var firstAt: Long = -1L
    private var lastAt: Long = -1L
    private var firstBytes: Long = 0L
    private var lastBytes: Long = 0L

    /** Records that cumulative progress reached [cumulativeBytes] at the injected "now". */
    fun record(cumulativeBytes: Long) {
        val t = nowMs()
        if (firstAt < 0L) {
            firstAt = t
            firstBytes = cumulativeBytes
        } else if (t < lastAt) {
            // Clock went backwards (host suspend/NTP): re-anchor instead of producing negatives.
            firstAt = t
            firstBytes = lastBytes
        }
        lastAt = t
        lastBytes = cumulativeBytes
    }

    /**
     * Bytes/sec across the window at [atMs]; `-1.0` when fewer than two distinct timestamps exist
     * or no forward progress is visible inside the window (stall ⇒ ETA hidden, not faked).
     */
    fun instantBytesPerSec(atMs: Long): Double {
        if (firstAt < 0L || lastAt <= firstAt || atMs < lastAt) return -1.0
        val effectiveStart = maxOf(firstAt, atMs - windowMs)
        if (effectiveStart >= lastAt) return -1.0
        val dtMs = (lastAt - effectiveStart).toDouble()
        val db = (lastBytes - firstBytes).toDouble()
        if (db <= 0.0 || dtMs <= 0.0) return -1.0
        return db * 1000.0 / dtMs
    }
}

/** Terminal outcome of [MultiStreamDispatcher.send]. */
sealed interface MultiStreamResult {

    /**
     * All chunks confirmed by the receiver (bit-vector complete).
     *
     * @property verified receiver COMPLETE flag (whole-file digest recheck result or trust-per-
     *   chunk when the engine disabled recheck); null when completion was detected via full ACK
     *   coverage before the COMPLETE frame was ingested.
     */
    data class Completed(
        val totalChunks: Int,
        val chunksSent: Int,
        val chunksSkippedResume: Int,
        val bytesSent: Long,
        val bytesSkippedResume: Long,
        val fileSha256Hex: String,
        val verified: Boolean?,
        val deadChannelIds: List<Int>,
        /** Serialized terminal COMPLETE frame emitted exactly once (see dispatcher KDoc). */
        val completeFrameBytes: ByteArray?,
    ) : MultiStreamResult

    data class Failed(
        val reason: String,
        val deadChannelIds: List<Int>,
        /** Un-ACKed chunk indexes — feed into a fresh dispatcher's doneIndexes? No: these are
         *  the NOT-done set; resume uses the receiver's persisted done-set (C5.6). Kept for
         *  diagnostics and targeted repair. */
        val unconfirmedIndexes: List<Int>,
    ) : MultiStreamResult
}
