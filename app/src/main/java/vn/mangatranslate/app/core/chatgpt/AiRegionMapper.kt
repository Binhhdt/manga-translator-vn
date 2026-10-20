package vn.mangatranslate.app.core.chatgpt

import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.vision.BubbleDetector
import vn.mangatranslate.app.core.vision.GrayImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Ghép vùng chữ do ChatGPT trả về (bbox ƯỚC LƯỢNG, tỉ lệ 0..1) với hình ảnh THẬT của trang. Thuần Kotlin.
 *
 * Nguyên tắc an toàn (ưu tiên giữ nguyên ảnh gốc):
 * - bbox của AI KHÔNG BAO GIỜ được dùng làm mask xóa. Nó chỉ dùng để chọn bong bóng nào chứa câu thoại.
 * - Xóa chữ chỉ trong bong bóng trắng khép kín do [BubbleDetector] tìm thấy, và chỉ xóa các nét mực bị bong bóng
 *   bao kín (chữ Nhật + furigana). Viền bong bóng và hình vẽ không bao giờ bị xóa.
 * - Chữ không nằm trong bong bóng: chỉ phủ nền sáng khi xung quanh là nền trắng/sáng thật (không phải hình vẽ),
 *   vùng phủ bám theo nét mực thật, nhỏ; không thỏa → BỎ QUA (giữ nguyên ảnh).
 * - Chữ hiệu ứng (sfx) và vùng AI đánh dấu "uncertain" → bỏ qua ở bản MVP.
 */
object AiRegionMapper {

    enum class Action { BUBBLE, OVERLAY, SKIP }

    /**
     * Quyết định cho một vùng. Tọa độ theo ẢNH GỐC (px). [bubble] dùng tọa độ ảnh xám (nhân [grayScale]).
     */
    data class Decision(
        val ids: List<String>,
        val type: String,
        val ja: String,
        val vi: String,
        val action: Action,
        val reason: String,
        val aiBox: BoxF,
        val textBox: BoxF?,
        val layoutBox: BoxF?,
        val lineHeight: Float,
        val bubble: BubbleDetector.Bubble?,
        /** Vùng phủ nền sáng chính xác (đã kiểm tra không đè nét vẽ), chỉ cho OVERLAY. */
        val coverBox: BoxF? = null
    )

    /**
     * @param regions vùng từ JSON của AI.
     * @param width/height kích thước ảnh gốc.
     * @param gray ảnh xám dùng tìm bong bóng; [grayScale] = kích thước xám / kích thước gốc.
     */
    fun map(
        regions: List<MangaJson.Region>,
        width: Int,
        height: Int,
        gray: GrayImage,
        grayScale: Float,
        bubbles: BubbleDetector.Result
    ): List<Decision> {
        val s = grayScale
        val out = ArrayList<Decision>()
        val byBubble = LinkedHashMap<Int, Int>() // nhãn bong bóng → chỉ số trong out
        for (r in regions) {
            val bb = r.bbox
            if (bb == null || bb.size != 4) {
                out += skip(r, BoxF(0f, 0f, 0f, 0f), "Không có bbox hợp lệ")
                continue
            }
            val ai = BoxF(
                (bb[0] * width).toFloat(), (bb[1] * height).toFloat(),
                ((bb[0] + bb[2]) * width).toFloat(), ((bb[1] + bb[3]) * height).toFloat()
            ).clampTo(width.toFloat(), height.toFloat())
            if (ai.width < 2f || ai.height < 2f) { out += skip(r, ai, "bbox quá nhỏ"); continue }
            if (r.vi.isBlank()) { out += skip(r, ai, "Không có bản dịch"); continue }
            if (r.uncertain) { out += skip(r, ai, "AI đánh dấu cần kiểm tra — giữ nguyên ảnh"); continue }
            if (r.type.equals("sfx", ignoreCase = true)) { out += skip(r, ai, "Chữ hiệu ứng (sfx) — giữ nguyên ảnh ở bản MVP"); continue }

            // 1) Bong bóng khép kín khớp với vùng AI?
            val aiGray = BoxF(ai.left * s, ai.top * s, ai.right * s, ai.bottom * s)
            val match = bestBubble(aiGray, bubbles)
            if (match != null) {
                val (b, why) = match
                val prev = byBubble[b.label]
                if (prev != null) {
                    // Hai câu AI cùng một bong bóng → gộp, vẽ một lần.
                    val d = out[prev]
                    out[prev] = d.copy(ids = d.ids + r.id, ja = d.ja + " " + r.ja, vi = d.vi + " " + r.vi,
                        reason = d.reason + "; gộp vùng #${r.id} (cùng bong bóng)")
                    continue
                }
                val holes = b.holeBounds()
                val textBox = (holes ?: b.box).let { BoxF(it.left / s, it.top / s, it.right / s, it.bottom / s) }
                val inner = BoxF(b.inner.left / s, b.inner.top / s, b.inner.right / s, b.inner.bottom / s)
                val chars = r.ja.count { !it.isWhitespace() }.coerceAtLeast(1)
                val lineHeight = sqrt(b.holeArea / (s * s) / chars * 4f).coerceIn(8f, 80f)
                byBubble[b.label] = out.size
                out += Decision(listOf(r.id), r.type, r.ja, r.vi, Action.BUBBLE, why, ai, textBox, inner, lineHeight, b)
                continue
            }

            // 2) Không có bong bóng: chỉ phủ khi nền quanh nét chữ thật sự sáng.
            out += overlayOrSkip(r, ai, gray, s, width, height)
        }
        return out
    }

    /**
     * Bong bóng có phần lớn nét chữ nằm trong vùng AI, và không lớn bất thường so với vùng AI.
     * Trả về (bong bóng, lý do khớp) hoặc null.
     */
    private fun bestBubble(aiGray: BoxF, res: BubbleDetector.Result): Pair<BubbleDetector.Bubble, String>? {
        val search = aiGray.expand(aiGray.width * 0.15f, aiGray.height * 0.15f)
        var best: BubbleDetector.Bubble? = null
        var bestScore = 0f
        var bestInfo = ""
        for (b in res.bubbles) {
            if (!b.box.intersects(search)) continue
            val hb = b.holeBounds() ?: continue
            val inkInside = hb.intersectionArea(search) / max(1f, hb.area)
            if (inkInside < 0.5f) continue
            val sizeRatio = b.box.area / max(1f, aiGray.area)
            if (sizeRatio > 6f) continue
            val aiCovered = aiGray.intersectionArea(b.box) / max(1f, aiGray.area)
            if (aiCovered < 0.3f) continue
            val score = inkInside * min(1f, aiCovered * 1.5f)
            if (score > bestScore) {
                bestScore = score; best = b
                bestInfo = "Khớp bong bóng khép kín: ${pct(inkInside)}% nét chữ nằm trong vùng AI, " +
                    "vùng AI nằm ${pct(aiCovered)}% trong bong bóng"
            }
        }
        return best?.let { it to bestInfo }
    }

    /**
     * Chữ không nằm trong bong bóng khép kín (bong bóng hở, lời dẫn trên nền trắng…). Trong vùng AI nới 10%:
     * 1. Tách "nền" (sáng) và "mực" (tối) thành các cụm liền nhau. Cụm nền "chính" = cụm nền chiếm nhiều nhất ở
     *    lõi vùng AI (lòng bong bóng / nền trắng quanh chữ).
     * 2. Cụm mực là CHỮ khi mọi điểm nền kề nó thuộc nền chính (hoặc lòng nhỏ khép kín của nét chữ như 口):
     *    viền bong bóng giáp cả trong lẫn ngoài, nét vẽ giáp nền khác → bị loại. Sau đó chỉ giữ các cụm chữ
     *    nằm gần nhau quanh lõi (mảnh viền rời xa bị loại).
     * 3. Vùng phủ = khung chữ + lề nhỏ. Trong vùng phủ: mực không phải chữ + nền không thuộc nền chính ≤ 0,5%,
     *    viền ngoài ≥ 70% sáng. Không thỏa → BỎ QUA (giữ nguyên ảnh).
     * 4. Vùng đặt chữ Việt chỉ được nới sang khoảng trắng gần như tuyệt đối (≥ 98.5% sáng).
     */
    private fun overlayOrSkip(r: MangaJson.Region, ai: BoxF, gray: GrayImage, s: Float, w: Int, h: Int): Decision {
        val gw = gray.width
        val gh = gray.height
        val ex = ai.expand(ai.width * 0.1f, ai.height * 0.1f)
        val l = (ex.left * s).toInt().coerceIn(0, gw - 1)
        val t = (ex.top * s).toInt().coerceIn(0, gh - 1)
        val rr = (ex.right * s).toInt().coerceIn(l + 1, gw)
        val bb = (ex.bottom * s).toInt().coerceIn(t + 1, gh)
        val sw = rr - l
        val sh = bb - t
        val n = sw * sh
        val isInk = BooleanArray(n) { gray[l + it % sw, t + it / sw] < GrayImage.INK_THRESHOLD }
        val lab = IntArray(n)
        val queue = IntArray(n)
        // Thống kê cụm: chỉ số = nhãn - 1.
        val cx0 = ArrayList<Int>(); val cy0 = ArrayList<Int>(); val cx1 = ArrayList<Int>(); val cy1 = ArrayList<Int>()
        val cnt = ArrayList<Int>(); val edge = ArrayList<Boolean>(); val inkC = ArrayList<Boolean>()
        for (start in 0 until n) {
            if (lab[start] != 0) continue
            val id = cnt.size + 1
            val ink = isInk[start]
            var head = 0; var tail = 0
            queue[tail++] = start; lab[start] = id
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1; var c = 0; var e = false
            while (head < tail) {
                val p = queue[head++]
                val x = p % sw; val y = p / sw
                c++
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                if (x == 0 || y == 0 || x == sw - 1 || y == sh - 1) e = true
                if (x > 0 && lab[p - 1] == 0 && isInk[p - 1] == ink) { lab[p - 1] = id; queue[tail++] = p - 1 }
                if (x < sw - 1 && lab[p + 1] == 0 && isInk[p + 1] == ink) { lab[p + 1] = id; queue[tail++] = p + 1 }
                if (y > 0 && lab[p - sw] == 0 && isInk[p - sw] == ink) { lab[p - sw] = id; queue[tail++] = p - sw }
                if (y < sh - 1 && lab[p + sw] == 0 && isInk[p + sw] == ink) { lab[p + sw] = id; queue[tail++] = p + sw }
            }
            cx0 += x0; cy0 += y0; cx1 += x1; cy1 += y1; cnt += c; edge += e; inkC += ink
        }
        val comps = cnt.size
        // Lõi vùng AI (60% giữa), theo tọa độ cửa sổ.
        val core = BoxF(ai.left * s - l, ai.top * s - t, ai.right * s - l, ai.bottom * s - t)
            .let { it.expand(-it.width * 0.2f, -it.height * 0.2f) }
        val coreVotes = IntArray(comps + 1)
        for (y in max(0, core.top.toInt()) until min(sh, core.bottom.toInt()))
            for (x in max(0, core.left.toInt()) until min(sw, core.right.toInt())) {
                val id = lab[y * sw + x]
                if (!inkC[id - 1]) coreVotes[id]++
            }
        val seed = (1..comps).maxByOrNull { coreVotes[it] }?.takeIf { coreVotes[it] > 0 }
            ?: return skip(r, ai, "Không thấy nền sáng quanh chữ — giữ nguyên ảnh")
        val smallLimit = max(50, (0.01f * n).toInt())
        fun neutralBg(id: Int) = !inkC[id - 1] && (id == seed || (!edge[id - 1] && cnt[id - 1] <= smallLimit))

        // Cụm mực nào chỉ giáp nền chính (hoặc lòng nhỏ khép kín)?
        val seedAdj = IntArray(comps + 1)
        val badAdj = IntArray(comps + 1)
        for (p in 0 until n) {
            if (!isInk[p]) continue
            val id = lab[p]
            val x = p % sw; val y = p / sw
            for (q in intArrayOf(if (x > 0) p - 1 else -1, if (x < sw - 1) p + 1 else -1, if (y > 0) p - sw else -1, if (y < sh - 1) p + sw else -1)) {
                if (q < 0 || isInk[q]) continue
                val b = lab[q]
                if (b == seed) seedAdj[id]++ else if (!neutralBg(b)) badAdj[id]++
            }
        }
        val eligible = (1..comps).filter { inkC[it - 1] && !edge[it - 1] && cnt[it - 1] >= 3 && seedAdj[it] > 0 && badAdj[it] <= 0.03f * (seedAdj[it] + badAdj[it]) }
        if (eligible.isEmpty()) return skip(r, ai, "Không tách được nét chữ khỏi hình vẽ trong vùng AI — giữ nguyên ảnh")

        // Gom cụm chữ quanh lõi.
        fun cbox(id: Int) = BoxF(cx0[id - 1].toFloat(), cy0[id - 1].toFloat(), (cx1[id - 1] + 1).toFloat(), (cy1[id - 1] + 1).toFloat())
        val dims = eligible.map { max(cx1[it - 1] - cx0[it - 1] + 1, cy1[it - 1] - cy0[it - 1] + 1) }.sorted()
        val charSize = dims[(dims.size * 3) / 4].toFloat().coerceAtLeast(4f)
        val aiWin = BoxF(ai.left * s - l, ai.top * s - t, ai.right * s - l, ai.bottom * s - t)
        // Hạt giống: cụm chữ ở lõi vùng AI (mép vùng AI hay dính mảnh viền bong bóng), rồi nới tối đa 0.75 cỡ chữ.
        val chosen = eligible.filter { core.contains(cbox(it).centerX, cbox(it).centerY) }.toHashSet()
        if (chosen.isEmpty()) return skip(r, ai, "Nét chữ không nằm ở giữa vùng AI — giữ nguyên ảnh")
        var cluster = BoxF.unionOf(chosen.map { cbox(it) })
        var changed = true
        while (changed) {
            changed = false
            val reach = cluster.expand(charSize * 0.75f)
            for (id in eligible) if (id !in chosen && cbox(id).intersects(reach)) {
                chosen += id; cluster = cluster.union(cbox(id)); changed = true
            }
        }
        val keptInk = chosen.sumOf { cnt[it - 1] }
        // Phải phủ TRỌN chữ trong vùng AI; sót nhiều → chữ Nhật lộ ra cạnh chữ Việt → bỏ qua.
        val inAiInk = eligible.filter { aiWin.contains(cbox(it).centerX, cbox(it).centerY) }.sumOf { cnt[it - 1] }
        val missed = eligible.filter { it !in chosen && aiWin.contains(cbox(it).centerX, cbox(it).centerY) }.sumOf { cnt[it - 1] }
        if (missed > 0.15f * max(1, inAiInk)) return skip(r, ai, "Không gom trọn chữ trong vùng (sót ${pct(missed / max(1f, inAiInk.toFloat()))}%) — giữ nguyên ảnh")
        val dropped = (1..comps).count { inkC[it - 1] } - chosen.size
        val inkBox = BoxF(cluster.left + l, cluster.top + t, cluster.right + l, cluster.bottom + t)
        val inkRatio = keptInk / max(1f, inkBox.area)
        if (inkRatio > 0.45f) return skip(r, ai, "Vùng dày mực (${pct(inkRatio)}%) — có thể là hình vẽ, giữ nguyên ảnh")

        val chars = r.ja.count { !it.isWhitespace() }.coerceAtLeast(1)
        val lineHeight = sqrt(inkBox.area / (s * s) / chars).coerceIn(8f, 80f)
        val margin = (lineHeight * 0.25f).coerceIn(3f, 14f)
        val cover = inkBox.expand(margin * s + 1f)
        val cl = cover.left.toInt(); val ct = cover.top.toInt(); val cr = cover.right.toInt(); val cb = cover.bottom.toInt()
        var foreign = 0; var area = 0
        for (y in ct until cb) for (x in cl until cr) {
            if (x !in 0 until gw || y !in 0 until gh) continue
            area++
            val inWin = x in l until rr && y in t until bb
            if (!inWin) { if (gray[x, y] < 200) foreign++; continue }
            val id = lab[(y - t) * sw + (x - l)]
            val ok = if (inkC[id - 1]) id in chosen else neutralBg(id)
            if (!ok) foreign++
        }
        val foreignRatio = foreign.toFloat() / max(1, area)
        if (foreignRatio > 0.005f) return skip(r, ai, "Vùng phủ sẽ đè lên nét vẽ/viền (${pct(foreignRatio)}% điểm lạ) — giữ nguyên ảnh")
        var light = 0; var total = 0
        fun sample(x: Int, y: Int) {
            if (x !in 0 until gw || y !in 0 until gh) return
            total++
            if (gray[x, y] >= 200) light++
        }
        for (x in cl - 1..cr) { sample(x, ct - 1); sample(x, cb) }
        for (y in ct - 1..cb) { sample(cl - 1, y); sample(cr, y) }
        val lightRatio = light.toFloat() / max(1, total)
        if (lightRatio < 0.7f) return skip(r, ai, "Nền quanh chữ không đủ sáng (${pct(lightRatio)}%) — giữ nguyên ảnh")
        if (cover.area / (gw.toFloat() * gh) > 0.03f) return skip(r, ai, "Vùng phủ quá lớn — giữ nguyên ảnh")

        // Vùng đặt chữ: nới sang khoảng trắng tuyệt đối (để chữ Việt ngang có chỗ khi chữ Nhật là cột dọc hẹp).
        val grow = max(cover.width, cover.height)
        val layout = vn.mangatranslate.app.core.vision.WhiteSpaceExpander.expand(gray, cover, 0f, grow, 200, 0.985f)
        fun orig(b: BoxF) = BoxF(b.left / s, b.top / s, b.right / s, b.bottom / s).clampTo(w.toFloat(), h.toFloat())
        return Decision(listOf(r.id), r.type, r.ja, r.vi, Action.OVERLAY,
            "Không có bong bóng khép kín; giữ ${chosen.size} cụm nét chữ, bỏ $dropped cụm (viền/hình vẽ); " +
                "điểm lạ ${pct(foreignRatio)}%, viền sáng ${pct(lightRatio)}% → phủ nền sáng bám theo nét chữ",
            ai, orig(inkBox), orig(layout), lineHeight, null, orig(cover))
    }

    private fun skip(r: MangaJson.Region, ai: BoxF, why: String) =
        Decision(listOf(r.id), r.type, r.ja, r.vi, Action.SKIP, why, ai, null, null, 0f, null)

    private fun pct(f: Float) = (f * 100).toInt()
}
