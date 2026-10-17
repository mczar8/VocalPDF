package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.ReadingHistoryItem
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingHistoryDao {
    @Query("SELECT * FROM reading_history ORDER BY lastReadTimestamp DESC")
    fun getAllHistory(): Flow<List<ReadingHistoryItem>>

    @Query("SELECT * FROM reading_history WHERE title LIKE '%' || :query || '%' ORDER BY lastReadTimestamp DESC")
    fun searchHistory(query: String): Flow<List<ReadingHistoryItem>>

    @Query("SELECT * FROM reading_history WHERE fileUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): ReadingHistoryItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ReadingHistoryItem): Long

    @Update
    suspend fun update(item: ReadingHistoryItem)

    @Query("DELETE FROM reading_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM reading_history")
    suspend fun deleteAll()
}
