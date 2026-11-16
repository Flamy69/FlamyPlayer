package io.github.flamy69.flamyplayer.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.flamy69.flamyplayer.data.RadioStation
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
    /** Radio stations to play instead of [tracks]. */
    val stations: List<RadioStation> = emptyList(),
    /** Path of the playlist file the tracks come from, if they are not the big playlist. */
    val playlistPath: String? = null,
) {
    /** What the player is given to play. */
    fun mediaItems(): List<MediaItem> =
        if (stations.isNotEmpty()) stations.map { it.toMediaItem() } else tracks.map { it.toMediaItem(this) }
}

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

    /** Path and length of a music track, for keeping its listening history. */
    const val EXTRA_TRACK_PATH = "flamyplayer.track_path"
    const val EXTRA_TRACK_DURATION = "flamyplayer.track_duration"

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

/** Whether this is a radio station, not a file. */
val MediaItem.isRadio: Boolean get() = mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_RADIO_STATION

fun RadioStation.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setUri(url)
    .setMediaMetadata(
        // No title: that place is for what the station says is on air right now.
        MediaMetadata.Builder()
            .setStation(name)
            .setArtist(name)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build(),
    )
    .build()

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
            .setExtras(
                Bundle().apply {
                    putString(PlaybackQueue.EXTRA_TRACK_PATH, path)
                    putLong(PlaybackQueue.EXTRA_TRACK_DURATION, durationMs)
                },
            )
    }
    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(metadata.build())
        .build()
}
