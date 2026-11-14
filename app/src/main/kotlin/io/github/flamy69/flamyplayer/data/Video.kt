package io.github.flamy69.flamyplayer.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

/** One video file known to the system media index. */
data class Video(
    val id: Long,
    /** Absolute path of the file, e.g. `/storage/emulated/0/Movies/Film.mkv`. */
    val path: String,
    val durationMs: Long,
    val sizeBytes: Long,
) {
    /** Absolute path of the directory that contains the file. */
    val folder: String = path.substringBeforeLast('/', "")

    val fileName: String get() = path.substring(path.lastIndexOf('/') + 1)

    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
}

/** A directory on the device together with the videos that sit directly in it. */
class VideoFolder(val path: String, val videos: List<Video>) {
    val name: String get() = path.substringAfterLast('/')
}

/** Groups videos by directory; folders and the files inside them are in natural order. */
fun groupVideosByFolder(videos: List<Video>): List<VideoFolder> =
    videos.groupBy { it.folder }
        .map { (folder, files) -> VideoFolder(folder, files.sortedWith(compareBy(NaturalOrder) { it.fileName })) }
        .sortedWith(compareBy<VideoFolder, String>(NaturalOrder) { it.name }.thenBy { it.path })
