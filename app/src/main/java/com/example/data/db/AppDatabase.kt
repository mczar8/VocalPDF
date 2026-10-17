package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.ReadingHistoryDao
import com.example.data.model.ReadingHistoryItem

@Database(entities = [ReadingHistoryItem::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun readingHistoryDao(): ReadingHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 -> v2 : suppression des documents d'exemple (colonnes isSample / sampleKey).
         * L'historique des vrais PDF est conservé.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_history_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`fileUri` TEXT NOT NULL, " +
                        "`totalPages` INTEGER NOT NULL, " +
                        "`lastReadPage` INTEGER NOT NULL, " +
                        "`lastReadSentenceIndex` INTEGER NOT NULL, " +
                        "`lastReadTimestamp` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `reading_history_new` " +
                        "(id, title, fileUri, totalPages, lastReadPage, lastReadSentenceIndex, lastReadTimestamp) " +
                        "SELECT id, title, fileUri, totalPages, lastReadPage, lastReadSentenceIndex, lastReadTimestamp " +
                        "FROM `reading_history` WHERE isSample = 0"
                )
                db.execSQL("DROP TABLE `reading_history`")
                db.execSQL("ALTER TABLE `reading_history_new` RENAME TO `reading_history`")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vocalpdf_database.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
