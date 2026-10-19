package vn.mangatranslate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.mangatranslate.app.core.render.RenderSafety
import vn.mangatranslate.app.core.translate.QualityChecker
import vn.mangatranslate.app.core.util.TextNormalizer
import vn.mangatranslate.app.core.vision.BubbleDetector

/** Kiểm thử hồi quy cho lỗi bản 0.1.2: xóa mất hình vẽ, bản dịch vô nghĩa không bị chặn. */
class SafetyRegressionTest {

    /**
     * Mô phỏng khung tranh phải của trang 006: nền khung TRẮNG, có cầu thủ (mảng nét lớn) và
     * một bong bóng hình sao HỞ thông với nền khung. Lỗi cũ: cả khung bị coi là "bong bóng" và bị xóa trắng.
     */
    @Test
    fun whitePanelWithArtworkIsNotABubble() {
        val p = SyntheticPage(600, 800, background = 255)
        // viền khung tranh
        p.fillRect(100, 100, 104, 700); p.fillRect(500, 100, 504, 700)
        p.fillRect(100, 100, 504, 104); p.fillRect(100, 696, 504, 700)
        // "cầu thủ": mảng nét lớn liền nhau
        p.fillRect(250, 300, 330, 600)
        p.fillRect(220, 330, 360, 360)
        // chữ nhỏ trong vùng trắng (bong bóng hở)
        p.column(400, 150, 24, 4)
        val res = BubbleDetector().detect(p.img)
        val big = res.bubbles.filter { it.width > 150 || it.height > 150 }
        assertTrue("khung tranh có hình vẽ không được coi là bong bóng", big.isEmpty())
    }

    @Test
    fun realBubbleStillDetectedAfterStricterRules() {
        val p = SyntheticPage(1200, 900, background = 120)
        p.bubble(150, 200, 90, 140)
        p.column(160, 110, 30, 4)
        assertEquals(1, BubbleDetector().detect(p.img).bubbles.size)
    }

    // ---------------- an toàn khi xóa chữ

    @Test
    fun abnormalMasksAreRejected() {
        val page = 1000L * 1500L
        assertNull(RenderSafety.maskProblem(maskPixels = 2_000, allowedPixels = 10_000, pagePixels = page))
        // vùng xóa chiếm 23% trang (như lỗi khung tranh) → từ chối
        assertTrue(RenderSafety.maskProblem(50_000, 345_000, page) != null)
        // mask phủ 90% khung chữ → không phải nét chữ → từ chối
        assertTrue(RenderSafety.maskProblem(9_000, 10_000, page) != null)
        assertTrue(RenderSafety.maskProblem(0, 0, page) != null)
    }

    @Test
    fun changesOutsideAllowedAreasAreDetected() {
        val w = 10
        val h = 10
        val orig = IntArray(w * h) { 0xFFFFFF }
        val out = orig.copyOf()
        out[2 * w + 2] = 0 // trong vùng cho phép
        val allowed = listOf(RenderSafety.Rect(1, 1, 4, 4))
        assertEquals(0, RenderSafety.changedOutside(orig, out, w, h, allowed))
        out[8 * w + 8] = 0 // ngoài vùng cho phép (giống xóa mất hình)
        assertEquals(1, RenderSafety.changedOutside(orig, out, w, h, allowed))
        // Ảnh không đổi → luôn 0 (bật/tắt bản dịch trả về ảnh gốc).
        assertEquals(0, RenderSafety.changedOutside(orig, orig.copyOf(), w, h, emptyList()))
    }

    // ---------------- lỗi dịch trong ảnh thử

    @Test
    fun verticalEllipsisReadAsColonIsNormalized() {
        assertEquals("いててて…クソ", TextNormalizer.normalizeEllipsis("いててて::クソ"))
        assertEquals("残念だなァ…", TextNormalizer.normalizeEllipsis("残念だなァ：："))
        assertEquals("な―…", TextNormalizer.normalizeEllipsis("な|・・・"))
        assertTrue(!TextNormalizer.prepareForTranslation("受け身がとれてたら::", "ja").contains(":"))
    }

    @Test
    fun katakanaEmphasisIsTranslatable() {
        // "buzzma": ブザマ (無様) bị máy dịch phiên âm.
        assertEquals("こんな無様はさらさなかった", TextNormalizer.katakanaEmphasisToKanji("こんなブザマはさらさなかった"))
        // Không thay bên trong từ katakana dài hơn.
        assertEquals("ダメージ", TextNormalizer.katakanaEmphasisToKanji("ダメージ"))
        assertEquals("駄目だ", TextNormalizer.katakanaEmphasisToKanji("ダメだ"))
    }

    @Test
    fun nonsenseFromScreenshotIsFlagged() {
        val (r1, _) = QualityChecker.translationIssues("黒崎おいっ大丈夫かよォ…", "Kurosaki otoji 4 ok ok", emptyList(), emptyList())
        assertTrue(r1.any { "otoji" in it })
        assertTrue(r1.any { "lặp từ" in it })
        assertTrue(r1.any { "số" in it })
        val (r2, _) = QualityChecker.translationIssues("…", "Xin vui lòng:: ※ Nếu cậu có một người nhận chết tiệt", emptyList(), emptyList())
        // dấu "::" và "※" không phải từ; câu này được làm sạch ở bước chuẩn hóa nguồn, phần còn lại là tiếng Việt hợp lệ
        assertTrue(r2.none { "lặp từ" in it })
    }

    @Test
    fun repeatedOcrChunksAreFlagged() {
        assertEquals("骨折", QualityChecker.repeatedChunk("骨折骨折骨折腕腕腕"))
        assertTrue(QualityChecker.ocrIssues("が黒崎黒崎黒崎!骨折骨折骨折", "ja").any { "lặp" in it })
        assertNull(QualityChecker.repeatedChunk("黒崎おいっ大丈夫かよォ"))
    }

    @Test
    fun legitimateVietnameseReduplicationIsNotFlagged() {
        val (r, _) = QualityChecker.translationIssues("おいおい", "Này này…", emptyList(), emptyList())
        assertTrue(r.isEmpty())
    }
}
