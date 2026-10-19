package vn.mangatranslate.app.core.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
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
import vn.mangatranslate.app.core.image.CropMath
import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.model.OcrBlock
import vn.mangatranslate.app.core.model.OcrLine
import kotlin.math.max
import kotlin.math.min

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
        maxTiles: Int = Int.MAX_VALUE,
        tag: String = "trang"
    ): List<OcrBlock> {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "[$tag] bỏ qua ảnh rỗng ${w}x$h")
            return emptyList()
        }
        val scale = upscale ?: if (w < SMALL_WIDTH) min(MAX_AUTO_UPSCALE, TARGET_WIDTH.toFloat() / w) else 1f
        val tileHeight = max((w * 1.6f).toInt(), MIN_TILE_HEIGHT)
        val overlap = (tileHeight * 0.22f).toInt()
        val tiles = buildTiles(h, tileHeight, overlap)
        val result = ArrayList<OcrBlock>()

        for ((index, tile) in tiles.withIndex()) {
            if (index >= maxTiles) break
            currentCoroutineContext().ensureActive()
            val tileH = tile.bottom - tile.top
            if (tileH <= 0) continue
            val tileBmp = if (tile.top == 0 && tile.bottom == h) source
            else Bitmap.createBitmap(source, 0, tile.top, w, tileH)

            // ML Kit từ chối ảnh < 32 px mỗi chiều → phóng (giữ tỉ lệ) và/hoặc đệm nền trắng trước khi gọi.
            val plan = CropMath.inputPlan(w, tileH, scale)
            val input = prepareInput(tileBmp, plan)
            Log.d(
                TAG,
                "[$tag] ảnh nguồn=${w}x$h ô=${tile.top}..${tile.bottom} " +
                    "phóng=${"%.2f".format(plan.scale)} đệm=${plan.padX},${plan.padY} → InputImage ${input.width}x${input.height}"
            )

            try {
                if (input.width < CropMath.MLKIT_MIN_SIDE || input.height < CropMath.MLKIT_MIN_SIDE) {
                    Log.e(TAG, "[$tag] ảnh vẫn nhỏ hơn ${CropMath.MLKIT_MIN_SIDE}px sau khi chuẩn bị (${input.width}x${input.height}) — bỏ qua ô này")
                    continue
                }
                val text: Text = client(script).process(InputImage.fromBitmap(input, 0)).await()
                // Quy đổi tọa độ: bỏ phần đệm, chia tỉ lệ phóng, cộng vị trí ô.
                val sx = w.toFloat() / plan.scaledW
                val sy = tileH.toFloat() / plan.scaledH
                val ox = plan.padX.toFloat()
                val oy = plan.padY.toFloat()
                for (block in text.textBlocks) {
                    val lines = block.lines.mapNotNull { line ->
                        val r = line.boundingBox ?: return@mapNotNull null
                        val box = BoxF(
                            ((r.left - ox) * sx).coerceIn(0f, w.toFloat()),
                            ((r.top - oy) * sy).coerceIn(0f, tileH.toFloat()) + tile.top,
                            ((r.right - ox) * sx).coerceIn(0f, w.toFloat()),
                            ((r.bottom - oy) * sy).coerceIn(0f, tileH.toFloat()) + tile.top
                        )
                        if (box.width <= 0f || box.height <= 0f) null else OcrLine(line.text, box)
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

    /** Tạo ảnh đúng theo [plan]: phóng giữ tỉ lệ rồi đệm nền trắng nếu còn thiếu. Trả về chính [src] nếu không cần. */
    private fun prepareInput(src: Bitmap, plan: CropMath.InputPlan): Bitmap {
        if (!plan.needsWork) return src
        val scaled = if (plan.scaledW != src.width || plan.scaledH != src.height) {
            Bitmap.createScaledBitmap(src, plan.scaledW, plan.scaledH, true)
        } else src
        if (plan.padX == 0 && plan.padY == 0) return scaled
        val padded = Bitmap.createBitmap(plan.outW, plan.outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(scaled, plan.padX.toFloat(), plan.padY.toFloat(), null)
        if (scaled !== src) scaled.recycle()
        return padded
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
        const val TAG = "MangaOCR"
    }
}
