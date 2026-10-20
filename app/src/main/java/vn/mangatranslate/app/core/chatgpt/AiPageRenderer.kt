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
        val summary: String
    )

    fun render(original: Bitmap, regions: List<MangaJson.Region>): Result {
        val w = original.width
        val h = original.height
        val t0 = System.currentTimeMillis()
        val scaled = BitmapGray.fromBitmap(original, GRAY_MAX_PIXELS)
        val s = scaled.scale
        val bubbles = BubbleDetector().detect(scaled.image)
        val decisions = AiRegionMapper.map(regions, w, h, scaled.image, s, bubbles)

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
        val out = VietnameseTextRenderer().render(original, textRegions, style)

        val log = StringBuilder()
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
        val summary = "Đã render $drawn · còn chữ Nhật $japaneseLeft · cố ý giữ $keptOnPurpose · điểm ảnh đổi ngoài vùng cho phép: $changed"
        return Result(out.bitmap, log.toString(), drawn, skipped, changed, japaneseLeft, keptOnPurpose, summary)
    }

    private fun gray2orig(b: BoxF, s: Float) = BoxF(b.left / s, b.top / s, b.right / s, b.bottom / s)

    private fun fmt(b: BoxF) = String.format(Locale.US, "(%.0f, %.0f)–(%.0f, %.0f) %.0f×%.0f", b.left, b.top, b.right, b.bottom, b.width, b.height)

    private companion object {
        const val GRAY_MAX_PIXELS = 4_000_000L
    }
}
