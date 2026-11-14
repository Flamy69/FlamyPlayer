package io.github.flamy69.flamyplayer.video

import android.net.Uri
import io.github.flamy69.flamyplayer.network.SmbServer

/** One video the player can show. */
class VideoItem(
    val uri: Uri,
    val title: String,
    /** Identifies the video when remembering where the viewer stopped. */
    val key: String,
    val subtitles: SubtitleSource,
)

/** Where to look for subtitle files that belong to a video. */
sealed interface SubtitleSource {

    /** A file on the device; subtitles are looked up next to it. */
    class Device(val videoPath: String) : SubtitleSource

    /** A file on a share; [fileNames] are the subtitle files found in its [directory]. */
    class Network(
        val server: SmbServer,
        /** Share name followed by the folders. */
        val directory: List<String>,
        val videoName: String,
        val fileNames: List<String>,
    ) : SubtitleSource
}

/**
 * Hands the videos of a folder from the browser to the player. Both live in the same process,
 * so there is no need to squeeze the list through an intent.
 */
object VideoQueue {

    var items: List<VideoItem> = emptyList()
        private set

    var startIndex: Int = 0
        private set

    fun set(items: List<VideoItem>, startIndex: Int) {
        this.items = items
        this.startIndex = startIndex
    }
}
