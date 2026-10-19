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
