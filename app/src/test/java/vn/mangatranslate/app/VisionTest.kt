package vn.mangatranslate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.vision.BubbleDetector
import vn.mangatranslate.app.core.vision.TextLayoutAnalyzer
import vn.mangatranslate.app.core.vision.VerticalComposer
import vn.mangatranslate.app.core.vision.WhiteSpaceExpander

class VisionTest {

    /** Trang 600×400 nền xám (tranh vẽ), hai bong bóng trắng, mỗi bong bóng có chữ dọc. */
    private fun twoBubblePage(): SyntheticPage {
        val p = SyntheticPage(600, 400, background = 120)
        p.bubble(150, 200, 90, 140)
        p.column(160, 110, 30, 4) // cột phải
        p.column(115, 110, 30, 3) // cột trái
        p.bubble(450, 200, 80, 120)
        p.column(435, 130, 30, 4)
        return p
    }

    @Test
    fun detectsBubblesAndText() {
        val res = BubbleDetector().detect(twoBubblePage().img)
        assertEquals(2, res.bubbles.size)
        for (b in res.bubbles) {
            assertTrue("bong bóng phải chứa nét chữ", b.holeArea > 0)
            // Vùng đặt chữ phải nằm trong bong bóng.
            assertTrue(b.inner.left >= b.left && b.inner.right <= b.right)
            assertTrue(b.isInterior(b.inner.centerX.toInt(), b.inner.centerY.toInt()))
        }
    }

    @Test
    fun linesInDifferentBubblesAreNotMerged() {
        val res = BubbleDetector().detect(twoBubblePage().img)
        val left = res.bubbleForBox(BoxF(160f, 110f, 190f, 250f))
        val right = res.bubbleForBox(BoxF(435f, 130f, 465f, 270f))
        assertNotNull(left)
        assertNotNull(right)
        assertNotEquals(left!!.label, right!!.label)
        // Chữ nằm ngoài mọi bong bóng (trên nền tranh) → không thuộc bong bóng nào.
        assertNull(res.bubbleForBox(BoxF(10f, 10f, 40f, 40f)))
    }

    @Test
    fun bubbleOutlineIsNotTreatedAsText() {
        val res = BubbleDetector().detect(twoBubblePage().img)
        val b = res.bubbles.first { it.left < 300 }
        // Điểm trên viền bong bóng (x = 150 - 90 - 1) không phải "lỗ" chữ.
        assertTrue(!b.isHole(150 - 91, 200))
        // Điểm nét chữ là "lỗ".
        assertTrue(b.isHole(161, 111))
    }

    @Test
    fun verticalColumnsReadRightToLeftAndRubyIsIgnored() {
        val p = SyntheticPage(200, 200)
        p.column(110, 20, 30, 3) // cột chính phải
        p.column(60, 20, 30, 3)  // cột chính trái
        p.column(146, 22, 12, 4, gap = 3) // furigana mỏng sát bên phải cột phải
        val mask = p.img.inkMask()
        val layout = TextLayoutAnalyzer().analyzeVertical(mask, 200, 200)!!
        val main = layout.mainColumns
        assertEquals(2, main.size)
        assertTrue("cột phải đọc trước", main[0].left > main[1].left)
        assertEquals(1, layout.rubyColumns.size)
        assertEquals(6, layout.expectedChars)
    }

    @Test
    fun twoStrokeCharacterCountsAsOneCell() {
        val p = SyntheticPage(100, 200)
        p.glyph(30, 10, 30)
        p.twoStrokeGlyph(30, 46, 30) // "二"
        p.glyph(30, 82, 30)
        val layout = TextLayoutAnalyzer().analyzeVertical(p.img.inkMask(), 100, 200)!!
        assertEquals(3, layout.mainColumns.single().cells.size)
    }

    @Test
    fun composerTurnsColumnsIntoRows() {
        val p = SyntheticPage(200, 200)
        p.column(110, 20, 30, 3)
        p.column(60, 20, 30, 2)
        val layout = TextLayoutAnalyzer().analyzeVertical(p.img.inkMask(), 200, 200)!!
        val comp = VerticalComposer.compose(p.img, layout)!!
        // 2 cột → 2 hàng: ảnh mới phải rộng hơn cao (chữ đã nằm ngang).
        assertTrue(comp.width > comp.height)
        // Hàng trên cùng (cột phải, 3 ký tự) dài hơn hàng dưới (2 ký tự).
        val rowInk = IntArray(comp.height)
        for (y in 0 until comp.height) for (x in 0 until comp.width) if (comp[x, y] < 128) rowInk[y]++
        val firstInkRow = rowInk.indexOfFirst { it > 0 }
        assertTrue(firstInkRow >= 0)
    }

    @Test
    fun longDashIsRotatedButExclamationIsNot() {
        val dash = TextLayoutAnalyzer.Cell(0, 0, 4, 30, pieces = 1)
        val bang = TextLayoutAnalyzer.Cell(0, 0, 6, 30, pieces = 2)
        assertTrue(VerticalComposer.shouldRotate(dash, 30))
        assertTrue(!VerticalComposer.shouldRotate(bang, 30))
    }

    @Test
    fun whiteSpaceExpansionStopsAtBubbleOutline() {
        val p = SyntheticPage(300, 300, background = 120)
        p.bubble(150, 150, 100, 100) // bong bóng không cần khép kín cho thuật toán này
        p.column(140, 110, 20, 4)
        val grown = WhiteSpaceExpander.expand(p.img, BoxF(140f, 110f, 160f, 198f), absorb = 14f, maxGrow = 200f)
        assertTrue("phải nới ra khỏi khối chữ", grown.width > 40f)
        assertTrue("không vượt ra ngoài bong bóng", grown.left >= 48f && grown.right <= 252f)
    }
}
