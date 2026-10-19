package vn.mangatranslate.app.core.util

/** Chuẩn hóa văn bản trước và sau khi dịch. Thuần Kotlin, kiểm thử được trên JVM. */
object TextNormalizer {

    private val cjkScripts = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA
    )

    /** Ký tự Hán / Kana / dấu câu CJK toàn khổ — các ký tự này nối liền không cần dấu cách. */
    fun isCjk(ch: Char): Boolean {
        if (ch in '　'..'〿' || ch in '＀'..'￯') return true
        return try {
            Character.UnicodeScript.of(ch.code) in cjkScripts
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Nối các dòng OCR thành một câu: tiếng Trung/Nhật nối liền, chữ Latin/Hàn nối bằng dấu cách,
     * từ tiếng Anh bị ngắt bằng gạch nối cuối dòng được ghép lại.
     */
    fun joinLines(parts: List<String>): String {
        val sb = StringBuilder()
        for (raw in parts) {
            val p = raw.trim()
            if (p.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(p)
                continue
            }
            val last = sb[sb.length - 1]
            val first = p[0]
            when {
                last == '-' && sb.length >= 2 && sb[sb.length - 2].isLetter() && first.isLetter() -> {
                    sb.setLength(sb.length - 1)
                    sb.append(p)
                }
                isCjk(last) && isCjk(first) -> sb.append(p)
                else -> sb.append(' ').append(p)
            }
        }
        return sb.toString()
    }

    fun prepareForTranslation(text: String, langCode: String): String {
        var t = text.replace(Regex("\\s+"), " ").trim()
        when (langCode) {
            "en" -> t = fixAllCaps(t)
            "ja", "zh" -> t = removeSpacesBetweenCjk(t)
        }
        return t
    }

    private fun removeSpacesBetweenCjk(t: String): String {
        val sb = StringBuilder(t.length)
        for (i in t.indices) {
            val c = t[i]
            if (c == ' ' && i > 0 && i < t.length - 1 && isCjk(t[i - 1]) && isCjk(t[i + 1])) continue
            sb.append(c)
        }
        return sb.toString()
    }

    /**
     * Truyện tiếng Anh thường viết IN HOA toàn bộ; máy dịch xử lý câu in hoa kém (dễ hiểu nhầm là tên riêng).
     * Chuyển về dạng câu thường: "I WILL NEVER FORGIVE YOU!" → "I will never forgive you!".
     */
    fun fixAllCaps(t: String): String {
        val letters = t.filter { it.isLetter() }
        if (letters.length < 3) return t
        val upper = letters.count { it.isUpperCase() }
        if (upper < letters.length * 0.8f) return t
        val lower = t.lowercase()
        val sb = StringBuilder(lower.length)
        var capNext = true
        for (c in lower) {
            if (capNext && c.isLetter()) {
                sb.append(c.uppercaseChar())
                capNext = false
            } else {
                sb.append(c)
            }
            if (c == '.' || c == '!' || c == '?') capNext = true
        }
        return Regex("(?<![\\p{L}'’])i(?![\\p{L}])").replace(sb) { "I" }
    }

    /** Dọn bản dịch tiếng Việt: bỏ khoảng trắng thừa, bỏ dấu cách trước dấu câu, viết hoa chữ đầu. */
    fun cleanupVietnamese(t: String): String {
        var s = t.replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("\\s+([,.!?;:…])"), "$1")
        if (s.isNotEmpty() && s[0].isLowerCase()) s = s[0].uppercase() + s.substring(1)
        return s
    }
}
