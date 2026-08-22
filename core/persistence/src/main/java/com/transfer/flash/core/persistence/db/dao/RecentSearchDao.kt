package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.RecentSearchEntity
import kotlinx.coroutines.flow.Flow

/** Re-searching the same term touches [RecentSearchEntity.usedAt] via upsert. */
@Dao
interface RecentSearchDao {

    @Upsert
    suspend fun upsert(search: RecentSearchEntity)

    @Query("SELECT * FROM recent_searches ORDER BY usedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecentSearchEntity>>

    @Query("DELETE FROM recent_searches WHERE query = :query")
    suspend fun remove(query: String)

    @Query("DELETE FROM recent_searches")
    suspend fun clearAll()
}
