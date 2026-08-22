package com.transfer.flash.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.messaging.model.FlashConversationUiState
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashQuotedReplyUi
import com.transfer.flash.core.messaging.model.FlashReaction
import com.transfer.flash.core.messaging.util.sampleFlashConversationState
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FlashConversationScreen(
    state: FlashConversationUiState,
    onBack: () -> Unit,
    onOpenPeerDetails: () -> Unit,
    onSendText: (String) -> Unit,
    onAttachmentClick: () -> Unit,
) {
    val context = LocalContext.current
    val motion = FlashTheme.motion
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var localMessages by remember(state.messages) { mutableStateOf(state.messages) }
    var draft by remember { mutableStateOf("") }
    var focusedMessage by remember { mutableStateOf<FlashMessageUi?>(null) }
    var replyingToMessage by remember { mutableStateOf<FlashMessageUi?>(null) }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var selectedMessageIds by remember { mutableStateOf(emptySet<String>()) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var mediaViewerVisible by remember { mutableStateOf(false) }
    var mediaViewerItems by remember { mutableStateOf(emptyList<FlashMediaViewerItem>()) }
    var mediaViewerStartIndex by remember { mutableStateOf(0) }

    // UI-029: group member sheet (demo roster until the repository feeds live members).
    var showGroupMembers by remember { mutableStateOf(false) }
    val groupMembers = remember(state.header.title) { sampleGroupMembers() }

    // UI-023: in-chat search state.
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var activeResultIndex by remember { mutableIntStateOf(-1) }
    val searchMatches = remember(searchQuery, localMessages) {
        FlashChatSearchMath.findMatches(localMessages, searchQuery)
    }

    fun closeSearch() {
        isSearchActive = false
        searchQuery = ""
        activeResultIndex = -1
    }

    val inSelectionMode = selectedMessageIds.isNotEmpty()

    // UI-030: connection health derived from header state.
    val connectionHealth = FlashNetworkStatusMath.resolveHealth(
        transport = state.header.transport,
        peerPresence = state.header.presence,
        peerCount = if (state.header.presence == FlashPeerPresence.Online || state.header.presence == FlashPeerPresence.Typing) 1 else 0,
    )

    // Hardware back press exits media viewer → search → selection mode
    BackHandler(enabled = mediaViewerVisible) {
        mediaViewerVisible = false
    }
    BackHandler(enabled = isSearchActive && !mediaViewerVisible) {
        closeSearch()
    }
    BackHandler(enabled = inSelectionMode && !isSearchActive && !mediaViewerVisible) {
        selectedMessageIds = emptySet()
    }

    // Auto-clear message highlight after 700ms pulse glow
    LaunchedEffect(highlightedMessageId) {
        if (highlightedMessageId != null) {
            delay(700L)
            highlightedMessageId = null
        }
    }

    fun jumpToMessage(targetId: String) {
        val targetIndex = localMessages.asReversed().indexOfFirst { it.id == targetId }
        if (targetIndex >= 0) {
            coroutineScope.launch {
                if (motion.reduceMotion) {
                    listState.scrollToItem(targetIndex)
                } else {
                    listState.animateScrollToItem(targetIndex)
                }
                highlightedMessageId = targetId
            }
        }
    }

    fun stepSearchResult(forward: Boolean) {
        if (searchMatches.isEmpty()) return
        activeResultIndex = FlashChatSearchMath.stepIndex(activeResultIndex, searchMatches.size, forward)
        searchMatches.getOrNull(activeResultIndex)?.let { jumpToMessage(it) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AnimatedContent(
                targetState = inSelectionMode to isSearchActive,
                transitionSpec = { motion.statusCrossfade() },
                label = "conversation_header_swap",
            ) { headerMode ->
                val (isSelecting, isSearching) = headerMode
                when {
                    isSelecting -> {
                        FlashSelectionToolbar(
                            selectedCount = selectedMessageIds.size,
                            onClose = { selectedMessageIds = emptySet() },
                            onCopy = {
                                val selectedTexts = localMessages
                                    .filter { it.id in selectedMessageIds }
                                    .joinToString("\n") { it.text }
                                copyToClipboard(context, selectedTexts)
                                selectedMessageIds = emptySet()
                            },
                            onReply = {
                                val singleId = selectedMessageIds.firstOrNull()
                                val msg = localMessages.firstOrNull { it.id == singleId }
                                if (msg != null) {
                                    replyingToMessage = msg
                                }
                                selectedMessageIds = emptySet()
                            },
                            onForward = {
                                Toast.makeText(context, "Forwarding ${selectedMessageIds.size} messages", Toast.LENGTH_SHORT).show()
                                selectedMessageIds = emptySet()
                            },
                            onDelete = {
                                Toast.makeText(context, "Deleted ${selectedMessageIds.size} messages", Toast.LENGTH_SHORT).show()
                                selectedMessageIds = emptySet()
                            },
                        )
                    }

                    isSearching -> {
                        // UI-023: in-chat search replaces the header while active.
                        FlashChatSearchBar(
                            query = searchQuery,
                            onQueryChanged = { newQuery ->
                                searchQuery = newQuery
                                activeResultIndex = FlashChatSearchMath.initialResultIndex(
                                    FlashChatSearchMath.findMatches(localMessages, newQuery).size,
                                )
                            },
                            activeResultIndex = activeResultIndex,
                            resultCount = searchMatches.size,
                            onClose = { closeSearch() },
                            onPrevious = { stepSearchResult(forward = false) },
                            onNext = { stepSearchResult(forward = true) },
                        )
                    }

                    else -> {
                        Column {
                            FlashChatHeader(
                                state = state.header,
                                onBack = onBack,
                                onAvatarClick = {
                                    if (state.header.isGroup) {
                                        showGroupMembers = true
                                    } else {
                                        onOpenPeerDetails()
                                    }
                                },
                                onSearchClick = { isSearchActive = true },
                            )
                            // UI-030 connection banner — hidden while fully connected.
                            AnimatedVisibility(
                                visible = connectionHealth != FlashConnectionHealth.Connected,
                                enter = fadeIn(motion.tweenNormalSpec()),
                                exit = fadeOut(motion.tweenFastSpec()),
                            ) {
                                FlashConnectionBanner(
                                    health = connectionHealth,
                                    onRetry = {
                                        Toast.makeText(context, "Reconnecting…", Toast.LENGTH_SHORT).show()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            FlashComposer(
                draft = draft,
                onDraftChanged = { draft = it },
                onAttachmentClick = {
                    showAttachmentSheet = true
                },
                isAttachmentExpanded = showAttachmentSheet,
                replyingTo = replyingToMessage,
                onDismissReply = { replyingToMessage = null },
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        onSendText(text)
                        draft = ""
                        replyingToMessage = null
                    }
                },
                onSendVoice = { voice ->
                    Toast.makeText(context, "Voice message sent", Toast.LENGTH_SHORT).show()
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            FlashMessageList(
                messages = localMessages,
                onOpenMessageActions = { msg -> focusedMessage = msg },
                selectedMessageIds = selectedMessageIds,
                inSelectionMode = inSelectionMode,
                onSelectToggle = { id ->
                    selectedMessageIds = if (id in selectedMessageIds) {
                        selectedMessageIds - id
                    } else {
                        selectedMessageIds + id
                    }
                },
                onToggleReaction = { messageId, emoji ->
                    localMessages = toggleMessageReaction(localMessages, messageId, emoji)
                },
                onReplySwipe = { msg ->
                    replyingToMessage = msg
                },
                onJumpToMessage = { targetId ->
                    jumpToMessage(targetId)
                },
                onImageClick = { msg, index ->
                    if (msg.images.isNotEmpty()) {
                        mediaViewerItems = msg.images.map { image ->
                            FlashMediaViewerItem(
                                image = image,
                                senderName = msg.senderName,
                                timeLabel = msg.timeLabel,
                            )
                        }
                        mediaViewerStartIndex = index
                        mediaViewerVisible = true
                    }
                },
                highlightedMessageId = highlightedMessageId,
                peerTypingName = if (state.header.presence == FlashPeerPresence.Typing) state.header.title else null,
                listState = listState,
                showSenderHeaders = state.header.isGroup,
                searchQuery = if (isSearchActive) searchQuery else null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                // NOTE: no .imePadding() here — the composer bottomBar already grows with
                // the IME via innerPadding; adding it again doubles the inset (ERROR-009).
            )

            // UI-025 empty conversation state
            if (localMessages.isEmpty()) {
                FlashEmptyState(
                    kind = FlashStateCopy.EmptyKind.ConversationEmpty,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = FlashSpacing.space40),
                )
            }
        }
    }

    // UI-007 / UI-008 Immersive Message Focus Overlay & Context Menu (in-screen, not a Dialog)
    focusedMessage?.let { msg ->
        FlashMessageFocusOverlay(
            message = localMessages.firstOrNull { it.id == msg.id } ?: msg,
            onDismiss = { focusedMessage = null },
            onReactionSelect = { reaction ->
                localMessages = toggleMessageReaction(localMessages, msg.id, reaction)
                Toast.makeText(context, "Reacted $reaction", Toast.LENGTH_SHORT).show()
            },
            onReply = {
                replyingToMessage = msg
            },
            onCopy = {
                copyToClipboard(context, msg.text)
            },
            onForward = {
                Toast.makeText(context, "Forwarding message", Toast.LENGTH_SHORT).show()
            },
            onSelectMultiple = {
                selectedMessageIds = setOf(msg.id)
            },
            onDelete = {
                Toast.makeText(context, "Message deleted", Toast.LENGTH_SHORT).show()
            },
        )
    }

    // UI-012 Modal Attachment Sheet & Palette
    if (showAttachmentSheet) {
        FlashAttachmentSheet(
            onDismiss = { showAttachmentSheet = false },
            onSelectAction = { action ->
                Toast.makeText(context, "Attach from ${action.label}", Toast.LENGTH_SHORT).show()
                onAttachmentClick()
            },
        )
    }

    // UI-029 Group members sheet
    if (showGroupMembers) {
        FlashGroupMembersSheet(
            members = groupMembers,
            onDismiss = { showGroupMembers = false },
        )
    }

    // UI-018 Full-screen media viewer overlay.
    // Items intentionally persist after dismissal so the exit animation fades out real content;
    // they are replaced on next open and only composed while the overlay is visible/leaving.
    AnimatedVisibility(
        visible = mediaViewerVisible,
        enter = motion.mediaOpenEnter(),
        exit = motion.mediaOpenExit(),
    ) {
        if (mediaViewerItems.isNotEmpty()) {
            FlashMediaViewer(
                items = mediaViewerItems,
                initialIndex = mediaViewerStartIndex,
                onDismiss = {
                    mediaViewerVisible = false
                },
                onSave = { index ->
                    Toast.makeText(context, "Saving image ${index + 1}", Toast.LENGTH_SHORT).show()
                },
                onShare = { index ->
                    Toast.makeText(context, "Sharing image ${index + 1}", Toast.LENGTH_SHORT).show()
                },
                onForward = { index ->
                    Toast.makeText(context, "Forwarding image ${index + 1}", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

/**
 * UI-009 Pure reaction toggling logic for message list.
 */
fun toggleMessageReaction(
    messages: List<FlashMessageUi>,
    messageId: String,
    emoji: String,
): List<FlashMessageUi> {
    return messages.map { msg ->
        if (msg.id != messageId) return@map msg

        val existingIndex = msg.reactions.indexOfFirst { it.emoji == emoji }
        val updatedReactions = if (existingIndex >= 0) {
            val existing = msg.reactions[existingIndex]
            if (existing.isSelfReacted) {
                if (existing.count <= 1) {
                    msg.reactions.filterIndexed { i, _ -> i != existingIndex }
                } else {
                    msg.reactions.mapIndexed { i, r ->
                        if (i == existingIndex) r.copy(count = r.count - 1, isSelfReacted = false) else r
                    }
                }
            } else {
                msg.reactions.mapIndexed { i, r ->
                    if (i == existingIndex) r.copy(count = r.count + 1, isSelfReacted = true) else r
                }
            }
        } else {
            msg.reactions + FlashReaction(emoji = emoji, count = 1, isSelfReacted = true)
        }
        msg.copy(reactions = updatedReactions)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Flash Message", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FlashConversationScreenPreview() {
    FlashTheme {
        FlashConversationScreen(
            state = sampleFlashConversationState(),
            onBack = {},
            onOpenPeerDetails = {},
            onSendText = {},
            onAttachmentClick = {},
        )
    }
}
