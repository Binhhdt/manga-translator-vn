package vn.mangatranslate.app.core.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.model.OcrBlock
import vn.mangatranslate.app.core.model.OcrLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OCR trên thiết bị bằng ML Kit Text Recognition v2.
 *
 * - Mỗi bộ nhận dạng chỉ khởi tạo một lần và dùng lại.
 * - Ảnh hẹp (chữ nhỏ) được phóng to trước khi OCR, tọa độ được quy đổi về ảnh gốc.
 * - Ảnh truyện dài (manhwa cuộn dọc) được chia thành các ô chồng lấn nhau, OCR từng ô để chữ không bị
 *   thu nhỏ quá mức; khối chữ bị cắt ở mép ô được bỏ vì ô kế tiếp đã chứa trọn khối đó.
 *
 * Giới hạn đã biết: ML Kit đọc chữ dọc tiếng Nhật và chữ cách điệu/hiệu ứng âm thanh kém —
 * khi đó dùng chức năng "Thêm vùng" để OCR thủ công và sửa chữ bằng tay.
 */
class OCRProcessor {

    enum class Script { LATIN, CHINESE, JAPANESE, KOREAN }

    private data class Tile(val top: Int, val bottom: Int)

    private val clients = HashMap<Script, TextRecognizer>()

    private fun client(script: Script): TextRecognizer = synchronized(clients) {
        clients.getOrPut(script) {
            when (script) {
                Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            }
        }
    }

    /**
     * @param upscale hệ số phóng to cố định; null = tự quyết định theo độ rộng ảnh.
     * @param maxTiles chỉ xử lý N ô đầu (dùng khi đoán ngôn ngữ).
     * @return các khối chữ với tọa độ theo [source].
     */
    suspend fun recognize(
        source: Bitmap,
        script: Script,
        upscale: Float? = null,
        maxTiles: Int = Int.MAX_VALUE
    ): List<OcrBlock> {
        val w = source.width
        val h = source.height
        val scale = upscale ?: if (w < SMALL_WIDTH) min(MAX_AUTO_UPSCALE, TARGET_WIDTH.toFloat() / w) else 1f
        val tileHeight = max((w * 1.6f).toInt(), MIN_TILE_HEIGHT)
        val overlap = (tileHeight * 0.22f).toInt()
        val tiles = buildTiles(h, tileHeight, overlap)
        val result = ArrayList<OcrBlock>()

        for ((index, tile) in tiles.withIndex()) {
            if (index >= maxTiles) break
            currentCoroutineContext().ensureActive()
            val tileH = tile.bottom - tile.top
            val tileBmp = if (tile.top == 0 && tile.bottom == h) source
            else Bitmap.createBitmap(source, 0, tile.top, w, tileH)
            val input = if (abs(scale - 1f) > 0.01f) {
                Bitmap.createScaledBitmap(
                    tileBmp, max(1, (w * scale).roundToInt()), max(1, (tileH * scale).roundToInt()), true
                )
            } else tileBmp

            try {
                val text: Text = client(script).process(InputImage.fromBitmap(input, 0)).await()
                val sx = w.toFloat() / input.width
                val sy = tileH.toFloat() / input.height
                for (block in text.textBlocks) {
                    val lines = block.lines.mapNotNull { line ->
                        val r = line.boundingBox ?: return@mapNotNull null
                        OcrLine(
                            line.text,
                            BoxF(r.left * sx, r.top * sy + tile.top, r.right * sx, r.bottom * sy + tile.top)
                        )
                    }
                    if (lines.isEmpty()) continue
                    val box = BoxF.unionOf(lines.map { it.box })
                    val small = box.height < overlap * 0.9f
                    val cutTop = index > 0 && box.top <= tile.top + EDGE_PX && small
                    val cutBottom = index < tiles.lastIndex && box.bottom >= tile.bottom - EDGE_PX && small
                    if (cutTop || cutBottom) continue
                    result += OcrBlock(lines, box)
                }
            } finally {
                if (input !== tileBmp) input.recycle()
                if (tileBmp !== source) tileBmp.recycle()
            }
        }
        return if (tiles.size > 1) dedupe(result) else result
    }

    private fun buildTiles(h: Int, tileHeight: Int, overlap: Int): List<Tile> {
        if (h <= tileHeight * 5 / 4) return listOf(Tile(0, h))
        val out = ArrayList<Tile>()
        var top = 0
        while (true) {
            val bottom = min(h, top + tileHeight)
            out += Tile(top, bottom)
            if (bottom >= h) break
            top = bottom - overlap
        }
        return out
    }

    /** Bỏ khối trùng lặp do vùng chồng lấn giữa hai ô. */
    private fun dedupe(blocks: List<OcrBlock>): List<OcrBlock> {
        val kept = ArrayList<OcrBlock>()
        for (b in blocks.sortedByDescending { it.box.area }) {
            val duplicate = kept.any { k ->
                k.box.intersectionArea(b.box) > 0.6f * min(k.box.area, b.box.area)
            }
            if (!duplicate) kept += b
        }
        return kept
    }

    fun close() {
        synchronized(clients) {
            clients.values.forEach { it.close() }
            clients.clear()
        }
    }

    private companion object {
        const val SMALL_WIDTH = 900
        const val TARGET_WIDTH = 1300
        const val MAX_AUTO_UPSCALE = 2f
        const val MIN_TILE_HEIGHT = 1600
        const val EDGE_PX = 3
    }
}
