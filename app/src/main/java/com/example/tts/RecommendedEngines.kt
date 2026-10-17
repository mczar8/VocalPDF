package com.example.tts

import androidx.annotation.StringRes
import com.example.R

/**
 * Moteur de synthèse vocale gratuit et utilisable hors ligne, proposé pour les langues
 * qui n'ont pas de voix intégrée. Seuls des liens vers le Play Store sont proposés
 * (règles Google Play).
 */
data class RecommendedEngine(
    val packageName: String,
    val displayName: String,
    @StringRes val descriptionRes: Int,
    val storeUri: String,
    val webUri: String
)

object RecommendedEngines {
    val all = listOf(
        RecommendedEngine(
            packageName = "com.google.android.tts",
            displayName = "Google Speech Services",
            descriptionRes = R.string.engine_google_desc,
            storeUri = "market://details?id=com.google.android.tts",
            webUri = "https://play.google.com/store/apps/details?id=com.google.android.tts"
        )
    )
}
