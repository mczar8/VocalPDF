package com.example.text

/**
 * Détection hors ligne, légère et sans dépendance de la langue d'un texte.
 * Langues prises en charge : français, anglais, espagnol, allemand, italien, portugais.
 *
 * Principe : fréquence de mots-outils propres à chaque langue + indices typographiques
 * (ñ, ß, ã…). Suffisant pour choisir automatiquement la bonne voix de lecture.
 */
object LanguageDetector {

    data class Detection(val language: String, val confidence: Float)

    val SUPPORTED = listOf("fr", "en", "es", "de", "it", "pt")

    private val STOPWORDS: Map<String, Set<String>> = mapOf(
        "fr" to setOf(
            "le", "la", "les", "des", "est", "et", "une", "un", "du", "que", "qui", "dans", "pour",
            "pas", "sur", "avec", "ce", "cette", "ces", "il", "elle", "ils", "elles", "nous", "vous",
            "sont", "mais", "ou", "au", "aux", "être", "été", "leur", "leurs", "comme", "plus",
            "tout", "tous", "ses", "son", "sa", "je", "ne", "se", "en", "par", "très", "aussi",
            "fait", "avait", "était", "c'est", "d'un", "d'une", "l'on", "qu'il", "n'est", "où"
        ),
        "en" to setOf(
            "the", "and", "of", "to", "is", "in", "that", "it", "was", "for", "with", "as", "on",
            "be", "at", "by", "this", "have", "from", "are", "not", "but", "or", "which", "you",
            "they", "we", "an", "were", "their", "has", "been", "will", "would", "there", "what",
            "can", "all", "if", "she", "he", "his", "her", "its", "into", "than", "these", "those"
        ),
        "es" to setOf(
            "el", "los", "las", "y", "de", "que", "en", "por", "con", "para", "una", "un", "es",
            "se", "del", "al", "lo", "como", "más", "pero", "sus", "su", "le", "ya", "este", "esta",
            "son", "está", "también", "fue", "muy", "hay", "sin", "sobre", "entre", "cuando",
            "porque", "todo", "ser", "ha", "nos", "les", "eso", "donde", "desde", "puede"
        ),
        "de" to setOf(
            "der", "die", "das", "und", "ist", "nicht", "ein", "eine", "zu", "den", "mit", "sich",
            "des", "auf", "für", "im", "dem", "auch", "es", "an", "werden", "aus", "er", "hat",
            "dass", "sie", "nach", "wird", "bei", "einer", "um", "noch", "wie", "über", "sind",
            "oder", "ich", "wir", "aber", "kann", "nur", "wenn", "durch", "zum", "zur", "vom"
        ),
        "it" to setOf(
            "il", "di", "che", "è", "e", "la", "per", "un", "una", "non", "sono", "del", "della",
            "con", "si", "da", "le", "al", "nel", "nella", "gli", "anche", "come", "più", "ma",
            "alla", "dei", "questo", "questa", "essere", "ha", "ci", "delle", "lo", "degli",
            "sua", "suo", "loro", "molto", "quando", "perché", "tutto", "stato", "dalla", "sul"
        ),
        "pt" to setOf(
            "o", "os", "as", "de", "que", "e", "do", "da", "em", "um", "uma", "para", "com",
            "não", "no", "na", "por", "dos", "das", "se", "mais", "como", "mas", "ao", "ele",
            "ela", "foi", "são", "está", "isso", "também", "muito", "já", "seu", "sua", "pelo",
            "pela", "nos", "nas", "você", "quando", "entre", "sobre", "ser", "tem", "até"
        )
    )

    /** Nombre de langues partageant chaque mot (un mot commun à plusieurs langues pèse moins). */
    private val SHARE_COUNT: Map<String, Int> =
        STOPWORDS.values.flatten().groupingBy { it }.eachCount()

    private val WORD_REGEX = Regex("[\\p{L}']+")

    /**
     * @return la langue détectée, ou null si le texte est trop court ou ambigu.
     */
    fun detect(text: String): Detection? {
        if (text.isBlank()) return null
        val sample = if (text.length > 6000) text.substring(0, 6000) else text
        val lower = sample.lowercase().replace('\u2019', '\'')

        val scores = HashMap<String, Double>()
        var hits = 0
        for (match in WORD_REGEX.findAll(lower)) {
            val word = match.value.trim('\'')
            if (word.isEmpty()) continue
            val share = SHARE_COUNT[word] ?: continue
            hits++
            val weight = 1.0 / share
            for ((lang, words) in STOPWORDS) {
                if (word in words) scores[lang] = (scores[lang] ?: 0.0) + weight
            }
        }

        // Indices typographiques
        fun bonus(lang: String, count: Int, perChar: Double, cap: Double = 6.0) {
            if (count > 0) scores[lang] = (scores[lang] ?: 0.0) + minOf(cap, count * perChar)
        }
        bonus("es", lower.count { it == 'ñ' }, 1.5)
        bonus("es", lower.count { it == '¿' || it == '¡' }, 2.0)
        bonus("de", lower.count { it == 'ß' }, 1.5)
        bonus("de", lower.count { it == 'ä' || it == 'ö' || it == 'ü' }, 0.5)
        bonus("pt", lower.count { it == 'ã' || it == 'õ' }, 1.5)
        bonus("fr", lower.count { it == 'ê' || it == 'â' || it == 'î' || it == 'û' || it == 'œ' || it == 'ë' }, 0.5)
        bonus("it", lower.count { it == 'ò' || it == 'ì' }, 1.0)

        if (hits < 3) return null
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.value ?: 0.0
        if (best.value < 2.0) return null
        if (second > 0 && best.value < second * 1.25) return null

        val total = ranked.sumOf { it.value }
        val confidence = (best.value / total).toFloat().coerceIn(0f, 1f)
        return Detection(best.key, confidence)
    }
}
