package vn.mangatranslate.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.mangatranslate.app.core.model.TextRegion

/** Chạm vào vùng chữ → sửa chữ gốc (OCR) và bản dịch → Lưu → ảnh vẽ lại ngay. */
@Composable
fun EditRegionDialog(
    region: TextRegion,
    overflow: Boolean,
    onDismiss: () -> Unit,
    onSave: (source: String, translation: String) -> Unit,
    onRetranslate: (source: String) -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit
) {
    var source by remember(region.id, region.sourceText) { mutableStateOf(region.sourceText) }
    var translation by remember(region.id, region.translatedText) { mutableStateOf(region.translatedText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vùng chữ #${region.id}" + if (region.manual) " (khoanh tay)" else "") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (overflow) {
                    Text(
                        "⚠ Bản dịch không vừa khung thoại. Hãy rút ngắn câu.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!region.enabled) {
                    Text("Vùng này đang bị ẩn (không vẽ lên ảnh).", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text("Chữ gốc (OCR) — sửa nếu nhận sai") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = translation,
                    onValueChange = { translation = it },
                    label = { Text("Bản dịch tiếng Việt") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onRetranslate(source) }, enabled = source.isNotBlank()) { Text("Dịch lại") }
                    TextButton(onClick = onToggleEnabled) { Text(if (region.enabled) "Ẩn vùng" else "Hiện vùng") }
                    TextButton(onClick = onDelete) { Text("Xóa", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(source, translation) }) { Text("Lưu") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}
