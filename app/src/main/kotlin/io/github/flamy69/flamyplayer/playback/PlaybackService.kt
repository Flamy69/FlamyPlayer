package io.github.flamy69.flamyplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
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
import io.github.flamy69.flamyplayer.data.TrackStats
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Owns the player and publishes it as a media session. The session is what gives the app its
 * media notification, lock screen controls and headset / Bluetooth button handling.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var settings: Settings
    private lateinit var stats: TrackStats
    private var session: MediaSession? = null

    /** The order in which the shuffled music playlist is played, and the playlist it is for. */
    private var plan: ShufflePlan? = null
    private var planFor: PlayRequest? = null

    private val scope = MainScope()
    private val boost = VolumeBoost()

    private val handler = Handler(Looper.getMainLooper())
    private var consecutiveErrors = 0

    /** The station that was chosen, by its place in the list; -1 while no radio is playing. */
    private var stationIndex = -1

    /** When playback was paused, to tell a short break from a stream that went stale. */
    private var pausedAtMs = 0L

    /** Connects to a radio stream again after it broke off. */
    private val reconnect = Runnable {
        if (player.currentMediaItem?.isRadio == true && player.playerError != null) {
            player.seekToDefaultPosition()
            player.prepare()
        }
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            saveBookProgress()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    private val listenTicker = object : Runnable {
        override fun run() {
            checkListened()
            handler.postDelayed(this, LISTEN_CHECK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = (application as FlamyApp).settings
        stats = (application as FlamyApp).trackStats

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes(speech = false), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            // Radio streams are behind redirects that may switch between HTTPS and HTTP.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)),
                ),
            )
            // Keeps Wi-Fi awake as well, so that a radio stream survives the screen going dark.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
        player.addListener(playerListener)
        boost.attach(player.audioSessionId)
        // Also follows the setting while it is being changed during playback.
        scope.launch { settings.boosts.collect { applyBoost() } }

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
        handler.removeCallbacks(listenTicker)
        handler.removeCallbacks(reconnect)
        scope.cancel()
        boost.release()
        saveBookProgress()
        saveTrackProgress()
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
            saveTrackProgress()

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
                    request.mediaItems(),
                    request.startIndex,
                    request.startPositionMs,
                ),
            )
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(progressTicker)
            handler.removeCallbacks(listenTicker)
            if (isPlaying) {
                handler.postDelayed(progressTicker, SAVE_INTERVAL_MS)
                handler.postDelayed(listenTicker, LISTEN_CHECK_MS)
            } else {
                saveBookProgress()
                saveTrackProgress()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            handler.removeCallbacks(reconnect)
            if (mediaItem?.isRadio == true) {
                val index = player.currentMediaItemIndex
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    stationIndex != index && stationIndex in 0 until player.mediaItemCount
                ) {
                    // A stream that ends is a connection that broke, not a wish for the next station.
                    player.seekToDefaultPosition(stationIndex)
                    return
                }
                stationIndex = index
            } else {
                stationIndex = -1
            }
            saveBookProgress()
            applyBoost()
            syncShuffle()
            if (plan != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                mediaItem?.trackPath?.let(stats::countShufflePlay)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                pausedAtMs = SystemClock.elapsedRealtime()
            } else {
                // After a pause a radio continues with what is on air now, not with what was
                // on air back then.
                val paused = pausedAtMs != 0L && SystemClock.elapsedRealtime() - pausedAtMs > STALE_STREAM_MS
                pausedAtMs = 0L
                if (paused && player.currentMediaItem?.isRadio == true) player.seekToDefaultPosition()
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) syncShuffle()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // Shuffling starts over from the track that is playing.
            plan = null
            syncShuffle()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            boost.attach(audioSessionId)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) saveBookProgress()
            // A track was left behind: note how much of it was heard.
            val finished = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            if (finished || oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                saveTrackProgress(oldPosition.mediaItem, oldPosition.positionMs, finished)
            }
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
            if (player.currentMediaItem?.isRadio == true) {
                // A stream that broke off is connected to again; a station that stays silent is
                // not replaced by its neighbor in the list.
                if (consecutiveErrors <= MAX_STREAM_RETRIES) handler.postDelayed(reconnect, RECONNECT_DELAY_MS)
                return
            }
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

    /** Notes how much of a music track was heard; does nothing for audiobooks. */
    private fun saveTrackProgress(
        item: MediaItem? = player.currentMediaItem,
        positionMs: Long = player.currentPosition,
        finished: Boolean = player.playbackState == Player.STATE_ENDED,
    ) {
        val path = item?.trackPath ?: return
        val durationMs = item.mediaMetadata.extras?.getLong(PlaybackQueue.EXTRA_TRACK_DURATION) ?: 0L
        val percent = when {
            finished -> 100
            durationMs > 0 -> (positionMs * 100 / durationMs).toInt().coerceIn(0, 100)
            else -> return
        }
        stats.setLastPercent(path, percent)
    }

    private val MediaItem.trackPath: String?
        get() = mediaMetadata.extras?.getString(PlaybackQueue.EXTRA_TRACK_PATH)

    /**
     * Keeps the order of a shuffled music playlist in step with what is playing: the tracks
     * played least come first, and the track after the current one is chosen anew whenever
     * the listener moves on. Other playlists keep the player's own order.
     */
    private fun syncShuffle() {
        val request = PlaybackQueue.active
        val count = player.mediaItemCount
        if (request == null || request.bookFolder != null || !player.shuffleModeEnabled ||
            count == 0 || count != request.tracks.size
        ) {
            plan = null
            planFor = null
            return
        }
        val index = player.currentMediaItemIndex
        val known = plan.takeIf { planFor === request }
        if (known == null) {
            val tracks = request.tracks
            val fresh = ShufflePlan(
                artists = tracks.map { ShufflePlan.artistKey(it.artist) },
                plays = { stats.shufflePlays(tracks[it].path) },
                startIndex = index,
            )
            plan = fresh
            planFor = request
            applyOrder(fresh)
        } else if (known.moveTo(index)) {
            applyOrder(known)
        }
    }

    /** Once most of a shuffled track was heard, the next one may come from the same artist. */
    private fun checkListened() {
        val current = plan ?: return
        val durationMs = player.duration
        if (durationMs == C.TIME_UNSET || durationMs <= 0) return
        if (player.currentPosition * 100 > durationMs * ShufflePlan.LISTENED_PERCENT && current.markListened()) {
            applyOrder(current)
        }
    }

    private fun applyOrder(current: ShufflePlan) {
        val order = current.snapshot()
        if (order.size == player.mediaItemCount) player.setShuffleOrder(DefaultShuffleOrder(order, Random.nextLong()))
    }

    /** Plays the current audiobook, or music, as much louder as the listener asked for. */
    private fun applyBoost() {
        val bookFolder = player.currentMediaItem?.mediaMetadata?.extras?.getString(PlaybackQueue.EXTRA_BOOK_FOLDER)
        boost.setGain(settings.boosts.value[Settings.boostKey(bookFolder)] ?: 0)
    }

    private fun audioAttributes(speech: Boolean) = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(if (speech) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
        const val LISTEN_CHECK_MS = 1_000L
        const val MAX_STREAM_RETRIES = 3
        const val RECONNECT_DELAY_MS = 2_000L
        const val STALE_STREAM_MS = 3_000L
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L
        const val MAX_CONSECUTIVE_ERRORS = 5
    }
}
