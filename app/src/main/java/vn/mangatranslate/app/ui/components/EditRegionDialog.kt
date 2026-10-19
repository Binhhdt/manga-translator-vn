package vn.mangatranslate.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.mangatranslate.app.core.model.TextRegion

private fun langLabel(code: String) = when (code) {
    "ja" -> "Nhật"
    "zh" -> "Trung"
    "ko" -> "Hàn"
    "en" -> "Anh"
    else -> "gốc"
}

/**
 * Chế độ kiểm tra OCR: chạm vào bong bóng → xem văn bản gốc nhận diện được và bản dịch.
 * - "Sửa chữ Nhật": sửa văn bản OCR. Khi lưu mà chưa tự sửa bản dịch, ứng dụng DỊCH LẠI từ chữ đã sửa.
 * - "Dịch lại": dịch ngay từ văn bản gốc đang hiển thị (đã sửa hoặc chưa).
 * - "Sửa tiếng Việt": sửa trực tiếp bản dịch.
 * - "Lưu bản dịch": lưu và vẽ lại ảnh.
 */
@Composable
fun EditRegionDialog(
    region: TextRegion,
    overflow: Boolean,
    onDismiss: () -> Unit,
    onSave: (source: String, translation: String, retranslate: Boolean) -> Unit,
    onRetranslate: (source: String) -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit
) {
    val lang = langLabel(region.sourceLang)
    var source by remember(region.id, region.sourceText) { mutableStateOf(region.sourceText) }
    var translation by remember(region.id, region.translatedText) { mutableStateOf(region.translatedText) }
    var editingSource by remember(region.id) { mutableStateOf(false) }
    var editingTranslation by remember(region.id) { mutableStateOf(false) }
    val sourceChanged = source.trim() != region.sourceText.trim()
    val translationChanged = translation.trim() != region.translatedText.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Vùng chữ #${region.id}" + if (region.manual) " (khoanh tay)" else "")
                if (region.ocrMethod.isNotEmpty()) {
                    Text(
                        "Cách đọc OCR: ${region.ocrMethod}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (region.needsReview || overflow) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0x33FF9800), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("⚠ Cần kiểm tra", color = Color(0xFFFFB74D), fontWeight = FontWeight.Bold)
                        if (overflow) Text("• Bản dịch không vừa khung thoại — hãy rút ngắn câu.", style = MaterialTheme.typography.bodySmall)
                        region.reviewNotes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                region.infoNotes.forEach {
                    Text("ℹ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!region.enabled) {
                    Text("Vùng này đang bị ẩn (không vẽ lên ảnh).", style = MaterialTheme.typography.bodySmall)
                }

                Text("Văn bản $lang gốc nhận diện được:", fontWeight = FontWeight.SemiBold)
                if (editingSource) {
                    OutlinedTextField(
                        value = source,
                        onValueChange = { source = it },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    SelectionContainer {
                        Text(source.ifBlank { "(trống — hãy bấm “Sửa chữ $lang” để nhập)" }, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editingSource = !editingSource }) {
                        Text(if (editingSource) "Xong" else "Sửa chữ $lang")
                    }
                    FilledTonalButton(onClick = { onRetranslate(source) }, enabled = source.isNotBlank()) {
                        Text("Dịch lại")
                    }
                }

                Text("Bản dịch tiếng Việt:", fontWeight = FontWeight.SemiBold)
                if (editingTranslation) {
                    OutlinedTextField(
                        value = translation,
                        onValueChange = { translation = it },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    SelectionContainer {
                        Text(translation.ifBlank { "(chưa có bản dịch)" }, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                OutlinedButton(onClick = { editingTranslation = !editingTranslation }) {
                    Text(if (editingTranslation) "Xong" else "Sửa tiếng Việt")
                }
                if (sourceChanged && !translationChanged) {
                    Text(
                        "Chữ gốc đã sửa — khi lưu, ứng dụng sẽ dịch lại từ nội dung mới.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onToggleEnabled) { Text(if (region.enabled) "Ẩn vùng" else "Hiện vùng") }
                    TextButton(onClick = onDelete) { Text("Xóa vùng", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(source, translation, sourceChanged && !translationChanged) }) {
                Text("Lưu bản dịch")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}
