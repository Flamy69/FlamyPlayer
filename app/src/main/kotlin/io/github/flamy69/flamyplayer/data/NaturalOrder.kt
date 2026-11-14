package io.github.flamy69.flamyplayer.data

/**
 * Case-insensitive ordering that compares runs of digits by their numeric value,
 * so that "Chapter 2" sorts before "Chapter 10".
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
                var startA = i
                while (startA < a.length && a[startA] == '0') startA++
                var startB = j
                while (startB < b.length && b[startB] == '0') startB++
                var endA = startA
                while (endA < a.length && a[endA].isAsciiDigit()) endA++
                var endB = startB
                while (endB < b.length && b[endB].isAsciiDigit()) endB++

                // Without leading zeros, the longer number is the bigger one.
                val lengthDiff = (endA - startA) - (endB - startB)
                if (lengthDiff != 0) return lengthDiff
                for (k in 0 until endA - startA) {
                    val diff = a[startA + k] - b[startB + k]
                    if (diff != 0) return diff
                }
                i = endA
                j = endB
            } else {
                val diff = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (diff != 0) return diff
                i++
                j++
            }
        }
        val remaining = (a.length - i) - (b.length - j)
        return if (remaining != 0) remaining else a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}

/**
 * Orders directory paths one segment at a time, so that a folder always comes
 * directly before its own subfolders and siblings are in natural order.
 */
object PathOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val segmentsA = a.split('/')
        val segmentsB = b.split('/')
        for (k in 0 until minOf(segmentsA.size, segmentsB.size)) {
            val diff = NaturalOrder.compare(segmentsA[k], segmentsB[k])
            if (diff != 0) return diff
        }
        return segmentsA.size - segmentsB.size
    }
}
