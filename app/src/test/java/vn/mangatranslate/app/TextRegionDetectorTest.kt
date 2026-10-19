package vn.mangatranslate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.model.OcrBlock
import vn.mangatranslate.app.core.model.OcrLine
import vn.mangatranslate.app.core.ocr.TextRegionDetector

class TextRegionDetectorTest {

    private val detector = TextRegionDetector()

    private fun block(vararg lines: OcrLine) = OcrBlock(lines.toList(), BoxF.unionOf(lines.map { it.box }))
    private fun line(text: String, l: Float, t: Float, r: Float, b: Float) = OcrLine(text, BoxF(l, t, r, b))

    @Test
    fun mergesBlocksOfSameBubble() {
        // Hai khối cách nhau 10px, cỡ chữ 30px → cùng một bong bóng.
        val a = block(line("I WILL NEVER", 100f, 100f, 300f, 130f))
        val b = block(line("FORGIVE YOU!", 105f, 140f, 295f, 170f))
        val regions = detector.detect(listOf(a, b), rightToLeft = false)
        assertEquals(1, regions.size)
        assertEquals("I WILL NEVER FORGIVE YOU!", regions[0].text)
        assertEquals(false, regions[0].vertical)
    }

    @Test
    fun keepsSeparateBubblesApart() {
        val a = block(line("HELLO", 100f, 100f, 250f, 130f))
        val b = block(line("GOODBYE", 600f, 900f, 800f, 930f))
        val regions = detector.detect(listOf(a, b), rightToLeft = false)
        assertEquals(2, regions.size)
        assertEquals("HELLO", regions[0].text)
    }

    @Test
    fun readsVerticalColumnsRightToLeft() {
        // Hai cột chữ dọc tiếng Nhật: cột phải đọc trước.
        val right = line("お前を", 300f, 100f, 330f, 200f)
        val left = line("許さない", 260f, 100f, 290f, 230f)
        val regions = detector.detect(listOf(block(left, right)), rightToLeft = true)
        assertEquals(1, regions.size)
        assertTrue(regions[0].vertical)
        assertEquals("お前を許さない", regions[0].text)
    }

    @Test
    fun dropsPunctuationOnlyNoise() {
        val noise = block(line("...", 10f, 10f, 40f, 20f))
        assertTrue(detector.detect(listOf(noise), rightToLeft = false).isEmpty())
    }

    @Test
    fun ordersRightToLeftForManga() {
        val leftBubble = block(line("左", 100f, 100f, 130f, 130f), line("側", 100f, 132f, 130f, 162f), line("だ", 100f, 164f, 130f, 194f))
        val rightBubble = block(line("右", 700f, 100f, 730f, 130f), line("側", 700f, 132f, 730f, 162f), line("だ", 700f, 164f, 730f, 194f))
        val regions = detector.detect(listOf(leftBubble, rightBubble), rightToLeft = true)
        assertEquals(2, regions.size)
        assertEquals("右側だ", regions[0].text)
    }
}
