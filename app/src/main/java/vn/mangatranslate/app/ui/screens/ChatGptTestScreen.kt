package vn.mangatranslate.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import vn.mangatranslate.app.core.chatgpt.ChatGptClient
import vn.mangatranslate.app.core.chatgpt.OAuthUtil
import vn.mangatranslate.app.ui.ChatGptTestViewModel
import vn.mangatranslate.app.ui.MainViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Thử kết nối ChatGPT (giai đoạn A–C). Mọi kết quả hiển thị là phản hồi thật từ máy chủ, kể cả lỗi nguyên văn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatGptTestScreen(main: MainViewModel, vm: ChatGptTestViewModel = viewModel()) {
    BackHandler { main.closeTool() }
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val name = runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            vm.pickImage(uri, name)
        }
    }
    val signedIn = s.account != null
    val planOk = OAuthUtil.hasPlanScope(s.account?.scopes)
    val idle = s.busy == null

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Thử ChatGPT (A–C)") },
            navigationIcon = { IconButton(onClick = main::closeTool) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") } }
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Dùng hạn mức gói ChatGPT của bạn qua \"Sign in with ChatGPT\" (bản xem trước của OpenAI cho ứng dụng mã nguồn mở). " +
                    "Không dùng API key, không lấy cookie. Có thể bị giới hạn sử dụng theo gói.",
                style = MaterialTheme.typography.bodySmall
            )

            // ---- A/B: đăng nhập
            Section("1. Tài khoản") {
                if (signedIn) {
                    val a = s.account!!
                    Text("Email: ${a.email.ifEmpty { "(không có)" }}")
                    Text("client_id: ${a.clientId}", style = MaterialTheme.typography.bodySmall)
                    Text("Quyền: ${a.scopes.ifEmpty { "(không có)" }}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (planOk) "✓ Có chatgpt.tokens.use.direct" else "✗ Thiếu chatgpt.tokens.use.direct — không được gọi AI",
                        color = if (planOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Access token hết hạn: ${DateFormat.getTimeInstance().format(Date(a.expiresAt))}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::refresh, enabled = idle) { Text("Làm mới token") }
                        OutlinedButton(onClick = vm::signOut, enabled = idle) { Text("Đăng xuất") }
                    }
                } else {
                    s.issuedClientId?.let { Text("client_id đã cấp cho máy này: $it", style = MaterialTheme.typography.bodySmall) }
                }
                Button(onClick = vm::signIn, enabled = idle) { Text(if (signedIn) "Đăng nhập lại với ChatGPT" else "Continue with ChatGPT") }
            }

            // ---- B: mô hình + câu chữ
            Section("2. Mô hình") {
                FilledTonalButton(onClick = vm::listModels, enabled = idle && signedIn && planOk) { Text("Lấy danh sách mô hình") }
                if (s.models.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.models.forEach { m ->
                            FilterChip(selected = s.selectedModel == m.slug, onClick = { vm.selectModel(m.slug) }, label = { Text(m.displayName) })
                        }
                    }
                    Text("Đang chọn: ${s.selectedModel}", style = MaterialTheme.typography.bodySmall)
                    Text("Cần chọn mô hình nhận được ảnh; nếu mô hình không nhận ảnh, máy chủ sẽ báo lỗi và lỗi đó hiện ở nhật ký.", style = MaterialTheme.typography.bodySmall)
                } else if (s.modelsRaw != null) {
                    Text("Không đọc được danh sách. Phản hồi thô:", color = MaterialTheme.colorScheme.error)
                    Mono(s.modelsRaw!!)
                }
                FilledTonalButton(onClick = vm::sendHello, enabled = idle && s.selectedModel != null && !s.limitReached) { Text("Gửi câu \"Xin chào\"") }
            }

            // ---- C: ảnh
            Section("3. Ảnh manga") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { picker.launch("image/*") }, enabled = idle) { Text("Chọn ảnh") }
                    Text(s.imageName ?: "(chưa chọn)", style = MaterialTheme.typography.bodySmall)
                }
                s.imageInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = vm::sendImage, enabled = idle && s.selectedModel != null && s.imageName != null && !s.limitReached) {
                    Text("Gửi ảnh để đọc và dịch")
                }
            }

            if (s.limitReached) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Đã chạm giới hạn sử dụng của gói ChatGPT", fontWeight = FontWeight.Bold)
                        Text("Ứng dụng đã dừng gửi yêu cầu. Máy chủ không cho biết khi nào hạn mức khôi phục. Không chuyển sang API trả phí.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ChatGptClient.USAGE_URL))) }) { Text("Mở trang Usage") }
                            TextButton(onClick = vm::clearLimit) { Text("Thử lại sau giới hạn") }
                        }
                    }
                }
            }

            s.busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text(it, Modifier.weight(1f))
                    TextButton(onClick = vm::cancel) { Text("Hủy") }
                }
            }

            // ---- Kết quả
            if (s.output.isNotEmpty() || s.reply != null) {
                Section("Phản hồi${s.reply?.let { " — ${it.status}" } ?: " (đang nhận…)"}") {
                    Mono(s.output.ifEmpty { "(trống)" })
                }
            }
            s.parsed?.let { p ->
                Section("Kiểm tra JSON: ${p.regions.size} vùng") {
                    p.problems.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text("Vị trí là ƯỚC LƯỢNG của AI (tỉ lệ 0..1), chưa dùng để vẽ.", style = MaterialTheme.typography.bodySmall)
                    p.regions.forEach { r ->
                        HorizontalDivider()
                        Text("#${r.id} ${r.type}${if (r.uncertain) "  ⚠ cần kiểm tra" else ""}", fontWeight = FontWeight.Bold)
                        Text("JA: ${r.ja}")
                        Text("VI: ${r.vi}")
                        Text("bbox: ${r.bbox?.joinToString { "%.3f".format(it) } ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Section("Nhật ký (không chứa token)") {
                Mono(if (s.log.isEmpty()) "(trống)" else s.log.joinToString("\n"))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Mono(text: String) {
    SelectionContainer {
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}
