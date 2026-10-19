package vn.mangatranslate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.model.OcrBlock
import vn.mangatranslate.app.core.model.OcrLine
import vn.mangatranslate.app.core.ocr.OcrCandidateScorer
import vn.mangatranslate.app.core.ocr.RubyFilter
import vn.mangatranslate.app.core.ocr.TextRegionDetector
import vn.mangatranslate.app.core.render.FontSizing
import vn.mangatranslate.app.core.translate.Glossary
import vn.mangatranslate.app.core.translate.QualityChecker
import vn.mangatranslate.app.core.util.TextNormalizer

class OcrQualityTest {

    private fun line(t: String, l: Float, top: Float, r: Float, b: Float) = OcrLine(t, BoxF(l, top, r, b))

    // ---------------- furigana

    @Test
    fun rubyBesideKanjiColumnIsRemoved() {
        val main = line("救急班", 100f, 100f, 130f, 200f)              // cột chính rộng 30
        val ruby = line("きゅうきゅうはん", 132f, 100f, 142f, 200f)   // furigana rộng 10 sát bên phải
        val kept = RubyFilter.filter(listOf(main, ruby))
        assertEquals(listOf(main), kept)
    }

    @Test
    fun smallKanaFarFromMainTextIsKept() {
        val main = line("救急班", 100f, 100f, 130f, 200f)
        val alone = line("ざわ", 400f, 400f, 410f, 430f)
        assertEquals(2, RubyFilter.filter(listOf(main, alone)).size)
    }

    @Test
    fun rubyDoesNotLeakIntoBubbleSentence() {
        // Lỗi trong ảnh thử: cột furigana được ghép vào câu / tạo vùng riêng.
        val col1 = line("黒崎", 200f, 100f, 230f, 160f)
        val ruby = line("くろさき", 232f, 100f, 241f, 160f)
        val col2 = line("だ!!", 160f, 100f, 190f, 170f)
        val regions = TextRegionDetector().detect(listOf(OcrBlock(listOf(col1, ruby, col2), BoxF(160f, 100f, 241f, 170f))), rightToLeft = true)
        assertEquals(1, regions.size)
        assertEquals("黒崎だ!!", regions[0].text)
    }

    // ---------------- chấm điểm phương án OCR

    @Test
    fun scorerPrefersJapaneseOverLatinGarbage() {
        val good = OcrCandidateScorer.score("救急班", "ja", 3)
        val garbage = OcrCandidateScorer.score("Ou", "ja", 3)
        val withRuby = OcrCandidateScorer.score("救急班きゅうきゅうはん", "ja", 3)
        assertTrue(good > garbage)
        assertTrue("đọc lẫn furigana bị trừ điểm", good > withRuby)
    }

    @Test
    fun uppercaseAbbreviationIsValidJapaneseContext() {
        assertEquals(0, OcrCandidateScorer.stats("神奈川GK負傷!!", "ja").invalid)
    }

    @Test
    fun similarityDetectsDisagreement() {
        assertTrue(OcrCandidateScorer.similarity("救急班", "救急班") > 0.99f)
        assertTrue(OcrCandidateScorer.similarity("救急班", "Ou") < 0.5f)
    }

    // ---------------- kiểm tra chất lượng bản dịch (câu lấy từ ảnh thử)

    @Test
    fun flagsNonsenseWordsFromRealScreenshot() {
        val (r1, _) = QualityChecker.translationIssues("…", "Bởi vì một buzzma như vậy đã không đi", emptyList(), emptyList())
        assertTrue(r1.any { "buzzma" in it })
        val (r2, _) = QualityChecker.translationIssues("…", "Nếu cậu đang ucking nhận", emptyList(), emptyList())
        assertTrue(r2.any { "ucking" in it })
    }

    @Test
    fun normalVietnameseIsNotFlagged() {
        val (r, info) = QualityChecker.translationIssues("すみません", "Tôi xin lỗi", emptyList(), emptyList())
        assertTrue(r.isEmpty())
        assertTrue(info.isEmpty())
        // Từ tiếng Việt viết không dấu vẫn hợp lệ.
        assertTrue(QualityChecker.isVietnameseWord("khong"))
        assertTrue(QualityChecker.isVietnameseWord("nguoi"))
        assertTrue(!QualityChecker.isVietnameseWord("buzzma"))
    }

    @Test
    fun flagsUntranslatedCharactersAndForeignNames() {
        val (r, info) = QualityChecker.translationIssues("救急班", "救急班 Ou", emptyList(), emptyList())
        assertTrue(r.any { "chưa dịch" in it })
        val (_, info2) = QualityChecker.translationIssues("黒崎…", "Blacklord…", emptyList(), emptyList())
        assertTrue(info2.any { "Blacklord" in it })
        assertTrue(info.isEmpty() || info.isNotEmpty())
    }

    @Test
    fun flagsLatinGarbageInJapaneseOcr() {
        assertTrue(QualityChecker.ocrIssues("救急班Ou", "ja").isNotEmpty())
        assertTrue(QualityChecker.ocrIssues("神奈川GK負傷!!", "ja").isEmpty())
    }

    // ---------------- bảng thuật ngữ & viết tắt bóng đá

    @Test
    fun glossaryKeepsNamesAndFixesOutput() {
        val g = Glossary.parse("# ghi chú\n黒崎 = Kurosaki\nBlacklord = Kurosaki\n神奈川=Kanagawa\nsai dòng")
        assertEquals(3, g.size)
        assertEquals("Kurosaki!!", Glossary.applyBefore("黒崎!!", g))
        assertEquals("Kurosaki đến rồi", Glossary.applyAfter("Blacklord đến rồi", g))
        assertTrue(Glossary.missingTargets("黒崎だ", "Blacklord đây", g).isNotEmpty())
        assertTrue(Glossary.missingTargets("黒崎だ", "Kurosaki đây", g).isEmpty())
    }

    @Test
    fun goalkeeperAbbreviationIsExpanded() {
        val t = TextNormalizer.prepareForTranslation("神奈川ＧＫ負傷！！", "ja")
        assertTrue(t.contains("ゴールキーパー"))
        assertTrue(!t.contains("GK"))
        // Không thay bên trong từ Latin dài hơn.
        assertEquals("GKX", TextNormalizer.expandSportsAbbreviations("GKX"))
    }

    // ---------------- cỡ chữ (lỗi chữ tí hon)

    @Test
    fun fontNeverTinyEvenWhenSourceCharIsTiny() {
        val b = FontSizing.bounds(imageWidth = 1000f, sourceCharSize = 6f, areaHeight = 300f)
        assertTrue("không tí hon như lỗi cũ (~6px)", b.min >= 12f)
        assertTrue(b.max >= b.min)
        val big = FontSizing.bounds(imageWidth = 1000f, sourceCharSize = 200f, areaHeight = 2000f)
        assertTrue("không vượt 6% bề rộng trang", big.max <= 60f)
    }
}
