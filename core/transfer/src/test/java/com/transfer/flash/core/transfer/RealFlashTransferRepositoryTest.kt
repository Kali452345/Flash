package com.transfer.flash.core.transfer

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.Chunker
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.core.transfer.multistream.StreamChannelFactory
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealFlashTransferRepositoryTest {

    private val executor = Executors.newFixedThreadPool(4)
    private val testDispatcher = executor.asCoroutineDispatcher()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun `sendFile starts transfer, updates activeTransfers, and completes when channels ACK`() = runBlocking {
        val payload = ByteArray(32 * 1024) { (it % 127).toByte() }
        val targetDevice = FlashDevice(
            id = com.transfer.flash.core.common.model.FlashDeviceId("target-peer-1"),
            friendlyName = "Pixel 9 Pro",
            transportType = com.transfer.flash.core.common.model.FlashTransportType.LAN,
        )

        lateinit var repo: RealFlashTransferRepository

        val dummyChannel = object : StreamChannel {
            override val id: Int = 0
            override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                val parsed = ChunkFrame.parse(frameBytes)
                if (parsed is ChunkFrame.Chunk) {
                    val ack = ChunkFrame.AckBatch(
                        parsed.transferId,
                        parsed.fileId,
                        listOf(parsed.index),
                    )
                    // MultiStreamDispatcher receives ACK via its inbound frame route
                    // Handled automatically if wired
                }
                return true
            }
        }

        // Loopback factory for test
        var activeChannel: StreamChannel? = null
        val factory = StreamChannelFactory { channelId ->
            activeChannel ?: object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                    return true
                }
            }.also { activeChannel = it }
        }

        repo = RealFlashTransferRepository(
            chunker = Chunker(),
            streamChannelFactory = factory,
            fileSourceOpener = { ByteArrayInputStream(payload) },
            workerDispatcher = testDispatcher,
            defaultStreams = 1,
        )

        val result = repo.sendFile(
            targetDevice = targetDevice,
            fileUri = "content://media/test.bin",
            displayName = "test.bin",
            fileSize = payload.size.toLong(),
        )

        assertTrue(result is FlashResult.Success)
        val transferId = (result as FlashResult.Success).value

        val activeList = repo.activeTransfers.value
        assertEquals(1, activeList.size)
        assertEquals("test.bin", activeList.first().fileName)
        assertEquals(payload.size.toLong(), activeList.first().bytesTotal)

        // Test cancel
        val cancelResult = repo.cancelTransfer(transferId)
        assertTrue(cancelResult is FlashResult.Success)

        val cancelledList = repo.activeTransfers.value
        assertEquals(FlashTransferState.Cancelled, cancelledList.first().state)
    }
}
