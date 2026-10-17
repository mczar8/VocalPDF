package com.example.text

/**
 * Transforme le texte brut extrait d'une page PDF en segments lisibles et agréables à écouter.
 *
 * Ce que fait le traitement « intelligent » :
 *  - normalise les caractères (ligatures ﬁ/ﬂ, espaces insécables, césures invisibles…)
 *  - supprime les numéros de page et les en-têtes / pieds de page répétés d'une page à l'autre
 *  - recolle les mots coupés en fin de ligne (« compré-\nhension » -> « compréhension »)
 *  - reconstitue les paragraphes, détecte les titres et les listes à puces
 *  - découpe en phrases sans se tromper sur les abréviations (M., Dr., p. 12, e.g., z.B.…)
 *  - prépare un texte « parlé » : sans URL interminables, sans renvois [12], sans pointillés
 *    de sommaire, avec des pauses naturelles après les titres et les paragraphes
 *
 * Code Kotlin pur (aucune dépendance Android) : testable sur JVM.
 */
object PageTextProcessor {

    data class Result(
        val segments: List<SentenceSegment>,
        val language: String?,
        val cleanText: String
    )

    internal enum class BlockKind { PARAGRAPH, HEADING, LIST_ITEM }

    internal data class Block(val text: String, val kind: BlockKind)

    private const val MAX_SEGMENT_LENGTH = 350
    private const val PAUSE_HEADING_MS = 450
    private const val PAUSE_PARAGRAPH_MS = 250
    private const val PAUSE_LIST_ITEM_MS = 150

    /**
     * @param raw texte brut de la page
     * @param neighborPages texte brut des pages voisines (pour repérer en-têtes et pieds de page)
     * @param languageHint langue à utiliser si la détection n'est pas concluante
     */
    fun process(
        raw: String,
        neighborPages: List<String> = emptyList(),
        languageHint: String? = null
    ): Result {
        val lines = normalize(raw).split('\n')
        val neighbors = neighborPages.map { normalize(it).split('\n') }
        val cleanedLines = removePageNoise(lines, neighbors)
        val blocks = buildBlocks(cleanedLines)
        val cleanText = blocks.joinToString("\n") { it.text }
        val language = LanguageDetector.detect(cleanText)?.language ?: languageHint
        return Result(segment(blocks, language), language, cleanText)
    }

    // ---------------------------------------------------------------------------------------
    // 1. Normalisation des caractères
    // ---------------------------------------------------------------------------------------

    private val LIGATURES = mapOf(
        "ﬀ" to "ff", "ﬁ" to "fi", "ﬂ" to "fl", "ﬃ" to "ffi",
        "ﬄ" to "ffl", "ﬅ" to "st", "ﬆ" to "st"
    )
    private val INVISIBLE_REGEX = Regex("[­​‌‍⁠﻿]")
    private val SPACE_LIKE_REGEX = Regex("[\t     ]")
    private val CONTROL_REGEX = Regex("[\\p{Cc}&&[^\\n]]")
    private val MULTI_SPACE_REGEX = Regex(" {2,}")

    internal fun normalize(raw: String): String {
        var s = raw.replace("\r\n", "\n").replace('\r', '\n')
        for ((lig, rep) in LIGATURES) s = s.replace(lig, rep)
        s = INVISIBLE_REGEX.replace(s, "")
        s = SPACE_LIKE_REGEX.replace(s, " ")
        s = CONTROL_REGEX.replace(s, "")
        s = MULTI_SPACE_REGEX.replace(s, " ")
        return s
    }

    // ---------------------------------------------------------------------------------------
    // 2. Numéros de page, en-têtes et pieds de page
    // ---------------------------------------------------------------------------------------

    private val PAGE_NUMBER_REGEX = Regex(
        """^[\s\-–—|•·]*(?:(?:page|p\.|pg\.?|seite|s\.|página|pág\.?|pagina|pag\.?)\s*)?\d{1,4}(?:\s*(?:/|sur|of|de|von|di|da)\s*\d{1,4})?[\s\-–—|•·]*$""",
        RegexOption.IGNORE_CASE
    )
    private val ROMAN_PAGE_REGEX = Regex("""^\s*[-–—]?\s*(?:[ivxlc]{1,6}|[IVXLC]{1,6})\s*[-–—]?\s*$""")
    private val DIGITS_REGEX = Regex("\\d+")
    private val WHITESPACE_REGEX = Regex("\\s+")

    internal fun isPageNumberLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        return PAGE_NUMBER_REGEX.matches(t) || ROMAN_PAGE_REGEX.matches(t)
    }

    /** Signature d'une ligne : insensible à la casse et aux numéros (« Chapitre 3 — page 12 »). */
    internal fun signature(line: String): String =
        WHITESPACE_REGEX.replace(DIGITS_REGEX.replace(line.lowercase(), "#"), " ").trim()

    private const val EDGE_LINES = 3

    internal fun removePageNoise(lines: List<String>, neighbors: List<List<String>>): List<String> {
        val nonBlank = lines.indices.filter { lines[it].isNotBlank() }
        if (nonBlank.isEmpty()) return emptyList()
        val edgeIndices = (nonBlank.take(EDGE_LINES) + nonBlank.takeLast(EDGE_LINES)).toSet()

        val neighborEdgeSignatures: Set<String> = neighbors.flatMap { page ->
            val content = page.filter { it.isNotBlank() }
            content.take(EDGE_LINES) + content.takeLast(EDGE_LINES)
        }.map(::signature).filter { it.length >= 3 }.toSet()

        return lines.filterIndexed { i, line ->
            if (i !in edgeIndices) return@filterIndexed true
            if (isPageNumberLine(line)) return@filterIndexed false
            val sig = signature(line)
            !(sig.length >= 3 && sig in neighborEdgeSignatures)
        }
    }

    // ---------------------------------------------------------------------------------------
    // 3. Paragraphes, titres, listes
    // ---------------------------------------------------------------------------------------

    private val LIST_ITEM_REGEX = Regex("""^(?:[•●○■□▪▫◦‣∙·*➢➤►▸–—-]|\(?\d{1,3}[.)]|[a-z]\))\s+\S""")
    private val BULLET_SYMBOL_REGEX = Regex("""^[•●○■□▪▫◦‣∙·*➢➤►▸]\s*""")
    private val SENTENCE_END_REGEX = Regex("""[.!?…:][»"”’')\]]*$""")
    private val NUMBERED_HEADING_REGEX = Regex("""^\d{1,2}(?:\.\d{1,2})*[.)]?\s+\p{Lu}""")
    private val HYPHENATED_END_REGEX = Regex("""\p{L}-$""")

    internal fun isListItemStart(line: String): Boolean = LIST_ITEM_REGEX.containsMatchIn(line)

    internal fun endsSentence(text: String): Boolean = SENTENCE_END_REGEX.containsMatchIn(text.trimEnd())

    private fun startsLikeNewBlock(line: String?): Boolean {
        if (line.isNullOrEmpty()) return true
        val c = line.first()
        return c.isUpperCase() || c.isDigit() || isListItemStart(line) || c in "«\"“"
    }

    private fun isHeading(line: String, previousIsBoundary: Boolean, next: String?, medianLength: Int): Boolean {
        if (!previousIsBoundary) return false
        if (line.length > 90) return false
        if (line.count { it == ' ' } >= 12) return false
        if (line.last() in ".,;:!?…") return false
        if (line.none { it.isLetter() }) return false
        val first = line.first()
        if (!first.isUpperCase() && !first.isDigit()) return false
        val isShort = if (medianLength > 0) line.length < medianLength * 0.7 else line.length <= 60
        if (!isShort) return false
        return startsLikeNewBlock(next)
    }

    private fun normalizeListPrefix(line: String): String =
        if (BULLET_SYMBOL_REGEX.containsMatchIn(line)) "• " + BULLET_SYMBOL_REGEX.replace(line, "") else line

    internal fun buildBlocks(rawLines: List<String>): List<Block> {
        // Une ligne sans lettre ni chiffre (« * * * », « _____ ») = séparation
        val lines = rawLines.map { l -> l.trim().let { t -> if (t.any { it.isLetterOrDigit() }) t else "" } }

        val lengths = lines.map { it.length }.filter { it > 20 }.sorted()
        val median = if (lengths.size >= 3) lengths[lengths.size / 2] else 0

        val blocks = mutableListOf<Block>()
        val current = StringBuilder()
        var currentKind = BlockKind.PARAGRAPH
        var lastLineLength = 0
        var pendingBreak = false

        fun flush() {
            val text = current.toString().trim()
            if (text.isNotEmpty()) blocks += Block(text, currentKind)
            current.setLength(0)
            currentKind = BlockKind.PARAGRAPH
            lastLineLength = 0
        }

        for (i in lines.indices) {
            val line = lines[i]
            if (line.isEmpty()) {
                pendingBreak = true
                continue
            }
            val next = lines.drop(i + 1).firstOrNull { it.isNotEmpty() }
            val currentText = current.toString()
            val boundary = currentText.isBlank() || endsSentence(currentText)

            // Ligne vide entre deux morceaux d'une même phrase : on ignore la coupure
            if (pendingBreak) {
                pendingBreak = false
                if (currentText.isNotBlank() && (endsSentence(currentText) || startsLikeNewBlock(line))) flush()
            }

            // « 2. État de l'art » : titre numéroté, sauf s'il fait partie d'une liste numérotée
            val looksNumberedHeading = NUMBERED_HEADING_REGEX.containsMatchIn(line) &&
                !(next != null && isListItemStart(next)) &&
                currentKind != BlockKind.LIST_ITEM &&
                blocks.lastOrNull()?.kind != BlockKind.LIST_ITEM

            when {
                looksNumberedHeading && isHeading(line, boundary, next, median) -> {
                    flush()
                    blocks += Block(line, BlockKind.HEADING)
                }

                isListItemStart(line) -> {
                    flush()
                    currentKind = BlockKind.LIST_ITEM
                    current.append(normalizeListPrefix(line))
                }

                isHeading(line, current.isBlank() || boundary, next, median) -> {
                    flush()
                    blocks += Block(line, BlockKind.HEADING)
                }

                current.isEmpty() -> current.append(line)

                else -> {
                    val prevWasShortEnd = median > 0 && lastLineLength < median * 0.75 &&
                        endsSentence(currentText) && startsLikeNewBlock(line)
                    val listItemEnded = currentKind == BlockKind.LIST_ITEM && median > 0 &&
                        lastLineLength < median * 0.75
                    if (prevWasShortEnd || listItemEnded) {
                        // La ligne précédente terminait visiblement un paragraphe
                        flush()
                        current.append(line)
                    } else if (HYPHENATED_END_REGEX.containsMatchIn(currentText) && line.first().isLowerCase()) {
                        // Mot coupé en fin de ligne : on recolle sans tiret
                        current.setLength(current.length - 1)
                        current.append(line)
                    } else {
                        current.append(' ').append(line)
                    }
                }
            }
            lastLineLength = line.length
        }
        flush()
        return demoteHeadingRuns(blocks)
    }

    /** Au moins 3 « titres » d'affilée = en réalité des lignes courtes (tableau, liste sans puces). */
    private fun demoteHeadingRuns(blocks: List<Block>): List<Block> {
        val out = blocks.toMutableList()
        var i = 0
        while (i < out.size) {
            if (out[i].kind != BlockKind.HEADING) { i++; continue }
            var j = i
            while (j < out.size && out[j].kind == BlockKind.HEADING) j++
            if (j - i >= 3) for (k in i until j) out[k] = out[k].copy(kind = BlockKind.LIST_ITEM)
            i = j
        }
        return out
    }

    // ---------------------------------------------------------------------------------------
    // 4. Découpage en phrases
    // ---------------------------------------------------------------------------------------

    /** Abréviations jamais suivies d'une fin de phrase (titres, renvois…), toutes langues. */
    private val ABBREVIATIONS = setOf(
        "m", "mm", "mme", "mmes", "mlle", "mlles", "mgr", "dr", "drs", "pr", "prof", "me",
        "mr", "mrs", "ms", "messrs", "st", "ste", "sts", "sr", "sra", "sras", "srta", "sres",
        "dra", "jr", "hr", "hrn", "frl", "dott", "sig", "sigg", "ing", "avv", "gen", "col",
        "capt", "lt", "sgt", "gov", "rev", "cf", "cfr", "vs", "ex", "ca", "approx", "env",
        "av", "bd", "ave", "vgl", "sog", "bzw", "resp", "ibid", "op", "cit", "éd", "eds",
        "trad", "coord", "dir", "tél", "tel", "fig", "figs", "chap", "art", "réf", "ref", "al"
    )

    /** Abréviations suivies d'un nombre : « p. 12 », « n. 3 », « vol. 2 »… */
    private val ABBREVIATIONS_BEFORE_NUMBER = setOf(
        "p", "pp", "s", "ss", "n", "no", "nº", "nr", "num", "vol", "t", "tab", "sec", "sect",
        "par", "pág", "págs", "pag", "pagg", "ed", "cap"
    )

    private const val TERMINALS = ".!?…"
    private const val CLOSERS = "»\"”’')]"

    private fun startsNewSentence(c: Char): Boolean =
        c.isUpperCase() || c.isDigit() || c in "«\"“'(¿¡[—–-•"

    private fun isAbbreviationBefore(
        text: String,
        dotIndex: Int,
        segmentStart: Int,
        nextChar: Char,
        language: String?
    ): Boolean {
        if (text[dotIndex] != '.') return false
        if (dotIndex + 1 < text.length && text[dotIndex + 1] == '.') return false // points de suspension
        var b = dotIndex - 1
        while (b >= 0 && (text[b].isLetterOrDigit() || text[b] == '.')) b--
        val tokenStart = b + 1
        val token = text.substring(tokenStart, dotIndex)
        if (token.isEmpty()) return false
        val lower = token.lowercase()

        if (token.length == 1 && token[0].isLetter() && token[0].isUpperCase()) return true // initiale « J. »
        if (token.contains('.') && token.any { it.isLetter() }) return true                 // « e.g », « z.B », « U.S »
        if (lower in ABBREVIATIONS) return true
        if (lower in ABBREVIATIONS_BEFORE_NUMBER && nextChar.isDigit()) return true
        if (token.all { it.isDigit() }) {
            if (tokenStart == segmentStart) return true                                // « 1. Introduction »
            if (language == "de" && token.length <= 2) return true                     // « am 3. Oktober »
        }
        return false
    }

    internal fun splitSentences(text: String, language: String?): List<String> {
        val result = mutableListOf<String>()
        val n = text.length
        var start = 0
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c in TERMINALS) {
                var j = i + 1
                while (j < n && text[j] in TERMINALS) j++
                while (j < n && text[j] in CLOSERS) j++
                if (j < n && text[j].isWhitespace()) {
                    var k = j
                    while (k < n && text[k].isWhitespace()) k++
                    if (k < n && startsNewSentence(text[k]) &&
                        !(j == i + 1 && isAbbreviationBefore(text, i, start, text[k], language))
                    ) {
                        val sentence = text.substring(start, j).trim()
                        if (sentence.isNotEmpty()) result += sentence
                        start = k
                        i = k
                        continue
                    }
                }
                i = j
                continue
            }
            i++
        }
        if (start < n) {
            val last = text.substring(start).trim()
            if (last.isNotEmpty()) result += last
        }
        return result.flatMap { splitLong(it) }
    }

    /** Coupe les phrases trop longues (meilleur suivi visuel, synthèse plus stable). */
    internal fun splitLong(sentence: String): List<String> {
        if (sentence.length <= MAX_SEGMENT_LENGTH) return listOf(sentence)
        val middle = sentence.length / 2
        val minPart = 40
        for (separator in listOf("; ", ": ", ", ", " – ", " ")) {
            var bestPos = -1
            var idx = sentence.indexOf(separator)
            while (idx >= 0) {
                val cut = idx + separator.trimEnd().length
                if (cut >= minPart && sentence.length - cut >= minPart &&
                    (bestPos < 0 || kotlin.math.abs(cut - middle) < kotlin.math.abs(bestPos - middle))
                ) bestPos = cut
                idx = sentence.indexOf(separator, idx + 1)
            }
            if (bestPos > 0) {
                val left = sentence.substring(0, bestPos).trim()
                val right = sentence.substring(bestPos).trim()
                return splitLong(left) + splitLong(right)
            }
        }
        return listOf(sentence)
    }

    // ---------------------------------------------------------------------------------------
    // 5. Texte parlé
    // ---------------------------------------------------------------------------------------

    private val URL_REGEX = Regex("""https?://(?:www\.)?([^/\s]+)\S*""", RegexOption.IGNORE_CASE)
    private val WWW_REGEX = Regex("""\bwww\.([^/\s]+)\S*""", RegexOption.IGNORE_CASE)
    private val CITATION_REGEX = Regex("""\[\d+(?:\s*[,–-]\s*\d+)*]""")
    private val LEADER_DOTS_REGEX = Regex("""(?:\s?\.){4,}\s*""")
    private val SYMBOL_RUN_REGEX = Regex("""[_=~*#]{2,}""")
    private val DIALOG_DASH_REGEX = Regex("""^[—–-]\s*""")
    private val INNER_DASH_REGEX = Regex("""\s[—–]\s""")
    private val QUOTES_REGEX = Regex("""[«»“”"]""")
    private val DOUBLE_COMMA_REGEX = Regex(""",\s*,""")
    private val SPACE_BEFORE_PUNCT_REGEX = Regex("""\s+([,.…])""")

    internal fun toSpeech(display: String, kind: SegmentKind, isLastOfBlock: Boolean): String {
        var s = display
        s = URL_REGEX.replace(s) { it.groupValues[1].trimEnd('.', ',', ';', ')') }
        s = WWW_REGEX.replace(s) { it.groupValues[1].trimEnd('.', ',', ';', ')') }
        s = CITATION_REGEX.replace(s, "")
        s = LEADER_DOTS_REGEX.replace(s, ", ")
        s = SYMBOL_RUN_REGEX.replace(s, " ")
        s = s.replace('|', ',')
        s = BULLET_SYMBOL_REGEX.replace(s, "")
        s = DIALOG_DASH_REGEX.replace(s, "")
        s = INNER_DASH_REGEX.replace(s, ", ")
        s = QUOTES_REGEX.replace(s, "")
        s = WHITESPACE_REGEX.replace(s, " ").trim()
        s = DOUBLE_COMMA_REGEX.replace(s, ",")
        s = SPACE_BEFORE_PUNCT_REGEX.replace(s, "$1")
        s = s.trim(',', ' ')

        // Titres en MAJUSCULES : évite que la voix épelle chaque mot
        val letters = s.filter { it.isLetter() }
        if (kind == SegmentKind.HEADING && letters.length >= 6 && letters.count { it.isUpperCase() } >= letters.length * 0.8) {
            s = s.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
        // Intonation finale pour les titres et les éléments de liste
        if (kind != SegmentKind.SENTENCE && isLastOfBlock && s.isNotEmpty() && s.last() !in ".!?…:;") {
            s += "."
        }
        return s
    }

    // ---------------------------------------------------------------------------------------
    // 6. Assemblage
    // ---------------------------------------------------------------------------------------

    internal fun segment(blocks: List<Block>, language: String?): List<SentenceSegment> {
        val out = mutableListOf<SentenceSegment>()
        for (block in blocks) {
            val pieces = when (block.kind) {
                BlockKind.HEADING -> splitLong(block.text)
                else -> splitSentences(block.text, language)
            }
            pieces.forEachIndexed { pieceIndex, piece ->
                val isLast = pieceIndex == pieces.lastIndex
                val kind = when (block.kind) {
                    BlockKind.HEADING -> SegmentKind.HEADING
                    BlockKind.LIST_ITEM -> if (pieceIndex == 0) SegmentKind.LIST_ITEM else SegmentKind.SENTENCE
                    BlockKind.PARAGRAPH -> SegmentKind.SENTENCE
                }
                val speech = toSpeech(piece, if (block.kind == BlockKind.LIST_ITEM) SegmentKind.LIST_ITEM else kind, isLast)
                if (speech.none { it.isLetterOrDigit() }) return@forEachIndexed
                val pause = if (!isLast) 0 else when (block.kind) {
                    BlockKind.HEADING -> PAUSE_HEADING_MS
                    BlockKind.LIST_ITEM -> PAUSE_LIST_ITEM_MS
                    BlockKind.PARAGRAPH -> PAUSE_PARAGRAPH_MS
                }
                out += SentenceSegment(
                    index = out.size,
                    displayText = piece,
                    speechText = speech,
                    kind = kind,
                    pauseAfterMs = pause
                )
            }
        }
        return out
    }
}
