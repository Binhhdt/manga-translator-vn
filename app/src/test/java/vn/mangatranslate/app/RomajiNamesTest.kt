package vn.mangatranslate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.mangatranslate.app.core.translate.RomajiNames

class RomajiNamesTest {
    @Test
    fun findsJapaneseNamesInEnglish() {
        assertEquals(listOf("Kurosaki"), RomajiNames.find("Kurosaki."))
        assertEquals(listOf("Kanagawa"), RomajiNames.find("Kanagawa, GK, hurt!"))
        assertEquals(listOf("Kagoshima"), RomajiNames.find("I broke my arm against the power of Kagoshima!"))
        assertEquals(listOf("Tanaka"), RomajiNames.find("Hey, Tanaka, are you okay?"))
    }

    @Test
    fun ignoresOrdinaryEnglish() {
        assertTrue(RomajiNames.find("Don't touch me.").isEmpty())
        assertTrue(RomajiNames.find("Take it. Make it. Home now.").isEmpty())
        assertTrue(RomajiNames.find("I'm sorry, Hey, meds.").isEmpty())
    }
}
