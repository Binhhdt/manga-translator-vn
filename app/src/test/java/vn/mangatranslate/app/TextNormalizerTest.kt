package vn.mangatranslate.app

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.mangatranslate.app.core.util.TextNormalizer

class TextNormalizerTest {

    @Test
    fun allCapsEnglishBecomesSentenceCase() {
        assertEquals("I will never forgive you!", TextNormalizer.fixAllCaps("I WILL NEVER FORGIVE YOU!"))
        assertEquals("Wait... What? I'm here.", TextNormalizer.fixAllCaps("WAIT... WHAT? I'M HERE."))
    }

    @Test
    fun mixedCaseIsUntouched() {
        assertEquals("Hello Naruto", TextNormalizer.fixAllCaps("Hello Naruto"))
    }

    @Test
    fun joinsLinesPerScript() {
        assertEquals("お前を許さない", TextNormalizer.joinLines(listOf("お前を", "許さない")))
        assertEquals("I will never forgive you", TextNormalizer.joinLines(listOf("I will never", "forgive you")))
        assertEquals("translate", TextNormalizer.joinLines(listOf("trans-", "late")))
        assertEquals("너를 용서하지 않겠다", TextNormalizer.joinLines(listOf("너를", "용서하지 않겠다")))
    }

    @Test
    fun cleansVietnamesePunctuation() {
        assertEquals("Ngươi dám !".let { TextNormalizer.cleanupVietnamese(it) }, "Ngươi dám!")
        assertEquals("Đi thôi.", TextNormalizer.cleanupVietnamese("  đi   thôi . "))
    }

    @Test
    fun removesSpacesBetweenCjkBeforeTranslation() {
        assertEquals("你好世界", TextNormalizer.prepareForTranslation("你好 世界", "zh"))
    }
}
