package vn.mangatranslate.app.core.vision

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sắp xếp lại chữ dọc thành chữ ngang để OCR dễ đọc hơn — KHÔNG xoay cả ảnh (xoay làm ký tự nằm nghiêng).
 *
 * Mỗi cột chính (phải → trái) trở thành một hàng ngang; các ô ký tự được đặt từ trái sang phải, giữ nguyên
 * chiều đứng của ký tự. Riêng nét gạch dọc dài (ー, ―) ở dạng chữ dọc được xoay 90° thành gạch ngang.
 * Furigana bị bỏ vì chỉ lấy điểm ảnh trong phạm vi cột chính.
 *
 * Kết quả chỉ là MỘT phương án; PageTranslator so sánh nó với cách đọc trực tiếp và chỉ dùng khi kết quả
 * được chấm điểm tốt hơn.
 */
object VerticalComposer {

    fun compose(gray: GrayImage, layout: TextLayoutAnalyzer.Layout): GrayImage? {
        val columns = layout.mainColumns
        if (columns.isEmpty()) return null
        val cs = max(4, layout.charSize.roundToInt())
        val gap = max(2, (cs * 0.15f).roundToInt())
        val rowGap = max(4, (cs * 0.8f).roundToInt())
        val margin = cs

        val rows = columns.map { col ->
            col.cells.map { cell ->
                val piece = crop(gray, col.left, cell.top, col.right, cell.bottom)
                if (shouldRotate(cell, cs)) rotateClockwise(piece) else piece
            }
        }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return null

        val rowWidths = rows.map { r -> r.sumOf { it.width } + gap * (r.size - 1) }
        val rowHeights = rows.map { r -> r.maxOf { it.height } }
        val outW = margin * 2 + rowWidths.max()
        val outH = margin * 2 + rowHeights.sum() + rowGap * (rows.size - 1)
        val out = GrayImage.blank(outW, outH)

        var y = margin
        rows.forEachIndexed { i, r ->
            var x = margin
            for (piece in r) {
                paste(out, piece, x, y + (rowHeights[i] - piece.height) / 2)
                x += piece.width + gap
            }
            y += rowHeights[i] + rowGap
        }
        return out
    }

    /** Gạch dọc dài, mảnh, liền một nét → dạng dọc của ー / ―. Dấu "!" có 2 mảnh nên không bị xoay. */
    internal fun shouldRotate(cell: TextLayoutAnalyzer.Cell, cs: Int): Boolean =
        cell.pieces == 1 && cell.height >= 1.5f * cell.width && cell.width <= 0.45f * cs

    internal fun crop(src: GrayImage, l: Int, t: Int, r: Int, b: Int): GrayImage {
        val w = max(1, r - l)
        val h = max(1, b - t)
        val out = GrayImage.blank(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = l + x
            val sy = t + y
            if (sx in 0 until src.width && sy in 0 until src.height) out[x, y] = src[sx, sy]
        }
        return out
    }

    /** Xoay 90° theo chiều kim đồng hồ. */
    internal fun rotateClockwise(src: GrayImage): GrayImage {
        val out = GrayImage.blank(src.height, src.width)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            out[src.height - 1 - y, x] = src[x, y]
        }
        return out
    }

    private fun paste(dst: GrayImage, src: GrayImage, ox: Int, oy: Int) {
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val dx = ox + x
            val dy = oy + y
            if (dx in 0 until dst.width && dy in 0 until dst.height) dst[dx, dy] = src[x, y]
        }
    }
}
