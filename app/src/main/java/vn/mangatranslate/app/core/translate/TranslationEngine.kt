package vn.mangatranslate.app.core.translate

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * Dịch sang tiếng Việt, miễn phí, offline sau khi tải mô hình.
 *
 * Kết quả đo thật (trang 006, trình giả lập): ML Kit dịch Nhật→Việt QUA TIẾNG ANH, và bước Nhật→Anh của ML Kit
 * là chỗ sai chính ("黒崎"→"Blacklord", "腕を骨折"→"Arms fracture"→"Giũa vũ khí"). Opus-MT Nhật→Anh đọc đúng hơn
 * ("Kurosaki", "I broke my arm…"). Vì vậy với tiếng Nhật:
 *
 *  Nhật ──(Opus-MT, trong ứng dụng)──▶ Anh ──(giữ tên romaji bằng QQn)──▶ ML Kit Anh→Việt ──▶ Việt
 *
 * Nếu bản build không kèm Opus-MT hoặc người dùng chọn "Chỉ ML Kit": ML Kit Nhật→Anh rồi Anh→Việt (tương đương
 * ML Kit Nhật→Việt — đã xác nhận trùng 13/13 câu), vẫn giữ tên romaji.
 * Trung/Hàn/Anh: ML Kit trực tiếp.
 */
class TranslationEngine(private val context: Context? = null) {

    enum class JaMode { OPUS_PIVOT, MLKIT_ONLY }

    /** Kết quả từng bước. [pivot]: câu tiếng Anh trung gian (null nếu dịch trực tiếp). */
    data class Result(val output: String, val pivot: String?, val method: String, val lostNames: List<String>)

    @Volatile
    var jaMode: JaMode = JaMode.OPUS_PIVOT

    private val translators = HashMap<String, Translator>()
    private val modelManager = RemoteModelManager.getInstance()
    private val cache = object : LinkedHashMap<String, Result>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?): Boolean = size > CACHE_SIZE
    }

    private var opus: MarianTranslator? = null
    private var opusFailed = false

    /** Có Opus-MT Nhật→Anh trong bản build này không. */
    val opusAvailable: Boolean by lazy { context != null && MarianTranslator.available(context, OPUS_JA_EN) }

    val name: String
        get() = if (jaMode == JaMode.OPUS_PIVOT && opusAvailable && !opusFailed) NAME_OPUS else NAME_MLKIT

    private fun opusOrNull(): MarianTranslator? = synchronized(this) {
        if (jaMode != JaMode.OPUS_PIVOT || !opusAvailable || opusFailed || context == null) return null
        opus ?: try {
            MarianTranslator.fromAssets(context, OPUS_JA_EN).also { opus = it }
        } catch (e: Throwable) {
            Log.e(TAG, "Không nạp được Opus-MT, dùng ML Kit: ${e.message}", e)
            opusFailed = true
            null
        }
    }

    private fun translator(source: String, target: String): Translator = synchronized(translators) {
        translators.getOrPut("$source>$target") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        }
    }

    suspend fun isModelReady(sourceCode: String): Boolean {
        val src = sourceCode == TranslateLanguage.ENGLISH ||
            modelManager.isModelDownloaded(TranslateRemoteModel.Builder(sourceCode).build()).await()
        val vi = modelManager.isModelDownloaded(TranslateRemoteModel.Builder(TranslateLanguage.VIETNAMESE).build()).await()
        return src && vi
    }

    /** Tải mô hình ML Kit (nguồn + tiếng Việt) nếu chưa có. Cần Internet ở lần đầu. */
    suspend fun downloadModel(sourceCode: String) {
        val cond = DownloadConditions.Builder().build()
        translator(sourceCode, TranslateLanguage.VIETNAMESE).downloadModelIfNeeded(cond).await()
        if (sourceCode == TranslateLanguage.JAPANESE) {
            translator(TranslateLanguage.JAPANESE, TranslateLanguage.ENGLISH).downloadModelIfNeeded(cond).await()
            translator(TranslateLanguage.ENGLISH, TranslateLanguage.VIETNAMESE).downloadModelIfNeeded(cond).await()
        }
    }

    suspend fun translate(sourceCode: String, text: String): String = translateDetailed(sourceCode, text).output

    suspend fun translateDetailed(sourceCode: String, text: String, mode: JaMode = jaMode): Result {
        if (text.isBlank()) return Result("", null, name, emptyList())
        val key = "$sourceCode\u0001$mode\u0001$text"
        synchronized(cache) { cache[key] }?.let { return it }
        val result = if (sourceCode == TranslateLanguage.JAPANESE) translateJapanese(text, mode)
        else Result(translator(sourceCode, TranslateLanguage.VIETNAMESE).translate(text).await(), null, NAME_MLKIT_DIRECT, emptyList())
        synchronized(cache) { cache[key] = result }
        return result
    }

    private suspend fun translateJapanese(text: String, mode: JaMode): Result {
        val marian = if (mode == JaMode.OPUS_PIVOT) opusOrNull() else null
        val english = marian?.translate(text)?.text
            ?: translator(TranslateLanguage.JAPANESE, TranslateLanguage.ENGLISH).translate(text).await()
        val (masked, names) = RomajiNames.mask(english)
        val vi = translator(TranslateLanguage.ENGLISH, TranslateLanguage.VIETNAMESE).translate(masked).await()
        val (restored, lost) = RomajiNames.restore(vi, names)
        return Result(restored, english, if (marian != null) NAME_OPUS else NAME_MLKIT, lost)
    }

    fun close() {
        synchronized(translators) {
            translators.values.forEach { it.close() }
            translators.clear()
        }
        synchronized(this) {
            opus?.close()
            opus = null
        }
    }

    companion object {
        const val OPUS_JA_EN = "mt/ja-en"
        const val NAME_OPUS = "Opus-MT Nhật→Anh (offline, trong ứng dụng) + ML Kit Anh→Việt"
        const val NAME_MLKIT = "ML Kit Nhật→Anh→Việt (offline)"
        const val NAME_MLKIT_DIRECT = "ML Kit (offline)"
        private const val CACHE_SIZE = 3000
        private const val TAG = "MangaMT"
    }
}
