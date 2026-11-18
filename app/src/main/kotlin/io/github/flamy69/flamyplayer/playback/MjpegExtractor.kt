package io.github.flamy69.flamyplayer.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import io.github.flamy69.flamyplayer.data.VideoFiles
import java.io.IOException
import kotlin.math.min

/**
 * Reads a raw Motion-JPEG file: JPEG pictures one after another, with no container.
 *
 * AVI already comes apart in ExoPlayer, which hands each picture on as `video/mjpeg`. A `.mjpg`
 * file never gets that far, because it is not a container ExoPlayer knows. One JPEG on its own is
 * left to the still-image extractor; a second picture is what makes this a film.
 */
@OptIn(UnstableApi::class)
internal class MjpegExtractor(
    private val allowSingleFrame: Boolean = false,
) : Extractor {

    private val seekMap = object : SeekMap {
        override fun isSeekable(): Boolean = framePositions.size > 1

        override fun getDurationUs(): Long = if (inputEnded) timeUs else C.TIME_UNSET

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            if (frameTimes.isEmpty()) return SeekMap.SeekPoints(SeekPoint.START)
            var index = frameTimes.binarySearch(timeUs)
            if (index < 0) index = -index - 2
            if (index < 0) index = 0
            return SeekMap.SeekPoints(SeekPoint(frameTimes[index], framePositions[index]))
        }
    }

    private var output: ExtractorOutput? = null
    private var track: TrackOutput? = null
    private var formatSent = false
    private var buffer = ByteArray(64 * 1024)
    private var start = 0
    private var end = 0
    private var timeUs = 0L
    private var inputEnded = false
    private val framePositions = ArrayList<Long>()
    private val frameTimes = ArrayList<Long>()

    override fun sniff(input: ExtractorInput): Boolean {
        return try {
            val header = ByteArray(2)
            if (input.peek(header, 0, 2) < 2 || header[0] != MARKER || header[1] != SOI) return false
            if (allowSingleFrame) return true
            // peek() already moved past the two header bytes, so start the scan over.
            input.resetPeekPosition()
            countPictures(input) >= 2
        } catch (_: IOException) {
            false
        } finally {
            input.resetPeekPosition()
        }
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        track = output.track(0, C.TRACK_TYPE_VIDEO)
        output.endTracks()
        output.seekMap(seekMap)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val track = track ?: return Extractor.RESULT_END_OF_INPUT
        val frame = nextFrame(input.position)
        if (frame != null) {
            if (!formatSent) {
                track.format(formatFor(frame))
                formatSent = true
            }
            writeSample(track, frame)
            track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, frame.size, 0, null)
            timeUs += FRAME_DURATION_US
            return Extractor.RESULT_CONTINUE
        }
        if (inputEnded) return Extractor.RESULT_END_OF_INPUT
        val read = fill(input)
        if (read == C.RESULT_END_OF_INPUT) {
            inputEnded = true
            output?.seekMap(seekMap)
            val last = nextFrame(input.position)
            if (last != null) {
                if (!formatSent) {
                    track.format(formatFor(last))
                    formatSent = true
                }
                writeSample(track, last)
                track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, last.size, 0, null)
                timeUs += FRAME_DURATION_US
                return Extractor.RESULT_CONTINUE
            }
            return Extractor.RESULT_END_OF_INPUT
        }
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        start = 0
        end = 0
        this.timeUs = timeUs
        inputEnded = false
    }

    override fun release() = Unit

    private fun nextFrame(filePosition: Long): ByteArray? {
        val soi = MjpegJpeg.findSoi(buffer, start, end)
        if (soi < 0) {
            if (end - start > 1) start = end - 1
            return null
        }
        val limit = MjpegJpeg.completeEnd(buffer, soi, end)
        if (limit < 0) {
            if (end - start > MAX_BUFFER) start = soi + 2
            return null
        }
        val framePosition = filePosition - (end - soi)
        if (framePositions.isEmpty() || framePosition > framePositions.last()) {
            framePositions += framePosition
            frameTimes += timeUs
        }
        val frame = buffer.copyOfRange(soi, limit)
        start = limit
        return frame
    }

    private fun fill(input: ExtractorInput): Int {
        if (end == buffer.size) grow()
        val read = input.read(buffer, end, buffer.size - end)
        if (read > 0) end += read
        return read
    }

    private fun grow() {
        val used = end - start
        if (start > 0 && used < buffer.size / 2) {
            buffer.copyInto(buffer, 0, start, end)
            end = used
            start = 0
        } else {
            buffer = buffer.copyOf(buffer.size * 2)
        }
    }

    private fun writeSample(track: TrackOutput, frame: ByteArray) {
        val reader = object : DataReader {
            var offset = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (this.offset >= frame.size) return C.RESULT_END_OF_INPUT
                val n = min(length, frame.size - this.offset)
                frame.copyInto(buffer, offset, this.offset, this.offset + n)
                this.offset += n
                return n
            }
        }
        var remaining = frame.size
        while (remaining > 0) {
            val written = track.sampleData(reader, remaining, false)
            if (written <= 0) break
            remaining -= written
        }
    }

    private fun formatFor(frame: ByteArray): Format {
        val picture = MjpegJpeg.prepare(frame, 0, frame.size, MjpegJpeg.Tables())
        return Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_MJPEG)
            .setWidth(picture?.width ?: Format.NO_VALUE)
            .setHeight(picture?.height ?: Format.NO_VALUE)
            .setMaxInputSize(frame.size.coerceAtLeast(256 * 1024))
            .build()
    }

    private fun countPictures(input: ExtractorInput): Int {
        val chunk = ByteArray(8 * 1024)
        var pending = ByteArray(0)
        var total = 0
        while (total < SNIFF_LIMIT) {
            val n = min(chunk.size, SNIFF_LIMIT - total)
            val read = input.peek(chunk, 0, n)
            if (read == C.RESULT_END_OF_INPUT || read <= 0) break
            pending += chunk.copyOf(read)
            total += read
            val count = MjpegJpeg.frameCount(pending)
            if (count >= 2) return count
        }
        return MjpegJpeg.frameCount(pending)
    }

    private companion object {
        const val MARKER = 0xFF.toByte()
        const val SOI = 0xD8.toByte()
        const val FRAME_DURATION_US = 1_000_000L / 30
        const val SNIFF_LIMIT = 4 * 1024 * 1024
        const val MAX_BUFFER = 16 * 1024 * 1024
    }
}

/** [bundled] plus the raw Motion-JPEG extractor, tried first. */
@OptIn(UnstableApi::class)
internal fun mjpegAwareExtractors(bundled: DefaultExtractorsFactory): ExtractorsFactory =
    object : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> = createExtractors(Uri.EMPTY, emptyMap())

        override fun createExtractors(
            uri: Uri,
            responseHeaders: Map<String, List<String>>,
        ): Array<Extractor> {
            val defaults = bundled.createExtractors(uri, responseHeaders)
            val extension = VideoFiles.extension(uri.lastPathSegment.orEmpty())
            val singleFrame = extension == "mjpg" || extension == "mjpeg"
            return Array(defaults.size + 1) { index ->
                if (index == 0) MjpegExtractor(allowSingleFrame = singleFrame) else defaults[index - 1]
            }
        }
    }
