package vn.mangatranslate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.mangatranslate.app.core.image.CropMath
import vn.mangatranslate.app.core.model.BoxF

/** Kiểm thử lỗi "InputImage width and height should be at least 32!". */
class CropMathTest {

    private val minSide = CropMath.MLKIT_MIN_SIDE

    @Test
    fun normalBoxIsUnchanged() {
        val r = CropMath.safeCrop(BoxF(100f, 200f, 300f, 450f), 1000, 1500)!!
        assertEquals(CropMath.IntRect(100, 200, 300, 450), r)
    }

    @Test
    fun tinyBoxIsExpandedAroundCenterToAtLeast32() {
        val r = CropMath.safeCrop(BoxF(100f, 100f, 110f, 108f), 1000, 1500)!!
        assertTrue(r.width >= minSide && r.height >= minSide)
        // Tâm gần như giữ nguyên.
        assertTrue(kotlin.math.abs((r.left + r.right) / 2 - 105) <= 1)
    }

    @Test
    fun negativeCoordinatesAreClampedAndStillMinSize() {
        val r = CropMath.safeCrop(BoxF(-5f, -7f, 10f, 12f), 1000, 1500)!!
        assertEquals(0, r.left)
        assertEquals(0, r.top)
        assertTrue(r.width >= minSide && r.height >= minSide)
    }

    @Test
    fun boxAtRightBottomEdgeShiftsInside() {
        val r = CropMath.safeCrop(BoxF(995f, 1495f, 1003f, 1510f), 1000, 1500)!!
        assertEquals(1000, r.right)
        assertEquals(1500, r.bottom)
        assertTrue(r.width >= minSide && r.height >= minSide)
    }

    @Test
    fun zeroWidthBoxBecomesValid() {
        val r = CropMath.safeCrop(BoxF(50f, 50f, 50f, 80f), 1000, 1500)!!
        assertTrue(r.width >= minSide && r.height >= minSide)
    }

    @Test
    fun invalidBoxesAreRejected() {
        assertNull(CropMath.safeCrop(BoxF(2000f, 10f, 2100f, 50f), 1000, 1500)) // ngoài ảnh
        assertNull(CropMath.safeCrop(BoxF(-100f, -100f, -10f, -10f), 1000, 1500))
        assertNull(CropMath.safeCrop(BoxF(Float.NaN, 0f, 10f, 10f), 1000, 1500))
        assertNull(CropMath.safeCrop(BoxF(0f, 0f, 10f, 10f), 0, 0))
    }

    @Test
    fun imageSmallerThan32TakesWholeImage() {
        val r = CropMath.safeCrop(BoxF(2f, 2f, 5f, 5f), 20, 18)!!
        assertEquals(CropMath.IntRect(0, 0, 20, 18), r)
    }

    @Test
    fun planUpscalesNarrowVerticalColumn() {
        // Cột chữ dọc mảnh 20×300 px.
        val p = CropMath.inputPlan(20, 300, desiredScale = 1f)
        assertTrue(p.outW >= minSide && p.outH >= minSide)
        assertEquals(0, p.padX)
    }

    @Test
    fun planPadsWhenUpscalingWouldBeTooLarge() {
        // Dải cực mảnh 5×4000: phóng đủ 32 sẽ thành 25600 px → đệm thay vì phóng.
        val p = CropMath.inputPlan(5, 4000, desiredScale = 1f)
        assertTrue(p.outW >= minSide)
        assertTrue(p.outH <= CropMath.MAX_SIDE)
        assertTrue(p.padX > 0)
    }

    @Test
    fun planKeepsLargePagesAndCapsUpscale() {
        val page = CropMath.inputPlan(3000, 4800, desiredScale = 1f)
        assertEquals(1f, page.scale)
        assertTrue(!page.needsWork)
        val capped = CropMath.inputPlan(2000, 1500, desiredScale = 4f)
        assertTrue(capped.scaledW <= CropMath.MAX_SIDE)
        val tiny = CropMath.inputPlan(10, 10, desiredScale = 1f)
        assertTrue(tiny.outW >= minSide && tiny.outH >= minSide)
        val bad = CropMath.inputPlan(40, 40, desiredScale = Float.NaN)
        assertEquals(1f, bad.scale)
    }

    @Test
    fun coordinatesMapBackThroughPadding() {
        // Mô phỏng quy đổi trong OCRProcessor: (x - đệm) × (rộng gốc / rộng sau phóng).
        val w = 5
        val p = CropMath.inputPlan(w, 4000, desiredScale = 1f)
        val sx = w.toFloat() / p.scaledW
        val leftInInput = p.padX.toFloat()
        val rightInInput = (p.padX + p.scaledW).toFloat()
        assertEquals(0f, (leftInInput - p.padX) * sx, 0.001f)
        assertEquals(w.toFloat(), (rightInInput - p.padX) * sx, 0.001f)
    }
}
