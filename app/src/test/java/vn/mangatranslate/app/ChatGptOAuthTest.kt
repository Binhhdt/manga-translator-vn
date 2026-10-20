package vn.mangatranslate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.mangatranslate.app.core.chatgpt.LoopbackServer
import vn.mangatranslate.app.core.chatgpt.OAuthUtil
import vn.mangatranslate.app.core.chatgpt.SseParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class ChatGptOAuthTest {

    @Test
    fun pkceMatchesRfc7636Vector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OAuthUtil.s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
    }

    @Test
    fun randomValuesAreUrlSafeAndUnique() {
        val a = OAuthUtil.newAttempt(1455)
        assertTrue(a.verifier.length in 43..128)
        assertTrue(Regex("[A-Za-z0-9_-]+").matches(a.verifier))
        assertTrue(a.state != a.nonce)
        assertEquals("http://127.0.0.1:1455/auth/callback", a.redirectUri)
    }

    private fun query(url: String): Map<String, String> =
        url.substringAfter('?').split("&").associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    @Test
    fun firstRegistrationUsesDynamicClientAndNameHint() {
        val a = OAuthUtil.newAttempt(1456)
        val url = OAuthUtil.authorizeUrl(a, "urn:uuid:x", null, "should-not-be-sent")
        assertTrue(url.startsWith("https://auth.openai.com/api/accounts/authorize?"))
        val q = query(url)
        assertEquals("dynamic_agent_client", q["client_id"])
        assertEquals("Manga Translator VN", q["agent_name_hint"])
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", q["scope"])
        assertEquals("https://api.openai.com/v1", q["resource"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(OAuthUtil.s256(a.verifier), q["code_challenge"])
        assertEquals("http://127.0.0.1:1456/auth/callback", q["redirect_uri"])
        assertEquals("code", q["response_type"])
        assertNull(q["id_token_hint"])
        assertFalse(url.contains("+")) // khoảng trắng mã hóa %20
    }

    @Test
    fun laterSignInUsesIssuedClientWithoutNameHint() {
        val q = query(OAuthUtil.authorizeUrl(OAuthUtil.newAttempt(1455), "urn:uuid:x", "oaiapp_abc", "tok"))
        assertEquals("oaiapp_abc", q["client_id"])
        assertNull(q["agent_name_hint"])
        assertEquals("tok", q["id_token_hint"])
    }

    @Test
    fun parsesCallbackRequestLine() {
        val p = OAuthUtil.parseRequestLine("GET /auth/callback?code=a%2Bb&state=xyz&scope=openid%20chatgpt.tokens.use.direct HTTP/1.1")
        assertNotNull(p)
        assertEquals("/auth/callback", p!!.first)
        assertEquals("a+b", p.second["code"])
        assertEquals("xyz", p.second["state"])
        assertTrue(OAuthUtil.hasPlanScope(p.second["scope"]))
        assertNull(OAuthUtil.parseRequestLine("POST /auth/callback HTTP/1.1"))
        assertFalse(OAuthUtil.hasPlanScope("openid profile"))
        assertFalse(OAuthUtil.hasPlanScope(null))
    }

    @Test
    fun sseParserGroupsEvents() {
        val got = mutableListOf<Pair<String?, String>>()
        val p = SseParser { e, d -> got += e to d }
        listOf(
            ": keep-alive", "event: response.output_text.delta", "data: {\"delta\":\"Xin\"}", "",
            "event: response.output_text.delta\r", "data: {\"delta\":\" chào\"}\r", "\r",
            "data: line1", "data: line2", "", "data: [DONE]", ""
        ).forEach(p::feedLine)
        p.flush()
        assertEquals(3, got.size)
        assertEquals("response.output_text.delta" to "{\"delta\":\"Xin\"}", got[0])
        assertEquals("{\"delta\":\" chào\"}", got[1].second)
        assertEquals(null to "line1\nline2", got[2])
    }

    private fun http(port: Int, path: String): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 3000
        return try {
            val code = c.responseCode
            code to ((if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText() ?: "")
        } finally { c.disconnect() }
    }

    @Test
    fun loopbackServerBindsSelfTestsAndAnswersImmediately() {
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        LoopbackServer.open("S1", "<p>Quay lại</p>", { logs += it }, listOf(0)).use { server ->
            val port = server.port
            assertEquals("127.0.0.1", server.boundHost)
            assertTrue(logs.any { it.startsWith("LISTENER_BOUND 127.0.0.1:$port") })
            assertTrue(server.selfTest() >= 0)
            assertEquals(200, http(port, "/health").first)

            // Socket "mở sẵn" không gửi gì (như Chrome preconnect) không được chặn các yêu cầu khác.
            val idle = java.net.Socket(java.net.Proxy.NO_PROXY).apply { connect(java.net.InetSocketAddress("127.0.0.1", port), 2000) }
            assertEquals(404, http(port, "/favicon.ico").first)

            // state sai → 400, listener tiếp tục chờ.
            val bad = http(port, "/auth/callback?code=c0&state=WRONG")
            assertEquals(400, bad.first)
            assertNull(server.awaitCallback(200))

            // Callback đúng nhận 200 NGAY, trước khi ai gọi awaitCallback (tức trước bước đổi token).
            val ok = http(port, "/auth/callback?code=c1&state=S1&client_id=oaiapp_1")
            assertEquals(200, ok.first)
            assertTrue(ok.second.contains("Quay lại"))
            assertEquals(mapOf("code" to "c1", "state" to "S1", "client_id" to "oaiapp_1"), server.awaitCallback(1000))
            idle.close()
            assertTrue(logs.containsAll(listOf("STATE_VALIDATED", "HTTP_RESPONSE_SENT 200")))
            // Nhật ký không chứa code hay state.
            assertFalse(logs.any { it.contains("c1") || it.contains("S1") || it.contains("WRONG") })
        }
    }

    @Test
    fun loopbackServerTimesOutAndCloses() {
        val server = LoopbackServer.open(ports = listOf(0))
        val port = server.port
        assertNull(server.awaitCallback(300))
        server.close()
        assertTrue(runCatching { http(port, "/health") }.isFailure)
    }

    @Test
    fun busyPortFallsBackToNext() {
        val s1 = LoopbackServer.open(ports = listOf(0))
        val s2 = LoopbackServer.open(ports = listOf(s1.port, 0))
        assertTrue(s2.port != s1.port)
        s2.close(); s1.close()
    }
}
