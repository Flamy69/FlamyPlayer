package io.github.flamy69.flamyplayer.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.DecoderOutputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.DecoderVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Plays MJPEG with the JPEG decoder built into Android.
 *
 * Each sample is one JPEG picture. [BitmapFactory] is that decoder — the same one Media3 uses for
 * still images. Phones are not required to ship a video decoder for `video/mjpeg`, and the FFmpeg
 * build bundled with the app was compiled without one, so this is what actually draws the frame.
 * The phone's own video decoders are registered ahead of this one and stay in front when they
 * have the codec.
 */
@OptIn(UnstableApi::class)
class MjpegRenderer(
    allowedJoiningTimeMs: Long,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
) : DecoderVideoRenderer(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify) {

    private var decoder: MjpegVideoDecoder? = null

    @Volatile
    private var outputMode: Int = C.VIDEO_OUTPUT_MODE_NONE

    override fun getName(): String = "MjpegRenderer"

    override fun supportsFormat(format: Format): Int {
        if (format.sampleMimeType != MimeTypes.VIDEO_MJPEG) return C.FORMAT_UNSUPPORTED_TYPE
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        }
        return RendererCapabilities.create(
            C.FORMAT_HANDLED,
            ADAPTIVE_NOT_SEAMLESS,
            TUNNELING_NOT_SUPPORTED,
        )
    }

    override fun createDecoder(
        format: Format,
        cryptoConfig: CryptoConfig?,
    ): Decoder<DecoderInputBuffer, out VideoDecoderOutputBuffer, out DecoderException> {
        val created = MjpegVideoDecoder()
        created.setOutputMode(outputMode)
        decoder = created
        return created
    }

    override fun renderOutputBufferToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        val bitmap = (outputBuffer as MjpegOutputBuffer).bitmap
        try {
            if (bitmap == null) throw MjpegDecoderException("Missing MJPEG frame")
            val canvas = lock(surface)
            try {
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bitmap, null, Rect(0, 0, canvas.width, canvas.height), null)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } finally {
            outputBuffer.release()
        }
    }

    override fun setDecoderOutputMode(outputMode: Int) {
        this.outputMode = outputMode
        decoder?.setOutputMode(outputMode)
    }

    private fun lock(surface: Surface): Canvas = try {
        surface.lockHardwareCanvas()
    } catch (_: IllegalArgumentException) {
        surface.lockCanvas(null)
    } catch (_: IllegalStateException) {
        surface.lockCanvas(null)
    }
}

@OptIn(UnstableApi::class)
@Suppress("UNCHECKED_CAST")
internal class MjpegVideoDecoder :
    SimpleDecoder<DecoderInputBuffer, MjpegOutputBuffer, MjpegDecoderException>(
        arrayOfNulls<DecoderInputBuffer>(INPUT_BUFFERS) as Array<DecoderInputBuffer>,
        arrayOfNulls<MjpegOutputBuffer>(OUTPUT_BUFFERS) as Array<MjpegOutputBuffer>,
    ) {

    private val tables = MjpegJpeg.Tables()

    @Volatile
    private var outputMode: Int = C.VIDEO_OUTPUT_MODE_NONE

    fun setOutputMode(outputMode: Int) {
        this.outputMode = outputMode
    }

    override fun getName(): String = "mjpeg"

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

    override fun createOutputBuffer(): MjpegOutputBuffer =
        MjpegOutputBuffer { releaseOutputBuffer(it as MjpegOutputBuffer) }

    override fun createUnexpectedDecodeException(error: Throwable): MjpegDecoderException =
        MjpegDecoderException("Unexpected MJPEG decode error", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: MjpegOutputBuffer,
        reset: Boolean,
    ): MjpegDecoderException? {
        if (reset) tables.clear()
        val data = inputBuffer.data ?: return MjpegDecoderException("Empty MJPEG frame")
        val bytes = ByteArray(data.remaining())
        data.get(bytes)
        val picture = MjpegJpeg.prepare(bytes, 0, bytes.size, tables)
            ?: return MjpegDecoderException("Not a JPEG picture")
        val bitmap = BitmapFactory.decodeByteArray(picture.jpeg, 0, picture.jpeg.size, decodeOptions())
            ?: return MjpegDecoderException("Could not decode MJPEG frame")
        bitmap.density = Bitmap.DENSITY_NONE
        outputBuffer.init(inputBuffer.timeUs, outputMode, null)
        outputBuffer.width = bitmap.width
        outputBuffer.height = bitmap.height
        outputBuffer.bitmap = bitmap
        return null
    }

    private companion object {
        const val INPUT_BUFFERS = 4
        const val OUTPUT_BUFFERS = 4

        fun decodeOptions(): BitmapFactory.Options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // Density scaling would resize the picture to the screen and ignore the video size.
            inScaled = false
        }
    }
}

@OptIn(UnstableApi::class)
internal class MjpegOutputBuffer(owner: DecoderOutputBuffer.Owner<VideoDecoderOutputBuffer>) :
    VideoDecoderOutputBuffer(owner) {

    var bitmap: Bitmap? = null

    override fun clear() {
        bitmap?.recycle()
        bitmap = null
        super.clear()
    }
}

internal class MjpegDecoderException : DecoderException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}
