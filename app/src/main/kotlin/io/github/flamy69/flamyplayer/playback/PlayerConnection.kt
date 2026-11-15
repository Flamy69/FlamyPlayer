package io.github.flamy69.flamyplayer.playback

import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Snapshot of the player for the UI. The playback position is polled separately. */
data class PlayerState(
    val hasQueue: Boolean = false,
    /** Identifies what is playing: the id of a track, or of a radio station. */
    val mediaId: String? = null,
    val trackId: Long? = null,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    /** Folder of the audiobook being played, or `null` while playing music. */
    val bookFolder: String? = null,
    /** Whether a radio station is playing; its [title] is what is on air, if the station tells. */
    val isRadio: Boolean = false,
    /** Whether playback stopped because of an error. */
    val failed: Boolean = false,
    val artwork: ImageBitmap? = null,
    /** Whether the play/pause button should offer "pause". */
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
)

/** The UI's link to [PlaybackService]: observes the player and sends it commands. */
class PlayerConnection(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val controllerFuture = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
    ).buildAsync()

    private var controller: MediaController? = null
    private var whenConnected: ((MediaController) -> Unit)? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var artworkKey: String? = null
    private var artworkJob: Job? = null

    init {
        controllerFuture.addListener({
            val connected = runCatching { controllerFuture.get() }.getOrNull() ?: return@addListener
            controller = connected
            connected.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish(connected)
            })
            publish(connected)
            whenConnected?.invoke(connected)
            whenConnected = null
        }, ContextCompat.getMainExecutor(appContext))
    }

    val positionMs: Long get() = controller?.currentPosition ?: 0L

    fun release() {
        artworkJob?.cancel()
        whenConnected = null
        controller = null
        MediaController.releaseFuture(controllerFuture)
    }

    /** Starts [request], reusing the playlist that is already loaded when it is the same one. */
    fun play(request: PlayRequest, shuffle: Boolean, repeatMode: Int, speed: Float) = withController { c ->
        c.shuffleModeEnabled = shuffle
        c.repeatMode = repeatMode
        c.setPlaybackSpeed(speed)

        val active = PlaybackQueue.active
        val sameQueue = active != null &&
            active.bookFolder == request.bookFolder &&
            c.mediaItemCount == request.tracks.size + request.stations.size &&
            active.tracks == request.tracks &&
            active.stations == request.stations
        if (sameQueue) {
            c.seekTo(request.startIndex, request.startPositionMs)
        } else {
            c.setMediaItems(listOf(PlaybackQueue.submit(request)), 0, 0L)
        }
        c.prepare()
        c.play()
    }

    fun togglePlay() = withController { c ->
        if (c.showsPause()) {
            c.pause()
        } else {
            resumePlayback(c)
        }
    }

    fun resume() = withController { resumePlayback(it) }

    fun next() = withController { it.seekToNext() }

    fun previous() = withController { it.seekToPrevious() }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    fun seekBack() = withController { it.seekBack() }

    fun seekForward() = withController { it.seekForward() }

    fun setShuffle(enabled: Boolean) = withController { it.shuffleModeEnabled = enabled }

    fun setRepeatMode(mode: Int) = withController { it.repeatMode = mode }

    fun setSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed) }

    private fun resumePlayback(c: MediaController) {
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
        c.play()
    }

    /** Runs [action] now, or as soon as the connection to the service is up. */
    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else whenConnected = action
    }

    private fun MediaController.showsPause(): Boolean =
        playWhenReady && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED

    private fun publish(c: MediaController) {
        val item = c.currentMediaItem
        val metadata = c.mediaMetadata
        val station = item?.takeIf { it.isRadio }?.mediaMetadata?.station?.toString()
        // A station that tells what is on air has that as its title; the others their name.
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: station.orEmpty()
        _state.update { old ->
            old.copy(
                hasQueue = item != null,
                mediaId = item?.mediaId,
                trackId = item?.mediaId?.toLongOrNull(),
                title = title,
                artist = if (station != null) station.takeIf { it != title } else metadata.artist?.toString(),
                album = metadata.albumTitle?.toString(),
                isRadio = station != null,
                failed = c.playerError != null,
                bookFolder = item?.mediaMetadata?.extras?.getString(PlaybackQueue.EXTRA_BOOK_FOLDER),
                isPlaying = c.showsPause(),
                durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
                shuffle = c.shuffleModeEnabled,
                repeatMode = c.repeatMode,
                speed = c.playbackParameters.speed,
            )
        }
        updateArtwork(item?.mediaId, metadata.artworkData)
    }

    /** Decodes the cover embedded in the current file, off the main thread and only once per track. */
    private fun updateArtwork(mediaId: String?, data: ByteArray?) {
        val key = if (data == null) null else "$mediaId:${data.size}"
        if (key == artworkKey) return
        artworkKey = key
        artworkJob?.cancel()
        if (data == null) {
            _state.update { it.copy(artwork = null) }
            return
        }
        artworkJob = scope.launch {
            val bitmap = withContext(Dispatchers.Default) { decodeArtwork(data) }
            _state.update { it.copy(artwork = bitmap) }
        }
    }

    private fun decodeArtwork(data: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_ARTWORK_PX) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)?.asImageBitmap()
    }

    private companion object {
        const val MAX_ARTWORK_PX = 1024
    }
}
