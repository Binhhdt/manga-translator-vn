package vn.mangatranslate.app.core.vision

/**
 * Ảnh xám 8-bit (0 = đen, 255 = trắng) dạng mảng thuần Kotlin.
 * Mọi thuật toán xử lý ảnh (tìm bong bóng, tách cột chữ) chạy trên lớp này để kiểm thử được trên JVM.
 */
class GrayImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Kích thước ảnh không hợp lệ" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    operator fun set(x: Int, y: Int, value: Int) {
        pixels[y * width + x] = value
    }

    /** Mặt nạ "mực": điểm tối hơn [threshold]. */
    fun inkMask(threshold: Int = INK_THRESHOLD): BooleanArray = BooleanArray(pixels.size) { pixels[it] < threshold }

    companion object {
        const val INK_THRESHOLD = 150

        fun blank(width: Int, height: Int, value: Int = 255) = GrayImage(width, height, IntArray(width * height) { value })
    }
}
