package vn.mangatranslate.app.core.chatgpt

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * Máy chủ HTTP tối giản trên 127.0.0.1 để nhận callback OAuth (tài liệu yêu cầu callback loopback).
 * Chỉ nghe trên giao diện loopback — ứng dụng khác trên mạng không truy cập được.
 * Thuần Java/Kotlin (không phụ thuộc Android) nên kiểm thử được trên JVM.
 */
class LoopbackServer private constructor(private val socket: ServerSocket) : Closeable {

    val port: Int get() = socket.localPort

    /**
     * Chờ trình duyệt gọi về [OAuthUtil.CALLBACK_PATH]. Các yêu cầu khác (favicon…) trả 404 và chờ tiếp.
     * @return tham số trên URL callback (code, state, client_id, error…), hoặc null nếu hết giờ.
     */
    fun awaitCallback(timeoutMs: Int, successHtml: String): Map<String, String>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = (deadline - System.currentTimeMillis()).toInt()
            if (left <= 0) return null
            socket.soTimeout = left
            val client = try {
                socket.accept()
            } catch (e: SocketTimeoutException) {
                return null
            }
            client.use { c ->
                c.soTimeout = 10_000
                val reader = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = reader.readLine() ?: return@use
                // Đọc hết phần header để trình duyệt không bị cắt kết nối.
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val parsed = OAuthUtil.parseRequestLine(requestLine)
                val out = c.getOutputStream()
                if (parsed == null || parsed.first != OAuthUtil.CALLBACK_PATH) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return@use
                }
                val body = successHtml.toByteArray(Charsets.UTF_8)
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                        "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
                )
                out.write(body)
                out.flush()
                return parsed.second
            }
        }
    }

    override fun close() = socket.close()

    companion object {
        /** Cổng giống ví dụ của tài liệu; nếu bận thì thử cổng kế tiếp (tài liệu cho phép đổi cổng giữa các lần). */
        val PORTS = listOf(1455, 1456, 1457, 1458, 1459, 1460)

        fun open(ports: List<Int> = PORTS): LoopbackServer {
            val lo = InetAddress.getByName("127.0.0.1")
            var last: Exception? = null
            for (p in ports) {
                try {
                    return LoopbackServer(ServerSocket(p, 4, lo))
                } catch (e: Exception) {
                    last = e
                }
            }
            throw IllegalStateException("Không mở được cổng callback ${ports.first()}–${ports.last()}: ${last?.message}")
        }
    }
}
