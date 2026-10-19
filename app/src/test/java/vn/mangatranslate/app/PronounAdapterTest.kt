package vn.mangatranslate.app

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.mangatranslate.app.core.model.PronounStyle
import vn.mangatranslate.app.core.translate.PronounAdapter

class PronounAdapterTest {

    @Test
    fun taNguoiStyle() {
        assertEquals(
            "Ta sẽ không bao giờ tha thứ cho ngươi!",
            PronounAdapter.apply("Tôi sẽ không bao giờ tha thứ cho bạn!", PronounStyle.TA_NGUOI)
        )
    }

    @Test
    fun keepStyleDoesNothing() {
        val s = "Tôi sẽ không bao giờ tha thứ cho bạn!"
        assertEquals(s, PronounAdapter.apply(s, PronounStyle.KEEP))
    }

    @Test
    fun pluralAndPhrasesFirst() {
        assertEquals(
            "Chúng ta sẽ đánh bại các ngươi.",
            PronounAdapter.apply("Chúng tôi sẽ đánh bại các bạn.", PronounStyle.TA_NGUOI)
        )
    }

    @Test
    fun doesNotTouchFriendMeaningOrInsideWords() {
        assertEquals(
            "Ta có nhiều bạn bè. Ngươi là người bạn tốt.",
            PronounAdapter.apply("Tôi có nhiều bạn bè. Bạn là người bạn tốt.", PronounStyle.TA_NGUOI)
        )
        // "tối" (buổi tối) khác "tôi" — không bị thay.
        assertEquals("Tối nay cậu đi đâu?", PronounAdapter.apply("Tối nay bạn đi đâu?", PronounStyle.TOI_CAU))
    }

    @Test
    fun keepsUppercase() {
        assertEquals("TA ĐẾN ĐÂY!", PronounAdapter.apply("TÔI ĐẾN ĐÂY!", PronounStyle.TA_NGUOI))
    }
}
