package vn.mangatranslate.app.core.chatgpt

import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.vision.BubbleDetector
import vn.mangatranslate.app.core.vision.GrayImage
import kotlin.math.abs
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
        val coverBox: BoxF? = null,
        /** Tỉ lệ nét chữ Nhật trong vùng KHÔNG xóa được an toàn (sẽ còn lộ sau khi chèn). */
        val residual: Float = 0f
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

        // A detected white component is not necessarily one speech bubble. In manga,
        // adjacent bubbles/panels may be connected, so two unrelated ChatGPT boxes
        // can receive the SAME detector label. Never merge those translations: the
        // combined sentence may not fit (007: 7+8, 15+16, 18+19).
        // If ambiguous, try individually masking their actual text as OPEN. This is
        // deliberately conservative: unresolved words stay Japanese, art stays safe.
        val bubbleBoxes = HashMap<Int, MutableList<BoxF>>()
        val ambiguousBubbles = HashSet<Int>()
        for (candidate in regions) {
            val bb = candidate.bbox ?: continue
            if (bb.size != 4 || candidate.vi.isBlank() || candidate.uncertain ||
                candidate.type.equals("sfx", true)) continue
            val rect = BoxF((bb[0] * width).toFloat(), (bb[1] * height).toFloat(),
                ((bb[0] + bb[2]) * width).toFloat(), ((bb[1] + bb[3]) * height).toFloat())
                .clampTo(width.toFloat(), height.toFloat())
            if (rect.width < 2f || rect.height < 2f) continue
            val scaledBox = BoxF(rect.left * s, rect.top * s, rect.right * s, rect.bottom * s)
            val label = bestBubble(scaledBox, bubbles)?.first?.label ?: continue
            val seen = bubbleBoxes.getOrPut(label) { ArrayList() }
            if (seen.any { !canMergeTextBoxes(it, rect) }) ambiguousBubbles += label
            seen += rect
        }

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
            // Preserve the pre-existing OPEN rendering whenever it works.
            // A fallback bubble match must never replace a region that previously
            // rendered successfully; that caused visible Japanese-text regressions.
            val strictMatch = bestBubble(aiGray, bubbles)
            val isAmbiguous = strictMatch?.first?.label in ambiguousBubbles
            val openCandidate = if (strictMatch == null || isAmbiguous)
                overlayOrSkip(r, ai, gray, s, width, height) else null
            val match = if (isAmbiguous) null else strictMatch ?: if (openCandidate?.action == Action.SKIP) {
                bestBubbleFallback(aiGray, bubbles)?.takeUnless { it.first.label in ambiguousBubbles }
            } else null
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
                // Lòng trắng có "lỗ" lớn không giống chữ (tóc, tay, mảng tranh bị nền trắng bao quanh) thì KHÔNG phải
                // bong bóng thoại → chuyển sang cách xử lý vùng hở (chỉ xóa nét chữ được chọn).
                val tb0 = textOnly(b0, gray)
                if (tb0 == null) {
                    out += overlayOrSkip(r, ai, gray, s, width, height).let {
                        if (it.action == Action.SKIP) it else it.copy(reason = "Lòng trắng chứa mảng tranh, không coi là bong bóng; " + it.reason)
                    }
                    continue
                }
                val b = withHalo(tb0, gray)
                val holes = b.holeBounds()
                val textBox = (holes ?: b.box).let { BoxF(it.left / s, it.top / s, it.right / s, it.bottom / s) }
                // Vùng đặt chữ: lớn hơn giữa "hình chữ nhật trong" của BubbleDetector và hình chữ nhật nới từng điểm
                // trong lòng bong bóng (cách viền ≥ 2 px) — bong bóng hẹp/xiên có thêm chỗ cho chữ Việt.
                val grown = growInBubble(b, holes)
                var innerG = if (grown != null && score(grown) > score(b.inner) * 1.1f) grown else b.inner
                // Ứng viên: khối chữ Nhật (sẽ bị xóa) nếu gần như toàn bộ nằm trong lòng bong bóng.
                if (holes != null) {
                    var tot = 0; var inside = 0
                    for (y in holes.top.toInt() until holes.bottom.toInt()) for (x in holes.left.toInt() until holes.right.toInt()) {
                        tot++; if (b.isInterior(x, y)) inside++
                    }
                    if (tot > 0 && inside >= 0.97f * tot && score(holes) > score(innerG)) innerG = holes
                }
                val inner = BoxF(innerG.left / s, innerG.top / s, innerG.right / s, innerG.bottom / s)
                val chars = r.ja.count { !it.isWhitespace() }.coerceAtLeast(1)
                val lineHeight = sqrt(b.holeArea / (s * s) / chars * 4f).coerceIn(8f, 80f)
                byBubble[b0.label] = out.size
                // Nét trong bong bóng bị loại khỏi mặt nạ xóa (không giống chữ) nhưng nằm trong khối chữ → sẽ còn lộ.
                var all0 = 0; var kept0 = 0
                holes?.let { hb -> for (y in hb.top.toInt() until hb.bottom.toInt()) for (x in hb.left.toInt() until hb.right.toInt()) {
                    if (b0.isHole(x, y)) { all0++; if (tb0.isHole(x, y)) kept0++ } } }
                val residual = if (all0 == 0) 0f else (all0 - kept0).toFloat() / all0
                out += Decision(listOf(r.id), r.type, r.ja, r.vi, Action.BUBBLE, why, ai, textBox, inner, lineHeight, b, residual = residual)
                continue
            }

            // 2) Không có bong bóng: chỉ phủ khi nền quanh nét chữ thật sự sáng.
            out += openCandidate ?: overlayOrSkip(r, ai, gray, s, width, height)
        }
        return out
    }

    /** Strong overlap only: do not combine independent speech balloons sharing a white component. */
    internal fun canMergeTextBoxes(a: BoxF, b: BoxF): Boolean =
        a.intersectionArea(b) >= 0.30f * min(a.area, b.area) && min(a.area, b.area) > 0f

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
            // Avoid choosing a large adjacent balloon whose edge merely overlaps
            // the AI box. The center must be inside the detected bubble.
            if (!b.isInterior(aiGray.centerX.toInt(), aiGray.centerY.toInt())) continue
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
     * Conservative fallback for tiny AI boxes only, invoked after the existing OPEN
     * renderer has declined the region. This avoids changing previously successful
     * render decisions when experimenting with bubble recovery.
     */
    private fun bestBubbleFallback(aiGray: BoxF, res: BubbleDetector.Result): Pair<BubbleDetector.Bubble, String>? {
        // AI often returns the bounding box of a short Japanese word/column rather than
        // the whole speech bubble. The strict 6x area ratio rejects these legitimate
        // cases. Only use this fallback when the AI box CENTER really belongs to an
        // already-detected closed bubble and overlaps its actual text strokes. Never
        // use the AI box itself to erase pixels.
        val fallback = ArrayList<Pair<Float, BubbleDetector.Bubble>>()
        for (b in res.bubbles) {
            if (!b.isInterior(aiGray.centerX.toInt(), aiGray.centerY.toInt())) continue
            if (b.box.area > max(1f, aiGray.area) * 72f) continue
            val hb = b.holeBounds() ?: continue
            val inside = aiGray.intersectionArea(b.box) / max(1f, aiGray.area)
            if (inside < 0.7f) continue
            val inkOverlap = aiGray.intersectionArea(hb) / max(1f, min(aiGray.area, hb.area))
            if (inkOverlap < 0.25f) continue
            val cx = abs(aiGray.centerX - hb.centerX) / max(1f, b.width.toFloat())
            val cy = abs(aiGray.centerY - hb.centerY) / max(1f, b.height.toFloat())
            val proximity = (1f - (cx + cy) * 0.5f).coerceIn(0f, 1f)
            fallback += (inkOverlap * inside * proximity) to b
        }
        val sorted = fallback.sortedByDescending { it.first }
        if (sorted.isEmpty()) return null
        // Ambiguous overlapping candidates are NOT a reason to risk erasing artwork.
        if (sorted.size > 1 && sorted[0].first < sorted[1].first * 1.3f) return null
        val chosen = sorted.first().second
        return chosen to "Khớp dự phòng an toàn: tâm vùng AI nằm trong bong bóng, " +
            "khung AI giao nét chữ thật; không dùng bbox AI làm mặt nạ xóa"
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
        // Nền chính phải thật sự TRẮNG (không phải lưới chấm/tram): trung bình độ sáng ≥ 215.
        run {
            var sum = 0L; var c = 0
            for (p in 0 until n) if (lab[p] == seed) { sum += gray[l + p % sw, t + p / sw]; c++ }
            if (c == 0 || sum / c < 215) return skip(r, ai, "Nền quanh chữ không phải nền trắng (lưới chấm/tram) — giữ nguyên ảnh")
        }
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
        val adjOk = (1..comps).filter { inkC[it - 1] && !edge[it - 1] && cnt[it - 1] >= 3 && seedAdj[it] > 0 && badAdj[it] <= 0.03f * (seedAdj[it] + badAdj[it]) }
        val sizes0 = adjOk.filter { cnt[it - 1] >= 8 }.ifEmpty { adjOk }
            .map { max(cx1[it - 1] - cx0[it - 1] + 1, cy1[it - 1] - cy0[it - 1] + 1) }.sorted()
        // Cỡ chữ ước lượng = phân vị 75% (kanji tách thành nhiều mảnh nhỏ nên trung vị quá nhỏ; hiragana lớn như の là một nét).
        val med = if (sizes0.isEmpty()) 0 else sizes0[(sizes0.size * 3) / 4]
        // Lưới chấm (screentone): nhiều cụm tí hon đứng dày đặc. Cụm tí hon có ≥ 4 cụm tí hon khác trong bán kính 6 px
        // là chấm tram, KHÔNG phải chữ (dấu chấm câu/dakuten thì đứng lẻ).
        val tiny = (1..comps).filter { inkC[it - 1] && max(cx1[it - 1] - cx0[it - 1] + 1, cy1[it - 1] - cy0[it - 1] + 1) <= 5 }
        val tinyCenters = tiny.map { (cx0[it - 1] + cx1[it - 1]) / 2 to (cy0[it - 1] + cy1[it - 1]) / 2 }
        val screentone = HashSet<Int>()
        for ((k, id) in tiny.withIndex()) {
            val (x, y) = tinyCenters[k]
            var near = 0
            for ((x2, y2) in tinyCenters) if (abs(x2 - x) <= 6 && abs(y2 - y) <= 6) near++
            if (near - 1 >= 4) screentone += id
        }
        val adjSet = adjOk.toHashSet()
        // Vòng cách nét 2 px: phải phần lớn là nền trắng (≥ 215) hoặc nét của CHỮ bên cạnh; điểm xám trung bình
        // (lưới chấm/tram) và nét lạ làm giảm tỉ lệ → nét vẽ trên nền tram bị loại.
        fun ringLight(id: Int): Float {
            val x0 = cx0[id - 1] - 2; val y0 = cy0[id - 1] - 2; val x1 = cx1[id - 1] + 2; val y1 = cy1[id - 1] + 2
            var tot = 0; var li = 0
            fun smp(x: Int, y: Int) {
                if (x !in 0 until sw || y !in 0 until sh) return
                tot++
                val p = y * sw + x
                val v = gray[l + x, t + y]
                if (v >= 215 || (isInk[p] && lab[p] in adjSet && lab[p] !in screentone)) li++
            }
            for (x in x0..x1) { smp(x, y0); smp(x, y1) }
            for (y in y0..y1) { smp(x0, y); smp(x1, y) }
            return li.toFloat() / max(1, tot)
        }
        var thinLong = 0
        val eligible = adjOk.filter { id ->
            if (id in screentone) return@filter false
            val cw = cx1[id - 1] - cx0[id - 1] + 1
            val ch = cy1[id - 1] - cy0[id - 1] + 1
            val fill = cnt[id - 1].toFloat() / (cw * ch)
            // Nét DÀI và MẢNH/CONG (viền bong bóng, tia khung nổ, đường tốc độ) không phải chữ.
            // Gạch "ー" thẳng có độ đặc của khung cao nên vẫn được giữ.
            val curvy = max(cw, ch) > 2.5f * max(med, 4) && fill < 0.35f
            if (curvy) thinLong++
            !curvy && ringLight(id) >= 0.75f
        }
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
                val fillA = cnt[id - 1].toFloat() / max(1f, bx.area)
                if (id in screentone || md > 1.5f * charSize || (md > charSize && fillA < 0.35f) || ringLight(id) < 0.6f) continue
                if (bx.left >= block.left && bx.right <= block.right && bx.top >= block.top && bx.bottom <= block.bottom) chosen += id
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
        if (missed > 0.2f * max(1, inAiInk)) return skip(r, ai, "Không tách trọn chữ trong vùng (sót ${pct(missed / max(1f, inAiInk.toFloat()))}%, có nét dính viền/hình vẽ) — giữ nguyên ảnh")
        val dropped = (1..comps).count { inkC[it - 1] } - chosen.size
        val inkBoxW = cluster
        val inkRatio = keptInk / max(1f, inkBoxW.area)
        if (inkRatio > 0.45f) return skip(r, ai, "Vùng dày mực (${pct(inkRatio)}%) — có thể là hình vẽ, giữ nguyên ảnh")

        // Nét giống chữ nằm TRONG khối chữ nhưng không được xóa (không tính mảnh sát viền ở rìa) → sẽ còn lộ.
        val blockInk = chosen.sumOf { cnt[it - 1] }
        val leftInBlock = eligible.filter { it !in chosen && it !in nearForeign && inkBoxW.contains(cbox(it).centerX, cbox(it).centerY) }.sumOf { cnt[it - 1] }
        val residualInBlock = leftInBlock.toFloat() / max(1, blockInk + leftInBlock)
        // Mặt nạ trên vùng mở rộng E (cửa sổ + khoảng bằng kích thước vùng AI mỗi phía, để chữ Việt có chỗ):
        // - "lòng" = trong cửa sổ: nền chính + lòng nhỏ + nét chữ đã chọn; ngoài cửa sổ: chỉ điểm trắng tinh (≥ 230).
        // - "nét cần xóa" = nét chữ đã chọn + quầng xám khử răng cưa quanh nét (≤ 3 px, thuộc lòng, < 245) → không còn bóng.
        // Bộ vẽ chỉ tô điểm thuộc lòng → viền bong bóng và nét vẽ không bao giờ bị đụng.
        // Chỉ nới ra ngoài cửa sổ theo CHIỀU NGANG (chữ Việt ngang cần bề rộng); không nới xuống/lên để chữ
        // không tràn khỏi bong bóng hở xuống tranh bên dưới.
        val extra = (max(sw, sh) * 0.6f).toInt()
        val el = max(0, l - extra); val et = t; val er = min(gw, rr + extra); val eb = bb
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
        // Ứng viên: chính khối chữ Nhật (sẽ được xóa) nếu mực còn lại bên trong (không xóa) không đáng kể (< 5%).
        run {
            var rest = 0; var all = 0
            for (y in inkBoxW.top.toInt() until inkBoxW.bottom.toInt()) for (x in inkBoxW.left.toInt() until inkBoxW.right.toInt()) {
                val p = y * sw + x
                if (!isInk[p]) continue
                all++
                if (lab[p] !in chosen) rest++
            }
            if (all > 0 && rest <= 0.05f * all) {
                val ib = BoxF(inkBoxW.left + l - el, inkBoxW.top + t - et, inkBoxW.right + l - el, inkBoxW.bottom + t - et)
                candidates += ib
            }
        }
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
                (if (thinLong > 0) ", $thinLong nét dài/cong" else "") +
                (if (nearForeign.isNotEmpty()) ", ${nearForeign.size} cụm sát viền" else "") +
                ") → chỉ xóa đúng các nét chữ này, không phủ khung",
            ai, origW(inkBoxW), origE(layoutE), lineHeight, shape, residual = residualInBlock)
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

    /**
     * Chỉ giữ "lỗ" giống chữ trong bong bóng: cụm nét cỡ ký tự, không dài/cong, không phải chấm tram.
     * @return null nếu bong bóng chứa mảng mực lớn không phải chữ (≥ 2.5 cỡ chữ và đặc, hoặc diện tích lớn).
     */
    private fun textOnly(b: BubbleDetector.Bubble, gray: GrayImage): BubbleDetector.Bubble? {
        val bw = b.width; val bh = b.height
        val lab = IntArray(bw * bh)
        val q = IntArray(bw * bh)
        data class C(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val n: Int) { val md get() = max(x1 - x0 + 1, y1 - y0 + 1) }
        val comps = ArrayList<C>()
        for (st in 0 until bw * bh) {
            if (lab[st] != 0 || !b.isHole(b.left + st % bw, b.top + st / bw)) continue
            val id = comps.size + 1
            var hd = 0; var tl = 0; q[tl++] = st; lab[st] = id
            var x0 = bw; var y0 = bh; var x1 = -1; var y1 = -1; var n = 0
            while (hd < tl) {
                val p = q[hd++]; val x = p % bw; val y = p / bw; n++
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (nb in intArrayOf(if (x > 0) p - 1 else -1, if (x < bw - 1) p + 1 else -1, if (y > 0) p - bw else -1, if (y < bh - 1) p + bw else -1)) {
                    if (nb >= 0 && lab[nb] == 0 && b.isHole(b.left + nb % bw, b.top + nb / bw)) { lab[nb] = id; q[tl++] = nb }
                }
            }
            comps += C(x0, y0, x1, y1, n)
        }
        if (comps.isEmpty()) return null
        val sizes = comps.filter { it.n >= 8 }.ifEmpty { comps }.map { it.md }.sorted()
        val med = max(4, sizes[(sizes.size * 3) / 4])
        val keep = BooleanArray(comps.size + 1)
        var bigInk = false
        for ((i, c) in comps.withIndex()) {
            val fill = c.n.toFloat() / ((c.x1 - c.x0 + 1) * (c.y1 - c.y0 + 1))
            val curvy = c.md > 3f * med && fill < 0.35f
            val massive = c.md > 3f * med && fill >= 0.35f && c.n > 3f * med * med * 0.35f
            if (massive) bigInk = true
            keep[i + 1] = !curvy && !massive
        }
        if (bigInk) return null
        val interior = BooleanArray(bw * bh) { val x = b.left + it % bw; val y = b.top + it / bw
            b.isInterior(x, y) && (lab[it] == 0 || keep[lab[it]]) }
        val holes = BooleanArray(bw * bh) { lab[it] != 0 && keep[lab[it]] }
        return BubbleDetector.Bubble(b.label, b.left, b.top, b.right, b.bottom, interior, holes,
            interior.count { it }, holes.count { it }, b.inner)
    }

    /** Bản sao bong bóng thật với "nét cần xóa" gồm cả quầng xám quanh nét (lòng bong bóng giữ nguyên). */
    private fun withHalo(b: BubbleDetector.Bubble, gray: GrayImage): BubbleDetector.Bubble {
        val bw = b.width; val bh = b.height
        val interior = BooleanArray(bw * bh) { b.isInterior(b.left + it % bw, b.top + it / bw) }
        val holes = BooleanArray(bw * bh) { b.isHole(b.left + it % bw, b.top + it / bw) }
        val halo = haloHoles(holes, interior, bw, bh) { x, y -> gray[b.left + x, b.top + y] }
        return BubbleDetector.Bubble(b.label, b.left, b.top, b.right, b.bottom, interior, halo, b.interiorArea, halo.count { it }, b.inner)
    }

    /** Điểm cho vùng đặt chữ Việt ngang: rộng × min(cao, rộng). */
    private fun score(b: BoxF) = b.width * min(b.height, b.width)

    /** Hình chữ nhật lớn nhất (tìm tham lam từ vài điểm xuất phát) nằm trọn trong lòng bong bóng, cách viền ≥ 2 px. */
    private fun growInBubble(b: BubbleDetector.Bubble, textBox: BoxF?): BoxF? {
        fun ok(x: Int, y: Int): Boolean {
            for (dy in -2..2) for (dx in -2..2) if (!b.isInterior(x + dx, y + dy)) return false
            return true
        }
        fun allOk(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
            for (y in y0 until y1) for (x in x0 until x1) if (!ok(x, y)) return false
            return true
        }
        val c = textBox ?: b.box
        val starts = listOf(c.centerX to c.centerY, c.centerX to (c.top + c.height * 0.3f), c.centerX to (c.top + c.height * 0.7f),
            b.box.centerX to b.box.centerY)
        var best: BoxF? = null
        for ((sx, sy) in starts) {
            val x = sx.toInt(); val y = sy.toInt()
            if (!ok(x, y)) continue
            for (horizontalFirst in listOf(false, true)) {
                var lx = x; var ty = y; var rx = x + 1; var by = y + 1
                if (horizontalFirst) {
                    var g = true
                    while (g) { g = false
                        if (allOk(lx - 1, ty, lx, by)) { lx--; g = true }
                        if (allOk(rx, ty, rx + 1, by)) { rx++; g = true } }
                }
                var g = true
                while (g) { g = false
                    if (allOk(lx - 1, ty, lx, by)) { lx--; g = true }
                    if (allOk(rx, ty, rx + 1, by)) { rx++; g = true }
                    if (allOk(lx, ty - 1, rx, ty)) { ty--; g = true }
                    if (allOk(lx, by, rx, by + 1)) { by++; g = true } }
                val r = BoxF(lx.toFloat(), ty.toFloat(), rx.toFloat(), by.toFloat())
                if (best == null || score(r) > score(best!!)) best = r
            }
        }
        return best
    }

    /** Nhãn âm cho "bong bóng" tổng hợp của vùng hở (không trùng nhãn bong bóng thật). */
    private const val OPEN_LABEL_BASE = -1
}
