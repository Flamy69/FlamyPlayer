package io.github.flamy69.flamyplayer.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

/** One audio file known to the system media index. */
data class Track(
    val id: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    /** Absolute path of the file, e.g. `/storage/emulated/0/Music/Album/01 Song.mp3`. */
    val path: String,
) {
    /** Absolute path of the directory that contains the file. */
    val folder: String = path.substringBeforeLast('/', "")

    val fileName: String get() = path.substring(path.lastIndexOf('/') + 1)

    /** File name without its extension; the most reliable label for audiobook chapters. */
    val displayName: String get() = fileName.substringBeforeLast('.').ifEmpty { fileName }

    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    fun matches(query: String): Boolean =
        title.contains(query, ignoreCase = true) ||
            artist?.contains(query, ignoreCase = true) == true ||
            album?.contains(query, ignoreCase = true) == true ||
            fileName.contains(query, ignoreCase = true)
}
