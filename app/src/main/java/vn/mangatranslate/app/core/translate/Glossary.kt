package vn.mangatranslate.app.core.translate

/**
 * Bảng thuật ngữ do người dùng nhập, mỗi dòng: `nguồn = đích` (dòng bắt đầu bằng # là chú thích).
 *
 * - Nguồn chứa chữ Hán/Kana/Hangul → thay TRƯỚC khi dịch (giữ tên riêng: `黒崎 = Kurosaki`,
 *   `神奈川 = Kanagawa`). Máy dịch giữ nguyên chữ Latin nên tên không bị dịch nghĩa thành "Blacklord".
 * - Nguồn là chữ Latin/tiếng Việt → sửa SAU khi dịch (`Blacklord = Kurosaki`, `người giữ cầu môn = thủ môn`).
 */
data class GlossaryEntry(val source: String, val target: String) {
    val beforeTranslation: Boolean
        get() = source.any { c ->
            val s = try { Character.UnicodeScript.of(c.code) } catch (e: IllegalArgumentException) { null }
            s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA ||
                s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL
        }
}

object Glossary {

    fun parse(text: String): List<GlossaryEntry> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val idx = line.indexOf('=').takeIf { it > 0 } ?: line.indexOf('→').takeIf { it > 0 } ?: return@mapNotNull null
            val src = line.substring(0, idx).trim()
            val dst = line.substring(idx + 1).trim()
            if (src.isEmpty() || dst.isEmpty()) null else GlossaryEntry(src, dst)
        }
        .sortedByDescending { it.source.length } // cụm dài thay trước

    /**
     * Tên 47 tỉnh của Nhật (chữ Hán → romaji) dùng để KIỂM TRA sau khi dịch: tên có trong câu gốc mà không có trong
     * bản dịch → "Cần kiểm tra" (đo thật: 鹿児島 → "deer islands"). Không thay trước khi dịch vì đo thật cho thấy
     * Opus-MT viết sai romaji khi nhận chữ La-tinh ("Khangawa", "Kagoshama"). Bỏ 大分 ("đáng kể"), 三重 ("ba lớp").
     */
    val BUILTIN_JA_PLACES: List<GlossaryEntry> = listOf(
        "北海道" to "Hokkaido", "青森" to "Aomori", "岩手" to "Iwate", "宮城" to "Miyagi", "秋田" to "Akita",
        "山形" to "Yamagata", "福島" to "Fukushima", "茨城" to "Ibaraki", "栃木" to "Tochigi", "群馬" to "Gunma",
        "埼玉" to "Saitama", "千葉" to "Chiba", "東京" to "Tokyo", "神奈川" to "Kanagawa", "新潟" to "Niigata",
        "富山" to "Toyama", "石川" to "Ishikawa", "福井" to "Fukui", "山梨" to "Yamanashi", "長野" to "Nagano",
        "岐阜" to "Gifu", "静岡" to "Shizuoka", "愛知" to "Aichi", "滋賀" to "Shiga", "京都" to "Kyoto",
        "大阪" to "Osaka", "兵庫" to "Hyogo", "奈良" to "Nara", "和歌山" to "Wakayama", "鳥取" to "Tottori",
        "島根" to "Shimane", "岡山" to "Okayama", "広島" to "Hiroshima", "山口" to "Yamaguchi", "徳島" to "Tokushima",
        "香川" to "Kagawa", "愛媛" to "Ehime", "高知" to "Kochi", "福岡" to "Fukuoka", "佐賀" to "Saga",
        "長崎" to "Nagasaki", "熊本" to "Kumamoto", "宮崎" to "Miyazaki", "鹿児島" to "Kagoshima", "沖縄" to "Okinawa"
    ).map { (a, b) -> GlossaryEntry(a, b) }.sortedByDescending { it.source.length }

    fun applyBefore(text: String, entries: List<GlossaryEntry>): String {
        var out = text
        for (e in entries) if (e.beforeTranslation) out = out.replace(e.source, e.target)
        return out
    }

    fun applyAfter(text: String, entries: List<GlossaryEntry>): String {
        var out = text
        for (e in entries) {
            if (e.beforeTranslation) continue
            val re = Regex("(?<!\\p{L})" + Regex.escape(e.source) + "(?!\\p{L})", RegexOption.IGNORE_CASE)
            out = re.replace(out) { e.target }
        }
        return out
    }

    /** Thuật ngữ có trong câu gốc nhưng không thấy trong bản dịch → cần kiểm tra. */
    fun missingTargets(source: String, translated: String, entries: List<GlossaryEntry>): List<String> =
        entries.filter { it.beforeTranslation && source.contains(it.source) && !translated.contains(it.target, ignoreCase = true) }
            .map { "${it.source} → ${it.target}" }
}
