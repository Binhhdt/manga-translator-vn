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
import android.util.Log
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

    private companion object {
        const val TAG = "MangaRender"
    }

    private val regular: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val bold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    /**
     * @param overflowIds vùng không đủ chỗ → KHÔNG vẽ (giữ nguyên ảnh gốc), cần người dùng rút ngắn câu.
     * @param hiddenIds vùng có bản dịch đáng ngờ chưa xác nhận → không vẽ.
     * @param maskRejected vùng bị từ chối xóa chữ vì mask bất thường (kèm lý do).
     * @param failedIds vùng gặp lỗi khi vẽ (đã bỏ qua, các vùng khác vẫn được vẽ).
     * @param changedOutside số điểm ảnh bị đổi NGOÀI vùng cho phép (phải bằng 0; -1 = ảnh quá lớn, không kiểm).
     */
    class Output(
        val bitmap: Bitmap,
        val overflowIds: Set<Int>,
        val hiddenIds: Set<Int> = emptySet(),
        val maskRejected: Map<Int, String> = emptyMap(),
        val failedIds: Set<Int> = emptySet(),
        val changedOutside: Int = 0
    )

    private class Fit(val layout: StaticLayout, val area: BoxF, val size: Float, val fits: Boolean)

    private class Ctx(
        val original: Bitmap,
        val out: Bitmap,
        val canvas: Canvas,
        val style: RenderStyle,
        val fillPaint: Paint,
        val textPaint: TextPaint,
        val allowed: MutableList<RenderSafety.Rect>,
        val overflow: MutableSet<Int>,
        val hidden: MutableSet<Int>,
        val maskRejected: MutableMap<Int, String>
    )

    /** Vẽ trên BẢN SAO của ảnh gốc; [original] không bao giờ bị sửa. */
    fun render(original: Bitmap, regions: List<TextRegion>, renderStyle: RenderStyle): Output {
        val out = original.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("Không đủ bộ nhớ để tạo ảnh kết quả.")
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        fillPaint.style = Paint.Style.FILL
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        textPaint.typeface = if (renderStyle.bold) bold else regular
        textPaint.isSubpixelText = true
        val ctx = Ctx(
            original, out, Canvas(out), renderStyle, fillPaint, textPaint,
            ArrayList(), HashSet(), HashSet(), HashMap()
        )
        val failed = HashSet<Int>()
        for (r in regions) {
            // Mỗi vùng độc lập: lỗi của một vùng không làm hỏng cả trang.
            try {
                drawRegion(ctx, r)
            } catch (e: Exception) {
                Log.w(TAG, "Bỏ qua vẽ vùng #${r.id}: ${e.message}", e)
                failed += r.id
            }
        }
        val changed = verifyUnchangedOutside(original, out, ctx.allowed)
        if (changed > 0) Log.e(TAG, "CẢNH BÁO: $changed điểm ảnh bị đổi ngoài vùng cho phép")
        return Output(out, ctx.overflow, ctx.hidden, ctx.maskRejected, failed, changed)
    }

    private fun drawRegion(c: Ctx, r: TextRegion) {
        if (!r.enabled) return
        val text = r.translatedText.trim()
        if (text.isEmpty()) return
        if (c.style.hideUnverified && r.unverified) {
            c.hidden += r.id
            return
        }
        val w = c.out.width.toFloat()
        val h = c.out.height.toFloat()
        val pad = (r.lineHeight * 0.35f).coerceIn(3f, 24f)
        val shape = r.bubble

        // 1) Xác định vùng che và vùng đặt chữ — KHÔNG vẽ gì cho tới khi biết chữ vừa khung.
        val cover: BoxF
        var area: BoxF
        val limit: BoxF
        if (shape != null) {
            cover = intersect(r.box.expand(pad).union(shape.inner), shape.box)?.clampTo(w, h) ?: return
            area = shape.inner
            // Vùng hở (bong bóng tổng hợp, nhãn âm): vùng đặt chữ đã kiểm tra từng điểm → không nới thêm.
            limit = if (shape.mask.label < 0) shape.inner
            else intersect(shape.inner.expand(shape.inner.width * 0.08f, shape.inner.height * 0.08f), shape.box) ?: shape.inner
        } else if (r.coverBox != null) {
            // Vùng phủ đã kiểm tra trước (không đè nét vẽ) ∪ khoảng trắng đặt chữ.
            cover = r.coverBox.union(r.layoutBox ?: r.coverBox).clampTo(w, h)
            area = r.layoutBox?.clampTo(w, h) ?: cover
            limit = cover
        } else {
            cover = (r.layoutBox ?: r.box).union(r.box.expand(pad)).clampTo(w, h)
            area = cover
            limit = cover // không bao giờ vẽ chữ ra ngoài vùng đã che
        }
        if (cover.width < 2f || cover.height < 2f) return
        val inset = min(area.width, area.height) * 0.06f
        area = area.expand(-inset)

        // 2) Xóa chữ trong bong bóng: chỉ khi được bật, mask giới hạn trong khung chữ thực tế và qua kiểm tra an toàn.
        var cleanRect: RenderSafety.Rect? = null
        var cleanColor = Color.WHITE
        if (shape != null && (c.style.cleanBubbles || c.style.debugMask)) {
            val allowedBox = intersect(r.box.expand(r.lineHeight * 0.6f), shape.box)?.clampTo(w, h)
            if (allowedBox != null) {
                val rect = toRect(allowedBox)
                val mask = countMask(shape, rect)
                // Vùng hở: mặt nạ đã là đúng các nét chữ được chọn (không phải cả khung) → cho phép khung xóa tới 15% trang;
                // kiểm tra mật độ mực (≤ 55% khung) vẫn giữ nguyên.
                val pageLimit = if (shape.mask.label < 0) 0.15f else 0.06f
                val problem = RenderSafety.maskProblem(mask.toLong(), rect.area, w.toLong() * h.toLong(), pageLimit)
                val bg = sampleBubbleBackground(c.original, shape)
                when {
                    problem != null -> c.maskRejected[r.id] = problem
                    luminance(bg) < 0.7 -> c.maskRejected[r.id] = "Nền bong bóng tối — không xóa chữ, dùng lớp phủ"
                    else -> { cleanRect = rect; cleanColor = bg }
                }
            }
        }

        val background: Int
        val overlayColor: Int
        val alpha: Int
        if (cleanRect != null && !c.style.debugMask) {
            background = cleanColor
            overlayColor = 0
            alpha = 0
        } else {
            val bg = sampleBackground(c.original, cover)
            overlayColor = if (luminance(bg) >= 0.6) bg else Color.WHITE
            alpha = if (c.style.coverBackground) (c.style.overlayOpacity.coerceIn(0, 100) * 255) / 100 else 0
            background = if (alpha > 0) blend(bg, overlayColor, alpha / 255f) else bg
        }

        // Any detected bubble whose ink-clearing mask was rejected must keep its
        // original text. Otherwise painting the whole bright interior can silently
        // erase an adjacent Japanese line or create an empty dialogue bubble.
        // Prefer a visible untranslated bubble over a blank, misleading one.
        if (shape != null && cleanRect == null && c.style.cleanBubbles && !c.style.debugMask) {
            if (!c.maskRejected.containsKey(r.id))
                c.maskRejected[r.id] = "Không có mặt nạ xóa chữ an toàn — giữ nguyên ảnh"
            c.overflow += r.id
            return
        }
        // Vùng hở: không xóa được từng nét an toàn → giữ nguyên ảnh (KHÔNG phủ kín cả khung — tránh mảng xám/xóa nét vẽ).
        if (shape != null && shape.mask.label < 0 && cleanRect == null) {
            if (!c.maskRejected.containsKey(r.id)) c.maskRejected[r.id] = "Vùng hở: không xóa được nét chữ an toàn"
            c.overflow += r.id
            return
        }
        val fit = fit(text, r, area, limit, c.textPaint, c.style, w)
        if (!fit.fits) {
            // Không đủ chỗ: giữ nguyên ảnh gốc, đánh dấu để người dùng rút ngắn câu.
            c.overflow += r.id
            return
        }

        // 3) Vẽ.
        if (c.style.debugMask) {
            cleanRect?.let { paintMask(c.out, shape!!, it, debug = true, color = 0); c.allowed += it }
            if (cleanRect == null) {
                if (shape != null) {
                    paintInsideBubble(c.out, shape, toRect(cover), Color.RED, 0x55)
                } else {
                    c.fillPaint.color = 0x55FF0000
                    c.canvas.drawRect(RectF(cover.left, cover.top, cover.right, cover.bottom), c.fillPaint)
                }
                c.allowed += toRect(cover)
            }
        } else if (cleanRect != null) {
            paintMask(c.out, shape!!, cleanRect!!, debug = false, color = cleanColor)
            c.allowed += cleanRect!!
        } else if (alpha > 0) {
            if (shape != null) {
                // Bong bóng khép kín: chỉ phủ các điểm BÊN TRONG bong bóng — góc khung chữ nhật không đè lên viền/tranh.
                paintInsideBubble(c.out, shape, toRect(cover), overlayColor, alpha)
            } else {
                c.fillPaint.color = (alpha shl 24) or (overlayColor and 0x00FFFFFF)
                c.canvas.drawRoundRect(RectF(cover.left, cover.top, cover.right, cover.bottom), pad, pad, c.fillPaint)
            }
            c.allowed += toRect(cover)
        }
        val (textColor, outlineColor) = colorsFor(c.style.textColorMode, background)
        drawLayout(c.canvas, fit, c.textPaint, textColor, outlineColor, c.style.outline)
        val stroke = max(1.5f, fit.size * 0.16f) + 2f
        c.allowed += toRect(fit.area.expand(stroke))
        if (c.style.debugMask) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f
            p.color = Color.BLUE
            c.canvas.drawRect(RectF(fit.area.left, fit.area.top, fit.area.right, fit.area.bottom), p)
            c.allowed += toRect(fit.area.expand(3f))
        }
    }

    private fun intersect(a: BoxF, b: BoxF): BoxF? {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val btm = min(a.bottom, b.bottom)
        return if (r > l && btm > t) BoxF(l, t, r, btm) else null
    }

    private fun toRect(b: BoxF) = RenderSafety.Rect(floor(b.left).toInt(), floor(b.top).toInt(), ceil(b.right).toInt(), ceil(b.bottom).toInt())

    /** So sánh ảnh gốc và ảnh xử lý; chỉ kiểm với ảnh ≤ 12 MP để không tràn RAM. */
    private fun verifyUnchangedOutside(original: Bitmap, out: Bitmap, allowed: List<RenderSafety.Rect>): Int {
        val w = original.width
        val h = original.height
        if (w.toLong() * h > 12_000_000L) return -1
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        original.getPixels(a, 0, w, 0, 0, w, h)
        out.getPixels(b, 0, w, 0, 0, w, h)
        return RenderSafety.changedOutside(a, b, w, h, allowed)
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
            val b = if (style.fillBubble) FontSizing.fillBounds(imageW, area.height)
            else FontSizing.bounds(imageW, region.lineHeight, area.height)
            val first = build(text, paint, b.min, area.width, style)
            if (!fits(first, area, text)) {
                // Single-word replies like "Ừ!", "!", and short names frequently
                // have less room than the 11 px default floor. Try a slightly
                // smaller font ONLY for very short ChatGPT speech; still require
                // every character and accent to fit entirely inside the mask.
                if (style.fillBubble && text.length <= 8 && area.width >= 10f && area.height >= 12f) {
                    val smallSize = min(9f, b.min)
                    val small = build(text, paint, smallSize, area.width, style)
                    if (fits(small, area, text)) return Fit(small, area, smallSize, true)
                }
                return null
            }
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
        val minSize = (if (style.fillBubble) FontSizing.fillBounds(imageW, bigger.height) else FontSizing.bounds(imageW, region.lineHeight, bigger.height)).min
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

    /** Phủ màu [color] với độ đậm [alpha] (0–255) lên các điểm của [rect] nằm bên trong bong bóng (cách viền ≥ 1 điểm). */
    private fun paintInsideBubble(out: Bitmap, shape: BubbleShape, rect: RenderSafety.Rect, color: Int, alpha: Int) {
        val r = rect.clamp(out.width, out.height)
        if (r.width <= 0 || r.height <= 0) return
        val b = shape.mask
        val s = shape.scale
        val a = alpha.coerceIn(0, 255) / 255f
        val px = IntArray(r.width * r.height)
        out.getPixels(px, 0, r.width, r.left, r.top, r.width, r.height)
        for (y in 0 until r.height) for (x in 0 until r.width) {
            if (b.isInteriorEroded(((r.left + x) * s).toInt(), ((r.top + y) * s).toInt())) {
                val i = y * r.width + x
                px[i] = blend(px[i], color, a)
            }
        }
        out.setPixels(px, 0, r.width, r.left, r.top, r.width, r.height)
    }

    /** Số điểm mask (nét chữ bị bong bóng bao kín) nằm trong [rect] (tọa độ ảnh gốc). */
    private fun countMask(shape: BubbleShape, rect: RenderSafety.Rect): Int {
        val b = shape.mask
        val s = shape.scale
        var n = 0
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
            if (b.isHoleDilated((x * s).toInt(), (y * s).toInt())) n++
        }
        return n
    }

    /**
     * Xóa (tô màu nền) hoặc tô đỏ (DEBUG) các điểm mask, CHỈ trong [rect] = khung chữ thực tế nới nhẹ.
     * Viền bong bóng và mọi thứ ngoài [rect] không bị đụng tới.
     */
    private fun paintMask(out: Bitmap, shape: BubbleShape, rect: RenderSafety.Rect, debug: Boolean, color: Int) {
        val r = rect.clamp(out.width, out.height)
        if (r.width <= 0 || r.height <= 0) return
        val b = shape.mask
        val s = shape.scale
        val px = IntArray(r.width * r.height)
        out.getPixels(px, 0, r.width, r.left, r.top, r.width, r.height)
        for (y in 0 until r.height) for (x in 0 until r.width) {
            val gx = ((r.left + x) * s).toInt()
            val gy = ((r.top + y) * s).toInt()
            // Chỉ tô điểm thuộc lòng bong bóng: phần nới 1 px quanh nét chữ không bao giờ lấn sang viền/hình vẽ.
            if (b.isHoleDilated(gx, gy) && b.isInterior(gx, gy)) {
                val i = y * r.width + x
                px[i] = if (debug) blend(px[i], Color.RED, 0.6f) else color
            }
        }
        out.setPixels(px, 0, r.width, r.left, r.top, r.width, r.height)
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
