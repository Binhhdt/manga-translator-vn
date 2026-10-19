package vn.mangatranslate.app.core.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Chấm điểm các phương án OCR của cùng một vùng để chọn phương án tốt nhất.
 *
 * Không có "độ tin cậy" giả: điểm chỉ dựa trên dữ kiện kiểm chứng được —
 * số ký tự đúng loại chữ của ngôn ngữ nguồn, số ký tự lạ, và độ lệch so với số ô ký tự đếm được trên ảnh
 * (nếu đã tách cột). Phương án đọc lẫn furigana sẽ dài hơn số ô thật → bị trừ điểm.
 */
object OcrCandidateScorer {

    data class Stats(val valid: Int, val invalid: Int) {
        val total: Int get() = valid + invalid
    }

    private const val NEUTRAL = "!?！？…‥・.,、。「」『』()（）ー―〜~-—:;：；'\"“”‘’‼⁉※　 "

    fun isExpected(c: Char, lang: String): Boolean {
        if (c in NEUTRAL || c.isDigit()) return true
        val script = try { Character.UnicodeScript.of(c.code) } catch (e: IllegalArgumentException) { return false }
        return when (lang) {
            "ja" -> script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
                script == Character.UnicodeScript.KATAKANA || c in 'A'..'Z' || c in 'Ａ'..'Ｚ' ||
                c in '　'..'〿' || c in '＀'..'￯'
            "zh" -> script == Character.UnicodeScript.HAN || c in 'A'..'Z' || c in '　'..'〿' || c in '＀'..'￯'
            "ko" -> script == Character.UnicodeScript.HANGUL || c in 'A'..'Z'
            else -> script == Character.UnicodeScript.LATIN
        }
    }

    fun stats(text: String, lang: String): Stats {
        var v = 0
        var inv = 0
        for (c in text) {
            if (c.isWhitespace()) continue
            if (isExpected(c, lang)) v++ else inv++
        }
        return Stats(v, inv)
    }

    fun score(text: String, lang: String, expectedChars: Int?): Float {
        val s = stats(text, lang)
        if (s.total == 0) return Float.NEGATIVE_INFINITY
        var score = s.valid - 2f * s.invalid
        if (expectedChars != null && expectedChars > 0) {
            // Thừa ký tự (thường do đọc lẫn furigana) bị phạt nặng hơn thiếu ký tự.
            val extra = s.total - expectedChars
            score -= if (extra > 0) 1.5f * extra else 0.75f * abs(extra)
        }
        return score
    }

    /** Độ giống nhau 0..1 dựa trên khoảng cách Levenshtein (bỏ khoảng trắng). */
    fun similarity(a: String, b: String): Float {
        val x = a.filterNot { it.isWhitespace() }
        val y = b.filterNot { it.isWhitespace() }
        if (x.isEmpty() && y.isEmpty()) return 1f
        val dp = IntArray(y.length + 1) { it }
        for (i in 1..x.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..y.length) {
                val tmp = dp[j]
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + if (x[i - 1] == y[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return 1f - dp[y.length].toFloat() / max(x.length, y.length)
    }
}
