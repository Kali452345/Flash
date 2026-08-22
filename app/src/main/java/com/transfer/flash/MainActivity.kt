package com.transfer.flash

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.transfer.flash.lan.LanController
import com.transfer.flash.lan.LanUiState
import com.transfer.flash.lan.PeerConnectionState
import com.transfer.flash.model.DiscoveredDevice
import com.transfer.flash.core.messaging.SampleFlashChatRepository
import com.transfer.flash.ui.chat.FlashChatListScreen
import com.transfer.flash.ui.chat.FlashConversationScreen
import com.transfer.flash.ui.icons.FlashIconSheet
import com.transfer.flash.ui.theme.FlashMaterialTheme
import com.transfer.flash.ui.theme.FlashMotionSheet
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.transfer.WsTransferScreen
import com.transfer.flash.wstransfer.WsTransferManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlashMaterialTheme {
                FlashApp()
            }
        }
    }
}

@Composable
fun FlashApp() {
    val context = LocalContext.current
    val controller = remember { LanController(context) }
    val state by controller.state.collectAsState()
    var showConversation by remember { mutableStateOf(false) }
    var showLanHome by remember { mutableStateOf(false) }
    var showIconSheet by remember { mutableStateOf(false) }
    var showMotionSheet by remember { mutableStateOf(false) }
    var showWsTransfer by remember { mutableStateOf(false) }
    val chatRepository = remember { SampleFlashChatRepository() }
    val conversationState by chatRepository.conversationState.collectAsState()
    val chatListState by chatRepository.chatListState.collectAsState()
    val wsTransferManager = remember { WsTransferManager(context) }
    val wsTransferState by wsTransferManager.state.collectAsState()

    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    DisposableEffect(wsTransferManager) {
        onDispose { wsTransferManager.close() }
    }

    if (showIconSheet) {
        BackHandler { showIconSheet = false }
        FlashTheme {
            FlashIconSheet()
        }
    } else if (showMotionSheet) {
        BackHandler { showMotionSheet = false }
        FlashTheme {
            FlashMotionSheet()
        }
    } else if (showWsTransfer) {
        BackHandler { showWsTransfer = false }
        WsTransferScreen(
            state = wsTransferState,
            onBack = { showWsTransfer = false },
            onStartServer = wsTransferManager::startServer,
            onStopServer = wsTransferManager::stopServer,
            onHostChanged = wsTransferManager::updateHostInput,
            onPortChanged = wsTransferManager::updatePortInput,
            onConnect = wsTransferManager::connectToPeer,
            onConnectDiscovered = wsTransferManager::connectToDiscoveredPeer,
            onDisconnectPeer = wsTransferManager::disconnectPeer,
            onSendFile = wsTransferManager::sendFile,
        )
    } else if (showLanHome) {
        BackHandler { showLanHome = false }
        FlashHomeScreen(
            state = state,
            onStartLan = controller::startLan,
            onStopLan = controller::stopLan,
            onProbeDevice = controller::probe,
            onDisconnectDevice = controller::disconnect,
            onManualHostChanged = controller::updateManualHost,
            onManualPortChanged = controller::updateManualPort,
            onManualConnect = controller::connectManual,
            onManualDisconnect = controller::disconnectManual,
            onOpenConversation = {
                chatRepository.openConversation("conv-false-school")
                showLanHome = false
                showConversation = true
            },
            onOpenIconSheet = { showIconSheet = true },
            onOpenMotionSheet = { showMotionSheet = true },
            onOpenWsTransfer = { showWsTransfer = true },
        )
    } else if (showConversation) {
        FlashTheme {
            FlashConversationScreen(
                state = conversationState,
                onBack = {
                    chatRepository.closeConversation()
                    showConversation = false
                },
                onOpenPeerDetails = { showConversation = false },
                onSendText = chatRepository::sendText,
                onAttachmentClick = chatRepository::openAttachmentPicker,
            )
        }
    } else {
        FlashTheme {
            FlashChatListScreen(
                state = chatListState,
                onConversationClick = { id ->
                    chatRepository.openConversation(id)
                    chatRepository.clearListSelection()
                    showConversation = true
                },
                onSearchClick = { /* UI-024 */ },
                onLanClick = { showLanHome = true },
                onConversationLongClick = chatRepository::enterListSelectionMode,
                onToggleSelection = chatRepository::toggleListSelection,
                onArchiveConversation = chatRepository::archiveConversation,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashHomeScreen(
    state: LanUiState,
    onStartLan: () -> Unit,
    onStopLan: () -> Unit,
    onProbeDevice: (DiscoveredDevice) -> Unit,
    onDisconnectDevice: (DiscoveredDevice) -> Unit,
    onManualHostChanged: (String) -> Unit,
    onManualPortChanged: (String) -> Unit,
    onManualConnect: () -> Unit,
    onManualDisconnect: () -> Unit,
    onOpenConversation: () -> Unit = {},
    onOpenIconSheet: () -> Unit = {},
    onOpenMotionSheet: () -> Unit = {},
    onOpenWsTransfer: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Flash") },
                actions = {
                    IconButton(onClick = onOpenConversation) {
                        Icon(
                            Icons.Default.ChatBubbleOutline,
                            contentDescription = "Open chat",
                        )
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
                LocalDeviceCard(
                    state = state,
                    onStartLan = onStartLan,
                    onStopLan = onStopLan,
                )
            }

            item {
                OutlinedButton(onClick = onOpenIconSheet, modifier = Modifier.fillMaxWidth()) {
                    Text("Icon sheet (QA)")
                }
            }

            item {
                OutlinedButton(onClick = onOpenMotionSheet, modifier = Modifier.fillMaxWidth()) {
                    Text("Motion sheet (QA)")
                }
            }

            item {
                OutlinedButton(onClick = onOpenWsTransfer, modifier = Modifier.fillMaxWidth()) {
                    Text("WebSocket transfer (experimental)")
                }
            }

            item {
                SectionTitle("Nearby Devices")
            }

            item {
                ManualConnectionCard(
                    state = state,
                    onManualHostChanged = onManualHostChanged,
                    onManualPortChanged = onManualPortChanged,
                    onManualConnect = onManualConnect,
                    onManualDisconnect = onManualDisconnect,
                )
            }

            if (state.devices.isEmpty()) {
                item {
                    EmptyDeviceList(isRunning = state.isRunning)
                }
            } else {
                items(state.devices, key = { it.deviceId }) { device ->
                    DeviceRow(
                        device = device,
                        connectionState = state.connectionStates[device.deviceId] ?: PeerConnectionState.IDLE,
                        onProbeDevice = onProbeDevice,
                        onDisconnectDevice = onDisconnectDevice,
                    )
                }
            }

            state.lastProbeResult?.let { result ->
                item {
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun ManualConnectionCard(
    state: LanUiState,
    onManualHostChanged: (String) -> Unit,
    onManualPortChanged: (String) -> Unit,
    onManualConnect: () -> Unit,
    onManualDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Manual LAN Connection",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = state.manualHost,
                    onValueChange = onManualHostChanged,
                    label = { Text("IP address") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.manualPort,
                    onValueChange = onManualPortChanged,
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.width(112.dp),
                )
            }
            Button(
                onClick = {
                    if (state.manualConnectionState == PeerConnectionState.CONNECTED) {
                        onManualDisconnect()
                    } else {
                        onManualConnect()
                    }
                },
                enabled = state.manualConnectionState != PeerConnectionState.CONNECTING,
            ) {
                Text(connectionButtonText(state.manualConnectionState))
            }
            state.manualConnectionResult?.let { result ->
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
private fun LocalDeviceCard(
    state: LanUiState,
    onStartLan: () -> Unit,
    onStopLan: () -> Unit,
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
            if (state.localAddresses.isNotEmpty() && state.listenPort > 0) {
                Text(
                    text = "Manual address ${state.localAddresses.joinToString { "$it:${state.listenPort}" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row {
                if (state.isRunning) {
                    OutlinedButton(onClick = onStopLan) {
                        Text("Stop LAN")
                    }
                } else {
                    Button(onClick = onStartLan) {
                        Text("Start LAN")
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
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun EmptyDeviceList(isRunning: Boolean) {
    Text(
        text = if (isRunning) {
            "No Flash devices found on this LAN yet."
        } else {
            "Start LAN to advertise this device and scan the local network."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DeviceRow(
    device: DiscoveredDevice,
    connectionState: PeerConnectionState,
    onProbeDevice: (DiscoveredDevice) -> Unit,
    onDisconnectDevice: (DiscoveredDevice) -> Unit,
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
                Text("${device.hostAddress}:${device.port}  ${device.transportType}")
            },
            trailingContent = {
                Button(
                    onClick = {
                        if (connectionState == PeerConnectionState.CONNECTED) {
                            onDisconnectDevice(device)
                        } else {
                            onProbeDevice(device)
                        }
                    },
                    enabled = device.port > 0 &&
                        connectionState != PeerConnectionState.CONNECTING,
                ) {
                    Text(connectionButtonText(connectionState))
                }
            },
        )
    }
}

private fun connectionButtonText(connectionState: PeerConnectionState): String {
    return when (connectionState) {
        PeerConnectionState.IDLE -> "Connect"
        PeerConnectionState.CONNECTING -> "Connecting"
        PeerConnectionState.CONNECTED -> "Disconnect"
        PeerConnectionState.FAILED -> "Retry"
    }
}

@Preview(showBackground = true)
@Composable
fun FlashHomePreview() {
    FlashMaterialTheme {
        FlashHomeScreen(
            state = LanUiState(
                localDeviceId = "12345678-1234-1234-1234-123456789abc",
                friendlyName = "Flash Pixel",
                isRunning = true,
                listenPort = 45821,
                status = "Scanning LAN",
                devices = listOf(
                    DiscoveredDevice(
                        deviceId = "peer",
                        friendlyName = "Flash Galaxy",
                        hostAddress = "192.168.1.42",
                        port = 45821,
                        serviceName = "Flash Galaxy",
                    )
                ),
            ),
            onStartLan = {},
            onStopLan = {},
            onProbeDevice = {},
            onDisconnectDevice = {},
            onManualHostChanged = {},
            onManualPortChanged = {},
            onManualConnect = {},
            onManualDisconnect = {},
        )
    }
}
