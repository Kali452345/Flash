package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.transfer.flash.core.persistence.db.entity.TransferChunkEntity

/**
 * Chunk done-set = resume bit-vector. [doneChunks] returns the sorted indexes of completed
 * chunks so a resumed transfer can skip them; [resetStuck] clears the set when the source
 * file identity no longer matches (C1 resume rule).
 */
@Dao
interface TransferChunkDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(chunks: List<TransferChunkEntity>)

    @Query(
        "UPDATE transfer_chunks SET done = 1 " +
            "WHERE transferId = :transferId AND chunkIndex = :chunkIndex",
    )
    suspend fun markChunkDone(transferId: String, chunkIndex: Int)

    @Query(
        "SELECT chunkIndex FROM transfer_chunks " +
            "WHERE transferId = :transferId AND done = 1 ORDER BY chunkIndex ASC",
    )
    suspend fun doneChunks(transferId: String): List<Int>

    @Query("UPDATE transfer_chunks SET done = 0 WHERE transferId = :transferId")
    suspend fun resetStuck(transferId: String)
}
