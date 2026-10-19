package vn.mangatranslate.app

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.mangatranslate.app.core.translate.SentencePiece
import java.io.ByteArrayOutputStream

class SentencePieceTest {

    /** Tạo tệp .spm (protobuf) nhỏ để kiểm thử bộ đọc + Viterbi. */
    private fun model(vararg pieces: Pair<String, Float>, controls: List<String> = listOf("<unk>", "<s>", "</s>")): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) {
            var x = v
            while (true) {
                if (x and 0x7FL.inv() == 0L) { out.write(x.toInt()); return }
                out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
            }
        }
        fun piece(p: String, score: Float, type: Int) {
            val inner = ByteArrayOutputStream()
            val pb = p.toByteArray(Charsets.UTF_8)
            fun iv(v: Int) { var x = v; while (true) { if (x and 0x7F.inv() == 0) { inner.write(x); break }; inner.write((x and 0x7F) or 0x80); x = x ushr 7 } }
            inner.write(0x0A); iv(pb.size); inner.write(pb)
            inner.write(0x15); val bits = score.toRawBits()
            for (i in 0 until 4) inner.write((bits ushr (8 * i)) and 0xFF)
            inner.write(0x18); iv(type)
            val bytes = inner.toByteArray()
            out.write(0x0A); varint(bytes.size.toLong()); out.write(bytes)
        }
        controls.forEach { piece(it, 0f, if (it == "<unk>") 2 else 3) }
        pieces.forEach { (p, s) -> piece(p, s, 1) }
        return out.toByteArray()
    }

    @Test
    fun viterbiPrefersHigherScoringSegmentation() {
        val sp = SentencePiece.parse(model("▁" to -5f, "▁黒崎" to -3f, "黒" to -6f, "崎" to -6f, "▁黒" to -4f))
        assertEquals(listOf("▁黒崎"), sp.encode("黒崎"))
    }

    @Test
    fun unknownCharactersBecomeSinglePieces() {
        val sp = SentencePiece.parse(model("▁" to -5f, "大" to -6f))
        assertEquals(listOf("▁", "大", "丈"), sp.encode("大丈"))
    }

    @Test
    fun normalizesFullWidthAndSpaces() {
        val sp = SentencePiece.parse(model("▁" to -5f, "▁GK" to -2f, "G" to -6f, "K" to -6f, "!" to -3f))
        assertEquals(listOf("▁GK", "!"), sp.encode("ＧＫ！"))
        assertEquals(listOf("▁GK", "▁GK"), sp.encode("  GK   GK "))
    }

    @Test
    fun decodeJoinsPieces() {
        assertEquals("Don't touch me.", SentencePiece.decode(listOf("▁Don", "'", "t", "▁touch", "▁me", ".")))
    }
}

class SentencePieceSkipTest {
    /** Tệp .spm thật có trainer_spec (trường 2, kiểu độ dài) phải bỏ qua đúng — lỗi cũ: đọc lệch, "kiểu 7". */
    @Test
    fun skipsLengthDelimitedFieldsCorrectly() {
        val out = java.io.ByteArrayOutputStream()
        // trường 2 (trainer_spec), độ dài 300 (varint 2 byte), nội dung rác
        out.write(0x12); out.write(0xAC); out.write(0x02); out.write(ByteArray(300) { 7 })
        // một piece "▁a" điểm -1
        val inner = java.io.ByteArrayOutputStream()
        val pb = "▁a".toByteArray(Charsets.UTF_8)
        inner.write(0x0A); inner.write(pb.size); inner.write(pb)
        inner.write(0x15); val bits = (-1f).toRawBits(); for (i in 0 until 4) inner.write((bits ushr (8 * i)) and 0xFF)
        inner.write(0x18); inner.write(1)
        val ib = inner.toByteArray()
        out.write(0x0A); out.write(ib.size); out.write(ib)
        val sp = SentencePiece.parse(out.toByteArray())
        assertEquals(listOf("▁a"), sp.encode("a"))
    }
}
