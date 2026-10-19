package vn.mangatranslate.app.core.image

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tạo bản xem trước để hiển thị: thu nhỏ vừa đủ cho màn hình và cắt thành các dải cao tối đa
 * [CHUNK_HEIGHT] px (GPU không vẽ được texture quá lớn — quan trọng với ảnh manhwa rất dài).
 * Ảnh gốc độ phân giải đầy đủ vẫn được giữ riêng để render và xuất.
 */
object PreviewChunker {
    private const val MAX_WIDTH = 1800
    private const val MAX_PIXELS = 14_000_000f
    private const val CHUNK_HEIGHT = 2048

    /** @param recycleSource true nếu [src] là ảnh tạm có thể giải phóng sau khi cắt. */
    fun build(src: Bitmap, recycleSource: Boolean = false): List<ImageBitmap> {
        var scale = min(1f, MAX_WIDTH.toFloat() / src.width)
        val pixels = src.width * scale * src.height * scale
        if (pixels > MAX_PIXELS) scale *= sqrt(MAX_PIXELS / pixels)

        val scaled = if (scale < 0.999f) {
            Bitmap.createScaledBitmap(
                src, max(1, (src.width * scale).roundToInt()), max(1, (src.height * scale).roundToInt()), true
            )
        } else src

        val pieces = ArrayList<Bitmap>()
        var y = 0
        while (y < scaled.height) {
            val h = min(CHUNK_HEIGHT, scaled.height - y)
            pieces += Bitmap.createBitmap(scaled, 0, y, scaled.width, h)
            y += h
        }
        if (scaled !== src && pieces.none { it === scaled }) scaled.recycle()
        if (recycleSource && pieces.none { it === src }) src.recycle()
        return pieces.map { it.asImageBitmap() }
    }
}
