package vn.mangatranslate.app

import vn.mangatranslate.app.core.vision.GrayImage

/** Vẽ trang truyện giả lập để kiểm thử thuật toán ảnh trên JVM. */
class SyntheticPage(w: Int, h: Int, background: Int = 255) {
    val img = GrayImage.blank(w, h, background)

    fun fillRect(l: Int, t: Int, r: Int, b: Int, v: Int = 0) {
        for (y in t until b) for (x in l until r) if (x in 0 until img.width && y in 0 until img.height) img[x, y] = v
    }

    /** Elip có viền đen dày [stroke], ruột trắng. */
    fun bubble(cx: Int, cy: Int, rx: Int, ry: Int, stroke: Int = 3) {
        for (y in cy - ry - stroke..cy + ry + stroke) for (x in cx - rx - stroke..cx + rx + stroke) {
            if (x !in 0 until img.width || y !in 0 until img.height) continue
            val dx = (x - cx).toDouble()
            val dy = (y - cy).toDouble()
            val outer = (dx * dx) / ((rx + stroke) * (rx + stroke)) + (dy * dy) / ((ry + stroke) * (ry + stroke))
            val inner = (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry)
            if (inner <= 1.0) img[x, y] = 255 else if (outer <= 1.0) img[x, y] = 0
        }
    }

    /** "Ký tự" giả: khung đen rỗng ruột (giống nét chữ, có lỗ trắng bên trong). */
    fun glyph(l: Int, t: Int, size: Int) {
        val s = maxOf(2, size / 6)
        fillRect(l, t, l + size, t + s)
        fillRect(l, t + size - s, l + size, t + size)
        fillRect(l, t, l + s, t + size)
        fillRect(l + size - s, t, l + size, t + size)
    }

    /** Ký tự gồm 2 nét ngang tách rời (như 二). */
    fun twoStrokeGlyph(l: Int, t: Int, size: Int) {
        val s = maxOf(2, size / 6)
        fillRect(l + size / 6, t + size / 5, l + size - size / 6, t + size / 5 + s)
        fillRect(l, t + size - size / 5 - s, l + size, t + size - size / 5)
    }

    /** Cột chữ dọc: [n] ký tự cỡ [size], cách nhau [gap]. */
    fun column(l: Int, t: Int, size: Int, n: Int, gap: Int = size / 6) {
        for (i in 0 until n) glyph(l, t + i * (size + gap), size)
    }
}
