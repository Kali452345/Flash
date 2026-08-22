package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.transfer.flash.core.persistence.db.entity.OutboxEntity
import kotlinx.coroutines.flow.Flow

/**
 * Outbox drain semantics (invariants encoded in C1.8 and relied on by C6):
 * 1. claim = read via [dueForDelivery] + atomic [incrementAttempts] per claimed row;
 * 2. delivery commit = [delete];
 * 3. re-claiming after delete yields nothing — no double delivery.
 */
@Dao
interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(item: OutboxEntity): Long

    @Query(
        "SELECT * FROM outbox WHERE nextAttemptAt <= :now " +
            "ORDER BY nextAttemptAt ASC LIMIT :limit",
    )
    suspend fun dueForDelivery(now: Long, limit: Int): List<OutboxEntity>

    /** Single-statement atomic increment; safe under concurrent claimers. */
    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE localId = :localId")
    suspend fun incrementAttempts(localId: String)

    @Query("DELETE FROM outbox WHERE localId = :localId")
    suspend fun delete(localId: String)

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>
}
