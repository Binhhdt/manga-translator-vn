package vn.mangatranslate.app.core.translate

import vn.mangatranslate.app.core.ocr.OcrCandidateScorer

/**
 * Phát hiện dấu hiệu bản dịch/OCR có vấn đề để gắn nhãn "Cần kiểm tra". Chỉ dựa trên dấu hiệu kiểm chứng được,
 * KHÔNG tạo điểm tin cậy giả. Bản dịch đọc trôi chảy không có nghĩa OCR đã đúng — nên lỗi OCR được kiểm riêng.
 */
object QualityChecker {

    /** Âm tiết tiếng Việt viết không dấu (ví dụ "cho", "anh", "khong") — để không nhầm với từ lạ. */
    private val VI_SYLLABLE = Regex(
        "^(ngh|ng|nh|ch|gh|gi|kh|ph|qu|th|tr|[bcdghklmnprstvx])?" +
            "(uye|uya|uyu|uoi|uou|oai|oay|oao|oeo|uay|uau|ieu|yeu|oa|oe|uy|ua|uo|ia|ie|ye|ai|ao|au|ay|eo|eu|iu|oi|ui|uu|ya|ue|ou|[aeiouy])" +
            "(ch|ng|nh|[cmnpt])?$"
    )
    private val WORD = Regex("[\\p{L}]+")
    private val ALLOWED_FOREIGN = setOf("ok", "tv", "gk", "fw", "mf", "df", "pk", "fk", "ck")

    fun isVietnameseWord(w: String): Boolean {
        val lower = w.lowercase()
        if (lower.any { it !in 'a'..'z' }) return true // có dấu tiếng Việt → chắc chắn là chữ Việt
        return VI_SYLLABLE.matches(lower)
    }

    /** Dấu hiệu OCR sai, kiểm trên văn bản gốc. */
    fun ocrIssues(text: String, lang: String): List<String> {
        val issues = ArrayList<String>()
        val t = text.filterNot { it.isWhitespace() }
        if (t.isEmpty()) return listOf("Không đọc được chữ")
        val st = OcrCandidateScorer.stats(t, lang)
        if (st.invalid > 0) {
            val odd = t.filterNot { OcrCandidateScorer.isExpected(it, lang) }.toSet().joinToString("")
            issues += "OCR có ký tự lạ với ${langName(lang)}: $odd"
        }
        if ((lang == "ja" || lang == "zh") && t.count { it.isLetter() } == 1) {
            issues += "Chỉ đọc được 1 chữ — có thể OCR thiếu"
        }
        return issues
    }

    /** Dấu hiệu bản dịch có vấn đề. Trả về (cần kiểm tra, thông tin thêm). */
    fun translationIssues(
        source: String,
        translated: String,
        glossaryMissing: List<String>,
        glossaryTargets: Collection<String>
    ): Pair<List<String>, List<String>> {
        val review = ArrayList<String>()
        val info = ArrayList<String>()
        if (translated.isBlank()) {
            review += "Chưa có bản dịch"
            return review to info
        }
        val untranslated = translated.filter { c ->
            val s = try { Character.UnicodeScript.of(c.code) } catch (e: IllegalArgumentException) { null }
            s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA ||
                s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL
        }
        if (untranslated.isNotEmpty()) review += "Bản dịch còn chữ chưa dịch: ${untranslated.take(12)}"

        val sourceLower = source.lowercase()
        val targetsLower = glossaryTargets.flatMap { WORD.findAll(it.lowercase()).map { m -> m.value } }.toSet()
        val strange = LinkedHashSet<String>()
        val names = LinkedHashSet<String>()
        for (m in WORD.findAll(translated)) {
            val w = m.value
            val lower = w.lowercase()
            if (w.length < 2 || isVietnameseWord(w) || lower in ALLOWED_FOREIGN) continue
            if (sourceLower.contains(lower) || lower in targetsLower) continue
            if (w[0].isUpperCase()) names += w else strange += w
        }
        if (strange.isNotEmpty()) review += "Bản dịch có từ lạ, có thể do OCR sai: ${strange.joinToString(", ")}"
        if (names.isNotEmpty()) info += "Tên riêng/từ nước ngoài: ${names.joinToString(", ")} — nếu sai, thêm vào bảng thuật ngữ"
        if (glossaryMissing.isNotEmpty()) review += "Thuật ngữ có thể chưa được giữ: ${glossaryMissing.joinToString("; ")}"
        return review to info
    }

    private fun langName(code: String) = when (code) {
        "ja" -> "tiếng Nhật"
        "zh" -> "tiếng Trung"
        "ko" -> "tiếng Hàn"
        else -> "tiếng Anh"
    }
}
