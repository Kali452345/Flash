package com.transfer.flash.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.discovery.FlashDiscoveryState
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.DiscoveryModePolicy
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.ui.theme.FlashText
import kotlinx.coroutines.launch

/**
 * PROVISIONAL debug-only Dev Console (P3.5/E). Lets the owner exercise
 * continuous discovery on real devices BEFORE the PART 2 bottom-nav + Nearby
 * page exists. Deleted/replaced by the real Nearby tab — do not polish.
 *
 * Entry point: floating chip in MainActivity, visible only when the app is
 * debuggable (FLAG_DEBUGGABLE), so release builds never see it.
 */
@Composable
fun FlashDevConsoleScreen(
    context: android.content.Context,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf<CompositeDiscovery?>(null) }
    var logLine by remember { mutableStateOf("Idle") }
    var started by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(FlashDiscoveryMode.STANDARD) }

    val endpointsFallback = remember {
        kotlinx.coroutines.flow.MutableStateFlow(
            emptyList<com.transfer.flash.core.discovery.FlashDiscoveredEndpoint>(),
        )
    }
    val endpoints by (engine?.discoveredEndpoints ?: endpointsFallback).collectAsState()
    val statusFallback = remember {
        kotlinx.coroutines.flow.MutableStateFlow(FlashDiscoveryState())
    }
    val status by (engine?.state ?: statusFallback).collectAsState()
    val networkFallbackState = remember {
        kotlinx.coroutines.flow.MutableStateFlow(com.transfer.flash.core.network.FlashNetworkState())
    }
    val networkFallbackHealth = remember {
        kotlinx.coroutines.flow.MutableStateFlow(com.transfer.flash.core.network.FlashConnectionHealth.Offline)
    }
    val currentNetwork = DiscoveryEngineHolder.currentNetwork()
    val networkState by (currentNetwork?.networkState ?: networkFallbackState).collectAsState()
    val health by (currentNetwork?.connectionHealth ?: networkFallbackHealth).collectAsState()
    var connectLog by remember { mutableStateOf("") }

    fun start() {
        scope.launch {
            runCatching {
                engine = DiscoveryEngineHolder.ensureStarted(context)
                started = true
                DiscoveryEngineHolder.current()?.setMode(mode)
                logLine = "startAll ok (${mode.name})"
            }.onFailure { logLine = "start failed: ${it.message}" }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlashText("Flash Dev Console", style = MaterialTheme.typography.titleLarge)
                FlashText(
                    "Close",
                    modifier = Modifier.clickable { onClose() }.padding(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                DevButton(if (started) "Restart" else "Start") {
                    if (started) {
                        scope.launch {
                            DiscoveryEngineHolder.stopAll()
                            engine = null
                            started = false
                            start()
                        }
                    } else {
                        start()
                    }
                }
                DevButton("Stop") {
                    scope.launch {
                        runCatching {
                            DiscoveryEngineHolder.stopAll()
                            engine = null
                            started = false
                            logLine = "stopped"
                        }.onFailure { logLine = "stop failed: ${it.message}" }
                    }
                }
                DevButton("Probe Gateway") {
                    val net = DiscoveryEngineHolder.currentNetwork()
                    if (net == null) {
                        logLine = "Start network first"
                        return@DevButton
                    }
                    scope.launch {
                        // In Android Hotspot, the host is virtually always 192.168.43.1 on port 8080 or the advertised port
                        logLine = "Probing hotspot gateway 192.168.43.1..."
                        // Probe common ports or iterate through network endpoints
                        val result = net.connectManual("192.168.43.1", 0)
                        logLine = when (result) {
                            is com.transfer.flash.core.common.result.FlashResult.Success ->
                                "Connected directly to Hotspot Host!"
                            is com.transfer.flash.core.common.result.FlashResult.Failure ->
                                "Probe failed: ${result.error}"
                        }
                    }
                }
            }

            FlashText(
                "Mode",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                FlashDiscoveryMode.entries.forEach { m ->
                    DevButton(
                        label = m.name.lowercase().replaceFirstChar { it.uppercase() },
                        highlighted = m == mode,
                    ) {
                        mode = m
                        val e = engine
                        if (e == null) {
                            logLine = "mode ${m.name} applies at start"
                        } else {
                            scope.launch {
                                e.setMode(m)
                                logLine = "mode -> ${m.name}"
                            }
                        }
                    }
                }
            }

            FlashText(
                "Status: ${status.statusMessage} · Network: ${health.name} (peers ${networkState.activePeerCount})",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            if (connectLog.isNotEmpty()) {
                FlashText(
                    connectLog,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val transferFallback = remember {
                kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.transfer.flash.core.transfer.model.FlashTransfer>())
            }
            val currentTransfers = DiscoveryEngineHolder.currentTransfers()
            val activeTransfers by (currentTransfers?.activeTransfers ?: transferFallback).collectAsState()

            val sessionsFallback = remember {
                kotlinx.coroutines.flow.MutableStateFlow(emptyMap<com.transfer.flash.core.common.model.FlashDeviceId, com.transfer.flash.core.network.FlashSession>())
            }
            val activeSessionsMap by (currentNetwork?.activeSessions ?: sessionsFallback).collectAsState()
            val activeSessions = activeSessionsMap.values.toList()

            var targetDeviceForPick by remember { mutableStateOf<com.transfer.flash.core.common.model.FlashDevice?>(null) }
            val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
            ) { uri: android.net.Uri? ->
                val targetDevice = targetDeviceForPick
                if (uri != null && targetDevice != null) {
                    val repo = DiscoveryEngineHolder.currentTransfers() ?: return@rememberLauncherForActivityResult
                    scope.launch {
                        var fileName = "selected_file"
                        var fileSize = 1024 * 1024L
                        runCatching {
                            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                if (cursor.moveToFirst()) {
                                    if (nameIdx != -1) fileName = cursor.getString(nameIdx)
                                    if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
                                }
                            }
                        }
                        val result = repo.sendFile(
                            targetDevice = targetDevice,
                            fileUri = uri.toString(),
                            displayName = fileName,
                            fileSize = fileSize,
                        )
                        connectLog = when (result) {
                            is com.transfer.flash.core.common.result.FlashResult.Success ->
                                "Multi-stream transfer started: $fileName (${fileSize / 1024} KB)"
                            is com.transfer.flash.core.common.result.FlashResult.Failure ->
                                "Transfer failed: ${result.error}"
                        }
                    }
                }
            }

            if (activeTransfers.isNotEmpty()) {
                FlashText(
                    "Active Transfers (${activeTransfers.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
                LazyColumn(Modifier.fillMaxWidth().weight(0.35f).padding(top = 4.dp)) {
                    items(activeTransfers, key = { it.id.value }) { transfer ->
                        val percent = if (transfer.bytesTotal > 0) {
                            (transfer.bytesDone * 100 / transfer.bytesTotal).toInt()
                        } else 0
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(8.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                FlashText(transfer.fileName, style = MaterialTheme.typography.titleSmall)
                                FlashText("${percent}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            FlashText(
                                "State: ${transfer.state} · ${transfer.bytesDone / 1024} / ${transfer.bytesTotal / 1024} KB · ${transfer.speedBytesPerSec / 1024} KB/s",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                        }
                    }
                }
            }

            LazyColumn(Modifier.weight(0.65f).padding(top = 4.dp)) {
                if (activeSessions.isNotEmpty()) {
                    item {
                        FlashText(
                            "Active Connected Peers (${activeSessions.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    items(activeSessions, key = { "session-${it.peerDeviceId.value}" }) { session ->
                        val peerDev = session.peer
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(10.dp),
                        ) {
                            FlashText(peerDev.friendlyName, style = MaterialTheme.typography.titleSmall)
                            FlashText(
                                "${session.peerDeviceId.value.take(12)}… · ${session.transportType} · Connected",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                DevButton("Ping Msg") {
                                    val chatRepo = DiscoveryEngineHolder.currentChats() ?: return@DevButton
                                    scope.launch {
                                        android.util.Log.i("DEV", "Triggered Ping Msg to ${peerDev.friendlyName} (${session.peerDeviceId.value})")
                                        chatRepo.openConversation(session.peerDeviceId.value)
                                        chatRepo.sendText("Hello from Flash Dev Console! Live session ping: ${System.currentTimeMillis()}")
                                        connectLog = "Sent ping message to ${peerDev.friendlyName}"
                                    }
                                }
                                DevButton("Choose File & Send") {
                                    android.util.Log.i("DEV", "Opening file picker for ${peerDev.friendlyName}")
                                    targetDeviceForPick = peerDev
                                    filePickerLauncher.launch(arrayOf("*/*"))
                                }
                                DevButton("Test 10MB") {
                                    val transferRepo = DiscoveryEngineHolder.currentTransfers() ?: return@DevButton
                                    scope.launch {
                                        android.util.Log.i("DEV", "Starting 10MB test transfer to ${peerDev.friendlyName}")
                                        val result = transferRepo.sendFile(
                                            targetDevice = peerDev,
                                            fileUri = "file:///dummy/test_payload.bin",
                                            displayName = "test_10mb.bin",
                                            fileSize = 10 * 1024 * 1024L,
                                        )
                                        connectLog = when (result) {
                                            is com.transfer.flash.core.common.result.FlashResult.Success ->
                                                "10MB Multi-stream transfer started to ${peerDev.friendlyName}"
                                            is com.transfer.flash.core.common.result.FlashResult.Failure ->
                                                "Transfer failed: ${result.error}"
                                        }
                                        android.util.Log.i("DEV", "SendFile result: $connectLog")
                                    }
                                }
                                DevButton("Disconnect") {
                                    android.util.Log.i("DEV", "Disconnecting session with ${peerDev.friendlyName}")
                                    session.disconnect("User disconnected via Dev Console")
                                    connectLog = "Disconnected from ${peerDev.friendlyName}"
                                }
                            }
                        }
                    }
                }

                item {
                    FlashText(
                        "Discovered Endpoints (${endpoints.size}) — mDNS",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }

                items(endpoints, key = { "ep-${it.deviceId.value}" }) { ep ->
                    val targetDev = com.transfer.flash.core.common.model.FlashDevice(
                        id = ep.deviceId,
                        friendlyName = ep.friendlyName,
                        transportType = ep.transportType,
                    )
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(10.dp),
                    ) {
                        FlashText(ep.friendlyName, style = MaterialTheme.typography.titleSmall)
                        FlashText(
                            "${ep.deviceId.value.take(12)}… · ${ep.hostAddress}:${ep.port} · ${ep.transportType}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            DevButton("Connect") {
                                val net = DiscoveryEngineHolder.currentNetwork() ?: return@DevButton
                                scope.launch {
                                    val result = net.connect(targetDev)
                                    connectLog = when (result) {
                                        is com.transfer.flash.core.common.result.FlashResult.Success ->
                                            "Connected to ${ep.friendlyName}"
                                        is com.transfer.flash.core.common.result.FlashResult.Failure ->
                                            "Connect failed: ${result.error}"
                                    }
                                }
                            }
                            DevButton("Ping Msg") {
                                val chatRepo = DiscoveryEngineHolder.currentChats() ?: return@DevButton
                                scope.launch {
                                    chatRepo.openConversation(ep.deviceId.value)
                                    chatRepo.sendText("Hello from Flash Dev Console! Test ping: ${System.currentTimeMillis()}")
                                    connectLog = "Sent ping message to ${ep.friendlyName}"
                                }
                            }
                            DevButton("Choose File & Send") {
                                targetDeviceForPick = targetDev
                                filePickerLauncher.launch(arrayOf("*/*"))
                            }
                            DevButton("Test 10MB") {
                                val transferRepo = DiscoveryEngineHolder.currentTransfers() ?: return@DevButton
                                scope.launch {
                                    val result = transferRepo.sendFile(
                                        targetDevice = targetDev,
                                        fileUri = "file:///dummy/test_payload.bin",
                                        displayName = "test_10mb.bin",
                                        fileSize = 10 * 1024 * 1024L,
                                    )
                                    connectLog = when (result) {
                                        is com.transfer.flash.core.common.result.FlashResult.Success ->
                                            "10MB Multi-stream transfer started (${result.value.value.take(8)})"
                                        is com.transfer.flash.core.common.result.FlashResult.Failure ->
                                            "Transfer failed: ${result.error}"
                                    }
                                }
                            }
                        }
                    }
                }
            }

            FlashText(
                logLine,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            FlashText(
                "Provisional debug tool — replaced by the Nearby tab (PART 2).",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
    }
}

@Composable
fun DevConsoleChip(onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                RoundedCornerShape(16.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        FlashText(
            "Dev",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun DevButton(
    label: String,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        highlighted -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val fg = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .background(bg, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        FlashText(label, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}
