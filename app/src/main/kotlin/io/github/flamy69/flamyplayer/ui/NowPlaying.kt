package io.github.flamy69.flamyplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import io.github.flamy69.flamyplayer.R
import io.github.flamy69.flamyplayer.playback.PlayerConnection
import io.github.flamy69.flamyplayer.playback.PlayerState

private val BookSpeeds = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Amplification in decibels; every step sounds clearly louder than the one before. */
private val Boosts = listOf(0, 4, 8, 12, 16, 20)

/** The strip above the bottom bar: what is playing, with play/pause and next. */
@Composable
fun MiniPlayer(
    state: PlayerState,
    player: PlayerConnection,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    padForNavigationBar: Boolean = false,
) {
    val position by rememberPlaybackPosition(player)
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(if (padForNavigationBar) Modifier.navigationBarsPadding() else Modifier) {
            ProgressBar(
                fraction = if (state.durationMs > 0) position.toFloat() / state.durationMs else 0f,
                height = 2.dp,
            )
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(state.artwork, isBook = state.bookFolder != null, modifier = Modifier.size(44.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val artist = state.artist
                    if (!artist.isNullOrEmpty()) {
                        Text(
                            text = artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = player::togglePlay) { PlayPauseIcon(state.isPlaying) }
                IconButton(onClick = player::next) {
                    Icon(Icons.Rounded.SkipNext, stringResource(R.string.cd_next))
                }
            }
        }
    }
}

/** Full screen player with the seek bar and all controls. */
@Composable
fun NowPlayingScreen(
    state: PlayerState,
    player: PlayerConnection,
    onClose: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    boostDb: Int,
    onBoostChange: (Int) -> Unit,
) {
    val isBook = state.bookFolder != null
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .systemBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.action_close))
                }
                Text(
                    text = stringResource(
                        when {
                            state.isRadio -> R.string.tab_radio
                            isBook -> R.string.tab_audiobooks
                            else -> R.string.tab_music
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(48.dp))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Artwork(
                    artwork = state.artwork,
                    isBook = isBook,
                    cornerRadius = 20.dp,
                    modifier = Modifier.aspectRatio(1f),
                )
            }

            Text(
                text = state.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(state.artist, state.album.takeIf { it != state.artist }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))

            // A radio is live: there is nothing to seek in.
            if (!state.isRadio) SeekBar(state, player)
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isRadio) {
                    Spacer(Modifier.size(48.dp))
                } else if (isBook) {
                    ControlButton(Icons.Rounded.Replay10, R.string.cd_rewind, onClick = player::seekBack)
                } else {
                    ControlButton(
                        icon = Icons.Rounded.Shuffle,
                        description = R.string.cd_shuffle,
                        active = state.shuffle,
                        onClick = onToggleShuffle,
                    )
                }
                ControlButton(Icons.Rounded.SkipPrevious, R.string.cd_previous, large = true, onClick = player::previous)
                FilledIconButton(onClick = player::togglePlay, modifier = Modifier.size(72.dp)) {
                    PlayPauseIcon(state.isPlaying, Modifier.size(40.dp))
                }
                ControlButton(Icons.Rounded.SkipNext, R.string.cd_next, large = true, onClick = player::next)
                if (state.isRadio) {
                    Spacer(Modifier.size(48.dp))
                } else if (isBook) {
                    ControlButton(Icons.Rounded.Forward30, R.string.cd_forward, onClick = player::seekForward)
                } else {
                    ControlButton(
                        icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        description = R.string.cd_repeat,
                        active = state.repeatMode != Player.REPEAT_MODE_OFF,
                        onClick = onCycleRepeat,
                    )
                }
            }

            // The row is there in both modes, so the controls do not jump between music and books.
            Row(
                modifier = Modifier.height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isBook) SpeedButton(state.speed, onSpeedChange)
                BoostButton(boostDb, onBoostChange)
            }
        }
    }
}

@Composable
private fun SeekBar(state: PlayerState, player: PlayerConnection) {
    var position by rememberPlaybackPosition(player)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs
    val fraction = dragFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Slider(
        value = fraction,
        onValueChange = { dragFraction = it },
        onValueChangeFinished = {
            dragFraction?.let { target ->
                val targetMs = (target * duration).toLong()
                position = targetMs
                player.seekTo(targetMs)
            }
            dragFraction = null
        },
        enabled = duration > 0,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val shownPosition = dragFraction?.let { (it * duration).toLong() } ?: position
        Text(
            text = formatTime(shownPosition),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatTime(duration),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    description: Int,
    onClick: () -> Unit,
    large: Boolean = false,
    active: Boolean? = null,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(if (large) 56.dp else 48.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(description),
            // Toggles are tinted when on and dimmed when off; plain buttons keep the default color.
            tint = when (active) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                null -> LocalContentColor.current
            },
            modifier = Modifier.size(if (large) 36.dp else 26.dp),
        )
    }
}

@Composable
private fun PlayPauseIcon(isPlaying: Boolean, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(
        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
        contentDescription = stringResource(if (isPlaying) R.string.cd_pause else R.string.cd_play),
        tint = tint,
        modifier = modifier,
    )
}

/** Amplification for quiet recordings: kept per audiobook, and once for all music. */
@Composable
private fun BoostButton(boostDb: Int, onBoostChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = boostLabel(boostDb),
                style = MaterialTheme.typography.titleMedium,
                color = if (boostDb > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.cd_boost), style = MaterialTheme.typography.bodyMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in Boosts) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = boostLabel(option),
                            fontWeight = if (option == boostDb) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        onBoostChange(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun boostLabel(decibels: Int): String =
    if (decibels > 0) stringResource(R.string.boost_value, decibels) else stringResource(R.string.boost_off)

@Composable
private fun SpeedButton(speed: Float, onSpeedChange: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val description = stringResource(R.string.cd_speed)
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = stringResource(R.string.speed_value, formatSpeed(speed)),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(6.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in BookSpeeds) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.speed_value, formatSpeed(option)),
                            fontWeight = if (option == speed) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSpeedChange(option)
                    },
                )
            }
        }
    }
}
