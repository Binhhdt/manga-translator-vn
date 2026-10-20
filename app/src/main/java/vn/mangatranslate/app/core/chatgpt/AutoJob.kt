package vn.mangatranslate.app.core.chatgpt

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/** Trạng thái một trang trong lượt dịch tự động. */
enum class PageStatus(val label: String) { PENDING("chưa xử lý"), DONE("xong"), FAILED("lỗi") }

/**
 * Một trang. [jsonCached] = đã có JSON của ChatGPT trong bộ nhớ riêng → render lại không tốn hạn mức.
 * [outImageUri]/[outJsonUri] = tệp công khai đã lưu (để xóa khi làm lại, tránh trùng).
 */
data class AutoPage(
    val index: Int,
    val uri: String,
    val name: String,
    val status: PageStatus = PageStatus.PENDING,
    val error: String? = null,
    val jsonCached: Boolean = false,
    val aiRegions: Int = 0,
    val rendered: Int = 0,
    val japaneseLeft: Int = 0,
    val keptOnPurpose: Int = 0,
    val reasons: Map<String, Int> = emptyMap(),
    val tokens: Long? = null,
    val seconds: Double = 0.0,
    val retries: Int = 0,
    val verified: String? = null,
    val outImageUri: String? = null,
    val outJsonUri: String? = null,
    /** Kiểm tra sau render: đã chèn / B có bản dịch nhưng chưa chèn / C nghi còn chữ Nhật / A nghi thiếu bản dịch. */
    val okCount: Int = 0,
    val notInserted: Int = 0,
    val suspectedJp: Int = 0,
    val missing: Int = 0,
    /** Khung (px ảnh gốc: trái, trên, phải, dưới) các vùng nghi thiếu bản dịch — để dịch bổ sung riêng. */
    val missingBoxes: List<List<Int>> = emptyList(),
    /** Danh sách vùng cần xử lý (số thứ tự khớp ảnh DEBUG). */
    val issues: List<String> = emptyList(),
    val outDebugUri: String? = null
) {
    /** Số vùng cần xử lý / kiểm tra thủ công. */
    val needsReview: Int get() = notInserted + suspectedJp + missing

    fun toJson(): JSONObject = JSONObject().put("index", index).put("uri", uri).put("name", name)
        .put("status", status.name).put("error", error).put("jsonCached", jsonCached)
        .put("aiRegions", aiRegions).put("rendered", rendered).put("japaneseLeft", japaneseLeft)
        .put("keptOnPurpose", keptOnPurpose).put("reasons", JSONObject(reasons as Map<*, *>))
        .put("tokens", tokens).put("seconds", seconds).put("retries", retries).put("verified", verified)
        .put("outImageUri", outImageUri).put("outJsonUri", outJsonUri)
        .put("okCount", okCount).put("notInserted", notInserted).put("suspectedJp", suspectedJp).put("missing", missing)
        .put("missingBoxes", JSONArray().apply { missingBoxes.forEach { b -> put(JSONArray(b)) } })
        .put("issues", JSONArray(issues)).put("outDebugUri", outDebugUri)

    companion object {
        fun fromJson(j: JSONObject): AutoPage {
            val r = j.optJSONObject("reasons")
            val reasons = LinkedHashMap<String, Int>()
            r?.keys()?.forEach { k -> reasons[k] = r.optInt(k) }
            return AutoPage(
                j.getInt("index"), j.getString("uri"), j.getString("name"),
                runCatching { PageStatus.valueOf(j.optString("status")) }.getOrDefault(PageStatus.PENDING),
                j.optString("error").ifEmpty { null }.takeIf { !j.isNull("error") },
                j.optBoolean("jsonCached"), j.optInt("aiRegions"), j.optInt("rendered"), j.optInt("japaneseLeft"),
                j.optInt("keptOnPurpose"), reasons, if (j.isNull("tokens")) null else j.optLong("tokens"),
                j.optDouble("seconds", 0.0), j.optInt("retries"),
                if (j.isNull("verified")) null else j.optString("verified"),
                if (j.isNull("outImageUri")) null else j.optString("outImageUri"),
                if (j.isNull("outJsonUri")) null else j.optString("outJsonUri"),
                j.optInt("okCount"), j.optInt("notInserted"), j.optInt("suspectedJp"), j.optInt("missing"),
                j.optJSONArray("missingBoxes")?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { b -> (0 until b.length()).map { k -> b.getInt(k) } } } } ?: emptyList(),
                j.optJSONArray("issues")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                if (j.isNull("outDebugUri")) null else j.optString("outDebugUri")
            )
        }
    }
}

/** Một lượt dịch tự động; lưu trong bộ nhớ riêng của ứng dụng sau MỖI trang để tiếp tục được khi mở lại. */
data class AutoRun(
    val id: String,
    val model: String,
    val pages: List<AutoPage>,
    val reportUri: String? = null,
    val stoppedForLimit: Boolean = false
) {
    val done get() = pages.count { it.status == PageStatus.DONE }
    val failed get() = pages.count { it.status == PageStatus.FAILED }
    val pending get() = pages.count { it.status == PageStatus.PENDING }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("model", model)
        .put("pages", JSONArray().apply { pages.forEach { put(it.toJson()) } })
        .put("reportUri", reportUri).put("stoppedForLimit", stoppedForLimit)

    companion object {
        fun fromJson(j: JSONObject): AutoRun {
            val a = j.getJSONArray("pages")
            return AutoRun(
                j.getString("id"), j.getString("model"), (0 until a.length()).map { AutoPage.fromJson(a.getJSONObject(it)) },
                if (j.isNull("reportUri")) null else j.optString("reportUri"), j.optBoolean("stoppedForLimit")
            )
        }
    }
}

/** Lưu/đọc tiến độ và JSON của ChatGPT trong bộ nhớ riêng: filesDir/auto_runs/<lượt>/. */
class AutoStore(private val ctx: Context) {
    private val root = File(ctx.filesDir, "auto_runs").apply { mkdirs() }

    private fun dir(id: String) = File(root, id).apply { mkdirs() }

    fun save(run: AutoRun) {
        val f = File(dir(run.id), "manifest.json")
        val tmp = File(dir(run.id), "manifest.tmp")
        tmp.writeText(run.toJson().toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        File(root, "latest.txt").writeText(run.id)
    }

    fun latest(): AutoRun? = runCatching {
        val id = File(root, "latest.txt").readText().trim()
        AutoRun.fromJson(JSONObject(File(dir(id), "manifest.json").readText()))
    }.getOrNull()

    fun saveJson(runId: String, index: Int, text: String) = File(dir(runId), "page_$index.json").writeText(text)

    fun loadJson(runId: String, index: Int): String? = File(dir(runId), "page_$index.json").takeIf { it.exists() }?.readText()
}

/**
 * Tệp kết quả công khai: Android 10+: Download/MangaTranslateVN/auto/<lượt>/ (MediaStore, không cần quyền);
 * Android 8–9: Android/data/vn.mangatranslate.app/files/auto/<lượt>/.
 */
class AutoOutput(private val ctx: Context, runId: String) {
    val relative = "${Environment.DIRECTORY_DOWNLOADS}/MangaTranslateVN/auto/$runId"
    private val legacyDir = File(ctx.getExternalFilesDir(null), "auto/$runId")
    val location: String = if (Build.VERSION.SDK_INT >= 29) "Download/MangaTranslateVN/auto/$runId" else legacyDir.absolutePath

    /** Ghi tệp; nếu [replace] là tệp cũ của chính ứng dụng thì xóa trước (không tạo bản trùng "(1)"). Trả về uri/đường dẫn. */
    fun write(name: String, bytes: ByteArray, mime: String, sub: String? = null, replace: String? = null): String {
        replace?.let { delete(it) }
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, if (sub == null) relative else "$relative/$sub")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Không tạo được tệp $name trong Download")
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            uri.toString()
        } else {
            val d = (if (sub == null) legacyDir else File(legacyDir, sub)).apply { mkdirs() }
            File(d, name).apply { writeBytes(bytes) }.let { Uri.fromFile(it).toString() }
        }
    }

    fun delete(uriOrPath: String) {
        runCatching {
            val u = Uri.parse(uriOrPath)
            if (u.scheme == "file") File(u.path!!).delete() else ctx.contentResolver.delete(u, null, null)
        }
    }

    /**
     * Kiểm tra ảnh ĐÃ LƯU THẬT: mở lại tệp, đọc kích thước và giải mã thử một bản nhỏ.
     * @return mô tả kết quả, hoặc ném lỗi nếu tệp không đọc được / sai kích thước.
     */
    fun verifyImage(uriOrPath: String, expectW: Int, expectH: Int): String {
        val u = Uri.parse(uriOrPath)
        val size = ctx.contentResolver.openInputStream(u)?.use { s -> var n = 0L; val buf = ByteArray(65536); while (true) { val r = s.read(buf); if (r < 0) break; n += r }; n }
            ?: throw IllegalStateException("Không mở lại được ảnh đã lưu")
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth != expectW || o.outHeight != expectH) throw IllegalStateException("Ảnh đã lưu sai kích thước: ${o.outWidth}×${o.outHeight}, cần ${expectW}×$expectH")
        val small = ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 }) }
            ?: throw IllegalStateException("Ảnh đã lưu không giải mã được")
        small.recycle()
        return "${o.outWidth}×${o.outHeight}, ${size / 1024} KB, mở lại được"
    }
}

/** Chuẩn bị ảnh gửi ChatGPT: cạnh dài ≤ 2048 px, JPEG — ảnh gốc không bị đổi. */
object AiImage {
    const val MAX_SIDE = 2048

    fun encode(ctx: Context, uri: Uri): String {
        val cr = ctx.contentResolver
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
        val w = opts.outWidth; val h = opts.outHeight
        if (w <= 0 || h <= 0) throw IllegalStateException("Không đọc được ảnh.")
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IllegalStateException("Không giải mã được ảnh.")
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        if (bmp !== decoded) bmp.recycle()
        decoded.recycle()
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bos.toByteArray())
    }
}
