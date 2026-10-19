package vn.mangatranslate.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.mangatranslate.app.ui.MainViewModel
import vn.mangatranslate.app.ui.UiState
import vn.mangatranslate.app.ui.components.BusyOverlay
import vn.mangatranslate.app.ui.components.MessageEffect

@Composable
fun HomeScreen(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbar, vm::consumeMessage)

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.openImages(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            vm.openFolder(uri)
        }
    }
    val pickCbz = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openCbz(uri)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Dịch Truyện VN",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Dịch truyện Trung · Nhật · Hàn · Anh sang tiếng Việt ngay trên điện thoại. Miễn phí, không tài khoản, không quảng cáo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))

            if (state.pages.isNotEmpty()) {
                Button(onClick = vm::resumeReader, modifier = Modifier.fillMaxWidth()) {
                    Text("Tiếp tục đọc: ${state.title} (${state.pages.size} trang)")
                }
            }
            HomeButton("1. Mở ảnh", "Chọn một hoặc nhiều ảnh JPG, PNG, WebP") { pickImages.launch(arrayOf("image/*")) }
            HomeButton("2. Mở thư mục truyện", "Chọn thư mục chứa các trang, tự sắp xếp 1, 2, 3… 10") { pickFolder.launch(null) }
            HomeButton("3. Mở file CBZ", "Đọc ảnh bên trong tệp .cbz / .zip") { pickCbz.launch(arrayOf("*/*")) }
            HomeButton(
                "4. Dịch trang hiện tại",
                if (state.pages.isEmpty()) "Hãy mở truyện trước" else "Trang ${state.pageIndex + 1}/${state.pages.size}",
                enabled = state.pages.isNotEmpty()
            ) {
                vm.resumeReader()
                vm.translateCurrentPage()
            }
            HomeButton("5. Dịch toàn bộ chương", "Sẽ có ở Giai đoạn 3 (dịch hàng loạt, tạm dừng/tiếp tục)", enabled = false) {}
            HomeButton(
                "6. Xem truyện đã dịch",
                "Sẽ có ở Giai đoạn 3. Hiện ảnh đã lưu nằm trong Thư viện ảnh › Pictures › MangaTranslateVN",
                enabled = false
            ) {}
            HomeButton("7. Cài đặt", "Ngôn ngữ nguồn, xưng hô, kiểu chữ, tải mô hình dịch") { vm.openSettings() }
            HomeButton("Kiểm tra bộ dịch", "Gõ chữ Nhật/Trung/Hàn/Anh để thử bộ dịch, không qua OCR") { vm.openMtTest() }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text(
                    "Lưu ý: nhận dạng chữ và dịch đều chạy offline bằng Google ML Kit. Máy dịch offline dịch từng câu, " +
                        "không hiểu ngữ cảnh truyện nên câu chữ có thể cứng hoặc sai xưng hô — bạn có thể sửa từng câu. " +
                        "Lần đầu dịch mỗi ngôn ngữ cần Internet để tải mô hình (~30 MB).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        state.busy?.let { BusyOverlay(it, onCancel = vm::cancelBusy) }
    }
}

@Composable
private fun HomeButton(title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
