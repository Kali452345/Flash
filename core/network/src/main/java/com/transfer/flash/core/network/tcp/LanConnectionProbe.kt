@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.tcp

import android.content.Context
import com.transfer.flash.core.common.annotation.FlashInternalApi
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LanConnectionProbe(
    context: Context,
    private val localDeviceId: String,
    private val localFriendlyName: String,
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun probe(hostAddress: String, port: Int, peerDeviceId: String? = null): Result<LanProbeHello> =
        probeEndpoint(hostAddress, port, peerDeviceId)

    suspend fun probeEndpoint(hostAddress: String, port: Int, peerDeviceId: String? = null): Result<LanProbeHello> = withContext(Dispatchers.IO) {
        connectSession(hostAddress, port, peerDeviceId) { _, _ -> }.map { session ->
            session.close("Probe complete")
            session.peerInfo
        }.onSuccess {
            Log.i(TAG, "LAN probe connected address=$hostAddress:$port peerId=${it.deviceId}")
        }
    }

    suspend fun connectSession(
        hostAddress: String,
        port: Int,
        peerDeviceId: String? = null,
        onDisconnected: (LanProbeHello, String) -> Unit,
    ): Result<LanSession> = withContext(Dispatchers.IO) {
        runCatching {
            val network = findLanNetwork()
            Log.i(
                TAG,
                "LAN probe connecting address=$hostAddress:$port " +
                    "deviceId=${peerDeviceId ?: "unknown"} network=${network?.networkHandle ?: "default"}",
            )
            val socket = createSocket(network)
            socket.connect(InetSocketAddress(hostAddress, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            writer.println(LanProbeMessages.hello(PROTOCOL_VERSION, localDeviceId, localFriendlyName))

            val response = reader.readLine()
                ?: error("No response from $hostAddress:$port")
            val parsed = LanProbeMessages.parseOk(response)
                ?: error("Malformed response from $hostAddress:$port")
            LanSession(
                socket = socket,
                reader = reader,
                writer = writer,
                localDeviceId = localDeviceId,
                localFriendlyName = localFriendlyName,
                peerInfo = parsed,
                onDisconnected = onDisconnected,
            ).also { it.start() }
        }.onFailure { error ->
            Log.w(TAG, "LAN probe failed address=$hostAddress:$port", error)
        }
    }

    suspend fun notifyDisconnect(hostAddress: String, port: Int, peerDeviceId: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val network = findLanNetwork()
            Log.i(
                TAG,
                "LAN disconnect notifying address=$hostAddress:$port " +
                    "deviceId=${peerDeviceId ?: "unknown"} network=${network?.networkHandle ?: "default"}",
            )
            createSocket(network).use { socket ->
                socket.connect(InetSocketAddress(hostAddress, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS

                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println(LanProbeMessages.disconnect(PROTOCOL_VERSION, localDeviceId, localFriendlyName))
            }
        }.onFailure { error ->
            Log.w(TAG, "LAN disconnect notify failed address=$hostAddress:$port", error)
        }
    }

    private fun createSocket(network: Network?): Socket {
        return network?.socketFactory?.createSocket() ?: Socket()
    }

    private fun findLanNetwork(): Network? {
        return connectivityManager.allNetworks.firstOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }
    }

    companion object {
        const val TAG = "LAN"
        const val PROTOCOL_VERSION = 1
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 4_000
    }
}
