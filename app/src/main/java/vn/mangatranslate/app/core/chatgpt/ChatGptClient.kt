package vn.mangatranslate.app.core.chatgpt

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gọi API bằng hạn mức gói ChatGPT (token từ Sign in with ChatGPT), KHÔNG dùng API key.
 * Theo tài liệu: chỉ store=false, stream=true, instructions thay cho vai trò system; không gửi temperature,
 * top_p, metadata, user, truncation, previous_response_id.
 */
class ChatGptClient {

    data class Model(val slug: String, val displayName: String, val visibility: String)

    data class ModelList(val models: List<Model>, val raw: String)

    /** Kết quả một lần gọi Responses. [ok] chỉ true khi nhận được response.completed. */
    data class Reply(
        val ok: Boolean,
        val text: String,
        val status: String,
        val errorCode: String?,
        val errorMessage: String?,
        val usage: String?,
        val events: Map<String, Int>,
        val limitReached: Boolean
    )

    fun listModels(accessToken: String): ModelList {
        val r = Http.get("$BASE/models", accessToken)
        if (r.status != 200) throw apiError("GET /v1/models", r.status, r.body)
        val j = JSONObject(r.body)
        val arr = j.optJSONArray("models") ?: j.optJSONArray("data") ?: JSONArray()
        val models = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Model(
                slug = it.optString("slug").ifEmpty { it.optString("id") },
                displayName = it.optString("display_name").ifEmpty { it.optString("slug").ifEmpty { it.optString("id") } },
                visibility = it.optString("visibility")
            )
        }
        return ModelList(models, r.body)
    }

    /**
     * Gửi một yêu cầu Responses có luồng. [imageDataUrl] dạng "data:image/jpeg;base64,…" (tùy chọn).
     * [onDelta] nhận từng đoạn chữ khi đang truyền.
     */
    fun respond(
        accessToken: String,
        model: String,
        instructions: String?,
        userText: String,
        imageDataUrl: String? = null,
        onDelta: (String) -> Unit = {}
    ): Reply {
        val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", userText))
        if (imageDataUrl != null) content.put(JSONObject().put("type", "input_image").put("image_url", imageDataUrl))
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("store", false)
            .put("stream", true)
        if (!instructions.isNullOrBlank()) body.put("instructions", instructions)

        val c = URL("$BASE/responses").openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 180_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $accessToken")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "text/event-stream")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = c.responseCode
            if (status != 200) {
                val err = c.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val code = errorCode(err)
                return Reply(false, "", "http_$status", code, err.take(800), null, emptyMap(), isLimit(code) || status == 429)
            }
            val text = StringBuilder()
            val counts = LinkedHashMap<String, Int>()
            var final: String? = null
            var errCode: String? = null
            var errMsg: String? = null
            var usage: String? = null
            val parser = SseParser { event, data ->
                val j = runCatching { JSONObject(data) }.getOrNull() ?: return@SseParser
                val type = j.optString("type").ifEmpty { event ?: "?" }
                counts[type] = (counts[type] ?: 0) + 1
                when (type) {
                    "response.output_text.delta" -> j.optString("delta").let { text.append(it); onDelta(it) }
                    "response.completed" -> {
                        final = "completed"
                        usage = j.optJSONObject("response")?.optJSONObject("usage")?.toString()
                    }
                    "response.failed" -> {
                        final = "failed"
                        val e = j.optJSONObject("response")?.optJSONObject("error")
                        errCode = e?.optString("code")?.ifEmpty { null } ?: "unknown_error"
                        errMsg = e?.optString("message")
                    }
                    "response.incomplete" -> {
                        final = "incomplete"
                        errMsg = j.optJSONObject("response")?.optJSONObject("incomplete_details")?.toString()
                    }
                    "error" -> {
                        final = final ?: "error"
                        errCode = j.optString("code").ifEmpty { j.optJSONObject("error")?.optString("code") }
                        errMsg = j.optString("message").ifEmpty { data.take(500) }
                    }
                }
            }
            c.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach(parser::feedLine) }
            parser.flush()
            val st = final ?: "stream_ended_without_completed"
            return Reply(st == "completed", text.toString(), st, errCode, errMsg, usage, counts, isLimit(errCode))
        } finally {
            c.disconnect()
        }
    }

    private fun errorCode(body: String): String? = runCatching {
        val j = JSONObject(body)
        j.optJSONObject("error")?.optString("code")?.ifEmpty { null } ?: j.optString("code").ifEmpty { null }
    }.getOrNull()

    private fun apiError(what: String, status: Int, body: String): ChatGptError {
        val code = errorCode(body)
        return ChatGptError("$what: HTTP $status ${code ?: ""} — ${body.take(500)}", code, status)
    }

    companion object {
        const val BASE = "https://api.openai.com/v1"
        const val USAGE_URL = "https://chatgpt.com/settings/usage"
        val LIMIT_CODES = setOf("subscription_sharing_usage_limit_exceeded", "subscription_sharing_usage_unavailable")
        fun isLimit(code: String?) = code != null && code in LIMIT_CODES

        /** Hướng dẫn dịch (theo yêu cầu người dùng) + định dạng JSON để ứng dụng kiểm tra. */
        const val MANGA_INSTRUCTIONS = """Bạn là biên dịch viên manga Nhật → Việt.
Hãy quan sát toàn bộ trang truyện và đọc văn bản Nhật trong từng bong bóng thoại.
Hiểu bối cảnh câu chuyện trước khi dịch.
Xử lý chữ Nhật viết dọc theo đúng thứ tự đọc (cột từ phải sang trái, trên xuống dưới; các ô truyện từ phải sang trái).
Giữ nguyên tên nhân vật và địa danh theo cách phiên âm thích hợp.
Dịch tự nhiên như lời thoại trong truyện tranh Việt Nam. Không dịch máy móc từng từ.
Không tự bịa nội dung khi chữ không rõ; khi đó đặt "uncertain": true.
Với truyện thể thao, sử dụng chính xác thuật ngữ bóng đá.
Phân biệt lời thoại (dialogue), lời dẫn chuyện (narration) và chữ hiệu ứng (sfx).
Chỉ trả về MỘT đối tượng JSON, không kèm lời giải thích, theo đúng mẫu:
{"regions":[{"id":1,"type":"dialogue","ja":"văn bản Nhật gốc","vi":"bản dịch","bbox":[x,y,w,h],"uncertain":false}]}
bbox là vị trí ƯỚC LƯỢNG, tỉ lệ 0..1 theo chiều rộng/chiều cao ảnh, (x,y) là góc trên trái."""
    }
}

/** Kiểm tra JSON vùng chữ AI trả về. Tọa độ chỉ là ước lượng — không dùng làm pixel chính xác. */
object MangaJson {
    data class Region(val id: String, val type: String, val ja: String, val vi: String, val bbox: List<Double>?, val uncertain: Boolean)
    data class Parsed(val regions: List<Region>, val problems: List<String>)

    fun parse(text: String): Parsed {
        val problems = mutableListOf<String>()
        var t = text.trim()
        // Một số mô hình bọc JSON trong ```json … ```
        if (t.startsWith("```")) {
            t = t.substringAfter('\n').substringBeforeLast("```").trim()
            problems += "JSON được bọc trong ``` (đã gỡ)."
        }
        val root = try {
            JSONObject(t)
        } catch (e: Exception) {
            return Parsed(emptyList(), listOf("Không phải JSON hợp lệ: ${e.message}"))
        }
        val arr = root.optJSONArray("regions") ?: return Parsed(emptyList(), problems + "Thiếu mảng \"regions\".")
        val out = ArrayList<Region>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) { problems += "Phần tử $i không phải đối tượng."; continue }
            val ja = o.optString("ja"); val vi = o.optString("vi")
            if (ja.isBlank()) problems += "Vùng ${i + 1}: thiếu \"ja\"."
            if (vi.isBlank()) problems += "Vùng ${i + 1}: thiếu \"vi\"."
            val b = o.optJSONArray("bbox")
            val bbox = if (b != null && b.length() == 4) (0 until 4).map { b.optDouble(it) } else null
            if (bbox == null) problems += "Vùng ${i + 1}: bbox không đủ 4 số."
            else if (bbox.any { it.isNaN() || it < -0.01 || it > 1.01 }) problems += "Vùng ${i + 1}: bbox ngoài khoảng 0..1."
            out += Region(o.opt("id")?.toString() ?: "${i + 1}", o.optString("type"), ja, vi, bbox, o.optBoolean("uncertain"))
        }
        return Parsed(out, problems)
    }
}
