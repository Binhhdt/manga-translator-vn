package vn.mangatranslate.app.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.mangatranslate.app.core.chatgpt.ChatGptAccount
import vn.mangatranslate.app.core.chatgpt.ChatGptAuth
import vn.mangatranslate.app.core.chatgpt.ChatGptClient
import vn.mangatranslate.app.core.chatgpt.MangaJson
import vn.mangatranslate.app.core.chatgpt.OAuthUtil
import vn.mangatranslate.app.core.chatgpt.OAuthWaitService
import vn.mangatranslate.app.core.chatgpt.TestRunWriter
import vn.mangatranslate.app.core.chatgpt.TokenStore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

data class ChatGptTestState(
    val account: ChatGptAccount? = null,
    val issuedClientId: String? = null,
    val busy: String? = null,
    val log: List<String> = emptyList(),
    val models: List<ChatGptClient.Model> = emptyList(),
    val modelsRaw: String? = null,
    val selectedModel: String? = null,
    val imageName: String? = null,
    val imageInfo: String? = null,
    val output: String = "",
    val reply: ChatGptClient.Reply? = null,
    val parsed: MangaJson.Parsed? = null,
    val limitReached: Boolean = false,
    /** Giai đoạn D: nhiều trang. */
    val batchPages: List<String> = emptyList(),
    val batchRows: List<BatchRow> = emptyList(),
    val batchLocation: String? = null,
    val batchFailed: List<String> = emptyList(),
    /** Giai đoạn E: render 1 trang. */
    val renderJsonName: String? = null,
    val renderPreview: android.graphics.Bitmap? = null,
    val renderLog: String? = null,
    val renderLocation: String? = null
)

/** Kết quả một trang trong lượt thử nhiều trang. */
data class BatchRow(
    val name: String,
    val status: String,
    val seconds: Double = 0.0,
    val regions: Int = 0,
    val uncertain: Int = 0,
    val problems: Int = 0,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val error: String? = null
)

/**
 * Màn hình thử giai đoạn A–C: đăng nhập ChatGPT, liệt kê mô hình, gửi câu chữ, gửi một ảnh manga.
 * Tách riêng khỏi MainViewModel để không chạm vào luồng dịch đang hoạt động.
 */
class ChatGptTestViewModel(app: Application) : AndroidViewModel(app) {
    private val store = TokenStore(app)
    private val auth = ChatGptAuth(store)
    private val client = ChatGptClient()
    private val _state = MutableStateFlow(ChatGptTestState(account = store.load(), issuedClientId = store.issuedClientId()))
    val state: StateFlow<ChatGptTestState> = _state
    private var imageDataUrl: String? = null
    private var job: Job? = null
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile private var server: vn.mangatranslate.app.core.chatgpt.LoopbackServer? = null

    /** Nhật ký trên màn hình + logcat (thẻ MTV-OAuth). Không bao giờ chứa code, state hay token. */
    private fun log(s: String) {
        android.util.Log.i(OAuthWaitService.TAG, s)
        _state.update { it.copy(log = (it.log + "${time.format(Date())}  $s").takeLast(200)) }
    }

    private fun run(label: String, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        job = viewModelScope.launch {
            _state.update { it.copy(busy = label) }
            try {
                block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log("LỖI: ${e.javaClass.name}: ${e.message}")
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun cancel() {
        server?.close()
        job?.cancel()
        _state.update { it.copy(busy = null) }
        log("Đã hủy thao tác.")
    }

    fun signIn() = run("Đang chờ đăng nhập trong trình duyệt…") {
        val app = getApplication<Application>()
        val acc = try {
            withContext(Dispatchers.IO) {
                auth.signIn(
                    openBrowser = { url ->
                        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        app.startActivity(i)
                    },
                    log = ::log,
                    keepAlive = {
                        // Giữ tiến trình ở tiền cảnh khi Chrome che ứng dụng (tránh bị đóng băng/chặn mạng nền).
                        val err = OAuthWaitService.start(app)
                        log(if (err == null) "FOREGROUND_SERVICE_REQUESTED" else "FOREGROUND_SERVICE_FAILED $err")
                    },
                    onServer = { server = it }
                )
            }
        } finally {
            server?.close()
            server = null
            OAuthWaitService.stop(app)
        }
        _state.update { it.copy(account = acc, issuedClientId = store.issuedClientId()) }
        log("Đăng nhập xong: ${acc.email.ifEmpty { "(không có email)" }}")
        log("Quyền được cấp: ${acc.scopes.ifEmpty { "(máy chủ không trả scope)" }}")
        log(
            if (OAuthUtil.hasPlanScope(acc.scopes)) "CÓ quyền chatgpt.tokens.use.direct — được dùng hạn mức gói ChatGPT."
            else "KHÔNG có quyền chatgpt.tokens.use.direct — ứng dụng sẽ KHÔNG gọi AI."
        )
    }

    fun refresh() = run("Đang làm mới token…") {
        val a = store.load() ?: return@run log("Chưa đăng nhập.")
        val acc = withContext(Dispatchers.IO) { auth.refresh(a, ::log) }
        _state.update { it.copy(account = acc) }
    }

    fun signOut() = run("Đang đăng xuất…") {
        withContext(Dispatchers.IO) { auth.signOut(::log) }
        _state.update { it.copy(account = null, models = emptyList(), modelsRaw = null, selectedModel = null) }
    }

    /** Kiểm tra bắt buộc trước MỌI lần gọi AI. */
    private suspend fun tokenForInference(): String? {
        val acc = withContext(Dispatchers.IO) { auth.validAccessToken(::log) }
        _state.update { it.copy(account = acc) }
        if (!OAuthUtil.hasPlanScope(acc.scopes)) {
            log("Dừng: tài khoản không có quyền chatgpt.tokens.use.direct.")
            return null
        }
        return acc.accessToken
    }

    fun listModels() = run("Đang lấy danh sách mô hình…") {
        val token = tokenForInference() ?: return@run
        val res = withContext(Dispatchers.IO) { client.listModels(token) }
        val listed = res.models.filter { it.visibility == "list" }.ifEmpty { res.models }
        _state.update { s -> s.copy(models = listed, modelsRaw = res.raw.take(4000), selectedModel = s.selectedModel ?: listed.firstOrNull()?.slug) }
        log("Nhận ${res.models.size} mô hình (${listed.size} hiển thị).")
    }

    fun selectModel(slug: String) = _state.update { it.copy(selectedModel = slug) }

    fun sendHello() = send(null, "Xin chào. Hãy trả lời đúng một câu tiếng Việt ngắn để xác nhận bạn nhận được tin nhắn.", null)

    fun sendImage() {
        val img = imageDataUrl ?: return log("Chưa chọn ảnh.")
        send(ChatGptClient.MANGA_INSTRUCTIONS, "Đọc và dịch trang truyện này. Chỉ trả về JSON theo mẫu.", img)
    }

    private fun send(instructions: String?, text: String, image: String?) {
        if (_state.value.limitReached) return log("Đã chạm giới hạn sử dụng — không gửi yêu cầu mới. Kiểm tra trang Usage rồi bấm \"Thử lại sau giới hạn\".")
        val model = _state.value.selectedModel ?: return log("Chưa chọn mô hình — bấm \"Lấy danh sách mô hình\" trước.")
        run(if (image != null) "Đang gửi ảnh cho $model…" else "Đang gửi câu chữ cho $model…") {
            val token = tokenForInference() ?: return@run
            _state.update { it.copy(output = "", reply = null, parsed = null) }
            log("POST /v1/responses model=$model store=false stream=true${if (image != null) " + ảnh" else ""}")
            val started = System.currentTimeMillis()
            val reply = withContext(Dispatchers.IO) {
                client.respond(token, model, instructions, text, image) { d -> _state.update { it.copy(output = it.output + d) } }
            }
            val secs = (System.currentTimeMillis() - started) / 1000.0
            log("Kết thúc: ${reply.status} sau ${"%.1f".format(secs)} s; sự kiện: ${reply.events}")
            reply.errorCode?.let { log("Mã lỗi: $it") }
            reply.errorMessage?.let { log("Chi tiết: ${it.take(500)}") }
            reply.usage?.let { log("Sử dụng (máy chủ trả về): $it") }
            if (reply.limitReached) log("Đã chạm giới hạn sử dụng của gói. Ứng dụng dừng gửi yêu cầu; máy chủ không cho biết thời điểm khôi phục.")
            val parsed = if (image != null && reply.ok) MangaJson.parse(reply.text) else null
            parsed?.let { p -> log("JSON: ${p.regions.size} vùng, ${p.problems.size} vấn đề.") }
            _state.update { it.copy(reply = reply, parsed = parsed, limitReached = reply.limitReached) }
        }
    }

    fun clearLimit() {
        _state.update { it.copy(limitReached = false) }
        log("Cho phép gửi lại. Nếu giới hạn vẫn còn, máy chủ sẽ trả lỗi tương tự.")
    }

    /** Đọc ảnh, thu nhỏ cạnh dài về tối đa 2048 px (đủ đọc chữ, giảm dung lượng gửi), mã hóa JPEG. Ảnh gốc không bị đổi. */
    private fun encodeImage(uri: Uri): Pair<String, String> {
        val cr = getApplication<Application>().contentResolver
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
        val bytes = bos.toByteArray()
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes) to
            "Gốc ${w}×${h} → gửi ${(w * minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))).toInt()}×${(h * minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))).toInt()} JPEG ${bytes.size / 1024} KB"
    }

    private var imageUri: Uri? = null

    fun pickImage(uri: Uri, name: String?) = run("Đang chuẩn bị ảnh…") {
        val (url, info) = withContext(Dispatchers.Default) { encodeImage(uri) }
        imageDataUrl = url
        imageUri = uri
        renderJsonText = null
        _state.update { it.copy(renderJsonName = null, renderPreview = null, renderLog = null, renderLocation = null) }
        _state.update { it.copy(imageName = name ?: uri.lastPathSegment, imageInfo = info) }
        log("Ảnh: ${name ?: uri.lastPathSegment} — $info")
    }

    // ---------------------------------------------------------------- giai đoạn D: 5–10 trang

    private var batchUris: List<Pair<Uri, String>> = emptyList()

    /** Nhận các ảnh đã chọn, sắp theo thứ tự trang (tên tệp, số tự nhiên: 2 < 10). Tối đa [MAX_BATCH] trang. */
    fun pickBatch(items: List<Pair<Uri, String>>) {
        val sorted = items.sortedWith { a, b -> vn.mangatranslate.app.core.util.NaturalOrderComparator.compare(a.second, b.second) }
        if (sorted.size > MAX_BATCH) log("Chọn ${sorted.size} ảnh — chỉ lấy $MAX_BATCH trang đầu cho bài thử.")
        batchUris = sorted.take(MAX_BATCH)
        _state.update { it.copy(batchPages = batchUris.map { p -> p.second }, batchRows = emptyList(), batchLocation = null) }
        log("Đã chọn ${batchUris.size} trang: ${batchUris.joinToString { it.second }}")
    }

    /**
     * Dịch tuần tự từng trang, lưu JSON + báo cáo ngay sau mỗi trang (trang đã xong không mất nếu dừng giữa chừng).
     * Gặp giới hạn gói: dừng gửi trang mới, giữ kết quả đã có.
     */
    fun runBatch() {
        if (_state.value.limitReached) return log("Đã chạm giới hạn sử dụng — không gửi yêu cầu mới.")
        val model = _state.value.selectedModel ?: return log("Chưa chọn mô hình.")
        val pages = batchUris
        if (pages.isEmpty()) return log("Chưa chọn trang nào.")
        run("Đang dịch thử ${pages.size} trang…") {
            val app = getApplication<Application>()
            val fgs = OAuthWaitService.start(app, "Đang dịch thử bằng ChatGPT", "${pages.size} trang — giữ kết nối khi màn hình tắt hoặc chuyển ứng dụng")
            log(if (fgs == null) "FOREGROUND_SERVICE_REQUESTED (giữ kết nối trong lúc dịch)" else "FOREGROUND_SERVICE_FAILED $fgs")
            try {
            val runId = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val writer = TestRunWriter(app, runId)
            _state.update { it.copy(batchRows = pages.map { p -> BatchRow(p.second, "chờ") }, batchLocation = writer.location) }
            log("Lượt thử $runId, mô hình $model, lưu tại ${writer.location}")
            val rows = pages.map { BatchRow(it.second, "chờ") }.toMutableList()
            val report = StringBuilder()
            fun saveReport() = runCatching {
                writer.write("bao_cao.txt", reportHeader(runId, model, rows) + report.toString())
            }.onFailure { log("Không lưu được báo cáo: ${it.javaClass.name}: ${it.message}") }

            for ((i, page) in pages.withIndex()) {
                val (uri, name) = page
                rows[i] = rows[i].copy(status = "đang gửi")
                _state.update { it.copy(batchRows = rows.toList(), busy = "Trang ${i + 1}/${pages.size}: $name") }
                val (img, info) = try {
                    withContext(Dispatchers.Default) { encodeImage(uri) }
                } catch (e: Exception) {
                    rows[i] = rows[i].copy(status = "lỗi ảnh", error = "${e.javaClass.name}: ${e.message}")
                    report.append("\n## ${i + 1}. $name — LỖI ẢNH: ${e.message}\n")
                    saveReport(); continue
                }
                val t0 = System.currentTimeMillis()
                val reply = try {
                    sendWithRetry(name) { tok ->
                        client.respond(tok, model, ChatGptClient.MANGA_INSTRUCTIONS,
                            "Đọc và dịch trang truyện này. Chỉ trả về JSON theo mẫu.", img)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    rows[i] = rows[i].copy(status = "lỗi mạng", error = "${e.javaClass.name}: ${e.message}")
                    report.append("\n## ${i + 1}. $name — LỖI MẠNG: ${e.javaClass.name}: ${e.message}\n")
                    log("Trang $name: ${e.javaClass.name}: ${e.message}")
                    saveReport(); continue
                }
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                val usage = reply.usage?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                val parsed = if (reply.ok) MangaJson.parse(reply.text) else null
                rows[i] = rows[i].copy(
                    status = reply.status, seconds = secs,
                    regions = parsed?.regions?.size ?: 0,
                    uncertain = parsed?.regions?.count { it.uncertain } ?: 0,
                    problems = parsed?.problems?.size ?: 0,
                    inputTokens = usage?.optLong("input_tokens"),
                    outputTokens = usage?.optLong("output_tokens"),
                    totalTokens = usage?.optLong("total_tokens"),
                    error = listOfNotNull(reply.errorCode, reply.errorMessage?.take(300)).joinToString(" ").ifEmpty { null }
                )
                log("Trang ${i + 1}/${pages.size} $name: ${reply.status}, ${"%.1f".format(secs)} s, " +
                    "${rows[i].regions} vùng, ${rows[i].problems} vấn đề, token ${rows[i].totalTokens ?: "?"}")
                // Lưu ngay: JSON thô của AI + phần báo cáo của trang.
                runCatching { writer.write(name.substringBeforeLast('.') + ".json", reply.text.ifEmpty { "{}" }, "application/json") }
                    .onFailure { log("Không lưu được JSON trang $name: ${it.message}") }
                report.append("\n## ${i + 1}. $name — ${reply.status}, ${"%.1f".format(secs)} s, $info\n")
                rows[i].error?.let { report.append("Lỗi: $it\n") }
                parsed?.problems?.forEach { report.append("! $it\n") }
                parsed?.regions?.forEach { r ->
                    report.append("#${r.id} [${r.type}]${if (r.uncertain) " (cần kiểm tra)" else ""} bbox=${r.bbox?.joinToString(",") { "%.3f".format(Locale.US, it) }}\n")
                    report.append("  JA: ${r.ja}\n  VI: ${r.vi}\n")
                }
                saveReport()
                _state.update { it.copy(batchRows = rows.toList()) }
                if (reply.limitReached) {
                    log("Chạm giới hạn sử dụng ở trang ${i + 1}. Dừng; ${i} trang trước đã được lưu.")
                    _state.update { it.copy(limitReached = true) }
                    for (j in i + 1 until rows.size) rows[j] = rows[j].copy(status = "chưa gửi (giới hạn)")
                    _state.update { it.copy(batchRows = rows.toList()) }
                    saveReport()
                    break
                }
            }
            log("Xong lượt thử. Báo cáo: ${writer.location}/bao_cao.txt")
            _state.update { it.copy(batchFailed = rows.filter { r -> r.status != "completed" }.map { r -> r.name }) }
            } finally {
                OAuthWaitService.stop(app)
            }
        }
    }

    /**
     * Gửi một trang; nếu MẠNG rớt (IOException: mất kết nối, hết giờ) thì thử lại tối đa 2 lần, chờ 5 s rồi 15 s.
     * Không thử lại khi máy chủ trả lỗi hoặc báo chạm giới hạn gói.
     */
    private suspend fun sendWithRetry(name: String, call: (String) -> ChatGptClient.Reply): ChatGptClient.Reply {
        val waits = listOf(5_000L, 15_000L)
        var attempt = 0
        while (true) {
            val token = tokenForInference() ?: throw IllegalStateException("Thiếu quyền chatgpt.tokens.use.direct")
            try {
                return withContext(Dispatchers.IO) { call(token) }
            } catch (e: java.io.IOException) {
                if (attempt >= waits.size) throw e
                log("Trang $name: ${e.javaClass.name}: ${e.message} — thử lại sau ${waits[attempt] / 1000} s (lần ${attempt + 2}/3)")
                kotlinx.coroutines.delay(waits[attempt])
                attempt++
            }
        }
    }

    /** Chỉ chọn lại các trang lỗi của lượt trước rồi chạy lượt mới. */
    fun retryFailed() {
        val failed = _state.value.batchFailed.toSet()
        val pages = batchUris.filter { it.second in failed }
        if (pages.isEmpty()) return log("Không có trang lỗi để dịch lại.")
        batchUris = pages
        _state.update { it.copy(batchPages = pages.map { p -> p.second }, batchRows = emptyList()) }
        log("Dịch lại ${pages.size} trang lỗi: ${pages.joinToString { it.second }}")
        runBatch()
    }

    private fun reportHeader(runId: String, model: String, rows: List<BatchRow>): String {
        val ok = rows.count { it.status == "completed" }
        val tokens = rows.mapNotNull { it.totalTokens }.sum()
        return buildString {
            append("Manga Translator VN — thử ChatGPT nhiều trang (giai đoạn D)\n")
            append("Lượt: $runId  Mô hình: $model\n")
            append("Thành công: $ok/${rows.size}  Lỗi: ${rows.count { it.status != "completed" && it.status != "chờ" && !it.status.startsWith("chưa") }}\n")
            append("Tổng token máy chủ báo: $tokens (chỉ để tham khảo; không suy ra số trang/ngày)\n")
            append("Vị trí bbox là ước lượng của AI (tỉ lệ 0..1).\n\n")
            rows.forEachIndexed { i, r ->
                append("${i + 1}. ${r.name}: ${r.status}  ${"%.1f".format(r.seconds)} s  vùng=${r.regions}  cần kiểm tra=${r.uncertain}  vấn đề JSON=${r.problems}  token=${r.totalTokens ?: "?"}\n")
            }
        }
    }

    // ---------------------------------------------------------------- giai đoạn E: chèn tiếng Việt lên 1 trang

    private var renderJsonText: String? = null

    /** Dùng tệp JSON đã lưu ở lượt nhiều trang (không phải gửi lại ảnh cho ChatGPT). */
    fun pickRenderJson(uri: Uri, name: String?) = run("Đang đọc JSON…") {
        val text = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } ?: throw IllegalStateException("Không đọc được tệp")
        val p = MangaJson.parse(text)
        if (p.regions.isEmpty()) throw IllegalStateException("JSON không có vùng nào: ${p.problems.joinToString()}")
        renderJsonText = text
        _state.update { it.copy(renderJsonName = name ?: uri.lastPathSegment) }
        log("JSON để render: ${name ?: uri.lastPathSegment} — ${p.regions.size} vùng, ${p.problems.size} vấn đề")
    }

    /**
     * Chèn bản dịch lên ảnh gốc (bản sao) và lưu: ảnh gốc (sao chép nguyên byte), JSON, ảnh đã chèn, nhật ký từng vùng.
     */
    fun renderPage() {
        val uri = imageUri ?: return log("Chưa chọn ảnh ở mục 3.")
        val json = renderJsonText ?: _state.value.reply?.takeIf { it.ok && _state.value.parsed != null }?.text
            ?: return log("Chưa có JSON: gửi ảnh ở mục 3 hoặc chọn tệp JSON đã lưu.")
        val name = _state.value.imageName ?: "trang"
        run("Đang chèn tiếng Việt lên ảnh…") {
            val app = getApplication<Application>()
            val parsed = MangaJson.parse(json)
            if (parsed.regions.isEmpty()) throw IllegalStateException("JSON không có vùng nào.")
            val loaded = vn.mangatranslate.app.core.image.ImageLoader(app).loadFull(uri)
            if (loaded.sampleSize > 1) log("Ảnh rất lớn: giải mã ở 1/${loaded.sampleSize} độ phân giải.")
            val result = withContext(Dispatchers.Default) { vn.mangatranslate.app.core.chatgpt.AiPageRenderer().render(loaded.bitmap, parsed.regions) }
            val runId = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val writer = TestRunWriter(app, runId, "render_test")
            val base = name.substringBeforeLast('.')
            withContext(Dispatchers.IO) {
                val origBytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Không đọc lại được ảnh gốc")
                val ext = name.substringAfterLast('.', "jpg").lowercase()
                writer.writeBytes("1_goc_$name", origBytes, if (ext == "png") "image/png" else if (ext == "webp") "image/webp" else "image/jpeg")
                writer.write("2_${base}.json", json, "application/json")
                val png = ByteArrayOutputStream().also { result.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                writer.writeBytes("3_${base}_vi.png", png, "image/png")
                writer.write("4_render_log.txt", result.log)
            }
            // Ảnh xem trước nhỏ để không tốn RAM.
            val bmp = result.bitmap
            val sc = minOf(1f, 1400f / maxOf(bmp.width, bmp.height))
            val preview = if (sc < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true) else bmp
            if (preview !== bmp) bmp.recycle()
            loaded.bitmap.recycle()
            _state.update { it.copy(renderPreview = preview, renderLog = result.log, renderLocation = writer.location) }
            log("Render xong: vẽ ${result.drawn} vùng, giữ nguyên ${result.skipped} vùng; điểm ảnh đổi ngoài vùng cho phép: ${result.changedOutside}. Lưu tại ${writer.location}")
        }
    }

    override fun onCleared() {
        server?.close()
        OAuthWaitService.stop(getApplication())
        super.onCleared()
    }

    private companion object {
        const val MAX_SIDE = 2048
        const val MAX_BATCH = 10
    }
}
