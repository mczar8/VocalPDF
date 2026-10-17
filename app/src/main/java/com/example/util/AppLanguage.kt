package com.example.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Langue de l'interface, choisie dans l'application (indépendante de la langue du téléphone). */
object AppLanguage {

    /** Langues traduites. */
    val SUPPORTED = listOf("fr", "en", "es", "de", "it", "pt")

    /** Nom de la langue dans sa propre langue : « Français », « Deutsch »… */
    fun nativeName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }

    /** Langue choisie dans l'app, ou null si l'app suit la langue du système. */
    fun current(): String? {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) null else locales[0]?.language
    }

    fun set(tag: String?) {
        val list = if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(list)
    }

    /**
     * Contexte traduit pour les composants hors Activity (notification…).
     * Sur Android 13+ le système applique déjà la langue de l'app partout.
     */
    fun localizedContext(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return context
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
        return context.createConfigurationContext(config)
    }
}
