package com.example

import android.app.Application
import com.example.data.AppPreferences
import com.example.data.db.AppDatabase
import com.example.data.repository.ReadingHistoryRepository
import com.example.security.AppLockManager
import com.example.tts.TtsPlaybackManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class VocalPdfApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var historyRepository: ReadingHistoryRepository
        private set

    lateinit var preferences: AppPreferences
        private set

    lateinit var ttsManager: TtsPlaybackManager
        private set

    lateinit var lockManager: AppLockManager
        private set

    override fun onCreate() {
        super.onCreate()
        // Initialisation de PDFBox (extraction PDF hors ligne)
        PDFBoxResourceLoader.init(applicationContext)

        preferences = AppPreferences(this)
        lockManager = AppLockManager(this)
        database = AppDatabase.getInstance(this)
        historyRepository = ReadingHistoryRepository(database.readingHistoryDao())
        ttsManager = TtsPlaybackManager(this, preferences)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // App en arrière-plan sans lecture : on libère la voix intégrée (rechargée au besoin)
        if (level >= TRIM_MEMORY_BACKGROUND) ttsManager.releaseEmbeddedModel()
    }

    override fun onTerminate() {
        super.onTerminate()
        ttsManager.shutdown()
    }
}
