package io.github.flamy69.flamyplayer

import android.app.Application
import io.github.flamy69.flamyplayer.data.Settings

class FlamyApp : Application() {

    /** Shared by the UI and the playback service, which run in the same process. */
    val settings: Settings by lazy { Settings(this) }
}
