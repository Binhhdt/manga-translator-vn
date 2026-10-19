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
import vn.mangatranslate.app.core.model.BubbleShape
import vn.mangatranslate.app.core.model.RenderStyle
import vn.mangatranslate.app.core.model.TextColorMode
import vn.mangatranslate.app.core.model.TextRegion
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Vẽ bản dịch tiếng Việt lên ảnh ở ĐỘ PHÂN GIẢI GỐC (ảnh gốc không bị sửa — luôn vẽ trên bản sao).
 *
 * Che chữ gốc:
 * - Bong bóng trắng khép kín: xóa sạch mọi nét bị bong bóng bao kín (chữ + furigana) bằng màu nền bong bóng;
 *   viền bong bóng và hình vẽ bên ngoài không bị đụng tới.
 * - Còn lại: lớp phủ nền sáng với độ che chỉnh được trong Cài đặt.
 *
 * Đặt chữ: trong vùng nằm gọn bên trong bong bóng (hoặc khoảng trắng quanh chữ), có khoảng đệm với viền,
 * căn giữa, xuống dòng theo từ (không cắt giữa từ), chọn cỡ chữ lớn nhất còn vừa. Không vừa thì vẫn vẽ đủ chữ
 * ở cỡ nhỏ nhất và đánh dấu tràn khung để cảnh báo — không tự cắt bớt nội dung.
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

            var background = Color.WHITE
            var area: BoxF
            val limit: BoxF
            var cleaned = false
            val shape = r.bubble
            if (shape != null && renderStyle.cleanBubbles) {
                val bg = sampleBubbleBackground(original, shape)
                if (luminance(bg) >= 0.7) {
                    cleanBubble(out, shape, bg)
                    background = bg
                    cleaned = true
                }
            }

            if (cleaned && shape != null) {
                area = shape.inner
                limit = shape.box
            } else {
                val pad = (r.lineHeight * 0.35f).coerceIn(3f, 24f)
                val cover = (r.layoutBox ?: r.box).union(r.box.expand(pad)).clampTo(w, h)
                if (cover.width < 2f || cover.height < 2f) continue
                val bg = sampleBackground(original, cover)
                val overlay = if (luminance(bg) >= 0.6) bg else Color.WHITE
                val alpha = (renderStyle.overlayOpacity.coerceIn(0, 100) * 255) / 100
                if (renderStyle.coverBackground && alpha > 0) {
                    fillPaint.color = (alpha shl 24) or (overlay and 0x00FFFFFF)
                    canvas.drawRoundRect(RectF(cover.left, cover.top, cover.right, cover.bottom), pad, pad, fillPaint)
                    background = blend(bg, overlay, alpha / 255f)
                } else {
                    background = bg
                }
                area = widenNarrow(cover, r.box, w)
                limit = area.expand(area.width * 0.1f, area.height * 0.2f).clampTo(w, h)
            }

            // Khoảng đệm với viền bong bóng.
            val inset = min(area.width, area.height) * 0.06f
            area = area.expand(-inset)
            val (textColor, outlineColor) = colorsFor(renderStyle.textColorMode, background)
            val fit = fit(text, r, area, limit, textPaint, renderStyle, w)
            if (!fit.fits) overflow += r.id
            drawLayout(canvas, fit, textPaint, textColor, outlineColor, renderStyle.outline)
        }
        return Output(out, overflow)
    }

    /** Cột chữ dọc hẹp: nới ngang vừa phải để chữ Việt nằm ngang, nhưng không quá 2.2 lần bề rộng ban đầu. */
    private fun widenNarrow(cover: BoxF, textBox: BoxF, imageWidth: Float): BoxF {
        if (cover.width >= cover.height * 0.5f) return cover
        val targetW = min(imageWidth, min(cover.height * 0.6f, cover.width * 2.2f))
        var left = textBox.centerX - targetW / 2f
        var right = textBox.centerX + targetW / 2f
        if (left < 0f) { right -= left; left = 0f }
        if (right > imageWidth) { left -= right - imageWidth; right = imageWidth }
        return BoxF(max(0f, left), cover.top, right, cover.bottom)
    }

    private fun fit(
        text: String,
        region: TextRegion,
        area0: BoxF,
        limit: BoxF,
        paint: TextPaint,
        style: RenderStyle,
        imageW: Float
    ): Fit {
        fun attempt(area: BoxF): Fit? {
            if (area.width < 4f || area.height < 4f) return null
            val b = FontSizing.bounds(imageW, region.lineHeight, area.height)
            val first = build(text, paint, b.min, area.width, style)
            if (!fits(first, area, text)) return null
            val top = build(text, paint, b.max, area.width, style)
            if (fits(top, area, text)) return Fit(top, area, b.max, true)
            var lo = b.min
            var hi = b.max
            var best = first
            var bestSize = b.min
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
        // Nới vùng chữ trong giới hạn cho phép (bong bóng / vùng sáng quanh chữ).
        val bigger = BoxF(
            max(limit.left, area0.left - area0.width * 0.1f), max(limit.top, area0.top - area0.height * 0.15f),
            min(limit.right, area0.right + area0.width * 0.1f), min(limit.bottom, area0.bottom + area0.height * 0.15f)
        )
        attempt(bigger)?.let { return it }
        // Không vừa: vẫn vẽ đủ nội dung ở cỡ nhỏ nhất và báo tràn khung.
        val minSize = FontSizing.bounds(imageW, region.lineHeight, bigger.height).min
        return Fit(build(text, paint, minSize, max(bigger.width, 4f), style), bigger, minSize, false)
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

    /** Tô màu nền lên mọi điểm bị bong bóng bao kín (nét chữ, furigana). Viền bong bóng không bị đụng tới. */
    private fun cleanBubble(out: Bitmap, shape: BubbleShape, bg: Int) {
        val b = shape.mask
        val s = shape.scale
        val l = floor(b.left / s).toInt().coerceIn(0, out.width - 1)
        val t = floor(b.top / s).toInt().coerceIn(0, out.height - 1)
        val r = ceil(b.right / s).toInt().coerceIn(l + 1, out.width)
        val btm = ceil(b.bottom / s).toInt().coerceIn(t + 1, out.height)
        val w = r - l
        val h = btm - t
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, l, t, w, h)
        var changed = false
        for (y in 0 until h) for (x in 0 until w) {
            if (b.isHoleDilated(((l + x) * s).toInt(), ((t + y) * s).toInt())) {
                px[y * w + x] = bg
                changed = true
            }
        }
        if (changed) out.setPixels(px, 0, w, l, t, w, h)
    }

    /** Màu nền bong bóng = trung vị các điểm bên trong bong bóng không thuộc nét chữ. */
    private fun sampleBubbleBackground(original: Bitmap, shape: BubbleShape): Int {
        val b = shape.mask
        val s = shape.scale
        val reds = ArrayList<Int>()
        val greens = ArrayList<Int>()
        val blues = ArrayList<Int>()
        val step = max(1, max(b.width, b.height) / 24)
        var gy = b.top
        while (gy < b.bottom) {
            var gx = b.left
            while (gx < b.right) {
                if (b.isInterior(gx, gy) && !b.isHoleDilated(gx, gy)) {
                    val ox = (gx / s).toInt().coerceIn(0, original.width - 1)
                    val oy = (gy / s).toInt().coerceIn(0, original.height - 1)
                    val p = original.getPixel(ox, oy)
                    reds += Color.red(p); greens += Color.green(p); blues += Color.blue(p)
                }
                gx += step
            }
            gy += step
        }
        if (reds.isEmpty()) return Color.WHITE
        reds.sort(); greens.sort(); blues.sort()
        val m = reds.size / 2
        return Color.rgb(reds[m], greens[m], blues[m])
    }

    private fun colorsFor(mode: TextColorMode, bg: Int): Pair<Int, Int> = when (mode) {
        TextColorMode.BLACK -> Color.BLACK to Color.WHITE
        TextColorMode.WHITE -> Color.WHITE to Color.BLACK
        TextColorMode.AUTO -> if (luminance(bg) >= 0.5) Color.BLACK to Color.WHITE else Color.WHITE to Color.BLACK
    }

    private fun luminance(c: Int): Double =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    private fun blend(base: Int, over: Int, a: Float): Int = Color.rgb(
        (Color.red(base) * (1 - a) + Color.red(over) * a).toInt(),
        (Color.green(base) * (1 - a) + Color.green(over) * a).toInt(),
        (Color.blue(base) * (1 - a) + Color.blue(over) * a).toInt()
    )

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
