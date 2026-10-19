package vn.mangatranslate.app.core.image

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Ảnh nhỏ để xem trong màn hình DEBUG (PNG, cạnh dài ≤ [maxSide]). */
object DiagImage {
    fun png(src: Bitmap, maxSide: Int = 480): ByteArray? = try {
        val s = minOf(1f, maxSide.toFloat() / max(src.width, src.height))
        val bmp = if (s < 1f) {
            Bitmap.createScaledBitmap(src, max(1, (src.width * s).roundToInt()), max(1, (src.height * s).roundToInt()), true)
        } else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (bmp !== src) bmp.recycle()
        out.toByteArray()
    } catch (e: Exception) {
        null
    }
}
