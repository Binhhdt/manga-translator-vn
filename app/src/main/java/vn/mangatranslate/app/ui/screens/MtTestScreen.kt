package vn.mangatranslate.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.mangatranslate.app.core.model.SourceLanguage
import vn.mangatranslate.app.ui.MainViewModel
import vn.mangatranslate.app.ui.UiState
import vn.mangatranslate.app.ui.components.BusyOverlay

/** Câu mẫu chỉ để điền nhanh vào ô nhập — KHÔNG có bản dịch cố định nào. */
private val SAMPLES = listOf(
    "さわらないで", "黒崎", "神奈川 GK 負傷!!", "腕を骨折", "大丈夫かよ", "こんなブザマはさらさなかったのによ"
)

/**
 * Kiểm tra bộ dịch ĐỘC LẬP với OCR: gõ văn bản → Dịch → xem văn bản gửi vào bộ dịch và kết quả thô.
 * Nếu gõ đúng chữ Nhật mà vẫn dịch sai → lỗi ở bộ dịch; nếu gõ tay dịch đúng mà từ ảnh dịch sai → lỗi ở OCR/ghép chữ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MtTestScreen(state: UiState, vm: MainViewModel) {
    BackHandler { vm.closeTool() }
    var text by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("Kiểm tra bộ dịch") },
                    navigationIcon = {
                        IconButton(onClick = vm::closeTool) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Bộ dịch: ${vm.engineName}", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceLanguage.entries.filter { it != SourceLanguage.AUTO }.forEach { l ->
                        FilterChip(selected = state.mtTestLang == l, onClick = { vm.setMtTestLang(l) }, label = { Text(l.label) })
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Nhập văn bản gốc") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Câu mẫu (chỉ điền vào ô nhập):", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SAMPLES.forEach { s -> AssistChip(onClick = { text = s }, label = { Text(s) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Chuẩn hóa + bảng thuật ngữ trước khi dịch")
                        Text(
                            "Tắt để gửi NGUYÊN VĂN vào bộ dịch",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = state.mtTestNormalize, onCheckedChange = vm::setMtTestNormalize)
                }
                Button(onClick = { vm.runMtTest(text) }, enabled = text.isNotBlank() && state.busy == null) { Text("Dịch") }

                state.mtTestError?.let { Text("Lỗi: $it", color = MaterialTheme.colorScheme.error) }
                state.mtTestResult?.let { r ->
                    Card(Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Step("1. Văn bản nhập", r.source)
                                Step("2. Gửi vào bộ dịch", r.engineInput)
                                r.pivot?.let { Step("2b. Câu tiếng Anh trung gian", it) }
                                Step("3. Bộ dịch trả về (thô)", r.engineOutput)
                                Step("4. Sau bảng thuật ngữ", r.afterGlossary)
                                Text("Bộ dịch: ${r.engine}", style = MaterialTheme.typography.bodySmall)
                                if (r.lostNames.isNotEmpty()) Text("Tên bị mất: ${r.lostNames.joinToString()}", color = MaterialTheme.colorScheme.error)
                                r.mlkitOnly?.let {
                                    Text("So sánh — chỉ dùng ML Kit:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    r.mlkitOnlyPivot?.let { p -> Text("Anh: «$p»", style = MaterialTheme.typography.bodySmall) }
                                    Text("«$it»", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
        state.busy?.let { BusyOverlay(it, onCancel = vm::cancelBusy) }
    }
}

@Composable
private fun Step(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    Text("«$value»", style = MaterialTheme.typography.bodyLarge)
}
