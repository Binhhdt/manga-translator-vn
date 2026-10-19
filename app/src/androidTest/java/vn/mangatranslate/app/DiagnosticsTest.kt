package vn.mangatranslate.app

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Test
import org.junit.runner.RunWith
import vn.mangatranslate.app.core.model.DiagReport
import vn.mangatranslate.app.core.model.PronounStyle
import vn.mangatranslate.app.core.model.SourceLanguage
import vn.mangatranslate.app.core.ocr.OCRProcessor
import vn.mangatranslate.app.core.ocr.TextRegionDetector
import vn.mangatranslate.app.core.pipeline.PageTranslator
import vn.mangatranslate.app.core.translate.TranslationEngine
import vn.mangatranslate.app.core.util.TextNormalizer
import java.io.File

/**
 * Bài CHẨN ĐOÁN (không phải kiểm thử đạt/không đạt): chạy OCR + ML Kit THẬT trên trình giả lập và ghi lại
 * từng bước để biết lỗi nằm ở OCR, ở bước ghép chữ hay ở bộ dịch.
 * Kết quả: files/diag.txt của ứng dụng (CI đọc bằng `run-as`).
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsTest {

    /** Câu nhập tay để thử bộ dịch ĐỘC LẬP với OCR. Chỉ là đầu vào — không có bản dịch đáp án nào trong mã. */
    private val sentences = listOf(
        "さわらないで",
        "黒崎",
        "神奈川 GK 負傷!!",
        "腕を骨折",
        "大丈夫かよ",
        "こんなブザマはさらさなかったのによ",
        // Các bong bóng khác của trang 006 (chép tay từ ảnh để thử bộ dịch).
        "黒崎おいっ大丈夫かよォ……",
        "さわらないで――",
        "オーイ救急班―",
        "こりゃみごとに折れてるな――……",
        "あまりの鹿児島のパワーに腕を骨折――!!",
        "残念だなァ……",
        "いててて……クソ受け身がとれてたら……"
    )

    private val out = StringBuilder()
    private lateinit var file: File

    private fun emit(line: String = "") {
        out.appendLine(line)
        Log.i("MangaDiag", line)
        file.writeText(out.toString())
    }

    @Test
    fun diagnose() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        file = File(inst.targetContext.filesDir, "diag.txt")
        emit("##### CHẨN ĐOÁN Manga Translator VN #####")

        // ---------- 1. Bộ dịch độc lập với OCR ----------
        val engine = TranslationEngine()
        emit("== 1. Bộ dịch: ${engine.name} ==")
        try {
            val t0 = System.currentTimeMillis()
            engine.downloadModel("ja")
            emit("Tải mô hình ja/vi: ${System.currentTimeMillis() - t0} ms")
            // Bản dịch Nhật → Anh của ML Kit, để thấy bước trung gian tiếng Anh.
            val jaEn = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.JAPANESE)
                    .setTargetLanguage(TranslateLanguage.ENGLISH).build()
            )
            jaEn.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            val enVi = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.VIETNAMESE).build()
            )
            enVi.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            for (s in sentences) {
                val raw = engine.translate("ja", s)
                val prepared = TextNormalizer.prepareForTranslation(s, "ja")
                val viaPrep = if (prepared != s) engine.translate("ja", prepared) else raw
                val en = jaEn.translate(s).await()
                val enThenVi = enVi.translate(en).await()
                emit("«$s»")
                emit("    A. ML Kit ja→vi trực tiếp (nguyên văn): «$raw»")
                if (prepared != s) emit("       sau chuẩn hóa «$prepared» → «$viaPrep»")
                emit("    B. ML Kit ja→en: «$en»")
                emit("    C. ML Kit en→vi của B: «$enThenVi»   ${if (enThenVi == raw) "(= A → xác nhận dịch qua tiếng Anh)" else "(≠ A)"}")
                // Thử các cách giữ tên riêng romaji qua bước Anh→Việt.
                val names = vn.mangatranslate.app.core.translate.RomajiNames.find(en)
                if (names.isNotEmpty()) {
                    emit("       tên romaji tìm thấy: $names")
                    val styles = listOf("X%d", "[%d]", "#%d", "QQ%d", "<%d>")
                    for (st in styles) {
                        var masked = en
                        names.forEachIndexed { i, n -> masked = masked.replace(n, st.format(i + 1)) }
                        val v = enVi.translate(masked).await()
                        var restored = v
                        names.forEachIndexed { i, n -> restored = restored.replace(st.format(i + 1), n) }
                        val kept = names.all { restored.contains(it) }
                        emit("       giữ tên kiểu '$st': «$masked» → «$v» → ${if (kept) "GIỮ ĐƯỢC" else "MẤT"} «$restored»")
                    }
                }
            }
            jaEn.close()
            enVi.close()
        } catch (e: Exception) {
            emit("LỖI bộ dịch: ${e.javaClass.simpleName}: ${e.message}")
        }

        // ---------- 2. Toàn bộ quy trình trên trang 006 ----------
        emit()
        emit("== 2. Quy trình đầy đủ trên trang 006 (OCR + ghép + dịch) ==")
        try {
            val bmp = inst.context.assets.open("page006.png").use { BitmapFactory.decodeStream(it) }
            val pt = PageTranslator(OCRProcessor(), TextRegionDetector(), engine)
            val t0 = System.currentTimeMillis()
            val res = pt.translatePage(bmp, SourceLanguage.JAPANESE, PronounStyle.KEEP, emptyList()) { }
            emit("Thời gian: ${System.currentTimeMillis() - t0} ms; vùng bỏ qua vì lỗi: ${res.skipped}")
            emit(DiagReport.format("Trang 006", bmp.width, bmp.height, res.regions) { "" })
        } catch (e: Exception) {
            emit("LỖI quy trình: ${e.javaClass.simpleName}: ${e.message}")
            emit(Log.getStackTraceString(e))
        }
        emit("##### HẾT #####")
    }
}
