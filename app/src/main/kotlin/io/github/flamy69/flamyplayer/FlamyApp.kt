package io.github.flamy69.flamyplayer

import android.app.Application
import io.github.flamy69.flamyplayer.data.Settings
import io.github.flamy69.flamyplayer.data.TrackStats
import io.github.flamy69.flamyplayer.data.VideoSettings
import io.github.flamy69.flamyplayer.network.LocalNetworkScanner
import io.github.flamy69.flamyplayer.network.SecretStore
import io.github.flamy69.flamyplayer.network.SmbClient

class FlamyApp : Application() {

    /** Shared by the UI and the playback service, which run in the same process. */
    val settings: Settings by lazy { Settings(this) }

    /** How often and how far the music tracks were listened to; steers the shuffle. */
    val trackStats: TrackStats by lazy { TrackStats(this) }

    val videoSettings: VideoSettings by lazy { VideoSettings(this) }

    val localNetwork: LocalNetworkScanner by lazy { LocalNetworkScanner(this) }

    /** One set of network connections for both the folder browser and the video player. */
    val smb: SmbClient by lazy {
        SmbClient(password = { SecretStore.decrypt(it.secret) }, resolveHost = localNetwork::resolve)
    }
}
