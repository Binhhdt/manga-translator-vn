package vn.mangatranslate.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.mangatranslate.app.core.export.ExportFormat
import vn.mangatranslate.app.core.model.PronounStyle
import vn.mangatranslate.app.core.model.SourceLanguage
import vn.mangatranslate.app.core.model.TextColorMode
import vn.mangatranslate.app.core.translate.Glossary
import vn.mangatranslate.app.ui.MainViewModel
import vn.mangatranslate.app.ui.ModelState
import vn.mangatranslate.app.ui.UiState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel) {
    BackHandler { vm.closeSettings() }
    LaunchedEffect(Unit) { vm.refreshModelStatus() }
    val s = state.settings

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt") },
                navigationIcon = {
                    IconButton(onClick = vm::closeSettings) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Section("Ngôn ngữ nguồn")
            SourceLanguage.entries.forEach { lang ->
                RadioRow(lang.label, s.sourceLanguage == lang) { vm.updateSettings { it.copy(sourceLanguage = lang) } }
            }
            Hint("“Tự động” đoán theo loại chữ trên trang (Kana → Nhật, Hangul → Hàn, chỉ chữ Hán → Trung). Nếu đoán sai, hãy chọn tay.")

            Section("Xưng hô trong bản dịch")
            PronounStyle.entries.forEach { p ->
                RadioRow(p.label, s.pronounStyle == p) { vm.updateSettings { it.copy(pronounStyle = p) } }
            }
            Hint("Máy dịch offline thường trả về “tôi / bạn”. Ứng dụng thay đại từ theo quy tắc đơn giản, không hiểu ai nói với ai — có thể sai, hãy sửa tay câu cần thiết.")

            Section("Chữ tiếng Việt trên ảnh")
            TextColorMode.entries.forEach { m ->
                RadioRow(m.label, s.textColorMode == m) { vm.updateSettings { it.copy(textColorMode = m) } }
            }
            SwitchRow("Viền chữ", s.outline) { v -> vm.updateSettings { it.copy(outline = v) } }
            SwitchRow("Chữ đậm", s.bold) { v -> vm.updateSettings { it.copy(bold = v) } }
            SwitchRow("Không vẽ bản dịch đáng ngờ lên ảnh", s.hideUnverified) { v -> vm.updateSettings { it.copy(hideUnverified = v) } }
            Hint("Vùng “Cần kiểm tra” chỉ được vẽ sau khi bạn mở “Sửa chữ”, kiểm tra và bấm “Lưu bản dịch”. Giữ nguyên ảnh gốc thay vì vẽ câu vô nghĩa.")
            SwitchRow("Xóa chữ gốc trong bong bóng trắng (thử nghiệm)", s.cleanBubbles) { v -> vm.updateSettings { it.copy(cleanBubbles = v) } }
            Hint("Mặc định TẮT. Chỉ xóa nét chữ trong khung chữ thực tế của bong bóng khép kín; mask bất thường sẽ bị từ chối. Ảnh gốc không bao giờ bị sửa — bấm “Bản gốc” để so sánh.")
            SwitchRow("Chế độ DEBUG: hiện mask xóa chữ (đỏ)", s.debugMask) { v -> vm.updateSettings { it.copy(debugMask = v) } }
            Hint("Tô đỏ các điểm sẽ bị xóa và viền xanh vùng đặt chữ, không xóa thật. Dùng để kiểm tra trước khi bật xóa chữ.")
            SwitchRow("Lớp phủ nền sáng (vùng không xóa sạch được)", s.coverBackground) { v -> vm.updateSettings { it.copy(coverBackground = v) } }
            var opacity by remember(s.overlayOpacity) { mutableFloatStateOf(s.overlayOpacity.toFloat()) }
            Text("Độ che lớp phủ: ${opacity.toInt()}%", modifier = Modifier.padding(top = 4.dp))
            Slider(
                value = opacity,
                onValueChange = { opacity = it },
                onValueChangeFinished = { vm.updateSettings { it.copy(overlayOpacity = opacity.toInt()) } },
                valueRange = 0f..100f,
                enabled = s.coverBackground
            )

            var spacing by remember(s.lineSpacing) { mutableFloatStateOf(s.lineSpacing) }
            Text("Giãn dòng: ${String.format(Locale.US, "%.2f", spacing)}", modifier = Modifier.padding(top = 8.dp))
            Slider(
                value = spacing,
                onValueChange = { spacing = it },
                onValueChangeFinished = { vm.updateSettings { it.copy(lineSpacing = spacing) } },
                valueRange = 0.8f..1.6f
            )

            Section("Bảng thuật ngữ / tên riêng")
            var glossary by remember(s.glossaryText) { mutableStateOf(s.glossaryText) }
            OutlinedTextField(
                value = glossary,
                onValueChange = { glossary = it },
                minLines = 5,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${Glossary.parse(glossary).size} mục",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = {
                        vm.updateSettings { it.copy(glossaryText = glossary) }
                        vm.showMessage("Đã lưu bảng thuật ngữ. Nhấn “Dịch” lại để áp dụng cho trang đang mở.")
                    },
                    enabled = glossary != s.glossaryText
                ) { Text("Lưu thuật ngữ") }
            }
            Hint("Mỗi dòng “nguồn = đích”. Bên trái là chữ Nhật/Trung/Hàn → thay trước khi dịch để giữ tên riêng (黒崎 = Kurosaki, 神奈川 = Kanagawa). Bên trái là chữ Latin/Việt → sửa sau khi dịch (Blacklord = Kurosaki). Viết tắt bóng đá GK, FW, MF, DF, PK, FK, CK trong truyện Nhật được tự hiểu đúng.")

            Section("Định dạng khi lưu ảnh")
            ExportFormat.entries.forEach { f ->
                RadioRow(f.label, s.exportFormat == f) { vm.updateSettings { it.copy(exportFormat = f) } }
            }
            Hint("Ảnh lưu vào Thư viện ảnh › Pictures › MangaTranslateVN › <tên truyện>, tên tệp thêm “_vi”. Ảnh gốc không bao giờ bị ghi đè.")

            Section("Mô hình dịch offline")
            listOf(SourceLanguage.CHINESE, SourceLanguage.JAPANESE, SourceLanguage.KOREAN, SourceLanguage.ENGLISH).forEach { lang ->
                val st = state.modelStatus[lang] ?: ModelState.UNKNOWN
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${lang.label} → Việt")
                        Text(st.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (st == ModelState.NOT_DOWNLOADED || st == ModelState.ERROR) {
                        FilledTonalButton(onClick = { vm.downloadModel(lang) }) { Text("Tải") }
                    }
                }
            }
            Hint("Mỗi mô hình ~30 MB, tải một lần qua Internet; sau đó dịch hoàn toàn offline, không giới hạn. Mô hình nhận dạng chữ (OCR) đã có sẵn trong ứng dụng.")

            Section("Giới hạn cần biết")
            Hint(
                "• Dịch offline dịch từng câu riêng lẻ, không hiểu ngữ cảnh; Trung/Nhật/Hàn → Việt kém tự nhiên hơn Anh → Việt.\n" +
                    "• ML Kit đọc chữ dọc tiếng Nhật, chữ viết tay và hiệu ứng âm thanh kém. Dùng “Thêm vùng” và sửa tay khi cần.\n" +
                    "• Chữ gốc trong bong bóng trắng khép kín được xóa sạch; chữ trên nền tranh hoặc bong bóng hở được che bằng lớp phủ sáng. Xóa chữ trên nền tranh phức tạp (inpainting) có ở Giai đoạn 2.\n" +
                    "• Kết quả dịch chỉ giữ trong lúc ứng dụng mở; lưu dự án để sửa lại sau có ở Giai đoạn 3."
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(10.dp))
        Text(label)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
