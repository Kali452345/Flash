package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.transfer.flash.core.messaging.model.FlashChatListUiState
import com.transfer.flash.core.messaging.util.sampleFlashChatListState
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashTheme

@Composable
fun FlashChatListScreen(
    state: FlashChatListUiState,
    onConversationClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLanClick: (() -> Unit)? = null,
    onConversationLongClick: (String) -> Unit = {},
    onArchiveConversation: (String) -> Unit = {},
    onToggleSelection: (String) -> Unit = {},
    // UI-025 / UI-026 / UI-027 system states
    isLoading: Boolean = false,
    errorMessage: String? = null,
    isErrorEnvironmental: Boolean = false,
    onRetryLoad: () -> Unit = {},
    onFindDevicesClick: () -> Unit = {},
    // UI-024 global / chat-list search
    isSearching: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChanged: (String) -> Unit = {},
    onCloseSearch: () -> Unit = {},
    recentSearches: List<String> = emptyList(),
    onRecentSearchClick: (String) -> Unit = {},
    onClearRecentSearches: () -> Unit = {},
) {
    val colors = FlashTheme.colors
    val items = state.items
    val displayItems = remember(items, searchQuery) {
        FlashChatListSearchMath.filterChats(items, searchQuery)
    }
    val searchActive = FlashChatListSearchMath.isSearchActive(searchQuery)
    val showRecents = isSearching && !searchActive && recentSearches.isNotEmpty()
    val showEmptyState = !isLoading && errorMessage == null && items.isEmpty() && !isSearching

    Scaffold(
        modifier = modifier,
        topBar = {
            if (isSearching) {
                FlashChatListSearchBar(
                    query = searchQuery,
                    onQueryChanged = onSearchQueryChanged,
                    onClose = onCloseSearch,
                    resultCount = if (searchActive) displayItems.size else null,
                )
            } else {
                FlashChatListTopBar(
                    onSearchClick = onSearchClick,
                    onLanClick = onLanClick,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(colors.backgroundApp),
        ) {
            when {
                // UI-027 container-level failure — replaces the data region entirely.
                errorMessage != null -> {
                    FlashErrorState(
                        title = if (isErrorEnvironmental) "Can't reach any devices" else "Couldn't load conversations",
                        message = errorMessage,
                        severity = if (isErrorEnvironmental) FlashErrorSeverity.Environmental else FlashErrorSeverity.Failure,
                        onRetry = onRetryLoad,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                // UI-026 layout-matched skeletons while data resolves.
                isLoading -> {
                    FlashSkeletonChatList()
                }
                // UI-025 first-run empty state with the P2P next action.
                showEmptyState -> {
                    FlashEmptyState(
                        kind = FlashStateCopy.EmptyKind.ChatListFirstRun,
                        onAction = onFindDevicesClick,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (showRecents) {
                            item(key = "flash-recent-searches") {
                                FlashRecentSearchChips(
                                    recents = recentSearches,
                                    onRecentClick = onRecentSearchClick,
                                    onClearAll = onClearRecentSearches,
                                )
                            }
                        }
                        itemsIndexed(
                            items = displayItems,
                            key = { _, item -> item.id },
                        ) { index, item ->
                            val isLastRow =
                                !showRecents && index == displayItems.lastIndex
                            FlashChatListRow(
                                item = item,
                                onClick = {
                                    if (state.selectionMode) {
                                        onToggleSelection(item.id)
                                    } else {
                                        onConversationClick(item.id)
                                    }
                                },
                                onLongClick = {
                                    if (state.selectionMode) {
                                        onToggleSelection(item.id)
                                    } else {
                                        onConversationLongClick(item.id)
                                    }
                                },
                                onArchive = onArchiveConversation,
                                isSelected = item.id in state.selectedIds,
                                selectionMode = state.selectionMode,
                                showDivider = showRecents || !isLastRow,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Chat list — light", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FlashChatListScreenLightPreview() {
    FlashTheme {
        FlashChatListScreen(
            state = sampleFlashChatListState(),
            onConversationClick = {},
            onSearchClick = {},
        )
    }
}

@Preview(name = "Chat list — dark", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FlashChatListScreenDarkPreview() {
    FlashTheme(darkTheme = true) {
        FlashChatListScreen(
            state = sampleFlashChatListState(),
            onConversationClick = {},
            onSearchClick = {},
        )
    }
}

@Preview(name = "Chat list — selection", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FlashChatListScreenSelectionPreview() {
    FlashTheme {
        FlashChatListScreen(
            state = sampleFlashChatListState().copy(
                selectionMode = true,
                selectedIds = setOf("conv-false-school", "conv-design"),
            ),
            onConversationClick = {},
            onSearchClick = {},
        )
    }
}

@Preview(name = "Chat list — skeleton loading", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FlashChatListScreenLoadingPreview() {
    FlashTheme {
        FlashChatListScreen(
            state = FlashChatListUiState(),
            onConversationClick = {},
            onSearchClick = {},
            isLoading = true,
        )
    }
}

@Preview(name = "Chat list — empty first run", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FlashChatListScreenEmptyPreview() {
    FlashTheme {
        FlashChatListScreen(
            state = FlashChatListUiState(),
            onConversationClick = {},
            onSearchClick = {},
        )
    }
}

@Preview(name = "Chat list — error", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FlashChatListScreenErrorPreview() {
    FlashTheme {
        FlashChatListScreen(
            state = FlashChatListUiState(),
            onConversationClick = {},
            onSearchClick = {},
            errorMessage = "We couldn't load your conversations. Check the connection and try again.",
        )
    }
}
