package vn.mangatranslate.app.core.render

import kotlin.math.max
import kotlin.math.min

/**
 * Các kiểm tra an toàn cho bước vẽ — thuần Kotlin để kiểm thử trên JVM.
 * Ưu tiên BẢO TOÀN ẢNH GỐC: khi nghi ngờ, không xóa/không vẽ và đánh dấu để người dùng kiểm tra.
 */
object RenderSafety {

    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = max(0, right - left)
        val height: Int get() = max(0, bottom - top)
        val area: Long get() = width.toLong() * height

        fun clamp(w: Int, h: Int) = Rect(left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h))
    }

    /**
     * Mask xóa chữ có hợp lý không?
     * - Vùng được phép xóa (khung chữ nới rộng) không được quá [maxAllowedPageRatio] diện tích trang.
     * - Nét chữ thường chiếm khoảng 10–40% khung chữ; mask > [maxInkRatio] khung chữ là bất thường
     *   (thường do nhầm hình vẽ là chữ) → không xóa.
     * @return null nếu an toàn, hoặc lý do từ chối.
     */
    fun maskProblem(
        maskPixels: Long,
        allowedPixels: Long,
        pagePixels: Long,
        maxAllowedPageRatio: Float = 0.06f,
        maxInkRatio: Float = 0.55f
    ): String? {
        if (allowedPixels <= 0 || pagePixels <= 0) return "Vùng xóa chữ rỗng"
        if (allowedPixels > maxAllowedPageRatio * pagePixels) {
            return "Vùng xóa chữ quá lớn (${pct(allowedPixels, pagePixels)}% trang) — đã bỏ qua xóa chữ"
        }
        if (maskPixels > maxInkRatio * allowedPixels) {
            return "Mask xóa chữ bất thường (${pct(maskPixels, allowedPixels)}% vùng chữ) — đã bỏ qua xóa chữ"
        }
        return null
    }

    /**
     * Đếm số điểm ảnh bị thay đổi NẰM NGOÀI các vùng được phép (lớp phủ, vùng xóa chữ, vùng vẽ chữ).
     * Phải luôn bằng 0; khác 0 nghĩa là có lỗi vẽ lan ra hình vẽ.
     */
    fun changedOutside(original: IntArray, processed: IntArray, w: Int, h: Int, allowed: List<Rect>): Int {
        require(original.size == w * h && processed.size == w * h) { "Kích thước ảnh không khớp" }
        val ok = BooleanArray(w * h)
        for (r in allowed) {
            val c = r.clamp(w, h)
            for (y in c.top until c.bottom) {
                val base = y * w
                for (x in c.left until c.right) ok[base + x] = true
            }
        }
        var changed = 0
        for (i in original.indices) if (!ok[i] && original[i] != processed[i]) changed++
        return changed
    }

    private fun pct(a: Long, b: Long): Int = (100.0 * a / max(1L, b)).toInt().let { min(it, 9999) }
}
