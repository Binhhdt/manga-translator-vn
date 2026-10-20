package vn.mangatranslate.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import vn.mangatranslate.app.core.image.PageSource
import vn.mangatranslate.app.core.model.SourceLanguage
import vn.mangatranslate.app.core.model.TextRegion
import vn.mangatranslate.app.data.AppSettings

enum class Screen { HOME, READER, SETTINGS, DEBUG, MT_TEST, CHATGPT, AUTO }

enum class ReaderMode { VIEW, EDIT, ADD_REGION }

enum class ModelState(val label: String) {
    UNKNOWN("Đang kiểm tra…"),
    NOT_DOWNLOADED("Chưa tải"),
    DOWNLOADING("Đang tải…"),
    READY("Đã có — dùng offline"),
    ERROR("Lỗi tải")
}

/** Thông báo một lần (snackbar). [id] khác nhau để hai thông báo giống chữ vẫn hiện lại. */
data class UiMessage(val text: String, val id: Long = System.nanoTime())

data class UiState(
    val screen: Screen = Screen.HOME,
    val screenBeforeSettings: Screen = Screen.HOME,
    /** Màn hình trước khi mở DEBUG / kiểm tra bộ dịch. */
    val screenBeforeTool: Screen = Screen.HOME,
    /** Màn hình kiểm tra bộ dịch nhập tay. */
    val mtTestLang: SourceLanguage = SourceLanguage.JAPANESE,
    val mtTestNormalize: Boolean = true,
    val mtTestResult: vn.mangatranslate.app.core.model.TranslationTrace? = null,
    val mtTestError: String? = null,
    val title: String = "",
    val pages: List<PageSource> = emptyList(),
    val pageIndex: Int = 0,
    val loadingPage: Boolean = false,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val originalChunks: List<ImageBitmap> = emptyList(),
    val translatedChunks: List<ImageBitmap> = emptyList(),
    val regions: List<TextRegion> = emptyList(),
    val overflowIds: Set<Int> = emptySet(),
    /** Vùng có bản dịch đáng ngờ chưa xác nhận → chưa vẽ lên ảnh. */
    val hiddenIds: Set<Int> = emptySet(),
    /** Vùng bị từ chối xóa chữ vì mask bất thường (lý do). */
    val maskNotes: Map<Int, String> = emptyMap(),
    val detectedLanguage: SourceLanguage? = null,
    val showTranslated: Boolean = true,
    val mode: ReaderMode = ReaderMode.VIEW,
    val selectedRegionId: Int? = null,
    val busy: String? = null,
    val message: UiMessage? = null,
    val settings: AppSettings = AppSettings(),
    val modelStatus: Map<SourceLanguage, ModelState> = emptyMap()
) {
    val currentPage: PageSource? get() = pages.getOrNull(pageIndex)
}
