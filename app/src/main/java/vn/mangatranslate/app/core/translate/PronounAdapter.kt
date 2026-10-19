package vn.mangatranslate.app.core.translate

import vn.mangatranslate.app.core.model.PronounStyle

/**
 * Đổi xưng hô trong bản dịch máy theo kiểu người dùng chọn.
 *
 * ML Kit dịch offline gần như luôn trả về "tôi / bạn / chúng tôi / các bạn". Lớp này thay các đại từ đó
 * bằng quy tắc theo ranh giới từ. Đây là thay thế CƠ HỌC, không hiểu ai đang nói với ai, nên có thể sai
 * (ví dụ "bạn" nghĩa là "bạn bè"). Một số cụm thường gặp như "bạn bè", "người bạn" được loại trừ.
 */
object PronounAdapter {

    private const val NOT_LETTER_BEFORE = "(?<!\\p{L})"
    private const val NOT_LETTER_AFTER = "(?!\\p{L})"

    private fun word(w: String) = Regex(NOT_LETTER_BEFORE + Regex.escape(w) + NOT_LETTER_AFTER, RegexOption.IGNORE_CASE)

    private val CHUNG_TOI = word("chúng tôi")
    private val CAC_BAN = word("các bạn")
    private val TOI = word("tôi")

    /** "bạn" là đại từ ngôi 2, trừ các cụm mang nghĩa "bạn bè". */
    private val BAN = Regex(
        NOT_LETTER_BEFORE +
            "(?<!người )(?<!những )(?<!các )(?<!một )" +
            "bạn" +
            "(?! bè)(?! thân)(?! học)(?! cùng)(?! đời)(?! trai)(?! gái)" +
            NOT_LETTER_AFTER,
        RegexOption.IGNORE_CASE
    )

    private fun rules(style: PronounStyle): List<Pair<Regex, String>> = when (style) {
        PronounStyle.KEEP -> emptyList()
        PronounStyle.TA_NGUOI -> listOf(CHUNG_TOI to "chúng ta", CAC_BAN to "các ngươi", TOI to "ta", BAN to "ngươi")
        PronounStyle.TOI_CAU -> listOf(CAC_BAN to "các cậu", BAN to "cậu")
        PronounStyle.ANH_EM -> listOf(CHUNG_TOI to "bọn anh", CAC_BAN to "các em", TOI to "anh", BAN to "em")
        PronounStyle.HUYNH_MUOI -> listOf(CHUNG_TOI to "chúng ta", CAC_BAN to "các muội", TOI to "huynh", BAN to "muội")
        PronounStyle.BON_TOA -> listOf(CHUNG_TOI to "chúng ta", CAC_BAN to "các ngươi", TOI to "bổn tọa", BAN to "ngươi")
    }

    fun apply(text: String, style: PronounStyle): String {
        var out = text
        for ((regex, target) in rules(style)) {
            out = regex.replace(out) { m -> matchCase(m.value, target) }
        }
        return out
    }

    private fun matchCase(source: String, target: String): String {
        if (source.isEmpty() || !source[0].isUpperCase()) return target
        val allUpper = source.length > 1 && source.all { !it.isLetter() || it.isUpperCase() }
        return if (allUpper) target.uppercase() else target.replaceFirstChar { it.uppercase() }
    }
}
