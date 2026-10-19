package vn.mangatranslate.app.core.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

enum class ExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
    val compressFormat: Bitmap.CompressFormat,
    val quality: Int
) {
    JPG("JPG — nhẹ, chất lượng 95", "jpg", "image/jpeg", Bitmap.CompressFormat.JPEG, 95),
    PNG("PNG — không mất chất lượng, nặng hơn", "png", "image/png", Bitmap.CompressFormat.PNG, 100)
}

/**
 * Lưu ảnh đã dịch vào Thư viện ảnh: Pictures/MangaTranslateVN/<TenTruyen>/.
 * Luôn tạo tệp MỚI (hậu tố "_vi"), không bao giờ ghi đè ảnh gốc.
 * Android 10+: MediaStore (không cần quyền). Android 8–9: cần quyền WRITE_EXTERNAL_STORAGE.
 */
class ImageExporter(private val context: Context) {

    suspend fun saveToGallery(
        bitmap: Bitmap,
        baseName: String,
        format: ExportFormat,
        subFolder: String
    ): String = withContext(Dispatchers.IO) {
        val name = sanitize(baseName).ifBlank { "trang" }
        val folder = sanitize(subFolder).ifBlank { "DaDich" }
        val fileName = "$name.${format.extension}"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = "${Environment.DIRECTORY_PICTURES}/$ROOT/$folder"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("Không tạo được tệp trong Thư viện ảnh")
            try {
                val os = resolver.openOutputStream(uri) ?: throw IOException("Không ghi được tệp")
                os.use {
                    if (!bitmap.compress(format.compressFormat, format.quality, it)) throw IOException("Nén ảnh thất bại")
                }
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            "$relativePath/$fileName"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "$ROOT/$folder")
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Không tạo được thư mục ${dir.absolutePath}")
            var file = File(dir, fileName)
            var n = 1
            while (file.exists()) {
                file = File(dir, "$name ($n).${format.extension}")
                n++
            }
            FileOutputStream(file).use {
                if (!bitmap.compress(format.compressFormat, format.quality, it)) throw IOException("Nén ảnh thất bại")
            }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(format.mimeType), null)
            file.absolutePath
        }
    }

    companion object {
        const val ROOT = "MangaTranslateVN"

        /** Bỏ ký tự không hợp lệ trong tên tệp/thư mục, giữ nguyên chữ có dấu tiếng Việt. */
        fun sanitize(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80)
    }
}
