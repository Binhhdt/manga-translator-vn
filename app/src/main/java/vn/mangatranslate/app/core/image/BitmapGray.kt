package vn.mangatranslate.app.core.image

import android.graphics.Bitmap
import vn.mangatranslate.app.core.vision.GrayImage
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Chuyển đổi Bitmap ↔ ảnh xám thuần Kotlin. */
object BitmapGray {

    /** [scale] = kích thước ảnh xám / kích thước ảnh gốc. */
    class Scaled(val image: GrayImage, val scale: Float)

    fun fromBitmap(src: Bitmap, maxPixels: Long = Long.MAX_VALUE): Scaled {
        val total = src.width.toLong() * src.height
        val s = if (total > maxPixels) sqrt(maxPixels.toDouble() / total).toFloat() else 1f
        val bmp = if (s < 1f) {
            Bitmap.createScaledBitmap(src, max(1, (src.width * s).roundToInt()), max(1, (src.height * s).roundToInt()), true)
        } else src
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        if (bmp !== src) bmp.recycle()
        return Scaled(GrayImage(w, h, px), w.toFloat() / src.width)
    }

    fun toBitmap(img: GrayImage): Bitmap {
        val colors = IntArray(img.pixels.size) { i ->
            val v = img.pixels[i].coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(colors, img.width, img.height, Bitmap.Config.ARGB_8888)
    }

    /** Ảnh đen trắng thuần (giúp OCR khi nền có tram/lưới chấm). */
    fun binarized(img: GrayImage, threshold: Int = GrayImage.INK_THRESHOLD): GrayImage =
        GrayImage(img.width, img.height, IntArray(img.pixels.size) { if (img.pixels[it] < threshold) 0 else 255 })
}
