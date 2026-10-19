package vn.mangatranslate.app.core.render

import kotlin.math.max
import kotlin.math.min

/**
 * Giới hạn cỡ chữ Việt (px ảnh gốc). Tách riêng để kiểm thử trên JVM.
 *
 * Lỗi cũ: cỡ tối đa = cỡ dòng chữ gốc × 1.15 — với chữ dọc/furigana, "cỡ dòng" có thể chỉ vài px nên chữ Việt
 * bị vẽ tí hon ("Kurosaki!", "Blacklord" trong ảnh thử). Nay cỡ tối thiểu theo bề rộng trang để luôn đọc được,
 * cỡ tối đa không phụ thuộc chặt vào cỡ chữ gốc.
 */
object FontSizing {

    data class Bounds(val min: Float, val max: Float)

    fun bounds(imageWidth: Float, sourceCharSize: Float, areaHeight: Float): Bounds {
        val minSize = max(11f, imageWidth * 0.016f)
        val preferred = max(sourceCharSize * 1.1f, imageWidth * 0.03f)
        val cap = min(imageWidth * 0.06f, max(minSize, areaHeight * 0.5f))
        val maxSize = max(minSize, min(preferred, cap))
        return Bounds(minSize, maxSize)
    }
}
