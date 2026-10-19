package vn.mangatranslate.app.core.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import vn.mangatranslate.app.core.image.BitmapGray
import vn.mangatranslate.app.core.image.CropMath
import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.model.BubbleShape
import vn.mangatranslate.app.core.model.OcrBlock
import vn.mangatranslate.app.core.model.OcrLine
import vn.mangatranslate.app.core.model.PronounStyle
import vn.mangatranslate.app.core.model.SourceLanguage
import vn.mangatranslate.app.core.model.TextRegion
import vn.mangatranslate.app.core.ocr.OCRProcessor
import vn.mangatranslate.app.core.ocr.RegionOcr
import vn.mangatranslate.app.core.ocr.RubyFilter
import vn.mangatranslate.app.core.ocr.TextRegionDetector
import vn.mangatranslate.app.core.translate.Glossary
import vn.mangatranslate.app.core.translate.GlossaryEntry
import vn.mangatranslate.app.core.translate.PronounAdapter
import vn.mangatranslate.app.core.translate.QualityChecker
import vn.mangatranslate.app.core.translate.TranslationEngine
import vn.mangatranslate.app.core.util.TextNormalizer
import vn.mangatranslate.app.core.vision.BubbleDetector
import vn.mangatranslate.app.core.vision.GrayImage
import vn.mangatranslate.app.core.vision.TextBlobFinder
import vn.mangatranslate.app.core.vision.WhiteSpaceExpander
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min

/** Lỗi có thông báo tiếng Việt hiển thị thẳng cho người dùng. */
class UserFacingException(message: String) : Exception(message)

/**
 * Quy trình một trang:
 *
 * 1. OCR toàn trang (ML Kit) để biết chỗ nào có chữ.
 * 2. Tìm bong bóng khép kín; gán các dòng chữ vào ĐÚNG bong bóng chứa nó → chữ của hai bong bóng
 *    khác nhau không bao giờ bị ghép.
 * 3. Mỗi bong bóng được cắt riêng (che phần ngoài bong bóng) và OCR lại bằng nhiều cách, chọn cách tốt nhất.
 * 4. Chữ ngoài bong bóng: gom theo khoảng cách (đã bỏ furigana) rồi cũng OCR lại từng vùng.
 * 5. Tiếng Nhật/Trung/Hàn: tìm các cụm giống chữ mà OCR toàn trang bỏ sót, đọc lại, gắn nhãn "Cần kiểm tra".
 * 6. Dịch CẢ NỘI DUNG một vùng thoại một lần (không dịch từng dòng), áp dụng bảng thuật ngữ, kiểm tra chất lượng.
 */
class PageTranslator(
    private val ocr: OCRProcessor,
    private val detector: TextRegionDetector,
    private val engine: TranslationEngine
) {

    /** [skipped]: các vùng bị bỏ qua vì lỗi (đã ghi log), không làm hỏng cả trang. */
    data class PageResult(val language: SourceLanguage, val regions: List<TextRegion>, val skipped: List<String> = emptyList())
    data class ManualResult(val language: SourceLanguage, val region: TextRegion)

    /** Vùng chữ đã đọc, chưa dịch. Tọa độ theo ảnh gốc. */
    private class Draft(
        val box: BoxF,
        val text: String,
        val vertical: Boolean,
        val lineHeight: Float,
        val layoutBox: BoxF,
        val bubble: BubbleShape?,
        val method: String,
        val notes: List<String>
    )

    private val regionOcr = RegionOcr(ocr, detector)
    private val bubbleDetector = BubbleDetector()
    private val blobFinder = TextBlobFinder()

    suspend fun translatePage(
        bitmap: Bitmap,
        requested: SourceLanguage,
        pronoun: PronounStyle,
        glossary: List<GlossaryEntry>,
        onStage: (String) -> Unit
    ): PageResult {
        val lang = resolveLanguage(bitmap, requested, onStage)
        val code = lang.code ?: throw UserFacingException("Chưa chọn ngôn ngữ nguồn.")
        val script = scriptOf(lang)

        onStage("Đang nhận dạng chữ toàn trang (${lang.label})…")
        val blocks = ocr.recognize(bitmap, script)

        onStage("Đang tìm bong bóng thoại…")
        val scaled = BitmapGray.fromBitmap(bitmap, GRAY_MAX_PIXELS)
        val gray = scaled.image
        val s = scaled.scale
        val bubbles = bubbleDetector.detect(gray)

        // Gán từng dòng OCR vào bong bóng chứa nó.
        val assigned = LinkedHashMap<Int, MutableList<OcrLine>>()
        val bubbleByLabel = HashMap<Int, BubbleDetector.Bubble>()
        val freeBlocks = ArrayList<OcrBlock>()
        for (block in blocks) {
            val free = ArrayList<OcrLine>()
            for (line in block.lines) {
                val b = bubbles.bubbleForBox(toGray(line.box, s))
                if (b != null) {
                    assigned.getOrPut(b.label) { ArrayList() } += line
                    bubbleByLabel[b.label] = b
                } else {
                    free += line
                }
            }
            if (free.isNotEmpty()) freeBlocks += OcrBlock(free, BoxF.unionOf(free.map { it.box }))
        }

        Log.d(TAG, "Trang ${bitmap.width}x${bitmap.height}, ảnh xám ${gray.width}x${gray.height} (tỉ lệ $s), " +
            "${blocks.size} khối OCR, ${bubbles.bubbles.size} bong bóng, ${assigned.size} bong bóng có chữ")

        // Mỗi vùng được xử lý độc lập: một vùng lỗi chỉ bị bỏ qua (ghi log), các vùng khác vẫn được dịch.
        val skipped = ArrayList<String>()
        val drafts = ArrayList<Draft>()
        var index = 0
        for ((label, lines) in assigned) {
            index++
            onStage("Đang đọc bong bóng $index/${assigned.size}…")
            val bubble = bubbleByLabel[label] ?: continue
            isolate("bong bóng #$index", skipped) { readBubble(bitmap, bubble, s, lines, code, script, "bong bóng #$index") }
                ?.let { drafts += it }
        }

        val freeRegions = detector.detect(freeBlocks, rightToLeft = lang == SourceLanguage.JAPANESE)
        for ((i, d) in freeRegions.withIndex()) {
            onStage("Đang đọc vùng chữ ngoài bong bóng ${i + 1}/${freeRegions.size}…")
            isolate("vùng ngoài bong bóng #${i + 1}", skipped) { readFree(bitmap, gray, s, d, code, script, "vùng #${i + 1}") }
                ?.let { drafts += it }
        }

        if (code == "ja" || code == "zh" || code == "ko") {
            onStage("Đang tìm chữ bị bỏ sót…")
            val exclude = drafts.flatMap { listOf(toGray(it.box, s), toGray(it.layoutBox, s)) }
            for ((i, cand) in blobFinder.find(gray, exclude, MAX_RESCUE).withIndex()) {
                currentCoroutineContext().ensureActive()
                val d = isolate("vùng bỏ sót #${i + 1}", skipped) { readRescue(bitmap, gray, s, cand, code, script, "bỏ sót #${i + 1}") }
                    ?: continue
                if (drafts.none { overlapRatio(it.box, d.box) > 0.4f }) drafts += d
            }
        }

        if (drafts.isEmpty()) return PageResult(lang, emptyList(), skipped)
        val ordered = readingOrder(drafts, rightToLeft = lang == SourceLanguage.JAPANESE)

        prepareModel(lang, onStage)
        val regions = ArrayList<TextRegion>(ordered.size)
        for ((i, d) in ordered.withIndex()) {
            onStage("Đang dịch vùng ${i + 1}/${ordered.size}…")
            var translateError: String? = null
            val raw = isolate("dịch vùng #${i + 1}", skipped) { translateRaw(code, d.text, glossary) }
                ?: "".also { translateError = "Lỗi khi dịch vùng này — hãy bấm “Dịch lại”" }
            val translated = finalize(raw, pronoun)
            val (rev0, inf) = review(d.text, translated, code, glossary)
            val rev = listOfNotNull(translateError) + rev0
            regions += TextRegion(
                id = i + 1,
                box = d.box,
                sourceText = d.text,
                rawTranslation = raw,
                translatedText = translated,
                vertical = d.vertical,
                lineHeight = d.lineHeight,
                layoutBox = d.layoutBox,
                bubble = d.bubble,
                reviewNotes = d.notes + rev,
                infoNotes = inf,
                ocrMethod = d.method,
                sourceLang = code
            )
        }
        return PageResult(lang, regions, skipped)
    }

    /**
     * Chạy [block] cho một vùng; lỗi của riêng vùng đó được ghi log và bỏ qua.
     * Không nuốt: hủy tác vụ (người dùng bấm Hủy) và lỗi cần báo người dùng (ví dụ không tải được mô hình).
     */
    private inline fun <T> isolate(what: String, skipped: MutableList<String>, block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: UserFacingException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Bỏ qua $what: ${e.javaClass.simpleName}: ${e.message}", e)
        skipped += what
        null
    }

    // ------------------------------------------------------------------ đọc từng loại vùng

    private suspend fun readBubble(
        bitmap: Bitmap,
        b: BubbleDetector.Bubble,
        s: Float,
        lines: List<OcrLine>,
        code: String,
        script: OCRProcessor.Script,
        tag: String
    ): Draft? {
        // Bong bóng nhỏ (ví dụ "!!") được nới tới tối thiểu 32 px; phần nới thêm nằm ngoài bong bóng nên bị tô trắng bên dưới.
        val rect = CropMath.safeCrop(fromGray(b.box, s), bitmap.width, bitmap.height)
        if (rect == null) {
            Log.w(TAG, "[$tag] vùng cắt không hợp lệ: bong bóng ${b.box} (ảnh xám), tỉ lệ $s, ảnh ${bitmap.width}x${bitmap.height}")
            return null
        }
        val l = rect.left
        val t = rect.top
        val w = rect.width
        val h = rect.height
        Log.d(TAG, "[$tag] bong bóng ${b.width}x${b.height} (ảnh xám) → cắt ($l,$t) ${w}x$h trên ảnh ${bitmap.width}x${bitmap.height}")

        // Cắt bong bóng, tô trắng mọi thứ bên ngoài để chữ/nét vẽ bên cạnh không lẫn vào.
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, l, t, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val gx = ((l + x) * s).toInt()
            val gy = ((t + y) * s).toInt()
            if (!b.isInteriorEroded(gx, gy)) px[y * w + x] = Color.WHITE
        }
        val crop = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)

        val kept = RubyFilter.filter(lines)
        val pageVertical = kept.isNotEmpty() && detector.isVertical(kept)
        val pageText = detector.buildText(kept, pageVertical)
        val hint = if (kept.isNotEmpty()) detector.estimateThickness(kept, pageVertical) else null
        val outcome = try {
            regionOcr.read(crop, code, script, pageText, pageVertical, hint, tag)
        } finally {
            crop.recycle()
        }
        val text = outcome?.text ?: pageText
        if (text.isBlank()) return null

        val textBox = outcome?.textBox?.let { offset(it, l, t) }
            ?: b.holeBounds()?.let { fromGray(it, s) }
            ?: BoxF.unionOf(lines.map { it.box })
        val inner = fromGray(b.inner, s)
        return Draft(
            box = textBox,
            text = text,
            vertical = outcome?.vertical ?: pageVertical,
            lineHeight = outcome?.charSize ?: hint ?: (min(w, h) / 6f),
            layoutBox = inner,
            bubble = BubbleShape(fromGray(b.box, s), inner, b, s),
            method = outcome?.method ?: RegionOcr.METHOD_PAGE,
            notes = outcome?.notes ?: emptyList()
        )
    }

    private suspend fun readFree(
        bitmap: Bitmap,
        gray: GrayImage,
        s: Float,
        d: TextRegionDetector.DetectedRegion,
        code: String,
        script: OCRProcessor.Script,
        tag: String
    ): Draft? {
        val cropBox = d.box.expand(d.lineHeight * 0.7f)
        val (crop, l, t) = cropOf(bitmap, cropBox, tag) ?: return null
        val outcome = try {
            regionOcr.read(crop, code, script, d.text, d.vertical, d.lineHeight, tag)
        } finally {
            crop.recycle()
        }
        val text = outcome?.text ?: d.text
        if (text.isBlank()) return null
        val textBox = outcome?.textBox?.let { offset(it, l, t) } ?: d.box
        val lh = outcome?.charSize ?: d.lineHeight
        return Draft(
            textBox, text, outcome?.vertical ?: d.vertical, lh,
            layoutAround(gray, s, textBox, lh), null,
            outcome?.method ?: RegionOcr.METHOD_PAGE, outcome?.notes ?: emptyList()
        )
    }

    private suspend fun readRescue(
        bitmap: Bitmap,
        gray: GrayImage,
        s: Float,
        candGray: BoxF,
        code: String,
        script: OCRProcessor.Script,
        tag: String
    ): Draft? {
        val cropBox = fromGray(candGray, s)
        val (crop, l, t) = cropOf(bitmap, cropBox, tag) ?: return null
        val textBoxFallback = cropBox.clampTo(bitmap.width.toFloat(), bitmap.height.toFloat())
        val outcome = (
            try {
                regionOcr.read(crop, code, script, tag = tag)
            } finally {
                crop.recycle()
            }
            ) ?: return null
        // Chỉ nhận khi kết quả thật sự giống văn bản của ngôn ngữ nguồn.
        if (outcome.validChars < 2 || outcome.invalidChars > 0 || outcome.score <= 0f) return null
        val textBox = outcome.textBox?.let { offset(it, l, t) } ?: textBoxFallback
        return Draft(
            textBox, outcome.text, outcome.vertical, outcome.charSize,
            layoutAround(gray, s, textBox, outcome.charSize), null, outcome.method,
            outcome.notes + "Vùng này OCR toàn trang đã bỏ sót — được tìm và đọc lại, hãy kiểm tra"
        )
    }

    /** OCR vùng do người dùng khoanh tay (khi nhận dạng tự động bỏ sót hoặc đọc sai). */
    suspend fun recognizeManualRegion(
        bitmap: Bitmap,
        box: BoxF,
        requested: SourceLanguage,
        pronoun: PronounStyle,
        glossary: List<GlossaryEntry>,
        id: Int,
        onStage: (String) -> Unit
    ): ManualResult {
        val b = box.clampTo(bitmap.width.toFloat(), bitmap.height.toFloat())
        if (b.width < 8f || b.height < 8f) throw UserFacingException("Vùng chọn quá nhỏ, hãy kéo rộng hơn.")
        val (crop, l, t) = cropOf(bitmap, b, "khoanh tay") ?: throw UserFacingException("Vùng chọn không hợp lệ, hãy kéo lại.")
        try {
            val lang = resolveLanguage(crop, requested, onStage)
            val code = lang.code ?: "en"
            onStage("Đang nhận dạng chữ trong vùng chọn…")
            val outcome = regionOcr.read(crop, code, scriptOf(lang), tag = "khoanh tay")
            val text = outcome?.text ?: ""
            val raw = if (text.isBlank()) "" else {
                prepareModel(lang, onStage)
                onStage("Đang dịch…")
                translateRaw(code, text, glossary)
            }
            val translated = finalize(raw, pronoun)
            val (rev, inf) = if (text.isBlank()) listOf("Không đọc được chữ — hãy tự nhập") to emptyList<String>()
            else review(text, translated, code, glossary)
            val region = TextRegion(
                id = id,
                box = outcome?.textBox?.let { offset(it, l, t) } ?: b,
                sourceText = text,
                rawTranslation = raw,
                translatedText = translated,
                vertical = outcome?.vertical ?: (b.height > b.width * 1.3f),
                lineHeight = outcome?.charSize ?: (min(b.width, b.height) * 0.25f),
                manual = true,
                layoutBox = b,
                reviewNotes = (outcome?.notes ?: emptyList()) + rev,
                infoNotes = inf,
                ocrMethod = outcome?.method?.let { "${RegionOcr.METHOD_MANUAL} · $it" } ?: RegionOcr.METHOD_MANUAL,
                sourceLang = code
            )
            return ManualResult(lang, region)
        } finally {
            crop.recycle()
        }
    }

    // ------------------------------------------------------------------ dịch

    /** Dịch lại một câu (sau khi người dùng sửa chữ gốc). Trả về bản dịch máy (đã áp dụng thuật ngữ). */
    suspend fun translateText(text: String, lang: SourceLanguage, glossary: List<GlossaryEntry>, onStage: (String) -> Unit): String {
        if (text.isBlank()) return ""
        prepareModel(lang, onStage)
        return translateRaw(lang.code ?: return "", text, glossary)
    }

    fun finalize(raw: String, pronoun: PronounStyle): String =
        TextNormalizer.cleanupVietnamese(PronounAdapter.apply(raw, pronoun))

    /** Kiểm tra OCR + bản dịch. Trả về (cần kiểm tra, ghi chú thêm). */
    fun review(source: String, translated: String, code: String, glossary: List<GlossaryEntry>): Pair<List<String>, List<String>> {
        val missing = Glossary.missingTargets(source, translated, glossary)
        val (tr, info) = QualityChecker.translationIssues(source, translated, missing, glossary.map { it.target })
        return (QualityChecker.ocrIssues(source, code) + tr) to info
    }

    private suspend fun translateRaw(code: String, text: String, glossary: List<GlossaryEntry>): String {
        if (text.isBlank()) return ""
        val prepared = Glossary.applyBefore(TextNormalizer.prepareForTranslation(text, code), glossary)
        return Glossary.applyAfter(engine.translate(code, prepared), glossary)
    }

    /** Đoán ngôn ngữ dựa trên loại ký tự đọc được (Kana → Nhật, Hangul → Hàn, chỉ Hán → Trung, Latin → Anh). */
    suspend fun detectLanguage(bitmap: Bitmap): SourceLanguage? {
        var kana = 0
        var han = 0
        var latin = 0
        ocr.recognize(bitmap, OCRProcessor.Script.JAPANESE, maxTiles = 2).forEach { b ->
            b.lines.forEach { l ->
                l.text.forEach { c ->
                    when (Character.UnicodeScript.of(c.code)) {
                        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                        Character.UnicodeScript.HAN -> han++
                        Character.UnicodeScript.LATIN -> latin++
                        else -> {}
                    }
                }
            }
        }
        if (kana >= 3) return SourceLanguage.JAPANESE

        var hangul = 0
        ocr.recognize(bitmap, OCRProcessor.Script.KOREAN, maxTiles = 2).forEach { b ->
            b.lines.forEach { l -> l.text.forEach { c -> if (Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HANGUL) hangul++ } }
        }
        return when {
            hangul >= 3 && hangul >= han -> SourceLanguage.KOREAN
            han >= 3 -> SourceLanguage.CHINESE
            kana >= 1 -> SourceLanguage.JAPANESE
            latin >= 4 -> SourceLanguage.ENGLISH
            else -> null
        }
    }

    private suspend fun resolveLanguage(bitmap: Bitmap, requested: SourceLanguage, onStage: (String) -> Unit): SourceLanguage {
        if (requested != SourceLanguage.AUTO) return requested
        onStage("Đang đoán ngôn ngữ của truyện…")
        return detectLanguage(bitmap)
            ?: throw UserFacingException("Không xác định được ngôn ngữ. Hãy chọn ngôn ngữ nguồn trong Cài đặt.")
    }

    private suspend fun prepareModel(lang: SourceLanguage, onStage: (String) -> Unit) {
        val code = lang.code ?: throw UserFacingException("Chưa chọn ngôn ngữ nguồn.")
        val ready = try { engine.isModelReady(code) } catch (e: Exception) { false }
        if (ready) return
        onStage("Đang tải mô hình dịch ${lang.label} → Việt (~30 MB, chỉ lần đầu, cần Internet)…")
        try {
            engine.downloadModel(code)
        } catch (e: Exception) {
            throw UserFacingException(
                "Không tải được mô hình dịch. Lần đầu dùng mỗi ngôn ngữ cần Internet; sau đó dịch offline. (${e.message ?: "lỗi mạng"})"
            )
        }
    }

    // ------------------------------------------------------------------ tiện ích hình học

    private fun layoutAround(gray: GrayImage, s: Float, textBox: BoxF, lineHeight: Float): BoxF {
        val g = WhiteSpaceExpander.expand(gray, toGray(textBox, s), absorb = 0.7f * lineHeight * s, maxGrow = 2.5f * lineHeight * s)
        return fromGray(g, s).union(textBox)
    }

    private data class Crop(val bitmap: Bitmap, val left: Int, val top: Int)

    /**
     * Cắt vùng an toàn: kẹp tọa độ âm/vượt mép, loại vùng ngoài ảnh hoặc NaN, nới vùng < 32 px quanh tâm
     * (trong giới hạn ảnh gốc). Trả về null (và ghi log) nếu không thể cắt.
     */
    private fun cropOf(bitmap: Bitmap, box: BoxF, tag: String): Crop? {
        val rect = CropMath.safeCrop(box, bitmap.width, bitmap.height)
        if (rect == null) {
            Log.w(TAG, "[$tag] vùng cắt không hợp lệ $box trên ảnh ${bitmap.width}x${bitmap.height} — bỏ qua")
            return null
        }
        Log.d(TAG, "[$tag] cắt ($box) → (${rect.left},${rect.top}) ${rect.width}x${rect.height} trên ảnh ${bitmap.width}x${bitmap.height}")
        var crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
        // createBitmap có thể trả về CHÍNH ảnh gốc khi cắt trọn ảnh — sao chép để không lỡ giải phóng ảnh gốc.
        if (crop === bitmap) crop = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        return Crop(crop, rect.left, rect.top)
    }

    private fun toGray(b: BoxF, s: Float) = BoxF(b.left * s, b.top * s, b.right * s, b.bottom * s)
    private fun fromGray(b: BoxF, s: Float) = BoxF(b.left / s, b.top / s, b.right / s, b.bottom / s)
    private fun offset(b: BoxF, dx: Int, dy: Int) = BoxF(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy)

    private fun overlapRatio(a: BoxF, b: BoxF): Float = a.intersectionArea(b) / max(1f, min(a.area, b.area))

    private fun readingOrder(drafts: List<Draft>, rightToLeft: Boolean): List<Draft> {
        if (drafts.size < 2) return drafts
        val band = max(1f, TextRegionDetector.median(drafts.map { it.box.height }) * 0.6f)
        val rows = ArrayList<MutableList<Draft>>()
        var anchor = Float.NaN
        for (d in drafts.sortedBy { it.box.top }) {
            if (rows.isEmpty() || d.box.top - anchor > band) {
                rows += mutableListOf(d)
                anchor = d.box.top
            } else {
                rows.last() += d
            }
        }
        return rows.flatMap { row ->
            if (rightToLeft) row.sortedByDescending { it.box.centerX } else row.sortedBy { it.box.centerX }
        }
    }

    private fun scriptOf(lang: SourceLanguage): OCRProcessor.Script = when (lang) {
        SourceLanguage.CHINESE -> OCRProcessor.Script.CHINESE
        SourceLanguage.JAPANESE -> OCRProcessor.Script.JAPANESE
        SourceLanguage.KOREAN -> OCRProcessor.Script.KOREAN
        SourceLanguage.ENGLISH, SourceLanguage.AUTO -> OCRProcessor.Script.LATIN
    }

    private companion object {
        const val GRAY_MAX_PIXELS = 4_000_000L
        const val MAX_RESCUE = 8
        const val TAG = "MangaOCR"
    }
}
