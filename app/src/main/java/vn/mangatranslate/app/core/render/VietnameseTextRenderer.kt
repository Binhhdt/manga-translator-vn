package vn.mangatranslate.app.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.model.RenderStyle
import vn.mangatranslate.app.core.model.TextColorMode
import vn.mangatranslate.app.core.model.TextRegion
import kotlin.math.max
import kotlin.math.min

/**
 * Vẽ bản dịch tiếng Việt lên ảnh ở ĐỘ PHÂN GIẢI GỐC.
 *
 * Giai đoạn 1: che chữ gốc bằng một khối nền (màu lấy trung vị từ viền vùng chữ — với bong bóng trắng
 * sẽ ra màu trắng). Giai đoạn 2 sẽ thay bằng mask nét chữ + OpenCV inpainting.
 *
 * Chữ: căn giữa, tự xuống dòng (không cắt giữa từ), tự chọn cỡ chữ lớn nhất còn vừa khung bằng tìm kiếm
 * nhị phân. Nếu ở cỡ nhỏ nhất dễ đọc vẫn không vừa, vùng được nới rộng một chút; nếu vẫn không vừa,
 * chữ vẫn được vẽ ĐẦY ĐỦ (không cắt nội dung) và vùng bị đánh dấu "tràn khung" để cảnh báo người dùng.
 *
 * Font: sans-serif hệ thống (Roboto/Noto trên Android) hỗ trợ đầy đủ dấu tiếng Việt.
 */
class VietnameseTextRenderer {

    private val regular: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val bold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    class Output(val bitmap: Bitmap, val overflowIds: Set<Int>)

    private class Fit(val layout: StaticLayout, val area: BoxF, val size: Float, val fits: Boolean)

    fun render(original: Bitmap, regions: List<TextRegion>, renderStyle: RenderStyle): Output {
        val out = original.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("Không đủ bộ nhớ để tạo ảnh kết quả.")
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        fillPaint.style = Paint.Style.FILL
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        textPaint.typeface = if (renderStyle.bold) bold else regular
        textPaint.isSubpixelText = true
        val overflow = HashSet<Int>()

        for (r in regions) {
            if (!r.enabled) continue
            val text = r.translatedText.trim()
            if (text.isEmpty()) continue

            val pad = (r.lineHeight * 0.35f).coerceIn(3f, 24f)
            val cover = r.box.expand(pad).clampTo(w, h)
            if (cover.width < 2f || cover.height < 2f) continue

            val bg = sampleBackground(original, cover)
            if (renderStyle.coverBackground) {
                fillPaint.color = bg
                canvas.drawRoundRect(RectF(cover.left, cover.top, cover.right, cover.bottom), pad, pad, fillPaint)
            }
            val (textColor, outlineColor) = colorsFor(renderStyle.textColorMode, bg)
            val fit = fit(text, r, layoutArea(cover, w), textPaint, renderStyle, w, h)
            if (!fit.fits) overflow += r.id
            drawLayout(canvas, fit, textPaint, textColor, outlineColor, renderStyle.outline)
        }
        return Output(out, overflow)
    }

    /** Vùng đặt chữ. Cột chữ dọc hẹp (tiếng Nhật) được nới ngang để chữ Việt nằm ngang dễ đọc. */
    private fun layoutArea(cover: BoxF, imageWidth: Float): BoxF {
        var a = cover
        if (a.width < a.height * 0.55f) {
            val targetW = min(imageWidth, a.height * 0.6f)
            var left = a.centerX - targetW / 2f
            var right = a.centerX + targetW / 2f
            if (left < 0f) { right -= left; left = 0f }
            if (right > imageWidth) { left -= right - imageWidth; right = imageWidth }
            a = BoxF(max(0f, left), a.top, right, a.bottom)
        }
        val inset = min(a.width, a.height) * 0.04f
        return a.expand(-inset)
    }

    private fun fit(
        text: String,
        region: TextRegion,
        area0: BoxF,
        paint: TextPaint,
        style: RenderStyle,
        imageW: Float,
        imageH: Float
    ): Fit {
        val minSize = max(9f, imageW * 0.012f)

        fun attempt(area: BoxF): Fit? {
            if (area.width < 4f || area.height < 4f) return null
            val maxSize = max(minSize, min(region.lineHeight * 1.15f, area.height))
            val first = build(text, paint, minSize, area.width, style)
            if (!fits(first, area, text)) return null
            val top = build(text, paint, maxSize, area.width, style)
            if (fits(top, area, text)) return Fit(top, area, maxSize, true)
            var lo = minSize
            var hi = maxSize
            var best = first
            var bestSize = minSize
            while (hi - lo > 0.5f) {
                val mid = (lo + hi) / 2f
                val l = build(text, paint, mid, area.width, style)
                if (fits(l, area, text)) {
                    best = l; bestSize = mid; lo = mid
                } else {
                    hi = mid
                }
            }
            return Fit(best, area, bestSize, true)
        }

        attempt(area0)?.let { return it }
        val bigger = area0.expand(area0.width * 0.12f, area0.height * 0.25f).clampTo(imageW, imageH)
        attempt(bigger)?.let { return it }
        // Không vừa: vẫn vẽ đủ nội dung ở cỡ nhỏ nhất và báo tràn khung.
        return Fit(build(text, paint, minSize, bigger.width, style), bigger, minSize, false)
    }

    private fun build(text: String, paint: TextPaint, size: Float, width: Float, style: RenderStyle): StaticLayout {
        paint.textSize = size
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, style.lineSpacing)
            .setIncludePad(true) // chừa chỗ cho dấu chồng của tiếng Việt (Ấ, Ở, Ữ…)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setUseLineSpacingFromFallbacks(true)
        return builder.build()
    }

    private fun fits(layout: StaticLayout, area: BoxF, text: String): Boolean {
        if (layout.height > area.height + 0.5f) return false
        for (i in 0 until layout.lineCount) {
            if (layout.getLineWidth(i) > area.width + 1f) return false
        }
        // Không chấp nhận ngắt dòng giữa một từ (ví dụ "nghiê-ng").
        for (i in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(i)
            if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) return false
        }
        return true
    }

    private fun drawLayout(canvas: Canvas, fit: Fit, paint: TextPaint, textColor: Int, outlineColor: Int, outline: Boolean) {
        paint.textSize = fit.size // layout dùng chung paint — khôi phục cỡ chữ đã chọn
        canvas.save()
        canvas.translate(fit.area.left, fit.area.top + (fit.area.height - fit.layout.height) / 2f)
        if (outline) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1.5f, fit.size * 0.16f)
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = outlineColor
            fit.layout.draw(canvas)
        }
        paint.style = Paint.Style.FILL
        paint.color = textColor
        fit.layout.draw(canvas)
        canvas.restore()
    }

    private fun colorsFor(mode: TextColorMode, bg: Int): Pair<Int, Int> = when (mode) {
        TextColorMode.BLACK -> Color.BLACK to Color.WHITE
        TextColorMode.WHITE -> Color.WHITE to Color.BLACK
        TextColorMode.AUTO -> {
            val lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0
            if (lum >= 0.5) Color.BLACK to Color.WHITE else Color.WHITE to Color.BLACK
        }
    }

    /** Màu nền = trung vị từng kênh của các điểm trên viền vùng che. */
    private fun sampleBackground(bmp: Bitmap, box: BoxF): Int {
        val l = box.left.toInt().coerceIn(0, bmp.width - 1)
        val r = (box.right.toInt() - 1).coerceIn(0, bmp.width - 1)
        val t = box.top.toInt().coerceIn(0, bmp.height - 1)
        val b = (box.bottom.toInt() - 1).coerceIn(0, bmp.height - 1)
        val n = 32
        val reds = IntArray(n * 4)
        val greens = IntArray(n * 4)
        val blues = IntArray(n * 4)
        var k = 0
        for (i in 0 until n) {
            val x = l + (r - l) * i / (n - 1)
            val y = t + (b - t) * i / (n - 1)
            for (p in intArrayOf(bmp.getPixel(x, t), bmp.getPixel(x, b), bmp.getPixel(l, y), bmp.getPixel(r, y))) {
                reds[k] = Color.red(p); greens[k] = Color.green(p); blues[k] = Color.blue(p); k++
            }
        }
        reds.sort(); greens.sort(); blues.sort()
        val m = k / 2
        return Color.rgb(reds[m], greens[m], blues[m])
    }
}
