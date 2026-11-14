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
import io.github.flamy69.flamyplayer.data.Section
import io.github.flamy69.flamyplayer.data.Settings
import io.github.flamy69.flamyplayer.data.Track
import io.github.flamy69.flamyplayer.playback.PlayRequest
import io.github.flamy69.flamyplayer.playback.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val settings: Settings = (app as FlamyApp).settings
    val player = PlayerConnection(app, viewModelScope)

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
        refresh()
    }

    /** Re-reads the library; cheap enough to call whenever the app comes to the foreground. */
    fun refresh() {
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

    /** Plays the music playlist from [startTrackId], or from a random / the first track. */
    fun playMusic(startTrackId: Long? = null, shuffle: Boolean = settings.shuffle.value) {
        val tracks = musicTracks.value
        if (tracks.isNullOrEmpty()) return
        val startIndex = when {
            startTrackId != null -> tracks.indexOfFirst { it.id == startTrackId }.coerceAtLeast(0)
            shuffle -> Random.nextInt(tracks.size)
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
