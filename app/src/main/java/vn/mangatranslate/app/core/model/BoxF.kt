package vn.mangatranslate.app.core.model

import kotlin.math.max
import kotlin.math.min

/**
 * Hình chữ nhật theo tọa độ pixel của ẢNH GỐC (không phải tọa độ màn hình).
 * Lớp thuần Kotlin để có thể kiểm thử trên JVM.
 */
data class BoxF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = max(0f, width) * max(0f, height)

    fun union(other: BoxF): BoxF = BoxF(
        min(left, other.left), min(top, other.top),
        max(right, other.right), max(bottom, other.bottom)
    )

    fun intersects(other: BoxF): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun intersectionArea(other: BoxF): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        return if (w > 0f && h > 0f) w * h else 0f
    }

    /** Mở rộng (giá trị âm = thu nhỏ). */
    fun expand(dx: Float, dy: Float = dx): BoxF = BoxF(left - dx, top - dy, right + dx, bottom + dy)

    fun clampTo(maxWidth: Float, maxHeight: Float): BoxF = BoxF(
        left.coerceIn(0f, maxWidth), top.coerceIn(0f, maxHeight),
        right.coerceIn(0f, maxWidth), bottom.coerceIn(0f, maxHeight)
    )

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    companion object {
        fun of(x1: Float, y1: Float, x2: Float, y2: Float): BoxF =
            BoxF(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))

        fun unionOf(boxes: List<BoxF>): BoxF = boxes.reduce { acc, b -> acc.union(b) }
    }
}
