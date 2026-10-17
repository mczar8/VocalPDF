package com.example.text

import com.example.text.SpeechChunker.Pause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {

    private fun words(s: String) = s.replace(",", " ").split(' ').filter { it.isNotBlank() }

    @Test
    fun shortSentenceIsKeptWhole() {
        val chunks = SpeechChunker.split("Le chat dort sur le canapé.")
        assertEquals(1, chunks.size)
        assertEquals(Pause.END, chunks[0].pause)
        assertTrue(SpeechChunker.split("   ").isEmpty())
    }

    @Test
    fun splitsAtCommasWithCommaPause() {
        val chunks = SpeechChunker.split("Après une longue journée de travail, les enfants sont enfin rentrés à la maison.")
        assertEquals(2, chunks.size)
        assertEquals(Pause.COMMA, chunks[0].pause)
        assertTrue(chunks[0].text.endsWith(","))
        assertEquals(Pause.END, chunks[1].pause)
    }

    @Test
    fun longSentenceWithoutCommaGetsBreathGroupsBeforeLinkWords() {
        val text = "Le conseil municipal se réunira demain matin dans la grande salle de la mairie pour voter le budget annuel qui prévoit une hausse importante des investissements dans les écoles."
        val chunks = SpeechChunker.split(text)
        assertTrue(chunks.toString(), chunks.size >= 2)
        assertTrue(chunks.dropLast(1).all { it.pause == Pause.BREATH && it.text.endsWith(",") })
        // Chaque nouveau groupe commence par un mot de liaison
        chunks.drop(1).forEach { c ->
            val first = c.text.split(' ').first().lowercase()
            assertTrue(c.text, first in setOf("dans", "pour", "qui", "des", "et"))
        }
        assertTrue(chunks.all { it.text.length <= 120 })
        // Aucun mot perdu ni ajouté
        assertEquals(words(text), words(chunks.joinToString(" ") { it.text }))
    }

    @Test
    fun tinyLeadingPartIsMerged() {
        val chunks = SpeechChunker.split("Oui, je viendrai demain avec plaisir.")
        assertEquals(1, chunks.size)
        assertEquals("Oui, je viendrai demain avec plaisir.", chunks[0].text)
    }

    @Test
    fun veryLongTextWithoutLinkWordsIsStillBounded() {
        val text = (1..60).joinToString(" ") { "mot$it" }
        val chunks = SpeechChunker.split(text)
        assertTrue(chunks.all { it.text.length <= 170 })
        assertEquals(words(text), words(chunks.joinToString(" ") { it.text }))
    }

    @Test
    fun englishLinkWordsAreUsed() {
        val text = "The government presented a new bill last night intended to simplify the procedures of small businesses that have long complained about the complexity of the forms."
        val chunks = SpeechChunker.split(text)
        assertTrue(chunks.size >= 2)
        assertEquals(words(text), words(chunks.joinToString(" ") { it.text }))
    }

    @Test
    fun paceFactorSlowsOnlyLongPassagesSlightly() {
        assertEquals(1f, SpeechChunker.paceFactor("Le chat dort."), 0.0001f)
        val long = SpeechChunker.paceFactor("a".repeat(150))
        assertEquals(0.94f, long, 0.0001f)
        val mid = SpeechChunker.paceFactor("a".repeat(75))
        assertTrue(mid < 1f && mid > 0.94f)
    }
}
