package vn.mangatranslate.app.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.mangatranslate.app.core.util.NaturalOrderComparator
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Một trang truyện: chỉ giữ Uri, KHÔNG giữ ảnh trong RAM. */
data class PageSource(val uri: Uri, val name: String)

class LoadedImage(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int, val sampleSize: Int)

/**
 * Nạp danh sách trang (ảnh lẻ, thư mục, CBZ) qua Storage Access Framework và giải mã ảnh.
 * Không cần quyền đọc bộ nhớ.
 */
class ImageLoader(private val context: Context) {

    private val resolver get() = context.contentResolver

    fun pagesFromUris(uris: List<Uri>): List<PageSource> =
        uris.map { PageSource(it, displayName(it)) }.sortedWith(compareBy(NaturalOrderComparator) { it.name })

    /** Liệt kê ảnh trong thư mục được chọn (không đệ quy), sắp xếp tự nhiên. */
    fun pagesFromTree(treeUri: Uri): List<PageSource> {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val out = ArrayList<PageSource>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                if (isImage(name, mime)) {
                    out += PageSource(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name)
                }
            }
        }
        return out.sortedWith(compareBy(NaturalOrderComparator) { it.name })
    }

    fun treeName(treeUri: Uri): String {
        val id = DocumentsContract.getTreeDocumentId(treeUri)
        return id.substringAfterLast(':').substringAfterLast('/').ifBlank { "ThuMuc" }
    }

    /** Giải nén ảnh trong CBZ (zip) vào bộ nhớ đệm của ứng dụng rồi trả về danh sách trang. */
    fun pagesFromCbz(uri: Uri): List<PageSource> {
        val root = File(context.cacheDir, "cbz")
        root.deleteRecursively()
        val dir = File(root, "current")
        if (!dir.mkdirs()) throw IOException("Không tạo được thư mục tạm")
        val entries = ArrayList<Pair<String, File>>()
        val stream = resolver.openInputStream(uri) ?: throw IOException("Không mở được tệp CBZ")
        ZipInputStream(BufferedInputStream(stream)).use { zip ->
            var index = 0
            var entry = zip.nextEntry
            while (entry != null) {
                val entryName = entry.name
                if (!entry.isDirectory && isImage(entryName, "")) {
                    // Tự đặt tên tệp an toàn (tránh lỗi "zip slip").
                    val baseName = entryName.substringAfterLast('/').substringAfterLast('\\')
                    val safe = baseName.replace(Regex("[^\\w.\\-]"), "_")
                    val outFile = File(dir, String.format("%05d_%s", index++, safe))
                    outFile.outputStream().use { zip.copyTo(it) }
                    entries += entryName to outFile
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
            .sortedWith(compareBy(NaturalOrderComparator) { it.first })
            .map { (name, file) -> PageSource(Uri.fromFile(file), name.substringAfterLast('/')) }
    }

    fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "trang"
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "trang"
    }

    /**
     * Giải mã ảnh ở độ phân giải gốc. Chỉ giảm độ phân giải (inSampleSize) khi ảnh vượt [maxPixels]
     * để tránh OutOfMemoryError trên máy RAM 4 GB.
     */
    suspend fun loadFull(uri: Uri, maxPixels: Long = MAX_PIXELS): LoadedImage = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Định dạng ảnh không được hỗ trợ")
        var sample = 1
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > maxPixels) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = open(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Không giải mã được ảnh")
        LoadedImage(bmp, bounds.outWidth, bounds.outHeight, sample)
    }

    private fun open(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("Không mở được $uri")

    companion object {
        /** ~30 MP ≈ 120 MB ARGB — đủ cho trang manga và phần lớn chương manhwa dạng dải dài. */
        const val MAX_PIXELS = 30_000_000L
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

        fun isImage(name: String, mime: String): Boolean =
            mime.startsWith("image/") || name.substringAfterLast('.', "").lowercase() in IMAGE_EXT
    }
}
