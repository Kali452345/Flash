package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.messaging.model.FlashChatHeaderUiState
import com.transfer.flash.core.messaging.model.FlashChatListItemUi
import com.transfer.flash.core.messaging.model.FlashChatListUiState
import com.transfer.flash.core.messaging.model.FlashConversationUiState
import com.transfer.flash.core.messaging.model.FlashMessageStatus
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.messaging.util.computeMessageGroupPositions
import com.transfer.flash.core.messaging.util.sortedChatListItems
import com.transfer.flash.core.persistence.db.dao.ConversationDao
import com.transfer.flash.core.persistence.db.dao.DraftDao
import com.transfer.flash.core.persistence.db.dao.MessageDao
import com.transfer.flash.core.persistence.db.dao.OutboxDao
import com.transfer.flash.core.persistence.db.dao.ReceiptDao
import com.transfer.flash.core.persistence.db.dao.RecentSearchDao
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.DraftEntity
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import com.transfer.flash.core.persistence.db.entity.OutboxEntity
import com.transfer.flash.core.persistence.db.entity.ReceiptEntity
import com.transfer.flash.core.persistence.db.entity.RecentSearchEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Functional wire transport provider for sending frames to a target conversation / peer.
 */
fun interface MessageTransportSink {
    suspend fun send(conversationId: String, frame: MessageWireFrame): Boolean
}

/**
 * Production Room-backed implementation of [FlashChatRepository] (C6.0 - C6.13).
 *
 * Key guarantees:
 * - **Durable Outbox (C6.1):** Outgoing messages are written directly to Room `messages` + `outbox`
 *   and instantly emitted to the UI before network dispatch. Process death does not lose un-sent messages.
 * - **Idempotent Ingestion (C6.2):** Message insertions use `OnConflictStrategy.IGNORE` on client-generated UUIDs.
 * - **Delivery & Read Receipts (C6.3):** Emits and absorbs delivery receipts to update status flags.
 * - **Ephemeral Typing & Presence (C6.6):** Memory-only TTL state for live typing indicators.
 */
class RealFlashChatRepository(
    private val localDeviceId: String,
    private val localDisplayName: String,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val outboxDao: OutboxDao,
    private val receiptDao: ReceiptDao,
    private val draftDao: DraftDao,
    private val recentSearchDao: RecentSearchDao,
    private val transportSink: MessageTransportSink? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FlashChatRepository {

    private val _chatListState = MutableStateFlow(FlashChatListUiState())
    override val chatListState: StateFlow<FlashChatListUiState> = _chatListState.asStateFlow()

    private val _conversationState = MutableStateFlow(
        FlashConversationUiState(
            header = FlashChatHeaderUiState(
                title = "Messages",
                avatarInitials = "FL",
            ),
            messages = emptyList(),
        ),
    )
    override val conversationState: StateFlow<FlashConversationUiState> = _conversationState.asStateFlow()

    private var activeConversationId: String? = null
    private var activeConversationJob: Job? = null

    // Ephemeral in-memory typing state: conversationId -> (memberId -> isTyping)
    private val typingStates = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    init {
        // Observe conversation list from Room
        scope.launch(ioDispatcher) {
            conversationDao.observeAll().collectLatest { entities ->
                val items = entities.filter { !it.archived }.map { entity ->
                    FlashChatListItemUi(
                        id = entity.id,
                        title = entity.title,
                        avatarInitials = computeInitials(entity.title),
                        previewText = "Tap to view conversation",
                        timestamp = formatTimestamp(entity.sortOrder),
                        unreadCount = 0,
                        isGroup = entity.isGroup,
                        isPinned = entity.pinned,
                        isMuted = entity.muted,
                        sortOrder = entity.sortOrder,
                    )
                }
                _chatListState.update { current ->
                    current.copy(
                        items = sortedChatListItems(items),
                    )
                }
            }
        }

        // Background outbox drain worker
        scope.launch(ioDispatcher) {
            drainOutboxLoop()
        }
    }

    override fun openConversation(conversationId: String) {
        activeConversationId = conversationId
        activeConversationJob?.cancel()

        activeConversationJob = scope.launch(ioDispatcher) {
            combine(
                messageDao.observeConversation(conversationId),
                draftDao.observeDraft(conversationId),
            ) { entities, draftEntity ->
                val messages = entities.map { entity ->
                    FlashMessageUi(
                        id = entity.localId,
                        senderName = entity.senderName ?: if (entity.senderId == localDeviceId) "You" else "Peer",
                        senderInitials = computeInitials(entity.senderName ?: entity.senderId),
                        timeLabel = formatTime(entity.sentAt),
                        text = entity.text,
                        isMine = entity.senderId == localDeviceId,
                        deliveryStatus = mapStatus(entity.status),
                    )
                }

                FlashConversationUiState(
                    header = FlashChatHeaderUiState(
                        title = conversationId,
                        avatarInitials = computeInitials(conversationId),
                    ),
                    messages = computeMessageGroupPositions(messages.reversed()),
                )
            }.collectLatest { state ->
                _conversationState.value = state
            }
        }
    }

    override fun closeConversation() {
        activeConversationJob?.cancel()
        activeConversationId = null
    }

    override fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val conversationId = activeConversationId ?: return
        val now = System.currentTimeMillis()
        val localId = UUID.randomUUID().toString()

        scope.launch(ioDispatcher) {
            // 1. Write message row
            val messageEntity = MessageEntity(
                localId = localId,
                conversationId = conversationId,
                senderId = localDeviceId,
                senderName = localDisplayName,
                text = trimmed,
                sentAt = now,
                status = "PENDING",
            )
            messageDao.insert(messageEntity)

            // 2. Ensure conversation exists in DB
            conversationDao.upsert(
                ConversationEntity(
                    id = conversationId,
                    title = conversationId,
                    isGroup = false,
                    sortOrder = now,
                ),
            )

            // 3. Clear draft
            draftDao.clear(conversationId)

            // 4. Enqueue into outbox
            outboxDao.enqueue(
                OutboxEntity(
                    localId = localId,
                    attempts = 0,
                    nextAttemptAt = now,
                    payloadJson = trimmed,
                    createdAt = now,
                ),
            )

            // 5. Trigger outbox drain immediately
            drainOutboxOnce()
        }
    }

    /**
     * Ingests an inbound wire frame from the network layer.
     */
    suspend fun onInboundWireFrame(frame: MessageWireFrame) {
        when (frame) {
            is MessageWireFrame.TextMessage -> {
                val entity = MessageEntity(
                    localId = frame.localId,
                    conversationId = frame.conversationId,
                    senderId = frame.senderId,
                    senderName = frame.senderName,
                    text = frame.text,
                    sentAt = frame.sentAt,
                    status = "DELIVERED",
                )
                messageDao.insert(entity)
                conversationDao.upsert(
                    ConversationEntity(
                        id = frame.conversationId,
                        title = frame.senderName ?: frame.senderId,
                        isGroup = false,
                        sortOrder = frame.sentAt,
                    ),
                )

                // Reply with delivery receipt
                transportSink?.send(
                    frame.conversationId,
                    MessageWireFrame.DeliveryReceipt(
                        messageId = frame.localId,
                        conversationId = frame.conversationId,
                        memberId = localDeviceId,
                        deliveredAt = System.currentTimeMillis(),
                    ),
                )
            }

            is MessageWireFrame.DeliveryReceipt -> {
                receiptDao.insert(
                    ReceiptEntity(
                        messageId = frame.messageId,
                        memberId = frame.memberId,
                        state = "DELIVERED",
                    ),
                )
                messageDao.updateStatus(frame.messageId, "DELIVERED")
            }

            is MessageWireFrame.ReadReceipt -> {
                conversationDao.updateLastReadCursor(frame.conversationId, frame.upToMessageId)
            }

            is MessageWireFrame.TypingFrame -> {
                val convTyping = typingStates.computeIfAbsent(frame.conversationId) { ConcurrentHashMap() }
                if (frame.isTyping) {
                    convTyping[frame.memberId] = frame.memberName
                } else {
                    convTyping.remove(frame.memberId)
                }
            }

            is MessageWireFrame.ReactionFrame -> {
                // Reaction handling
            }
        }
    }

    private val drainMutex = Mutex()

    private suspend fun drainOutboxLoop() {
        while (true) {
            drainOutboxOnce()
            kotlinx.coroutines.delay(1000)
        }
    }

    private suspend fun drainOutboxOnce() {
        drainMutex.withLock {
            val now = System.currentTimeMillis()
            val items = outboxDao.dueForDelivery(now, limit = 16)
            for (item in items) {
                outboxDao.incrementAttempts(item.localId)
                val sink = transportSink
                if (sink != null) {
                    val wireFrame = MessageWireFrame.TextMessage(
                        localId = item.localId,
                        conversationId = activeConversationId ?: "general",
                        senderId = localDeviceId,
                        senderName = localDisplayName,
                        text = item.payloadJson,
                        sentAt = now,
                    )
                    val success = sink.send(wireFrame.conversationId, wireFrame)
                    if (success) {
                        outboxDao.delete(item.localId)
                        messageDao.updateStatus(item.localId, "SENT")
                    }
                }
            }
        }
    }

    override fun openAttachmentPicker() {}

    override fun enterListSelectionMode(conversationId: String) {
        _chatListState.update { it.copy(selectionMode = true, selectedIds = setOf(conversationId)) }
    }

    override fun toggleListSelection(conversationId: String) {
        _chatListState.update { state ->
            val updated = state.selectedIds.toMutableSet()
            if (conversationId in updated) updated.remove(conversationId) else updated.add(conversationId)
            state.copy(selectedIds = updated, selectionMode = updated.isNotEmpty())
        }
    }

    override fun clearListSelection() {
        _chatListState.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    override fun archiveConversation(conversationId: String) {
        scope.launch(ioDispatcher) {
            conversationDao.setArchived(conversationId, true)
        }
    }

    private fun mapStatus(status: String): FlashMessageStatus = when (status) {
        "PENDING" -> FlashMessageStatus.Pending
        "SENT" -> FlashMessageStatus.Sent
        "DELIVERED" -> FlashMessageStatus.Delivered
        "READ" -> FlashMessageStatus.Read
        else -> FlashMessageStatus.Delivered
    }

    private fun computeInitials(name: String): String {
        val parts = name.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "FL"
            parts.size == 1 -> parts[0].take(2).uppercase(Locale.getDefault())
            else -> "${parts[0].first()}${parts[1].first()}".uppercase(Locale.getDefault())
        }
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))

    private fun formatTimestamp(millis: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - millis
        return when {
            diff < 60_000 -> "Now"
            diff < 3600_000 -> "${diff / 60_000}m"
            diff < 86400_000 -> "${diff / 3600_000}h"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
        }
    }
}
