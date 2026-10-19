package vn.mangatranslate.app.core.chatgpt

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Phần thuần Kotlin của OAuth "Sign in with ChatGPT" (luồng cho ứng dụng mã nguồn mở), theo tài liệu:
 * https://developers.openai.com/siwc/token-sharing-open-source/sign-in
 * Không có bí mật nào trong mã: ứng dụng là public client, dùng PKCE S256.
 */
object OAuthUtil {

    const val AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
    const val TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
    const val DISCOVERY_URL = "https://auth.openai.com/.well-known/openid-configuration"
    const val RESOURCE = "https://api.openai.com/v1"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    /** client_id dùng cho LẦN ĐĂNG KÝ ĐẦU; OpenAI trả về client_id thật (oaiapp_…) qua callback. */
    const val DYNAMIC_CLIENT = "dynamic_agent_client"
    const val CALLBACK_PATH = "/auth/callback"
    const val AGENT_NAME = "Manga Translator VN"

    private val random = SecureRandom()
    private val b64url = Base64.getUrlEncoder().withoutPadding()

    fun randomToken(bytes: Int = 32): String = ByteArray(bytes).also { random.nextBytes(it) }.let { b64url.encodeToString(it) }

    /** code_challenge = Base64url(SHA-256(verifier)), không đệm (RFC 7636). */
    fun s256(verifier: String): String =
        b64url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Callback BẮT BUỘC dùng 127.0.0.1 (tài liệu: không thay bằng localhost). */
    fun redirectUri(port: Int) = "http://127.0.0.1:$port$CALLBACK_PATH"

    data class Attempt(val state: String, val nonce: String, val verifier: String, val redirectUri: String)

    fun newAttempt(port: Int) = Attempt(randomToken(), randomToken(), randomToken(48), redirectUri(port))

    /**
     * URL đăng nhập. Lần đầu: client_id=dynamic_agent_client + agent_name_hint. Các lần sau: client_id đã được cấp,
     * KHÔNG gửi agent_name_hint.
     */
    fun authorizeUrl(a: Attempt, hostId: String, issuedClientId: String?, idTokenHint: String? = null): String {
        val p = linkedMapOf(
            "client_id" to (issuedClientId ?: DYNAMIC_CLIENT),
            "ext_agent_host_id" to hostId,
            "response_type" to "code",
            "redirect_uri" to a.redirectUri,
            "scope" to SCOPES,
            "resource" to RESOURCE,
            "state" to a.state,
            "nonce" to a.nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to s256(a.verifier)
        )
        if (issuedClientId == null) p["agent_name_hint"] = AGENT_NAME
        // Tài liệu: giữ ID token để lần đăng nhập sau gửi id_token_hint.
        if (issuedClientId != null && !idTokenHint.isNullOrEmpty()) p["id_token_hint"] = idTokenHint
        return AUTHORIZE_URL + "?" + form(p)
    }

    fun form(p: Map<String, String>): String =
        p.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Tách dòng yêu cầu HTTP "GET /auth/callback?code=…&state=… HTTP/1.1" → (đường dẫn, tham số). */
    fun parseRequestLine(line: String): Pair<String, Map<String, String>>? {
        val parts = line.trim().split(" ")
        if (parts.size < 2 || parts[0] != "GET") return null
        val target = parts[1]
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val params = LinkedHashMap<String, String>()
        if (q >= 0) {
            for (kv in target.substring(q + 1).split("&")) {
                if (kv.isEmpty()) continue
                val i = kv.indexOf('=')
                val k = URLDecoder.decode(if (i >= 0) kv.substring(0, i) else kv, "UTF-8")
                val v = if (i >= 0) URLDecoder.decode(kv.substring(i + 1), "UTF-8") else ""
                params[k] = v
            }
        }
        return path to params
    }

    /** ext_agent_host_id dạng urn:uuid (UUIDv4) — giá trị mờ, không chứa thông tin người dùng. */
    fun newHostId(): String = "urn:uuid:" + java.util.UUID.randomUUID().toString()

    fun hasPlanScope(scope: String?): Boolean = scope?.split(" ")?.contains(PLAN_SCOPE) == true
}
