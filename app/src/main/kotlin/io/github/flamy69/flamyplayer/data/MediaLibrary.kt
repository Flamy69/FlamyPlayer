package io.github.flamy69.flamyplayer.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * Reads the audio library from the system media index.
 *
 * Android already keeps an index of every audio file on the device together with its tags,
 * so the whole library comes back from a single query and no file has to be opened.
 */
object MediaLibrary {

    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /** Everything the app asks for on first launch; only the audio permission is mandatory. */
    val permissionsToRequest: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(audioPermission, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(audioPermission)
        }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED

    /**
     * Loads all audio files, sorted folder by folder. Call from a background thread.
     * Returns an empty list if the permission was revoked in the meantime.
     */
    fun load(context: Context): List<Track> = try {
        query(context)
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun query(context: Context): List<Track> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
        )
        val tracks = ArrayList<Track>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            tracks.ensureCapacity(cursor.count)
            while (cursor.moveToNext()) {
                val path = cursor.getString(pathColumn) ?: continue
                tracks += Track(
                    id = cursor.getLong(idColumn),
                    title = cursor.getString(titleColumn)?.takeIf { it.isNotBlank() }
                        ?: path.substringAfterLast('/'),
                    artist = cursor.getString(artistColumn).knownOrNull(),
                    album = cursor.getString(albumColumn).knownOrNull(),
                    durationMs = cursor.getLong(durationColumn),
                    path = path,
                )
            }
        }
        return sortByFolderAndName(tracks)
    }

    private fun String?.knownOrNull(): String? =
        this?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
}
