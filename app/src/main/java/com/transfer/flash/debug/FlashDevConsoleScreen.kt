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
                DevButton("BG service", enabled = started) {
                    FlashBackgroundService.start(context)
                    logLine = "foreground service started"
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
            FlashText(
                "Endpoints (${endpoints.size}) — tap to connect",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            LazyColumn(Modifier.weight(1f).padding(top = 4.dp)) {
                items(endpoints, key = { it.deviceId.value }) { ep ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable {
                                val net = DiscoveryEngineHolder.currentNetwork() ?: return@clickable
                                scope.launch {
                                    val result = net.connect(
                                        com.transfer.flash.core.common.model.FlashDevice(
                                            id = ep.deviceId,
                                            friendlyName = ep.friendlyName,
                                            transportType = ep.transportType,
                                        ),
                                    )
                                    connectLog = when (result) {
                                        is com.transfer.flash.core.common.result.FlashResult.Success ->
                                            "Connected to ${ep.friendlyName}"
                                        is com.transfer.flash.core.common.result.FlashResult.Failure ->
                                            "Connect failed: ${result.error}"
                                    }
                                }
                            }
                            .padding(10.dp),
                    ) {
                        FlashText(ep.friendlyName, style = MaterialTheme.typography.titleSmall)
                        FlashText(
                            "${ep.deviceId.value.take(12)}… · ${ep.hostAddress}:${ep.port} · ${ep.transportType}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
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
