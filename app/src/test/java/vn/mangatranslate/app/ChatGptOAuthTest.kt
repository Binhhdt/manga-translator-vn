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

    @Test
    fun loopbackServerReturnsCallbackParams() {
        LoopbackServer.open(listOf(0)).use { server ->
            val port = server.port
            val t = Thread {
                // Một yêu cầu không liên quan trước (trình duyệt hay xin favicon) → 404, server tiếp tục chờ.
                val f = URL("http://127.0.0.1:$port/favicon.ico").openConnection() as HttpURLConnection
                assertEquals(404, f.responseCode); f.disconnect()
                val c = URL("http://127.0.0.1:$port/auth/callback?code=c1&state=s1&client_id=oaiapp_1").openConnection() as HttpURLConnection
                assertEquals(200, c.responseCode)
                assertTrue(c.inputStream.bufferedReader().readText().contains("Quay lại"))
                c.disconnect()
            }
            t.start()
            val params = server.awaitCallback(10_000, "<p>Quay lại</p>")
            t.join(5_000)
            assertEquals(mapOf("code" to "c1", "state" to "s1", "client_id" to "oaiapp_1"), params)
        }
    }

    @Test
    fun loopbackServerTimesOut() {
        LoopbackServer.open(listOf(0)).use { assertNull(it.awaitCallback(300, "")) }
    }
}
