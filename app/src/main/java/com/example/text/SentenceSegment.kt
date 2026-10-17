package com.example.text

/** Nature d'un segment lu : sert à l'affichage (titre en gras, puce) et aux pauses de lecture. */
enum class SegmentKind { SENTENCE, HEADING, LIST_ITEM }

/**
 * Une unité de lecture (une phrase, un titre ou un élément de liste).
 *
 * @param displayText texte affiché à l'écran (nettoyé mais fidèle au PDF)
 * @param speechText texte envoyé à la synthèse vocale (optimisé pour l'écoute)
 * @param pauseAfterMs silence ajouté après le segment (fin de paragraphe, titre…)
 */
data class SentenceSegment(
    val index: Int,
    val displayText: String,
    val speechText: String,
    val kind: SegmentKind = SegmentKind.SENTENCE,
    val pauseAfterMs: Int = 0
)
