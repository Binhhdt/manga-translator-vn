package vn.mangatranslate.app.core.vision

import vn.mangatranslate.app.core.model.BoxF
import kotlin.math.max
import kotlin.math.min

/**
 * Tìm các cụm "trông giống chữ" mà OCR toàn trang có thể đã bỏ sót: nhiều mảnh mực cỡ ký tự, đứng sát nhau,
 * xung quanh là nền sáng. Kết quả chỉ là ỨNG VIÊN — mỗi ứng viên được OCR lại và bị loại nếu kết quả không
 * giống văn bản của ngôn ngữ nguồn. Không dùng để khẳng định là có chữ.
 */
class TextBlobFinder(private val inkThreshold: Int = 128) {

    private class Blob(val l: Int, val t: Int, val r: Int, val b: Int, val count: Int) {
        val w get() = r - l
        val h get() = b - t
        val size get() = max(w, h)
    }

    fun find(img: GrayImage, exclude: List<BoxF>, maxResults: Int = 8): List<BoxF> {
        val w = img.width
        val h = img.height
        val minC = max(6, (w * 0.012f).toInt())
        val maxC = max(minC + 1, (w * 0.09f).toInt())
        val labels = IntArray(w * h)
        val queue = IntArray(w * h)
        val blobs = ArrayList<Blob>()
        var next = 1
        for (start in 0 until w * h) {
            if (labels[start] != 0 || img.pixels[start] >= inkThreshold) continue
            val label = next++
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = label
            var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1; var c = 0
            var tooBig = false
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                c++
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
                if (r - l > maxC * 3 || b - t > maxC * 3) tooBig = true // nét vẽ lớn: vẫn lan hết để gán nhãn, nhưng bỏ
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (labels[q] == 0 && img.pixels[q] < inkThreshold) { labels[q] = label; queue[tail++] = q }
                }
            }
            if (tooBig) continue
            val blob = Blob(l, t, r + 1, b + 1, c)
            val fill = c.toFloat() / (blob.w * blob.h)
            val aspect = max(blob.w, blob.h).toFloat() / max(1, min(blob.w, blob.h))
            if (blob.size in (minC / 2)..maxC && fill in 0.06f..0.9f && aspect <= 8f) blobs += blob
        }

        // Giữ mảnh có nền sáng xung quanh (chữ trong bong bóng / vùng trắng).
        val clean = blobs.filter { bl ->
            val pad = max(2, (bl.size * 0.3f).toInt())
            surroundBright(img, bl.l - pad, bl.t - pad, bl.r + pad, bl.b + pad, bl) >= 0.55f
        }

        // Gom mảnh gần nhau, cỡ tương đương.
        val parent = IntArray(clean.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        for (i in clean.indices) for (j in i + 1 until clean.size) {
            val a = clean[i]
            val c = clean[j]
            if (max(a.size, c.size).toFloat() / max(1, min(a.size, c.size)) > 2.5f) continue
            val m = 0.45f * min(a.size, c.size)
            val gx = max(0, max(a.l, c.l) - min(a.r, c.r))
            val gy = max(0, max(a.t, c.t) - min(a.b, c.b))
            if (gx <= m && gy <= m) {
                val ri = find(i); val rj = find(j)
                if (ri != rj) parent[ri] = rj
            }
        }
        val groups = clean.indices.groupBy { find(it) }.values
            .filter { it.size >= 3 }
            .map { idx ->
                val members = idx.map { clean[it] }
                val size = members.map { it.size }.sorted()[members.size / 2]
                val box = BoxF(
                    members.minOf { it.l }.toFloat(), members.minOf { it.t }.toFloat(),
                    members.maxOf { it.r }.toFloat(), members.maxOf { it.b }.toFloat()
                )
                box to size
            }
            .filter { (box, size) -> max(box.width, box.height) >= 1.5f * size && max(box.width, box.height) <= 14f * size }
            .filter { (box, _) -> exclude.none { ex -> ex.intersectionArea(box) > 0.25f * box.area } }
            .sortedByDescending { it.first.area }
            .take(maxResults)
        return groups.map { (box, size) -> box.expand(size * 0.3f).clampTo(w.toFloat(), h.toFloat()) }
    }

    private fun surroundBright(img: GrayImage, l0: Int, t0: Int, r0: Int, b0: Int, self: Blob): Float {
        val l = max(0, l0); val t = max(0, t0); val r = min(img.width, r0); val b = min(img.height, b0)
        var bright = 0
        var total = 0
        for (y in t until b) for (x in l until r) {
            if (x in self.l until self.r && y in self.t until self.b) continue
            total++
            if (img[x, y] >= 200) bright++
        }
        return if (total == 0) 0f else bright.toFloat() / total
    }
}
