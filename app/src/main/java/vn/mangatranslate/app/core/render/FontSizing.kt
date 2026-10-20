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
        // Tối thiểu dễ đọc, nhưng chữ gốc nhỏ (chú thích cuối trang) được phép nhỏ hơn 1.6% bề rộng trang.
        val minSize = max(12f, min(imageWidth * 0.016f, max(sourceCharSize * 0.9f, 12f)))
        val preferred = max(sourceCharSize * 1.1f, imageWidth * 0.03f)
        val cap = min(imageWidth * 0.06f, max(minSize, areaHeight * 0.5f))
        val maxSize = max(minSize, min(preferred, cap))
        return Bounds(minSize, maxSize)
    }

    /**
     * Chế độ "lấp đầy bong bóng" (render bản dịch ChatGPT): không bám cỡ chữ Nhật gốc, chọn cỡ lớn nhất còn vừa
     * vùng đặt chữ, trần 5% bề rộng trang (tránh chữ to quá khổ), sàn 1.4% bề rộng trang (tối thiểu 12 px).
     */
    fun fillBounds(imageWidth: Float, areaHeight: Float): Bounds {
        val minSize = max(12f, imageWidth * 0.014f)
        val maxSize = max(minSize, min(imageWidth * 0.05f, areaHeight * 0.6f))
        return Bounds(minSize, maxSize)
    }
}
