package vn.mangatranslate.app.core.ocr

import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.model.OcrBlock
import vn.mangatranslate.app.core.model.OcrLine
import vn.mangatranslate.app.core.util.TextNormalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Gom các khối chữ OCR thành vùng thoại.
 *
 * ML Kit đôi khi tách một bong bóng thành nhiều khối (hoặc ngược lại). Ở đây các khối có cùng hướng chữ,
 * cỡ chữ tương đương và nằm sát nhau (khoảng cách < [mergeFactor] × cỡ chữ) được gộp lại.
 * Đây là heuristic hình học, KHÔNG phải nhận diện bong bóng thật sự; vùng gom sai có thể sửa/xóa bằng tay.
 */
class TextRegionDetector(private val mergeFactor: Float = 0.8f) {

    data class DetectedRegion(
        val box: BoxF,
        val text: String,
        val vertical: Boolean,
        val lineHeight: Float
    )

    fun detect(blocks: List<OcrBlock>, rightToLeft: Boolean): List<DetectedRegion> {
        val cleaned = blocks.mapNotNull { clean(it) }
        if (cleaned.isEmpty()) return emptyList()

        val vertical = BooleanArray(cleaned.size) { isVertical(cleaned[it].lines) }
        val thickness = FloatArray(cleaned.size) { estimateThickness(cleaned[it].lines, vertical[it]) }

        val parent = IntArray(cleaned.size) { it }
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != root) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }

        for (i in cleaned.indices) {
            for (j in i + 1 until cleaned.size) {
                if (vertical[i] != vertical[j]) continue
                val a = thickness[i]
                val b = thickness[j]
                if (max(a, b) / max(1f, min(a, b)) > MAX_SIZE_RATIO) continue
                val margin = mergeFactor * min(a, b)
                if (cleaned[i].box.expand(margin).intersects(cleaned[j].box)) {
                    val ri = find(i)
                    val rj = find(j)
                    if (ri != rj) parent[ri] = rj
                }
            }
        }

        val regions = cleaned.indices.groupBy { find(it) }.values.mapNotNull { members ->
            val lines = members.flatMap { cleaned[it].lines }
            val isVertical = members.count { vertical[it] } * 2 >= members.size
            val text = buildText(lines, isVertical)
            if (text.none { it.isLetter() }) return@mapNotNull null
            DetectedRegion(
                box = BoxF.unionOf(lines.map { it.box }),
                text = text,
                vertical = isVertical,
                lineHeight = median(members.map { thickness[it] })
            )
        }
        return sortReadingOrder(regions, rightToLeft)
    }

    /** Ghép các dòng thành câu theo đúng thứ tự đọc. Chữ dọc: cột từ phải sang trái, trên xuống dưới. */
    fun buildText(lines: List<OcrLine>, vertical: Boolean): String {
        if (lines.isEmpty()) return ""
        return if (vertical) {
            val t = median(lines.map { it.box.width })
            val columns = clusterBy(lines.sortedByDescending { it.box.centerX }, t * 0.6f) { it.box.centerX }
            TextNormalizer.joinLines(columns.map { col ->
                col.sortedBy { it.box.top }.joinToString("") { it.text.trim() }
            })
        } else {
            val t = median(lines.map { it.box.height })
            val rows = clusterBy(lines.sortedBy { it.box.centerY }, t * 0.5f) { it.box.centerY }
            TextNormalizer.joinLines(rows.map { row ->
                TextNormalizer.joinLines(row.sortedBy { it.box.left }.map { it.text })
            })
        }
    }

    /** Cỡ chữ ước lượng: trung vị chiều cao dòng (chữ ngang) hoặc độ rộng cột (chữ dọc). */
    fun estimateThickness(lines: List<OcrLine>, vertical: Boolean): Float =
        median(lines.map { if (vertical) it.box.width else it.box.height })

    private fun clean(block: OcrBlock): OcrBlock? {
        val lines = block.lines
            .map { it.copy(text = it.text.trim()) }
            .filter { l ->
                l.text.isNotEmpty() &&
                    l.box.width >= MIN_SIDE && l.box.height >= MIN_SIDE &&
                    !(l.text.length == 1 && !l.text[0].isLetterOrDigit())
            }
        if (lines.isEmpty()) return null
        return OcrBlock(lines, BoxF.unionOf(lines.map { it.box }))
    }

    private fun isVertical(lines: List<OcrLine>): Boolean {
        val multi = lines.filter { it.text.codePointCount(0, it.text.length) >= 2 }
        if (multi.isNotEmpty()) {
            val tall = multi.count { it.box.height > it.box.width * 1.5f }
            return tall * 2 > multi.size
        }
        // Toàn ký tự đơn: coi là chữ dọc nếu xếp chồng theo chiều đứng.
        if (lines.size >= 3) {
            val b = BoxF.unionOf(lines.map { it.box })
            return b.height > b.width * 1.8f
        }
        return false
    }

    private fun sortReadingOrder(regions: List<DetectedRegion>, rightToLeft: Boolean): List<DetectedRegion> {
        if (regions.size < 2) return regions
        val band = max(1f, median(regions.map { it.box.height }) * 0.6f)
        val rows = ArrayList<MutableList<DetectedRegion>>()
        var anchor = Float.NaN
        for (r in regions.sortedBy { it.box.top }) {
            if (rows.isEmpty() || r.box.top - anchor > band) {
                rows += mutableListOf(r)
                anchor = r.box.top
            } else {
                rows.last() += r
            }
        }
        return rows.flatMap { row ->
            if (rightToLeft) row.sortedByDescending { it.box.centerX } else row.sortedBy { it.box.centerX }
        }
    }

    private fun clusterBy(sorted: List<OcrLine>, tolerance: Float, key: (OcrLine) -> Float): List<List<OcrLine>> {
        val out = ArrayList<MutableList<OcrLine>>()
        var anchor = Float.NaN
        for (l in sorted) {
            val k = key(l)
            if (out.isEmpty() || abs(k - anchor) > tolerance) {
                out += mutableListOf(l)
                anchor = k
            } else {
                out.last() += l
            }
        }
        return out
    }

    companion object {
        private const val MIN_SIDE = 3f
        private const val MAX_SIZE_RATIO = 1.8f

        fun median(values: List<Float>): Float {
            if (values.isEmpty()) return 0f
            val s = values.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
        }
    }
}
