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
    val limitReached: Boolean = false
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

    private fun log(s: String) = _state.update { it.copy(log = (it.log + "${time.format(Date())}  $s").takeLast(200)) }

    private fun run(label: String, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        job = viewModelScope.launch {
            _state.update { it.copy(busy = label) }
            try {
                block()
            } catch (e: Exception) {
                log("LỖI: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(busy = null) }
        log("Đã hủy thao tác.")
    }

    fun signIn() = run("Đang chờ đăng nhập trong trình duyệt…") {
        val acc = withContext(Dispatchers.IO) {
            auth.signIn(openBrowser = { url ->
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(i)
            }, log = ::log)
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
    fun pickImage(uri: Uri, name: String?) = run("Đang chuẩn bị ảnh…") {
        val (url, info) = withContext(Dispatchers.Default) {
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
            val bytes = bos.toByteArray()
            "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes) to
                "Gốc ${w}×${h} → gửi ${bmp.width}×${bmp.height} JPEG ${bytes.size / 1024} KB"
        }
        imageDataUrl = url
        _state.update { it.copy(imageName = name ?: uri.lastPathSegment, imageInfo = info) }
        log("Ảnh: ${name ?: uri.lastPathSegment} — $info")
    }

    private companion object {
        const val MAX_SIDE = 2048
    }
}
