package com.transfer.flash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.transfer.flash.core.messaging.SampleFlashChatRepository
import com.transfer.flash.ui.chat.FlashChatListScreen
import com.transfer.flash.ui.chat.FlashConversationScreen
import com.transfer.flash.ui.theme.FlashMaterialTheme
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Showcase host. Provisional demo pages (LAN home, icon/motion QA sheets, experimental
 * WS transfer) were removed per owner decision — engine code (LanController,
 * WsTransferManager) remains for relocation into :core:* per docs/core-upgrade-plan.md.
 *
 * Bottom navigation + settings pages land via docs/ui-page-plan.md.
 */
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
    var showConversation by remember { mutableStateOf(false) }
    val chatRepository = remember { SampleFlashChatRepository() }
    val conversationState by chatRepository.conversationState.collectAsState()
    val chatListState by chatRepository.chatListState.collectAsState()

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
}
