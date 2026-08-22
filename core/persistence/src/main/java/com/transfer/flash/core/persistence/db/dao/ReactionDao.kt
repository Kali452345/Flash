package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.ReactionEntity
import kotlinx.coroutines.flow.Flow

/** Aggregated reaction rows are last-write-wins per `(messageId, emoji)`. */
@Dao
interface ReactionDao {

    @Upsert
    suspend fun upsert(reaction: ReactionEntity)

    @Query("SELECT * FROM reactions WHERE messageId = :messageId ORDER BY emoji")
    fun observeForMessage(messageId: String): Flow<List<ReactionEntity>>

    @Query("DELETE FROM reactions WHERE messageId = :messageId AND emoji = :emoji")
    suspend fun remove(messageId: String, emoji: String)
}
