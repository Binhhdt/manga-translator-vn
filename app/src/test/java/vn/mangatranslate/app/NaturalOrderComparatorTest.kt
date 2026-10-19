package vn.mangatranslate.app

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.mangatranslate.app.core.util.NaturalOrderComparator

class NaturalOrderComparatorTest {

    @Test
    fun sortsNumbersNaturally() {
        val input = listOf("10.jpg", "2.jpg", "1.jpg", "3.jpg", "11.jpg")
        assertEquals(listOf("1.jpg", "2.jpg", "3.jpg", "10.jpg", "11.jpg"), input.sortedWith(NaturalOrderComparator))
    }

    @Test
    fun handlesPrefixesAndLeadingZeros() {
        val input = listOf("chap1_p10.png", "chap1_p2.png", "chap1_p002.png", "Chap1_p1.png", "chap2_p1.png")
        assertEquals(
            listOf("Chap1_p1.png", "chap1_p2.png", "chap1_p002.png", "chap1_p10.png", "chap2_p1.png"),
            input.sortedWith(NaturalOrderComparator)
        )
    }

    @Test
    fun handlesVeryLongNumbers() {
        val input = listOf("page_100000000000000000002.jpg", "page_99.jpg")
        assertEquals(listOf("page_99.jpg", "page_100000000000000000002.jpg"), input.sortedWith(NaturalOrderComparator))
    }
}
