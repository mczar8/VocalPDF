package com.example.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTextProcessorTest {

    private fun speech(raw: String, neighbors: List<String> = emptyList()) =
        PageTextProcessor.process(raw, neighbors).segments.map { it.speechText }

    @Test
    fun rejoinsHyphenatedWords() {
        val raw = "La lecture favorise la compré-\nhension des textes longs et complexes.\n"
        val s = speech(raw)
        assertEquals(1, s.size)
        assertTrue(s[0], s[0].contains("compréhension"))
    }

    @Test
    fun keepsAbbreviationsInsideSentences() {
        val raw = "M. Dupont a rencontré le Dr. Martin, voir p. 12 pour les détails. Ensuite il est parti."
        val s = speech(raw)
        assertEquals(s.toString(), 2, s.size)
        assertTrue(s[0].startsWith("M. Dupont"))
    }

    @Test
    fun splitsOnRealSentenceEnds() {
        val s = speech("Il pleut. Le vent souffle ! Que faire ? Rien… Attendre.")
        assertEquals(s.toString(), 5, s.size)
    }

    @Test
    fun removesPageNumbersAndRepeatedHeaders() {
        val page = "Rapport annuel 2025\nLe chiffre d'affaires progresse nettement cette année grâce aux nouveaux marchés.\n- 12 -"
        val prev = "Rapport annuel 2025\nUne autre page de texte avec du contenu suffisamment long pour être réaliste.\n- 11 -"
        val s = speech(page, listOf(prev))
        assertEquals(s.toString(), 1, s.size)
        assertTrue(s[0].startsWith("Le chiffre"))
    }

    @Test
    fun fixesLigaturesAndInvisibleCharacters() {
        val s = speech("Le ﬁchier est ofﬁciel­.")
        assertEquals("Le fichier est officiel.", s[0])
    }

    @Test
    fun detectsHeadingsAndListItems() {
        val raw = """
            Introduction
            Ce document présente les grandes étapes du projet de manière détaillée et précise.
            Les objectifs sont décrits dans les sections suivantes du présent rapport annuel.
            • Réduire les coûts
            • Améliorer la qualité
        """.trimIndent()
        val segs = PageTextProcessor.process(raw).segments
        assertEquals(SegmentKind.HEADING, segs.first().kind)
        assertEquals("Introduction.", segs.first().speechText)
        val list = segs.filter { it.kind == SegmentKind.LIST_ITEM }
        assertEquals(2, list.size)
        assertEquals("• Réduire les coûts", list[0].displayText)
        assertEquals("Réduire les coûts.", list[0].speechText)
    }

    @Test
    fun cleansSpeechText() {
        val s = speech("Voir le site https://www.example.com/docs/page.html pour plus d'infos [12].")
        assertEquals("Voir le site example.com pour plus d'infos.", s[0])
        val toc = speech("Chapitre premier .......... 5")
        assertFalse(toc[0], toc[0].contains(".."))
    }

    @Test
    fun uppercaseHeadingIsNotSpelled() {
        val raw = "CHAPITRE PREMIER\nIl était une fois un petit village au bord de la mer, très loin d'ici."
        val segs = PageTextProcessor.process(raw).segments
        assertEquals("Chapitre premier.", segs.first().speechText)
    }

    @Test
    fun splitsVeryLongSentences() {
        val long = (1..40).joinToString(", ") { "élément numéro $it" } + "."
        val segs = PageTextProcessor.process(long).segments
        assertTrue(segs.size > 1)
        assertTrue(segs.all { it.displayText.length <= 350 })
    }

    @Test
    fun germanOrdinalDatesAreNotSentenceEnds() {
        val raw = "Die Sitzung findet am 3. Oktober in Berlin statt und ist für alle Mitglieder offen. Danach gibt es ein Essen."
        val r = PageTextProcessor.process(raw)
        assertEquals("de", r.language)
        assertEquals(r.segments.map { it.speechText }.toString(), 2, r.segments.size)
    }

    @Test
    fun emptyPageGivesNoSegments() {
        assertTrue(PageTextProcessor.process("  \n 12 \n").segments.isEmpty())
    }

    @Test
    fun numberedHeadingDoesNotSwallowParagraph() {
        val raw = "2. État de l'art\nLes premiers travaux sur la synthèse vocale remontent aux années soixante, bien avant le numérique.\nIls reposaient sur des règles phonétiques écrites à la main par des linguistes."
        val segs = PageTextProcessor.process(raw).segments
        assertEquals(SegmentKind.HEADING, segs[0].kind)
        assertEquals("2. État de l'art", segs[0].displayText)
    }

    @Test
    fun shortTableRowsAreNotHeadings() {
        val segs = PageTextProcessor.process("Nom Prénom Âge\nDupont Jean 34\nMartin Paul 28").segments
        assertTrue(segs.none { it.kind == SegmentKind.HEADING })
    }
}
