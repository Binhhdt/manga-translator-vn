package vn.mangatranslate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.mangatranslate.app.core.ocr.CandidatePolicy
import vn.mangatranslate.app.core.ocr.CandidatePolicy.Option
import vn.mangatranslate.app.core.ocr.RegionOcr

/**
 * Dữ liệu lấy NGUYÊN VĂN từ lần chạy ML Kit thật trên trang 006 (trình giả lập, CI).
 * Bộ chọn cũ đã chọn bản bị trộn ở các vùng này.
 */
class CandidatePolicyTest {

    private val page = RegionOcr.METHOD_PAGE
    private val direct = RegionOcr.METHOD_DIRECT
    private val cols = RegionOcr.METHOD_COLUMNS
    private val bw = RegionOcr.METHOD_BINARY

    @Test
    fun region7_scrambledPageTextIsNotChosen() {
        val opts = listOf(
            Option(page, "腕3パ鹿あをワ児ま !骨|島:り折ちにのの"),
            Option(direct, "あまりの鹿児島のパワーに腕を骨折"),
            Option(cols, "あまりの鹿児島のパワーに腕を骨折"),
            Option(bw, "あまりの鹿児島のパワーに腕を骨折")
        )
        assertEquals("あまりの鹿児島のパワーに腕を骨折", CandidatePolicy.choose(opts, "ja", 20, vertical = true)!!.text)
    }

    @Test
    fun region1_furiganaPollutedDirectReadLosesToColumns() {
        val opts = listOf(
            Option(page, "負G!神傷K奈、"),
            Option(direct, "かながわゴールキー 負傷!"),
            Option(cols, "神奈川 GK 負傷!")
        )
        assertEquals("神奈川 GK 負傷!", CandidatePolicy.choose(opts, "ja", 14, vertical = true)!!.text)
    }

    @Test
    fun region9_columnsWinTieOverTypo() {
        val opts = listOf(
            Option(page, "こんなブザマはきらきなかっ"),
            Option(direct, "こんなブザマはさらきなかったのによ"),
            Option(cols, "こんなブザマはさらさなかったのによ")
        )
        assertEquals("こんなブザマはさらさなかったのによ", CandidatePolicy.choose(opts, "ja", 17, vertical = true)!!.text)
    }

    @Test
    fun region11_scrambledPageNotChosenEvenWhenOthersImperfect() {
        val opts = listOf(
            Option(page, "な残ア念や :だ"),
            Option(direct, "残念だなテ."),
            Option(cols, "漫念だなア::")
        )
        val chosen = CandidatePolicy.choose(opts, "ja", 7, vertical = true)!!
        assertTrue(chosen.method != page)
    }

    @Test
    fun pageTextUsedOnlyWhenNothingElse() {
        val opts = listOf(Option(page, "黒崎"))
        assertEquals("黒崎", CandidatePolicy.choose(opts, "ja", 2, vertical = true)!!.text)
    }

    @Test
    fun horizontalTextKeepsScoring() {
        val opts = listOf(
            Option(page, "★この物語はフィクションです。 実在の入物、"),
            Option(bw, "*この物はフィクションです。実在の人物、")
        )
        assertEquals(page, CandidatePolicy.choose(opts, "ja", null, vertical = false)!!.method)
    }

    @Test
    fun verticalityFromImageLayout() {
        // (rộng, cao, số ô) của các cột đo được trên trang 006.
        assertTrue(CandidatePolicy.isVerticalByImage(listOf(Triple(24, 70, 2), Triple(24, 100, 3), Triple(24, 100, 3))))
        assertTrue(CandidatePolicy.isVerticalByImage(listOf(Triple(25, 80, 3)))) // 黒崎 một cột
        // Chú thích ngang cuối trang: 1 "cột" rộng 421px, 1 ô; hoặc 30 cột 1 ô.
        assertTrue(!CandidatePolicy.isVerticalByImage(listOf(Triple(421, 30, 1))))
        assertTrue(!CandidatePolicy.isVerticalByImage(List(30) { Triple(19, 20, 1) }))
        // Dải ngang trên trang 006: 30 cột, chỉ 2 cột 2 ô (có một cột cao) → vẫn là chữ ngang.
        assertTrue(!CandidatePolicy.isVerticalByImage(List(28) { Triple(19, 20, 1) } + listOf(Triple(10, 40, 2), Triple(10, 40, 2))))
        // Vùng #7 thật: 2/4/4/4/4/1/1 → chữ dọc.
        assertTrue(CandidatePolicy.isVerticalByImage(listOf(2, 4, 4, 4, 4, 1, 1).map { Triple(36, it * 40, it) }))
    }
}
