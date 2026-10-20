package vn.mangatranslate.app.core.chatgpt

import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.vision.BubbleDetector
import kotlin.math.abs
import kotlin.math.max

/**
 * KIỂM TRA SAU KHI RENDER (thuần Kotlin, chạy được trên JVM): so ảnh gốc với ảnh đã chèn, đối chiếu JSON,
 * phân loại từng vùng. Chỉ kiểm tra và báo cáo — không tự xóa thêm gì.
 *
 * - OK: đã chèn, không còn nét chữ Nhật ngoài vùng chữ Việt.
 * - B: có bản dịch trong JSON nhưng không chèn (không tách được chữ an toàn, không đủ chỗ…).
 * - C: đã chèn nhưng nét chữ Nhật gốc vẫn còn (ngoài vùng vẽ chữ Việt).
 * - D: cố ý giữ nguyên (chữ hiệu ứng, AI đánh dấu chưa chắc).
 * - A: bong bóng khép kín có nét giống chữ nhưng KHÔNG có vùng nào trong JSON (MISSING_TRANSLATION) — chỉ báo,
 *      không bịa bản dịch; người dùng có thể cho phép dịch bổ sung riêng vùng này.
 */
object PostRenderCheck {

    /** Only restore skipped source text when no successfully drawn area can overlap it. */
    fun safeToRestore(box: BoxF, drawnAreas: List<BoxF>): Boolean {
        if (box.width < 2f || box.height < 2f) return false
        return drawnAreas.none { area -> box.intersects(area) }
    }

    enum class Cat(val code: String, val label: String) {
        OK("OK", "đã chèn"),
        B("B", "có bản dịch nhưng chưa chèn"),
        C("C", "nghi còn chữ Nhật"),
        D("D", "cố ý giữ nguyên"),
        A("A", "nghi thiếu bản dịch (cần xác nhận)")
    }

    data class Item(val num: Int, val cat: Cat, val box: BoxF, val ids: List<String>, val reason: String, val remain: Float)

    /**
     * @param orig/out ảnh xám (0–255) cỡ ảnh gốc [w]×[h].
     * @param drawn số thứ tự quyết định (index+1) đã vẽ thành công.
     * @param notDrawnReason lý do không vẽ (không đủ chỗ, mask bị từ chối…) theo số thứ tự quyết định.
     * @param s tỉ lệ ảnh xám dùng tìm bong bóng / ảnh gốc.
     */
    fun check(
        w: Int, h: Int, orig: IntArray, out: IntArray,
        regions: List<MangaJson.Region>,
        decisions: List<AiRegionMapper.Decision>,
        drawn: Set<Int>,
        notDrawnReason: Map<Int, String>,
        bubbles: BubbleDetector.Result,
        s: Float
    ): List<Item> {
        val items = ArrayList<Item>()
        var num = 0
        decisions.forEachIndexed { i, d ->
            val idx = i + 1
            val box = d.textBox ?: d.aiBox
            if (d.action == AiRegionMapper.Action.SKIP) {
                val purposeful = d.type.equals("sfx", true) || d.reason.startsWith("AI đánh dấu")
                items += Item(++num, if (purposeful) Cat.D else Cat.B, d.aiBox, d.ids, d.reason, 1f)
                return@forEachIndexed
            }
            if (idx !in drawn) {
                items += Item(++num, Cat.B, box, d.ids, notDrawnReason[idx] ?: "Không vẽ được", 1f)
                return@forEachIndexed
            }
            val remain = remaining(w, h, orig, out, d, s)
            items += when {
                remain > 0.15f -> Item(++num, Cat.C, box, d.ids, "Còn ${(remain * 100).toInt()}% nét chữ Nhật ngoài vùng chữ Việt", remain)
                d.residual > 0.08f -> Item(++num, Cat.C, box, d.ids,
                    "Khoảng ${(d.residual * 100).toInt()}% nét chữ Nhật không xóa được an toàn (dính viền/hình vẽ) — có thể còn lộ", d.residual)
                else -> Item(++num, Cat.OK, box, d.ids, "", remain)
            }
        }

        // A: bong bóng khép kín có chữ nhưng không vùng AI / quyết định nào chạm tới.
        val aiBoxes = regions.mapNotNull { r -> r.bbox?.let { b ->
            BoxF((b[0] * w).toFloat(), (b[1] * h).toFloat(), ((b[0] + b[2]) * w).toFloat(), ((b[1] + b[3]) * h).toFloat())
        } }
        val usedLabels = decisions.mapNotNull { it.bubble?.label }.toSet()
        val pageGray = bubbles.width.toFloat() * bubbles.height
        for (b in bubbles.bubbles) {
            if (b.label in usedLabels) continue
            val hb = b.holeBounds() ?: continue
            // Tiêu chí bong bóng thoại thật (tránh báo nhầm lòng trắng của hình vẽ):
            if (b.interiorArea < 0.0025f * pageGray) continue                     // đủ lớn
            if (b.interiorArea < 0.55f * b.width * b.height) continue             // tròn/đặc
            if (b.holeRatio !in 0.03f..0.3f) continue                             // có lượng mực vừa phải
            if (minOf(hb.width, hb.height) < 0.25f * minOf(b.width, b.height)) continue
            val cxr = (hb.centerX - b.left) / b.width; val cyr = (hb.centerY - b.top) / b.height
            if (cxr !in 0.2f..0.8f || cyr !in 0.2f..0.8f) continue                // chữ ở giữa bong bóng
            if (!textLikeHoles(b)) continue
            if (!looksLikeSpeechBubble(b, orig, w, s)) continue
            val bo = BoxF(b.box.left / s, b.box.top / s, b.box.right / s, b.box.bottom / s)
            val hbo = BoxF(hb.left / s, hb.top / s, hb.right / s, hb.bottom / s)
            if (aiBoxes.any { it.expand(it.width * 0.2f, it.height * 0.2f).intersects(hbo) }) continue
            if (decisions.any { d -> d.textBox?.intersects(hbo) == true }) continue
            items += Item(++num, Cat.A, bo, emptyList(), "Bong bóng có chữ nhưng không có trong JSON (MISSING_TRANSLATION)", 1f)
        }
        return items
    }

    /**
     * Bong bóng thoại thật: lòng trắng sạch (trung bình ≥ 235 ở phần không phải nét) và có VIỀN tối bao quanh
     * (≥ 60% điểm ngay ngoài lòng là mực) — loại lòng trắng của áo/tranh.
     */
    private fun looksLikeSpeechBubble(b: BubbleDetector.Bubble, orig: IntArray, w: Int, s: Float): Boolean {
        fun g(x: Int, y: Int): Int {
            val ox = (x / s).toInt(); val oy = (y / s).toInt()
            return if (ox < 0 || oy < 0 || ox >= w || oy * w + ox >= orig.size) 255 else orig[oy * w + ox]
        }
        var sum = 0L; var n = 0; var ring = 0; var dark = 0
        for (y in b.top until b.bottom) for (x in b.left until b.right) {
            if (b.isInterior(x, y)) {
                if (!b.isHoleDilated(x, y)) { sum += g(x, y); n++ }
                continue
            }
            // điểm ngoài lòng sát lòng (≤ 2 px)
            var near = false
            for (dy in -2..2) for (dx in -2..2) if (b.isInterior(x + dx, y + dy) && !b.isHole(x + dx, y + dy)) near = true
            if (!near) continue
            ring++
            if (g(x, y) < 150) dark++
        }
        if (n == 0 || sum / n < 235) return false
        return ring > 0 && dark >= 0.6f * ring
    }

    /** Các cụm nét trong bong bóng giống chữ: ≥ 3 cụm, không cụm nào dài quá nửa bong bóng. */
    private fun textLikeHoles(b: BubbleDetector.Bubble): Boolean {
        val bw = b.width; val bh = b.height
        val seen = BooleanArray(bw * bh)
        val q = IntArray(bw * bh)
        var comps = 0
        for (st in 0 until bw * bh) {
            if (seen[st] || !b.isHole(b.left + st % bw, b.top + st / bw)) continue
            var hd = 0; var tl = 0; q[tl++] = st; seen[st] = true
            var x0 = bw; var y0 = bh; var x1 = -1; var y1 = -1; var n = 0
            while (hd < tl) {
                val p = q[hd++]; val x = p % bw; val y = p / bw; n++
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (nb in intArrayOf(if (x > 0) p - 1 else -1, if (x < bw - 1) p + 1 else -1, if (y > 0) p - bw else -1, if (y < bh - 1) p + bw else -1)) {
                    if (nb >= 0 && !seen[nb] && b.isHole(b.left + nb % bw, b.top + nb / bw)) { seen[nb] = true; q[tl++] = nb }
                }
            }
            if (n < 4) continue
            if (x1 - x0 + 1 > bw / 2 || y1 - y0 + 1 > bh / 2) return false
            comps++
        }
        return comps >= 3
    }

    /** Tỉ lệ điểm nét chữ Nhật (lỗ của mặt nạ) vẫn còn trên ảnh ra, KHÔNG tính phần nằm trong vùng đặt chữ Việt. */
    private fun remaining(w: Int, h: Int, orig: IntArray, out: IntArray, d: AiRegionMapper.Decision, s: Float): Float {
        val b = d.bubble ?: return 0f
        val tb = d.textBox ?: return 0f
        val lay = d.layoutBox
        var total = 0
        var still = 0
        for (y in max(0, tb.top.toInt()) until minOf(h, tb.bottom.toInt() + 1))
            for (x in max(0, tb.left.toInt()) until minOf(w, tb.right.toInt() + 1)) {
                if (lay != null && lay.contains(x + 0.5f, y + 0.5f)) continue
                val p = y * w + x
                if (orig[p] >= 150) continue
                if (!b.isHole((x * s).toInt(), (y * s).toInt())) continue
                total++
                if (out[p] < 150 && abs(out[p] - orig[p]) < 40) still++
            }
        return if (total < 20) 0f else still.toFloat() / total
    }

    /**
     * LƯỚI AN TOÀN: vùng đã vẽ có xóa mất nét KHÔNG phải chữ không? Gán nhãn các cụm mực gốc trong khung vùng
     * (nới 1 cỡ chữ); cụm bị xóa > 20% mà DÀI-MẢNH (> 3 cỡ chữ, độ đặc < 0.35) hoặc QUÁ LỚN (> 6 × cỡ chữ²)
     * là nét vẽ/viền bị xóa nhầm.
     * @return mô tả, hoặc null nếu không phát hiện.
     */
    fun damage(w: Int, h: Int, orig: IntArray, out: IntArray, d: AiRegionMapper.Decision): String? {
        val tb = d.textBox ?: return null
        val lh = d.lineHeight.coerceAtLeast(8f)
        val bx = tb.expand(4f * lh) // đủ rộng để thấy hết chiều dài nét viền/nét vẽ đi ngang qua vùng chữ
        val l = max(0, bx.left.toInt()); val t = max(0, bx.top.toInt())
        val r = minOf(w, bx.right.toInt() + 1); val b = minOf(h, bx.bottom.toInt() + 1)
        val bw = r - l; val bh = b - t
        if (bw <= 0 || bh <= 0) return null
        val lab = IntArray(bw * bh)
        val q = IntArray(bw * bh)
        var id = 0
        for (st in 0 until bw * bh) {
            if (lab[st] != 0 || orig[(t + st / bw) * w + l + st % bw] >= 110) continue
            id++
            var hd = 0; var tl = 0; q[tl++] = st; lab[st] = id
            var x0 = bw; var y0 = bh; var x1 = -1; var y1 = -1; var n = 0; var erased = 0
            while (hd < tl) {
                val p = q[hd++]; val x = p % bw; val y = p / bw; n++
                val gp = (t + y) * w + l + x
                if (out[gp] > 200) erased++
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (nb in intArrayOf(if (x > 0) p - 1 else -1, if (x < bw - 1) p + 1 else -1, if (y > 0) p - bw else -1, if (y < bh - 1) p + bw else -1)) {
                    if (nb >= 0 && lab[nb] == 0 && orig[(t + nb / bw) * w + l + nb % bw] < 110) { lab[nb] = id; q[tl++] = nb }
                }
            }
            if (erased <= 0.2f * n) continue
            val cw = x1 - x0 + 1; val ch = y1 - y0 + 1
            val fill = n.toFloat() / (cw * ch)
            if (max(cw, ch) > 3f * lh && fill < 0.35f) return "xóa mất một nét dài/cong ${cw}×$ch px (viền bong bóng/nét vẽ?)"
            // Nét thẳng dài hơn 4 cỡ chữ (viền khung, đường kẻ) — gạch "ー" của chữ không dài đến vậy.
            if (max(cw, ch) > 4f * lh) return "xóa mất một nét dài ${cw}×$ch px (viền khung/đường kẻ?)"
            if (n > 6f * lh * lh) return "xóa mất một mảng mực lớn $n px (hình vẽ?)"
        }
        return null
    }

    /** Tóm tắt số vùng theo loại. */
    fun counts(items: List<Item>): Map<Cat, Int> = Cat.values().associateWith { c -> items.count { it.cat == c } }
}
