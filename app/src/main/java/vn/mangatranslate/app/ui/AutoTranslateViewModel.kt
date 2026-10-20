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
        // SAF has a device-dependent limit on persisted URI grants. Before a new
        // run replaces the previous one, release stale image grants so choosing
        // 100 pages does not silently exhaust that limit on the user's phone.
        val selectedUris = chosen.map { it.first }.toSet()
        for (old in app.contentResolver.persistedUriPermissions) {
            if (old.isReadPermission && old.uri !in selectedUris) runCatching {
                app.contentResolver.releasePersistableUriPermission(old.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        for ((u, name) in chosen) runCatching {
            app.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure {
            log("Không giữ được quyền đọc lâu dài cho $name: ${it.message}. Khi mở lại ứng dụng có thể phải chọn lại ảnh.")
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
            var replyOrNull: ChatGptClient.Reply? = null
            while (replyOrNull == null) {
                val acc = try {
                    withContext(Dispatchers.IO) { auth.validAccessToken(::log) }
                } catch (e: Exception) {
                    return fail(p, "Không có token hợp lệ (cần đăng nhập lại): ${e.message}", t0)
                }
                if (!OAuthUtil.hasPlanScope(acc.scopes)) return fail(p, "Thiếu quyền chatgpt.tokens.use.direct", t0)
                try {
                    replyOrNull = withContext(Dispatchers.IO) {
                        client.respond(acc.accessToken, model, ChatGptClient.MANGA_INSTRUCTIONS,
                            "Đọc và dịch trang truyện này. Chỉ trả về JSON theo mẫu.", img)
                    }
                } catch (e: java.io.IOException) {
                    if (retries >= waits.size) return fail(p.copy(retries = retries), "Lỗi mạng sau ${retries + 1} lần thử: ${e.javaClass.name}: ${e.message}", t0)
                    log("${page.name}: ${e.javaClass.name}: ${e.message} — thử lại sau ${waits[retries] / 1000} s (lần ${retries + 2}/3)")
                    delay(waits[retries])
                    retries++
                }
            }
            val reply = replyOrNull
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
            // Ảnh DEBUG đánh số vùng (xanh lá đã chèn, đỏ B, cam C, tím A, xanh dương D) + ảnh cắt vùng nghi thiếu bản dịch.
            var debugUri: String? = p.outDebugUri
            result.debug?.let { dbg ->
                val bytes = withContext(Dispatchers.Default) { ByteArrayOutputStream().also { dbg.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
                dbg.recycle()
                debugUri = runCatching { withContext(Dispatchers.IO) { out.write("${base}_debug.png", bytes, "image/png", sub = "debug", replace = p.outDebugUri) } }
                    .onFailure { log("${page.name}: không lưu được ảnh DEBUG: ${it.message}") }.getOrNull() ?: p.outDebugUri
            }
            val missingItems = result.checks.filter { it.cat == vn.mangatranslate.app.core.chatgpt.PostRenderCheck.Cat.A }
            val missingBoxes = missingItems.map { listOf(it.box.left.toInt(), it.box.top.toInt(), it.box.right.toInt(), it.box.bottom.toInt()) }
            if (missingItems.isNotEmpty()) {
                val src = ImageLoader(app).loadFull(uri).bitmap
                for (it in missingItems) runCatching {
                    val l = it.box.left.toInt().coerceIn(0, src.width - 1); val t = it.box.top.toInt().coerceIn(0, src.height - 1)
                    val cw = (it.box.width.toInt()).coerceIn(1, src.width - l); val ch = (it.box.height.toInt()).coerceIn(1, src.height - t)
                    val crop = Bitmap.createBitmap(src, l, t, cw, ch)
                    val b = ByteArrayOutputStream().also { o -> crop.compress(Bitmap.CompressFormat.PNG, 100, o) }.toByteArray()
                    crop.recycle()
                    withContext(Dispatchers.IO) { out.write("${base}_thieu_${it.num}.png", b, "image/png", sub = "debug") }
                }
                src.recycle()
            }
            val issues = result.checks.filter { it.cat != vn.mangatranslate.app.core.chatgpt.PostRenderCheck.Cat.OK }.map {
                "[${it.num}] ${it.cat.code} ${it.cat.label}${if (it.ids.isNotEmpty()) " (vùng AI #${it.ids.joinToString("+")})" else ""}: ${it.reason} " +
                    "@(${it.box.left.toInt()},${it.box.top.toInt()} ${it.box.width.toInt()}×${it.box.height.toInt()})"
            }
            val reasons = parseReasons(result.log)
            val secs = (System.currentTimeMillis() - t0) / 1000.0
            log("${page.name}: XONG — ${result.summary}; ảnh đã lưu: $verified")
            if (result.changedOutside > 0) log("${page.name}: CẢNH BÁO ${result.changedOutside} điểm ảnh đổi ngoài vùng cho phép")
            p.copy(
                status = PageStatus.DONE, error = null, aiRegions = parsed.regions.size, rendered = result.drawn,
                japaneseLeft = result.japaneseLeft, keptOnPurpose = result.keptOnPurpose, reasons = reasons,
                seconds = secs, verified = verified, outImageUri = saved,
                okCount = result.okCount, notInserted = result.notInserted, suspectedJp = result.suspectedJapanese,
                missing = result.missingTranslation, missingBoxes = missingBoxes, issues = issues, outDebugUri = debugUri
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
        val clean = run.pages.count { it.status == PageStatus.DONE && it.needsReview == 0 }
        val review = run.pages.count { it.status == PageStatus.DONE && it.needsReview > 0 }
        sb.append("Hoàn chỉnh ${clean} · Xong nhưng còn vùng cần xử lý ${review} · Lỗi ${run.failed} · Chưa xử lý ${run.pending} / ${run.pages.size} trang\n")
        sb.append("Chú thích: B = có bản dịch nhưng chưa chèn (không tìm được bong bóng/không an toàn/không đủ chỗ) · " +
            "C = nghi còn chữ Nhật · A = nghi thiếu bản dịch (không có trong JSON, cần xác nhận) · D = cố ý giữ (sfx/AI chưa chắc)\n" +
            "Số thứ tự [n] khớp ảnh debug/<trang>_debug.png.\n")
        if (run.stoppedForLimit) sb.append("ĐANG DỪNG do chạm giới hạn sử dụng của gói ChatGPT (tiến độ đã lưu).\n")
        val tk = run.pages.mapNotNull { it.tokens }.sum()
        sb.append("Token máy chủ báo: $tk (chỉ tham khảo)\n")
        for (p in run.pages) {
            sb.append("\n${p.index}. ${p.name} — ${p.status.label}")
            if (p.status == PageStatus.DONE) {
                sb.append("\n   Vùng trong JSON: ${p.aiRegions} · đã chèn: ${p.okCount} · có bản dịch nhưng chưa chèn (B): ${p.notInserted} · " +
                    "nghi còn chữ Nhật (C): ${p.suspectedJp} · nghi thiếu bản dịch (A): ${p.missing} · cố ý giữ (D): ${p.keptOnPurpose} · " +
                    "CẦN KIỂM TRA THỦ CÔNG: ${p.needsReview}")
                if (p.needsReview == 0) sb.append("\n   → Trang hoàn chỉnh.") else sb.append("\n   → CHƯA hoàn chỉnh.")
                p.issues.forEach { sb.append("\n   $it") }
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

    /**
     * Dịch BỔ SUNG chỉ các vùng nghi thiếu bản dịch (A) của một trang — CHỈ khi người dùng bấm. Gửi ảnh cắt từng vùng,
     * không gửi lại cả trang. Kết quả được thêm vào JSON đã lưu (đánh dấu "supplement") rồi render lại trang từ JSON.
     */
    fun supplementMissing(page: AutoPage) {
        val s0 = _state.value
        if (s0.running || page.missingBoxes.isEmpty()) return
        if (s0.limitReached) return log("Đang chạm giới hạn sử dụng — không gửi yêu cầu mới.")
        val model = s0.model ?: return log("Chưa chọn mô hình.")
        val run0 = s0.run ?: return
        viewModelScope.launch {
            _state.update { it.copy(running = true, current = "Dịch bổ sung ${page.missingBoxes.size} vùng của ${page.name}") }
            val app = getApplication<Application>()
            try {
                val json = store.loadJson(run0.id, page.index) ?: return@launch log("${page.name}: chưa có JSON đã lưu.")
                val root = JSONObject(json)
                val arr = root.getJSONArray("regions")
                val src = ImageLoader(app).loadFull(Uri.parse(page.uri)).bitmap
                val W = src.width.toDouble(); val H = src.height.toDouble()
                var added = 0
                for ((k, bx) in page.missingBoxes.withIndex()) {
                    val mx = ((bx[2] - bx[0]) * 0.1).toInt(); val my = ((bx[3] - bx[1]) * 0.1).toInt()
                    val l = (bx[0] - mx).coerceIn(0, src.width - 1); val t = (bx[1] - my).coerceIn(0, src.height - 1)
                    val r = (bx[2] + mx).coerceIn(l + 1, src.width); val b = (bx[3] + my).coerceIn(t + 1, src.height)
                    val crop = Bitmap.createBitmap(src, l, t, r - l, b - t)
                    val bos = ByteArrayOutputStream(); crop.compress(Bitmap.CompressFormat.JPEG, 90, bos); crop.recycle()
                    val img = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(bos.toByteArray())
                    val acc = withContext(Dispatchers.IO) { auth.validAccessToken(::log) }
                    if (!OAuthUtil.hasPlanScope(acc.scopes)) { log("Thiếu quyền chatgpt.tokens.use.direct"); break }
                    var reply: ChatGptClient.Reply? = null
                    for (attempt in 0..2) {
                        try {
                            reply = withContext(Dispatchers.IO) {
                                client.respond(acc.accessToken, model, ChatGptClient.MANGA_INSTRUCTIONS,
                                    "Đây là MỘT VÙNG NHỎ cắt từ trang truyện. Chỉ đọc và dịch chữ trong ảnh này; nếu không có lời thoại thì trả về {\"regions\":[]}.", img)
                            }
                            break
                        } catch (e: java.io.IOException) {
                            if (attempt == 2) throw e
                            delay(if (attempt == 0) 5_000L else 15_000L)
                        }
                    }
                    val rep = reply ?: break
                    if (rep.limitReached) { _state.update { it.copy(limitReached = true) }; log("Chạm giới hạn sử dụng khi dịch bổ sung."); break }
                    if (!rep.ok) { log("${page.name}: vùng bổ sung ${k + 1} không hoàn tất (${rep.status})"); continue }
                    val parsed = MangaJson.parse(rep.text)
                    for (reg in parsed.regions) {
                        val bb = reg.bbox ?: continue
                        val o = JSONObject().put("id", "S${k + 1}_${reg.id}").put("type", reg.type).put("ja", reg.ja).put("vi", reg.vi)
                            .put("uncertain", reg.uncertain).put("supplement", true)
                            .put("bbox", org.json.JSONArray().put((l + bb[0] * (r - l)) / W).put((t + bb[1] * (b - t)) / H)
                                .put(bb[2] * (r - l) / W).put(bb[3] * (b - t) / H))
                        arr.put(o); added++
                    }
                    log("${page.name}: vùng bổ sung ${k + 1}: ${parsed.regions.size} vùng chữ")
                }
                src.recycle()
                if (added > 0) {
                    store.saveJson(run0.id, page.index, root.toString())
                    val nr = run0.copy(pages = run0.pages.map { if (it.index == page.index) it.copy(status = PageStatus.PENDING, error = null) else it })
                    store.save(nr)
                    _state.update { it.copy(run = nr) }
                    log("${page.name}: thêm $added vùng vào JSON (đã lưu cache). Bấm \"Tiếp tục\" để render lại trang — không tốn thêm hạn mức.")
                } else log("${page.name}: không có vùng chữ mới nào được thêm.")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("Dịch bổ sung lỗi: ${e.javaClass.name}: ${e.message}")
            } finally {
                _state.update { it.copy(running = false, current = null) }
            }
        }
    }

    /** Xem ảnh đã lưu (đọc lại từ tệp, bản thu nhỏ). [debug] = xem ảnh DEBUG đánh số vùng. */
    fun showPreview(page: AutoPage, debug: Boolean = false) {
        val u = (if (debug) page.outDebugUri else page.outImageUri) ?: return
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
        /** Tối đa 100 trang/lượt; có thể tiếp tục khi giới hạn ChatGPT được khôi phục. */
        const val MAX_PAGES = 100
    }
}
