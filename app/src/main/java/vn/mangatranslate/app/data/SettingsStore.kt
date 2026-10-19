package vn.mangatranslate.app.data

import android.content.Context
import vn.mangatranslate.app.core.export.ExportFormat
import vn.mangatranslate.app.core.model.PronounStyle
import vn.mangatranslate.app.core.model.RenderStyle
import vn.mangatranslate.app.core.model.SourceLanguage
import vn.mangatranslate.app.core.model.TextColorMode
import vn.mangatranslate.app.core.translate.Glossary
import vn.mangatranslate.app.core.translate.GlossaryEntry

data class AppSettings(
    val sourceLanguage: SourceLanguage = SourceLanguage.AUTO,
    val pronounStyle: PronounStyle = PronounStyle.KEEP,
    val textColorMode: TextColorMode = TextColorMode.AUTO,
    val outline: Boolean = true,
    val bold: Boolean = true,
    val lineSpacing: Float = 1.0f,
    val coverBackground: Boolean = true,
    val exportFormat: ExportFormat = ExportFormat.JPG,
    val overlayOpacity: Int = 92,
    val cleanBubbles: Boolean = false,
    val glossaryText: String = DEFAULT_GLOSSARY,
    val hideUnverified: Boolean = true,
    val debugMask: Boolean = false
) {
    fun toRenderStyle() = RenderStyle(
        textColorMode = textColorMode,
        outline = outline,
        bold = bold,
        lineSpacing = lineSpacing,
        coverBackground = coverBackground,
        overlayOpacity = overlayOpacity,
        cleanBubbles = cleanBubbles,
        hideUnverified = hideUnverified,
        debugMask = debugMask
    )

    fun glossary(): List<GlossaryEntry> = Glossary.parse(glossaryText)

    companion object {
        const val DEFAULT_GLOSSARY = "# Mỗi dòng: nguồn = đích. Dòng bắt đầu bằng # là ghi chú.\n" +
            "# Chữ Nhật/Trung/Hàn ở bên trái → thay TRƯỚC khi dịch (giữ tên riêng):\n" +
            "# 黒崎 = Kurosaki\n" +
            "# Chữ Latin/tiếng Việt ở bên trái → sửa SAU khi dịch:\n" +
            "# Blacklord = Kurosaki\n"
    }
}

/** Lưu cài đặt bằng SharedPreferences (Room dùng cho dữ liệu dự án từ Giai đoạn 3). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            sourceLanguage = enumOr(prefs.getString("source", null), d.sourceLanguage),
            pronounStyle = enumOr(prefs.getString("pronoun", null), d.pronounStyle),
            textColorMode = enumOr(prefs.getString("textColor", null), d.textColorMode),
            outline = prefs.getBoolean("outline", d.outline),
            bold = prefs.getBoolean("bold", d.bold),
            lineSpacing = prefs.getFloat("lineSpacing", d.lineSpacing),
            coverBackground = prefs.getBoolean("cover", d.coverBackground),
            exportFormat = enumOr(prefs.getString("format", null), d.exportFormat),
            overlayOpacity = prefs.getInt("overlayOpacity", d.overlayOpacity),
            // Khóa mới: giá trị "bật" của bản 0.1.1–0.1.2 (gây xóa mất hình) không được dùng lại.
            cleanBubbles = prefs.getBoolean("cleanBubblesV2", d.cleanBubbles),
            glossaryText = prefs.getString("glossary", null) ?: d.glossaryText,
            hideUnverified = prefs.getBoolean("hideUnverified", d.hideUnverified),
            debugMask = prefs.getBoolean("debugMask", d.debugMask)
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("source", s.sourceLanguage.name)
            .putString("pronoun", s.pronounStyle.name)
            .putString("textColor", s.textColorMode.name)
            .putBoolean("outline", s.outline)
            .putBoolean("bold", s.bold)
            .putFloat("lineSpacing", s.lineSpacing)
            .putBoolean("cover", s.coverBackground)
            .putString("format", s.exportFormat.name)
            .putInt("overlayOpacity", s.overlayOpacity)
            .putBoolean("cleanBubblesV2", s.cleanBubbles)
            .putString("glossary", s.glossaryText)
            .putBoolean("hideUnverified", s.hideUnverified)
            .putBoolean("debugMask", s.debugMask)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T =
        value?.let { v -> enumValues<T>().firstOrNull { it.name == v } } ?: default
}
