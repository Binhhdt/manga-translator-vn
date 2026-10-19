package vn.mangatranslate.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.mangatranslate.app.core.export.ImageExporter
import vn.mangatranslate.app.core.image.ImageLoader
import vn.mangatranslate.app.core.image.PageSource
import vn.mangatranslate.app.core.image.PreviewChunker
import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.model.SourceLanguage
import vn.mangatranslate.app.core.model.TextRegion
import vn.mangatranslate.app.core.ocr.OCRProcessor
import vn.mangatranslate.app.core.ocr.TextRegionDetector
import vn.mangatranslate.app.core.pipeline.PageTranslator
import vn.mangatranslate.app.core.pipeline.UserFacingException
import vn.mangatranslate.app.core.render.VietnameseTextRenderer
import vn.mangatranslate.app.core.translate.TranslationEngine
import vn.mangatranslate.app.data.AppSettings
import vn.mangatranslate.app.data.SettingsStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * ViewModel duy nhất của Giai đoạn 1. Giữ ảnh gốc ĐỘ PHÂN GIẢI ĐẦY ĐỦ của trang đang xem (chỉ một trang),
 * bản xem trước đã thu nhỏ, và các vùng thoại. Mọi xử lý nặng chạy ngoài luồng giao diện.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val imageLoader = ImageLoader(application)
    private val ocr = OCRProcessor()
    private val translationEngine = TranslationEngine(application)
    private val detector = TextRegionDetector()
    private val pageTranslator = PageTranslator(ocr, detector, translationEngine)
    private val renderer = VietnameseTextRenderer()
    private val exporter = ImageExporter(application)
    private val settingsStore = SettingsStore(application)

    private val _state = MutableStateFlow(UiState(settings = settingsStore.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        applyEngineMode(_state.value.settings)
    }

    private fun applyEngineMode(s: AppSettings) {
        translationEngine.jaMode = if (s.jaUseOpus) TranslationEngine.JaMode.OPUS_PIVOT else TranslationEngine.JaMode.MLKIT_ONLY
    }

    val opusAvailable: Boolean get() = translationEngine.opusAvailable

    /** Ảnh gốc của trang hiện tại (độ phân giải gốc). Không bao giờ bị vẽ đè. */
    private var original: Bitmap? = null

    /** Kết quả dịch theo trang (trong bộ nhớ). Giai đoạn 3 sẽ lưu vào Room để mở lại được. */
    private val pageCache = HashMap<String, CachedPage>()

    private data class CachedPage(val language: SourceLanguage, val regions: List<TextRegion>)

    private var pageJob: Job? = null
    private var workJob: Job? = null
    private var renderJob: Job? = null

    // ---------------------------------------------------------------- mở truyện

    fun openImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        openPages { "AnhLe" to imageLoader.pagesFromUris(uris) }
    }

    fun openFolder(treeUri: Uri) {
        openPages { imageLoader.treeName(treeUri) to imageLoader.pagesFromTree(treeUri) }
    }

    fun openCbz(uri: Uri) {
        openPages {
            val name = imageLoader.displayName(uri)
            if (!name.lowercase().let { it.endsWith(".cbz") || it.endsWith(".zip") }) {
                throw UserFacingException("Hãy chọn tệp .cbz hoặc .zip")
            }
            name.substringBeforeLast('.') to imageLoader.pagesFromCbz(uri)
        }
    }

    private fun openPages(loader: suspend () -> Pair<String, List<PageSource>>) {
        cancelAllJobs()
        workJob = viewModelScope.launch {
            _state.update { it.copy(busy = "Đang mở truyện…") }
            try {
                val (title, pages) = withContext(Dispatchers.IO) { loader() }
                if (pages.isEmpty()) {
                    _state.update { it.copy(busy = null, message = UiMessage("Không tìm thấy ảnh JPG/PNG/WebP nào.")) }
                    return@launch
                }
                pageCache.clear()
                original = null
                _state.update {
                    it.copy(
                        busy = null, title = title, pages = pages, pageIndex = 0,
                        detectedLanguage = null, screen = Screen.READER
                    )
                }
                loadPage(0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showError(e, "Không mở được")
            }
        }
    }

    // ---------------------------------------------------------------- điều hướng trang

    fun nextPage() = goToPage(_state.value.pageIndex + 1)
    fun prevPage() = goToPage(_state.value.pageIndex - 1)

    fun goToPage(index: Int) {
        val s = _state.value
        if (s.busy != null || index !in s.pages.indices) return
        loadPage(index)
    }

    private fun loadPage(index: Int) {
        val page = _state.value.pages.getOrNull(index) ?: return
        pageJob?.cancel()
        renderJob?.cancel()
        original = null
        _state.update {
            it.copy(
                pageIndex = index, loadingPage = true, imageWidth = 0, imageHeight = 0,
                originalChunks = emptyList(), translatedChunks = emptyList(), regions = emptyList(),
                overflowIds = emptySet(), hiddenIds = emptySet(), maskNotes = emptyMap(),
                selectedRegionId = null, mode = ReaderMode.VIEW
            )
        }
        pageJob = viewModelScope.launch {
            try {
                val loaded = imageLoader.loadFull(page.uri)
                val chunks = withContext(Dispatchers.Default) { PreviewChunker.build(loaded.bitmap) }
                original = loaded.bitmap
                val cached = pageCache[page.uri.toString()]
                _state.update {
                    it.copy(
                        loadingPage = false,
                        imageWidth = loaded.bitmap.width,
                        imageHeight = loaded.bitmap.height,
                        originalChunks = chunks,
                        regions = cached?.regions ?: emptyList(),
                        detectedLanguage = cached?.language ?: it.detectedLanguage,
                        message = if (loaded.sampleSize > 1)
                            UiMessage("Ảnh rất lớn (${loaded.originalWidth}×${loaded.originalHeight}) nên đã giảm độ phân giải ${loaded.sampleSize} lần để tránh tràn RAM.")
                        else it.message
                    )
                }
                if (!cached?.regions.isNullOrEmpty()) rerender()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(loadingPage = false) }
                showError(e, "Không đọc được ảnh ${page.name}")
            }
        }
    }

    // ---------------------------------------------------------------- dịch

    fun translateCurrentPage() {
        val bmp = original
        val s = _state.value
        if (bmp == null) {
            if (s.loadingPage) showMessage("Ảnh đang được mở, hãy thử lại sau giây lát.")
            return
        }
        if (s.busy != null) return
        val page = s.currentPage ?: return
        val requested = if (s.settings.sourceLanguage == SourceLanguage.AUTO) {
            s.detectedLanguage ?: SourceLanguage.AUTO // các trang sau dùng lại ngôn ngữ đã đoán
        } else s.settings.sourceLanguage

        workJob = viewModelScope.launch {
            _state.update { it.copy(busy = "Đang chuẩn bị…", mode = ReaderMode.VIEW, selectedRegionId = null) }
            try {
                val result = withContext(Dispatchers.Default) {
                    pageTranslator.translatePage(bmp, requested, s.settings.pronounStyle, s.settings.glossary()) { stage ->
                        _state.update { it.copy(busy = stage) }
                    }
                }
                pageCache[page.uri.toString()] = CachedPage(result.language, result.regions)
                val review = result.regions.count { it.needsReview }
                val msg = if (result.regions.isEmpty())
                    "Không tìm thấy chữ (${result.language.label}). Thử chọn đúng ngôn ngữ trong Cài đặt hoặc dùng “Thêm vùng”."
                else "Đã dịch ${result.regions.size} vùng chữ (${result.language.label} → Việt)" +
                    (if (review > 0) ", $review vùng cần kiểm tra (khung cam trong “Sửa chữ”)." else ".") +
                    (if (result.skipped.isNotEmpty()) " Bỏ qua ${result.skipped.size} vùng lỗi; có thể dùng “Thêm vùng” để đọc lại." else "")
                _state.update {
                    it.copy(
                        busy = null, regions = result.regions, detectedLanguage = result.language,
                        showTranslated = true, translatedChunks = emptyList(), message = UiMessage(msg)
                    )
                }
                rerender()
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = null) }
                throw e
            } catch (e: Throwable) {
                showError(e, "Dịch thất bại")
            }
        }
    }

    /** Vẽ lại bản dịch ở độ phân giải gốc rồi tạo bản xem trước. Gọi sau mỗi lần sửa. */
    private fun rerender() {
        val bmp = original ?: return
        val s = _state.value
        val regions = s.regions
        val style = s.settings.toRenderStyle()
        renderJob?.cancel()
        if (regions.isEmpty()) {
            _state.update { it.copy(translatedChunks = emptyList(), overflowIds = emptySet(), hiddenIds = emptySet(), maskNotes = emptyMap()) }
            return
        }
        renderJob = viewModelScope.launch {
            try {
                val (chunks, out) = withContext(Dispatchers.Default) {
                    val out = renderer.render(bmp, regions, style)
                    PreviewChunker.build(out.bitmap, recycleSource = true) to out
                }
                val warning = when {
                    out.changedOutside > 0 ->
                        "Cảnh báo an toàn: phát hiện ${out.changedOutside} điểm ảnh bị đổi ngoài vùng chữ. Hãy dùng “Bản gốc” để so sánh và báo lỗi."
                    out.failedIds.isNotEmpty() -> "Bỏ qua ${out.failedIds.size} vùng bị lỗi khi vẽ."
                    else -> null
                }
                _state.update {
                    it.copy(
                        translatedChunks = chunks,
                        overflowIds = out.overflowIds,
                        hiddenIds = out.hiddenIds,
                        maskNotes = out.maskRejected,
                        message = warning?.let { w -> UiMessage(w) } ?: it.message
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showError(e, "Không vẽ được bản dịch")
            }
        }
    }

    fun toggleShowTranslated() = _state.update { it.copy(showTranslated = !it.showTranslated) }

    fun cancelBusy() {
        workJob?.cancel()
        _state.update { it.copy(busy = null) }
    }

    // ---------------------------------------------------------------- chỉnh sửa vùng chữ

    fun setMode(mode: ReaderMode) {
        _state.update {
            val newMode = if (it.mode == mode) ReaderMode.VIEW else mode
            it.copy(
                mode = newMode,
                selectedRegionId = null,
                // Khi sửa, hiện bản dịch để thấy kết quả ngay.
                showTranslated = if (newMode != ReaderMode.VIEW) true else it.showTranslated
            )
        }
    }

    fun onImageTap(x: Float, y: Float) {
        val s = _state.value
        if (s.mode != ReaderMode.EDIT) return
        val hit = s.regions
            .filter { r -> hitBox(r).contains(x, y) }
            .minByOrNull { r -> hitBox(r).area }
        if (hit == null) showMessage("Chạm vào một khung màu để sửa vùng chữ đó.")
        _state.update { it.copy(selectedRegionId = hit?.id) }
    }

    private fun hitBox(r: TextRegion): BoxF =
        (r.bubble?.box ?: r.layoutBox ?: r.box).union(r.box.expand(r.lineHeight * 0.4f))

    fun dismissRegionDialog() = _state.update { it.copy(selectedRegionId = null) }

    /**
     * Lưu chỉnh sửa. Nếu người dùng đã sửa chữ gốc mà không tự sửa bản dịch, dịch lại từ chữ gốc MỚI
     * (không dùng kết quả OCR cũ).
     */
    fun saveRegionEdit(id: Int, sourceText: String, translation: String, retranslate: Boolean) {
        if (retranslate) {
            _state.update { it.copy(selectedRegionId = null) }
            retranslateRegion(id, sourceText)
            return
        }
        val glossary = _state.value.settings.glossary()
        updateRegions { r ->
            if (r.id != id) r
            else {
                val tr = translation.trim()
                val (rev, inf) = pageTranslator.review(sourceText, tr, r.sourceLang, glossary)
                r.copy(
                    sourceText = sourceText,
                    translatedText = tr,
                    userEdited = r.userEdited || tr != r.translatedText,
                    reviewNotes = rev,
                    infoNotes = inf,
                    confirmed = true // người dùng đã xem và bấm Lưu → được vẽ lên ảnh
                )
            }
        }
        _state.update { it.copy(selectedRegionId = null) }
    }

    fun toggleRegionEnabled(id: Int) = updateRegions { r -> if (r.id == id) r.copy(enabled = !r.enabled) else r }

    fun deleteRegion(id: Int) {
        _state.update { it.copy(selectedRegionId = null, regions = it.regions.filterNot { r -> r.id == id }) }
        persistCurrentPage()
        rerender()
    }

    fun retranslateRegion(id: Int, sourceText: String) {
        val s = _state.value
        if (s.busy != null) return
        val regionLang = s.regions.firstOrNull { it.id == id }?.sourceLang
            ?.let { code -> SourceLanguage.entries.firstOrNull { it.code == code } }
        val lang = regionLang ?: s.detectedLanguage ?: s.settings.sourceLanguage.takeIf { it != SourceLanguage.AUTO }
        if (lang == null) {
            showMessage("Chưa biết ngôn ngữ nguồn — hãy chọn trong Cài đặt.")
            return
        }
        val glossary = s.settings.glossary()
        workJob = viewModelScope.launch {
            _state.update { it.copy(busy = "Đang dịch lại…") }
            try {
                val tr = withContext(Dispatchers.Default) {
                    pageTranslator.translateText(sourceText, lang, glossary, { stage -> _state.update { it.copy(busy = stage) } })
                }
                val raw = tr.afterGlossary
                val pronoun = _state.value.settings.pronounStyle
                val translated = pageTranslator.finalize(raw, pronoun)
                val (rev, inf) = pageTranslator.review(sourceText, translated, lang.code ?: "", glossary)
                _state.update { it.copy(busy = null) }
                updateRegions { r ->
                    if (r.id != id) r
                    else r.copy(
                        sourceText = sourceText, rawTranslation = raw,
                        translatedText = translated, userEdited = false,
                        reviewNotes = rev, infoNotes = inf, sourceLang = lang.code ?: r.sourceLang,
                        confirmed = false,
                        trace = r.trace?.copy(mtInput = tr.engineInput, mtRaw = tr.engineOutput, engine = tr.engine, mtPivot = tr.pivot)
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = null) }
                throw e
            } catch (e: Throwable) {
                showError(e, "Dịch lại thất bại")
            }
        }
    }

    /** Người dùng khoanh vùng bằng tay → OCR riêng vùng đó (phóng to) → dịch → mở hộp sửa để kiểm tra. */
    fun addManualRegion(box: BoxF) {
        val bmp = original ?: return
        val s = _state.value
        if (s.busy != null) return
        if (box.width < 12f || box.height < 12f) {
            showMessage("Vùng chọn quá nhỏ, hãy kéo rộng hơn.")
            return
        }
        val requested = if (s.settings.sourceLanguage == SourceLanguage.AUTO) {
            s.detectedLanguage ?: SourceLanguage.AUTO
        } else s.settings.sourceLanguage
        val nextId = (s.regions.maxOfOrNull { it.id } ?: 0) + 1

        workJob = viewModelScope.launch {
            _state.update { it.copy(busy = "Đang xử lý vùng chọn…") }
            try {
                val result = withContext(Dispatchers.Default) {
                    pageTranslator.recognizeManualRegion(bmp, box, requested, s.settings.pronounStyle, s.settings.glossary(), nextId) { stage ->
                        _state.update { it.copy(busy = stage) }
                    }
                }
                _state.update {
                    it.copy(
                        busy = null,
                        regions = it.regions + result.region,
                        detectedLanguage = it.detectedLanguage ?: result.language,
                        mode = ReaderMode.EDIT,
                        selectedRegionId = result.region.id,
                        showTranslated = true,
                        message = if (result.region.sourceText.isBlank())
                            UiMessage("Không đọc được chữ trong vùng này — bạn có thể tự gõ nội dung.") else it.message
                    )
                }
                persistCurrentPage()
                rerender()
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = null) }
                throw e
            } catch (e: Throwable) {
                showError(e, "Không xử lý được vùng chọn")
            }
        }
    }

    private fun updateRegions(transform: (TextRegion) -> TextRegion) {
        _state.update { it.copy(regions = it.regions.map(transform)) }
        persistCurrentPage()
        rerender()
    }

    private fun persistCurrentPage() {
        val s = _state.value
        val page = s.currentPage ?: return
        val lang = s.detectedLanguage ?: s.settings.sourceLanguage
        pageCache[page.uri.toString()] = CachedPage(lang, s.regions)
    }

    // ---------------------------------------------------------------- lưu ảnh

    fun saveCurrent() {
        val bmp = original ?: return
        val s = _state.value
        if (s.busy != null) return
        if (s.regions.isEmpty()) {
            showMessage("Trang này chưa được dịch. Nhấn “Dịch” trước.")
            return
        }
        val page = s.currentPage ?: return
        workJob = viewModelScope.launch {
            _state.update { it.copy(busy = "Đang lưu ảnh ở độ phân giải gốc…") }
            try {
                val (path, overflowCount) = withContext(Dispatchers.Default) {
                    val out = renderer.render(bmp, s.regions, s.settings.toRenderStyle())
                    try {
                        exporter.saveToGallery(
                            out.bitmap,
                            page.name.substringBeforeLast('.') + "_vi",
                            s.settings.exportFormat,
                            s.title
                        ) to out.overflowIds.size
                    } finally {
                        out.bitmap.recycle()
                    }
                }
                val warn = if (overflowCount > 0) " Lưu ý: $overflowCount vùng chữ bị tràn khung." else ""
                _state.update { it.copy(busy = null, message = UiMessage("Đã lưu: $path.$warn")) }
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = null) }
                throw e
            } catch (e: Throwable) {
                showError(e, "Lưu ảnh thất bại")
            }
        }
    }

    // ---------------------------------------------------------------- cài đặt & mô hình

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val old = _state.value.settings
        val new = transform(old)
        if (new == old) return
        settingsStore.save(new)
        applyEngineMode(new)
        val pronounChanged = new.pronounStyle != old.pronounStyle
        if (pronounChanged) {
            // Áp dụng xưng hô mới cho mọi trang đã dịch, trừ câu người dùng đã tự sửa.
            for ((key, page) in pageCache.entries.toList()) {
                pageCache[key] = page.copy(regions = page.regions.map { applyPronoun(it, new) })
            }
        }
        _state.update {
            it.copy(
                settings = new,
                regions = if (pronounChanged) it.regions.map { r -> applyPronoun(r, new) } else it.regions,
                detectedLanguage = if (new.sourceLanguage != old.sourceLanguage) null else it.detectedLanguage
            )
        }
        val renderChanged = new.toRenderStyle() != old.toRenderStyle()
        if (pronounChanged || renderChanged) rerender()
    }

    private fun applyPronoun(r: TextRegion, s: AppSettings): TextRegion =
        if (r.userEdited) r else r.copy(translatedText = pageTranslator.finalize(r.rawTranslation, s.pronounStyle))

    fun refreshModelStatus() {
        viewModelScope.launch {
            val status = HashMap<SourceLanguage, ModelState>()
            for (lang in SourceLanguage.entries) {
                val code = lang.code ?: continue
                if (_state.value.modelStatus[lang] == ModelState.DOWNLOADING) {
                    status[lang] = ModelState.DOWNLOADING
                    continue
                }
                status[lang] = try {
                    if (translationEngine.isModelReady(code)) ModelState.READY else ModelState.NOT_DOWNLOADED
                } catch (e: Exception) {
                    ModelState.ERROR
                }
            }
            _state.update { it.copy(modelStatus = status) }
        }
    }

    fun downloadModel(lang: SourceLanguage) {
        val code = lang.code ?: return
        setModelState(lang, ModelState.DOWNLOADING)
        viewModelScope.launch {
            try {
                translationEngine.downloadModel(code)
                setModelState(lang, ModelState.READY)
                showMessage("Đã tải mô hình ${lang.label} → Việt. Có thể dịch offline.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setModelState(lang, ModelState.ERROR)
                showMessage("Tải mô hình thất bại (cần Internet): ${e.message ?: ""}")
            }
        }
    }

    private fun setModelState(lang: SourceLanguage, st: ModelState) =
        _state.update { it.copy(modelStatus = it.modelStatus + (lang to st)) }

    // ---------------------------------------------------------------- màn hình & thông báo

    fun openSettings() = _state.update {
        it.copy(screenBeforeSettings = if (it.screen == Screen.SETTINGS) it.screenBeforeSettings else it.screen, screen = Screen.SETTINGS)
    }

    fun closeSettings() = _state.update { it.copy(screen = it.screenBeforeSettings) }

    // ---------------------------------------------------------------- chẩn đoán

    private fun openTool(screen: Screen) = _state.update {
        val from = if (it.screen == Screen.DEBUG || it.screen == Screen.MT_TEST) it.screenBeforeTool else it.screen
        it.copy(screenBeforeTool = from, screen = screen)
    }

    fun openDebug() = openTool(Screen.DEBUG)
    fun openMtTest() = openTool(Screen.MT_TEST)
    fun closeTool() = _state.update { it.copy(screen = it.screenBeforeTool) }

    /** Trạng thái vẽ của một vùng, dùng trong màn hình DEBUG. */
    fun drawnStatus(r: TextRegion): String {
        val s = _state.value
        return when {
            !r.enabled -> "ĐÃ ẨN (không vẽ)"
            r.translatedText.isBlank() -> "không có bản dịch (không vẽ)"
            r.id in s.hiddenIds -> "CHƯA vẽ — bản dịch đáng ngờ, chờ xác nhận"
            r.id in s.overflowIds -> "CHƯA vẽ — không đủ chỗ trong khung"
            s.translatedChunks.isEmpty() -> "chưa vẽ"
            else -> "đã vẽ lên ảnh"
        }
    }

    fun debugReport(): String {
        val s = _state.value
        return "Bộ dịch: ${translationEngine.name}\n" +
            vn.mangatranslate.app.core.model.DiagReport.format(
                s.currentPage?.name ?: "trang", s.imageWidth, s.imageHeight, s.regions
            ) { drawnStatus(it) }
    }

    val engineName: String get() = translationEngine.name

    fun setMtTestLang(lang: SourceLanguage) = _state.update { it.copy(mtTestLang = lang, mtTestResult = null, mtTestError = null) }
    fun setMtTestNormalize(v: Boolean) = _state.update { it.copy(mtTestNormalize = v) }

    /** Kiểm tra bộ dịch ĐỘC LẬP với OCR: dịch văn bản người dùng gõ, hiện từng bước. */
    fun runMtTest(text: String) {
        val s = _state.value
        if (s.busy != null || text.isBlank()) return
        workJob = viewModelScope.launch {
            _state.update { it.copy(busy = "Đang dịch…", mtTestError = null) }
            try {
                val tr = withContext(Dispatchers.Default) {
                    pageTranslator.translateText(
                        text.trim(), s.mtTestLang, if (s.mtTestNormalize) s.settings.glossary() else emptyList(),
                        { stage -> _state.update { it.copy(busy = stage) } }, normalize = s.mtTestNormalize, compareMlKit = true
                    )
                }
                _state.update { it.copy(busy = null, mtTestResult = tr) }
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = null) }
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(busy = null, mtTestError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun closeReader() = _state.update { it.copy(screen = Screen.HOME, mode = ReaderMode.VIEW, selectedRegionId = null) }

    fun resumeReader() {
        if (_state.value.pages.isEmpty()) return
        _state.update { it.copy(screen = Screen.READER) }
    }

    fun showMessage(text: String) = _state.update { it.copy(message = UiMessage(text)) }

    fun consumeMessage(id: Long) = _state.update { if (it.message?.id == id) it.copy(message = null) else it }

    private fun showError(e: Throwable, prefix: String) {
        val text = when (e) {
            is UserFacingException -> e.message ?: prefix
            is OutOfMemoryError -> "$prefix: không đủ bộ nhớ RAM cho ảnh này."
            else -> "$prefix: ${e.message ?: e.javaClass.simpleName}"
        }
        _state.update { it.copy(busy = null, message = UiMessage(text)) }
    }

    private fun cancelAllJobs() {
        pageJob?.cancel()
        workJob?.cancel()
        renderJob?.cancel()
    }

    override fun onCleared() {
        cancelAllJobs()
        ocr.close()
        translationEngine.close()
        super.onCleared()
    }
}
