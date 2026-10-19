package vn.mangatranslate.app.core.chatgpt

/**
 * Bộ đọc Server-Sent Events (luồng `stream: true` của Responses API). Thuần Kotlin.
 * Mỗi sự kiện gồm các dòng "event: …" và "data: …", kết thúc bằng dòng trống.
 */
class SseParser(private val onEvent: (event: String?, data: String) -> Unit) {
    private var event: String? = null
    private val data = StringBuilder()

    fun feedLine(rawLine: String) {
        val line = rawLine.removeSuffix("\r")
        when {
            line.isEmpty() -> flush()
            line.startsWith(":") -> Unit // chú thích / giữ kết nối
            line.startsWith("event:") -> event = line.substring(6).trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).removePrefix(" "))
            }
        }
    }

    fun flush() {
        if (data.isNotEmpty() || event != null) {
            val d = data.toString()
            if (d != "[DONE]") onEvent(event, d)
        }
        event = null
        data.setLength(0)
    }
}
