package vn.mangatranslate.app.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vn.mangatranslate.app.core.chatgpt.AiImage
import vn.mangatranslate.app.core.chatgpt.AiPageRenderer
import vn.mangatranslate.app.core.chatgpt.AutoOutput
import vn.mangatranslate.app.core.chatgpt.AutoPage
import vn.mangatranslate.app.core.chatgpt.AutoRun
import vn.mangatranslate.app.core.chatgpt.AutoStore
import vn.mangatranslate.app.core.chatgpt.ChatGptAuth
import vn.mangatranslate.app.core.chatgpt.ChatGptClient
import vn.mangatranslate.app.core.chatgpt.MangaJson
import vn.mangatranslate.app.core.chatgpt.OAuthUtil
import vn.mangatranslate.app.core.chatgpt.OAuthWaitService
import vn.mangatranslate.app.core.chatgpt.PageStatus
import vn.mangatranslate.app.core.chatgpt.TokenStore
import vn.mangatranslate.app.core.image.ImageLoader
import vn.mangatranslate.app.core.util.NaturalOrderComparator
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AutoState(
    val email: String? = null,
    val planOk: Boolean = false,
    val models: List<ChatGptClient.Model> = emptyList(),
    val model: String? = null,
    val run: AutoRun? = null,
    val running: Boolean = false,
    val pauseRequested: Boolean = false,
    val current: String? = null,
    val limitReached: Boolean = false,
    val log: List<String> = emptyList(),
    val location: String? = null,
    val previewName: String? = null,
    val preview: Bitmap? = null
)

/**
 * Dịch tự động: chọn ảnh → ChatGPT đọc & dịch → JSON → chèn tiếng Việt → lưu ảnh → mở lại ảnh để kiểm tra.
 * Dùng lại nguyên các thành phần đã chạy tốt: ChatGptAuth/ChatGptClient (hạn mức gói ChatGPT), AiPageRenderer.
 * Tiến độ lưu sau MỖI trang (bộ nhớ riêng) → tạm dừng/tiếp tục, mở lại ứng dụng vẫn tiếp tục được.
 */
class AutoTranslateViewModel(app: Application) : AndroidViewModel(app) {
    private val tokens = TokenStore(app)
    private val auth = ChatGptAuth(tokens)
    private val client = ChatGptClient()
    private val store = AutoStore(app)
    private val prefs = app.getSharedPreferences("auto_translate", 0)
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _state = MutableStateFlow(AutoState())
    val state: StateFlow<AutoState> = _state

    init {
        refreshAccount()
        store.latest()?.let { r ->
            _state.update { it.copy(run = r, model = r.model, location = AutoOutput(app, r.id).location, limitReached = r.stoppedForLimit) }
        }
        if (_state.value.model == null) _state.update { it.copy(model = prefs.getString("model", null)) }
    }

    private fun log(s: String) {
        android.util.Log.i("MTV-Auto", s)
        _state.update { it.copy(log = (it.log + "${time.format(Date())}  $s").takeLast(300)) }
    }

    fun refreshAccount() {
        val a = tokens.load()
        _state.update { it.copy(email = a?.email?.ifEmpty { "(đã đăng nhập)" }, planOk = OAuthUtil.hasPlanScope(a?.scopes)) }
    }

    fun loadModels() {
        if (_state.value.running) return
        viewModelScope.launch {
            try {
                val acc = withContext(Dispatchers.IO) { auth.validAccessToken(::log) }
                if (!OAuthUtil.hasPlanScope(acc.scopes)) return@launch log("Tài khoản không có quyền chatgpt.tokens.use.direct.")
                val res = withContext(Dispatchers.IO) { client.listModels(acc.accessToken) }
                val listed = res.models.filter { it.visibility == "list" }.ifEmpty { res.models }
                val keep = _state.value.model?.takeIf { m -> listed.any { it.slug == m } } ?: listed.firstOrNull()?.slug
                _state.update { it.copy(models = listed, model = keep) }
                log("Có ${listed.size} mô hình; đang chọn: $keep")
            } catch (e: Exception) {
                log("Không lấy được mô hình: ${e.javaClass.name}: ${e.message}")
            }
        }
    }

    fun selectModel(slug: String) {
        prefs.edit().putString("model", slug).apply()
        _state.update { it.copy(model = slug) }
    }

    /** Ảnh chọn qua OPEN_DOCUMENT: giữ quyền đọc lâu dài để tiếp tục sau khi mở lại ứng dụng. */
    fun pickPages(items: List<Pair<Uri, String>>) {
        if (_state.value.running) return
        val app = getApplication<Application>()
        val sorted = items.sortedWith { a, b -> NaturalOrderComparator.compare(a.second, b.second) }
        if (sorted.size > MAX_PAGES) log("Bản thử nghiệm: chỉ lấy $MAX_PAGES trang đầu (đã chọn ${sorted.size}).")
        val chosen = sorted.take(MAX_PAGES)
        for ((u, _) in chosen) runCatching {
            app.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val model = _state.value.model ?: "?"
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val run = AutoRun(id, model, chosen.mapIndexed { i, (u, n) -> AutoPage(i + 1, u.toString(), n) })
        store.save(run)
        _state.update { it.copy(run = run, location = AutoOutput(app, id).location, limitReached = false, preview = null, previewName = null) }
        log("Lượt mới $id: ${run.pages.size} trang (${run.pages.joinToString { it.name }})")
    }

    fun start() = launchLoop("Bắt đầu / tiếp tục") { it }

    /** Đưa các trang lỗi về hàng chờ. Trang đã có JSON chỉ render lại (không gọi ChatGPT). */
    fun retryFailed() = launchLoop("Dịch lại trang lỗi") { r ->
        r.copy(pages = r.pages.map { if (it.status == PageStatus.FAILED) it.copy(status = PageStatus.PENDING, error = null) else it })
    }

    /** Render lại MỌI trang đã có JSON từ ChatGPT — không gửi lại ảnh, không tốn hạn mức. */
    fun rerenderFromJson() = launchLoop("Render lại từ JSON đã lưu") { r ->
        r.copy(pages = r.pages.map { if (it.jsonCached) it.copy(status = PageStatus.PENDING, error = null) else it })
    }

    fun pause() {
        if (_state.value.running) {
            _state.update { it.copy(pauseRequested = true) }
            log("Sẽ tạm dừng sau khi xong trang đang xử lý (không bỏ dở yêu cầu đã gửi).")
        }
    }

    fun clearLimit() {
        _state.value.run?.let { r -> val nr = r.copy(stoppedForLimit = false); store.save(nr); _state.update { it.copy(run = nr) } }
        _state.update { it.copy(limitReached = false) }
    }

    private fun launchLoop(label: String, prepare: (AutoRun) -> AutoRun) {
        val s = _state.value
        if (s.running) return
        val run0 = s.run ?: return log("Chưa chọn trang.")
        val model = s.model ?: return log("Chưa chọn mô hình — bấm \"Lấy danh sách mô hình\".")
        val prepared = prepare(run0).copy(model = model, stoppedForLimit = false)
        store.save(prepared)
        _state.update { it.copy(run = prepared, running = true, pauseRequested = false, limitReached = false) }
        log("$label: ${prepared.pending} trang chờ xử lý, mô hình $model")
        viewModelScope.launch {
            val app = getApplication<Application>()
            val fgs = OAuthWaitService.start(app, "Đang dịch truyện bằng ChatGPT", "Giữ kết nối khi tắt màn hình hoặc chuyển ứng dụng")
            if (fgs != null) log("Không bật được dịch vụ tiền cảnh: $fgs")
            try {
                loop(model)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Dừng do lỗi: ${e.javaClass.name}: ${e.message}")
            } finally {
                OAuthWaitService.stop(app)
                _state.update { it.copy(running = false, pauseRequested = false, current = null) }
                _state.value.run?.let { r -> log("Kết thúc lượt chạy: xong ${r.done}, lỗi ${r.failed}, chưa xử lý ${r.pending}.") }
            }
        }
    }

    private suspend fun loop(model: String) {
        val app = getApplication<Application>()
        while (true) {
            if (_state.value.pauseRequested) { log("Đã tạm dừng. Bấm \"Tiếp tục\" để chạy tiếp."); return }
            val run = _state.value.run ?: return
            val page = run.pages.firstOrNull { it.status == PageStatus.PENDING } ?: return
            val pos = run.pages.indexOf(page) + 1
            _state.update { it.copy(current = "Trang $pos/${run.pages.size}: ${page.name}") }
            val out = AutoOutput(app, run.id)
            val result = processPage(run, page, model, out)
            if (result == null) {
                // Chạm giới hạn gói: trang này vẫn "chưa xử lý"; dừng gửi yêu cầu mới.
                val nr = run.copy(stoppedForLimit = true)
                store.save(nr)
                writeReport(nr, out)
                _state.update { it.copy(run = nr, limitReached = true) }
                log("Chạm giới hạn sử dụng của gói ChatGPT. Đã lưu tiến độ; máy chủ không cho biết khi nào hạn mức khôi phục.")
                return
            }
            val nr = run.copy(pages = run.pages.map { if (it.index == result.index) result else it })
            store.save(nr)
            val withReport = writeReport(nr, out)
            _state.update { it.copy(run = withReport) }
        }
    }

    /** @return trang đã cập nhật (DONE/FAILED), hoặc null nếu chạm giới hạn gói (giữ PENDING). */
    private suspend fun processPage(run: AutoRun, page: AutoPage, model: String, out: AutoOutput): AutoPage? {
        val app = getApplication<Application>()
        val uri = Uri.parse(page.uri)
        val t0 = System.currentTimeMillis()
        var p = page
        // 1) JSON: dùng bản đã lưu nếu có (không tốn hạn mức), nếu không thì gửi ảnh cho ChatGPT.
        var json = if (page.jsonCached) store.loadJson(run.id, page.index) else null
        if (json != null) {
            log("${page.name}: dùng JSON đã lưu — không gọi ChatGPT.")
        } else {
            val img = try {
                withContext(Dispatchers.Default) { AiImage.encode(app, uri) }
            } catch (e: Exception) {
                return fail(p, "Không đọc được ảnh gốc: ${e.javaClass.name}: ${e.message}", t0)
            }
            var retries = 0
            val waits = listOf(5_000L, 15_000L)
            val reply: ChatGptClient.Reply
            while (true) {
                val acc = try {
                    withContext(Dispatchers.IO) { auth.validAccessToken(::log) }
                } catch (e: Exception) {
                    return fail(p, "Không có token hợp lệ (cần đăng nhập lại): ${e.message}", t0)
                }
                if (!OAuthUtil.hasPlanScope(acc.scopes)) return fail(p, "Thiếu quyền chatgpt.tokens.use.direct", t0)
                try {
                    reply = withContext(Dispatchers.IO) {
                        client.respond(acc.accessToken, model, ChatGptClient.MANGA_INSTRUCTIONS,
                            "Đọc và dịch trang truyện này. Chỉ trả về JSON theo mẫu.", img)
                    }
                    break
                } catch (e: java.io.IOException) {
                    if (retries >= waits.size) return fail(p.copy(retries = retries), "Lỗi mạng sau ${retries + 1} lần thử: ${e.javaClass.name}: ${e.message}", t0)
                    log("${page.name}: ${e.javaClass.name}: ${e.message} — thử lại sau ${waits[retries] / 1000} s (lần ${retries + 2}/3)")
                    delay(waits[retries])
                    retries++
                }
            }
            p = p.copy(retries = retries)
            if (reply.limitReached) return null
            val usage = reply.usage?.let { runCatching { JSONObject(it).optLong("total_tokens") }.getOrNull() }
            p = p.copy(tokens = usage)
            if (!reply.ok) return fail(p, "ChatGPT không hoàn tất (${reply.status}) ${reply.errorCode ?: ""} ${reply.errorMessage?.take(200) ?: ""}".trim(), t0)
            val parsed = MangaJson.parse(reply.text)
            if (parsed.regions.isEmpty()) return fail(p, "JSON không hợp lệ: ${parsed.problems.joinToString().take(200)}", t0)
            json = reply.text
            store.saveJson(run.id, page.index, json)
            p = p.copy(jsonCached = true)
            val base = page.name.substringBeforeLast('.')
            runCatching { out.write("$base.json", json.toByteArray(Charsets.UTF_8), "application/json", sub = "json", replace = page.outJsonUri) }
                .onSuccess { p = p.copy(outJsonUri = it) }
                .onFailure { log("${page.name}: không lưu được JSON công khai: ${it.message}") }
        }

        // 2) Render trên bản sao ảnh gốc, lưu PNG, mở lại để kiểm tra.
        val parsed = MangaJson.parse(json)
        if (parsed.regions.isEmpty()) return fail(p, "JSON đã lưu không có vùng nào", t0)
        return try {
            val loaded = ImageLoader(app).loadFull(uri)
            val result = withContext(Dispatchers.Default) { AiPageRenderer().render(loaded.bitmap, parsed.regions) }
            loaded.bitmap.recycle()
            val w = result.bitmap.width
            val h = result.bitmap.height
            val png = withContext(Dispatchers.Default) {
                ByteArrayOutputStream().also { result.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            }
            result.bitmap.recycle()
            val base = page.name.substringBeforeLast('.')
            val saved = withContext(Dispatchers.IO) { out.write("${base}_vi.png", png, "image/png", replace = p.outImageUri) }
            val verified = withContext(Dispatchers.IO) { out.verifyImage(saved, w, h) }
            val reasons = parseReasons(result.log)
            val secs = (System.currentTimeMillis() - t0) / 1000.0
            log("${page.name}: XONG — ${parsed.regions.size} vùng AI, render ${result.drawn}, còn chữ Nhật ${result.japaneseLeft}, " +
                "cố ý giữ ${result.keptOnPurpose}; ảnh đã lưu: $verified")
            if (result.changedOutside > 0) log("${page.name}: CẢNH BÁO ${result.changedOutside} điểm ảnh đổi ngoài vùng cho phép")
            p.copy(
                status = PageStatus.DONE, error = null, aiRegions = parsed.regions.size, rendered = result.drawn,
                japaneseLeft = result.japaneseLeft, keptOnPurpose = result.keptOnPurpose, reasons = reasons,
                seconds = secs, verified = verified, outImageUri = saved
            )
        } catch (e: OutOfMemoryError) {
            fail(p, "Hết bộ nhớ khi render (ảnh quá lớn)", t0)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(p, "Lỗi render/lưu ảnh: ${e.javaClass.name}: ${e.message}", t0)
        }
    }

    private fun fail(p: AutoPage, why: String, t0: Long): AutoPage {
        log("${p.name}: LỖI — $why")
        return p.copy(status = PageStatus.FAILED, error = why, seconds = (System.currentTimeMillis() - t0) / 1000.0)
    }

    /** Đọc phần "THỐNG KÊ" của nhật ký render thành bảng nguyên nhân bỏ qua. */
    private fun parseReasons(renderLog: String): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        for (line in renderLog.lineSequence().takeWhile { it.isNotBlank() }) {
            val t = line.trim()
            if (!t.startsWith("- ")) continue
            val k = t.removePrefix("- ").substringBeforeLast(":").trim()
            val v = t.substringAfterLast(":").trim().substringBefore(" ").toIntOrNull() ?: continue
            m[k] = v
        }
        return m
    }

    private fun writeReport(run: AutoRun, out: AutoOutput): AutoRun {
        val sb = StringBuilder()
        sb.append("Manga Translator VN — dịch tự động bằng ChatGPT\nLượt: ${run.id}  Mô hình: ${run.model}\n")
        sb.append("Xong ${run.done} · Lỗi ${run.failed} · Chưa xử lý ${run.pending} / ${run.pages.size} trang\n")
        if (run.stoppedForLimit) sb.append("ĐANG DỪNG do chạm giới hạn sử dụng của gói ChatGPT (tiến độ đã lưu).\n")
        val tk = run.pages.mapNotNull { it.tokens }.sum()
        sb.append("Token máy chủ báo: $tk (chỉ tham khảo)\n")
        for (p in run.pages) {
            sb.append("\n${p.index}. ${p.name} — ${p.status.label}")
            if (p.status == PageStatus.DONE) {
                sb.append("\n   Vùng AI dịch: ${p.aiRegions} · đã render: ${p.rendered} · còn chữ Nhật: ${p.japaneseLeft} · cố ý giữ nguyên (sfx/AI chưa chắc): ${p.keptOnPurpose}")
                p.reasons.forEach { (k, v) -> sb.append("\n   - $k: $v") }
                sb.append("\n   Ảnh đã lưu: ${p.verified}")
            }
            p.error?.let { sb.append("\n   Lỗi: $it") }
            if (p.retries > 0) sb.append("\n   Thử lại mạng: ${p.retries} lần")
            p.tokens?.let { sb.append("\n   Token: $it") }
            if (p.seconds > 0) sb.append("\n   Thời gian: ${"%.1f".format(p.seconds)} s")
        }
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        return try {
            val uri = out.write("bao_cao.txt", bytes, "text/plain", replace = run.reportUri)
            run.copy(reportUri = uri).also { store.save(it) }
        } catch (e: Exception) {
            log("Không lưu được báo cáo: ${e.message}")
            run
        }
    }

    /** Xem ảnh đã lưu (đọc lại từ tệp, bản thu nhỏ). */
    fun showPreview(page: AutoPage) {
        val u = page.outImageUri ?: return
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val cr = getApplication<Application>().contentResolver
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    cr.openInputStream(Uri.parse(u))?.use { BitmapFactory.decodeStream(it, null, o) }
                    var sample = 1
                    while (maxOf(o.outWidth, o.outHeight) / sample > 1600) sample *= 2
                    cr.openInputStream(Uri.parse(u))?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                }.getOrNull()
            }
            if (bmp == null) log("Không mở được ảnh đã lưu của ${page.name}")
            _state.update { it.copy(preview = bmp, previewName = if (bmp != null) page.name else null) }
        }
    }

    fun closePreview() = _state.update { it.copy(preview = null, previewName = null) }

    /** Intent mở thư mục kết quả (Android 10+); null nếu không có ứng dụng quản lý tệp phù hợp. */
    fun folderIntent(): Intent? {
        val r = _state.value.run ?: return null
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        val docId = "primary:Download/MangaTranslateVN/auto/${r.id}"
        val uri = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private companion object {
        /** Giai đoạn thử nghiệm: tối đa 10 trang/lượt (chưa chạy cả chương). */
        const val MAX_PAGES = 10
    }
}
