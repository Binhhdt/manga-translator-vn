package vn.mangatranslate.app.core.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * Dịch sang tiếng Việt bằng ML Kit On-device Translation (miễn phí, không giới hạn, không API key).
 *
 * - Mô hình mỗi ngôn ngữ ~30 MB, tải một lần (cần Internet), sau đó dịch hoàn toàn offline.
 * - Translator được cache theo ngôn ngữ nguồn; kết quả dịch được cache theo (ngôn ngữ, câu).
 *
 * Hạn chế trung thực: đây là mô hình dịch câu đơn lẻ, không hiểu ngữ cảnh truyện, không biết ai nói
 * với ai; Trung/Nhật/Hàn → Việt chất lượng thấp hơn Anh → Việt. Văn phong sẽ "máy", cần chỉnh tay.
 */
class TranslationEngine {

    /** ML Kit chỉ có mô hình dịch từ/sang tiếng Anh; cặp Nhật→Việt được dịch qua tiếng Anh trung gian (theo tài liệu Google). */
    val name: String = "Google ML Kit (Nhật → Anh → Việt, offline)"

    private val translators = HashMap<String, Translator>()
    private val modelManager = RemoteModelManager.getInstance()
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > CACHE_SIZE
    }

    private fun translator(sourceCode: String): Translator = synchronized(translators) {
        translators.getOrPut(sourceCode) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceCode)
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build()
            Translation.getClient(options)
        }
    }

    suspend fun isModelReady(sourceCode: String): Boolean {
        val src = modelManager.isModelDownloaded(TranslateRemoteModel.Builder(sourceCode).build()).await()
        val vi = modelManager.isModelDownloaded(TranslateRemoteModel.Builder(TranslateLanguage.VIETNAMESE).build()).await()
        return src && vi
    }

    /** Tải mô hình nguồn + tiếng Việt nếu chưa có. Cần Internet ở lần đầu. */
    suspend fun downloadModel(sourceCode: String) {
        translator(sourceCode).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    suspend fun translate(sourceCode: String, text: String): String {
        if (text.isBlank()) return ""
        val key = sourceCode + "\u0001" + text
        synchronized(cache) { cache[key] }?.let { return it }
        val result = translator(sourceCode).translate(text).await()
        synchronized(cache) { cache[key] = result }
        return result
    }

    fun close() {
        synchronized(translators) {
            translators.values.forEach { it.close() }
            translators.clear()
        }
    }

    private companion object {
        const val CACHE_SIZE = 3000
    }
}
