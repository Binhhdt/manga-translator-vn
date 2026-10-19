package vn.mangatranslate.app.core.translate

/**
 * Tìm tên riêng Nhật đã được phiên âm La-tinh (romaji) trong câu tiếng Anh trung gian: "Kurosaki", "Kanagawa",
 * "Kagoshima". Dùng để giữ nguyên tên khi dịch Anh → Việt (bước này hay dịch nghĩa tên: "Kurosaki" → "Nợ máu").
 *
 * Quy tắc tổng quát (không dùng danh sách tên cố định): từ viết hoa chữ đầu, tách được HOÀN TOÀN thành các âm tiết
 * Hepburn (ka, shi, tsu, ryo, n…). Từ ở đầu câu phải dài ≥ 6 chữ để tránh nhầm "Take", "Make", "Home".
 */
object RomajiNames {

    private val SYLLABLES = Regex(
        "^(?:(?:ky|gy|sh|ch|ts|ny|hy|by|py|my|ry|j|[kgsztdnhbpmyrwf])?(?:[aiueo]|ō|ū|ā)|n)+$",
        RegexOption.IGNORE_CASE
    )
    private val WORD = Regex("\\p{L}+")

    fun isRomaji(word: String): Boolean = word.length >= 3 && SYLLABLES.matches(word)

    /** Các tên tìm được, theo thứ tự xuất hiện, không trùng. */
    fun find(english: String): List<String> {
        val out = LinkedHashSet<String>()
        var sentenceStart = true
        var lastEnd = 0
        for (m in WORD.findAll(english)) {
            val between = english.substring(lastEnd, m.range.first)
            if (between.any { it == '.' || it == '!' || it == '?' || it == '…' }) sentenceStart = true
            val w = m.value
            if (w[0].isUpperCase() && w.drop(1).all { it.isLowerCase() } && isRomaji(w)) {
                if (!sentenceStart || w.length >= 6) out += w
            }
            sentenceStart = false
            lastEnd = m.range.last + 1
        }
        return out.toList()
    }
}
