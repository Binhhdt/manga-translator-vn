package vn.mangatranslate.app.core.vision

import vn.mangatranslate.app.core.model.BoxF
import kotlin.math.max
import kotlin.math.min

/**
 * Tìm vùng nền trắng quanh một khối chữ (dùng khi bong bóng không khép kín nên [BubbleDetector] không bắt được).
 *
 * Pha 1 — hút phần chữ phụ sát bên (furigana, dấu câu): nới mỗi cạnh tối đa [absorb] px miễn dải mới không
 * tối hẳn (≥ 50% điểm sáng).
 * Pha 2 — nới tiếp khi dải mới gần như trắng hoàn toàn (≥ [brightRatio]); dừng ở viền bong bóng hoặc nét vẽ.
 * Tổng mức nới mỗi cạnh không quá [maxGrow] px.
 */
object WhiteSpaceExpander {

    fun expand(
        img: GrayImage,
        box: BoxF,
        absorb: Float,
        maxGrow: Float,
        brightThreshold: Int = 200,
        brightRatio: Float = 0.92f
    ): BoxF {
        var l = box.left.toInt().coerceIn(0, img.width - 1)
        var t = box.top.toInt().coerceIn(0, img.height - 1)
        var r = box.right.toInt().coerceIn(l + 1, img.width)
        var b = box.bottom.toInt().coerceIn(t + 1, img.height)
        val startL = l; val startT = t; val startR = r; val startB = b
        val step = max(1, (min(r - l, b - t) / 20))

        fun ratio(x0: Int, y0: Int, x1: Int, y1: Int): Float {
            if (x1 <= x0 || y1 <= y0) return 0f
            var bright = 0
            var total = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                total++
                if (img[x, y] >= brightThreshold) bright++
            }
            return bright.toFloat() / total
        }

        fun grow(limitFor: (Int) -> Float, need: Float) {
            var moved = true
            while (moved) {
                moved = false
                if (l - step >= 0 && startL - (l - step) <= limitFor(0) && ratio(l - step, t, l, b) >= need) { l -= step; moved = true }
                if (r + step <= img.width && (r + step) - startR <= limitFor(1) && ratio(r, t, r + step, b) >= need) { r += step; moved = true }
                if (t - step >= 0 && startT - (t - step) <= limitFor(2) && ratio(l, t - step, r, t) >= need) { t -= step; moved = true }
                if (b + step <= img.height && (b + step) - startB <= limitFor(3) && ratio(l, b, r, b + step) >= need) { b += step; moved = true }
            }
        }

        grow({ absorb }, 0.5f)
        grow({ maxGrow }, brightRatio)
        return BoxF(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
    }
}
