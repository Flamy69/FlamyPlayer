package io.github.flamy69.flamyplayer.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.net.URLDecoder
import java.util.Locale

/** A playlist file as it was read: the files it names, not yet matched to tracks. */
class PlaylistFile(
    /** Absolute path of the playlist file itself. */
    val path: String,
    /** What the file lists, line by line: paths as their author wrote them. */
    val entries: List<String>,
) {
    /** Absolute path of the directory that contains the playlist file. */
    val folder: String = path.substringBeforeLast('/', "")

    /** File name without its extension. */
    val name: String get() = path.substringAfterLast('/').substringBeforeLast('.')
}

/**
 * A playlist whose entries were found in the music library. It lives next to the big list of
 * all tracks, never inside it: a track that is also named in a playlist is still only one
 * track there.
 */
class Playlist(val path: String, val name: String, val tracks: List<Track>)

/** Reads the usual playlist formats and finds the tracks they mean. */
object PlaylistFiles {

    private val extensions = setOf("m3u", "m3u8", "pls")

    fun isPlaylist(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in extensions

    /** The entries of a playlist file with the content [text], in the order they are played. */
    fun parse(fileName: String, text: String): List<String> {
        val lines = text.lineSequence().map { it.trim().trimStart(BYTE_ORDER_MARK) }.filter { it.isNotEmpty() }
        return if (fileName.endsWith(".pls", ignoreCase = true)) {
            // File1=..., File2=...; the other keys are titles and lengths.
            lines.filter { it.startsWith("file", ignoreCase = true) && '=' in it }
                .mapNotNull { line ->
                    val number = line.substringBefore('=').drop(4).trim().toIntOrNull() ?: return@mapNotNull null
                    number to line.substringAfter('=').trim()
                }
                .sortedBy { it.first }
                .map { it.second }
                .filter { it.isNotEmpty() }
                .toList()
        } else {
            // M3U: one file per line; lines starting with # are comments and titles.
            lines.filter { !it.startsWith('#') }.toList()
        }
    }

    /**
     * Turns an entry into the absolute path it means for a playlist in [playlistFolder].
     * Returns `null` for entries that are not files, such as internet streams.
     */
    internal fun absolutePath(entry: String, playlistFolder: String): String? {
        var path = entry.replace('\\', '/')
        if (path.startsWith("file://", ignoreCase = true)) {
            path = try {
                // "+" is a plus in a file address, not a space.
                URLDecoder.decode(path.substring(7).replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                path.substring(7)
            }
            // file:///C:/Music/... and file://localhost/...
            path = path.removePrefix("localhost")
            if (WINDOWS_DRIVE.containsMatchIn(path.removePrefix("/"))) path = path.removePrefix("/")
        } else if (URL.containsMatchIn(path)) {
            return null
        }
        val absolute = path.startsWith('/') || WINDOWS_DRIVE.containsMatchIn(path)
        val segments = ArrayList<String>()
        for (segment in (if (absolute) path else "$playlistFolder/$path").split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments += segment
            }
        }
        return "/" + segments.joinToString("/")
    }

    /**
     * Finds the tracks of the library that playlist entries mean.
     *
     * A playlist made on the phone names its files exactly. One copied over from a computer
     * names them the way that computer saw them (`C:\Music\Artist\Album\Song.mp3`); such an
     * entry is matched by the end of its path, as far as it agrees with exactly one track.
     */
    class Matcher(tracks: List<Track>) {

        private val byPath = HashMap<String, Track>(tracks.size * 2)
        private val byLowerPath = HashMap<String, Track>(tracks.size * 2)
        private val byFileName = HashMap<String, MutableList<Track>>()

        init {
            for (track in tracks) {
                byPath.putIfAbsent(track.path, track)
                byLowerPath.putIfAbsent(track.path.lowercase(Locale.ROOT), track)
                byFileName.getOrPut(track.fileName.lowercase(Locale.ROOT)) { ArrayList(1) } += track
            }
        }

        fun resolve(file: PlaylistFile): Playlist =
            Playlist(file.path, file.name, file.entries.mapNotNull { find(it, file.folder) })

        fun find(entry: String, playlistFolder: String): Track? {
            val path = absolutePath(entry, playlistFolder) ?: return null
            byPath[path]?.let { return it }
            val lower = path.lowercase(Locale.ROOT)
            byLowerPath[lower]?.let { return it }

            val wanted = lower.split('/')
            val candidates = byFileName[wanted.last()] ?: return null
            if (candidates.size == 1) return candidates[0]
            var best: Track? = null
            var bestLength = 0
            var tied = false
            for (candidate in candidates) {
                val length = commonEnd(wanted, candidate.path.lowercase(Locale.ROOT).split('/'))
                if (length > bestLength) {
                    best = candidate
                    bestLength = length
                    tied = false
                } else if (length == bestLength) {
                    tied = true
                }
            }
            // Several tracks fit equally well: better to leave one out than to play the wrong one.
            return best.takeUnless { tied }
        }

        /** How many path segments [a] and [b] share, counted from their ends. */
        private fun commonEnd(a: List<String>, b: List<String>): Int {
            var count = 0
            while (count < a.size && count < b.size && a[a.size - 1 - count] == b[b.size - 1 - count]) count++
            return count
        }
    }

    /**
     * The playlist [files] inside the selected music [folders], matched to [tracks] of the
     * library. Playlists that name nothing the library has are left out.
     */
    fun inFolders(tracks: List<Track>, files: List<PlaylistFile>, folders: Set<String>): List<Playlist> {
        val wanted = files.filter { FolderSelection.covers(folders, it.folder) }
        if (wanted.isEmpty()) return emptyList()
        val matcher = Matcher(tracks)
        return wanted.map(matcher::resolve)
            .filter { it.tracks.isNotEmpty() }
            .sortedWith(compareBy(NaturalOrder) { it.name })
    }

    private const val BYTE_ORDER_MARK = '\uFEFF'
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:/")
    private val URL = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
}

/** Finds the playlist files on the device. */
object PlaylistLibrary {

    private const val MAX_FILES = 300
    private const val MAX_BYTES = 2 * 1024 * 1024

    /**
     * Reads all playlist files the system media index knows. Call from a background thread.
     * Returns an empty list if the permission was revoked in the meantime.
     */
    fun load(context: Context): List<PlaylistFile> = try {
        query(context)
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    private fun query(context: Context): List<PlaylistFile> {
        val files = MediaStore.Files.getContentUri("external")
        val found = ArrayList<Pair<Long, String>>()
        @Suppress("DEPRECATION") // Deprecated for writing playlists; reading them is what it is for.
        val playlistType = MediaStore.Files.FileColumns.MEDIA_TYPE_PLAYLIST
        context.contentResolver.query(
            files,
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA),
            MediaStore.Files.FileColumns.MEDIA_TYPE + " = " + playlistType,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext() && found.size < MAX_FILES) {
                val path = cursor.getString(1) ?: continue
                if (PlaylistFiles.isPlaylist(path)) found += cursor.getLong(0) to path
            }
        }
        val language = Locale.getDefault().language
        return found.mapNotNull { (id, path) ->
            val bytes = read(context, id, path) ?: return@mapNotNull null
            // Old playlists are in the code page of the computer they were made on.
            val text = SubtitleFiles.decode(bytes, SubtitleFiles.legacyCharset(language))
            PlaylistFile(path, PlaylistFiles.parse(path, text))
        }
    }

    private fun read(context: Context, id: Long, path: String): ByteArray? {
        val bytes = try {
            context.contentResolver
                .openInputStream(ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id))
                ?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } ?: try {
            File(path).takeIf { it.length() <= MAX_BYTES }?.readBytes()
        } catch (_: Exception) {
            null
        }
        return bytes?.takeIf { it.size <= MAX_BYTES }
    }
}
