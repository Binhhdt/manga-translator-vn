package vn.mangatranslate.app.core.chatgpt

import android.graphics.Bitmap
import vn.mangatranslate.app.core.image.BitmapGray
import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.model.BubbleShape
import vn.mangatranslate.app.core.model.RenderStyle
import vn.mangatranslate.app.core.model.TextRegion
import vn.mangatranslate.app.core.render.VietnameseTextRenderer
import vn.mangatranslate.app.core.vision.BubbleDetector
import java.util.Locale

/**
 * Chèn bản dịch của ChatGPT lên ảnh gốc (giai đoạn E — MVP một trang).
 *
 * 1. Tìm bong bóng trên ảnh thật ([BubbleDetector]).
 * 2. [AiRegionMapper] ghép từng vùng AI với bong bóng / nét chữ thật, quyết định BUBBLE / OPEN / SKIP.
 * 3. [VietnameseTextRenderer] (bộ vẽ sẵn có) vẽ trên BẢN SAO: xóa đúng nét chữ trong bong bóng khép kín, phủ nền
 *    sáng đã kiểm tra cho chữ ngoài bong bóng, tự xuống dòng/căn giữa/chọn cỡ chữ; không đủ chỗ → giữ nguyên ảnh.
 *    Cuối cùng so sánh từng điểm ảnh: mọi thay đổi phải nằm trong vùng cho phép.
 */
class AiPageRenderer {

    class Result(
        val bitmap: Bitmap,
        val log: String,
        val drawn: Int,
        val skipped: Int,
        val changedOutside: Int,
        /** Vùng thoại/lời dẫn mà chữ Nhật vẫn còn trên ảnh (bỏ qua vì không an toàn, không đủ chỗ…). */
        val japaneseLeft: Int,
        /** Vùng cố ý giữ nguyên (sfx, AI đánh dấu cần kiểm tra). */
        val keptOnPurpose: Int,
        /** Tóm tắt một dòng cho màn hình. */
        val summary: String,
        /** Kết quả kiểm tra sau render (A/B/C/D/OK) từng vùng. */
        val checks: List<PostRenderCheck.Item> = emptyList(),
        /** Ảnh DEBUG: bản sao ảnh đã chèn, khoanh và đánh số các vùng theo loại. */
        val debug: Bitmap? = null
    ) {
        private fun n(c: PostRenderCheck.Cat) = checks.count { it.cat == c }
        val okCount get() = n(PostRenderCheck.Cat.OK)
        val notInserted get() = n(PostRenderCheck.Cat.B)
        val suspectedJapanese get() = n(PostRenderCheck.Cat.C)
        val missingTranslation get() = n(PostRenderCheck.Cat.A)
        val kept get() = n(PostRenderCheck.Cat.D)
        /** Số vùng cần xử lý / kiểm tra thủ công (B + C + A). */
        val needsReview get() = notInserted + suspectedJapanese + missingTranslation
    }

    fun render(original: Bitmap, regions: List<MangaJson.Region>): Result {
        val w = original.width
        val h = original.height
        val t0 = System.currentTimeMillis()
        val scaled = BitmapGray.fromBitmap(original, GRAY_MAX_PIXELS)
        val s = scaled.scale
        val bubbles = BubbleDetector().detect(scaled.image)
        var decisions = AiRegionMapper.map(regions, w, h, scaled.image, s, bubbles)
        val og = BitmapGray.fromBitmap(original).image.pixels

        // Mỗi quyết định không bỏ qua → một TextRegion (id = số thứ tự trong danh sách quyết định + 1).
        val textRegions = ArrayList<TextRegion>()
        decisions.forEachIndexed { i, d ->
            if (d.action == AiRegionMapper.Action.SKIP) return@forEachIndexed
            val box = d.textBox ?: return@forEachIndexed
            val bubble = d.bubble?.let { b ->
                BubbleShape(gray2orig(b.box, s), gray2orig(b.inner, s), b, s)
            }
            textRegions += TextRegion(
                id = i + 1, box = box, sourceText = d.ja, rawTranslation = d.vi, translatedText = d.vi,
                vertical = true, lineHeight = d.lineHeight, layoutBox = d.layoutBox, bubble = bubble,
                sourceLang = "ja", confirmed = true, coverBox = d.coverBox
            )
        }
        val style = RenderStyle(
            cleanBubbles = true,      // chỉ xóa nét chữ bị bong bóng khép kín bao quanh, có kiểm tra mask
            hideUnverified = false,   // vùng AI "uncertain" đã bị bỏ qua ở bước ghép
            coverBackground = true,
            // Phủ nền chỉ tô điểm BÊN TRONG lòng bong bóng/vùng đã kiểm tra → che kín 100%, không để lộ "bóng" chữ Nhật.
            overlayOpacity = 100,
            fillBubble = true
        )
        var out = VietnameseTextRenderer().render(original, textRegions, style)

        // LƯỚI AN TOÀN: vùng nào lỡ xóa nét vẽ/viền → render lại KHÔNG có vùng đó (giữ nguyên ảnh gốc ở đó).
        for (pass in 0 until 2) {
            val outGray = BitmapGray.fromBitmap(out.bitmap).image.pixels
            val bad = HashMap<Int, String>()
            decisions.forEachIndexed { i, d ->
                if (d.action == AiRegionMapper.Action.SKIP) return@forEachIndexed
                PostRenderCheck.damage(w, h, og, outGray, d)?.let { bad[i] = it }
            }
            if (bad.isEmpty()) break
            decisions = decisions.mapIndexed { i, d ->
                bad[i]?.let { why -> d.copy(action = AiRegionMapper.Action.SKIP, reason = "Bỏ qua để bảo vệ tranh: $why") } ?: d
            }
            out.bitmap.recycle()
            out = VietnameseTextRenderer().render(original, textRegions.filter { tr -> bad[tr.id - 1] == null }, style)
            textRegions.removeAll { tr -> bad[tr.id - 1] != null }
        }

        // Never silently lose original Japanese from a region that was NOT rendered.
        // An unrelated bubble's paint/overlay may touch it, even though its own
        // decision was SKIP/overflow/failed. Restore only that original AI text box,
        // and only when it does not overlap any successfully rendered region.
        // This does not invent Vietnamese text; it prevents empty speech bubbles.
        val successful = decisions.mapIndexedNotNull { i, d ->
            if (d.action != AiRegionMapper.Action.SKIP && i + 1 !in out.failedIds &&
                i + 1 !in out.overflowIds) d else null
        }
        val successfulIds = successful.flatMap { it.ids }.toSet()
        val successfulAreas = successful.flatMap { d -> listOfNotNull(d.aiBox, d.layoutBox, d.textBox) }
        val restored = ArrayList<String>()
        for (region in regions) {
            if (region.id in successfulIds) continue
            val b = region.bbox ?: continue
            if (b.size != 4) continue
            val rect = BoxF((b[0] * w).toFloat(), (b[1] * h).toFloat(),
                ((b[0] + b[2]) * w).toFloat(), ((b[1] + b[3]) * h).toFloat()).clampTo(w.toFloat(), h.toFloat())
            if (!PostRenderCheck.safeToRestore(rect, successfulAreas)) continue
            if (rect.area > w.toFloat() * h * 0.10f) continue // avoid giant erroneous AI boxes
            val l = rect.left.toInt().coerceIn(0, w - 1)
            val t = rect.top.toInt().coerceIn(0, h - 1)
            val rr = kotlin.math.ceil(rect.right.toDouble()).toInt().coerceIn(l + 1, w)
            val bb = kotlin.math.ceil(rect.bottom.toDouble()).toInt().coerceIn(t + 1, h)
            val bw = rr - l
            val bh = bb - t
            val originalPixels = IntArray(bw * bh)
            val resultPixels = IntArray(bw * bh)
            original.getPixels(originalPixels, 0, bw, l, t, bw, bh)
            out.bitmap.getPixels(resultPixels, 0, bw, l, t, bw, bh)
            if (!originalPixels.contentEquals(resultPixels)) {
                out.bitmap.setPixels(originalPixels, 0, bw, l, t, bw, bh)
                restored += region.id
            }
        }

        val log = StringBuilder()
        if (restored.isNotEmpty()) log.append("BẢO VỆ CHỮ NHẬT GỐC: khôi phục ${restored.size} vùng chưa chèn nhưng bị vùng khác làm thay đổi (#${restored.joinToString("+")}).\\n")
        log.append("Render thử ChatGPT → ảnh gốc ${w}×$h (ảnh gốc không bị sửa; vẽ trên bản sao)\n")
        log.append("Bong bóng khép kín phát hiện: ${bubbles.bubbles.size}; vùng AI: ${regions.size}\n")
        var drawn = 0
        var skipped = 0
        var japaneseLeft = 0
        var keptOnPurpose = 0
        val reasons = LinkedHashMap<String, Int>()
        decisions.forEachIndexed { i, d ->
            val id = i + 1
            val status = when {
                d.action == AiRegionMapper.Action.SKIP -> "BỎ QUA"
                id in out.failedIds -> "LỖI VẼ — bỏ qua"
                id in out.overflowIds -> "KHÔNG ĐỦ CHỖ — giữ nguyên ảnh"
                out.maskRejected.containsKey(id) -> "PHỦ KÍN LÒNG BONG BÓNG (không xóa từng nét: ${out.maskRejected[id]})"
                d.action == AiRegionMapper.Action.BUBBLE -> "XÓA NÉT CHỮ TRONG BONG BÓNG + VẼ"
                else -> "XÓA ĐÚNG NÉT CHỮ (VÙNG HỞ) + VẼ"
            }
            val ok = !(status.startsWith("BỎ") || status.startsWith("LỖI") || status.startsWith("KHÔNG"))
            if (ok) drawn++ else {
                skipped++
                val purposeful = d.action == AiRegionMapper.Action.SKIP &&
                    (d.type.equals("sfx", true) || d.reason.startsWith("AI đánh dấu"))
                if (purposeful) keptOnPurpose++ else japaneseLeft++
                val key = when {
                    status.startsWith("KHÔNG ĐỦ") -> "Không đủ chỗ cho bản dịch"
                    status.startsWith("LỖI") -> "Lỗi khi vẽ"
                    else -> d.reason.substringBefore(" (").substringBefore(" —").trim()
                }
                reasons[key] = (reasons[key] ?: 0) + 1
            }
            log.append("\nVùng AI #${d.ids.joinToString("+")} [${d.type}] → $status\n")
            log.append("  Lý do: ${d.reason}\n")
            log.append("  bbox AI (px, ước lượng): ${fmt(d.aiBox)}\n")
            d.textBox?.let { log.append("  Khung nét chữ thật: ${fmt(it)}\n") }
            d.layoutBox?.let { log.append("  Vùng đặt chữ: ${fmt(it)}\n") }
            log.append("  JA: ${d.ja}\n  VI: ${d.vi}\n")
        }
        val changed = out.changedOutside
        val stats = StringBuilder()
        stats.append("THỐNG KÊ: đã render $drawn vùng · còn chữ Nhật $japaneseLeft vùng · cố ý giữ nguyên $keptOnPurpose vùng (sfx/AI chưa chắc)\n")
        reasons.forEach { (k, v) -> stats.append("  - $k: $v vùng\n") }
        log.insert(0, stats.toString() + "\n")
        log.append("\nTổng: vẽ $drawn vùng, không vẽ $skipped vùng.\n")
        log.append(
            when {
                changed == 0 -> "Kiểm tra an toàn: 0 điểm ảnh bị đổi ngoài vùng cho phép.\n"
                changed < 0 -> "Kiểm tra an toàn: ảnh quá lớn, bỏ qua bước so sánh điểm ảnh.\n"
                else -> "CẢNH BÁO: $changed điểm ảnh bị đổi ngoài vùng cho phép.\n"
            }
        )
        log.append("Thời gian: ${System.currentTimeMillis() - t0} ms\n")

        // ---- Kiểm tra sau render: so ảnh gốc / ảnh ra, đối chiếu JSON.
        val drawnIds = HashSet<Int>()
        val notDrawn = HashMap<Int, String>()
        decisions.forEachIndexed { i, d ->
            val id = i + 1
            if (d.action == AiRegionMapper.Action.SKIP) return@forEachIndexed
            when {
                id in out.failedIds -> notDrawn[id] = "Lỗi khi vẽ"
                id in out.overflowIds -> notDrawn[id] = out.maskRejected[id] ?: "Không đủ chỗ cho bản dịch"
                else -> drawnIds += id
            }
        }
        val ogOut = BitmapGray.fromBitmap(out.bitmap).image.pixels
        val checks = PostRenderCheck.check(w, h, og, ogOut, regions, decisions, drawnIds, notDrawn, bubbles, s)
        val debug = drawDebug(out.bitmap, checks)
        val cnt = PostRenderCheck.counts(checks)
        val report = StringBuilder()
        report.append("KIỂM TRA SAU RENDER: vùng trong JSON ${regions.size} · đã chèn ${cnt[PostRenderCheck.Cat.OK]} · " +
            "có bản dịch nhưng chưa chèn ${cnt[PostRenderCheck.Cat.B]} · nghi còn chữ Nhật ${cnt[PostRenderCheck.Cat.C]} · " +
            "không có trong JSON ${cnt[PostRenderCheck.Cat.A]} · cố ý giữ ${cnt[PostRenderCheck.Cat.D]}\n")
        for (it in checks) if (it.cat != PostRenderCheck.Cat.OK) {
            report.append("  [${it.num}] ${it.cat.code} ${it.cat.label}${if (it.ids.isNotEmpty()) " (vùng AI #${it.ids.joinToString("+")})" else ""}: ${it.reason} @ ${fmt(it.box)}\n")
        }
        log.insert(0, report.toString() + "\n")
        val needs = (cnt[PostRenderCheck.Cat.B] ?: 0) + (cnt[PostRenderCheck.Cat.C] ?: 0) + (cnt[PostRenderCheck.Cat.A] ?: 0)
        val summary = "Đã chèn ${cnt[PostRenderCheck.Cat.OK]} · cần xử lý $needs (B ${cnt[PostRenderCheck.Cat.B]}, C ${cnt[PostRenderCheck.Cat.C]}, A ${cnt[PostRenderCheck.Cat.A]}) · " +
            "cố ý giữ ${cnt[PostRenderCheck.Cat.D]} · điểm ảnh đổi ngoài vùng cho phép: $changed"
        return Result(out.bitmap, log.toString(), drawn, skipped, changed, japaneseLeft, keptOnPurpose, summary, checks, debug)
    }

    /** Ảnh DEBUG: xanh lá = đã chèn, đỏ = B, cam = C, tím = A, xanh dương = D; số thứ tự khớp báo cáo. */
    private fun drawDebug(src: Bitmap, checks: List<PostRenderCheck.Item>): Bitmap? = try {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = android.graphics.Canvas(bmp)
        val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = maxOf(2f, bmp.width / 300f)
        }
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = maxOf(14f, bmp.width / 45f); isFakeBoldText = true
        }
        val bg = android.graphics.Paint().apply { color = 0xCCFFFFFF.toInt() }
        for (it in checks) {
            val col = when (it.cat) {
                PostRenderCheck.Cat.OK -> 0xFF00A000.toInt()
                PostRenderCheck.Cat.B -> 0xFFE00000.toInt()
                PostRenderCheck.Cat.C -> 0xFFFF8C00.toInt()
                PostRenderCheck.Cat.A -> 0xFFC000C0.toInt()
                PostRenderCheck.Cat.D -> 0xFF0060FF.toInt()
            }
            stroke.color = col; text.color = col
            val r = android.graphics.RectF(it.box.left, it.box.top, it.box.right, it.box.bottom)
            c.drawRect(r, stroke)
            val label = "${it.num}${if (it.cat == PostRenderCheck.Cat.OK) "" else it.cat.code}"
            val tw = text.measureText(label)
            c.drawRect(r.left, r.top - text.textSize, r.left + tw + 4, r.top, bg)
            c.drawText(label, r.left + 2, r.top - 3, text)
        }
        bmp
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun gray2orig(b: BoxF, s: Float) = BoxF(b.left / s, b.top / s, b.right / s, b.bottom / s)

    private fun fmt(b: BoxF) = String.format(Locale.US, "(%.0f, %.0f)–(%.0f, %.0f) %.0f×%.0f", b.left, b.top, b.right, b.bottom, b.width, b.height)

    private companion object {
        const val GRAY_MAX_PIXELS = 4_000_000L
    }
}
