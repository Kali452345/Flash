package com.transfer.flash.ui.transfer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.transfer.model.WsDiscoveredDevice
import com.transfer.flash.core.transfer.model.WsPeer
import com.transfer.flash.core.transfer.model.WsTransferDirection
import com.transfer.flash.core.transfer.model.WsTransferItem
import com.transfer.flash.core.transfer.model.WsTransferStatus
import com.transfer.flash.core.transfer.model.WsTransferUiState
import java.util.Locale

/**
 * Simple screen for the experimental WebSocket transfer track.
 * Start the server, connect to any number of peer addresses (e.g. the other two
 * phones in a three-device mesh), then push a picked file to one peer or all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WsTransferScreen(
    state: WsTransferUiState,
    onBack: () -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onConnectDiscovered: (String) -> Unit,
    onDisconnectPeer: (String) -> Unit,
    onSendFile: (Uri, String?) -> Unit,
) {
    val context = LocalContext.current
    val pendingTarget = remember { mutableStateOf<String?>(null) }
    val exportSourcePath = remember { mutableStateOf<String?>(null) }
    val exportMimeType = remember { mutableStateOf("*/*") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onSendFile(uri, pendingTarget.value)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(exportMimeType.value)
    ) { destUri ->
        if (destUri != null && exportSourcePath.value != null) {
            WsFileActions.exportFileToUri(context, exportSourcePath.value!!, destUri)
        }
    }

    val onOpenFile: (WsTransferItem) -> Unit = { item ->
        WsFileActions.openTransfer(context, item)
    }
    val onShareFile: (WsTransferItem) -> Unit = { item ->
        WsFileActions.shareTransfer(context, item)
    }
    val onExportFile: (WsTransferItem) -> Unit = { item ->
        val file = WsFileActions.resolveFile(context, item)
        val path = file?.absolutePath ?: item.filePath.orEmpty()
        exportSourcePath.value = path
        exportMimeType.value = WsFileActions.resolveMimeType(item.fileName)
        exportLauncher.launch(item.fileName)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebSocket Transfer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                WsServerCard(
                    state = state,
                    onStartServer = onStartServer,
                    onStopServer = onStopServer,
                )
            }

            item {
                WsSectionTitle("Discovered devices (${state.discovered.size})")
            }

            if (state.discovered.isEmpty()) {
                item {
                    Text(
                        text = "Searching for Flash devices... New devices appear here automatically while discovery is on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.discovered, key = { "discovered-" + it.deviceId }) { device ->
                    WsDiscoveredRow(
                        device = device,
                        onConnect = { onConnectDiscovered(device.deviceId) },
                    )
                }
            }

            item {
                WsConnectCard(
                    state = state,
                    onHostChanged = onHostChanged,
                    onPortChanged = onPortChanged,
                    onConnect = onConnect,
                )
            }

            item {
                WsSectionTitle("Paired peers (${state.peers.size})")
            }

            if (state.peers.isEmpty()) {
                item {
                    Text(
                        text = "No peers connected. Tap Connect on a discovered device above — pairing is accepted automatically and remembered.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    OutlinedButton(
                        onClick = {
                            pendingTarget.value = null
                            filePicker.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Send a file to all ${state.peers.size} peers")
                    }
                }
                items(state.peers, key = { it.deviceId }) { peer ->
                    WsPeerRow(
                        peer = peer,
                        onSendFile = {
                            pendingTarget.value = peer.deviceId
                            filePicker.launch(arrayOf("*/*"))
                        },
                        onDisconnect = { onDisconnectPeer(peer.deviceId) },
                    )
                }
            }

            item {
                WsSectionTitle("Transfers")
            }

            if (state.transfers.isEmpty()) {
                item {
                    Text(
                        text = "No transfers yet. Received files land in the app's ws-received folder.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.transfers, key = { it.id + it.direction }) { transfer ->
                    WsTransferRow(
                        transfer = transfer,
                        onOpenFile = onOpenFile,
                        onShareFile = onShareFile,
                        onExportFile = onExportFile,
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun WsServerCard(
    state: WsTransferUiState,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = state.friendlyName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Device ID ${state.localDeviceId.take(8)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.isServerRunning && state.localAddresses.isNotEmpty()) {
                Text(
                    text = "Your address ${state.localAddresses.joinToString { "$it:${state.listenPort}" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isServerRunning) {
                    OutlinedButton(onClick = onStopServer) {
                        Text("Stop server")
                    }
                } else {
                    Button(onClick = onStartServer) {
                        Text("Start server")
                    }
                }
                if (state.listenPort > 0) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = "Port ${state.listenPort}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WsConnectCard(
    state: WsTransferUiState,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Connect to a peer",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = state.hostInput,
                    onValueChange = onHostChanged,
                    label = { Text("Peer IP address") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.portInput,
                    onValueChange = onPortChanged,
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(112.dp),
                )
            }
            Button(
                onClick = onConnect,
                enabled = !state.isConnecting,
            ) {
                Text(if (state.isConnecting) "Connecting" else "Connect")
            }
            state.lastConnectResult?.let { result ->
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WsDiscoveredRow(
    device: WsDiscoveredDevice,
    onConnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(
                    text = device.friendlyName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(device.address)
            },
            overlineContent = if (device.paired) {
                { Text("Paired before") }
            } else {
                null
            },
            trailingContent = {
                Button(
                    onClick = onConnect,
                    enabled = !device.connected && !device.connecting,
                ) {
                    Text(
                        when {
                            device.connected -> "Connected"
                            device.connecting -> "Pairing"
                            device.paired -> "Reconnect"
                            else -> "Connect"
                        }
                    )
                }
            },
        )
    }
}

@Composable
private fun WsPeerRow(
    peer: WsPeer,
    onSendFile: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(
                    text = peer.friendlyName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    text = "${peer.address} · " + if (peer.outbound) "outbound" else "inbound",
                )
            },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSendFile) {
                        Text("Send")
                    }
                    OutlinedButton(onClick = onDisconnect) {
                        Text("Drop")
                    }
                }
            },
        )
    }
}

@Composable
private fun WsTransferRow(
    transfer: WsTransferItem,
    onOpenFile: (WsTransferItem) -> Unit,
    onShareFile: (WsTransferItem) -> Unit,
    onExportFile: (WsTransferItem) -> Unit,
) {
    val context = LocalContext.current
    val resolvedFile = remember(transfer.filePath, transfer.fileName, transfer.status, transfer.detail) {
        WsFileActions.resolveFile(context, transfer)
    }
    val canOpen = resolvedFile != null || transfer.status == WsTransferStatus.COMPLETED

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (canOpen) {
                    Modifier.clickable { onOpenFile(transfer) }
                } else {
                    Modifier
                }
            )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = transfer.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (canOpen) {
                    Text(
                        text = "READY",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Text(
                text = buildString {
                    append(if (transfer.direction == WsTransferDirection.SENDING) "To " else "From ")
                    append(transfer.peerName)
                    append(" · ")
                    append(formatBytes(transfer.bytesDone))
                    if (transfer.bytesTotal >= 0) {
                        append(" / ")
                        append(formatBytes(transfer.bytesTotal))
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (transfer.status == WsTransferStatus.ACTIVE) {
                if (transfer.bytesTotal > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (transfer.bytesDone.toDouble() / transfer.bytesTotal.toDouble())
                                .toFloat()
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Text(
                text = when (transfer.status) {
                    WsTransferStatus.ACTIVE ->
                        transfer.detail.ifBlank {
                            if (transfer.direction == WsTransferDirection.SENDING) "Sending..." else "Receiving..."
                        }
                    WsTransferStatus.COMPLETED ->
                        transfer.detail.ifBlank { "Completed" }
                    WsTransferStatus.FAILED ->
                        "Failed — " + transfer.detail.ifBlank { "unknown error" }
                },
                style = MaterialTheme.typography.labelMedium,
                color = when (transfer.status) {
                    WsTransferStatus.FAILED -> MaterialTheme.colorScheme.error
                    WsTransferStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                    WsTransferStatus.ACTIVE -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            if (canOpen) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = { onOpenFile(transfer) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Open")
                    }
                    OutlinedButton(
                        onClick = { onExportFile(transfer) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Export")
                    }
                    OutlinedButton(
                        onClick = { onShareFile(transfer) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Share")
                    }
                }
            }
        }
    }
}

@Composable
private fun WsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    do {
        value /= 1024.0
        unitIndex++
    } while (value >= 1024.0 && unitIndex < units.lastIndex)
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}
