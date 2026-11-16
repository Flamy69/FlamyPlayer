package io.github.flamy69.flamyplayer

import android.app.SearchManager
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.flamy69.flamyplayer.data.Section
import io.github.flamy69.flamyplayer.ui.FlamyPlayerApp
import io.github.flamy69.flamyplayer.ui.FlamyTheme
import io.github.flamy69.flamyplayer.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        // Music, audiobooks and radio are on the display of a car anyway, through Android Auto
        // itself. Opened there, the app is wanted for the one thing that is not: video.
        val startSection = Section.VIDEO.takeIf { intent?.hasCategory(CATEGORY_CAR_LAUNCHER) == true }
        setContent {
            FlamyTheme {
                FlamyPlayerApp(viewModel, startSection)
            }
        }
        // Not again after a rotation: the wish was granted the first time.
        if (savedInstanceState == null) playWhatWasAskedFor(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        playWhatWasAskedFor(intent)
    }

    /** "Play ... on FlamyPlayer", said to the voice assistant, arrives as a search to play. */
    private fun playWhatWasAskedFor(intent: Intent?) {
        if (intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            viewModel.player.playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
        }
    }

    override fun onStart() {
        super.onStart()
        // Picks up a permission granted in system settings and files added while we were away.
        viewModel.refresh()
    }

    private companion object {
        const val CATEGORY_CAR_LAUNCHER = "android.intent.category.CAR_LAUNCHER"
    }
}
