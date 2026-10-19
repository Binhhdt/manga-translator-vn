package vn.mangatranslate.app.core.chatgpt

import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** Lỗi có thông điệp nguyên văn từ máy chủ để hiển thị cho người dùng (không chứa token). */
class ChatGptError(message: String, val code: String? = null, val httpStatus: Int? = null) : Exception(message)

/** Kết quả một lần gọi HTTP: mã trạng thái + thân phản hồi nguyên văn. */
data class HttpResult(val status: Int, val body: String)

internal object Http {
    fun get(url: String, bearer: String? = null): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 60_000
        bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        c.setRequestProperty("Accept", "application/json")
        return read(c)
    }

    fun postForm(url: String, fields: Map<String, String>): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 60_000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setRequestProperty("Accept", "application/json")
        c.outputStream.use { it.write(OAuthUtil.form(fields).toByteArray(Charsets.UTF_8)) }
        return read(c)
    }

    private fun read(c: HttpURLConnection): HttpResult = try {
        val status = c.responseCode
        val stream = if (status >= 400) c.errorStream else c.inputStream
        HttpResult(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
    } finally {
        c.disconnect()
    }
}

/**
 * Luồng "Sign in with ChatGPT" cho ứng dụng mã nguồn mở, đúng các bước trong tài liệu chính thức:
 * authorize (PKCE S256 + state + nonce) → callback loopback 127.0.0.1 → đổi code lấy token →
 * xác minh ID token bằng JWKS → kiểm tra quyền chatgpt.tokens.use.direct → lưu mã hóa.
 */
class ChatGptAuth(private val store: TokenStore) {

    data class Discovery(val issuer: String, val jwksUri: String, val revocationEndpoint: String?)

    private var discovery: Discovery? = null

    fun discovery(): Discovery {
        discovery?.let { return it }
        val r = Http.get(OAuthUtil.DISCOVERY_URL)
        if (r.status != 200) throw ChatGptError("Không đọc được openid-configuration (HTTP ${r.status}): ${r.body.take(300)}", httpStatus = r.status)
        val j = JSONObject(r.body)
        return Discovery(j.getString("issuer"), j.getString("jwks_uri"), j.optString("revocation_endpoint").ifEmpty { null })
            .also { discovery = it }
    }

    /**
     * Đăng nhập. [openBrowser] mở URL bằng trình duyệt hệ thống. Hàm chặn luồng (gọi trên Dispatchers.IO).
     * [log] nhận các bước (không bao giờ chứa token).
     */
    fun signIn(openBrowser: (String) -> Unit, log: (String) -> Unit, timeoutMs: Int = 5 * 60_000): ChatGptAccount {
        val issued = store.issuedClientId()
        val previous = store.load()
        val hostId = store.hostId()
        val disc = discovery()
        log("issuer: ${disc.issuer}")
        LoopbackServer.open().use { server ->
            val attempt = OAuthUtil.newAttempt(server.port)
            log("Đang nghe callback tại ${attempt.redirectUri}")
            log(if (issued == null) "Lần đầu: client_id=dynamic_agent_client (đăng ký mới)" else "Dùng lại client_id đã cấp: $issued")
            val url = OAuthUtil.authorizeUrl(attempt, hostId, issued, previous?.takeIf { it.clientId == issued }?.idToken)
            openBrowser(url)
            val params = server.awaitCallback(timeoutMs, RETURN_PAGE)
                ?: throw ChatGptError("Hết thời gian chờ đăng nhập (5 phút). Trình duyệt không gọi về ${attempt.redirectUri}.")
            log("Nhận callback, tham số: ${params.keys.joinToString()}")

            // 1) state phải khớp trước mọi thứ khác.
            if (params["state"] != attempt.state) throw ChatGptError("state không khớp — bỏ qua callback này (có thể là yêu cầu giả).")
            params["error"]?.let { err ->
                throw ChatGptError("Đăng nhập bị từ chối: $err ${params["error_description"].orEmpty()}".trim(), code = err)
            }
            // 2) client_id: lần đầu phải có client_id được cấp; lần sau nếu có thì phải trùng.
            val cbClient = params["client_id"]
            val clientId = when {
                issued == null -> cbClient?.takeIf { it.isNotEmpty() && it != OAuthUtil.DYNAMIC_CLIENT }
                    ?: throw ChatGptError("Callback không trả client_id được cấp (oaiapp_…). Tham số: ${params.keys}")
                cbClient != null && cbClient != issued -> throw ChatGptError("Callback trả client_id khác ($cbClient) với client_id đã cấp ($issued) — từ chối.")
                else -> issued
            }
            if (issued == null) {
                store.saveIssuedClientId(clientId)
                log("Đã lưu client_id được cấp: $clientId")
            }
            val code = params["code"] ?: throw ChatGptError("Callback không có code.")
            val cbScope = params["scope"]

            // 3) Đổi code lấy token.
            val r = Http.postForm(
                OAuthUtil.TOKEN_URL, linkedMapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to clientId,
                    "code" to code,
                    "code_verifier" to attempt.verifier,
                    "redirect_uri" to attempt.redirectUri,
                    "resource" to OAuthUtil.RESOURCE
                )
            )
            if (r.status != 200) throw tokenError("Đổi code lấy token thất bại", r)
            val tok = JSONObject(r.body)
            log("Phản hồi token có các trường: ${tok.keys().asSequence().joinToString()}")

            // 4) Xác minh ID token.
            val idToken = tok.optString("id_token")
            if (idToken.isEmpty()) throw ChatGptError("Phản hồi token không có id_token.")
            val claims = verifyIdToken(idToken, disc, clientId, attempt.nonce)
            val sub = claims.getString("sub")
            if (previous != null && previous.clientId == clientId && previous.subject.isNotEmpty() && previous.subject != sub) {
                log("Lưu ý: tài khoản khác lần trước — thay thông tin đăng nhập cũ.")
            }

            // 5) Quyền được cấp.
            val scopes = tok.optString("scope").ifEmpty { cbScope.orEmpty() }
            val account = ChatGptAccount(
                email = claims.optString("email"), issuer = claims.getString("iss"), subject = sub,
                clientId = clientId, hostId = hostId, idToken = idToken,
                accessToken = tok.getString("access_token"), refreshToken = tok.optString("refresh_token"),
                tokenType = tok.optString("token_type", "Bearer"),
                expiresAt = System.currentTimeMillis() + tok.optLong("expires_in", 3600) * 1000,
                scopes = scopes, savedAt = System.currentTimeMillis()
            )
            store.save(account)
            log("Đã lưu thông tin đăng nhập (mã hóa bằng Android Keystore).")
            return account
        }
    }

    /** Trả access token còn hạn; tự làm mới khi còn dưới 2 phút. */
    fun validAccessToken(log: (String) -> Unit): ChatGptAccount {
        val a = store.load() ?: throw ChatGptError("Chưa đăng nhập ChatGPT.")
        if (a.expiresAt - System.currentTimeMillis() > 120_000) return a
        return refresh(a, log)
    }

    fun refresh(a: ChatGptAccount, log: (String) -> Unit): ChatGptAccount {
        if (a.refreshToken.isEmpty()) throw ChatGptError("Không có refresh token — cần đăng nhập lại.")
        log("Đang làm mới access token…")
        // Tài liệu chưa mô tả chi tiết yêu cầu làm mới; dùng dạng chuẩn OAuth 2.0 (RFC 6749 §6) cho public client.
        val r = Http.postForm(
            OAuthUtil.TOKEN_URL,
            linkedMapOf("grant_type" to "refresh_token", "client_id" to a.clientId, "refresh_token" to a.refreshToken)
        )
        if (r.status != 200) throw tokenError("Làm mới token thất bại — cần đăng nhập lại", r)
        val tok = JSONObject(r.body)
        // Thay access token, hạn dùng, quyền và refresh token (xoay vòng) cùng lúc.
        val updated = a.copy(
            accessToken = tok.getString("access_token"),
            refreshToken = tok.optString("refresh_token").ifEmpty { a.refreshToken },
            expiresAt = System.currentTimeMillis() + tok.optLong("expires_in", 3600) * 1000,
            scopes = tok.optString("scope").ifEmpty { a.scopes },
            idToken = tok.optString("id_token").ifEmpty { a.idToken },
            savedAt = System.currentTimeMillis()
        )
        store.save(updated)
        log("Đã làm mới token. Trường trả về: ${tok.keys().asSequence().joinToString()}")
        return updated
    }

    /** Đăng xuất: thu hồi refresh token nếu máy chủ công bố revocation_endpoint, rồi xóa token trên máy. */
    fun signOut(log: (String) -> Unit) {
        val a = store.load()
        try {
            val endpoint = discovery().revocationEndpoint
            if (a != null && a.refreshToken.isNotEmpty() && endpoint != null) {
                val r = Http.postForm(
                    endpoint,
                    linkedMapOf("token" to a.refreshToken, "token_type_hint" to "refresh_token", "client_id" to a.clientId)
                )
                log(if (r.status == 200) "Đã thu hồi refresh token." else "Thu hồi trả HTTP ${r.status}: ${r.body.take(300)}")
            } else if (endpoint == null) {
                log("Máy chủ không công bố revocation_endpoint — chỉ xóa token trên máy.")
            }
        } catch (e: Exception) {
            log("Không thu hồi được (${e.message}) — vẫn xóa token trên máy.")
        }
        store.clearTokens()
        log("Đã xóa token trên máy.")
    }

    private fun tokenError(prefix: String, r: HttpResult): ChatGptError {
        val code = runCatching { JSONObject(r.body).optString("error") }.getOrNull()?.ifEmpty { null }
        val desc = runCatching { JSONObject(r.body).optString("error_description") }.getOrNull().orEmpty()
        val hint = if (code == "invalid_grant") " (code hết hạn/đã dùng — bấm đăng nhập lại)" else ""
        return ChatGptError("$prefix: HTTP ${r.status} ${code ?: ""} $desc$hint".trim() +
            if (code == null) " — ${r.body.take(300)}" else "", code, r.status)
    }

    /** Xác minh chữ ký RS256 bằng JWKS, rồi kiểm tra iss, aud, exp, nonce. */
    private fun verifyIdToken(jwt: String, disc: Discovery, clientId: String, nonce: String): JSONObject {
        val parts = jwt.split(".")
        if (parts.size != 3) throw ChatGptError("ID token không đúng dạng JWT.")
        val dec = Base64.getUrlDecoder()
        val header = JSONObject(String(dec.decode(parts[0]), Charsets.UTF_8))
        val claims = JSONObject(String(dec.decode(parts[1]), Charsets.UTF_8))
        val alg = header.optString("alg")
        if (alg != "RS256") throw ChatGptError("ID token dùng thuật toán $alg — ứng dụng chỉ chấp nhận RS256.")
        val kid = header.optString("kid")

        val r = Http.get(disc.jwksUri)
        if (r.status != 200) throw ChatGptError("Không tải được JWKS (HTTP ${r.status}).")
        val keys = JSONObject(r.body).getJSONArray("keys")
        val jwk = (0 until keys.length()).map { keys.getJSONObject(it) }
            .firstOrNull { it.optString("kty") == "RSA" && (kid.isEmpty() || it.optString("kid") == kid) }
            ?: throw ChatGptError("Không tìm thấy khóa JWKS kid=$kid.")
        val pub = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(1, dec.decode(jwk.getString("n"))), BigInteger(1, dec.decode(jwk.getString("e"))))
        )
        val ok = Signature.getInstance("SHA256withRSA").run {
            initVerify(pub)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            verify(dec.decode(parts[2]))
        }
        if (!ok) throw ChatGptError("Chữ ký ID token KHÔNG hợp lệ.")
        if (claims.optString("iss") != disc.issuer) throw ChatGptError("ID token: iss=${claims.optString("iss")} khác ${disc.issuer}.")
        val audOk = when (val aud = claims.opt("aud")) {
            is String -> aud == clientId
            is org.json.JSONArray -> (0 until aud.length()).any { aud.optString(it) == clientId }
            else -> false
        }
        if (!audOk) throw ChatGptError("ID token: aud không khớp client_id $clientId.")
        if (claims.optLong("exp") * 1000 < System.currentTimeMillis() - 60_000) throw ChatGptError("ID token đã hết hạn.")
        if (claims.optString("nonce") != nonce) throw ChatGptError("ID token: nonce không khớp.")
        return claims
    }

    companion object {
        /** Trang trả về sau khi đăng nhập; liên kết intent:// đưa người dùng quay lại ứng dụng. */
        const val RETURN_PAGE = """<!doctype html><html lang="vi"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Manga Translator VN</title>
<style>body{font-family:sans-serif;margin:32px 20px;line-height:1.5}a{display:inline-block;margin-top:16px;padding:12px 20px;background:#1565c0;color:#fff;border-radius:8px;text-decoration:none}</style>
</head><body><h2>Đã nhận phản hồi đăng nhập</h2><p>Bạn có thể quay lại ứng dụng Manga Translator VN.</p>
<a href="intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=vn.mangatranslate.app;end">Quay lại ứng dụng</a>
</body></html>"""
    }
}
