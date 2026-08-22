package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Message history access. Inserts are idempotent (IGNORE on [MessageEntity.localId]).
 * History is keyset-paginated on the composite cursor `(sentAt DESC, localId DESC)` so pages
 * are stable under concurrent inserts and cost an index seek instead of OFFSET.
 */
@Dao
interface MessageDao {

    /** @return row id of the inserted row, or -1 when a duplicate [localId] was ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY sentAt DESC, localId DESC",
    )
    fun observeConversation(conversationId: String): Flow<List<MessageEntity>>

    /**
     * Keyset page strictly before `(cursorSentAt, cursorLocalId)` in descending
     * `(sentAt, localId)` order. For the first page pass `cursorSentAt = Long.MAX_VALUE` and a
     * sentinel string greater than any real localId (e.g. `"\uFFFF"`).
     */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND " +
            "(sentAt < :cursorSentAt OR (sentAt = :cursorSentAt AND localId < :cursorLocalId)) " +
            "ORDER BY sentAt DESC, localId DESC LIMIT :limit",
    )
    suspend fun historyBefore(
        conversationId: String,
        cursorSentAt: Long,
        cursorLocalId: String,
        limit: Int,
    ): List<MessageEntity>

    @Query("UPDATE messages SET status = :status WHERE localId = :localId")
    suspend fun updateStatus(localId: String, status: String)

    @Query("UPDATE messages SET editedAt = :editedAt WHERE localId = :localId")
    suspend fun markEdited(localId: String, editedAt: Long)

    /** Tombstone only — never deletes the row (history pagination must stay stable). */
    @Query("UPDATE messages SET deletedAt = :deletedAt WHERE localId = :localId")
    suspend fun markDeleted(localId: String, deletedAt: Long)
}
