package vn.mangatranslate.app.core.chatgpt

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Lưu kết quả thử nhiều trang (giai đoạn D) thành tệp văn bản người dùng mở được:
 * Android 10+: Download/MangaTranslateVN/chatgpt_test/<lượt>/ (MediaStore, không cần quyền).
 * Android 8–9: Android/data/vn.mangatranslate.app/files/chatgpt_test/<lượt>/.
 * Chỉ ghi văn bản (JSON, báo cáo); không chứa token. Ảnh gốc không bị đụng tới.
 */
class TestRunWriter(private val ctx: Context, private val run: String, private val folder: String = "chatgpt_test") {

    val location: String = if (Build.VERSION.SDK_INT >= 29) "Download/MangaTranslateVN/$folder/$run"
    else File(ctx.getExternalFilesDir(null), "$folder/$run").absolutePath

    /** Ghi (hoặc ghi đè trên Android 8–9) một tệp. Trên Android 10+, ghi lại cùng tên sẽ tạo tệp mới kèm số. */
    fun write(name: String, text: String, mime: String = "text/plain") = writeBytes(name, text.toByteArray(Charsets.UTF_8), mime)

    fun writeBytes(name: String, bytes: ByteArray, mime: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MangaTranslateVN/$folder/$run")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Không tạo được tệp $name trong Download")
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        } else {
            val dir = File(location).apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
        }
    }
}
