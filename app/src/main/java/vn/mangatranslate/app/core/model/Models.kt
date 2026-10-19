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
    val enabled: Boolean = true
)

data class RenderStyle(
    val textColorMode: TextColorMode = TextColorMode.AUTO,
    val outline: Boolean = true,
    val bold: Boolean = true,
    val lineSpacing: Float = 1.0f,
    val coverBackground: Boolean = true
)
