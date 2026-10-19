package vn.mangatranslate.app.core.vision

import vn.mangatranslate.app.core.model.BoxF
import kotlin.math.max
import kotlin.math.min

/**
 * Tìm bong bóng thoại bằng xử lý ảnh cổ điển (không cần mô hình AI):
 *
 * 1. Gán nhãn các vùng điểm sáng (trắng) liền nhau.
 * 2. Giữ vùng có kích thước hợp lý, không chạm mép trang (lề trắng của trang bị loại).
 * 3. "Lỗ" của vùng = các điểm tối bị vùng trắng bao kín hoàn toàn — chính là nét chữ (cả furigana) nằm
 *    trong bong bóng. Viền bong bóng KHÔNG phải lỗ vì nó thông ra ngoài.
 * 4. Phần trong bong bóng = vùng trắng ∪ lỗ. Lọc theo độ đặc (bong bóng tròn/elip/hình sao đều khá đặc).
 * 5. Tính hình chữ nhật nằm gọn trong bong bóng để đặt chữ Việt (không tràn ra ngoài, không đè nhân vật).
 *
 * Giới hạn: bong bóng tràn ra lề trắng của trang (dính lề) hoặc có viền đứt sẽ không nhận được —
 * khi đó ứng dụng quay về cách gom vùng chữ theo khoảng cách như cũ.
 */
class BubbleDetector(
    private val whiteThreshold: Int = 200,
    private val minAreaRatio: Float = 0.0004f,
    /** Bong bóng thoại thật hiếm khi > 6% diện tích trang. Lỗi bản 0.1.2: nền trắng của cả khung tranh (23%) lọt qua. */
    private val maxAreaRatio: Float = 0.06f,
    private val minInteriorFill: Float = 0.42f,
    private val maxHoleRatio: Float = 0.4f,
    /** Một mảng nét liền (lỗ) lớn hơn tỉ lệ này của bong bóng là HÌNH VẼ (nhân vật, khung thành), không phải chữ. */
    private val maxHoleComponentRatio: Float = 0.12f
) {

    /** Một bong bóng, tọa độ theo [GrayImage] đã phân tích. right/bottom là biên loại trừ. */
    class Bubble(
        val label: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        private val interior: BooleanArray,
        private val holes: BooleanArray,
        val interiorArea: Int,
        val holeArea: Int,
        val inner: BoxF
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val holeRatio: Float get() = holeArea.toFloat() / max(1, interiorArea)
        val box: BoxF get() = BoxF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

        fun isInterior(x: Int, y: Int): Boolean =
            x in left until right && y in top until bottom && interior[(y - top) * width + (x - left)]

        fun isHole(x: Int, y: Int): Boolean =
            x in left until right && y in top until bottom && holes[(y - top) * width + (x - left)]

        /** Bên trong và cách mép bong bóng ít nhất 1 điểm (tránh lấy dính viền khi cắt ảnh). */
        fun isInteriorEroded(x: Int, y: Int): Boolean =
            isInterior(x, y) && isInterior(x - 1, y) && isInterior(x + 1, y) && isInterior(x, y - 1) && isInterior(x, y + 1)

        /** Lỗ hoặc sát cạnh lỗ (phủ cả viền mờ khử răng cưa của nét chữ). */
        fun isHoleDilated(x: Int, y: Int): Boolean {
            for (dy in -1..1) for (dx in -1..1) if (isHole(x + dx, y + dy)) return true
            return false
        }

        /** Khung bao các nét chữ trong bong bóng, null nếu bong bóng trống. */
        fun holeBounds(): BoxF? {
            var l = Int.MAX_VALUE
            var t = Int.MAX_VALUE
            var r = Int.MIN_VALUE
            var b = Int.MIN_VALUE
            for (y in 0 until height) for (x in 0 until width) {
                if (holes[y * width + x]) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
            if (r < l) return null
            return BoxF((left + l).toFloat(), (top + t).toFloat(), (left + r + 1).toFloat(), (top + b + 1).toFloat())
        }
    }

    class Result(val width: Int, val height: Int, private val labels: IntArray, val bubbles: List<Bubble>) {
        private val byLabel = bubbles.associateBy { it.label }

        /**
         * Bong bóng chứa khung chữ [box] (tọa độ ảnh xám), null nếu chữ không nằm trong bong bóng nào.
         * Bỏ phiếu theo nhãn của các điểm trắng trong khung, rồi xác nhận tâm khung nằm trong bong bóng.
         */
        fun bubbleForBox(box: BoxF): Bubble? {
            val l = box.left.toInt().coerceIn(0, width - 1)
            val t = box.top.toInt().coerceIn(0, height - 1)
            val r = (box.right.toInt() - 1).coerceIn(0, width - 1)
            val b = (box.bottom.toInt() - 1).coerceIn(0, height - 1)
            if (r < l || b < t) return null
            val step = max(1, min(r - l + 1, b - t + 1) / 12)
            val votes = HashMap<Int, Int>()
            var y = t
            while (y <= b) {
                var x = l
                while (x <= r) {
                    val lab = labels[y * width + x]
                    if (lab > 0 && byLabel.containsKey(lab)) votes[lab] = (votes[lab] ?: 0) + 1
                    x += step
                }
                y += step
            }
            val best = votes.maxByOrNull { it.value }?.key ?: return null
            val bubble = byLabel[best] ?: return null
            return if (bubble.isInterior(box.centerX.toInt(), box.centerY.toInt())) bubble else null
        }
    }

    fun detect(img: GrayImage): Result {
        val w = img.width
        val h = img.height
        val n = w * h
        val labels = IntArray(n)
        val queue = IntArray(n)
        val px = img.pixels

        // Thống kê từng vùng trắng (chỉ số = nhãn).
        val minX = GrowInt(); val minY = GrowInt(); val maxX = GrowInt(); val maxY = GrowInt()
        val count = GrowInt(); val border = GrowInt()
        // Nhãn 0 = "chưa gán", không dùng làm vùng.
        minX.add(0); minY.add(0); maxX.add(0); maxY.add(0); count.add(0); border.add(0)

        var next = 1
        for (start in 0 until n) {
            if (px[start] < whiteThreshold || labels[start] != 0) continue
            val label = next++
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = label
            var lx = Int.MAX_VALUE; var ly = Int.MAX_VALUE; var hx = -1; var hy = -1
            var c = 0
            var touches = false
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                c++
                if (x < lx) lx = x
                if (x > hx) hx = x
                if (y < ly) ly = y
                if (y > hy) hy = y
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touches = true
                if (x > 0) { val q = p - 1; if (labels[q] == 0 && px[q] >= whiteThreshold) { labels[q] = label; queue[tail++] = q } }
                if (x < w - 1) { val q = p + 1; if (labels[q] == 0 && px[q] >= whiteThreshold) { labels[q] = label; queue[tail++] = q } }
                if (y > 0) { val q = p - w; if (labels[q] == 0 && px[q] >= whiteThreshold) { labels[q] = label; queue[tail++] = q } }
                if (y < h - 1) { val q = p + w; if (labels[q] == 0 && px[q] >= whiteThreshold) { labels[q] = label; queue[tail++] = q } }
            }
            minX.add(lx); minY.add(ly); maxX.add(hx); maxY.add(hy); count.add(c); border.add(if (touches) 1 else 0)
        }

        val minArea = max(120, (minAreaRatio * n).toInt())
        val maxArea = maxAreaRatio * n
        val bubbles = ArrayList<Bubble>()
        for (label in 1 until next) {
            if (count[label] < minArea) continue
            // Vùng trắng lớn chạm mép ảnh = lề trang / nền khung tranh, không phải bong bóng.
            if (border[label] == 1 && count[label] > 0.01f * n) continue
            val bl = minX[label]
            val bt = minY[label]
            val bw = maxX[label] - bl + 1
            val bh = maxY[label] - bt + 1
            if (bw < 10 || bh < 10 || bw.toFloat() * bh > maxArea * 2f) continue
            if (bw > 0.5f * w || bh > 0.5f * h) continue
            buildBubble(labels, w, label, bl, bt, bw, bh, maxArea)?.let { bubbles += it }
        }
        return Result(w, h, labels, bubbles)
    }

    private fun buildBubble(labels: IntArray, w: Int, label: Int, bl: Int, bt: Int, bw: Int, bh: Int, maxArea: Float): Bubble? {
        val size = bw * bh
        val outside = BooleanArray(size)
        val queue = IntArray(size)
        var tail = 0
        fun isComp(i: Int): Boolean = labels[(bt + i / bw) * w + bl + i % bw] == label
        fun seed(i: Int) {
            if (!outside[i] && !isComp(i)) { outside[i] = true; queue[tail++] = i }
        }
        for (x in 0 until bw) { seed(x); seed((bh - 1) * bw + x) }
        for (y in 0 until bh) { seed(y * bw); seed(y * bw + bw - 1) }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % bw
            val y = i / bw
            if (x > 0) seed(i - 1)
            if (x < bw - 1) seed(i + 1)
            if (y > 0) seed(i - bw)
            if (y < bh - 1) seed(i + bw)
        }

        val interior = BooleanArray(size)
        val holes = BooleanArray(size)
        var interiorArea = 0
        var holeArea = 0
        var sumX = 0L
        var sumY = 0L
        for (i in 0 until size) {
            val comp = isComp(i)
            val hole = !comp && !outside[i]
            if (comp || hole) {
                interior[i] = true
                interiorArea++
                sumX += i % bw
                sumY += i / bw
            }
            if (hole) {
                holes[i] = true
                holeArea++
            }
        }
        if (interiorArea > maxArea) return null
        if (interiorArea.toFloat() / size < minInteriorFill) return null
        if (holeArea.toFloat() / interiorArea > maxHoleRatio) return null
        if (hasArtworkHole(holes, bw, bh, interiorArea)) return null
        // Bong bóng thoại luôn chứa nét chữ. Vùng trắng kín không có nét nào bên trong (lòng chữ 口, mắt nhân vật…)
        // không phải bong bóng — nếu giữ lại, chúng "cướp phiếu" khi gán dòng chữ vào bong bóng.
        if (holeArea < max(4, (interiorArea * 0.002f).toInt())) return null

        val inner = innerRect(interior, bw, bh, sumX.toFloat() / interiorArea, sumY.toFloat() / interiorArea)
        return Bubble(
            label, bl, bt, bl + bw, bt + bh, interior, holes, interiorArea, holeArea,
            BoxF(bl + inner.left, bt + inner.top, bl + inner.right, bt + inner.bottom)
        )
    }

    /**
     * Trong bong bóng thật, "lỗ" là các nét chữ rời rạc, mỗi nét nhỏ. Nếu có một mảng nét liền lớn
     * (rộng VÀ cao gần nửa vùng, hoặc chiếm > [maxHoleComponentRatio] diện tích) thì đó là hình vẽ bị vùng trắng
     * bao quanh → không phải bong bóng, tuyệt đối không được xóa.
     */
    private fun hasArtworkHole(holes: BooleanArray, bw: Int, bh: Int, interiorArea: Int): Boolean {
        val seen = BooleanArray(holes.size)
        val queue = IntArray(holes.size)
        for (start in holes.indices) {
            if (!holes[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1; var c = 0
            while (head < tail) {
                val i = queue[head++]
                val x = i % bw
                val y = i / bw
                c++
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
                if (x > 0 && holes[i - 1] && !seen[i - 1]) { seen[i - 1] = true; queue[tail++] = i - 1 }
                if (x < bw - 1 && holes[i + 1] && !seen[i + 1]) { seen[i + 1] = true; queue[tail++] = i + 1 }
                if (y > 0 && holes[i - bw] && !seen[i - bw]) { seen[i - bw] = true; queue[tail++] = i - bw }
                if (y < bh - 1 && holes[i + bw] && !seen[i + bw]) { seen[i + bw] = true; queue[tail++] = i + bw }
            }
            val cw = r - l + 1
            val ch = b - t + 1
            if (cw > 0.45f * bw && ch > 0.45f * bh) return true
            if (c > maxHoleComponentRatio * interiorArea) return true
        }
        return false
    }

    /** Thu nhỏ khung dần quanh tâm bong bóng tới khi ≥ 96% diện tích nằm trong bong bóng. */
    private fun innerRect(interior: BooleanArray, bw: Int, bh: Int, cx: Float, cy: Float): BoxF {
        val sat = IntArray((bw + 1) * (bh + 1))
        for (y in 0 until bh) {
            var rowSum = 0
            for (x in 0 until bw) {
                if (interior[y * bw + x]) rowSum++
                sat[(y + 1) * (bw + 1) + x + 1] = sat[y * (bw + 1) + x + 1] + rowSum
            }
        }
        fun coverage(l: Int, t: Int, r: Int, b: Int): Float {
            if (r <= l || b <= t) return 1f
            val s = sat[b * (bw + 1) + r] - sat[t * (bw + 1) + r] - sat[b * (bw + 1) + l] + sat[t * (bw + 1) + l]
            return s.toFloat() / ((r - l) * (b - t))
        }
        var eL = cx
        var eR = bw - cx
        var eT = cy
        var eB = bh - cy
        var rect = BoxF(0f, 0f, bw.toFloat(), bh.toFloat())
        repeat(60) {
            val l = (cx - eL).toInt().coerceIn(0, bw)
            val r = (cx + eR).toInt().coerceIn(0, bw)
            val t = (cy - eT).toInt().coerceIn(0, bh)
            val b = (cy + eB).toInt().coerceIn(0, bh)
            rect = BoxF(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
            if (coverage(l, t, r, b) >= 0.96f) return rect
            eL *= 0.96f; eR *= 0.96f; eT *= 0.96f; eB *= 0.96f
        }
        return rect
    }

    /** Mảng Int tự giãn (tránh boxing của ArrayList<Int>). */
    private class GrowInt {
        private var data = IntArray(256)
        private var size = 0
        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        operator fun get(i: Int): Int = data[i]
    }
}
