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

    /**
     * Thay tên bằng dấu "QQ1", "QQ2"… trước khi dịch Anh → Việt.
     * Chọn kiểu "QQn" vì đo trên ML Kit thật (trang 006): giữ được tên 3/3 lần và giữ đúng chữ hoa;
     * kiểu "Xn" bị viết thường, "#n" bị tách thành "# 1".
     */
    fun mask(english: String, names: List<String> = find(english)): Pair<String, List<String>> {
        var out = english
        names.forEachIndexed { i, n -> out = Regex("(?<!\\p{L})" + Regex.escape(n) + "(?!\\p{L})").replace(out, "QQ${i + 1}") }
        return out to names
    }

    /** Trả tên về chỗ dấu "QQn" (chấp nhận "qq1", "QQ 1"). Trả về (văn bản, các tên bị mất). */
    fun restore(translated: String, names: List<String>): Pair<String, List<String>> {
        var out = translated
        val lost = ArrayList<String>()
        names.forEachIndexed { i, n ->
            val re = Regex("(?i)qq\\s?${i + 1}(?!\\d)")
            if (re.containsMatchIn(out)) out = re.replace(out, Regex.escapeReplacement(n)) else lost += n
        }
        return out to lost
    }

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
