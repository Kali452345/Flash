package com.transfer.flash.core.messaging

import com.transfer.flash.core.messaging.protocol.MessageWireFrame
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealFlashChatRepositoryTest {

    private val executor = Executors.newFixedThreadPool(4)
    private val testDispatcher = executor.asCoroutineDispatcher()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    // In-memory fake DAOs for deterministic JVM testing
    private class FakeMessageDao : MessageDao {
        val messages = ConcurrentHashMap<String, MessageEntity>()
        val flow = MutableStateFlow<List<MessageEntity>>(emptyList())

        override suspend fun insert(message: MessageEntity): Long {
            if (messages.containsKey(message.localId)) return -1L
            messages[message.localId] = message
            flow.value = messages.values.toList().sortedByDescending { it.sentAt }
            return 1L
        }

        override fun observeConversation(conversationId: String): Flow<List<MessageEntity>> = flow

        override suspend fun historyBefore(
            conversationId: String,
            cursorSentAt: Long,
            cursorLocalId: String,
            limit: Int,
        ): List<MessageEntity> = messages.values.filter { it.conversationId == conversationId }.take(limit)

        override suspend fun updateStatus(localId: String, status: String) {
            messages[localId]?.let {
                val updated = it.copy(status = status)
                messages[localId] = updated
                flow.value = messages.values.toList().sortedByDescending { m -> m.sentAt }
            }
        }

        override suspend fun markEdited(localId: String, editedAt: Long) {
            messages[localId]?.let { messages[localId] = it.copy(editedAt = editedAt) }
        }

        override suspend fun markDeleted(localId: String, deletedAt: Long) {
            messages[localId]?.let { messages[localId] = it.copy(deletedAt = deletedAt) }
        }
    }

    private class FakeConversationDao : ConversationDao {
        val conversations = ConcurrentHashMap<String, ConversationEntity>()
        val flow = MutableStateFlow<List<ConversationEntity>>(emptyList())

        override suspend fun upsert(conversation: ConversationEntity) {
            conversations[conversation.id] = conversation
            flow.value = conversations.values.toList()
        }

        override fun observeAll(): Flow<List<ConversationEntity>> = flow

        override suspend fun setArchived(id: String, archived: Boolean) {
            conversations[id]?.let { conversations[id] = it.copy(archived = archived); flow.value = conversations.values.toList() }
        }

        override suspend fun setPinned(id: String, pinned: Boolean) {
            conversations[id]?.let { conversations[id] = it.copy(pinned = pinned); flow.value = conversations.values.toList() }
        }

        override suspend fun setMuted(id: String, muted: Boolean) {
            conversations[id]?.let { conversations[id] = it.copy(muted = muted); flow.value = conversations.values.toList() }
        }

        override suspend fun updateLastReadCursor(id: String, cursor: String) {
            conversations[id]?.let { conversations[id] = it.copy(lastReadCursor = cursor) }
        }
    }

    private class FakeOutboxDao : OutboxDao {
        val queue = ConcurrentHashMap<String, OutboxEntity>()
        val countFlow = MutableStateFlow(0)

        override suspend fun enqueue(item: OutboxEntity): Long {
            queue[item.localId] = item
            countFlow.value = queue.size
            return 1L
        }

        override suspend fun dueForDelivery(now: Long, limit: Int): List<OutboxEntity> =
            queue.values.take(limit)

        override suspend fun incrementAttempts(localId: String) {
            queue[localId]?.let { queue[localId] = it.copy(attempts = it.attempts + 1) }
        }

        override suspend fun delete(localId: String) {
            queue.remove(localId)
            countFlow.value = queue.size
        }

        override fun observeCount(): Flow<Int> = countFlow
    }

    private class FakeReceiptDao : ReceiptDao {
        val receipts = mutableListOf<ReceiptEntity>()
        val flow = MutableStateFlow<List<ReceiptEntity>>(emptyList())

        override suspend fun insert(receipt: ReceiptEntity): Long {
            receipts.add(receipt)
            flow.value = receipts.toList()
            return 1L
        }

        override fun observeForMessage(messageId: String): Flow<List<ReceiptEntity>> = flow

        override suspend fun countForMessage(messageId: String): Int =
            receipts.count { it.messageId == messageId }
    }

    private class FakeDraftDao : DraftDao {
        val drafts = ConcurrentHashMap<String, DraftEntity>()
        val flow = MutableStateFlow<DraftEntity?>(null)

        override suspend fun upsert(draft: DraftEntity) {
            drafts[draft.conversationId] = draft
            flow.value = draft
        }

        override fun observeDraft(conversationId: String): Flow<DraftEntity?> = flow

        override suspend fun clear(conversationId: String) {
            drafts.remove(conversationId)
            flow.value = null
        }
    }

    private class FakeRecentSearchDao : RecentSearchDao {
        val recents = mutableListOf<RecentSearchEntity>()
        val flow = MutableStateFlow<List<RecentSearchEntity>>(emptyList())

        override suspend fun upsert(search: RecentSearchEntity) {
            recents.removeAll { it.query == search.query }
            recents.add(0, search)
            flow.value = recents.toList()
        }

        override fun observeRecent(limit: Int): Flow<List<RecentSearchEntity>> = flow

        override suspend fun remove(query: String) {
            recents.removeAll { it.query == query }
            flow.value = recents.toList()
        }

        override suspend fun clearAll() {
            recents.clear()
            flow.value = emptyList()
        }
    }

    @Test
    fun `sendText writes message to Room and enqueues in outbox`() = runBlocking {
        val messageDao = FakeMessageDao()
        val conversationDao = FakeConversationDao()
        val outboxDao = FakeOutboxDao()
        val receiptDao = FakeReceiptDao()
        val draftDao = FakeDraftDao()
        val recentSearchDao = FakeRecentSearchDao()

        val sentFrames = mutableListOf<MessageWireFrame>()
        val transportSink = MessageTransportSink { _, frame ->
            sentFrames.add(frame)
            true
        }

        val repository = RealFlashChatRepository(
            localDeviceId = "my-device-id",
            localDisplayName = "Kali",
            messageDao = messageDao,
            conversationDao = conversationDao,
            outboxDao = outboxDao,
            receiptDao = receiptDao,
            draftDao = draftDao,
            recentSearchDao = recentSearchDao,
            transportSink = transportSink,
            ioDispatcher = testDispatcher,
        )

        repository.openConversation("conv-alex")
        repository.sendText("Hello over LAN!")

        // Allow IO execution
        kotlinx.coroutines.delay(100)

        // Verify message was stored in DB
        assertEquals(1, messageDao.messages.size)
        val stored = messageDao.messages.values.first()
        assertEquals("Hello over LAN!", stored.text)
        assertEquals("my-device-id", stored.senderId)

        // Verify wire frame was dispatched
        assertEquals(1, sentFrames.size)
        val frame = sentFrames.first() as MessageWireFrame.TextMessage
        assertEquals("Hello over LAN!", frame.text)

        // Verify outbox was cleared after successful dispatch
        assertEquals(0, outboxDao.queue.size)
        assertEquals("SENT", stored.status)
    }

    @Test
    fun `onInboundWireFrame TextMessage stores message and replies with DeliveryReceipt`() = runBlocking {
        val messageDao = FakeMessageDao()
        val conversationDao = FakeConversationDao()
        val outboxDao = FakeOutboxDao()
        val receiptDao = FakeReceiptDao()
        val draftDao = FakeDraftDao()
        val recentSearchDao = FakeRecentSearchDao()

        val replyFrames = mutableListOf<MessageWireFrame>()
        val transportSink = MessageTransportSink { _, frame ->
            replyFrames.add(frame)
            true
        }

        val repository = RealFlashChatRepository(
            localDeviceId = "my-device-id",
            localDisplayName = "Kali",
            messageDao = messageDao,
            conversationDao = conversationDao,
            outboxDao = outboxDao,
            receiptDao = receiptDao,
            draftDao = draftDao,
            recentSearchDao = recentSearchDao,
            transportSink = transportSink,
            ioDispatcher = testDispatcher,
        )

        val inbound = MessageWireFrame.TextMessage(
            localId = "inbound-123",
            conversationId = "conv-peer",
            senderId = "peer-device-id",
            senderName = "Alex Chen",
            text = "Received fine!",
            sentAt = System.currentTimeMillis(),
        )

        repository.onInboundWireFrame(inbound)

        // Verify message stored
        assertEquals(1, messageDao.messages.size)
        val stored = messageDao.messages["inbound-123"]
        assertNotNull(stored)
        assertEquals("Received fine!", stored!!.text)

        // Verify delivery receipt reply was sent
        assertEquals(1, replyFrames.size)
        val receipt = replyFrames.first() as MessageWireFrame.DeliveryReceipt
        assertEquals("inbound-123", receipt.messageId)
        assertEquals("my-device-id", receipt.memberId)
    }
}
