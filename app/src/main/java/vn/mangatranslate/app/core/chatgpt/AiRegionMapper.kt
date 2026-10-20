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

    /** BUBBLE: bong bóng khép kín. OPEN: chữ ở vùng hở (bong bóng hở, nền trắng) — xóa đúng nét chữ. SKIP: giữ nguyên ảnh. */
    enum class Action { BUBBLE, OPEN, SKIP }

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
        /** Không còn dùng (giữ để tương thích). */
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
                val (b0, why) = match
                val prev = byBubble[b0.label]
                if (prev != null) {
                    // Hai câu AI cùng một bong bóng → gộp, vẽ một lần.
                    val d = out[prev]
                    out[prev] = d.copy(ids = d.ids + r.id, ja = d.ja + " " + r.ja, vi = d.vi + " " + r.vi,
                        reason = d.reason + "; gộp vùng #${r.id} (cùng bong bóng)")
                    continue
                }
                val b = withHalo(b0, gray)
                val holes = b.holeBounds()
                val textBox = (holes ?: b.box).let { BoxF(it.left / s, it.top / s, it.right / s, it.bottom / s) }
                val inner = BoxF(b.inner.left / s, b.inner.top / s, b.inner.right / s, b.inner.bottom / s)
                val chars = r.ja.count { !it.isWhitespace() }.coerceAtLeast(1)
                val lineHeight = sqrt(b.holeArea / (s * s) / chars * 4f).coerceIn(8f, 80f)
                byBubble[b0.label] = out.size
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
        // Mảnh mực nhỏ nằm TRỌN trong khối chữ (furigana, dấu câu, nét rời) cũng là chữ.
        run {
            val block = cluster
            for (id in 1..comps) {
                if (!inkC[id - 1] || id in chosen || edge[id - 1]) continue
                val bx = cbox(id)
                val md = max(bx.width, bx.height)
                if (md <= 1.5f * charSize && bx.left >= block.left && bx.right <= block.right && bx.top >= block.top && bx.bottom <= block.bottom) chosen += id
            }
        }
        // Nét mực LỚN không phải chữ (viền bong bóng, nét vẽ: dài hơn 1.5 cỡ chữ hoặc chạm mép cửa sổ).
        fun bigForeign(id: Int): Boolean {
            if (!inkC[id - 1] || id in chosen) return false
            if (edge[id - 1]) return true
            val bx = cbox(id)
            return max(bx.width, bx.height) > 1.5f * charSize
        }
        // Cụm chữ sát (≤ 2 px) một nét lớn như vậy → không xóa (có thể là mảnh viền).
        val nearForeign = HashSet<Int>()
        for (id in chosen) {
            loop@ for (y in max(0, cy0[id - 1] - 2)..min(sh - 1, cy1[id - 1] + 2))
                for (x in max(0, cx0[id - 1] - 2)..min(sw - 1, cx1[id - 1] + 2)) {
                    val q = y * sw + x
                    if (!isInk[q] || lab[q] == id || !bigForeign(lab[q])) continue
                    // điểm q là mực lạ; có điểm nào của cụm id cách q ≤ 2 px?
                    var hit = false
                    for (yy in max(0, y - 2)..min(sh - 1, y + 2)) for (xx in max(0, x - 2)..min(sw - 1, x + 2))
                        if (lab[yy * sw + xx] == id) hit = true
                    if (hit) { nearForeign += id; break@loop }
                }
        }
        chosen.removeAll(nearForeign)
        if (chosen.isEmpty()) return skip(r, ai, "Nét chữ dính sát viền/hình vẽ — giữ nguyên ảnh")
        cluster = BoxF.unionOf(chosen.map { cbox(it) })

        val keptInk = chosen.sumOf { cnt[it - 1] }
        // Phải xóa TRỌN chữ trong vùng AI; sót nhiều → chữ Nhật lộ ra cạnh chữ Việt → bỏ qua.
        val inAiInk = eligible.filter { aiWin.contains(cbox(it).centerX, cbox(it).centerY) }.sumOf { cnt[it - 1] }
        val missed = eligible.filter { it !in chosen && aiWin.contains(cbox(it).centerX, cbox(it).centerY) }.sumOf { cnt[it - 1] }
        if (missed > 0.15f * max(1, inAiInk)) return skip(r, ai, "Không tách trọn chữ trong vùng (sót ${pct(missed / max(1f, inAiInk.toFloat()))}%, có nét dính viền/hình vẽ) — giữ nguyên ảnh")
        val dropped = (1..comps).count { inkC[it - 1] } - chosen.size
        val inkBoxW = cluster
        val inkRatio = keptInk / max(1f, inkBoxW.area)
        if (inkRatio > 0.45f) return skip(r, ai, "Vùng dày mực (${pct(inkRatio)}%) — có thể là hình vẽ, giữ nguyên ảnh")

        // Mặt nạ trên vùng mở rộng E (cửa sổ + khoảng bằng kích thước vùng AI mỗi phía, để chữ Việt có chỗ):
        // - "lòng" = trong cửa sổ: nền chính + lòng nhỏ + nét chữ đã chọn; ngoài cửa sổ: chỉ điểm trắng tinh (≥ 230).
        // - "nét cần xóa" = nét chữ đã chọn + quầng xám khử răng cưa quanh nét (≤ 3 px, thuộc lòng, < 245) → không còn bóng.
        // Bộ vẽ chỉ tô điểm thuộc lòng → viền bong bóng và nét vẽ không bao giờ bị đụng.
        val extra = max(sw, sh)
        val el = max(0, l - extra); val et = max(0, t - extra); val er = min(gw, rr + extra); val eb = min(gh, bb + extra)
        val ew = er - el; val eh = eb - et
        val interior = BooleanArray(ew * eh)
        val holes = BooleanArray(ew * eh)
        for (y in et until eb) for (x in el until er) {
            val e = (y - et) * ew + (x - el)
            if (x in l until rr && y in t until bb) {
                val p = (y - t) * sw + (x - l)
                val id = lab[p]
                val text = isInk[p] && id in chosen
                val insideBlock = !isInk[p] && inkBoxW.contains((x - l).toFloat() + 0.5f, (y - t).toFloat() + 0.5f)
                if (text || (!isInk[p] && (neutralBg(id) || insideBlock))) interior[e] = true
                if (text) holes[e] = true
            } else if (gray[x, y] >= 230) interior[e] = true
        }
        val halo = haloHoles(holes, interior, ew, eh) { x, y -> gray[el + x, et + y] }
        var interiorArea = 0
        var holeArea = 0
        for (i in interior.indices) { if (interior[i]) interiorArea++; if (halo[i]) holeArea++ }
        fun allInterior(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
            for (y in max(0, y0) until min(eh, y1)) for (x in max(0, x0) until min(ew, x1)) if (!interior[y * ew + x]) return false
            return true
        }
        // Vùng đặt chữ: từ một điểm "lòng" gần tâm khung chữ, nới đều 4 cạnh khi dải mới hoàn toàn thuộc lòng.
        val cxw = (inkBoxW.centerX.toInt() + l - el).coerceIn(0, ew - 1)
        val cyw = (inkBoxW.centerY.toInt() + t - et).coerceIn(0, eh - 1)
        var start = -1
        run search@{
            for (rad in 0..(charSize * 2).toInt()) {
                for (y in max(0, cyw - rad)..min(eh - 1, cyw + rad)) for (x in max(0, cxw - rad)..min(ew - 1, cxw + rad)) {
                    if (interior[y * ew + x]) { start = y * ew + x; return@search }
                }
            }
        }
        if (start < 0) return skip(r, ai, "Không tìm được khoảng trống an toàn để đặt chữ — giữ nguyên ảnh")
        // Hai cách nới: đều 4 cạnh, hoặc ưu tiên chiều ngang trước (bong bóng xiên/hẹp) → chọn vùng hợp chữ Việt ngang hơn.
        fun growFrom(horizontalFirst: Boolean): BoxF {
            var lx = start % ew; var ty = start / ew; var rx = lx + 1; var by = ty + 1
            if (horizontalFirst) {
                var g = true
                while (g) {
                    g = false
                    if (lx > 0 && allInterior(lx - 1, ty, lx, by)) { lx--; g = true }
                    if (rx < ew && allInterior(rx, ty, rx + 1, by)) { rx++; g = true }
                }
                // Giữ một dải đủ cao cho 2 dòng chữ trước khi nới ngang tiếp.
            }
            var grew = true
            while (grew) {
                grew = false
                if (lx > 0 && allInterior(lx - 1, ty, lx, by)) { lx--; grew = true }
                if (rx < ew && allInterior(rx, ty, rx + 1, by)) { rx++; grew = true }
                if (ty > 0 && allInterior(lx, ty - 1, rx, ty)) { ty--; grew = true }
                if (by < eh && allInterior(lx, by, rx, by + 1)) { by++; grew = true }
            }
            return BoxF(lx.toFloat(), ty.toFloat(), rx.toFloat(), by.toFloat())
        }
        // Cũng thử bắt đầu từ các hàng khác của khối chữ (bong bóng xiên rộng nhất ở một độ cao nào đó).
        val candidates = ArrayList<BoxF>()
        candidates += growFrom(false)
        candidates += growFrom(true)
        val startSaved = start
        for (frac in floatArrayOf(0.25f, 0.75f)) {
            val yy = (inkBoxW.top + inkBoxW.height * frac).toInt() + t - et
            val xx = cxw
            if (yy in 0 until eh && interior[yy * ew + xx]) { start = yy * ew + xx; candidates += growFrom(true); candidates += growFrom(false) }
        }
        start = startSaved
        // Điểm: diện tích dùng được cho chữ ngang = rộng × min(cao, rộng).
        val best = candidates.maxByOrNull { it.width * min(it.height, it.width) }!!
        val lx = best.left.toInt(); val ty = best.top.toInt(); val rx = best.right.toInt(); val by = best.bottom.toInt()
        // Chừa 1 px với viền.
        val layoutE = BoxF(lx.toFloat(), ty.toFloat(), rx.toFloat(), by.toFloat()).let { if (it.width > 4 && it.height > 4) it.expand(-1f) else it }
        val chars = r.ja.count { !it.isWhitespace() }.coerceAtLeast(1)
        val lineHeight = sqrt(inkBoxW.area / (s * s) / chars).coerceIn(8f, 80f)
        fun origW(b: BoxF) = BoxF((b.left + l) / s, (b.top + t) / s, (b.right + l) / s, (b.bottom + t) / s).clampTo(w.toFloat(), h.toFloat())
        fun origE(b: BoxF) = BoxF((b.left + el) / s, (b.top + et) / s, (b.right + el) / s, (b.bottom + et) / s).clampTo(w.toFloat(), h.toFloat())
        val shape = BubbleDetector.Bubble(
            label = OPEN_LABEL_BASE - r.id.hashCode().and(0xFFFF), left = el, top = et, right = er, bottom = eb,
            interior = interior, holes = halo, interiorArea = interiorArea, holeArea = holeArea,
            inner = BoxF(layoutE.left + el, layoutE.top + et, layoutE.right + el, layoutE.bottom + et)
        )
        return Decision(listOf(r.id), r.type, r.ja, r.vi, Action.OPEN,
            "Không có bong bóng khép kín; tách ${chosen.size} cụm nét chữ (bỏ $dropped cụm viền/hình vẽ" +
                (if (nearForeign.isNotEmpty()) ", ${nearForeign.size} cụm sát viền" else "") +
                ") → chỉ xóa đúng các nét chữ này, không phủ khung",
            ai, origW(inkBoxW), origE(layoutE), lineHeight, shape)
    }

    private fun skip(r: MangaJson.Region, ai: BoxF, why: String) =
        Decision(listOf(r.id), r.type, r.ja, r.vi, Action.SKIP, why, ai, null, null, 0f, null)

    private fun pct(f: Float) = (f * 100).toInt()

    /**
     * Thêm vào "nét cần xóa" quầng xám khử răng cưa quanh nét chữ: điểm thuộc lòng, cách nét ≤ 3 px, độ sáng < 245.
     * Nếu không, sau khi xóa lõi nét vẫn còn "bóng" xám của chữ Nhật.
     */
    private fun haloHoles(holes: BooleanArray, interior: BooleanArray, bw: Int, bh: Int, grayAt: (Int, Int) -> Int): BooleanArray {
        val out = holes.copyOf()
        for (y in 0 until bh) for (x in 0 until bw) {
            val i = y * bw + x
            if (holes[i] || !interior[i] || grayAt(x, y) >= 245) continue
            var near = false
            for (dy in -3..3) for (dx in -3..3) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until bw && yy in 0 until bh && holes[yy * bw + xx]) near = true
            }
            if (near) out[i] = true
        }
        return out
    }

    /** Bản sao bong bóng thật với "nét cần xóa" gồm cả quầng xám quanh nét (lòng bong bóng giữ nguyên). */
    private fun withHalo(b: BubbleDetector.Bubble, gray: GrayImage): BubbleDetector.Bubble {
        val bw = b.width; val bh = b.height
        val interior = BooleanArray(bw * bh) { b.isInterior(b.left + it % bw, b.top + it / bw) }
        val holes = BooleanArray(bw * bh) { b.isHole(b.left + it % bw, b.top + it / bw) }
        val halo = haloHoles(holes, interior, bw, bh) { x, y -> gray[b.left + x, b.top + y] }
        return BubbleDetector.Bubble(b.label, b.left, b.top, b.right, b.bottom, interior, halo, b.interiorArea, halo.count { it }, b.inner)
    }

    /** Nhãn âm cho "bong bóng" tổng hợp của vùng hở (không trùng nhãn bong bóng thật). */
    private const val OPEN_LABEL_BASE = -1
}
