package io.github.flamy69.flamyplayer.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.flamy69.flamyplayer.data.Track
import java.util.concurrent.atomic.AtomicReference

/** A playlist together with the place to start from. */
class PlayRequest(
    val tracks: List<Track>,
    val startIndex: Int,
    val startPositionMs: Long,
    /** Folder of the audiobook the tracks belong to, or `null` for music. */
    val bookFolder: String? = null,
    val bookTitle: String? = null,
)

/**
 * Hands playlists from the UI to the playback service.
 *
 * Both live in the same process, so instead of pushing thousands of media items through the
 * media controller, the UI parks the request here and sends a single placeholder item. The
 * service swaps the placeholder for the real playlist.
 */
object PlaybackQueue {

    const val PLACEHOLDER_ID = "flamyplayer:pending-queue"
    const val EXTRA_BOOK_FOLDER = "flamyplayer.book_folder"

    private val pending = AtomicReference<PlayRequest?>()

    /** The request the player is currently working through, if it came from this app. */
    @Volatile
    var active: PlayRequest? = null

    fun submit(request: PlayRequest): MediaItem {
        pending.set(request)
        return MediaItem.Builder().setMediaId(PLACEHOLDER_ID).build()
    }

    fun take(): PlayRequest? = pending.getAndSet(null)
}

fun Track.toMediaItem(request: PlayRequest): MediaItem {
    val metadata = MediaMetadata.Builder().setIsPlayable(true).setIsBrowsable(false)
    if (request.bookFolder != null) {
        metadata
            .setTitle(displayName)
            .setArtist(request.bookTitle)
            .setAlbumTitle(request.bookTitle)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .setExtras(Bundle().apply { putString(PlaybackQueue.EXTRA_BOOK_FOLDER, request.bookFolder) })
    } else {
        metadata
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
    }
    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(metadata.build())
        .build()
}
