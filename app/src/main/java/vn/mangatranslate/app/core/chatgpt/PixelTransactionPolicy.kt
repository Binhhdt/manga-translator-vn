package vn.mangatranslate.app.core.chatgpt

import vn.mangatranslate.app.core.model.BoxF

/**
 * Pixel-level, all-or-nothing commit for one translated region.
 *
 * The candidate is always rendered from the ORIGINAL page, never from previously
 * modified pixels. Before committing a single pixel, reject changes to any other
 * source region or an earlier accepted translation. Failed attempts therefore
 * cannot leave a half-erased or empty speech bubble.
 *
 * Pure Kotlin so the contract is testable without Android/Bitmap.
 */
object PixelTransactionPolicy {
    data class Result(val committed: Boolean, val changedPixels: Int, val reason: String)

    fun tryCommit(
        original: IntArray,
        composite: IntArray,
        candidate: IntArray,
        width: Int,
        height: Int,
        protectedBoxes: List<BoxF>
    ): Result {
        require(width > 0 && height > 0 && original.size == width * height)
        require(composite.size == original.size && candidate.size == original.size)
        var changed = 0
        for (i in original.indices) {
            if (candidate[i] == original[i]) continue
            val x = (i % width).toFloat() + 0.5f
            val y = (i / width).toFloat() + 0.5f
            if (composite[i] != original[i]) {
                return Result(false, 0, "Chồng lên vùng dịch đã chèn trước đó")
            }
            if (protectedBoxes.any { x >= it.left && x < it.right && y >= it.top && y < it.bottom }) {
                return Result(false, 0, "Đụng vào chữ gốc của vùng khác")
            }
            changed++
        }
        if (changed == 0) return Result(false, 0, "Không tạo ra điểm ảnh mới")
        // Commit only after the WHOLE candidate passed the checks.
        for (i in original.indices) {
            if (candidate[i] != original[i]) composite[i] = candidate[i]
        }
        return Result(true, changed, "")
    }
}
