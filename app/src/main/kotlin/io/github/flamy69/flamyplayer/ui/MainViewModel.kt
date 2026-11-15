package io.github.flamy69.flamyplayer.ui

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import io.github.flamy69.flamyplayer.FlamyApp
import io.github.flamy69.flamyplayer.data.Book
import io.github.flamy69.flamyplayer.data.FolderNode
import io.github.flamy69.flamyplayer.data.FolderSelection
import io.github.flamy69.flamyplayer.data.FolderTree
import io.github.flamy69.flamyplayer.data.MediaLibrary
import io.github.flamy69.flamyplayer.data.NaturalOrder
import io.github.flamy69.flamyplayer.data.RadioStation
import io.github.flamy69.flamyplayer.data.RadioStations
import io.github.flamy69.flamyplayer.data.Section
import io.github.flamy69.flamyplayer.data.Settings
import io.github.flamy69.flamyplayer.data.Track
import io.github.flamy69.flamyplayer.data.TrackStats
import io.github.flamy69.flamyplayer.playback.PlayRequest
import io.github.flamy69.flamyplayer.playback.PlayerConnection
import io.github.flamy69.flamyplayer.playback.ShufflePlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val settings: Settings = (app as FlamyApp).settings
    val trackStats: TrackStats = (app as FlamyApp).trackStats
    val player = PlayerConnection(app, viewModelScope)
    val video = VideoBrowser(app as FlamyApp, viewModelScope)

    private val _hasPermission = MutableStateFlow(MediaLibrary.hasPermission(app))
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    /** Every audio file on the device; `null` until the first load has finished. */
    private val library = MutableStateFlow<List<Track>?>(null)

    /** The one big playlist: all tracks in the selected music folders. `null` while loading. */
    val musicTracks: StateFlow<List<Track>?> =
        combine(library, settings.musicFolders) { tracks, folders ->
            tracks?.let { FolderSelection.tracksIn(it, folders) }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** One book per selected audiobook folder. `null` while loading. */
    val books: StateFlow<List<Book>?> =
        combine(library, settings.bookFolders) { tracks, folders ->
            tracks?.let { loaded ->
                folders.map { folder -> Book(folder, FolderSelection.tracksIn(loaded, setOf(folder))) }
                    .sortedWith(compareBy(NaturalOrder) { it.title })
            }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** All folders that contain audio, for the folder picker. */
    val folderTree: StateFlow<FolderNode?> = library.map { tracks ->
        FolderTree.build(tracks.orEmpty().groupingBy { it.folder }.eachCount())
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The radio stations that come with the app; read when the radio section is first opened. */
    val radioStations: StateFlow<List<RadioStation>?> = flow<List<RadioStation>?> {
        emit(
            try {
                RadioStations.load(getApplication())
            } catch (_: Exception) {
                emptyList()
            },
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** The stations shown first; a handful of well known ones until the listener chooses. */
    val radioFavorites: StateFlow<Set<String>> =
        combine(radioStations, settings.radioFavorites) { stations, chosen ->
            chosen ?: RadioStations.defaultFavorites(stations.orEmpty())
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    private var loadJob: Job? = null
    private var reloadJob: Job? = null

    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // Copying an album fires a burst of changes; wait for it to settle.
            reloadJob?.cancel()
            reloadJob = viewModelScope.launch {
                delay(RELOAD_DELAY_MS)
                refresh()
            }
        }
    }

    init {
        app.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObserver,
        )
        app.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver,
        )
        refresh()
    }

    /** Re-reads the library; cheap enough to call whenever the app comes to the foreground. */
    fun refresh() {
        video.refreshDevice()
        val granted = MediaLibrary.hasPermission(getApplication())
        _hasPermission.value = granted
        if (!granted || loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            library.value = withContext(Dispatchers.IO) { MediaLibrary.load(getApplication()) }
        }
    }

    fun toggleFolder(section: Section, folder: String) {
        settings.setFolders(section, FolderSelection.toggle(settings.folders(section).value, folder))
    }

    fun clearFolders(section: Section) = settings.setFolders(section, emptySet())

    /**
     * Plays the music playlist from [startTrackId], or from the first track. Shuffling starts
     * with one of the tracks that were played the fewest times.
     */
    fun playMusic(startTrackId: Long? = null, shuffle: Boolean = settings.shuffle.value) {
        val tracks = musicTracks.value
        if (tracks.isNullOrEmpty()) return
        val startIndex = when {
            startTrackId != null -> tracks.indexOfFirst { it.id == startTrackId }.coerceAtLeast(0)
            shuffle -> ShufflePlan.leastPlayed(tracks.size, { trackStats.shufflePlays(tracks[it].path) })
            else -> 0
        }
        settings.setShuffle(shuffle)
        player.play(
            PlayRequest(tracks, startIndex, 0L),
            shuffle = shuffle,
            repeatMode = settings.repeatMode.value,
            speed = 1f,
        )
    }

    /** Continues [book] where the listener stopped, or starts the given chapter from its beginning. */
    fun playBook(book: Book, chapterIndex: Int? = null) {
        if (book.tracks.isEmpty()) return
        if (chapterIndex == null && player.state.value.bookFolder == book.folder) {
            // Already loaded; the player knows the position better than the periodically saved one.
            player.resume()
            return
        }
        val (index, position) = if (chapterIndex != null) {
            chapterIndex to 0L
        } else {
            book.resumePoint(settings.bookProgress.value[book.folder])
        }
        player.play(
            PlayRequest(book.tracks, index, position, bookFolder = book.folder, bookTitle = book.title),
            shuffle = false,
            repeatMode = Player.REPEAT_MODE_OFF,
            speed = settings.bookSpeed.value,
        )
    }

    /**
     * Plays [station]; "next" and "previous" then move through [stations]. Tapping the station
     * that is on pauses it or lets it go on.
     */
    fun playRadio(stations: List<RadioStation>, station: RadioStation) {
        val index = stations.indexOf(station)
        if (index < 0) return
        val state = player.state.value
        if (state.mediaId == station.mediaId && !state.failed) {
            player.togglePlay()
            return
        }
        player.play(
            PlayRequest(emptyList(), index, 0L, stations = stations),
            shuffle = false,
            repeatMode = Player.REPEAT_MODE_OFF,
            speed = 1f,
        )
    }

    fun toggleRadioFavorite(station: RadioStation) {
        val favorites = radioFavorites.value
        settings.setRadioFavorites(if (station.id in favorites) favorites - station.id else favorites + station.id)
    }

    fun toggleShuffle() {
        val enabled = !player.state.value.shuffle
        settings.setShuffle(enabled)
        player.setShuffle(enabled)
    }

    fun cycleRepeatMode() {
        val mode = when (player.state.value.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        settings.setRepeatMode(mode)
        player.setRepeatMode(mode)
    }

    fun setBookSpeed(speed: Float) {
        settings.setBookSpeed(speed)
        player.setSpeed(speed)
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(mediaObserver)
        player.release()
    }

    private companion object {
        const val RELOAD_DELAY_MS = 1_500L
    }
}
