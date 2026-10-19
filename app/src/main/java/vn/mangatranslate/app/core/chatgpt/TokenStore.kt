package vn.mangatranslate.app.core.chatgpt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Thông tin một lần đăng ký ChatGPT (theo tài liệu: lưu riêng theo client_id + danh tính đã xác thực). */
data class ChatGptAccount(
    val email: String,
    val issuer: String,
    val subject: String,
    val clientId: String,
    val hostId: String,
    val idToken: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresAt: Long,
    val scopes: String,
    val savedAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("email", email).put("issuer", issuer).put("subject", subject).put("client_id", clientId)
        .put("ext_agent_host_id", hostId).put("id_token", idToken).put("access_token", accessToken)
        .put("refresh_token", refreshToken).put("token_type", tokenType).put("expires_at", expiresAt)
        .put("scopes", scopes).put("saved_at", savedAt)

    companion object {
        fun fromJson(j: JSONObject) = ChatGptAccount(
            j.optString("email"), j.optString("issuer"), j.optString("subject"), j.getString("client_id"),
            j.getString("ext_agent_host_id"), j.optString("id_token"), j.getString("access_token"),
            j.optString("refresh_token"), j.optString("token_type", "Bearer"), j.optLong("expires_at"),
            j.optString("scopes"), j.optLong("saved_at")
        )
    }
}

/**
 * Lưu token MÃ HÓA bằng khóa AES-GCM trong Android Keystore (khóa không rời phần cứng/kho khóa của máy),
 * tệp nằm trong bộ nhớ riêng của ứng dụng (chỉ ứng dụng đọc được). Không ghi token vào log.
 * ext_agent_host_id và client_id đã cấp được giữ lại cả khi đăng xuất (tài liệu: dùng lại cho máy này).
 */
class TokenStore(context: Context) {
    private val dir = File(context.filesDir, "chatgpt").apply { mkdirs() }
    private val accountFile = File(dir, "account.bin")
    private val prefs = context.getSharedPreferences("chatgpt_reg", Context.MODE_PRIVATE)

    /** ID máy, tạo một lần trước lần đăng nhập đầu tiên. */
    fun hostId(): String = prefs.getString("host_id", null)
        ?: OAuthUtil.newHostId().also { prefs.edit().putString("host_id", it).apply() }

    fun issuedClientId(): String? = prefs.getString("client_id", null)

    fun saveIssuedClientId(id: String) = prefs.edit().putString("client_id", id).apply()

    fun load(): ChatGptAccount? = try {
        if (!accountFile.exists()) null else ChatGptAccount.fromJson(JSONObject(decrypt(accountFile.readText())))
    } catch (e: Exception) {
        null
    }

    fun save(a: ChatGptAccount) {
        val tmp = File(dir, "account.tmp")
        tmp.writeText(encrypt(a.toJson().toString()))
        if (!tmp.renameTo(accountFile)) {
            accountFile.delete()
            tmp.renameTo(accountFile)
        }
    }

    fun clearTokens() {
        accountFile.delete()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val enc = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b = Base64.getEncoder()
        return b.encodeToString(c.iv) + ":" + b.encodeToString(enc)
    }

    private fun decrypt(s: String): String {
        val (iv, data) = s.split(":").let { Base64.getDecoder().decode(it[0]) to Base64.getDecoder().decode(it[1]) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(data), Charsets.UTF_8)
    }

    private companion object {
        const val ALIAS = "manga_chatgpt_tokens"
    }
}
