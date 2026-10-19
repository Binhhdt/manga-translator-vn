package vn.mangatranslate.app.core.ocr

import android.graphics.Bitmap
import android.util.Log
import vn.mangatranslate.app.core.image.BitmapGray
import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.model.OcrAttempt
import vn.mangatranslate.app.core.model.OcrLine
import vn.mangatranslate.app.core.model.TraceLine
import vn.mangatranslate.app.core.vision.GrayImage
import vn.mangatranslate.app.core.vision.TextLayoutAnalyzer
import vn.mangatranslate.app.core.vision.VerticalComposer
import kotlin.coroutines.cancellation.CancellationException

/**
 * OCR một vùng đã cắt riêng (một bong bóng hoặc một khối chữ) bằng NHIỀU cách rồi chọn cách tốt nhất:
 *
 * - "Trang": kết quả OCR toàn trang (nếu có), đã bỏ furigana.
 * - "Đọc trực tiếp": OCR riêng vùng cắt, phóng to để mỗi chữ cao ~[TARGET_CHAR] px, bỏ furigana.
 * - "Tách cột dọc": (Nhật/Trung) tách cột, bỏ furigana, xếp lại thành hàng ngang rồi OCR — ký tự giữ nguyên chiều đứng.
 * - "Ảnh đen trắng": chỉ chạy khi các cách trên cho kết quả yếu.
 *
 * Chọn theo [OcrCandidateScorer]. Không xoay cả ảnh 90°: ký tự sẽ nằm nghiêng và ML Kit đọc kém hơn.
 * Mọi lần đọc được ghi lại NGUYÊN VẸN trong [Outcome.attempts] cho màn hình DEBUG.
 */
class RegionOcr(
    private val ocr: OCRProcessor,
    private val detector: TextRegionDetector,
    private val analyzer: TextLayoutAnalyzer = TextLayoutAnalyzer()
) {

    class Outcome(
        val text: String,
        val method: String,
        val vertical: Boolean,
        /** Cỡ chữ gốc (px của vùng cắt). */
        val charSize: Float,
        /** Khung chữ trong tọa độ vùng cắt, null nếu không xác định. */
        val textBox: BoxF?,
        val notes: List<String>,
        val validChars: Int,
        val invalidChars: Int,
        val score: Float,
        val attempts: List<OcrAttempt>,
        val layoutInfo: String
    )

    private class Candidate(
        val method: String,
        val text: String,
        val vertical: Boolean,
        val box: BoxF?,
        val lines: List<TraceLine>,
        val inputW: Int,
        val inputH: Int
    )

    /** Kết quả một lần gọi ML Kit: dòng đã lọc furigana + toàn bộ dòng thô để ghi vết. */
    private class Read(val kept: List<OcrLine>, val trace: List<TraceLine>)

    suspend fun read(
        crop: Bitmap,
        langCode: String,
        script: OCRProcessor.Script,
        pageText: String? = null,
        pageVertical: Boolean? = null,
        hintCharSize: Float? = null,
        tag: String = "vùng",
        pageLines: List<TraceLine> = emptyList()
    ): Outcome? {
        if (crop.width <= 0 || crop.height <= 0) {
            Log.w(TAG, "[$tag] vùng cắt rỗng ${crop.width}x${crop.height} — bỏ qua")
            return null
        }
        val gray = BitmapGray.fromBitmap(crop).image
        val cjk = langCode == "ja" || langCode == "zh"
        val layout = if (cjk) analyzer.analyzeVertical(gray.inkMask(), gray.width, gray.height) else null
        val charSize = (hintCharSize ?: layout?.charSize ?: (minOf(gray.width, gray.height) / 4f)).coerceAtLeast(4f)
        val candidates = ArrayList<Candidate>()

        if (!pageText.isNullOrBlank()) {
            candidates += Candidate(METHOD_PAGE, pageText, pageVertical ?: cjk, null, pageLines, 0, 0)
        }

        // A — đọc trực tiếp vùng cắt.
        val direct = recognizeLines(crop, script, charSize, "$tag/$METHOD_DIRECT")
        val directVertical = if (direct.kept.isNotEmpty()) detector.isVertical(direct.kept) else (pageVertical ?: cjk)
        if (direct.kept.isNotEmpty()) {
            candidates += Candidate(
                METHOD_DIRECT, detector.buildText(direct.kept, directVertical), directVertical,
                BoxF.unionOf(direct.kept.map { it.box }), direct.trace, crop.width, crop.height
            )
        }

        // C — tách cột dọc, xếp lại thành hàng ngang.
        val vertical = directVertical || (direct.kept.isEmpty() && cjk)
        val useColumns = layout != null && vertical && layout.mainColumns.any { it.cells.size >= 2 }
        if (useColumns && layout != null) {
            composeAndRead(gray, layout, script, "$tag/$METHOD_COLUMNS")?.let { c -> candidates += c }
        }

        val expected = if (useColumns && layout != null) layout.expectedChars else null
        var best = pick(candidates, langCode, expected)

        // B — ảnh đen trắng, chỉ khi kết quả còn yếu.
        val bestStats = best?.let { OcrCandidateScorer.stats(it.text, langCode) }
        val weak = bestStats == null || bestStats.invalid > 0 ||
            (expected != null && bestStats.valid < 0.6f * expected)
        if (weak) {
            val bw = BitmapGray.toBitmap(BitmapGray.binarized(gray))
            try {
                val r = recognizeLines(bw, script, charSize, "$tag/$METHOD_BINARY")
                if (r.kept.isNotEmpty()) {
                    val v = detector.isVertical(r.kept)
                    candidates += Candidate(
                        METHOD_BINARY, detector.buildText(r.kept, v), v, BoxF.unionOf(r.kept.map { it.box }),
                        r.trace, bw.width, bw.height
                    )
                    best = pick(candidates, langCode, expected)
                }
            } finally {
                bw.recycle()
            }
        }
        val layoutInfo = describeLayout(layout, useColumns)
        val chosen = best ?: return null

        val notes = ArrayList<String>()
        val others = candidates.filter { it !== chosen && it.text.isNotBlank() }
        val disagree = others.filter { OcrCandidateScorer.similarity(it.text, chosen.text) < 0.5f }
        if (disagree.isNotEmpty() && others.size == disagree.size) {
            val alt = disagree.first()
            notes += "Các cách đọc OCR cho kết quả khác nhau (${alt.method}: «${alt.text.take(30)}»)"
        }
        val st = OcrCandidateScorer.stats(chosen.text, langCode)
        val outSize = if (useColumns && layout != null) layout.charSize else charSize
        val attempts = candidates.map { c ->
            OcrAttempt(
                c.method, c.inputW, c.inputH, c.lines, c.text,
                OcrCandidateScorer.score(c.text, langCode, expected), c === chosen
            )
        }
        return Outcome(
            chosen.text, chosen.method, chosen.vertical, outSize, chosen.box, notes,
            st.valid, st.invalid, OcrCandidateScorer.score(chosen.text, langCode, expected), attempts, layoutInfo
        )
    }

    private fun describeLayout(layout: TextLayoutAnalyzer.Layout?, used: Boolean): String {
        if (layout == null) return ""
        val main = layout.mainColumns
        val cells = main.joinToString("/") { it.cells.size.toString() }
        return "Phân tích ảnh: ${main.size} cột chính (phải→trái, số ô: $cells), " +
            "${layout.rubyColumns.size} cột furigana bị bỏ, cỡ chữ ~${layout.charSize.toInt()}px" +
            if (used) "" else " — không dùng cách tách cột"
    }

    private fun pick(cands: List<Candidate>, lang: String, expected: Int?): Candidate? {
        var best: Candidate? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (c in cands) {
            val s = OcrCandidateScorer.score(c.text, lang, expected)
            if (s > bestScore) { // giữ phương án đứng trước khi bằng điểm
                best = c
                bestScore = s
            }
        }
        return if (bestScore == Float.NEGATIVE_INFINITY) null else best
    }

    /** Một cách đọc lỗi (ví dụ ảnh bất thường) chỉ làm mất cách đọc đó, không làm hỏng cả vùng. */
    private suspend fun recognizeLines(bmp: Bitmap, script: OCRProcessor.Script, charSize: Float, tag: String): Read {
        val up = (TARGET_CHAR / charSize).coerceIn(1f, MAX_UPSCALE)
        val lines = try {
            ocr.recognize(bmp, script, upscale = up, tag = tag).flatMap { it.lines }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "[$tag] OCR lỗi trên ảnh ${bmp.width}x${bmp.height}: ${e.message}", e)
            emptyList()
        }
        val kept = RubyFilter.filter(lines)
        val keptSet = kept.toHashSet()
        return Read(kept, lines.map { TraceLine(it.text, it.box, removedAsRuby = it !in keptSet) })
    }

    private suspend fun composeAndRead(
        gray: GrayImage,
        layout: TextLayoutAnalyzer.Layout,
        script: OCRProcessor.Script,
        tag: String
    ): Candidate? {
        val comp = VerticalComposer.compose(gray, layout) ?: return null
        val bmp = BitmapGray.toBitmap(comp)
        return try {
            val up = (TARGET_CHAR / layout.charSize).coerceIn(1f, MAX_UPSCALE)
            val lines = ocr.recognize(bmp, script, upscale = up, tag = tag).flatMap { it.lines }
            // Mỗi cột dọc đã thành một hàng ngang: đọc theo hàng, trên xuống dưới.
            val text = detector.buildText(lines, vertical = false)
            if (text.isBlank()) return null
            val cols = layout.mainColumns
            val box = BoxF(
                cols.minOf { it.left }.toFloat(), cols.minOf { it.top }.toFloat(),
                cols.maxOf { it.right }.toFloat(), cols.maxOf { it.bottom }.toFloat()
            )
            Candidate(METHOD_COLUMNS, text, true, box, lines.map { TraceLine(it.text, it.box) }, comp.width, comp.height)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "[$tag] OCR lỗi trên ảnh tách cột ${comp.width}x${comp.height}: ${e.message}", e)
            null
        } finally {
            bmp.recycle()
        }
    }

    companion object {
        const val METHOD_PAGE = "OCR toàn trang"
        const val METHOD_DIRECT = "Đọc riêng vùng"
        const val METHOD_COLUMNS = "Tách cột dọc"
        const val METHOD_BINARY = "Ảnh đen trắng"
        const val METHOD_MANUAL = "Khoanh tay"
        private const val TARGET_CHAR = 40f
        private const val MAX_UPSCALE = 4f
        private const val TAG = "MangaOCR"
    }
}
