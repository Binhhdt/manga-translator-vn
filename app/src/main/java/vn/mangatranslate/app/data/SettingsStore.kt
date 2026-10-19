package vn.mangatranslate.app.data

import android.content.Context
import vn.mangatranslate.app.core.export.ExportFormat
import vn.mangatranslate.app.core.model.PronounStyle
import vn.mangatranslate.app.core.model.RenderStyle
import vn.mangatranslate.app.core.model.SourceLanguage
import vn.mangatranslate.app.core.model.TextColorMode

data class AppSettings(
    val sourceLanguage: SourceLanguage = SourceLanguage.AUTO,
    val pronounStyle: PronounStyle = PronounStyle.KEEP,
    val textColorMode: TextColorMode = TextColorMode.AUTO,
    val outline: Boolean = true,
    val bold: Boolean = true,
    val lineSpacing: Float = 1.0f,
    val coverBackground: Boolean = true,
    val exportFormat: ExportFormat = ExportFormat.JPG
) {
    fun toRenderStyle() = RenderStyle(
        textColorMode = textColorMode,
        outline = outline,
        bold = bold,
        lineSpacing = lineSpacing,
        coverBackground = coverBackground
    )
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
            exportFormat = enumOr(prefs.getString("format", null), d.exportFormat)
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
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T =
        value?.let { v -> enumValues<T>().firstOrNull { it.name == v } } ?: default
}
