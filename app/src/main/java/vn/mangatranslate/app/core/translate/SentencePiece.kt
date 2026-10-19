package vn.mangatranslate.app.core.translate

import java.text.Normalizer

/**
 * Bộ tách từ SentencePiece (thuật toán Unigram + Viterbi) đọc trực tiếp tệp .spm (protobuf) của Opus-MT.
 * Thuần Kotlin, không cần thư viện native. Kết quả được đối chiếu với thư viện sentencepiece của Python
 * (reference.json sinh ra khi chuyển đổi mô hình).
 */
class SentencePiece private constructor(
    private val pieces: HashMap<String, Float>,
    private val maxPieceLen: Int,
    private val unkScore: Float,
    private val addDummyPrefix: Boolean,
    private val removeExtraWhitespaces: Boolean
) {

    /** Tách văn bản thành các mảnh (piece). Ký tự không có trong từ điển → mảnh 1 ký tự (sẽ thành <unk>). */
    fun encode(text: String): List<String> {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        if (removeExtraWhitespaces) t = t.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return emptyList()
        t = t.replace(" ", SPACE)
        if (addDummyPrefix) t = SPACE + t
        val cps = t.codePoints().toArray()
        val n = cps.size
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        best[0] = 0f
        for (end in 1..n) {
            val startMin = maxOf(0, end - maxPieceLen)
            for (start in end - 1 downTo startMin) {
                if (best[start] == Float.NEGATIVE_INFINITY) continue
                val piece = String(cps, start, end - start)
                val s = pieces[piece] ?: if (end - start == 1) unkScore else continue
                val cand = best[start] + s
                if (cand > best[end]) {
                    best[end] = cand
                    from[end] = start
                }
            }
        }
        val out = ArrayList<String>()
        var end = n
        while (end > 0) {
            val start = from[end]
            if (start < 0) { // không thể xảy ra vì luôn có mảnh 1 ký tự
                out += String(cps, end - 1, 1); end--; continue
            }
            out += String(cps, start, end - start)
            end = start
        }
        out.reverse()
        return out
    }

    companion object {
        const val SPACE = "▁"
        private const val TYPE_NORMAL = 1
        private const val TYPE_USER_DEFINED = 4

        /** Đọc ModelProto: pieces (trường 1) và normalizer_spec (trường 3). */
        fun parse(bytes: ByteArray): SentencePiece {
            val pieces = HashMap<String, Float>()
            var minScore = Float.MAX_VALUE
            var maxLen = 1
            var addDummy = true
            var removeWs = true
            val r = ProtoReader(bytes, 0, bytes.size)
            while (r.hasMore()) {
                val (field, wire) = r.tag()
                when {
                    field == 1 && wire == 2 -> {
                        val sub = r.sub()
                        var piece = ""
                        var score = 0f
                        var type = TYPE_NORMAL
                        while (sub.hasMore()) {
                            val (f, w) = sub.tag()
                            when {
                                f == 1 && w == 2 -> piece = String(sub.bytes(), Charsets.UTF_8)
                                f == 2 && w == 5 -> score = Float.fromBits(sub.fixed32())
                                f == 3 && w == 0 -> type = sub.varint().toInt()
                                else -> sub.skip(w)
                            }
                        }
                        if (type == TYPE_NORMAL || type == TYPE_USER_DEFINED) {
                            pieces[piece] = score
                            if (score < minScore) minScore = score
                            maxLen = maxOf(maxLen, piece.codePointCount(0, piece.length))
                        }
                    }
                    field == 3 && wire == 2 -> {
                        val sub = r.sub()
                        while (sub.hasMore()) {
                            val (f, w) = sub.tag()
                            when {
                                f == 3 && w == 0 -> addDummy = sub.varint() != 0L
                                f == 4 && w == 0 -> removeWs = sub.varint() != 0L
                                else -> sub.skip(w)
                            }
                        }
                    }
                    else -> r.skip(wire)
                }
            }
            require(pieces.isNotEmpty()) { "Tệp SentencePiece không hợp lệ" }
            // Giống sentencepiece: ký tự lạ bị phạt 10 điểm dưới điểm thấp nhất.
            return SentencePiece(pieces, maxLen, minScore - 10f, addDummy, removeWs)
        }

        /** Ghép mảnh đầu ra thành văn bản (bộ giải mã của SentencePiece). */
        fun decode(pieces: List<String>): String = pieces.joinToString("").replace(SPACE, " ").trim()
    }

    /** Bộ đọc protobuf tối thiểu. */
    private class ProtoReader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
        fun hasMore() = pos < end
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        fun tag(): Pair<Int, Int> {
            val t = varint().toInt()
            return (t ushr 3) to (t and 7)
        }
        fun fixed32(): Int {
            val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                ((buf[pos + 2].toInt() and 0xFF) shl 16) or ((buf[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        fun bytes(): ByteArray {
            val len = varint().toInt()
            val out = buf.copyOfRange(pos, pos + len)
            pos += len
            return out
        }
        fun sub(): ProtoReader {
            val len = varint().toInt()
            val r = ProtoReader(buf, pos, pos + len)
            pos += len
            return r
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += varint().toInt()
                5 -> pos += 4
                else -> error("Kiểu protobuf không hỗ trợ: $wire")
            }
        }
    }
}
