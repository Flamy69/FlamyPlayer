package io.github.flamy69.flamyplayer.playback

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.flamy69.flamyplayer.FlamyApp
import io.github.flamy69.flamyplayer.MainActivity
import io.github.flamy69.flamyplayer.R
import io.github.flamy69.flamyplayer.data.Settings
import io.github.flamy69.flamyplayer.data.TrackStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Owns the player and publishes it as a media session. The session is what gives the app its
 * media notification, lock screen controls and headset / Bluetooth button handling.
 *
 * It also hands out the library, which is how Android Auto shows music, audiobooks and radio
 * on the display of a car and starts them from there, without the app's own screens.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var settings: Settings
    private lateinit var stats: TrackStats
    private lateinit var catalog: CatalogLoader
    private var session: MediaLibrarySession? = null

    /** The answer to the last search, which the car asks for right after the search itself. */
    private var lastSearch: Pair<String, List<BrowseNode>>? = null

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
        catalog = CatalogLoader(this, settings)

        player = ExoPlayer.Builder(this, MediaRenderersFactory(this))
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
        session = MediaLibrarySession.Builder(this, player, sessionCallback)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

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
        catalog.release()
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

    private val sessionCallback = object : MediaLibrarySession.Callback {

        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (!controller.isTrusted) {
                if (mayBrowse(session, controller)) {
                    result.setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
                    result.setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                } else {
                    // The service is open to other apps for the sake of the car. An app nobody
                    // vouches for may see what is playing, as it could before, but it is not
                    // told what is in the library.
                    result.setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_COMMANDS)
                }
            }
            return Futures.immediateFuture(result.build())
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(BrowseTree.ROOT)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build(),
                )
                .build()
            // Plain lists: there are no covers that a grid could show.
            val style = Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            }
            return Futures.immediateFuture(LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(style).build()))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = async {
            // Cars do not ask page by page, so the whole list is the answer to the first page.
            val children = if (page == 0) catalog.tree().children(parentId) else emptyList()
            if (children == null) {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            } else {
                LibraryResult.ofItemList(children.map { it.toMediaItem() }, params)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = async {
            val node = catalog.tree().node(mediaId)
            if (node == null) {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            } else {
                LibraryResult.ofItem(node.toMediaItem(), null)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch { session.notifySearchResultChanged(browser, query, search(query).size, params) }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = async {
            val found = if (page == 0) search(query) else emptyList()
            LibraryResult.ofItemList(found.map { it.toMediaItem() }, params)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val single = mediaItems.singleOrNull()
            val request = PlaybackQueue.take()?.takeIf { single?.mediaId == PlaybackQueue.PLACEHOLDER_ID }
            if (request == null && single != null && single.localConfiguration == null &&
                mayBrowse(mediaSession, controller)
            ) {
                // A line chosen on the display of the car, or something asked for by voice.
                return async { start(wished(single) ?: throw IllegalArgumentException("Nothing to play")) }
            }

            // The outgoing playlist may be a book whose position has not been stored yet.
            saveBookProgress()
            saveTrackProgress()
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

        /**
         * "Play" with nothing loaded, from the car or a headset after the app was closed: goes
         * on with what was played last.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> = async {
            val target = settings.lastPlayed?.let { catalog.tree().target(it) }
                ?: throw UnsupportedOperationException("Nothing was played yet")
            if (isForPlayback) start(target) else plan(target).items()
        }
    }

    /** Runs [block] on the main thread and hands its result to the media session. */
    private fun <T : Any> async(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        scope.launch {
            try {
                result.set(block())
            } catch (e: CancellationException) {
                result.cancel(false)
                throw e
            } catch (e: Exception) {
                result.setException(e)
            }
        }
        return result
    }

    /**
     * Whether [controller] may look into the library and start things from it: the app
     * itself, the system, the car and the voice assistants.
     */
    private fun mayBrowse(session: MediaSession, controller: MediaSession.ControllerInfo): Boolean =
        controller.isTrusted ||
            controller.uid == Process.myUid() ||
            session.isMediaNotificationController(controller) ||
            controller.isPackageNameVerified && (
                session.isAutoCompanionController(controller) ||
                    session.isAutomotiveController(controller) ||
                    controller.packageName in VOICE_ASSISTANTS
                )

    private suspend fun search(query: String): List<BrowseNode> {
        lastSearch?.let { (asked, found) -> if (asked == query) return found }
        return catalog.tree().search(query).also { lastSearch = query to it }
    }

    /** What an item that names something, or asks for it in words, is meant to play. */
    private suspend fun wished(item: MediaItem): BrowseTarget? {
        val tree = catalog.tree()
        if (item.mediaId.isNotEmpty()) return tree.target(item.mediaId)
        val query = item.requestMetadata.searchQuery.orEmpty()
        // "Play some music" without saying what: go on where the listener stopped.
        return tree.searchTarget(query)
            ?: (settings.lastPlayed?.let(tree::target) ?: tree.target(BrowseTree.SHUFFLE)).takeIf { query.isBlank() }
    }

    /** A playlist and the way to play it, worked out from what was chosen. */
    private class Start(val request: PlayRequest, val shuffle: Boolean, val repeatMode: Int, val speed: Float) {
        fun items() = MediaItemsWithStartPosition(request.mediaItems(), request.startIndex, request.startPositionMs)
    }

    /** The same choices the app's own screens make when something is tapped there. */
    private fun plan(target: BrowseTarget): Start = when (target) {
        is BrowseTarget.Music -> {
            val tracks = target.tracks
            val shuffle = target.shuffle ?: settings.shuffle.value
            val index = target.startIndex
                ?: if (shuffle) ShufflePlan.leastPlayed(tracks.size, { stats.shufflePlays(tracks[it].path) }) else 0
            Start(
                PlayRequest(tracks, index, 0L, playlistPath = target.playlistPath),
                shuffle,
                settings.repeatMode.value,
                1f,
            )
        }
        is BrowseTarget.Audiobook -> {
            val book = target.book
            val (index, position) = book.resumePoint(settings.bookProgress.value[book.folder])
            Start(
                PlayRequest(book.tracks, index, position, bookFolder = book.folder, bookTitle = book.title),
                false,
                Player.REPEAT_MODE_OFF,
                settings.bookSpeed.value,
            )
        }
        is BrowseTarget.Radio -> Start(
            PlayRequest(emptyList(), target.index, 0L, stations = target.stations),
            false,
            Player.REPEAT_MODE_OFF,
            1f,
        )
    }

    /** Gets the player ready for [target] and returns what it is to play. */
    private fun start(target: BrowseTarget): MediaItemsWithStartPosition {
        // The outgoing playlist may be the very book that is asked for again.
        saveBookProgress()
        saveTrackProgress()
        val start = plan(target)
        if (target is BrowseTarget.Music && target.shuffle != null) settings.setShuffle(target.shuffle)
        // In this order the shuffle never sees the new playlist paired with the old tracks.
        player.shuffleModeEnabled = start.shuffle
        player.repeatMode = start.repeatMode
        player.setPlaybackSpeed(start.speed)
        player.setAudioAttributes(audioAttributes(speech = start.request.bookFolder != null), true)
        PlaybackQueue.active = start.request
        return start.items()
    }

    private fun BrowseNode.toMediaItem(): MediaItem {
        val icon = when (kind) {
            BrowseNode.Kind.MUSIC -> R.drawable.ic_car_music
            BrowseNode.Kind.BOOKS -> R.drawable.ic_car_book
            BrowseNode.Kind.RADIO, BrowseNode.Kind.ALL_STATIONS -> R.drawable.ic_car_radio
            BrowseNode.Kind.FOLDER -> R.drawable.ic_car_folder
            BrowseNode.Kind.PLAYLISTS, BrowseNode.Kind.PLAYLIST -> R.drawable.ic_car_playlist
            BrowseNode.Kind.SHUFFLE -> R.drawable.ic_car_shuffle
            BrowseNode.Kind.TRACK, BrowseNode.Kind.BOOK, BrowseNode.Kind.STATION -> null
        }
        val type = when (kind) {
            BrowseNode.Kind.MUSIC, BrowseNode.Kind.FOLDER -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            BrowseNode.Kind.BOOKS -> MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS
            BrowseNode.Kind.RADIO, BrowseNode.Kind.ALL_STATIONS -> MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS
            BrowseNode.Kind.PLAYLISTS -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            BrowseNode.Kind.PLAYLIST, BrowseNode.Kind.SHUFFLE -> MediaMetadata.MEDIA_TYPE_PLAYLIST
            BrowseNode.Kind.TRACK -> MediaMetadata.MEDIA_TYPE_MUSIC
            BrowseNode.Kind.BOOK -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
            BrowseNode.Kind.STATION -> MediaMetadata.MEDIA_TYPE_RADIO_STATION
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    // The second line of a list item is what a media session calls the artist.
                    .setArtist(subtitle)
                    .setArtworkUri(icon?.let(::resourceUri))
                    .setIsBrowsable(kind.isBrowsable)
                    .setIsPlayable(!kind.isBrowsable)
                    .setMediaType(type)
                    .build(),
            )
            .build()
    }

    /** The address under which another app, such as Android Auto, can load a drawable of this one. */
    private fun resourceUri(drawable: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(resources.getResourcePackageName(drawable))
        .appendPath(resources.getResourceTypeName(drawable))
        .appendPath(resources.getResourceEntryName(drawable))
        .build()

    /** Notes what is playing, so that a later "play" can go on with it. */
    private fun rememberCurrent(item: MediaItem?) {
        val request = PlaybackQueue.active ?: return
        if (item == null) return
        settings.lastPlayed = when {
            item.isRadio -> item.mediaId
            request.bookFolder != null -> BrowseTree.bookId(request.bookFolder)
            request.playlistPath != null ->
                BrowseTree.playlistTrackId(request.playlistPath, player.currentMediaItemIndex)
            else -> BrowseTree.trackId(item.mediaId.toLongOrNull() ?: return)
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
            rememberCurrent(mediaItem)
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

        /** The Google app (Assistant) and Gemini, which start playback when asked by voice. */
        val VOICE_ASSISTANTS = setOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.bard",
            "com.google.android.carassistant",
        )
    }
}
