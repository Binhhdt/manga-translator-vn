package vn.mangatranslate.app.core.util

/**
 * So sánh tên tệp theo thứ tự tự nhiên: "2.jpg" < "10.jpg", "chap1_p2" < "chap1_p10".
 * Không phân biệt hoa thường.
 */
object NaturalOrderComparator : Comparator<String> {

    private fun Char.isAsciiDigit() = this in '0'..'9'

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
                val si = i
                while (i < a.length && a[i].isAsciiDigit()) i++
                val sj = j
                while (j < b.length && b[j].isAsciiDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                val leadingZeros = (i - si) - (j - sj)
                if (leadingZeros != 0) return leadingZeros
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
