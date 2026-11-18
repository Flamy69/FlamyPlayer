package io.github.flamy69.flamyplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.exoplayer.RendererCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import javax.imageio.ImageIO
import kotlin.math.min

/**
 * The pictures below were encoded by FFmpeg's MJPEG encoder with the standard Huffman tables.
 * [BitmapFactory] is a stub in JVM unit tests, so the pixels are checked with the JDK's JPEG
 * decoder on the same bytes the renderer passes to BitmapFactory. One test also runs
 * [MjpegVideoDecoder] and only accepts a result that got as far as that call.
 */
@OptIn(UnstableApi::class)
class MjpegTest {

    private val red = resource("red.jpg")
    private val blue = resource("blue.jpg")

    @Test
    fun bitmapFactoryBytesDecodeWhenHuffmanTablesWereLeftOut() {
        val picture = MjpegJpeg.prepare(strip(red, DHT), 0, strip(red, DHT).size, MjpegJpeg.Tables())
        assertNotNull(picture)
        val image = readJpeg(picture!!.jpeg)
        assertEquals(32, image.width)
        assertEquals(24, image.height)
        assertTrue(isRed(image.getRGB(16, 12)))
    }

    @Test
    fun laterFrameReusesTablesFromTheFirst() {
        val tables = MjpegJpeg.Tables()
        assertNotNull(MjpegJpeg.prepare(red, 0, red.size, tables))
        val bare = strip(blue, DHT, DQT)
        val picture = MjpegJpeg.prepare(bare, 0, bare.size, tables)
        assertNotNull(picture)
        assertTrue(isBlue(readJpeg(picture!!.jpeg).getRGB(16, 12)))
    }

    @Test
    fun extractorReadsARawMotionJpegStream() {
        val stream = red + byteArrayOf(0, 0) + blue
        val extractor = MjpegExtractor()
        val input = input(stream)
        assertTrue(extractor.sniff(input))
        val samples = readAll(extractor, input)
        assertEquals(2, samples.size)
        assertTrue(isRed(readJpeg(samples[0]).getRGB(16, 12)))
        assertTrue(isBlue(readJpeg(samples[1]).getRGB(16, 12)))
        assertEquals(MimeTypes.VIDEO_MJPEG, samplesMime)
    }

    @Test
    fun oneJpegIsNotAFilmUnlessTheNameSaysSo() {
        assertFalse(MjpegExtractor().sniff(input(red)))
        assertTrue(MjpegExtractor(allowSingleFrame = true).sniff(input(red)))
    }

    @Test
    fun rendererClaimsMjpegAndLeavesOtherCodecsAlone() {
        val renderer = MjpegRenderer(0, null, null, 0)
        val mjpeg = renderer.supportsFormat(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_MJPEG).build())
        val h264 = renderer.supportsFormat(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(mjpeg))
        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, RendererCapabilities.getFormatSupport(h264))
    }

    @Test
    fun decoderHandsThePictureToTheJpegCodec() {
        val decoder = MjpegVideoDecoder()
        try {
            val inputBuffer = decoder.dequeueInputBuffer()
            assertNotNull(inputBuffer)
            inputBuffer!!.ensureSpaceForWrite(red.size)
            inputBuffer.data!!.put(red).flip()
            decoder.queueInputBuffer(inputBuffer)
            val deadline = System.currentTimeMillis() + 5_000
            var output: MjpegOutputBuffer? = null
            var failure: MjpegDecoderException? = null
            while (System.currentTimeMillis() < deadline && output == null && failure == null) {
                try {
                    output = decoder.dequeueOutputBuffer()
                } catch (error: MjpegDecoderException) {
                    failure = error
                }
                if (output == null && failure == null) Thread.sleep(10)
            }
            if (output?.bitmap != null) {
                assertEquals(32, output.width)
                assertEquals(24, output.height)
            } else {
                assertNotNull(failure)
                assertFalse(failure!!.message.orEmpty().contains("Not a JPEG"))
            }
        } finally {
            decoder.release()
        }
    }

    private var samplesMime: String? = null

    private fun readAll(extractor: MjpegExtractor, input: ExtractorInput): List<ByteArray> {
        val samples = ArrayList<ByteArray>()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                private val bytes = ByteArrayOutputStream()

                override fun format(format: Format) {
                    samplesMime = format.sampleMimeType
                }

                override fun sampleData(
                    input: DataReader,
                    length: Int,
                    allowEndOfInput: Boolean,
                    sampleDataPart: Int,
                ): Int {
                    val chunk = ByteArray(length)
                    val read = input.read(chunk, 0, length)
                    if (read > 0) bytes.write(chunk, 0, read)
                    return if (read < 0) 0 else read
                }

                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                    val chunk = ByteArray(length)
                    data.readBytes(chunk, 0, length)
                    bytes.write(chunk)
                }

                override fun sampleMetadata(
                    timeUs: Long,
                    flags: Int,
                    size: Int,
                    offset: Int,
                    cryptoData: TrackOutput.CryptoData?,
                ) {
                    val all = bytes.toByteArray()
                    samples += all.copyOfRange(all.size - size, all.size)
                }
            }

            override fun endTracks() = Unit

            override fun seekMap(seekMap: SeekMap) = Unit
        })
        var guard = 0
        while (guard++ < 20) {
            if (extractor.read(input, PositionHolder()) == Extractor.RESULT_END_OF_INPUT) break
        }
        return samples
    }

    private fun input(bytes: ByteArray): ExtractorInput = BytesInput(bytes)

    /** Enough of [ExtractorInput] to feed the extractor from a byte array, without Android's Uri. */
    private class BytesInput(private val data: ByteArray) : ExtractorInput {
        private var readPosition = 0
        private var peekPosition = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = take(buffer, offset, length, consume = true)

        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean =
            takeFully(target, offset, length, allowEndOfInput, consume = true)

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            readFully(target, offset, length, false)
        }

        override fun skip(length: Int): Int {
            if (readPosition >= data.size) return C.RESULT_END_OF_INPUT
            val n = min(length, data.size - readPosition)
            readPosition += n
            peekPosition = readPosition
            return n
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (readPosition + length > data.size) {
                if (allowEndOfInput && readPosition >= data.size) return false
                throw EOFException()
            }
            skip(length)
            return true
        }

        override fun skipFully(length: Int) {
            skipFully(length, false)
        }

        override fun peek(target: ByteArray, offset: Int, length: Int): Int = take(target, offset, length, consume = false)

        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean =
            takeFully(target, offset, length, allowEndOfInput, consume = false)

        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            peekFully(target, offset, length, false)
        }

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            if (peekPosition + length > data.size) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            peekPosition += length
            return true
        }

        override fun advancePeekPosition(length: Int) {
            advancePeekPosition(length, false)
        }

        override fun resetPeekPosition() {
            peekPosition = readPosition
        }

        override fun getPeekPosition(): Long = peekPosition.toLong()

        override fun getPosition(): Long = readPosition.toLong()

        override fun getLength(): Long = data.size.toLong()

        override fun <E : Throwable> setRetryPosition(position: Long, e: E): Unit = throw e

        private fun take(buffer: ByteArray, offset: Int, length: Int, consume: Boolean): Int {
            val cursor = if (consume) readPosition else peekPosition
            if (cursor >= data.size) return C.RESULT_END_OF_INPUT
            val n = min(length, data.size - cursor)
            data.copyInto(buffer, offset, cursor, cursor + n)
            if (consume) {
                readPosition += n
                peekPosition = readPosition
            } else {
                peekPosition += n
            }
            return n
        }

        private fun takeFully(
            target: ByteArray,
            offset: Int,
            length: Int,
            allowEndOfInput: Boolean,
            consume: Boolean,
        ): Boolean {
            val cursor = if (consume) readPosition else peekPosition
            if (cursor + length > data.size) {
                if (allowEndOfInput && cursor >= data.size) return false
                throw EOFException()
            }
            take(target, offset, length, consume)
            return true
        }
    }

    private fun readJpeg(jpeg: ByteArray): BufferedImage =
        ImageIO.read(ByteArrayInputStream(jpeg)) ?: error("JPEG decoder rejected the picture")

    private fun isRed(rgb: Int): Boolean = channel(rgb, 16) > 200 && channel(rgb, 8) < 40 && channel(rgb, 0) < 40

    private fun isBlue(rgb: Int): Boolean = channel(rgb, 0) > 200 && channel(rgb, 16) < 80 && channel(rgb, 8) < 80

    private fun channel(rgb: Int, shift: Int): Int = (rgb shr shift) and 0xFF

    /** Drops the given markers. The scan after the start-of-scan marker is copied whole. */
    private fun strip(jpeg: ByteArray, vararg markers: Int): ByteArray {
        val drop = markers.toSet()
        val out = ByteArrayOutputStream()
        var i = 0
        while (i + 1 < jpeg.size) {
            check(jpeg[i] == MARKER) { "lost JPEG marker sync at $i" }
            while (i + 1 < jpeg.size && jpeg[i + 1] == MARKER) i++
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == SOS) {
                out.write(jpeg, i, jpeg.size - i)
                break
            }
            if (marker == 0x00 || marker == 0x01 || marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7) {
                out.write(jpeg, i, 2)
                i += 2
                continue
            }
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            val size = 2 + length
            if (marker !in drop) out.write(jpeg, i, size)
            i += size
        }
        return out.toByteArray()
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("mjpeg/$name")) { name }.use { it.readBytes() }

    private companion object {
        const val MARKER = 0xFF.toByte()
        const val DHT = 0xC4
        const val DQT = 0xDB
        const val SOS = 0xDA
    }
}
