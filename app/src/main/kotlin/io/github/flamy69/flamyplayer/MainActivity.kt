package io.github.flamy69.flamyplayer

import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.flamy69.flamyplayer.ui.FlamyPlayerApp
import io.github.flamy69.flamyplayer.ui.FlamyTheme
import io.github.flamy69.flamyplayer.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent {
            FlamyTheme {
                FlamyPlayerApp(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Picks up a permission granted in system settings and files added while we were away.
        viewModel.refresh()
    }
}
