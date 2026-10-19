package vn.mangatranslate.app.core.ocr

import vn.mangatranslate.app.core.model.OcrLine
import kotlin.math.max
import kotlin.math.min

/**
 * Loại FURIGANA (chữ kana nhỏ chú cách đọc bên cạnh chữ Hán) khỏi kết quả OCR theo dòng.
 *
 * Lỗi thực tế: ML Kit đọc furigana thành dòng riêng ("くろさき", "きゅうきゅうはん"…) rồi ghép vào câu hoặc
 * tạo vùng thoại riêng → bản dịch vô nghĩa ("Ou", "ucking", "Otetsu Kurosaki").
 *
 * Một dòng bị coi là furigana khi: chỉ gồm kana, mỏng hơn hẳn dòng chính (< [ratio] × độ dày dòng chính),
 * và nằm sát cạnh một dòng chính lớn hơn (chồng lấn theo chiều dọc với chữ dọc / chiều ngang với chữ ngang).
 */
object RubyFilter {

    fun isKana(c: Char): Boolean = c in '぀'..'ゟ' || c in '゠'..'ヿ' || c == 'ー' || c == '・'

    fun isKanaOnly(s: String): Boolean {
        val t = s.filterNot { it.isWhitespace() }
        return t.isNotEmpty() && t.all { isKana(it) }
    }

    private fun thickness(l: OcrLine): Float = min(l.box.width, l.box.height)

    fun filter(lines: List<OcrLine>, ratio: Float = 0.62f): List<OcrLine> {
        if (lines.size < 2) return lines
        return lines.filterNot { cand -> isRuby(cand, lines, ratio) }
    }

    fun isRuby(cand: OcrLine, all: List<OcrLine>, ratio: Float = 0.62f): Boolean {
        if (!isKanaOnly(cand.text)) return false
        val tc = thickness(cand)
        return all.any { main ->
            if (main === cand) return@any false
            val tm = thickness(main)
            if (tc > ratio * tm) return@any false
            val a = cand.box
            val b = main.box
            val gapX = max(0f, max(a.left, b.left) - min(a.right, b.right))
            val gapY = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))
            val overlapX = min(a.right, b.right) - max(a.left, b.left)
            val overlapY = min(a.bottom, b.bottom) - max(a.top, b.top)
            val besideVertical = gapX <= 0.6f * tm && overlapY > 0.3f * min(a.height, b.height)
            val besideHorizontal = gapY <= 0.6f * tm && overlapX > 0.3f * min(a.width, b.width)
            besideVertical || besideHorizontal
        }
    }
}
