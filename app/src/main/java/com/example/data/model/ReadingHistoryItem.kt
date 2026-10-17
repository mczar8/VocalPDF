package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reading_history")
data class ReadingHistoryItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val fileUri: String,
    val totalPages: Int,
    val lastReadPage: Int, // 1-indexed
    val lastReadSentenceIndex: Int = 0,
    val lastReadTimestamp: Long = System.currentTimeMillis()
) {
    val progressPercentage: Int
        get() = if (totalPages > 0) {
            ((lastReadPage.toFloat() / totalPages.toFloat()) * 100).toInt().coerceIn(0, 100)
        } else 0
}
