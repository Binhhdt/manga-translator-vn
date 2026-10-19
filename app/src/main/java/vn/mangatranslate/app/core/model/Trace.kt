package vn.mangatranslate.app.core.model

/**
 * Vết xử lý của MỘT vùng chữ, dùng cho màn hình DEBUG và báo cáo chẩn đoán.
 * Văn bản OCR được lưu NGUYÊN VẸN như ML Kit trả về — không chuẩn hóa, không làm đẹp.
 * Thuần Kotlin (ảnh xem trước lưu dạng PNG bytes) để kiểm thử được trên JVM.
 */

/** Một dòng ML Kit trả về. [removedAsRuby]: bị loại vì là furigana. */
data class TraceLine(val text: String, val box: BoxF, val removedAsRuby: Boolean = false)

/** Một lần gọi OCR cho vùng (một "cách đọc"). */
data class OcrAttempt(
    val method: String,
    /** Kích thước ảnh đưa vào cách đọc này (trước khi OCRProcessor phóng/đệm). */
    val inputWidth: Int,
    val inputHeight: Int,
    /** Các dòng ML Kit trả về, đúng thứ tự ML Kit trả. */
    val rawLines: List<TraceLine>,
    /** Văn bản sau khi ghép các dòng theo thứ tự đọc (chưa chuẩn hóa để dịch). */
    val joined: String,
    val score: Float,
    val chosen: Boolean
)

data class RegionTrace(
    /** bong bóng / ngoài bong bóng / bỏ sót / khoanh tay */
    val kind: String,
    /** Vùng cắt trên ảnh gốc. */
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    /** Ảnh vùng cắt ĐÚNG như đưa vào OCR (thu nhỏ để xem), PNG. */
    val cropPng: ByteArray?,
    /** Dòng của OCR toàn trang được gán cho vùng này (thứ tự ML Kit). */
    val pageLines: List<TraceLine>,
    val attempts: List<OcrAttempt>,
    /** Mô tả bố cục cột dọc (số cột, số ô mỗi cột, cột furigana bị bỏ), rỗng nếu không tách cột. */
    val layoutInfo: String,
    /** Văn bản nguồn đã chọn (= sourceText ban đầu). */
    val chosenText: String,
    /** Văn bản THỰC SỰ gửi vào bộ dịch (sau chuẩn hóa + thuật ngữ). */
    val mtInput: String = "",
    /** Kết quả THÔ của bộ dịch, chưa sửa gì. */
    val mtRaw: String = "",
    /** Tên bộ dịch đã dùng. */
    val engine: String = ""
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Kết quả một lần dịch, từng bước. */
data class TranslationTrace(
    val source: String,
    /** Văn bản gửi vào bộ dịch. */
    val engineInput: String,
    /** Kết quả thô của bộ dịch. */
    val engineOutput: String,
    /** Sau khi áp bảng thuật ngữ (chưa đổi xưng hô). */
    val afterGlossary: String,
    val engine: String
)
