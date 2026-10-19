package vn.mangatranslate.app.core.vision

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Phân tích bố cục chữ DỌC trong một vùng (thường là một bong bóng) từ mặt nạ mực:
 *
 * - Chiếu theo trục x để tách các cột chữ.
 * - Cột hẹp hơn hẳn cột chính (< [rubyWidthRatio] × cỡ chữ) và nằm sát cột chính là FURIGANA (chữ chú cách đọc)
 *   → đánh dấu để bỏ qua, không để lẫn vào câu.
 * - Trong mỗi cột chính, chiếu theo trục y để tách từng ký tự; các mảnh nhỏ (ví dụ 二, 三, dấu ︙) được gộp
 *   lại nếu tổng chiều cao vẫn ≈ một ô chữ.
 * - Thứ tự đọc: cột từ PHẢI sang TRÁI, ký tự từ TRÊN xuống DƯỚI.
 */
class TextLayoutAnalyzer(private val rubyWidthRatio: Float = 0.62f) {

    /** Một ô ký tự; tọa độ là khung mực (right/bottom loại trừ). [pieces] = số mảnh đã gộp. */
    class Cell(val left: Int, val top: Int, val right: Int, val bottom: Int, val pieces: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    class Column(val left: Int, val right: Int, val top: Int, val bottom: Int, val ruby: Boolean, val cells: List<Cell>) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    class Layout(val columns: List<Column>, val charSize: Float) {
        /** Cột chính theo thứ tự đọc (phải → trái). */
        val mainColumns: List<Column> get() = columns.filter { !it.ruby }
        val rubyColumns: List<Column> get() = columns.filter { it.ruby }
        val expectedChars: Int get() = mainColumns.sumOf { it.cells.size }
    }

    fun analyzeVertical(mask: BooleanArray, w: Int, h: Int): Layout? {
        val colInk = IntArray(w)
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x]) colInk[x]++

        data class Run(val a: Int, val b: Int, val top: Int, val bottom: Int, val ink: Int)

        val runs = ArrayList<Run>()
        var x = 0
        while (x < w) {
            if (colInk[x] == 0) { x++; continue }
            val a = x
            var ink = 0
            while (x < w && colInk[x] > 0) { ink += colInk[x]; x++ }
            var top = h
            var bottom = -1
            for (y in 0 until h) for (xx in a until x) if (mask[y * w + xx]) {
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
            if (ink >= 3) runs += Run(a, x, top, bottom + 1, ink)
        }
        if (runs.isEmpty()) return null

        val widths = runs.map { it.b - it.a }.sorted()
        val ref = widths[((widths.size - 1) * 0.75f).roundToInt()].toFloat()

        val columns = runs.map { r ->
            val width = r.b - r.a
            val narrow = width < rubyWidthRatio * ref
            val besideMain = narrow && runs.any { m ->
                m !== r && (m.b - m.a) >= rubyWidthRatio * ref &&
                    gap(r.a, r.b, m.a, m.b) <= 0.8f * ref &&
                    overlap(r.top, r.bottom, m.top, m.bottom) > 0
            }
            val cells = if (besideMain) emptyList() else cellsOf(mask, w, r.a, r.b, r.top, r.bottom, max(ref, width.toFloat()))
            Column(r.a, r.b, r.top, r.bottom, besideMain, cells)
        }.sortedByDescending { it.left }

        if (columns.none { !it.ruby }) return null
        return Layout(columns, ref)
    }

    private fun cellsOf(mask: BooleanArray, w: Int, l: Int, r: Int, top: Int, bottom: Int, cs: Float): List<Cell> {
        // Tách các mảnh theo hàng có mực.
        val pieces = ArrayList<Cell>()
        var y = top
        while (y < bottom) {
            if (!rowHasInk(mask, w, l, r, y)) { y++; continue }
            val a = y
            var minX = r
            var maxX = l - 1
            while (y < bottom && rowHasInk(mask, w, l, r, y)) {
                for (xx in l until r) if (mask[y * w + xx]) {
                    if (xx < minX) minX = xx
                    if (xx > maxX) maxX = xx
                }
                y++
            }
            pieces += Cell(minX, a, maxX + 1, y, 1)
        }
        if (pieces.isEmpty()) return pieces

        // Gộp mảnh nhỏ thành ô ký tự.
        val merged = ArrayList<Cell>()
        var cur = pieces[0]
        for (i in 1 until pieces.size) {
            val p = pieces[i]
            val combined = p.bottom - cur.top
            val small = cur.height < 0.6f * cs || p.height < 0.35f * cs
            cur = if (small && combined <= 1.15f * cs) {
                Cell(minOf(cur.left, p.left), cur.top, maxOf(cur.right, p.right), p.bottom, cur.pieces + p.pieces)
            } else {
                merged += cur
                p
            }
        }
        merged += cur
        return merged
    }

    private fun rowHasInk(mask: BooleanArray, w: Int, l: Int, r: Int, y: Int): Boolean {
        val base = y * w
        for (x in l until r) if (mask[base + x]) return true
        return false
    }

    private fun gap(a1: Int, b1: Int, a2: Int, b2: Int): Int = max(0, max(a1, a2) - minOf(b1, b2))
    private fun overlap(a1: Int, b1: Int, a2: Int, b2: Int): Int = minOf(b1, b2) - max(a1, a2)
}
