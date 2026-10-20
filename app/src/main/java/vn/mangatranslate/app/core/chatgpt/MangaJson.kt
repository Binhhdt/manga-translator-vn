package vn.mangatranslate.app.core.chatgpt

import org.json.JSONObject

/** Kiểm tra JSON vùng chữ AI trả về. Tọa độ chỉ là ước lượng — không dùng làm pixel chính xác. */
object MangaJson {
    data class Region(val id: String, val type: String, val ja: String, val vi: String, val bbox: List<Double>?, val uncertain: Boolean)
    data class Parsed(val regions: List<Region>, val problems: List<String>)

    fun parse(text: String): Parsed {
        val problems = mutableListOf<String>()
        var t = text.trim()
        // Một số mô hình bọc JSON trong ```json … ```
        if (t.startsWith("```")) {
            t = t.substringAfter('\n').substringBeforeLast("```").trim()
            problems += "JSON được bọc trong ``` (đã gỡ)."
        }
        val root = try {
            JSONObject(t)
        } catch (e: Exception) {
            return Parsed(emptyList(), listOf("Không phải JSON hợp lệ: ${e.message}"))
        }
        val arr = root.optJSONArray("regions") ?: return Parsed(emptyList(), problems + "Thiếu mảng \"regions\".")
        val out = ArrayList<Region>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) { problems += "Phần tử $i không phải đối tượng."; continue }
            val ja = o.optString("ja"); val vi = o.optString("vi")
            if (ja.isBlank()) problems += "Vùng ${i + 1}: thiếu \"ja\"."
            if (vi.isBlank()) problems += "Vùng ${i + 1}: thiếu \"vi\"."
            val b = o.optJSONArray("bbox")
            val bbox = if (b != null && b.length() == 4) (0 until 4).map { b.optDouble(it) } else null
            if (bbox == null) problems += "Vùng ${i + 1}: bbox không đủ 4 số."
            else if (bbox.any { it.isNaN() || it < -0.01 || it > 1.01 }) problems += "Vùng ${i + 1}: bbox ngoài khoảng 0..1."
            out += Region(o.opt("id")?.toString() ?: "${i + 1}", o.optString("type"), ja, vi, bbox, o.optBoolean("uncertain"))
        }
        return Parsed(out, problems)
    }
}
