package com.transfer.flash.core.transfer.multistream

import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ChunkSink
import com.transfer.flash.core.transfer.chunked.ChunkSource
import com.transfer.flash.core.transfer.chunked.Chunker
import com.transfer.flash.core.transfer.chunked.FileMeta
import com.transfer.flash.core.transfer.chunked.Sha256
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic JVM tests for C5.7 multi-stream dispatch: real threads via Dispatchers.Default,
 * ordering controlled by gates/polling — no kotlinx-coroutines-test.
 */
class MultiStreamDispatcherTest {

    companion object {
        private val liveExecutors = java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.ExecutorService>()
        private val chunkSize = 16_384

        /** 300 KB => 19 chunks (18 full + tail); exercises end-game K=8 across N=3 streams. */
        private val totalBytes = 300_000L
        private val payload = ByteArray(totalBytes.toInt()) { ((it * 31) + (it ushr 5)).toByte() }
        private val meta = FileMeta("ms-t", "file-ms", "big.bin", totalBytes)
    }

    // ---- fixtures ------------------------------------------------------------------------


    @After
    fun tearDown() {
        liveExecutors.forEach { runCatching { it.shutdownNow() } }
        liveExecutors.clear()
    }

    private class Assembler(totalChunks: Int) : ChunkSink {
        val parts = arrayOfNulls<ByteArray>(totalChunks)
        var writes = 0

        override fun write(index: Int, data: ByteArray) {
            parts[index] = data.copyOf()
            writes++
        }

        fun matches(expected: ByteArray): Boolean =
            Sha256.hexEqualsConstantTime(
                Sha256.digestHex(assembled()),
                Sha256.digestHex(expected),
            )

        private fun assembled(): ByteArray {
            val flat = ByteArray(parts.sumOf { it!!.size })
            var offset = 0
            for (part in parts) {
                part!!.copyInto(flat, offset)
                offset += part.size
            }
            return flat
        }
    }

    /**
     * In-memory loopback channel: pushes sender frames into the shared [receiver] and feeds every
     * emitted feedback frame back into the dispatcher tagged with THIS channel id — receiver
     * replies always travel down their arrival channel, like a real socket pair.
     */
    private open class LoopbackChannel(
        override val id: Int,
        private val receiver: MultiStreamReceiver,
        private val dispatcherProvider: () -> MultiStreamDispatcher,
    ) : StreamChannel {

        val sentIndexes: MutableList<Int> = Collections.synchronizedList(ArrayList())

        /** Runs before a CHUNK is forwarded; return false = channel death at that chunk. */
        var interceptor: ((frame: ChunkFrame.Chunk) -> Boolean)? = null

        override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
            when (val frame = ChunkFrame.parse(frameBytes)) {
                is ChunkFrame.FileStart -> forward(frameBytes)
                is ChunkFrame.AckBatch, is ChunkFrame.Complete -> Unit
                is ChunkFrame.Chunk -> {
                    if (interceptor?.invoke(frame) == false) return false
                    sentIndexes.add(frame.index)
                    forward(frameBytes)
                }

                null -> return false
            }
            return true
        }

        private fun forward(bytes: ByteArray) {
            java.io.File("E:/Flash/.gradle-user-home/msdbg.txt").appendText("forward id=$id parsed=" + (com.transfer.flash.core.transfer.chunked.ChunkFrame.parse(bytes)?.javaClass?.simpleName ?: "null") + "\n")
            for (event in receiver.onFrame(id, bytes)) {
                java.io.File("E:/Flash/.gradle-user-home/msdbg.txt").appendText("event id=$id " + event.javaClass.simpleName + "\n")
                val feedback = when (event) {
                    is RoutedReceiveEvent.AckBatchReady -> event.frameBytes
                    is RoutedReceiveEvent.Completed -> event.frameBytes
                    is RoutedReceiveEvent.Rejected -> throw AssertionError("rejected: $event")
                }
                assertTrue(dispatcherProvider().onInboundFrame(id, feedback))
            }
        }
    }

    /** Channel whose CHUNK sends die after [failAfterChunks] successes. */
    private class DyingChannel(
        id: Int,
        receiver: MultiStreamReceiver,
        dispatcherProvider: () -> MultiStreamDispatcher,
        private val failAfterChunks: Int,
    ) : LoopbackChannel(id, receiver, dispatcherProvider) {
        var chunkAttempts = 0

        override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
            if (ChunkFrame.parse(frameBytes) is ChunkFrame.Chunk) {
                if (chunkAttempts >= failAfterChunks) return false
                chunkAttempts++
            }
            return super.sendFrame(frameBytes)
        }
    }

    /** Channel that blocks inside its first CHUNK send until [gate] completes. */
    private class GatedChannel(
        id: Int,
        receiver: MultiStreamReceiver,
        dispatcherProvider: () -> MultiStreamDispatcher,
        private val gate: CompletableFuture<Unit>,
    ) : LoopbackChannel(id, receiver, dispatcherProvider) {
        /** Set BEFORE blocking so observers can detect "slow channel engaged". */
        var startedSending = false

        override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
            if (!startedSending && ChunkFrame.parse(frameBytes) is ChunkFrame.Chunk) {
                startedSending = true
                gate.get(30, TimeUnit.SECONDS)
            }
            return super.sendFrame(frameBytes)
        }
    }

    /** Channel that suspends its first CHUNK send until [waitFor] passes (start-order control). */
    private class OrderedFastChannel(
        id: Int,
        receiver: MultiStreamReceiver,
        dispatcherProvider: () -> MultiStreamDispatcher,
        private val waitFor: () -> Unit,
    ) : LoopbackChannel(id, receiver, dispatcherProvider) {
        private var waited = false

        override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
            if (!waited && ChunkFrame.parse(frameBytes) is ChunkFrame.Chunk) {
                waited = true
                waitFor()
            }
            return super.sendFrame(frameBytes)
        }
    }

    /** Builds a harness whose stream 1 is gated; streams 0/2 wait until stream 1 is blocked. */
    private fun gatedHarness(): Triple<Harness, CompletableFuture<Unit>, List<LoopbackChannel>> {
        val gate = CompletableFuture<Unit>()
        var slowRef: GatedChannel? = null
        val h = Harness({ id, r, p ->
            if (id == 1) {
                GatedChannel(id, r, p, gate).also { slowRef = it }
            } else {
                OrderedFastChannel(id, r, p) {
                    awaitUntil(condition = { slowRef!!.startedSending })
                }
            }
        })
        return Triple(h, gate, h.channels)
    }


    private class Harness(
        val channelsFactory: (
            id: Int,
            receiver: MultiStreamReceiver,
            provider: () -> MultiStreamDispatcher,
        ) -> LoopbackChannel = ::LoopbackChannel,
        val streamCount: Int = 3,
    ) {
        // Dedicated single-thread worker dispatcher: full-suite runs showed the shared
        // Dispatchers.Default pool idle-parked while our workers never ran (ERROR-013).
        private val testExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ms-harness-worker") }
        val testDispatcher = testExecutor.asCoroutineDispatcher()

        init {
            liveExecutors.add(testExecutor)
        }
        val assembler = Assembler(Chunker.totalChunks(totalBytes, chunkSize))
        val receiver = MultiStreamReceiver(assembler)
        lateinit var dispatcher: MultiStreamDispatcher
        val channels: List<LoopbackChannel> = (0 until streamCount).map { id ->
            channelsFactory(id, receiver) { dispatcher }
        }

        fun build(): MultiStreamDispatcher = MultiStreamDispatcher(
            chunker = Chunker(),
            meta = meta,
            source = ChunkSource { payload.inputStream() },
            factory = StreamChannelFactory { id -> channels.firstOrNull { it.id == id } },
            streamCount = streamCount,
            requestedChunkSize = chunkSize,
            workerDispatcher = testDispatcher,
        ).also { dispatcher = it }

        fun fastSentTotal(excludeId: Int): Int =
            channels.filter { it.id != excludeId }.sumOf { it.sentIndexes.size }
    }

    private fun awaitUntil(timeoutMs: Long = 20_000, condition: () -> Boolean, describe: () -> String = { "" }) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                val stacks = java.lang.management.ManagementFactory.getThreadMXBean()
                    .dumpAllThreads(true, false)
                    .filter { it.threadName.contains("Default") || it.threadName.contains("main") || it.threadName.contains("Test worker") }
                val interesting = stacks.joinToString("\n") { st ->
                    val frames = st.stackTrace.take(12).joinToString(" | ") { f -> f.className.substringAfterLast(".") + ":" + f.methodName }
                    "THREAD " + st.threadName + " state=" + st.threadState + " :: " + frames
                }
                throw AssertionError("condition timeout " + describe() + "\n" + interesting)
            }
            Thread.sleep(5)
        }
    }

    // ---- tests ---------------------------------------------------------------------------

    @Test
    fun `three streams move 300KB end to end - bytes identical, exactly once, endgame single-file`() =
        runBlocking {
            val h = Harness()
            val dispatcher = h.build()

            val result = dispatcher.send()

            val completed = result as? MultiStreamResult.Completed
                ?: throw AssertionError(
                    "expected Completed got $result",
                    (result as? MultiStreamResult.Failed)?.let { java.lang.AssertionError(it.reason) },
                )
            assertEquals(19, completed.totalChunks)
            assertEquals(
                "sentPerChannel=" + h.channels.joinToString { c -> "${c.id}:${c.sentIndexes.size}(${c.sentIndexes})" },
                19,
                completed.chunksSent,
            )
            assertEquals(0, completed.chunksSkippedResume)
            assertEquals(totalBytes, completed.bytesSent)
            assertEquals(true, completed.verified)
            assertTrue(completed.deadChannelIds.isEmpty())
            assertTrue(h.assembler.matches(payload))
            assertEquals("exactly-once writes", 19, h.assembler.writes)

            // End-game revision (ADR-015 follow-up): no exclusive owner — every tail
            // chunk still flows EXACTLY ONCE across channels (atomic claims).
            for (index in 11 until 19) {
                val senders = h.channels.count { it.sentIndexes.contains(index) }
                assertEquals("tail chunk $index exactly-once", 1, senders)
            }

            // Aggregate progress reached completion; terminal COMPLETE emitted exactly once.
            val finalProgress = dispatcher.progress.value
            assertEquals(totalBytes, finalProgress.bytesDone)
            assertNotNull(completed.completeFrameBytes)
            val parsedComplete = ChunkFrame.parse(completed.completeFrameBytes!!)
            assertTrue(parsedComplete is ChunkFrame.Complete)
        }

    @Test(timeout = 60_000)
    fun `slow gated channel - others finish the file, no deadlock, slow completes its one chunk`() =
        runBlocking {
            val (h, gate, channels) = gatedHarness()
            val dispatcher = h.build()
            var result: MultiStreamResult? = null
            val job = launch { result = dispatcher.send() }

            val slow = channels[1]
            awaitUntil(condition = { h.fastSentTotal(excludeId = 1) == 13 }, describe = { "fast=" + h.fastSentTotal(1) + " dead=" + h.dispatcher.deadChannelsSnapshot() + " confirmed=" + h.dispatcher.confirmedCountSnapshot() })
            val slowGated = slow as GatedChannel
            assertTrue("slow engaged before fasts finished", slowGated.startedSending)
            gate.complete(Unit)
            job.join()

            assertTrue(result is MultiStreamResult.Completed)
            assertTrue(h.assembler.matches(payload))
            assertEquals(19, h.assembler.writes)
        }
    @Test
    fun `channel death mid transfer - unacked claims return to pool, survivor completes`() =
        runBlocking {
            val h = Harness(channelsFactory = { id, r, p -> DyingChannel(id, r, p, failAfterChunks = 2) })
            val dispatcher = h.build()

            val result = dispatcher.send()

            assertTrue(result is MultiStreamResult.Completed)
            assertTrue(h.assembler.matches(payload))
            assertEquals(19, h.assembler.writes)
            assertTrue(dispatcher.deadChannelsSnapshot().contains(1))
            assertEquals(19, (result as MultiStreamResult.Completed).chunksSent)
        }

    @Test
    fun `all channels die - Failed with unconfirmed set`() = runBlocking {
        val h = Harness(channelsFactory = { id, r, p -> DyingChannel(id, r, p, failAfterChunks = 0) })
        val dispatcher = h.build()

        val result = dispatcher.send()

        val failed = result as MultiStreamResult.Failed
        assertTrue(failed.reason.isNotEmpty())
        assertEquals(3, failed.deadChannelIds.size)
        assertEquals(setOf(0, 1, 2), failed.deadChannelIds.toSet())
        assertEquals(19, failed.unconfirmedIndexes.size)
        assertEquals(0, h.assembler.writes)
    }

    @Test(timeout = 60_000)
    fun `progress is monotonic and eta sane while transferring`() = runBlocking {
        val (h, gate, _) = gatedHarness()
        val dispatcher = h.build()
        val samples = Collections.synchronizedList(ArrayList<MultiStreamProgress>())
        val sampler = launch {
            while (dispatcher.progress.value.bytesDone < totalBytes && !gate.isDone) {
                samples.add(dispatcher.progress.value)
                Thread.sleep(2)
            }
        }
        val job = launch { dispatcher.send() }
        awaitUntil(condition = { h.fastSentTotal(excludeId = 1) == 13 }, describe = { "fast=" + h.fastSentTotal(1) + " dead=" + h.dispatcher.deadChannelsSnapshot() + " confirmed=" + h.dispatcher.confirmedCountSnapshot() })
        gate.complete(Unit)
        job.join()
        sampler.join()

        assertTrue(samples.isNotEmpty())
        var previous = 0L
        for (sample in samples) {
            assertTrue("bytesDone monotonic", sample.bytesDone >= previous)
            previous = sample.bytesDone
            assertTrue(sample.bytesDone <= totalBytes)
            assertTrue(sample.fraction in 0f..1f)
            if (sample.instantBytesPerSec > 0.0 && sample.bytesDone < totalBytes) {
                assertTrue(sample.etaMs >= 0)
            } else {
                assertEquals(-1L, sample.etaMs)
            }
        }
        assertEquals(totalBytes, dispatcher.progress.value.bytesDone)
    }

    @Test
    fun `terminal COMPLETE frame is emitted exactly once under racing full-coverage ACKs`() {
        val emitted = Collections.synchronizedList(ArrayList<ByteArray>())
        val dispatcher = MultiStreamDispatcher(
            chunker = Chunker(),
            meta = meta,
            source = ChunkSource { payload.inputStream() },
            factory = StreamChannelFactory { null },
            streamCount = 1,
            requestedChunkSize = chunkSize,
            onCompleteFrame = { emitted.add(it) },
            workerDispatcher = Dispatchers.Default,
            completeGraceMs = 0, // coverage resolves immediately; no channels needed
        )
        val ack = ChunkFrame.serialize(
            ChunkFrame.AckBatch(meta.transferId, meta.fileId, (0 until 19).toList()),
        )
        val threads = (0 until 8).map { _ ->
            Thread {
                repeat(50) { ackRound -> dispatcher.onInboundFrame(ackRound % 3, ack) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("COMPLETE emitted once despite 400 redundant ACKs", 1, emitted.size)
        assertEquals(19, dispatcher.confirmedCountSnapshot())
        val parsed = ChunkFrame.parse(emitted[0])
        assertTrue(parsed is ChunkFrame.Complete)
        assertEquals(meta.transferId, (parsed as ChunkFrame.Complete).transferId)
    }

    @Test
    fun `resume seeding - doneIndexes skipped, progress starts at resumed bytes`() = runBlocking {
        val rxExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val rxDipatcher = rxExecutor.asCoroutineDispatcher()
        val h = Harness(streamCount = 2)
        val dispatcher = MultiStreamDispatcher(
            chunker = Chunker(),
            meta = meta,
            source = ChunkSource { payload.inputStream() },
            factory = StreamChannelFactory { id -> h.channels.firstOrNull { it.id == id } },
            streamCount = 2,
            requestedChunkSize = chunkSize,
            doneIndexes = listOf(0),
            workerDispatcher = rxDipatcher,
        )
        h.dispatcher = dispatcher
        var result: MultiStreamResult? = null
        val job = launch { result = dispatcher.send() }

        awaitUntil(condition = { h.channels.sumOf { s -> s.sentIndexes.size } == 18 }, describe = { "sent=" + h.channels.sumOf { s -> s.sentIndexes.size } + " dead=" + h.dispatcher.deadChannelsSnapshot() })
        // Feed the missing confirmation manually (exercises direct inbound ingestion).
        dispatcher.onInboundFrame(
            0,
            ChunkFrame.serialize(
                ChunkFrame.AckBatch(meta.transferId, meta.fileId, (0 until 19).toList()),
            ),
        )
        job.join()

        val completed = result as MultiStreamResult.Completed
        assertEquals(18, completed.chunksSent)
        assertEquals(1, completed.chunksSkippedResume)
        assertEquals(chunkSize.toLong(), completed.bytesSkippedResume)
        assertFalse(h.channels.any { it.sentIndexes.contains(0) })
        assertEquals(totalBytes - chunkSize, completed.bytesSent)
        assertEquals(totalBytes, dispatcher.progress.value.bytesDone)
        rxDipatcher.close()
        rxExecutor.shutdownNow()
        Unit
    }
}
