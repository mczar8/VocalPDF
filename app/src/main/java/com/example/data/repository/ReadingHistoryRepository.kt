package com.example.data.repository

import com.example.data.dao.ReadingHistoryDao
import com.example.data.model.ReadingHistoryItem
import kotlinx.coroutines.flow.Flow

class ReadingHistoryRepository(private val dao: ReadingHistoryDao) {
    fun searchHistory(query: String): Flow<List<ReadingHistoryItem>> {
        return if (query.isBlank()) {
            dao.getAllHistory()
        } else {
            dao.searchHistory(query.trim())
        }
    }

    suspend fun saveOrUpdateProgress(
        title: String,
        fileUri: String,
        totalPages: Int,
        lastReadPage: Int,
        lastReadSentenceIndex: Int
    ) {
        val existing = dao.getByUri(fileUri)
        if (existing != null) {
            dao.update(
                existing.copy(
                    title = title,
                    totalPages = totalPages,
                    lastReadPage = lastReadPage,
                    lastReadSentenceIndex = lastReadSentenceIndex,
                    lastReadTimestamp = System.currentTimeMillis()
                )
            )
        } else {
            dao.insert(
                ReadingHistoryItem(
                    title = title,
                    fileUri = fileUri,
                    totalPages = totalPages,
                    lastReadPage = lastReadPage,
                    lastReadSentenceIndex = lastReadSentenceIndex,
                    lastReadTimestamp = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun clearAll() = dao.deleteAll()
}
