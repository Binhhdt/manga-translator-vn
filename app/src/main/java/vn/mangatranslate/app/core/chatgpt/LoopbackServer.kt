package vn.mangatranslate.app.core.chatgpt

import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Máy chủ HTTP tối giản CHỈ trên 127.0.0.1 để nhận callback OAuth (callback loopback theo tài liệu OpenAI).
 *
 * - Một luồng riêng chỉ làm accept(); mỗi kết nối được xử lý trên luồng khác → một kết nối "chờ sẵn"
 *   (Chrome hay mở trước vài socket rồi không gửi gì) không chặn được callback thật.
 * - Phản hồi HTTP được gửi NGAY khi đọc xong yêu cầu, trước khi ứng dụng đổi code lấy token.
 * - /health để tự kiểm tra kết nối HTTP trước khi mở trình duyệt.
 * - Không phụ thuộc Android → kiểm thử được trên JVM. Nhật ký không chứa code, state hay token.
 */
class LoopbackServer private constructor(
    private val socket: ServerSocket,
    private val expectedState: String?,
    private val successHtml: String,
    private val log: (String) -> Unit
) : Closeable {

    val port: Int get() = socket.localPort
    /** Địa chỉ thực tế socket đang nghe, ví dụ "127.0.0.1". */
    val boundHost: String get() = socket.inetAddress.hostAddress ?: "?"
    val redirectUri: String get() = OAuthUtil.redirectUri(port)

    private val healthToken = OAuthUtil.randomToken(12)
    private val closed = AtomicBoolean(false)
    private val result = CompletableFuture<Map<String, String>>()
    private val connCount = AtomicInteger(0)
    private val workers: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "oauth-loopback-conn").apply { isDaemon = true }
    }
    private val acceptor = Thread({ acceptLoop() }, "oauth-loopback-accept").apply { isDaemon = true }

    /** Số kết nối TCP đã accept() (để chẩn đoán). */
    val acceptedConnections: Int get() = connCount.get()

    private fun acceptLoop() {
        while (!closed.get()) {
            val s = try {
                socket.accept()
            } catch (e: Exception) {
                if (!closed.get()) log("LISTENER_ERROR accept(): ${e.javaClass.name}: ${e.message}")
                break
            }
            connCount.incrementAndGet()
            try {
                workers.execute { handle(s) }
            } catch (e: Exception) {
                runCatching { s.close() }
            }
        }
    }

    private fun handle(s: Socket) {
        try {
            s.use { c ->
                c.soTimeout = 15_000
                c.tcpNoDelay = true
                val input = c.getInputStream()
                val requestLine = readLine(input)
                if (requestLine == null) return // kết nối mở sẵn nhưng không gửi gì
                while (true) { // bỏ qua phần header
                    val h = readLine(input) ?: break
                    if (h.isEmpty()) break
                }
                val parsed = OAuthUtil.parseRequestLine(requestLine)
                val path = parsed?.first
                when (path) {
                    HEALTH_PATH -> respond(c, 200, "text/plain; charset=utf-8", "ok $healthToken")
                    OAuthUtil.CALLBACK_PATH -> {
                        val params = parsed.second
                        log("CALLBACK_ACCEPTED (tham số: ${params.keys.joinToString()})")
                        if (expectedState != null && params["state"] != expectedState) {
                            // state sai: từ chối, KHÔNG dùng callback này, tiếp tục chờ callback đúng.
                            respond(c, 400, "text/html; charset=utf-8", REJECT_HTML)
                            log("HTTP_RESPONSE_SENT 400; STATE_INVALID — bỏ qua callback này, tiếp tục chờ")
                            return
                        }
                        if (expectedState != null) log("STATE_VALIDATED")
                        // Gửi phản hồi TRƯỚC, rồi mới chuyển kết quả cho bước đổi token.
                        respond(c, 200, "text/html; charset=utf-8", successHtml)
                        log("HTTP_RESPONSE_SENT 200")
                        result.complete(params)
                    }
                    else -> respond(c, 404, "text/plain; charset=utf-8", "not found")
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            // Kết nối Chrome mở sẵn nhưng không gửi yêu cầu — bình thường, không ghi nhật ký.
        } catch (e: Exception) {
            if (!closed.get()) log("CONNECTION_ERROR ${e.javaClass.name}: ${e.message}")
        }
    }

    /** Đọc một dòng HTTP (kết thúc \n), tối đa 8 KB. null nếu kết nối đóng trước khi có dữ liệu. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().removeSuffix("\r")
            if (sb.length > 8192) throw IllegalStateException("Dòng HTTP quá dài")
            sb.append(b.toChar())
        }
    }

    private fun respond(c: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; else -> "Not Found" }
        val out = c.getOutputStream()
        out.write(
            ("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.write(bytes)
        out.flush()
    }

    /**
     * Tự kiểm tra: kết nối TCP thật tới 127.0.0.1:port (không qua proxy), gửi GET /health,
     * yêu cầu nhận "200" và đúng mã ngẫu nhiên của CHÍNH máy chủ này (không phải ứng dụng khác chiếm cổng).
     * @return thời gian (ms). Ném lỗi nếu thất bại.
     */
    fun selfTest(timeoutMs: Int = 3000): Long {
        val t0 = System.nanoTime()
        Socket(Proxy.NO_PROXY).use { s ->
            s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeoutMs)
            s.soTimeout = timeoutMs
            s.getOutputStream().apply {
                write("GET $HEALTH_PATH HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val resp = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            val status = resp.substringBefore("\r\n")
            if (!status.startsWith("HTTP/1.1 200")) throw IllegalStateException("Tự kiểm tra /health nhận \"$status\"")
            if (!resp.endsWith("ok $healthToken")) throw IllegalStateException("Cổng $port trả lời nhưng không phải máy chủ của ứng dụng")
        }
        return (System.nanoTime() - t0) / 1_000_000
    }

    /** Chờ callback có state hợp lệ. null nếu hết giờ hoặc máy chủ đã đóng. */
    fun awaitCallback(timeoutMs: Long): Map<String, String>? = try {
        result.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        null
    } catch (e: java.util.concurrent.CancellationException) {
        null
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
            workers.shutdownNow()
            result.cancel(false)
        }
    }

    companion object {
        const val HEALTH_PATH = "/health"
        /** Cổng giống ví dụ của tài liệu; nếu bận thì thử cổng kế tiếp (tài liệu cho phép đổi cổng giữa các lần). */
        val PORTS = listOf(1455, 1456, 1457, 1458, 1459, 1460)
        /** Hàng đợi kết nối lớn: Chrome có thể mở sẵn nhiều socket; hàng đợi đầy thì SYN bị bỏ → "connection timed out". */
        const val BACKLOG = 64

        private const val REJECT_HTML = """<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="font-family:sans-serif;margin:32px 20px"><h2>Phản hồi đăng nhập không hợp lệ</h2><p>Mã state không khớp nên ứng dụng bỏ qua. Hãy đăng nhập lại từ ứng dụng.</p></body></html>"""

        /**
         * Mở listener: bind 127.0.0.1, xác minh bind thành công (isBound, địa chỉ, cổng), rồi mới chạy luồng accept
         * và ghi LISTENER_BOUND.
         */
        fun open(
            expectedState: String? = null,
            successHtml: String = "",
            log: (String) -> Unit = {},
            ports: List<Int> = PORTS
        ): LoopbackServer {
            val lo = InetAddress.getByName("127.0.0.1")
            val errors = ArrayList<String>()
            for (p in ports) {
                val ss = try {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(lo, p), BACKLOG)
                    }
                } catch (e: Exception) {
                    errors += "$p: ${e.javaClass.name}: ${e.message}"
                    log("LISTENER_BIND_FAILED cổng $p: ${e.javaClass.name}: ${e.message}")
                    continue
                }
                val addr = ss.inetAddress?.hostAddress
                if (!ss.isBound || addr != "127.0.0.1" || (p != 0 && ss.localPort != p)) {
                    errors += "$p: bind không như mong đợi (isBound=${ss.isBound}, addr=$addr, port=${ss.localPort})"
                    runCatching { ss.close() }
                    continue
                }
                val server = LoopbackServer(ss, expectedState, successHtml, log)
                server.acceptor.start()
                log("LISTENER_BOUND $addr:${ss.localPort} (backlog $BACKLOG)")
                return server
            }
            throw IllegalStateException("Không bind được cổng callback: ${errors.joinToString("; ")}")
        }
    }
}
