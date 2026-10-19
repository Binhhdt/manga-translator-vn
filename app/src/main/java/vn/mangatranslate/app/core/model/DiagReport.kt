package vn.mangatranslate.app.core.model

import java.util.Locale

/**
 * Báo cáo chẩn đoán dạng văn bản cho từng vùng chữ. Văn bản OCR in NGUYÊN VẸN (trong «»).
 * Thuần Kotlin — dùng cho nút "Sao chép báo cáo" và bài đo tự động.
 */
object DiagReport {

    fun format(
        title: String,
        imageWidth: Int,
        imageHeight: Int,
        regions: List<TextRegion>,
        drawnText: (TextRegion) -> String
    ): String = buildString {
        appendLine("=== $title — ảnh ${imageWidth}x$imageHeight, ${regions.size} vùng ===")
        for (r in regions) {
            appendLine()
            append(region(r, drawnText(r)))
        }
    }

    fun region(r: TextRegion, drawn: String): String = buildString {
        val t = r.trace
        appendLine("--- Vùng #${r.id} (${t?.kind ?: "?"}) ---")
        appendLine("8. Tọa độ vùng chữ (ảnh gốc): ${box(r.box)}")
        if (t != null) {
            appendLine("1-2. Vùng cắt đưa vào OCR: (${t.cropLeft},${t.cropTop}) ${t.cropWidth}x${t.cropHeight}px")
            if (t.pageLines.isNotEmpty()) {
                appendLine("3a. OCR toàn trang — dòng ML Kit trả về (đúng thứ tự):")
                t.pageLines.forEachIndexed { i, l -> appendLine("    [${i + 1}] «${l.text}» ${box(l.box)}${ruby(l)}") }
            }
            if (t.layoutInfo.isNotEmpty()) appendLine("4. ${t.layoutInfo}")
            for (a in t.attempts) {
                val size = if (a.inputWidth > 0) " ảnh ${a.inputWidth}x${a.inputHeight}" else ""
                appendLine("3b. ${if (a.chosen) "✓ CHỌN" else "  "} ${a.method}$size, điểm ${fmt(a.score)}:")
                a.rawLines.forEachIndexed { i, l -> appendLine("    [${i + 1}] «${l.text}» ${box(l.box)}${ruby(l)}") }
                appendLine("    → ghép theo thứ tự đọc: «${a.joined}»")
            }
            appendLine("   Văn bản nguồn đã chọn: «${t.chosenText}»")
        }
        appendLine("   Văn bản nguồn hiện tại: «${r.sourceText}»")
        if (t != null) {
            appendLine("   Bộ dịch: ${t.engine}")
            appendLine("5. Gửi vào bộ dịch: «${t.mtInput}»")
            appendLine("6. Bộ dịch trả về (thô): «${t.mtRaw}»")
        }
        appendLine("7. Văn bản cuối: «${r.translatedText}» — $drawn")
        if (r.reviewNotes.isNotEmpty()) appendLine("   Cần kiểm tra: ${r.reviewNotes.joinToString(" | ")}")
    }

    private fun ruby(l: TraceLine) = if (l.removedAsRuby) "  ← bị loại (furigana)" else ""

    fun box(b: BoxF) = "(${b.left.toInt()},${b.top.toInt()})-(${b.right.toInt()},${b.bottom.toInt()})"

    private fun fmt(f: Float) = if (f.isInfinite()) "-∞" else String.format(Locale.US, "%.1f", f)
}
