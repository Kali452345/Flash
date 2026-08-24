package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.result.FlashResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WsFlashNetworkTest {

    private var serverNetwork: WsFlashNetwork? = null
    private var clientNetwork: WsFlashNetwork? = null

    @After
    fun tearDown() {
        runBlocking {
            serverNetwork?.stop()
            clientNetwork?.stop()
        }
    }

    @Test(timeout = 10_000L)
    fun testServerAndClientMeshSession() {
        runBlocking {
            serverNetwork = WsFlashNetwork(
                context = null,
                localDeviceId = "device-server-123",
                localFriendlyName = "Server Phone",
            )
            val serverStart = serverNetwork!!.start(0)
            assertTrue(serverStart is FlashResult.Success)
            val serverPort = (serverStart as FlashResult.Success).value
            assertTrue(serverPort > 0)

            clientNetwork = WsFlashNetwork(
                context = null,
                localDeviceId = "device-client-456",
                localFriendlyName = "Client Phone",
            )
            val clientStart = clientNetwork!!.start(0)
            assertTrue(clientStart is FlashResult.Success)

            // Connect client to server
            val connectResult = clientNetwork!!.connectManual("127.0.0.1", serverPort)
            assertTrue(connectResult is FlashResult.Success)
            val clientSession = (connectResult as FlashResult.Success).value as WsSession

            assertEquals("device-server-123", clientSession.peerDeviceId.value)
            assertEquals("Server Phone", clientSession.peer.friendlyName)

            // Wait for server to observe inbound session
            var serverSession: WsSession? = null
            for (i in 1..50) {
                val sessions = serverNetwork!!.activeSessions.value
                if (sessions.isNotEmpty()) {
                    serverSession = sessions.values.first() as WsSession
                    break
                }
                kotlinx.coroutines.delay(50)
            }
            assertNotNull("Server should have registered inbound session", serverSession)
            assertEquals("device-client-456", serverSession!!.peerDeviceId.value)
            assertEquals("Client Phone", serverSession.peer.friendlyName)

            // Test Text message send from client to server
            var receivedText: String? = null
            val serverTextJob = launch {
                receivedText = serverSession!!.incomingText.first()
            }
            kotlinx.coroutines.delay(20)
            clientSession.sendText("Hello Server!")
            serverTextJob.join()
            assertEquals("Hello Server!", receivedText)

            // Test Binary message send from server to client
            val sampleBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            var receivedBytes: ByteArray? = null
            val clientBinaryJob = launch {
                receivedBytes = clientSession.incomingBinary.first()
            }
            kotlinx.coroutines.delay(20)
            serverSession!!.send(sampleBytes)
            clientBinaryJob.join()
            assertNotNull(receivedBytes)
            assertEquals(8, receivedBytes!!.size)
            assertEquals(1.toByte(), receivedBytes!![0])
            assertEquals(8.toByte(), receivedBytes!![7])

            // Test Disconnect
            clientSession.disconnect("Test finished")
            kotlinx.coroutines.delay(100)
            assertEquals(0, clientNetwork!!.activeSessions.value.size)
        }
    }
}
