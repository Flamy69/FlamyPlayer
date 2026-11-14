package io.github.flamy69.flamyplayer.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings as SystemSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.flamy69.flamyplayer.R
import io.github.flamy69.flamyplayer.data.Book
import io.github.flamy69.flamyplayer.data.MediaLibrary
import io.github.flamy69.flamyplayer.data.Section

@Composable
fun FlamyPlayerApp(viewModel: MainViewModel) {
    val playerState by viewModel.player.state.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val bookProgress by viewModel.settings.bookProgress.collectAsStateWithLifecycle()

    var section by rememberSaveable { mutableStateOf(viewModel.settings.lastSection) }
    var pickerSection by rememberSaveable { mutableStateOf<Section?>(null) }
    var openBookFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }

    val openBook = openBookFolder?.let { folder -> books?.firstOrNull { it.folder == folder } }
    val picker = pickerSection
    val showNowPlaying = nowPlayingOpen && playerState.hasQueue

    val miniPlayer: @Composable (Boolean) -> Unit = { padForNavigationBar ->
        if (playerState.hasQueue) {
            MiniPlayer(
                state = playerState,
                player = viewModel.player,
                onOpen = { nowPlayingOpen = true },
                padForNavigationBar = padForNavigationBar,
            )
        }
    }

    fun playOrPause(book: Book) {
        if (playerState.bookFolder == book.folder && playerState.isPlaying) {
            viewModel.player.togglePlay()
        } else {
            viewModel.playBook(book)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            picker != null -> {
                val root by viewModel.folderTree.collectAsStateWithLifecycle()
                val selected by viewModel.settings.folders(picker).collectAsStateWithLifecycle()
                BackHandler { pickerSection = null }
                FolderPickerScreen(
                    section = picker,
                    root = root,
                    selected = selected,
                    onToggle = { viewModel.toggleFolder(picker, it) },
                    onClearAll = { viewModel.clearFolders(picker) },
                    onClose = { pickerSection = null },
                )
            }

            openBook != null -> {
                BackHandler { openBookFolder = null }
                BookScreen(
                    book = openBook,
                    progress = bookProgress[openBook.folder],
                    playerState = playerState,
                    onBack = { openBookFolder = null },
                    onResume = { viewModel.playBook(openBook) },
                    onChapterClick = { viewModel.playBook(openBook, it) },
                    bottomBar = { miniPlayer(true) },
                )
            }

            else -> HomeScreen(
                viewModel = viewModel,
                section = section,
                onSectionChange = {
                    section = it
                    viewModel.settings.lastSection = it
                },
                onChooseFolders = { pickerSection = section },
                onOpenBook = { openBookFolder = it.folder },
                onPlayOrPauseBook = ::playOrPause,
                miniPlayer = { miniPlayer(false) },
            )
        }

        AnimatedVisibility(
            visible = showNowPlaying,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            BackHandler { nowPlayingOpen = false }
            NowPlayingScreen(
                state = playerState,
                player = viewModel.player,
                onClose = { nowPlayingOpen = false },
                onToggleShuffle = viewModel::toggleShuffle,
                onCycleRepeat = viewModel::cycleRepeatMode,
                onSpeedChange = viewModel::setBookSpeed,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    viewModel: MainViewModel,
    section: Section,
    onSectionChange: (Section) -> Unit,
    onChooseFolders: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onPlayOrPauseBook: (Book) -> Unit,
    miniPlayer: @Composable () -> Unit,
) {
    val hasPermission by viewModel.hasPermission.collectAsStateWithLifecycle()
    val musicTracks by viewModel.musicTracks.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val playerState by viewModel.player.state.collectAsStateWithLifecycle()

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val searchActive = searching && section == Section.MUSIC && hasPermission
    val closeSearch = {
        searching = false
        query = ""
    }
    BackHandler(enabled = searchActive, onBack = closeSearch)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        SearchField(query, onQueryChange = { query = it })
                    } else {
                        Text(
                            stringResource(
                                if (section == Section.MUSIC) R.string.tab_music else R.string.tab_audiobooks,
                            ),
                        )
                    }
                },
                actions = {
                    if (searchActive) {
                        IconButton(onClick = closeSearch) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.action_close))
                        }
                    } else if (hasPermission) {
                        if (section == Section.MUSIC) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Rounded.Search, stringResource(R.string.action_search))
                            }
                        }
                        IconButton(onClick = onChooseFolders) {
                            Icon(Icons.Rounded.FolderOpen, stringResource(R.string.action_choose_folders))
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                miniPlayer()
                NavigationBar {
                    NavigationBarItem(
                        selected = section == Section.MUSIC,
                        onClick = { onSectionChange(Section.MUSIC) },
                        icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_music)) },
                    )
                    NavigationBarItem(
                        selected = section == Section.BOOKS,
                        onClick = { onSectionChange(Section.BOOKS) },
                        icon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_audiobooks)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val tracks = musicTracks
            val bookList = books
            when {
                !hasPermission -> PermissionPrompt(onChanged = viewModel::refresh)

                tracks == null || bookList == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                section == Section.MUSIC -> {
                    val folders by viewModel.settings.musicFolders.collectAsStateWithLifecycle()
                    MusicScreen(
                        tracks = tracks,
                        hasFolders = folders.isNotEmpty(),
                        query = if (searchActive) query else "",
                        currentTrackId = playerState.trackId.takeIf { playerState.bookFolder == null },
                        onChooseFolders = onChooseFolders,
                        onShuffle = { viewModel.playMusic(shuffle = true) },
                        onPlayInOrder = { viewModel.playMusic(shuffle = false) },
                        onTrackClick = { viewModel.playMusic(startTrackId = it.id) },
                    )
                }

                else -> {
                    val progress by viewModel.settings.bookProgress.collectAsStateWithLifecycle()
                    AudiobooksScreen(
                        books = bookList,
                        progress = progress,
                        playerState = playerState,
                        onChooseFolders = onChooseFolders,
                        onOpenBook = onOpenBook,
                        onPlayPause = onPlayOrPauseBook,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
    )
}

/** Asks for access to audio files; falls back to the system settings once Android stops asking. */
@Composable
private fun PermissionPrompt(onChanged: () -> Unit) {
    val activity = LocalActivity.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        onChanged()
        val denied = result.filterValues { !it }.keys
        val audioPermission = MediaLibrary.permissionsToRequest.first()
        if (activity != null && audioPermission in denied && !activity.canAskAgain(audioPermission)) {
            activity.startActivity(
                Intent(
                    SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null),
                ),
            )
        }
    }
    EmptyState(
        icon = Icons.Rounded.Lock,
        title = stringResource(R.string.permission_title),
        text = stringResource(R.string.permission_text),
        buttonText = stringResource(R.string.permission_button),
        onButtonClick = { launcher.launch(MediaLibrary.permissionsToRequest) },
    )
}

private fun Activity.canAskAgain(permission: String): Boolean =
    ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
