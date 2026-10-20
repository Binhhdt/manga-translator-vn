package vn.mangatranslate.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import vn.mangatranslate.app.core.chatgpt.ChatGptClient
import vn.mangatranslate.app.core.chatgpt.PageStatus
import vn.mangatranslate.app.ui.AutoTranslateViewModel
import vn.mangatranslate.app.ui.MainViewModel

/** Chọn ảnh → Dịch: ChatGPT đọc & dịch, ứng dụng tự chèn tiếng Việt và lưu ảnh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoTranslateScreen(main: MainViewModel, vm: AutoTranslateViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    BackHandler { if (s.preview != null) vm.closePreview() else main.closeTool() }
    LaunchedEffect(Unit) { vm.refreshAccount(); if (s.models.isEmpty() && s.planOk) vm.loadModels() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) vm.pickPages(uris.map { u ->
            u to (runCatching {
                ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: u.lastPathSegment ?: "trang")
        })
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Dịch tự động (ChatGPT)") },
            navigationIcon = { IconButton(onClick = main::closeTool) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") } }
        )
    }) { pad ->
        // Xem ảnh đã lưu (đọc lại từ tệp).
        s.preview?.let { bmp ->
            Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.previewName ?: "", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    TextButton(onClick = vm::closePreview) { Text("Đóng") }
                }
                Image(bmp.asImageBitmap(), "Ảnh đã dịch", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            }
            return@Scaffold
        }
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val idle = !s.running
            // ---- Tài khoản & mô hình
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (s.email == null || !s.planOk) {
                        Text(if (s.email == null) "Chưa đăng nhập ChatGPT." else "Tài khoản thiếu quyền chatgpt.tokens.use.direct.",
                            color = MaterialTheme.colorScheme.error)
                        Button(onClick = main::openChatGpt) { Text("Đăng nhập ChatGPT") }
                    } else {
                        Text("Tài khoản: ${s.email}", style = MaterialTheme.typography.bodySmall)
                        if (s.models.isEmpty()) FilledTonalButton(onClick = vm::loadModels, enabled = idle) { Text("Lấy danh sách mô hình") }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.models.forEach { m -> FilterChip(selected = s.model == m.slug, onClick = { if (idle) vm.selectModel(m.slug) }, label = { Text(m.displayName) }) }
                        }
                        s.model?.let { Text("Mô hình: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            // ---- Chọn trang & điều khiển
            val run = s.run
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = idle) { Text("Chọn ảnh (tối đa 100)") }
                if (run != null) Text("${run.pages.size} trang", style = MaterialTheme.typography.bodySmall)
            }
            if (run != null) {
                val total = run.pages.size.coerceAtLeast(1)
                LinearProgressIndicator(progress = { (run.done + run.failed).toFloat() / total }, modifier = Modifier.fillMaxWidth())
                val needPages = run.pages.count { it.status == PageStatus.DONE && it.needsReview > 0 }
                Text("Hoàn thành ${run.done} (hoàn chỉnh ${run.done - needPages}, còn vùng cần xử lý $needPages) · Thất bại ${run.failed} · Chưa xử lý ${run.pending} / ${run.pages.size}", fontWeight = FontWeight.Bold)
                s.current?.let { Text("Đang xử lý: $it", style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.running) {
                        Button(onClick = vm::pause, enabled = !s.pauseRequested) { Text(if (s.pauseRequested) "Đang dừng…" else "Tạm dừng") }
                    } else {
                        Button(onClick = vm::start, enabled = run.pending > 0 && s.planOk && s.model != null && !s.limitReached) {
                            Text(if (run.done + run.failed == 0) "Dịch" else "Tiếp tục")
                        }
                        OutlinedButton(onClick = vm::retryFailed, enabled = run.failed > 0 && s.planOk && !s.limitReached) { Text("Dịch lại trang lỗi") }
                    }
                    OutlinedButton(onClick = {
                        val i = vm.folderIntent()
                        try {
                            if (i != null) ctx.startActivity(i) else throw ActivityNotFoundException()
                        } catch (e: Exception) {
                            ctx.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Text("Mở thư mục kết quả") }
                }
                if (!s.running && run.pages.any { it.jsonCached }) {
                    TextButton(onClick = vm::rerenderFromJson) { Text("Render lại từ JSON đã lưu (không tốn hạn mức)") }
                }
                s.location?.let { Text("Lưu tại: $it", style = MaterialTheme.typography.bodySmall) }
            }

            if (s.limitReached) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Đã chạm giới hạn sử dụng của gói ChatGPT", fontWeight = FontWeight.Bold)
                        Text("Đã dừng gửi trang mới và lưu tiến độ. Máy chủ không cho biết khi nào hạn mức khôi phục. Không chuyển sang API trả phí.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ChatGptClient.USAGE_URL))) }) { Text("Mở trang Usage") }
                            TextButton(onClick = vm::clearLimit) { Text("Hạn mức đã có lại") }
                        }
                    }
                }
            }

            // ---- Từng trang
            run?.pages?.forEach { p ->
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val label = if (p.status == PageStatus.DONE && p.needsReview > 0) "xong — còn ${p.needsReview} vùng cần xử lý" else p.status.label
                        Text("${p.index}. ${p.name} — $label", Modifier.weight(1f), fontWeight = FontWeight.Bold,
                            color = if (p.status == PageStatus.FAILED || (p.status == PageStatus.DONE && p.needsReview > 0)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        if (p.status == PageStatus.DONE && p.outImageUri != null) TextButton(onClick = { vm.showPreview(p) }) { Text("Xem") }
                        if (p.status == PageStatus.DONE && p.outDebugUri != null) TextButton(onClick = { vm.showPreview(p, debug = true) }) { Text("DEBUG") }
                    }
                    if (p.status == PageStatus.DONE) {
                        Text("JSON ${p.aiRegions} · đã chèn ${p.okCount} · chưa chèn (B) ${p.notInserted} · nghi còn chữ Nhật (C) ${p.suspectedJp} · " +
                            "nghi thiếu bản dịch (A) ${p.missing} · cố ý giữ (D) ${p.keptOnPurpose}", style = MaterialTheme.typography.bodySmall)
                        p.issues.forEach { Text("  $it", style = MaterialTheme.typography.bodySmall) }
                        if (p.missingBoxes.isNotEmpty() && !s.running) {
                            TextButton(onClick = { vm.supplementMissing(p) }, enabled = s.planOk && !s.limitReached) {
                                Text("Dịch bổ sung ${p.missingBoxes.size} vùng nghi thiếu (gọi ChatGPT cho riêng các vùng này)")
                            }
                        }
                        p.verified?.let { Text("Ảnh đã kiểm tra: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    p.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }

            if (s.log.isNotEmpty()) {
                HorizontalDivider()
                Text("Nhật ký", style = MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(s.log.takeLast(60).joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
