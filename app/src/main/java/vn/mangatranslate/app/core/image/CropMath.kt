package vn.mangatranslate.app.core.image

import vn.mangatranslate.app.core.model.BoxF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tính toán vùng cắt và kích thước ảnh đưa vào ML Kit. Thuần Kotlin để kiểm thử trên JVM.
 *
 * Nguyên nhân lỗi "InputImage width and height should be at least 32!": ML Kit Text Recognition từ chối ảnh có
 * chiều rộng hoặc chiều cao < 32 px. Các vùng cắt nhỏ (bong bóng "!!", cột chữ dọc mảnh, ảnh tách cột) lọt
 * xuống ML Kit mà không được kiểm tra.
 */
object CropMath {

    /** Kích thước tối thiểu ML Kit chấp nhận. */
    const val MLKIT_MIN_SIDE = 32

    /** Cạnh dài nhất cho phép sau khi phóng (tránh tốn RAM). */
    const val MAX_SIDE = 4096

    data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * Vùng cắt hợp lệ trong ảnh [imageW]×[imageH], hoặc null nếu không thể cắt.
     * - Loại tọa độ NaN/vô cực, vùng nằm hoàn toàn ngoài ảnh.
     * - Kẹp tọa độ âm / vượt mép về trong ảnh.
     * - Vùng hẹp hơn [minSize] (kể cả rộng 0) được NỚI đều quanh tâm, trượt vào trong nếu chạm mép ảnh.
     *   Nếu chính ảnh nhỏ hơn [minSize] thì lấy toàn bộ chiều đó (phần còn thiếu sẽ được đệm ở [inputPlan]).
     */
    fun safeCrop(box: BoxF, imageW: Int, imageH: Int, minSize: Int = MLKIT_MIN_SIDE): IntRect? {
        if (imageW <= 0 || imageH <= 0) return null
        val vals = floatArrayOf(box.left, box.top, box.right, box.bottom)
        if (vals.any { it.isNaN() || it.isInfinite() }) return null
        val x0 = min(box.left, box.right)
        val x1 = max(box.left, box.right)
        val y0 = min(box.top, box.bottom)
        val y1 = max(box.top, box.bottom)
        // Hoàn toàn ngoài ảnh.
        if (x1 < 0f || y1 < 0f || x0 > imageW || y0 > imageH) return null

        val (l, r) = fitRange(floor(x0).toInt(), ceil(x1).toInt(), imageW, minSize)
        val (t, b) = fitRange(floor(y0).toInt(), ceil(y1).toInt(), imageH, minSize)
        if (r - l < 1 || b - t < 1) return null
        return IntRect(l, t, r, b)
    }

    private fun fitRange(a0: Int, b0: Int, limit: Int, minSize: Int): Pair<Int, Int> {
        var a = a0.coerceIn(0, limit)
        var b = b0.coerceIn(0, limit)
        val need = min(minSize, limit)
        if (b - a < need) {
            val center = (a0 + b0) / 2.0
            a = (center - need / 2.0).roundToInt()
            b = a + need
            if (a < 0) { b -= a; a = 0 }
            if (b > limit) { a -= b - limit; b = limit }
            a = a.coerceAtLeast(0)
        }
        return a to b
    }

    /**
     * Kế hoạch chuẩn bị ảnh cho ML Kit: phóng theo [desiredScale] (giữ tỉ lệ), giới hạn cạnh dài ≤ [maxSide];
     * nếu cạnh ngắn vẫn < [minSide] thì phóng thêm (nếu không làm cạnh dài vượt giới hạn), phần còn thiếu
     * được ĐỆM nền trắng — không kéo méo chữ.
     */
    data class InputPlan(
        val scale: Float,
        val scaledW: Int,
        val scaledH: Int,
        val padX: Int,
        val padY: Int
    ) {
        val outW: Int get() = scaledW + 2 * padX
        val outH: Int get() = scaledH + 2 * padY
        val needsWork: Boolean get() = scale != 1f || padX > 0 || padY > 0
    }

    fun inputPlan(w: Int, h: Int, desiredScale: Float, minSide: Int = MLKIT_MIN_SIDE, maxSide: Int = MAX_SIDE): InputPlan {
        require(w > 0 && h > 0) { "Ảnh rỗng ${w}x$h" }
        var s = if (desiredScale.isNaN() || desiredScale <= 0f) 1f else desiredScale
        val longSide = max(w, h)
        // Chỉ giới hạn khi PHÓNG TO (ảnh trang lớn giữ nguyên như trước, ML Kit tự xử lý).
        if (s > 1f && longSide * s > maxSide) s = max(1f, maxSide.toFloat() / longSide)
        val shortSide = min(w, h)
        if (shortSide * s < minSide) {
            val need = minSide.toFloat() / shortSide
            if (longSide * need <= maxSide) s = need
        }
        if (abs1(s - 1f) < 0.01f) s = 1f
        val sw = max(1, (w * s).roundToInt())
        val sh = max(1, (h * s).roundToInt())
        val padX = if (sw < minSide) ceilDiv(minSide - sw, 2) else 0
        val padY = if (sh < minSide) ceilDiv(minSide - sh, 2) else 0
        return InputPlan(s, sw, sh, padX, padY)
    }

    private fun abs1(v: Float) = if (v < 0) -v else v
    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b
}
