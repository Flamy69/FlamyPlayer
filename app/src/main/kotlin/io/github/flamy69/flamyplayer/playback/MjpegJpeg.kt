package io.github.flamy69.flamyplayer.playback

import java.io.ByteArrayOutputStream

/**
 * Turns one MJPEG sample into a JPEG byte array that [android.graphics.BitmapFactory] can decode.
 *
 * A frame is already a JPEG picture. Two things that show up in AVI files still have to be
 * repaired, or the JPEG decoder rejects the frame: padding before the start-of-image marker, and
 * Huffman tables that the file states once and then leaves out. The tables defined by the JPEG
 * specification (section K.3) are the ones those files were encoded with.
 */
internal object MjpegJpeg {

    class Tables {
        var quant: ByteArray? = null
        var huffman: ByteArray? = null

        fun clear() {
            quant = null
            huffman = null
        }
    }

    class Picture(val jpeg: ByteArray, val width: Int, val height: Int)

    /** A JPEG the platform decoder can read, or null when [data] holds no picture. */
    fun prepare(data: ByteArray, offset: Int, length: Int, tables: Tables): Picture? {
        if (length < 4 || offset < 0 || offset + length > data.size) return null
        val end = offset + length
        val soi = findSoi(data, offset, end)
        if (soi < 0) return null
        val frame = readFrame(data, soi, end, acceptTruncated = true) ?: return null
        if (frame.width <= 0 || frame.height <= 0) return null

        if (frame.quant.isNotEmpty()) tables.quant = concat(frame.quant)
        if (frame.huffman.isNotEmpty()) tables.huffman = concat(frame.huffman)

        val missingQuant = frame.quant.isEmpty()
        val missingHuffman = frame.huffman.isEmpty()
        if (!missingQuant && !missingHuffman && frame.endsWithEoi) {
            return Picture(data.copyOfRange(soi, frame.limit), frame.width, frame.height)
        }

        // Whichever table this frame leaves out was either sent with an earlier frame or, for
        // Huffman, is the standard table. Quantization has no such default.
        val quant = if (missingQuant) tables.quant ?: return null else concat(frame.quant)
        val huffman = if (missingHuffman) tables.huffman ?: STANDARD_HUFFMAN else concat(frame.huffman)
        val out = ByteArrayOutputStream(frame.limit - soi + quant.size + huffman.size + 2)
        out.write(SOI)
        out.write(quant)
        frame.beforeSos.forEach { out.write(it) }
        out.write(huffman)
        out.write(frame.tail)
        if (!frame.endsWithEoi) out.write(EOI)
        return Picture(out.toByteArray(), frame.width, frame.height)
    }

    /**
     * How many complete JPEG pictures [data] holds. A picture that runs off the end of the array
     * is not counted: the caller is still waiting for the rest of it.
     */
    fun frameCount(data: ByteArray): Int {
        var count = 0
        var pos = 0
        while (pos + 1 < data.size) {
            val soi = findSoi(data, pos, data.size)
            if (soi < 0) break
            val limit = completeEnd(data, soi, data.size)
            if (limit < 0) break
            count++
            pos = limit
        }
        return count
    }

    /** Index of the first start-of-image marker at or after [from], or -1. */
    fun findSoi(data: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i + 1 < end) {
            if (data[i] == MARKER && (data[i + 1].toInt() and 0xFF) == SOI_BYTE) return i
            i++
        }
        return -1
    }

    /**
     * Index just past the end-of-image marker of the picture that starts at [soi], or -1 when the
     * picture is not complete inside [end].
     */
    fun completeEnd(data: ByteArray, soi: Int, end: Int): Int {
        val frame = readFrame(data, soi, end, acceptTruncated = false) ?: return -1
        return if (frame.endsWithEoi) frame.limit else -1
    }

    private class Frame(
        val limit: Int,
        val width: Int,
        val height: Int,
        val quant: List<ByteArray>,
        val huffman: List<ByteArray>,
        val beforeSos: List<ByteArray>,
        val tail: ByteArray,
        val endsWithEoi: Boolean,
    )

    private fun readFrame(data: ByteArray, soi: Int, end: Int, acceptTruncated: Boolean): Frame? {
        if (soi < 0 || soi + 1 >= end || data[soi] != MARKER || (data[soi + 1].toInt() and 0xFF) != SOI_BYTE) return null
        var pos = soi + 2
        var width = 0
        var height = 0
        val quant = ArrayList<ByteArray>()
        val huffman = ArrayList<ByteArray>()
        val beforeSos = ArrayList<ByteArray>()
        while (pos + 1 < end) {
            if (data[pos] != MARKER) return null
            while (pos + 1 < end && data[pos + 1] == MARKER) pos++
            val markerStart = pos
            pos++
            if (pos >= end) return if (acceptTruncated) null else null
            val marker = data[pos].toInt() and 0xFF
            pos++
            if (marker == EOI_BYTE) return null
            if (marker == SOI_BYTE || marker == 0x00 || marker == 0x01 || marker in RST_FIRST..RST_LAST) continue
            if (pos + 1 >= end) return null
            val segmentLength = u16(data, pos)
            if (segmentLength < 2) return null
            if (pos + segmentLength > end) return null
            if (marker == SOS) {
                val entropy = pos + segmentLength
                val eoi = entropyEnd(data, entropy, end)
                val tailEnd = if (eoi >= 0) eoi else if (acceptTruncated) end else return null
                if (width <= 0 || height <= 0) return null
                return Frame(
                    limit = tailEnd,
                    width = width,
                    height = height,
                    quant = quant,
                    huffman = huffman,
                    beforeSos = beforeSos,
                    tail = data.copyOfRange(markerStart, tailEnd),
                    endsWithEoi = eoi >= 0,
                )
            }
            val segment = data.copyOfRange(markerStart, pos + segmentLength)
            if (marker == DQT) quant += segment
            else if (marker == DHT) huffman += segment
            else beforeSos += segment
            if (marker == SOF0 || marker == SOF1 || marker == SOF2) {
                if (segmentLength < 8) return null
                height = u16(data, pos + 3)
                width = u16(data, pos + 5)
            }
            pos += segmentLength
        }
        return null
    }

    /** Index just past an end-of-image marker, or -1 when the scan runs off [end]. */
    private fun entropyEnd(data: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i + 1 < end) {
            if (data[i] != MARKER) {
                i++
                continue
            }
            val next = data[i + 1].toInt() and 0xFF
            when {
                next == 0x00 || next in RST_FIRST..RST_LAST -> i += 2
                next == 0xFF -> i++
                next == EOI_BYTE -> return i + 2
                else -> return -1
            }
        }
        return -1
    }

    private fun u16(data: ByteArray, pos: Int): Int =
        ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var pos = 0
        for (part in parts) {
            part.copyInto(out, pos)
            pos += part.size
        }
        return out
    }

    private const val MARKER = 0xFF.toByte()
    private const val SOI_BYTE = 0xD8
    private const val EOI_BYTE = 0xD9
    private const val SOF0 = 0xC0
    private const val SOF1 = 0xC1
    private const val SOF2 = 0xC2
    private const val DHT = 0xC4
    private const val SOS = 0xDA
    private const val DQT = 0xDB
    private const val RST_FIRST = 0xD0
    private const val RST_LAST = 0xD7

    private val SOI = byteArrayOf(MARKER, SOI_BYTE.toByte())
    private val EOI = byteArrayOf(MARKER, EOI_BYTE.toByte())

    /**
     * The Huffman tables from JPEG specification K.3, as one DHT segment. FFmpeg's default MJPEG
     * encoder and the AVI files it matches use these, and omit them from later frames.
     * Built after the count and value arrays below; those are what [huffmanSegment] reads.
     */
    private val STANDARD_HUFFMAN: ByteArray by lazy { huffmanSegment() }

    private fun huffmanSegment(): ByteArray {
        val body = ByteArrayOutputStream()
        fun table(classAndId: Int, counts: IntArray, values: IntArray) {
            body.write(classAndId)
            counts.forEach { body.write(it) }
            values.forEach { body.write(it) }
        }
        table(0x00, DC_LUMA_COUNTS, DC_VALUES)
        table(0x10, AC_LUMA_COUNTS, AC_LUMA_VALUES)
        table(0x01, DC_CHROMA_COUNTS, DC_VALUES)
        table(0x11, AC_CHROMA_COUNTS, AC_CHROMA_VALUES)
        val payload = body.toByteArray()
        val length = payload.size + 2
        val out = ByteArray(payload.size + 4)
        out[0] = MARKER
        out[1] = DHT.toByte()
        out[2] = (length shr 8).toByte()
        out[3] = length.toByte()
        payload.copyInto(out, 4)
        return out
    }

    private val DC_VALUES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

    /** Code-length counts for lengths 1..16. Index 0 of the JPEG tables is unused and omitted. */
    private val DC_LUMA_COUNTS = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
    private val DC_CHROMA_COUNTS = intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
    private val AC_LUMA_COUNTS = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d)
    private val AC_CHROMA_COUNTS = intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)

    private val AC_LUMA_VALUES = intArrayOf(
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12,
        0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
        0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08,
        0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
        0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16,
        0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39,
        0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
        0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
        0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
        0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79,
        0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
        0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98,
        0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
        0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6,
        0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
        0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4,
        0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
        0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea,
        0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
        0xf9, 0xfa,
    )

    private val AC_CHROMA_VALUES = intArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21,
        0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
        0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91,
        0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
        0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34,
        0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
        0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38,
        0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
        0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
        0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
        0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78,
        0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
        0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96,
        0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
        0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4,
        0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
        0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2,
        0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
        0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9,
        0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
        0xf9, 0xfa,
    )
}
