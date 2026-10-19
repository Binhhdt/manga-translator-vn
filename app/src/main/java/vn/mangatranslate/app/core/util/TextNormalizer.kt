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
            "ja", "zh" -> {
                // Chữ/số toàn khổ (ＧＫ, ！！) → dạng thường để máy dịch hiểu.
                t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFKC)
                t = removeSpacesBetweenCjk(t)
                t = normalizeEllipsis(t)
                // Viết tắt (GK, FW…) KHÔNG đổi ở đây: đo thật cho thấy Opus-MT dịch "GK" đúng nhưng dịch
                // "ゴールキーパー" thành "Gorkeyper". TranslationEngine xử lý viết tắt theo từng bộ dịch.
                if (langCode == "ja") t = katakanaEmphasisToKanji(t)
            }
        }
        return t
    }

    /**
     * Viết tắt vị trí/tình huống bóng đá trong truyện Nhật (GK, FW…) được máy dịch giữ nguyên hoặc hiểu sai.
     * Đổi sang dạng katakana đầy đủ — ML Kit dịch đúng các từ này (ゴールキーパー → thủ môn).
     * Chỉ thay khi là từ đứng riêng (không nằm trong từ Latin dài hơn).
     */
    private val SPORTS_ABBR = mapOf(
        "GK" to "ゴールキーパー",
        "FW" to "フォワード",
        "MF" to "ミッドフィールダー",
        "DF" to "ディフェンダー",
        "PK" to "ペナルティキック",
        "FK" to "フリーキック",
        "CK" to "コーナーキック"
    )

    /**
     * Dấu "…" và gạch "――" viết dọc thường bị OCR đọc thành ":", "：", "︙", "|", "・・・".
     * Máy dịch giữ nguyên các ký tự này → bản dịch có "::" vô nghĩa ("Xin vui lòng::").
     * Văn bản Nhật/Trung trong truyện gần như không dùng dấu hai chấm nên đổi chúng về dấu chuẩn.
     */
    fun normalizeEllipsis(t: String): String {
        var s = t
        s = s.replace(Regex("[︙︰⋮]+"), "…")
        s = s.replace(Regex("[:：]{1,}"), "…")
        s = s.replace(Regex("(?:[・･.。]\\s*){2,}"), "…")
        s = s.replace(Regex("[‥]+"), "…")
        s = s.replace(Regex("[|｜丨]+"), "―")
        s = s.replace(Regex("…{2,}"), "…")
        return s
    }

    /**
     * Truyện Nhật hay viết từ thường bằng katakana để nhấn mạnh (ブザマ thay vì 無様). Máy dịch coi katakana là
     * từ mượn/tên riêng và phiên âm ("buzzma"). Đổi một số từ phổ biến về dạng chữ Hán/hiragana để dịch đúng nghĩa.
     * Chỉ thay khi là từ đứng riêng (không nằm trong một từ katakana dài hơn, ví dụ ダメージ giữ nguyên).
     */
    private val KATAKANA_EMPHASIS = linkedMapOf(
        "ブザマ" to "無様", "バカ" to "馬鹿", "ムダ" to "無駄", "ダメ" to "駄目", "ヤツ" to "奴",
        "ケンカ" to "喧嘩", "スゴイ" to "凄い", "ヒドイ" to "酷い", "ウソ" to "嘘", "ホント" to "本当",
        "キサマ" to "貴様", "オレ" to "俺", "オマエ" to "お前", "テメェ" to "てめえ", "アイツ" to "あいつ",
        "コイツ" to "こいつ", "ソイツ" to "そいつ", "ゴメン" to "ごめん", "クソ" to "くそ", "オイ" to "おい",
        "オーイ" to "おーい", "ヤバイ" to "やばい", "スミマセン" to "すみません"
    )

    fun katakanaEmphasisToKanji(t: String): String {
        var out = t
        for ((kata, plain) in KATAKANA_EMPHASIS.entries.sortedByDescending { it.key.length }) {
            out = Regex("(?<![\\p{IsKatakana}ー])" + Regex.escape(kata) + "(?![\\p{IsKatakana}ー])").replace(out, plain)
        }
        return out
    }

    /** Viết tắt bóng đá trong câu TIẾNG ANH trung gian → từ đầy đủ để bước Anh→Việt dịch đúng ("GK" → "goalkeeper"). */
    private val ENGLISH_SPORTS = mapOf(
        "GK" to "goalkeeper", "FW" to "forward", "MF" to "midfielder", "DF" to "defender",
        "PK" to "penalty kick", "FK" to "free kick", "CK" to "corner kick"
    )

    fun expandEnglishSportsAbbreviations(t: String): String {
        var out = t
        for ((abbr, full) in ENGLISH_SPORTS) out = Regex("(?<![A-Za-z])$abbr(?![A-Za-z])").replace(out, full)
        return out
    }

    fun expandSportsAbbreviations(t: String): String {
        var out = t
        for ((abbr, full) in SPORTS_ABBR) {
            out = Regex("(?<![A-Za-z])$abbr(?![A-Za-z])").replace(out, full)
        }
        return out
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
