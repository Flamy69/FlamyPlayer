package io.github.flamy69.flamyplayer.data

import android.content.Context
import androidx.core.content.edit
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Section { MUSIC, BOOKS, VIDEO }

/** Everything the app remembers between launches. Small enough for plain SharedPreferences. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val progressPrefs = context.getSharedPreferences("book_progress", Context.MODE_PRIVATE)
    private val boostPrefs = context.getSharedPreferences("boost", Context.MODE_PRIVATE)

    private val _musicFolders = MutableStateFlow(readFolders(KEY_MUSIC_FOLDERS))
    val musicFolders: StateFlow<Set<String>> = _musicFolders.asStateFlow()

    private val _bookFolders = MutableStateFlow(readFolders(KEY_BOOK_FOLDERS))
    val bookFolders: StateFlow<Set<String>> = _bookFolders.asStateFlow()

    private val _shuffle = MutableStateFlow(prefs.getBoolean(KEY_SHUFFLE, true))
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow(prefs.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF))
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _bookSpeed = MutableStateFlow(prefs.getFloat(KEY_BOOK_SPEED, 1f))
    val bookSpeed: StateFlow<Float> = _bookSpeed.asStateFlow()

    private val _bookProgress = MutableStateFlow(readProgress())
    val bookProgress: StateFlow<Map<String, BookProgress>> = _bookProgress.asStateFlow()

    private val _boosts = MutableStateFlow(readBoosts())

    /**
     * How much louder to play, in decibels: one value per audiobook and one for music as a
     * whole. Look values up under [boostKey]; what is missing is not amplified.
     */
    val boosts: StateFlow<Map<String, Int>> = _boosts.asStateFlow()

    var lastSection: Section
        get() = prefs.getString(KEY_SECTION, null).let { name -> Section.entries.firstOrNull { it.name == name } }
            ?: Section.MUSIC
        set(value) = prefs.edit { putString(KEY_SECTION, value.name) }

    /** The folders picked for music or audiobooks; the video section has no folder selection. */
    fun folders(section: Section): StateFlow<Set<String>> =
        if (section == Section.MUSIC) musicFolders else bookFolders

    fun setFolders(section: Section, folders: Set<String>) {
        if (section == Section.MUSIC) {
            _musicFolders.value = folders
            prefs.edit { putStringSet(KEY_MUSIC_FOLDERS, folders) }
        } else {
            _bookFolders.value = folders
            prefs.edit { putStringSet(KEY_BOOK_FOLDERS, folders) }
        }
    }

    fun setShuffle(enabled: Boolean) {
        _shuffle.value = enabled
        prefs.edit { putBoolean(KEY_SHUFFLE, enabled) }
    }

    fun setRepeatMode(mode: Int) {
        _repeatMode.value = mode
        prefs.edit { putInt(KEY_REPEAT, mode) }
    }

    fun setBookSpeed(speed: Float) {
        _bookSpeed.value = speed
        prefs.edit { putFloat(KEY_BOOK_SPEED, speed) }
    }

    fun saveBookProgress(bookFolder: String, trackId: Long, positionMs: Long) {
        _bookProgress.update { it + (bookFolder to BookProgress(trackId, positionMs)) }
        progressPrefs.edit { putString(bookFolder, "$trackId:$positionMs") }
    }

    fun clearBookProgress(bookFolder: String) {
        _bookProgress.update { it - bookFolder }
        progressPrefs.edit { remove(bookFolder) }
    }

    /** Sets the amplification of the audiobook in [bookFolder], or of music if that is `null`. */
    fun setBoost(bookFolder: String?, decibels: Int) {
        val key = boostKey(bookFolder)
        if (decibels > 0) {
            _boosts.update { it + (key to decibels) }
            boostPrefs.edit { putInt(key, decibels) }
        } else {
            _boosts.update { it - key }
            boostPrefs.edit { remove(key) }
        }
    }

    private fun readBoosts(): Map<String, Int> {
        val result = HashMap<String, Int>()
        for ((key, value) in boostPrefs.all) {
            if (value is Int && value > 0) result[key] = value
        }
        return result
    }

    // The set returned by SharedPreferences must not be modified, hence the copy.
    private fun readFolders(key: String): Set<String> = prefs.getStringSet(key, null)?.toSet().orEmpty()

    private fun readProgress(): Map<String, BookProgress> {
        val result = HashMap<String, BookProgress>()
        for ((folder, value) in progressPrefs.all) {
            val parts = (value as? String)?.split(':') ?: continue
            val trackId = parts.getOrNull(0)?.toLongOrNull() ?: continue
            val position = parts.getOrNull(1)?.toLongOrNull() ?: continue
            result[folder] = BookProgress(trackId, position)
        }
        return result
    }

    companion object {
        /** Where [boosts] keeps the value of the audiobook in [bookFolder], or of music for `null`. */
        fun boostKey(bookFolder: String?): String = bookFolder ?: "music"

        private const val KEY_MUSIC_FOLDERS = "music_folders"
        private const val KEY_BOOK_FOLDERS = "book_folders"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT = "repeat_mode"
        private const val KEY_BOOK_SPEED = "book_speed"
        private const val KEY_SECTION = "section"
    }
}
