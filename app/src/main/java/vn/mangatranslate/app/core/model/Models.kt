package vn.mangatranslate.app.core.model

/** Ngôn ngữ nguồn. [code] là mã BCP-47 mà ML Kit Translate dùng. */
enum class SourceLanguage(val label: String, val code: String?) {
    AUTO("Tự động nhận dạng", null),
    CHINESE("Tiếng Trung", "zh"),
    JAPANESE("Tiếng Nhật", "ja"),
    KOREAN("Tiếng Hàn", "ko"),
    ENGLISH("Tiếng Anh", "en");
}

/** Kiểu xưng hô áp dụng lên bản dịch máy (máy dịch offline thường trả về "tôi / bạn"). */
enum class PronounStyle(val label: String) {
    KEEP("Giữ nguyên bản dịch máy (tôi / bạn)"),
    TA_NGUOI("Ta / Ngươi"),
    TOI_CAU("Tôi / Cậu"),
    ANH_EM("Anh / Em"),
    HUYNH_MUOI("Huynh / Muội"),
    BON_TOA("Bổn tọa / Các ngươi");
}

enum class TextColorMode(val label: String) {
    AUTO("Tự động theo màu nền"),
    BLACK("Chữ đen, viền trắng"),
    WHITE("Chữ trắng, viền đen");
}

/** Một dòng chữ OCR, tọa độ theo ảnh gốc. */
data class OcrLine(val text: String, val box: BoxF)

/** Một khối chữ OCR (ML Kit TextBlock), tọa độ theo ảnh gốc. */
data class OcrBlock(val lines: List<OcrLine>, val box: BoxF)

/**
 * Một vùng thoại đã nhận dạng và dịch.
 * @param box vùng chữ theo pixel ảnh gốc.
 * @param rawTranslation bản dịch máy chưa đổi xưng hô (để đổi kiểu xưng hô không cần dịch lại).
 * @param lineHeight độ cao một dòng chữ gốc (với chữ dọc: độ rộng cột) — dùng để chọn cỡ chữ Việt.
 * @param userEdited người dùng đã sửa bản dịch — không tự ghi đè nữa.
 */
data class TextRegion(
    val id: Int,
    val box: BoxF,
    val sourceText: String,
    val rawTranslation: String,
    val translatedText: String,
    val vertical: Boolean,
    val lineHeight: Float,
    val manual: Boolean = false,
    val userEdited: Boolean = false,
    val enabled: Boolean = true,
    /** Vùng đặt chữ Việt (ảnh gốc): phần trong bong bóng hoặc khoảng trắng quanh chữ. null = dùng [box]. */
    val layoutBox: BoxF? = null,
    /** Hình dạng bong bóng nếu phát hiện được bong bóng khép kín (dùng để xóa sạch chữ gốc trong bong bóng). */
    val bubble: BubbleShape? = null,
    /** Lý do cần người dùng kiểm tra (dấu hiệu kiểm chứng được, không phải điểm tin cậy). */
    val reviewNotes: List<String> = emptyList(),
    /** Ghi chú thêm, không tính là lỗi. */
    val infoNotes: List<String> = emptyList(),
    /** Cách OCR đã chọn cho vùng này. */
    val ocrMethod: String = "",
    /** Mã ngôn ngữ nguồn (ja, zh, ko, en). */
    val sourceLang: String = "",
    /** Người dùng đã xem và bấm "Lưu bản dịch" → được vẽ lên ảnh dù từng bị đánh dấu cần kiểm tra. */
    val confirmed: Boolean = false,
    /** Vết xử lý cho màn hình DEBUG. */
    val trace: RegionTrace? = null,
    /**
     * Vùng phủ nền sáng CHÍNH XÁC đã được kiểm tra không đè nét vẽ (chỉ dùng cho vùng không có bong bóng khép kín,
     * do AiRegionMapper tính). null = cách cũ (khung chữ + lề).
     */
    val coverBox: BoxF? = null
) {
    val needsReview: Boolean get() = reviewNotes.isNotEmpty()

    /** Bản dịch đáng ngờ chưa được người dùng xác nhận. */
    val unverified: Boolean get() = needsReview && !confirmed && !userEdited
}

/**
 * Bong bóng khép kín đã phát hiện. [mask] dùng tọa độ ảnh xám đã thu nhỏ; ảnh gốc = tọa độ xám / [scale].
 * Không phải data class: so sánh theo tham chiếu (mảng mặt nạ lớn).
 */
class BubbleShape(
    val box: BoxF,
    val inner: BoxF,
    val mask: vn.mangatranslate.app.core.vision.BubbleDetector.Bubble,
    val scale: Float
)

data class RenderStyle(
    val textColorMode: TextColorMode = TextColorMode.AUTO,
    val outline: Boolean = true,
    val bold: Boolean = true,
    val lineSpacing: Float = 1.0f,
    val coverBackground: Boolean = true,
    /** Độ che của lớp nền sáng (0–100%) khi không xóa sạch được chữ gốc. */
    val overlayOpacity: Int = 92,
    /** Xóa chữ gốc trong bong bóng trắng khép kín. MẶC ĐỊNH TẮT (thử nghiệm) sau lỗi xóa mất hình ở bản 0.1.2. */
    val cleanBubbles: Boolean = false,
    /** Không vẽ bản dịch đáng ngờ (cần kiểm tra) lên ảnh cho tới khi người dùng xác nhận. */
    val hideUnverified: Boolean = true,
    /** Chế độ DEBUG: tô đỏ mask xóa chữ, viền xanh vùng đặt chữ thay vì xóa thật. */
    val debugMask: Boolean = false,
    /** Chọn cỡ chữ lớn nhất vừa vùng đặt chữ (dùng khi render bản dịch ChatGPT). Mặc định tắt: luồng cũ không đổi. */
    val fillBubble: Boolean = false
)
