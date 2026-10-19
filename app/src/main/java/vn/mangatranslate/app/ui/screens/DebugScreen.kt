package vn.mangatranslate.app.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.mangatranslate.app.core.model.DiagReport
import vn.mangatranslate.app.core.model.TextRegion
import vn.mangatranslate.app.ui.MainViewModel
import vn.mangatranslate.app.ui.UiState
import vn.mangatranslate.app.ui.components.MessageEffect

/**
 * Màn hình DEBUG: với từng vùng chữ hiện ảnh cắt đưa vào OCR, văn bản ML Kit trả về NGUYÊN VẸN,
 * thứ tự dòng/cột, văn bản gửi vào bộ dịch, kết quả thô của bộ dịch, văn bản cuối và tọa độ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(state: UiState, vm: MainViewModel) {
    BackHandler { vm.closeTool() }
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbar, vm::consumeMessage)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("DEBUG vùng chữ") },
                navigationIcon = {
                    IconButton(onClick = vm::closeTool) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
                },
                actions = {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(vm.debugReport()))
                            vm.showMessage("Đã sao chép báo cáo — có thể dán gửi để phân tích.")
                        },
                        enabled = state.regions.isNotEmpty()
                    ) { Text("Sao chép báo cáo") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Trang: ${state.currentPage?.name ?: "-"} · ảnh ${state.imageWidth}x${state.imageHeight} · bộ dịch: ${vm.engineName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.regions.isEmpty()) {
                    Text("Chưa có vùng chữ. Hãy mở một trang và nhấn “Dịch”.", modifier = Modifier.padding(top = 12.dp))
                }
            }
            items(state.regions, key = { it.id }) { r -> RegionCard(r, vm.drawnStatus(r)) }
        }
    }
}

@Composable
private fun RegionCard(r: TextRegion, drawn: String) {
    val t = r.trace
    val image = remember(t?.cropPng) {
        t?.cropPng?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Vùng #${r.id} · ${t?.kind ?: "?"}" + if (r.needsReview) " · ⚠ cần kiểm tra" else "",
                fontWeight = FontWeight.Bold
            )
            if (image != null) {
                Text("1. Ảnh cắt đưa vào OCR:", style = MaterialTheme.typography.labelMedium)
                Image(
                    bitmap = image,
                    contentDescription = "Ảnh vùng #${r.id}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).background(Color.White)
                )
            }
            SelectionContainer {
                Text(
                    DiagReport.region(r, drawn),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
