package com.example.text

/**
 * Découpe une phrase en « groupes de souffle » pour les voix intégrées, comme le ferait
 * un lecteur humain :
 *  - aux virgules, points-virgules, deux-points et tirets
 *  - dans les longs passages sans ponctuation, juste avant un mot de liaison
 *    (« et », « qui », « pour », « dans »… / « and », « which », « with »…)
 *
 * Chaque morceau est synthétisé séparément puis enchaîné avec une pause mesurée
 * (voir [Chunk.pause]) : le débit reste régulier au lieu de s'emballer sur les phrases longues.
 * Le premier morceau étant court, la voix démarre aussi plus vite.
 */
object SpeechChunker {

    enum class Pause { BREATH, COMMA, END }

    /**
     * @param text texte envoyé à la voix (une virgule est ajoutée aux groupes de souffle
     *        pour garder une intonation « la phrase continue »)
     * @param pause pause à marquer après ce morceau
     */
    data class Chunk(val text: String, val pause: Pause)

    /** Au-delà, un passage sans ponctuation est découpé en groupes de souffle. */
    const val BREATH_MAX_LENGTH = 90
    private const val MIN_PART_LENGTH = 25
    /** Longueur maximale absolue d'un morceau (texte sans aucun mot de liaison). */
    private const val HARD_MAX_LENGTH = 160

    private val PUNCTUATION_BREAK = Regex("""(?<=[,;:])\s+|\s+(?=[–—]\s)""")

    /** Vraies articulations de la phrase : conjonctions et pronoms relatifs (coupe privilégiée). */
    private val STRONG_LINKS = setOf(
        "et", "ou", "mais", "car", "donc", "puis", "ni", "qui", "que", "dont", "où", "lorsque", "quand",
        "parce", "puisque", "afin", "tandis", "alors", "ainsi", "comme", "si",
        "and", "or", "but", "because", "which", "who", "whom", "whose", "where", "when", "while",
        "although", "though", "unless", "whereas", "so"
    )

    /** Prépositions : coupe acceptable seulement à défaut de mieux. */
    private val WEAK_LINKS = setOf(
        "pour", "avec", "sans", "dans", "depuis", "pendant", "avant", "après", "selon", "malgré",
        "sur", "sous", "vers", "chez", "entre", "contre", "parmi",
        "with", "without", "for", "from", "into", "during", "after", "before", "since", "until", "that", "to"
    )

    /** Pénalité (en caractères d'écart) d'une coupe devant une simple préposition. */
    private const val WEAK_LINK_PENALTY = 30

    /** En dessous de cette longueur, on ne coupe jamais devant une simple préposition. */
    private const val WEAK_SPLIT_MIN_LENGTH = 125

    fun split(text: String): List<Chunk> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()

        val parts = clean.split(PUNCTUATION_BREAK).map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<Chunk>()
        parts.forEachIndexed { i, part ->
            val pieces = breathGroups(part)
            pieces.forEachIndexed { j, piece ->
                val isLastOfPart = j == pieces.lastIndex
                val isLast = isLastOfPart && i == parts.lastIndex
                chunks += when {
                    isLast -> Chunk(piece, Pause.END)
                    isLastOfPart -> Chunk(piece, Pause.COMMA)
                    else -> Chunk(withContinuation(piece), Pause.BREATH)
                }
            }
        }
        return mergeTinyChunks(chunks)
    }

    /** Coupe un passage sans ponctuation en morceaux d'environ [BREATH_MAX_LENGTH] caractères. */
    internal fun breathGroups(part: String): List<String> {
        if (part.length <= BREATH_MAX_LENGTH) return listOf(part)
        val words = part.split(' ').filter { it.isNotEmpty() }
        val pieceCount = (part.length + BREATH_MAX_LENGTH - 1) / BREATH_MAX_LENGTH
        val target = part.length / pieceCount

        // Positions de coupe possibles : avant un mot de liaison, en laissant assez de texte des deux côtés
        var bestIndex = -1
        var bestIsWeak = false
        var bestScore = Int.MAX_VALUE
        var position = 0
        for (i in words.indices) {
            if (i > 0) {
                val left = position - 1 // longueur à gauche (sans l'espace)
                val right = part.length - position
                val word = words[i].lowercase().trimEnd(',', '.', ';', ':')
                val strong = word in STRONG_LINKS || word.startsWith("qu'") || word.startsWith("lorsqu'")
                val weak = !strong && word in WEAK_LINKS
                if ((strong || weak) && left >= MIN_PART_LENGTH && right >= MIN_PART_LENGTH) {
                    val score = kotlin.math.abs(left - target) + if (weak) WEAK_LINK_PENALTY else 0
                    if (score < bestScore) {
                        bestScore = score
                        bestIndex = i
                        bestIsWeak = weak
                    }
                }
            }
            position += words[i].length + 1
        }

        if (bestIndex >= 0 && bestIsWeak && part.length <= WEAK_SPLIT_MIN_LENGTH) return listOf(part)
        if (bestIndex < 0) {
            // Aucun mot de liaison : on ne coupe qu'en cas de passage vraiment très long
            return if (part.length <= HARD_MAX_LENGTH) listOf(part) else splitAtMiddleSpace(part)
        }
        val left = words.subList(0, bestIndex).joinToString(" ")
        val right = words.subList(bestIndex, words.size).joinToString(" ")
        return breathGroups(left) + breathGroups(right)
    }

    private fun splitAtMiddleSpace(part: String): List<String> {
        val middle = part.length / 2
        val cut = (part.lastIndexOf(' ', middle).takeIf { it > 0 } ?: part.indexOf(' ', middle)).takeIf { it > 0 }
            ?: return listOf(part)
        return breathGroups(part.substring(0, cut).trim()) + breathGroups(part.substring(cut).trim())
    }

    /**
     * Facteur de vitesse d'un morceau : la voix française a tendance à accélérer sur les
     * passages longs ; on les ralentit très légèrement (jusqu'à −6 %) pour un débit régulier.
     */
    fun paceFactor(text: String): Float {
        val letters = text.count { it.isLetter() }
        return when {
            letters <= PACE_SHORT_LETTERS -> 1f
            letters >= PACE_LONG_LETTERS -> 1f - MAX_PACE_REDUCTION
            else -> 1f - MAX_PACE_REDUCTION * (letters - PACE_SHORT_LETTERS) / (PACE_LONG_LETTERS - PACE_SHORT_LETTERS)
        }
    }

    private const val PACE_SHORT_LETTERS = 50
    private const val PACE_LONG_LETTERS = 100
    private const val MAX_PACE_REDUCTION = 0.06f

    /** Une virgule finale garde une intonation suspensive (la voix ne « termine » pas la phrase). */
    private fun withContinuation(piece: String): String =
        if (piece.last() in ",;:.!?…") piece else "$piece,"

    /** Évite les morceaux d'un ou deux mots (« Bonjour, ») qui casseraient le rythme. */
    private fun mergeTinyChunks(chunks: List<Chunk>): List<Chunk> {
        if (chunks.size < 2) return chunks
        val out = mutableListOf<Chunk>()
        for (chunk in chunks) {
            val previous = out.lastOrNull()
            if (previous != null && previous.pause != Pause.END &&
                (previous.text.length < 12 || chunk.text.length < 12) &&
                previous.text.length + chunk.text.length < BREATH_MAX_LENGTH
            ) {
                val joinedText = if (previous.pause == Pause.BREATH) previous.text.trimEnd(',') else previous.text
                out[out.lastIndex] = Chunk("$joinedText ${chunk.text}", chunk.pause)
            } else {
                out += chunk
            }
        }
        return out
    }
}
