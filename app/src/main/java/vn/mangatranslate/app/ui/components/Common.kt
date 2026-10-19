package vn.mangatranslate.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import vn.mangatranslate.app.ui.UiMessage

/** Hiện snackbar cho mỗi thông báo mới, sau đó báo ViewModel đã hiển thị. */
@Composable
fun MessageEffect(message: UiMessage?, host: SnackbarHostState, onShown: (Long) -> Unit) {
    LaunchedEffect(message?.id) {
        if (message != null) {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(message.text, withDismissAction = true)
            onShown(message.id)
        }
    }
}

/** Lớp phủ khi đang xử lý: chặn thao tác, hiện tiến trình, cho phép hủy. */
@Composable
fun BusyOverlay(text: String, onCancel: (() -> Unit)?) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        Card(Modifier.widthIn(max = 320.dp).padding(24.dp)) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator()
                Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                if (onCancel != null) TextButton(onClick = onCancel) { Text("Hủy") }
            }
        }
    }
}
