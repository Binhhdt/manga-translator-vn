package vn.mangatranslate.app.core.ocr

/**
 * Chọn cách đọc OCR cho một vùng. Thuần Kotlin để kiểm thử trên JVM.
 *
 * Bằng chứng (chạy ML Kit thật trên trang 006): với chữ Nhật DỌC, ML Kit đọc theo HÀNG NGANG cắt qua các cột
 * → ký tự của nhiều cột bị trộn («な残ア念や :だ» thay vì «残念だなァ…»). Bản trộn có đủ ký tự nên bộ chấm điểm
 * cũ (đếm ký tự hợp lệ) lại chọn nó. Cách "tách cột dọc" đọc đúng thứ tự ở hầu hết các vùng.
 *
 * Quy tắc:
 * - Chữ dọc (xác định bằng PHÂN TÍCH ẢNH, không dựa vào hình dạng dòng ML Kit trả về):
 *   ưu tiên Tách cột dọc → Đọc riêng vùng → Ảnh đen trắng; bản OCR toàn trang chỉ dùng khi không còn gì khác.
 *   Một cách đọc chỉ được chọn khi không có ký tự lạ và đọc được ít nhất một nửa số ô chữ đếm được trên ảnh.
 * - Chữ ngang: chọn theo điểm như trước.
 */
object CandidatePolicy {

    data class Option(val method: String, val text: String)

    val VERTICAL_PRIORITY = listOf(
        RegionOcr.METHOD_COLUMNS, RegionOcr.METHOD_DIRECT, RegionOcr.METHOD_BINARY, RegionOcr.METHOD_PAGE
    )

    fun choose(options: List<Option>, lang: String, expectedChars: Int?, vertical: Boolean): Option? {
        val usable = options.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return null
        if (!vertical) return usable.maxByOrNull { OcrCandidateScorer.score(it.text, lang, expectedChars) }

        val ordered = usable.sortedBy { o -> VERTICAL_PRIORITY.indexOf(o.method).let { if (it < 0) 99 else it } }
        // Lần 1: cách đọc sạch (không ký tự lạ) và đủ ký tự.
        for (o in ordered) {
            if (o.method == RegionOcr.METHOD_PAGE && ordered.size > 1) continue
            val st = OcrCandidateScorer.stats(o.text, lang)
            val enough = expectedChars == null || expectedChars <= 0 || st.valid >= 0.5f * expectedChars
            if (st.invalid == 0 && enough && st.valid > 0) return o
        }
        // Lần 2: không có cách nào sạch → cách đọc ưu tiên cao nhất có ký tự hợp lệ (vẫn tránh bản toàn trang).
        return ordered.firstOrNull { it.method != RegionOcr.METHOD_PAGE && OcrCandidateScorer.stats(it.text, lang).valid > 0 }
            ?: ordered.first()
    }

    /**
     * Chữ dọc theo phân tích ảnh: có cột chính cao hơn hẳn bề rộng và tổng số ô ≥ 2.
     * [columnShapes]: (rộng, cao, số ô) của từng cột chính.
     */
    fun isVerticalByImage(columnShapes: List<Triple<Int, Int, Int>>): Boolean {
        if (columnShapes.isEmpty()) return false
        val cells = columnShapes.sumOf { it.third }
        if (cells < 2) return false
        val tall = columnShapes.count { (w, h, n) -> n >= 2 && h > 1.5f * w }
        val singleWide = columnShapes.size == 1 && columnShapes[0].let { (w, h, _) -> w > h }
        // Dòng chữ NGANG cũng bị tách thành nhiều "cột" 1 ô (mỗi ký tự một cột). Đo trên trang 006: dải ngang
        // cho 30 cột, chỉ 2 cột có ≥ 2 ô. Chữ dọc thật: đa số cột có ≥ 2 ô.
        val multiCell = columnShapes.count { it.third >= 2 }
        return tall >= 1 && !singleWide && multiCell * 2 >= columnShapes.size
    }
}
