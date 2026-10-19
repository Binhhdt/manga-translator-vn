package vn.mangatranslate.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import vn.mangatranslate.app.core.model.SourceLanguage
import vn.mangatranslate.app.ui.MainViewModel
import vn.mangatranslate.app.ui.ReaderMode
import vn.mangatranslate.app.ui.UiState
import vn.mangatranslate.app.ui.components.BusyOverlay
import vn.mangatranslate.app.ui.components.EditRegionDialog
import vn.mangatranslate.app.ui.components.MessageEffect
import vn.mangatranslate.app.ui.components.ZoomableMangaCanvas

@Composable
fun ReaderScreen(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbar, vm::consumeMessage)

    BackHandler {
        if (state.mode != ReaderMode.VIEW) vm.setMode(state.mode) else vm.closeReader()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.saveCurrent() else vm.showMessage("Cần quyền lưu ảnh để ghi vào Thư viện ảnh (Android 9 trở xuống).")
    }
    val onSave = {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else vm.saveCurrent()
    }

    val hasTranslation = state.translatedChunks.isNotEmpty()
    val chunks = if (state.showTranslated && hasTranslation) state.translatedChunks else state.originalChunks

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            ReaderTopBar(state, onBack = vm::closeReader, onSettings = vm::openSettings)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.loadingPage) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else if (chunks.isNotEmpty()) {
                    ZoomableMangaCanvas(
                        chunks = chunks,
                        imageWidth = state.imageWidth,
                        imageHeight = state.imageHeight,
                        regions = state.regions,
                        overflowIds = state.overflowIds,
                        showRegionBoxes = state.mode != ReaderMode.VIEW,
                        drawMode = state.mode == ReaderMode.ADD_REGION,
                        resetKey = state.pageIndex,
                        onTapImage = vm::onImageTap,
                        onRegionDrawn = vm::addManualRegion,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                StatusBanner(state, hasTranslation, Modifier.align(Alignment.TopCenter))
            }

            ReaderToolbar(
                state = state,
                hasTranslation = hasTranslation,
                onPrev = vm::prevPage,
                onNext = vm::nextPage,
                onTranslate = vm::translateCurrentPage,
                onToggleOriginal = vm::toggleShowTranslated,
                onEdit = { vm.setMode(ReaderMode.EDIT) },
                onAddRegion = { vm.setMode(ReaderMode.ADD_REGION) },
                onSave = onSave
            )
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp).navigationBarsPadding())

        state.selectedRegionId?.let { id ->
            state.regions.firstOrNull { it.id == id }?.let { region ->
                EditRegionDialog(
                    region = region,
                    overflow = id in state.overflowIds,
                    onDismiss = vm::dismissRegionDialog,
                    onSave = { src, tr -> vm.saveRegionEdit(id, src, tr) },
                    onRetranslate = { src -> vm.retranslateRegion(id, src) },
                    onToggleEnabled = { vm.toggleRegionEnabled(id) },
                    onDelete = { vm.deleteRegion(id) }
                )
            }
        }

        state.busy?.let { BusyOverlay(it, onCancel = vm::cancelBusy) }
    }
}

@Composable
private fun ReaderTopBar(state: UiState, onBack: () -> Unit, onSettings: () -> Unit) {
    val lang = when {
        state.settings.sourceLanguage != SourceLanguage.AUTO -> state.settings.sourceLanguage.label
        state.detectedLanguage != null -> "Tự động: ${state.detectedLanguage.label}"
        else -> "Tự động nhận dạng"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Về màn hình chính")
        }
        Column(Modifier.weight(1f)) {
            Text(
                state.currentPage?.name ?: "",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Trang ${state.pageIndex + 1}/${state.pages.size} · $lang",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Cài đặt")
        }
    }
}

@Composable
private fun StatusBanner(state: UiState, hasTranslation: Boolean, modifier: Modifier) {
    val text = when {
        state.mode == ReaderMode.ADD_REGION -> "Kéo một ngón tay để khoanh vùng chữ cần nhận dạng. (Thoát: nhấn lại “Thêm vùng”)"
        state.mode == ReaderMode.EDIT -> "Chạm vào khung để sửa. Xanh: tự động · Lục: khoanh tay · Đỏ: tràn khung"
        state.overflowIds.isNotEmpty() && state.showTranslated ->
            "⚠ ${state.overflowIds.size} vùng chữ không vừa khung — hãy rút ngắn câu trong “Sửa chữ”."
        hasTranslation && !state.showTranslated -> "Đang xem bản gốc"
        else -> null
    } ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Color.White,
        modifier = modifier
            .padding(8.dp)
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun ReaderToolbar(
    state: UiState,
    hasTranslation: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onTranslate: () -> Unit,
    onToggleOriginal: () -> Unit,
    onEdit: () -> Unit,
    onAddRegion: () -> Unit,
    onSave: () -> Unit
) {
    val ready = !state.loadingPage && state.imageWidth > 0
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolButton("◀", enabled = state.pageIndex > 0, onClick = onPrev)
        ToolButton("Dịch", enabled = ready, highlighted = true, onClick = onTranslate)
        ToolButton(
            if (state.showTranslated && hasTranslation) "Bản gốc" else "Bản dịch",
            enabled = hasTranslation,
            onClick = onToggleOriginal
        )
        ToolButton("Sửa chữ", enabled = state.regions.isNotEmpty(), highlighted = state.mode == ReaderMode.EDIT, onClick = onEdit)
        ToolButton("Thêm vùng", enabled = ready, highlighted = state.mode == ReaderMode.ADD_REGION, onClick = onAddRegion)
        ToolButton("Lưu ảnh", enabled = state.regions.isNotEmpty(), onClick = onSave)
        ToolButton("▶", enabled = state.pageIndex < state.pages.lastIndex, onClick = onNext)
    }
}

@Composable
private fun ToolButton(label: String, enabled: Boolean, highlighted: Boolean = false, onClick: () -> Unit) {
    val padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    if (highlighted) {
        FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = padding) { Text(label) }
    } else {
        TextButton(onClick = onClick, enabled = enabled, contentPadding = padding) { Text(label) }
    }
}
