package vn.mangatranslate.app.core.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Bộ dịch Opus-MT (Helsinki-NLP, Apache-2.0) chạy OFFLINE trong ứng dụng bằng ONNX Runtime.
 * Mô hình int8 (~100 MB) nằm trong assets/mt/<cặp>/ và được chép ra bộ nhớ trong ở lần dùng đầu.
 *
 * Giải mã beam search không KV-cache — đúng thuật toán đã dùng để tạo reference.json khi chuyển đổi
 * mô hình, nên kết quả Kotlin và Python phải trùng nhau (kiểm thử trên trình giả lập).
 */
class MarianTranslator private constructor(dir: File) : Closeable {

    class Output(val pieces: List<String>, val inputIds: LongArray, val outputIds: List<Long>, val text: String)

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val sp: SentencePiece
    private val vocab: HashMap<String, Long>
    private val inverse: HashMap<Long, String>
    private val eos: Long
    private val pad: Long
    private val start: Long
    private val unk: Long

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        encoder = env.createSession(File(dir, "encoder.onnx").absolutePath, opts)
        decoder = env.createSession(File(dir, "decoder.onnx").absolutePath, opts)
        sp = SentencePiece.parse(File(dir, "source.spm").readBytes())
        val vj = JSONObject(File(dir, "vocab.json").readText())
        vocab = HashMap(vj.length() * 2)
        inverse = HashMap(vj.length() * 2)
        for (k in vj.keys()) {
            val id = vj.getLong(k)
            vocab[k] = id
            inverse[id] = k
        }
        val cfg = JSONObject(File(dir, "config.json").readText())
        eos = cfg.getLong("eos_token_id")
        pad = cfg.getLong("pad_token_id")
        start = cfg.optLong("decoder_start_token_id", pad)
        unk = vocab["<unk>"] ?: 1L
    }

    /** Mã hóa nguồn: mảnh SentencePiece → id theo vocab.json, thêm </s>. */
    fun encode(text: String): Pair<List<String>, LongArray> {
        val pieces = sp.encode(text)
        val ids = LongArray(pieces.size + 1)
        pieces.forEachIndexed { i, p -> ids[i] = vocab[p] ?: unk }
        ids[pieces.size] = eos
        return pieces to ids
    }

    /**
     * Dịch bằng beam search ([beams] nhánh; 1 = tham lam). Thuật toán giống hệt export_opus.py:
     * log-softmax, chọn top-k mỗi nhánh (bằng điểm thì id nhỏ trước), sắp xếp ổn định theo điểm,
     * điểm câu hoàn chỉnh = tổng log-xác suất / số token sinh ra (kể cả </s>).
     * Các nhánh cùng độ dài nên được chạy gộp thành một lô trong một lần gọi bộ giải mã.
     */
    @Synchronized
    fun translate(text: String, beams: Int = DEFAULT_BEAMS, maxLen: Int = 128): Output {
        val (pieces, ids) = encode(text)
        val n = ids.size
        val mask = LongArray(n) { 1L }
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, n.toLong())).use { inIds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), longArrayOf(1, n.toLong())).use { inMask ->
                encoder.run(mapOf("input_ids" to inIds, "attention_mask" to inMask)).use { encOut ->
                    val hiddenTensor = encOut.get("last_hidden_state").get() as OnnxTensor
                    val hidden = hiddenTensor.floatBuffer
                    val hiddenSize = hidden.capacity() / n
                    val hiddenArr = FloatArray(hidden.capacity()).also { hidden.get(it) }

                    var hyps = listOf(Hyp(listOf(start), 0.0))
                    val done = ArrayList<Pair<List<Long>, Double>>()
                    for (step in 0 until maxLen) {
                        val k = hyps.size
                        val len = hyps[0].seq.size
                        val logp = runDecoderBatch(hyps, k, len, n, hiddenSize, hiddenArr)
                        val vocabSize = logp.size / k
                        val cand = ArrayList<Hyp>(k * beams)
                        for (h in 0 until k) {
                            for (t in topK(logp, h * vocabSize, vocabSize, beams)) {
                                cand += Hyp(hyps[h].seq + t.toLong(), hyps[h].score + logp[h * vocabSize + t])
                            }
                        }
                        val sorted = cand.sortedByDescending { it.score } // ổn định như list.sort của Python
                        val next = ArrayList<Hyp>(beams)
                        for (c in sorted) {
                            if (c.seq.last() == eos) done += c.seq.subList(1, c.seq.size - 1) to c.score / (c.seq.size - 1)
                            else next += c
                            if (next.size == beams) break
                        }
                        hyps = next
                        if (done.size >= beams || hyps.isEmpty()) break
                    }
                    if (done.isEmpty()) hyps.forEach { done += it.seq.drop(1) to it.score / it.seq.size }
                    val best = done.maxByOrNull { it.second }!!.first
                    return Output(pieces, ids, best, SentencePiece.decode(best.map { inverse[it] ?: "" }))
                }
            }
        }
    }

    private class Hyp(val seq: List<Long>, val score: Double)

    /** Chạy bộ giải mã cho [k] nhánh cùng độ dài; trả về log-softmax (double) của token cuối, [k × vocab]. */
    private fun runDecoderBatch(hyps: List<Hyp>, k: Int, len: Int, n: Int, hiddenSize: Int, hiddenArr: FloatArray): DoubleArray {
        val decIds = LongArray(k * len)
        hyps.forEachIndexed { i, h -> h.seq.forEachIndexed { j, t -> decIds[i * len + j] = t } }
        val hid = FloatArray(k * n * hiddenSize)
        for (i in 0 until k) System.arraycopy(hiddenArr, 0, hid, i * n * hiddenSize, n * hiddenSize)
        val m = LongArray(k * n) { 1L }
        OnnxTensor.createTensor(env, LongBuffer.wrap(decIds), longArrayOf(k.toLong(), len.toLong())).use { tIds ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(hid), longArrayOf(k.toLong(), n.toLong(), hiddenSize.toLong())).use { tHid ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(m), longArrayOf(k.toLong(), n.toLong())).use { tMask ->
                    decoder.run(
                        mapOf("input_ids" to tIds, "encoder_hidden_states" to tHid, "encoder_attention_mask" to tMask),
                        setOf("logits")
                    ).use { r ->
                        val logits = (r.get("logits").get() as OnnxTensor).floatBuffer
                        val vocabSize = logits.capacity() / (k * len)
                        val out = DoubleArray(k * vocabSize)
                        for (i in 0 until k) {
                            val base = (i * len + len - 1) * vocabSize
                            var max = Double.NEGATIVE_INFINITY
                            for (v in 0 until vocabSize) {
                                val x = if (v.toLong() == pad) Double.NEGATIVE_INFINITY else logits.get(base + v).toDouble()
                                out[i * vocabSize + v] = x
                                if (x > max) max = x
                            }
                            var sum = 0.0
                            for (v in 0 until vocabSize) sum += kotlin.math.exp(out[i * vocabSize + v] - max)
                            val lse = max + kotlin.math.ln(sum)
                            for (v in 0 until vocabSize) out[i * vocabSize + v] -= lse
                        }
                        return out
                    }
                }
            }
        }
    }

    /** Chỉ số của [k] giá trị lớn nhất trong đoạn [off, off+len); bằng nhau thì chỉ số nhỏ trước. */
    private fun topK(a: DoubleArray, off: Int, len: Int, k: Int): IntArray {
        val idx = IntArray(k) { -1 }
        val vals = DoubleArray(k) { Double.NEGATIVE_INFINITY }
        for (v in 0 until len) {
            val x = a[off + v]
            if (idx[k - 1] != -1 && x <= vals[k - 1]) continue
            var pos = k - 1
            while (pos > 0 && (idx[pos - 1] == -1 || x > vals[pos - 1])) pos--
            for (q in k - 1 downTo pos + 1) { idx[q] = idx[q - 1]; vals[q] = vals[q - 1] }
            idx[pos] = v
            vals[pos] = x
        }
        return idx.filter { it >= 0 }.toIntArray()
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }

    companion object {
        const val DEFAULT_BEAMS = 4
        private val FILES = listOf("encoder.onnx", "decoder.onnx", "source.spm", "vocab.json", "config.json")

        /** Có mô hình trong assets không (bản build có thể không kèm mô hình). */
        fun available(context: Context, assetDir: String): Boolean = try {
            context.assets.list(assetDir)?.toSet()?.containsAll(FILES) == true
        } catch (e: Exception) {
            false
        }

        /** Chép mô hình từ assets ra bộ nhớ trong (một lần), rồi nạp. */
        fun fromAssets(context: Context, assetDir: String): MarianTranslator {
            val dir = File(context.filesDir, assetDir)
            dir.mkdirs()
            // Chép lại khi ứng dụng được cập nhật (mô hình trong APK mới có thể khác).
            @Suppress("DEPRECATION")
            val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
            val marker = File(dir, ".version")
            val fresh = marker.exists() && marker.readText() == stamp && FILES.all { File(dir, it).exists() }
            if (!fresh) {
                for (f in FILES) {
                    val tmp = File(dir, "$f.tmp")
                    context.assets.open("$assetDir/$f").use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                    val target = File(dir, f)
                    target.delete()
                    if (!tmp.renameTo(target)) throw IllegalStateException("Không chép được mô hình $f")
                }
                marker.writeText(stamp)
            }
            return MarianTranslator(dir)
        }
    }
}
