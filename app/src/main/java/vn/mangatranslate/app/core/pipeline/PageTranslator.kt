package vn.mangatranslate.app.core.pipeline

import android.graphics.Bitmap
import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.model.PronounStyle
import vn.mangatranslate.app.core.model.SourceLanguage
import vn.mangatranslate.app.core.model.TextRegion
import vn.mangatranslate.app.core.ocr.OCRProcessor
import vn.mangatranslate.app.core.ocr.TextRegionDetector
import vn.mangatranslate.app.core.translate.PronounAdapter
import vn.mangatranslate.app.core.translate.TranslationEngine
import vn.mangatranslate.app.core.util.TextNormalizer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Lỗi có thông báo tiếng Việt hiển thị thẳng cho người dùng. */
class UserFacingException(message: String) : Exception(message)

/**
 * Điều phối quy trình một trang: (đoán ngôn ngữ) → OCR → gom vùng thoại → dịch → đổi xưng hô.
 * Không vẽ — việc vẽ do VietnameseTextRenderer đảm nhận để có thể vẽ lại ngay khi người dùng sửa chữ.
 */
class PageTranslator(
    private val ocr: OCRProcessor,
    private val detector: TextRegionDetector,
    private val engine: TranslationEngine
) {

    data class PageResult(val language: SourceLanguage, val regions: List<TextRegion>)
    data class ManualResult(val language: SourceLanguage, val region: TextRegion)

    suspend fun translatePage(
        bitmap: Bitmap,
        requested: SourceLanguage,
        pronoun: PronounStyle,
        onStage: (String) -> Unit
    ): PageResult {
        val lang = resolveLanguage(bitmap, requested, onStage)
        onStage("Đang nhận dạng chữ (${lang.label})…")
        val blocks = ocr.recognize(bitmap, scriptOf(lang))
        val detected = detector.detect(blocks, rightToLeft = lang == SourceLanguage.JAPANESE)
        if (detected.isEmpty()) return PageResult(lang, emptyList())

        prepareModel(lang, onStage)
        val regions = ArrayList<TextRegion>(detected.size)
        detected.forEachIndexed { i, d ->
            onStage("Đang dịch vùng ${i + 1}/${detected.size}…")
            val raw = translateRaw(lang, d.text)
            regions += TextRegion(
                id = i + 1,
                box = d.box,
                sourceText = d.text,
                rawTranslation = raw,
                translatedText = finalize(raw, pronoun),
                vertical = d.vertical,
                lineHeight = d.lineHeight
            )
        }
        return PageResult(lang, regions)
    }

    /** OCR vùng do người dùng khoanh tay (khi nhận dạng tự động bỏ sót hoặc đọc sai). */
    suspend fun recognizeManualRegion(
        bitmap: Bitmap,
        box: BoxF,
        requested: SourceLanguage,
        pronoun: PronounStyle,
        id: Int,
        onStage: (String) -> Unit
    ): ManualResult {
        val b = box.clampTo(bitmap.width.toFloat(), bitmap.height.toFloat())
        val left = floor(b.left).toInt()
        val top = floor(b.top).toInt()
        val width = min(bitmap.width - left, max(1, ceil(b.right).toInt() - left))
        val height = min(bitmap.height - top, max(1, ceil(b.bottom).toInt() - top))
        if (width < 8 || height < 8) throw UserFacingException("Vùng chọn quá nhỏ, hãy kéo rộng hơn.")

        val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
        try {
            val lang = resolveLanguage(crop, requested, onStage)
            onStage("Đang nhận dạng chữ trong vùng chọn…")
            val upscale = (1200f / max(width, height)).coerceIn(1f, 4f)
            val lines = ocr.recognize(crop, scriptOf(lang), upscale = upscale).flatMap { it.lines }
            val vertical = (lang == SourceLanguage.JAPANESE || lang == SourceLanguage.CHINESE) && height > width * 1.3f
            val text = detector.buildText(lines, vertical)
            val raw = if (text.isBlank()) "" else {
                prepareModel(lang, onStage)
                onStage("Đang dịch…")
                translateRaw(lang, text)
            }
            val lineHeight = if (lines.isEmpty()) min(width, height) * 0.25f
            else detector.estimateThickness(lines, vertical)
            val region = TextRegion(
                id = id,
                box = BoxF(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat()),
                sourceText = text,
                rawTranslation = raw,
                translatedText = finalize(raw, pronoun),
                vertical = vertical,
                lineHeight = lineHeight,
                manual = true
            )
            return ManualResult(lang, region)
        } finally {
            if (crop !== bitmap) crop.recycle()
        }
    }

    /** Dịch lại một câu (sau khi người dùng sửa chữ OCR). Trả về bản dịch máy thô. */
    suspend fun translateText(text: String, lang: SourceLanguage, onStage: (String) -> Unit): String {
        if (text.isBlank()) return ""
        prepareModel(lang, onStage)
        return translateRaw(lang, text)
    }

    fun finalize(raw: String, pronoun: PronounStyle): String =
        TextNormalizer.cleanupVietnamese(PronounAdapter.apply(raw, pronoun))

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

    private suspend fun translateRaw(lang: SourceLanguage, text: String): String {
        val code = lang.code ?: return ""
        return engine.translate(code, TextNormalizer.prepareForTranslation(text, code))
    }

    private fun scriptOf(lang: SourceLanguage): OCRProcessor.Script = when (lang) {
        SourceLanguage.CHINESE -> OCRProcessor.Script.CHINESE
        SourceLanguage.JAPANESE -> OCRProcessor.Script.JAPANESE
        SourceLanguage.KOREAN -> OCRProcessor.Script.KOREAN
        SourceLanguage.ENGLISH, SourceLanguage.AUTO -> OCRProcessor.Script.LATIN
    }
}
