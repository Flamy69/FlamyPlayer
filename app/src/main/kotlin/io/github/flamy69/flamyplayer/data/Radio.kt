package io.github.flamy69.flamyplayer.data

import android.content.Context
import org.json.JSONArray
import java.text.Collator
import java.util.Locale

/** An internet radio station. */
data class RadioStation(
    val id: String,
    val name: String,
    /** Address of the stream itself, not of a playlist file. */
    val url: String,
    /** A few words about the genre; may be empty. */
    val tags: String,
    val codec: String,
    /** In kb/s; zero if the station does not say. */
    val bitrate: Int,
) {
    /** Identifies the station to the player. */
    val mediaId: String get() = MEDIA_ID_PREFIX + id

    /** `rock · MP3 128 kb/s` */
    val description: String
        get() = listOfNotNull(
            tags.ifEmpty { null },
            (codec + if (bitrate > 0) " $bitrate kb/s" else "").ifBlank { null },
        ).joinToString(" · ")

    fun matches(query: String): Boolean =
        name.contains(query, ignoreCase = true) || tags.contains(query, ignoreCase = true)

    private companion object {
        const val MEDIA_ID_PREFIX = "radio:"
    }
}

/**
 * The stations that come with the app: Czech radio stations from the community database
 * radio-browser.info, checked to be on air when the app was built.
 */
object RadioStations {

    /** Favorites of somebody who has not chosen any yet. */
    private val FEATURED = setOf("Rádio Beat", "Rock Radio", "Evropa 2", "Frekvence 1")

    private const val FILE = "radio_cz.json"

    /** Reads the list, sorted by name. Blocks; call it from a background thread. */
    fun load(context: Context): List<RadioStation> {
        val text = context.assets.open(FILE).use { it.readBytes().toString(Charsets.UTF_8) }
        val array = JSONArray(text)
        val stations = ArrayList<RadioStation>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url")
            val name = item.optString("name")
            if (url.isEmpty() || name.isEmpty()) continue
            stations += RadioStation(
                id = item.optString("id").ifEmpty { url },
                name = name,
                url = url,
                tags = item.optString("tags"),
                codec = item.optString("codec"),
                bitrate = item.optInt("bitrate"),
            )
        }
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        return stations.sortedWith(compareBy(collator) { it.name })
    }

    fun defaultFavorites(stations: List<RadioStation>): Set<String> =
        stations.filter { it.name in FEATURED }.map { it.id }.toSet()
}
