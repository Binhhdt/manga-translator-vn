package vn.mangatranslate.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.mangatranslate.app.core.chatgpt.PixelTransactionPolicy
import vn.mangatranslate.app.core.model.BoxF

class PixelTransactionPolicyTest {
    private val w = 12
    private val h = 12
    private val initial = IntArray(w * h) { 255 }

    @Test fun commitsSuccessfulChangeWithoutTouchingTheSourceArray() {
        val result = initial.copyOf()
        val candidate = initial.copyOf().apply { this[3 * w + 4] = 0 }
        val check = PixelTransactionPolicy.tryCommit(initial, result, candidate, w, h, emptyList())
        assertTrue(check.committed)
        assertEquals(1, check.changedPixels)
        assertEquals(0, result[3 * w + 4])
        assertEquals(255, initial[3 * w + 4])
    }

    @Test fun rejectedCandidateCannotLeaveAnEmptyBubble() {
        val result = initial.copyOf()
        val candidate = initial.copyOf().apply {
            this[2 * w + 2] = 255 - 100 // an allowed change near its own bubble
            this[7 * w + 8] = 255 - 100 // would erase another region's dialogue
        }
        val protected = listOf(BoxF(7f, 6f, 10f, 9f))
        val before = result.copyOf()
        val check = PixelTransactionPolicy.tryCommit(initial, result, candidate, w, h, protected)
        assertFalse(check.committed)
        assertArrayEquals(before, result)
    }

    @Test fun neighboringTranslationsCannotOverwriteEachOther() {
        val result = initial.copyOf()
        val first = initial.copyOf().apply { this[4 * w + 4] = 0 }
        assertTrue(PixelTransactionPolicy.tryCommit(initial, result, first, w, h, emptyList()).committed)
        val beforeSecond = result.copyOf()
        val second = initial.copyOf().apply {
            this[4 * w + 4] = 50
            this[5 * w + 5] = 0
        }
        assertFalse(PixelTransactionPolicy.tryCommit(initial, result, second, w, h, emptyList()).committed)
        assertArrayEquals(beforeSecond, result)
    }

    @Test fun noChangeIsNotCountedAsTranslated() {
        val result = initial.copyOf()
        val check = PixelTransactionPolicy.tryCommit(initial, result, initial.copyOf(), w, h, emptyList())
        assertFalse(check.committed)
        assertEquals(0, check.changedPixels)
    }
}
