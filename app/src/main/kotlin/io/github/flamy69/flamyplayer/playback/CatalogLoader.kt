package io.github.flamy69.flamyplayer.playback

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import io.github.flamy69.flamyplayer.R
import io.github.flamy69.flamyplayer.data.Book
import io.github.flamy69.flamyplayer.data.FolderSelection
import io.github.flamy69.flamyplayer.data.MediaLibrary
import io.github.flamy69.flamyplayer.data.PlaylistFiles
import io.github.flamy69.flamyplayer.data.PlaylistLibrary
import io.github.flamy69.flamyplayer.data.RadioStation
import io.github.flamy69.flamyplayer.data.RadioStations
import io.github.flamy69.flamyplayer.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the library for the playback service, which has to answer the car without the app's
 * screens ever having been opened. What was read is kept until the files on the phone or the
 * chosen folders change. Use it from the main thread.
 */
class CatalogLoader(private val context: Context, private val settings: Settings) {

    /** What the library depends on besides the files themselves. */
    private data class Key(
        val musicFolders: Set<String>,
        val bookFolders: Set<String>,
        val favorites: Set<String>?,
        val hasPermission: Boolean,
        val language: String,
    )

    private var tree: BrowseTree? = null
    private var key: Key? = null
    @Volatile
    private var stations: List<RadioStation>? = null

    @Volatile
    private var stale = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            stale = true
        }
    }

    init {
        context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    fun release() = context.contentResolver.unregisterContentObserver(observer)

    suspend fun tree(): BrowseTree {
        val wanted = Key(
            musicFolders = settings.musicFolders.value,
            bookFolders = settings.bookFolders.value,
            favorites = settings.radioFavorites.value,
            hasPermission = MediaLibrary.hasPermission(context),
            language = context.resources.configuration.locales.toLanguageTags(),
        )
        tree?.let { if (!stale && key == wanted) return it }
        stale = false
        val loaded = withContext(Dispatchers.IO) { BrowseTree(read(wanted), labels()) }
        tree = loaded
        key = wanted
        return loaded
    }

    private fun read(key: Key): Catalog {
        val tracks = if (key.hasPermission) MediaLibrary.load(context) else emptyList()
        val files = if (key.hasPermission && key.musicFolders.isNotEmpty()) PlaylistLibrary.load(context) else emptyList()
        val radio = stations ?: loadStations().also { stations = it }
        return Catalog(
            music = FolderSelection.tracksIn(tracks, key.musicFolders),
            playlists = PlaylistFiles.inFolders(tracks, files, key.musicFolders),
            books = Book.inFolders(tracks, key.bookFolders),
            stations = radio,
            favorites = key.favorites ?: RadioStations.defaultFavorites(radio),
        )
    }

    private fun loadStations(): List<RadioStation> = try {
        RadioStations.load(context)
    } catch (_: Exception) {
        emptyList()
    }

    private fun labels(): BrowseLabels {
        val resources = context.resources
        return BrowseLabels(
            music = resources.getString(R.string.tab_music),
            books = resources.getString(R.string.tab_audiobooks),
            radio = resources.getString(R.string.tab_radio),
            shuffle = resources.getString(R.string.action_shuffle),
            playlists = resources.getString(R.string.playlists_title),
            allStations = resources.getString(R.string.radio_all),
            trackCount = { resources.getQuantityString(R.plurals.track_count, it, it) },
            chapterCount = { resources.getQuantityString(R.plurals.chapter_count, it, it) },
        )
    }
}
