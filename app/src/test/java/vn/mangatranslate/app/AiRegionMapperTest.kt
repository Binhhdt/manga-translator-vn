package vn.mangatranslate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.mangatranslate.app.core.chatgpt.AiRegionMapper
import vn.mangatranslate.app.core.chatgpt.MangaJson
import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.vision.BubbleDetector
import vn.mangatranslate.app.core.vision.GrayImage

/** Trang tổng hợp (không dùng ảnh có bản quyền): bong bóng khép kín, chữ trên nền trắng, vùng hình vẽ dày nét. */
class AiRegionMapperTest {

    private val w = 400
    private val h = 300
    private val art = BoxF(200f, 150f, 300f, 250f)

    private fun page(): GrayImage {
        val g = GrayImage.blank(w, h)
        // Bong bóng elip khép kín, viền dày ~3 px, tâm (100, 80), bán kính 42×38 (~4% trang; > 6% bị BubbleDetector loại).
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - 100) / 42.0
            val dy = (y - 80) / 38.0
            val d = dx * dx + dy * dy
            if (d in 0.86..1.0) g[x, y] = 0
        }
        // "Chữ" trong bong bóng: hai cột nét nhỏ.
        for (col in 0..1) for (row in 0..3) glyph(g, 90 + col * 16, 58 + row * 12, row % 2 == 0)
        // Chữ trên nền trắng (không bong bóng) ở góc phải trên.
        for (row in 0..2) glyph(g, 320, 30 + row * 12, row % 2 == 1)
        // Vùng hình vẽ: gạch chéo dày đặc.
        for (y in art.top.toInt() until art.bottom.toInt()) for (x in art.left.toInt() until art.right.toInt()) {
            if ((x + y) % 4 == 0 || (x - y) % 5 == 0) g[x, y] = 0
        }
        return g
    }

    /** "Chữ" 8×8: dấu + hoặc hình 口 (mật độ mực ~30%, giống nét chữ thật). */
    private fun glyph(g: GrayImage, x0: Int, y0: Int, box: Boolean) {
        for (y in 0 until 8) for (x in 0 until 8) {
            val ink = if (box) (x == 0 || x == 7 || y == 0 || y == 7) else (x == 3 || x == 4 || y == 3 || y == 4)
            if (ink) g[x0 + x, y0 + y] = 0
        }
    }

    private fun region(id: String, type: String, x: Double, y: Double, bw: Double, bh: Double, uncertain: Boolean = false) =
        MangaJson.Region(id, type, "あいうえ", "Xin chào", listOf(x / w, y / h, bw / w, bh / h), uncertain)

    @Test
    fun mapsBubbleOverlayAndNeverTouchesArtwork() {
        val g = page()
        val bubbles = BubbleDetector().detect(g)
        val regions = listOf(
            region("1", "dialogue", 70.0, 45.0, 60.0, 70.0),          // bong bóng khép kín
            region("2", "narration", 312.0, 24.0, 24.0, 46.0),         // chữ trên nền trắng
            region("3", "dialogue", 210.0, 160.0, 80.0, 80.0),         // AI đặt nhầm lên hình vẽ
            region("4", "sfx", 320.0, 200.0, 40.0, 40.0),              // chữ hiệu ứng
            region("5", "dialogue", 70.0, 45.0, 60.0, 70.0, uncertain = true)
        )
        val ds = AiRegionMapper.map(regions, w, h, g, 1f, bubbles)
        val byId = ds.associateBy { it.ids.first() }

        val b = byId.getValue("1")
        assertEquals(AiRegionMapper.Action.BUBBLE, b.action)
        assertNotNull(b.bubble)
        // Khung chữ dùng nét mực thật, nằm trong bong bóng, không phải bbox AI.
        val tb = b.textBox!!
        assertTrue(tb.left >= 85f && tb.right <= 125f && tb.top >= 55f && tb.bottom <= 110f)

        val o = byId.getValue("2")
        assertEquals(AiRegionMapper.Action.OVERLAY, o.action)
        assertFalse(o.coverBox!!.intersects(art))
        assertFalse(o.layoutBox!!.intersects(art))

        assertEquals(AiRegionMapper.Action.SKIP, byId.getValue("3").action)
        assertEquals(AiRegionMapper.Action.SKIP, byId.getValue("4").action)
        assertEquals(AiRegionMapper.Action.SKIP, byId.getValue("5").action)

        // Không quyết định nào được phép đụng vào vùng hình vẽ.
        for (d in ds) {
            if (d.action == AiRegionMapper.Action.SKIP) continue
            assertFalse("Vùng #${d.ids} đè lên hình vẽ", (d.coverBox ?: d.textBox!!).intersects(art))
        }
    }

    @Test
    fun missingBboxOrTranslationIsSkipped() {
        val g = page()
        val ds = AiRegionMapper.map(
            listOf(
                MangaJson.Region("1", "dialogue", "あ", "", listOf(0.2, 0.2, 0.1, 0.1), false),
                MangaJson.Region("2", "dialogue", "あ", "Chào", null, false)
            ), w, h, g, 1f, BubbleDetector().detect(g)
        )
        assertTrue(ds.all { it.action == AiRegionMapper.Action.SKIP })
    }
}
