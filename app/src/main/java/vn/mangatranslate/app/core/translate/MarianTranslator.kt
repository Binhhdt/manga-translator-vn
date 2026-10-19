package vn.mangatranslate.app.core.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer

/**
 * Bộ dịch Opus-MT (Helsinki-NLP, Apache-2.0) chạy OFFLINE trong ứng dụng bằng ONNX Runtime.
 * Mô hình int8 (~100 MB) nằm trong assets/mt/<cặp>/ và được chép ra bộ nhớ trong ở lần dùng đầu.
 *
 * Giải mã tham lam (greedy), không KV-cache — đúng thuật toán đã dùng để tạo reference.json khi chuyển đổi
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

    @Synchronized
    fun translate(text: String, maxLen: Int = 128): Output {
        val (pieces, ids) = encode(text)
        val n = ids.size
        val shape = longArrayOf(1, n.toLong())
        val mask = LongArray(n) { 1L }
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { inIds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { inMask ->
                encoder.run(mapOf("input_ids" to inIds, "attention_mask" to inMask)).use { encOut ->
                    val hidden = encOut.get("last_hidden_state").get() as OnnxTensor
                    val out = ArrayList<Long>()
                    out += start
                    while (out.size <= maxLen) {
                        val next = OnnxTensor.createTensor(env, LongBuffer.wrap(out.toLongArray()), longArrayOf(1, out.size.toLong())).use { decIds ->
                            decoder.run(
                                mapOf("input_ids" to decIds, "encoder_hidden_states" to hidden, "encoder_attention_mask" to inMask),
                                setOf("logits")
                            ).use { r ->
                                val logits = (r.get("logits").get() as OnnxTensor).floatBuffer
                                val vocabSize = logits.capacity() / out.size
                                val base = (out.size - 1) * vocabSize
                                var best = -1
                                var bestV = Float.NEGATIVE_INFINITY
                                for (v in 0 until vocabSize) {
                                    if (v.toLong() == pad) continue // giống bad_words_ids của Marian
                                    val x = logits.get(base + v)
                                    if (x > bestV) { bestV = x; best = v }
                                }
                                best.toLong()
                            }
                        }
                        if (next == eos) break
                        out += next
                    }
                    val outIds = out.drop(1)
                    val textOut = SentencePiece.decode(outIds.map { inverse[it] ?: "" })
                    return Output(pieces, ids, outIds, textOut)
                }
            }
        }
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }

    companion object {
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
