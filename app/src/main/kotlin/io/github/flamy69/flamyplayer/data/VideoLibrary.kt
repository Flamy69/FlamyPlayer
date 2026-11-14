package io.github.flamy69.flamyplayer.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/** Reads the videos on the device from the system media index, the same way as [MediaLibrary]. */
object VideoLibrary {

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /**
     * `true` if at least some videos can be read. From Android 14 on the user may share only
     * selected videos instead of all of them; the media index then simply returns those.
     */
    fun hasPermission(context: Context): Boolean =
        isGranted(context, permission) ||
            (Build.VERSION.SDK_INT >= 34 && isGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Loads all videos. Call from a background thread. */
    fun load(context: Context): List<Video> = try {
        query(context)
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun query(context: Context): List<Video> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
        )
        val videos = ArrayList<Video>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            videos.ensureCapacity(cursor.count)
            while (cursor.moveToNext()) {
                val path = cursor.getString(pathColumn) ?: continue
                videos += Video(
                    id = cursor.getLong(idColumn),
                    path = path,
                    durationMs = cursor.getLong(durationColumn),
                    sizeBytes = cursor.getLong(sizeColumn),
                )
            }
        }
        return videos
    }
}
