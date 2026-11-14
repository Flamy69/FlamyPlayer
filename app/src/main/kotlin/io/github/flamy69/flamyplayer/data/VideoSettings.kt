package io.github.flamy69.flamyplayer.data

import android.content.Context
import androidx.core.content.edit
import io.github.flamy69.flamyplayer.network.SmbServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** How subtitles are drawn. */
data class SubtitleStyle(
    /** Text size relative to the standard size. */
    val scale: Float = 1f,
    val color: Int = COLORS.first(),
    /** Distance of the text from the bottom edge, as a fraction of the screen height. */
    val position: Float = 0.06f,
    /** A dark box behind the text instead of an outline around the letters. */
    val background: Boolean = false,
) {
    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.5f
        const val MAX_POSITION = 0.9f

        /** White, yellow, green, cyan, orange, pink. */
        val COLORS = listOf(
            0xFFFFFFFF.toInt(),
            0xFFFFEB3B.toInt(),
            0xFF8CF58C.toInt(),
            0xFF7FE9FF.toInt(),
            0xFFFFB74D.toInt(),
            0xFFFF9BDD.toInt(),
        )
    }
}

enum class OrientationMode { AUTO, LANDSCAPE, PORTRAIT }

enum class VideoResizeMode { FIT, CROP, STRETCH }

/** What the video section remembers between launches. */
class VideoSettings(context: Context) {

    private val prefs = context.getSharedPreferences("video", Context.MODE_PRIVATE)
    private val positionPrefs = context.getSharedPreferences("video_positions", Context.MODE_PRIVATE)

    private val _servers = MutableStateFlow(readServers())
    val servers: StateFlow<List<SmbServer>> = _servers.asStateFlow()

    private val _subtitleStyle = MutableStateFlow(readSubtitleStyle())
    val subtitleStyle: StateFlow<SubtitleStyle> = _subtitleStyle.asStateFlow()

    private val _positions = MutableStateFlow(readPositions())

    /** Where the viewer stopped in each unfinished video, in milliseconds. */
    val positions: StateFlow<Map<String, Long>> = _positions.asStateFlow()

    var orientation: OrientationMode
        get() = enumOf(prefs.getString(KEY_ORIENTATION, null), OrientationMode.AUTO)
        set(value) = prefs.edit { putString(KEY_ORIENTATION, value.name) }

    var resizeMode: VideoResizeMode
        get() = enumOf(prefs.getString(KEY_RESIZE, null), VideoResizeMode.FIT)
        set(value) = prefs.edit { putString(KEY_RESIZE, value.name) }

    /** Screen brightness of the player from 0 to 1, or a negative value to follow the system. */
    var brightness: Float
        get() = prefs.getFloat(KEY_BRIGHTNESS, -1f)
        set(value) = prefs.edit { putFloat(KEY_BRIGHTNESS, value) }

    var subtitlesEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUBTITLES_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_SUBTITLES_ENABLED, value) }

    /** Language of the subtitles the viewer picked last; preferred in the next video too. */
    var subtitleLanguage: String?
        get() = prefs.getString(KEY_SUBTITLE_LANGUAGE, null)
        set(value) = prefs.edit { putString(KEY_SUBTITLE_LANGUAGE, value) }

    var audioLanguage: String?
        get() = prefs.getString(KEY_AUDIO_LANGUAGE, null)
        set(value) = prefs.edit { putString(KEY_AUDIO_LANGUAGE, value) }

    fun server(id: String): SmbServer? = _servers.value.firstOrNull { it.id == id }

    /** Adds a network location, or replaces the one with the same id. */
    fun saveServer(server: SmbServer) {
        _servers.update { servers ->
            if (servers.any { it.id == server.id }) {
                servers.map { if (it.id == server.id) server else it }
            } else {
                servers + server
            }
        }
        writeServers()
    }

    fun removeServer(id: String) {
        _servers.update { servers -> servers.filterNot { it.id == id } }
        writeServers()
        prefs.edit { remove(KEY_LAST_PATH + id) }
    }

    /** The folder below the root of a network location that was open last. */
    fun lastPath(serverId: String): List<String> =
        prefs.getString(KEY_LAST_PATH + serverId, null)?.let(::parseStrings).orEmpty()

    fun setLastPath(serverId: String, path: List<String>) {
        prefs.edit { putString(KEY_LAST_PATH + serverId, JSONArray(path).toString()) }
    }

    fun setSubtitleStyle(style: SubtitleStyle) {
        _subtitleStyle.value = style
        prefs.edit {
            putFloat(KEY_SUBTITLE_SCALE, style.scale)
            putInt(KEY_SUBTITLE_COLOR, style.color)
            putFloat(KEY_SUBTITLE_POSITION, style.position)
            putBoolean(KEY_SUBTITLE_BACKGROUND, style.background)
        }
    }

    fun savePosition(key: String, positionMs: Long) {
        _positions.update { it + (key to positionMs) }
        positionPrefs.edit { putString(key, "$positionMs:${System.currentTimeMillis()}") }
        if (_positions.value.size > MAX_POSITIONS) forgetOldestPositions()
    }

    fun clearPosition(key: String) {
        if (key !in _positions.value) return
        _positions.update { it - key }
        positionPrefs.edit { remove(key) }
    }

    private fun forgetOldestPositions() {
        val oldest = positionPrefs.all.entries
            .sortedBy { (it.value as? String)?.substringAfter(':')?.toLongOrNull() ?: 0L }
            .take(MAX_POSITIONS / 4)
            .map { it.key }
        _positions.update { it - oldest.toSet() }
        positionPrefs.edit { oldest.forEach { remove(it) } }
    }

    private fun readPositions(): Map<String, Long> {
        val result = HashMap<String, Long>()
        for ((key, value) in positionPrefs.all) {
            result[key] = (value as? String)?.substringBefore(':')?.toLongOrNull() ?: continue
        }
        return result
    }

    private fun readSubtitleStyle(): SubtitleStyle {
        val defaults = SubtitleStyle()
        return SubtitleStyle(
            scale = prefs.getFloat(KEY_SUBTITLE_SCALE, defaults.scale)
                .coerceIn(SubtitleStyle.MIN_SCALE, SubtitleStyle.MAX_SCALE),
            color = prefs.getInt(KEY_SUBTITLE_COLOR, defaults.color),
            position = prefs.getFloat(KEY_SUBTITLE_POSITION, defaults.position)
                .coerceIn(0f, SubtitleStyle.MAX_POSITION),
            background = prefs.getBoolean(KEY_SUBTITLE_BACKGROUND, defaults.background),
        )
    }

    private fun readServers(): List<SmbServer> = try {
        val array = JSONArray(prefs.getString(KEY_SERVERS, null) ?: "[]")
        (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            SmbServer(
                id = json.getString("id"),
                name = json.optString("name"),
                host = json.getString("host"),
                root = parseStrings(json.optString("root", "[]")),
                user = json.optString("user"),
                secret = json.optString("secret"),
            )
        }
    } catch (_: JSONException) {
        emptyList()
    }

    private fun writeServers() {
        val array = JSONArray()
        for (server in _servers.value) {
            array.put(
                JSONObject()
                    .put("id", server.id)
                    .put("name", server.name)
                    .put("host", server.host)
                    .put("root", JSONArray(server.root).toString())
                    .put("user", server.user)
                    .put("secret", server.secret),
            )
        }
        prefs.edit { putString(KEY_SERVERS, array.toString()) }
    }

    private fun parseStrings(json: String): List<String> = try {
        val array = JSONArray(json)
        (0 until array.length()).map(array::getString)
    } catch (_: JSONException) {
        emptyList()
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY_SERVERS = "servers"
        const val KEY_LAST_PATH = "last_path_"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_RESIZE = "resize_mode"
        const val KEY_BRIGHTNESS = "brightness"
        const val KEY_SUBTITLES_ENABLED = "subtitles_enabled"
        const val KEY_SUBTITLE_LANGUAGE = "subtitle_language"
        const val KEY_AUDIO_LANGUAGE = "audio_language"
        const val KEY_SUBTITLE_SCALE = "subtitle_scale"
        const val KEY_SUBTITLE_COLOR = "subtitle_color"
        const val KEY_SUBTITLE_POSITION = "subtitle_position"
        const val KEY_SUBTITLE_BACKGROUND = "subtitle_background"
        const val MAX_POSITIONS = 400
    }
}
