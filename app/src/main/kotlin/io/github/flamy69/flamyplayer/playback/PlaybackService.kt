package io.github.flamy69.flamyplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.flamy69.flamyplayer.FlamyApp
import io.github.flamy69.flamyplayer.MainActivity
import io.github.flamy69.flamyplayer.R
import io.github.flamy69.flamyplayer.data.Settings

/**
 * Owns the player and publishes it as a media session. The session is what gives the app its
 * media notification, lock screen controls and headset / Bluetooth button handling.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var settings: Settings
    private var session: MediaSession? = null

    private val handler = Handler(Looper.getMainLooper())
    private var consecutiveErrors = 0

    private val progressTicker = object : Runnable {
        override fun run() {
            saveBookProgress()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = (application as FlamyApp).settings

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes(speech = false), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
        player.addListener(playerListener)

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            },
        )

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(sessionCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps the music going, but there is no reason to linger when idle.
        val idle = player.mediaItemCount == 0 ||
            !player.playWhenReady ||
            player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED
        if (idle) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTicker)
        saveBookProgress()
        PlaybackQueue.active = null
        session?.release()
        session = null
        player.removeListener(playerListener)
        player.release()
        super.onDestroy()
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            // The outgoing playlist may be a book whose position has not been stored yet.
            saveBookProgress()

            val request = PlaybackQueue.take()
                ?.takeIf { mediaItems.singleOrNull()?.mediaId == PlaybackQueue.PLACEHOLDER_ID }
            PlaybackQueue.active = request
            if (request == null) {
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }

            // Spoken word should pause for other sounds instead of being played over quietly.
            player.setAudioAttributes(audioAttributes(speech = request.bookFolder != null), true)
            return Futures.immediateFuture(
                MediaItemsWithStartPosition(
                    request.tracks.map { it.toMediaItem(request) },
                    request.startIndex,
                    request.startPositionMs,
                ),
            )
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(progressTicker)
            if (isPlaying) {
                handler.postDelayed(progressTicker, SAVE_INTERVAL_MS)
            } else {
                saveBookProgress()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveBookProgress()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) saveBookProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> consecutiveErrors = 0
                Player.STATE_ENDED -> saveBookProgress()
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // A file the device cannot decode should not stop the whole playlist: move on to the
            // next one, but give up if nothing in a row plays.
            consecutiveErrors++
            if (consecutiveErrors <= MAX_CONSECUTIVE_ERRORS && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
            }
        }
    }

    /** Remembers the position in the current audiobook; does nothing while music is playing. */
    private fun saveBookProgress() {
        val item = player.currentMediaItem ?: return
        val bookFolder = item.mediaMetadata.extras?.getString(PlaybackQueue.EXTRA_BOOK_FOLDER) ?: return
        val trackId = item.mediaId.toLongOrNull() ?: return
        if (player.playbackState == Player.STATE_ENDED) {
            // Finished: the next listen starts from the beginning again.
            settings.clearBookProgress(bookFolder)
        } else {
            settings.saveBookProgress(bookFolder, trackId, player.currentPosition)
        }
    }

    private fun audioAttributes(speech: Boolean) = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(if (speech) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L
        const val MAX_CONSECUTIVE_ERRORS = 5
    }
}
