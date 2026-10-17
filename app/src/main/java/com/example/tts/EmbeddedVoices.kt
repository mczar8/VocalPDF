package com.example.tts

import java.util.Locale

/**
 * Voix neuronales Piper livrées DANS l'application (assets/voices/…), lues par sherpa-onnx.
 * Aucune installation ni connexion nécessaire.
 *
 *  - Français : « siwis » (medium) — jeu de données CC BY 4.0 (Université d'Édimbourg)
 *  - Anglais  : « ljspeech » (medium) — domaine public
 */
data class EmbeddedVoice(
    val id: String,
    val locale: Locale,
    val language: String,
    val assetDir: String,
    val modelFile: String,
    val gender: VoiceGender,
    /** Calibrage du débit : 1.0 de l'utilisateur = débit de lecture naturel pour cette voix. */
    val baseSpeed: Float = 1f,
    /** Ralentir très légèrement les passages longs (voix qui accélère sur les phrases longues). */
    val compensateLongPassages: Boolean = false
) {
    val modelAsset: String get() = "$assetDir/$modelFile"
    val tokensAsset: String get() = "$assetDir/tokens.txt"
}

object EmbeddedVoices {
    /** Préfixe des noms de voix intégrées (distingue des voix Android). */
    const val PREFIX = "vocalpdf:"

    /** Qualité annoncée : supérieure aux voix système, donc choisie par défaut pour FR/EN. */
    const val QUALITY = 500

    /** Dossier des données de prononciation (espeak-ng, limité au français et à l'anglais). */
    const val ESPEAK_ASSET_DIR = "espeak-ng-data"

    val all = listOf(
        EmbeddedVoice(
            id = "${PREFIX}fr-siwis",
            locale = Locale.FRANCE,
            language = "fr",
            assetDir = "voices/fr_FR-siwis-medium",
            modelFile = "fr_FR-siwis-medium.onnx",
            gender = VoiceGender.FEMALE,
            // Mesuré : ~17 lettres/s à vitesse 1, nettement plus rapide que la voix anglaise
            baseSpeed = 0.95f,
            compensateLongPassages = true
        ),
        EmbeddedVoice(
            id = "${PREFIX}en-ljspeech",
            locale = Locale.US,
            language = "en",
            assetDir = "voices/en_US-ljspeech-medium",
            modelFile = "en_US-ljspeech-medium.onnx",
            gender = VoiceGender.FEMALE
        )
    )

    fun byId(id: String?): EmbeddedVoice? = all.firstOrNull { it.id == id }

    fun isEmbedded(name: String?): Boolean = name?.startsWith(PREFIX) == true
}
