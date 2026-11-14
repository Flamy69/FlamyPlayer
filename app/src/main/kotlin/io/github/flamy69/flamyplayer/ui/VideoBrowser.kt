package io.github.flamy69.flamyplayer.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.flamy69.flamyplayer.FlamyApp
import io.github.flamy69.flamyplayer.data.NaturalOrder
import io.github.flamy69.flamyplayer.data.SubtitleFiles
import io.github.flamy69.flamyplayer.data.Video
import io.github.flamy69.flamyplayer.data.VideoFiles
import io.github.flamy69.flamyplayer.data.VideoFolder
import io.github.flamy69.flamyplayer.data.VideoLibrary
import io.github.flamy69.flamyplayer.data.VideoSettings
import io.github.flamy69.flamyplayer.data.groupVideosByFolder
import io.github.flamy69.flamyplayer.network.SecretStore
import io.github.flamy69.flamyplayer.network.SmbAddress
import io.github.flamy69.flamyplayer.network.SmbEntry
import io.github.flamy69.flamyplayer.network.SmbException
import io.github.flamy69.flamyplayer.network.SmbServer
import io.github.flamy69.flamyplayer.network.SmbUri
import io.github.flamy69.flamyplayer.video.SubtitleSource
import io.github.flamy69.flamyplayer.video.VideoItem
import io.github.flamy69.flamyplayer.video.VideoPlayerActivity
import io.github.flamy69.flamyplayer.video.VideoQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Where in the video section the user is. */
sealed interface VideoLocation {
    /** The overview: network locations and the folders of the device. */
    data object Home : VideoLocation

    /** A folder of the device. */
    data class Device(val folder: String) : VideoLocation

    /** A folder of a network location; [path] is relative to the root of that location. */
    data class Network(val serverId: String, val path: List<String>) : VideoLocation
}

/** The content of the network folder that is open. */
sealed interface NetworkListing {
    data object Loading : NetworkListing
    class Ready(val entries: List<SmbEntry>) : NetworkListing
    class Failed(val reason: SmbException.Reason) : NetworkListing
}

/** Access to devices on the local network needs the user's consent from Android 17 on. */
object LocalNetwork {

    val permission: String?
        get() = if (Build.VERSION.SDK_INT >= 37) Manifest.permission.ACCESS_LOCAL_NETWORK else null

    fun isAllowed(context: Context): Boolean {
        val needed = permission ?: return true
        return ContextCompat.checkSelfPermission(context, needed) == PackageManager.PERMISSION_GRANTED
    }
}

/** State and actions of the video section: the videos on the device and the network folders. */
class VideoBrowser(private val app: FlamyApp, private val scope: CoroutineScope) {

    val settings: VideoSettings = app.videoSettings

    private val _hasPermission = MutableStateFlow(VideoLibrary.hasPermission(app))
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val _folders = MutableStateFlow<List<VideoFolder>?>(null)

    /** The folders of the device that contain videos; `null` until they have been loaded. */
    val folders: StateFlow<List<VideoFolder>?> = _folders.asStateFlow()

    private val _location = MutableStateFlow<VideoLocation>(VideoLocation.Home)
    val location: StateFlow<VideoLocation> = _location.asStateFlow()

    private val _listing = MutableStateFlow<NetworkListing>(NetworkListing.Loading)
    val listing: StateFlow<NetworkListing> = _listing.asStateFlow()

    /** Folders seen in this session; going back up shows them without asking the server again. */
    private val cache = HashMap<VideoLocation.Network, List<SmbEntry>>()

    private var deviceJob: Job? = null
    private var listJob: Job? = null
    private var deviceWanted = false

    /** Loads the videos of the device. Does nothing until the video section was opened once. */
    fun refreshDevice(force: Boolean = false) {
        if (force) deviceWanted = true
        val granted = VideoLibrary.hasPermission(app)
        _hasPermission.value = granted
        if (!deviceWanted || !granted || deviceJob?.isActive == true) return
        deviceJob = scope.launch {
            _folders.value = withContext(Dispatchers.IO) { groupVideosByFolder(VideoLibrary.load(app)) }
        }
    }

    fun openFolder(folder: VideoFolder) {
        _location.value = VideoLocation.Device(folder.path)
    }

    /** Opens a network location where the user left it the last time. */
    fun openServer(server: SmbServer) {
        show(VideoLocation.Network(server.id, settings.lastPath(server.id)), fallBackToRoot = true)
    }

    fun openDirectory(name: String) {
        val current = _location.value as? VideoLocation.Network ?: return
        show(current.copy(path = current.path + name))
    }

    /** Goes one level up. Returns `false` when already at the overview. */
    fun up(): Boolean {
        when (val current = _location.value) {
            VideoLocation.Home -> return false
            is VideoLocation.Device -> _location.value = VideoLocation.Home
            is VideoLocation.Network -> if (current.path.isEmpty()) {
                listJob?.cancel()
                _location.value = VideoLocation.Home
            } else {
                show(current.copy(path = current.path.dropLast(1)))
            }
        }
        return true
    }

    /** Asks the server for the open folder again. */
    fun reload() {
        val current = _location.value as? VideoLocation.Network ?: return
        cache.remove(current)
        show(current)
    }

    /**
     * Saves a new or edited network location. Returns `false` if [address] cannot be understood.
     * An empty [password] keeps the stored one, unless the user name was removed as well.
     */
    fun saveServer(existing: SmbServer?, name: String, address: String, user: String, password: String): Boolean {
        val (host, root) = SmbAddress.parse(address) ?: return false
        val id = existing?.id ?: UUID.randomUUID().toString()
        scope.launch {
            val secret = when {
                user.isBlank() -> ""
                password.isNotEmpty() -> withContext(Dispatchers.IO) { SecretStore.encrypt(password) }.orEmpty()
                else -> existing?.secret.orEmpty()
            }
            val server = SmbServer(id, name.trim(), host, root, user.trim(), secret)
            if (existing != null && (existing.host != host || existing.root != root)) {
                settings.setLastPath(id, emptyList())
            }
            settings.saveServer(server)
            forget(id)
        }
        return true
    }

    fun removeServer(server: SmbServer) {
        settings.removeServer(server.id)
        forget(server.id)
    }

    fun playDeviceVideo(context: Context, folder: VideoFolder, video: Video) {
        val items = folder.videos.map {
            VideoItem(
                uri = it.uri,
                title = VideoFiles.baseName(it.fileName),
                key = it.path,
                subtitles = SubtitleSource.Device(it.path),
            )
        }
        play(context, items, folder.videos.indexOf(video))
    }

    /** Plays [entry] of the open network folder; the other videos of the folder follow it. */
    fun playNetworkVideo(context: Context, entry: SmbEntry) {
        val current = _location.value as? VideoLocation.Network ?: return
        val server = settings.server(current.serverId) ?: return
        val entries = (_listing.value as? NetworkListing.Ready)?.entries ?: return
        val directory = server.root + current.path
        if (directory.isEmpty()) return

        val files = entries.filterNot { it.isDirectory }
        val names = files.map { it.name }
        val videos = files.filter { VideoFiles.isVideo(it.name) }
        val items = videos.map { video ->
            VideoItem(
                uri = SmbUri.build(server.id, directory + video.name),
                title = VideoFiles.baseName(video.name),
                key = networkKey(server.id, directory, video.name),
                subtitles = SubtitleSource.Network(
                    server = server,
                    directory = directory,
                    videoName = video.name,
                    fileNames = SubtitleFiles.matching(video.name, names),
                ),
            )
        }
        play(context, items, videos.indexOf(entry))
    }

    /** The key under which the position in a network video is remembered. */
    fun networkKey(serverId: String, directory: List<String>, fileName: String): String =
        SmbUri.build(serverId, directory + fileName).toString()

    private fun play(context: Context, items: List<VideoItem>, index: Int) {
        if (index < 0) return
        VideoQueue.set(items, index)
        context.startActivity(Intent(context, VideoPlayerActivity::class.java))
    }

    private fun show(target: VideoLocation.Network, fallBackToRoot: Boolean = false) {
        val server = settings.server(target.serverId) ?: return
        listJob?.cancel()
        _location.value = target
        settings.setLastPath(server.id, target.path)

        cache[target]?.let {
            _listing.value = NetworkListing.Ready(it)
            return
        }
        _listing.value = NetworkListing.Loading
        listJob = scope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { fetch(server, target.path) }
                cache[target] = entries
                _listing.value = NetworkListing.Ready(entries)
            } catch (e: SmbException) {
                if (fallBackToRoot && target.path.isNotEmpty() && e.reason == SmbException.Reason.NOT_FOUND) {
                    // The folder that was open last is gone; start from the top instead.
                    show(target.copy(path = emptyList()))
                } else {
                    _listing.value = NetworkListing.Failed(e.reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _listing.value = NetworkListing.Failed(SmbException.Reason.OTHER)
            }
        }
    }

    private fun fetch(server: SmbServer, path: List<String>): List<SmbEntry> {
        val full = server.root + path
        val entries = if (full.isEmpty()) {
            app.smb.listShares(server).map { SmbEntry(it, isDirectory = true, size = 0) }
        } else {
            app.smb.list(server, full.first(), full.drop(1))
        }
        return entries
            .filter { entry ->
                if (entry.isDirectory) {
                    !entry.name.startsWith('.') && entry.name !in HIDDEN_FOLDERS
                } else {
                    VideoFiles.isVideo(entry.name) || SubtitleFiles.format(entry.name) != null
                }
            }
            .sortedWith(compareByDescending<SmbEntry> { it.isDirectory }.thenBy(NaturalOrder) { it.name })
    }

    private fun forget(serverId: String) {
        cache.keys.removeAll { it.serverId == serverId }
        scope.launch(Dispatchers.IO) { app.smb.disconnect(serverId) }
    }

    private companion object {
        /** Housekeeping folders of Windows and of common NAS systems. */
        val HIDDEN_FOLDERS = setOf("\$RECYCLE.BIN", "System Volume Information", "@eaDir", "#recycle", "#snapshot")
    }
}
