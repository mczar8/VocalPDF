package com.example.data

import android.content.Context
import androidx.core.content.edit

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Toutes les préférences persistantes de l'application (un seul fichier).
 * Le nom du fichier est conservé pour garder les réglages des versions précédentes
 * (vitesse, tonalité, voix choisie…).
 */
class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // --- Affichage ---
    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM)
        set(value) = prefs.edit { putString(KEY_THEME, value.name) }

    var fontSizeSp: Float
        get() = prefs.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
        set(value) = prefs.edit { putFloat(KEY_FONT_SIZE, value) }

    // --- Discrétion ---
    var silentModeProtection: Boolean
        get() = prefs.getBoolean(KEY_SILENT_PROTECTION, true)
        set(value) = prefs.edit { putBoolean(KEY_SILENT_PROTECTION, value) }

    // --- Synthèse vocale ---
    var speechRate: Float
        get() = prefs.getFloat(KEY_SPEECH_RATE, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_SPEECH_RATE, value) }

    var pitch: Float
        get() = prefs.getFloat(KEY_PITCH, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_PITCH, value) }

    var autoAdvance: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ADVANCE, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_ADVANCE, value) }

    var loopMode: Boolean
        get() = prefs.getBoolean(KEY_LOOP_MODE, false)
        set(value) = prefs.edit { putBoolean(KEY_LOOP_MODE, value) }

    var selectedVoice: String?
        get() = prefs.getString(KEY_VOICE_NAME, null)
        set(value) = prefs.edit { putString(KEY_VOICE_NAME, value) }

    /** Moteur de synthèse choisi (nom de paquet) ; null = moteur par défaut d'Android. */
    var ttsEngine: String?
        get() = prefs.getString(KEY_ENGINE, null)
        set(value) = prefs.edit { putString(KEY_ENGINE, value) }

    /** Choisir automatiquement une voix dans la langue du document. */
    var autoVoiceByLanguage: Boolean
        get() = prefs.getBoolean(KEY_AUTO_VOICE, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_VOICE, value) }

    fun preferredVoiceFor(language: String): String? = prefs.getString(KEY_VOICE_FOR_LANG + language, null)

    fun setPreferredVoiceFor(language: String, voiceName: String) =
        prefs.edit { putString(KEY_VOICE_FOR_LANG + language, voiceName) }

    companion object {
        private const val FILE_NAME = "vocalpdf_preferences"
        const val DEFAULT_FONT_SIZE = 18f
        const val MIN_FONT_SIZE = 13f
        const val MAX_FONT_SIZE = 30f

        private const val KEY_THEME = "pref_theme_mode"
        private const val KEY_FONT_SIZE = "pref_font_size"
        private const val KEY_SILENT_PROTECTION = "pref_silent_protection"
        private const val KEY_SPEECH_RATE = "pref_speech_rate"
        private const val KEY_PITCH = "pref_pitch"
        private const val KEY_AUTO_ADVANCE = "pref_auto_advance"
        private const val KEY_LOOP_MODE = "pref_loop_mode"
        private const val KEY_VOICE_NAME = "pref_selected_voice"
        private const val KEY_ENGINE = "pref_tts_engine"
        private const val KEY_AUTO_VOICE = "pref_auto_voice_by_language"
        private const val KEY_VOICE_FOR_LANG = "pref_voice_for_"
    }
}
