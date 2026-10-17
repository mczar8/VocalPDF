package com.example.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageDetectorTest {

    private fun lang(text: String) = LanguageDetector.detect(text)?.language

    @Test
    fun detectsSupportedLanguages() {
        assertEquals("fr", lang("Le renard est un animal qui vit dans la forêt et il chasse pendant la nuit avec ses petits."))
        assertEquals("en", lang("The fox is an animal that lives in the forest and it hunts at night with its young."))
        assertEquals("es", lang("El zorro es un animal que vive en el bosque y caza por la noche con sus crías."))
        assertEquals("de", lang("Der Fuchs ist ein Tier, das im Wald lebt und nachts mit seinen Jungen auf die Jagd geht."))
        assertEquals("it", lang("La volpe è un animale che vive nel bosco e caccia di notte con i suoi piccoli."))
        assertEquals("pt", lang("A raposa é um animal que vive na floresta e caça durante a noite com os seus filhotes."))
    }

    @Test
    fun returnsNullWhenTooShort() {
        assertNull(lang("Bonjour"))
        assertNull(lang("12 34 56"))
    }
}
