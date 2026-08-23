package com.transfer.flash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.SampleFlashChatRepository
import com.transfer.flash.ui.chat.FlashChatListScreen
import com.transfer.flash.ui.chat.FlashConversationScreen
import com.transfer.flash.ui.theme.FlashMaterialTheme
import com.transfer.flash.ui.theme.FlashTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Showcase host. Provisional demo pages (LAN home, icon/motion QA sheets, experimental
 * WS transfer) were removed per owner decision — engine code (LanController)
 * remains for relocation into :core:* per docs/core-upgrade-plan.md.
 *
 * Bottom navigation + settings pages land via docs/ui-page-plan.md.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        setContent {
            FlashMaterialTheme {
                FlashApp(showDevConsoleEntry = isDebuggable)
            }
        }
    }
}

@Composable
fun FlashApp(showDevConsoleEntry: Boolean = false) {
    var showConversation by remember { mutableStateOf(false) }
    var showDevConsole by remember { mutableStateOf(false) }
    val chatRepository = remember { SampleFlashChatRepository() }
    val conversationState by chatRepository.conversationState.collectAsState()
    val chatListState by chatRepository.chatListState.collectAsState()

    if (showDevConsole) {
        FlashTheme {
            com.transfer.flash.debug.FlashDevConsoleScreen(
                context = androidx.compose.ui.platform.LocalContext.current,
                onClose = { showDevConsole = false },
            )
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        if (showConversation) {
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
                    onSearchClick = { /* UI-024 global search */ },
                    onConversationLongClick = chatRepository::enterListSelectionMode,
                    onToggleSelection = chatRepository::toggleListSelection,
                    onArchiveConversation = chatRepository::archiveConversation,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // P3.5/E: debug-only Dev Console entry. Release builds never see this.
        if (showDevConsoleEntry) {
            Box(
                Modifier
                    .align(androidx.compose.ui.Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                com.transfer.flash.debug.DevConsoleChip(onClick = { showDevConsole = true })
            }
        }
    }
}
