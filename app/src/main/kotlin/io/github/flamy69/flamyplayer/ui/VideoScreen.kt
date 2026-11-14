package io.github.flamy69.flamyplayer.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as SystemSettings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.flamy69.flamyplayer.R
import io.github.flamy69.flamyplayer.data.VideoFiles
import io.github.flamy69.flamyplayer.data.VideoFolder
import io.github.flamy69.flamyplayer.data.VideoLibrary
import io.github.flamy69.flamyplayer.network.SmbException
import io.github.flamy69.flamyplayer.network.SmbServer
import io.github.flamy69.flamyplayer.video.VideoPlayerViewModel
import java.util.Locale

/** The video section: an overview of sources, and the folders below it. */
@Composable
fun VideoScreen(browser: VideoBrowser, onAddServer: () -> Unit, onEditServer: (SmbServer) -> Unit) {
    val location by browser.location.collectAsStateWithLifecycle()
    // The videos of the device are only looked up once somebody actually comes here.
    LaunchedEffect(Unit) { browser.refreshDevice(force = true) }

    when (val current = location) {
        VideoLocation.Home -> VideoHome(browser, onAddServer, onEditServer)
        is VideoLocation.Device -> DeviceFolder(browser, current.folder)
        is VideoLocation.Network -> NetworkFolder(browser, current)
    }
}

@Composable
private fun VideoHome(browser: VideoBrowser, onAddServer: () -> Unit, onEditServer: (SmbServer) -> Unit) {
    val servers by browser.settings.servers.collectAsStateWithLifecycle()
    val hasPermission by browser.hasPermission.collectAsStateWithLifecycle()
    val folders by browser.folders.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Android 17 asks the user before an app may talk to devices on the local network.
    var pendingServer by remember { mutableStateOf<SmbServer?>(null) }
    val askForNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Open the folder either way; without the permission it explains what is missing.
        pendingServer?.let(browser::openServer)
        pendingServer = null
    }
    val openServer = { server: SmbServer ->
        val permission = LocalNetwork.permission
        if (permission != null && !LocalNetwork.isAllowed(context)) {
            pendingServer = server
            askForNetwork.launch(permission)
        } else {
            browser.openServer(server)
        }
    }
    val askForVideos = rememberPermissionRequest(
        permission = VideoLibrary.permission,
        isSatisfied = { VideoLibrary.hasPermission(context) },
        onResult = { browser.refreshDevice(force = true) },
    )

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "network-header", contentType = "header") {
            SectionHeader(stringResource(R.string.video_section_network))
        }
        items(servers, key = { "server:" + it.id }, contentType = { "server" }) { server ->
            BrowserRow(
                icon = Icons.Rounded.Lan,
                title = server.title,
                subtitle = if (server.name.isBlank()) "" else server.address,
                onClick = { openServer(server) },
                trailing = {
                    IconButton(onClick = { onEditServer(server) }) {
                        Icon(
                            Icons.Rounded.Edit,
                            stringResource(R.string.action_edit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
        item(key = "network-add", contentType = "add") {
            BrowserRow(
                icon = Icons.Rounded.Add,
                title = stringResource(R.string.video_add_network),
                subtitle = if (servers.isEmpty()) stringResource(R.string.video_add_network_hint) else "",
                onClick = onAddServer,
            )
        }

        item(key = "device-header", contentType = "header") {
            SectionHeader(stringResource(R.string.video_section_device))
        }
        val deviceFolders = folders
        when {
            !hasPermission -> item(key = "device-permission") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.video_permission_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(12.dp))
                    FilledTonalButton(onClick = askForVideos) {
                        Text(stringResource(R.string.permission_button))
                    }
                }
            }

            deviceFolders == null -> item(key = "device-loading") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            deviceFolders.isEmpty() -> item(key = "device-empty") {
                Text(
                    text = stringResource(R.string.video_none_on_device),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            else -> items(deviceFolders, key = { "folder:" + it.path }, contentType = { "folder" }) { folder ->
                BrowserRow(
                    icon = Icons.Rounded.Folder,
                    title = folder.name,
                    subtitle = pluralStringResource(R.plurals.video_count, folder.videos.size, folder.videos.size),
                    onClick = { browser.openFolder(folder) },
                )
            }
        }
    }
}

@Composable
private fun DeviceFolder(browser: VideoBrowser, path: String) {
    val folders by browser.folders.collectAsStateWithLifecycle()
    val positions by browser.settings.positions.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val loaded = folders
    val folder: VideoFolder? = loaded?.firstOrNull { it.path == path }
    when {
        loaded == null -> Loading()

        folder == null -> EmptyState(
            icon = Icons.Rounded.VideoLibrary,
            title = stringResource(R.string.video_folder_empty_title),
            text = stringResource(R.string.video_folder_empty_text),
        )

        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(folder.videos, key = { it.id }, contentType = { "video" }) { video ->
                BrowserRow(
                    icon = Icons.Rounded.Movie,
                    title = video.fileName,
                    subtitle = listOfNotNull(
                        formatTime(video.durationMs).takeIf { video.durationMs > 0 },
                        formatSize(video.sizeBytes).takeIf { video.sizeBytes > 0 },
                    ).joinToString(" · "),
                    resumeAtMs = positions[video.path],
                    onClick = { browser.playDeviceVideo(context, folder, video) },
                )
            }
        }
    }
}

@Composable
private fun NetworkFolder(browser: VideoBrowser, location: VideoLocation.Network) {
    val listing by browser.listing.collectAsStateWithLifecycle()
    val positions by browser.settings.positions.collectAsStateWithLifecycle()
    val servers by browser.settings.servers.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val server = servers.firstOrNull { it.id == location.serverId }
    val directory = server?.root.orEmpty() + location.path

    when (val current = listing) {
        NetworkListing.Loading -> Loading()

        is NetworkListing.Failed -> NetworkError(
            reason = current.reason,
            listingShares = directory.isEmpty(),
            onRetry = browser::reload,
        )

        is NetworkListing.Ready -> {
            val visible = remember(current) {
                current.entries.filter { it.isDirectory || VideoFiles.isVideo(it.name) }
            }
            if (visible.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.VideoLibrary,
                    title = stringResource(R.string.video_folder_empty_title),
                    text = stringResource(R.string.video_folder_empty_text),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visible, key = { it.name }, contentType = { it.isDirectory }) { entry ->
                        if (entry.isDirectory) {
                            BrowserRow(
                                icon = Icons.Rounded.Folder,
                                title = entry.name,
                                subtitle = "",
                                onClick = { browser.openDirectory(entry.name) },
                            )
                        } else {
                            BrowserRow(
                                icon = Icons.Rounded.Movie,
                                title = entry.name,
                                subtitle = formatSize(entry.size),
                                resumeAtMs = positions[browser.networkKey(location.serverId, directory, entry.name)],
                                onClick = { browser.playNetworkVideo(context, entry) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkError(reason: SmbException.Reason, listingShares: Boolean, onRetry: () -> Unit) {
    val context = LocalContext.current
    val permissionMissing = reason == SmbException.Reason.UNREACHABLE && !LocalNetwork.isAllowed(context)
    val askForNetwork = rememberPermissionRequest(
        permission = LocalNetwork.permission,
        isSatisfied = { LocalNetwork.isAllowed(context) },
        onResult = onRetry,
    )

    val text = when {
        permissionMissing -> R.string.network_permission_text
        // Some servers do not hand out the list of their shared folders to everybody.
        listingShares && reason != SmbException.Reason.UNREACHABLE && reason != SmbException.Reason.LOGIN_FAILED ->
            R.string.network_error_shares
        else -> VideoPlayerViewModel.networkErrorText(reason)
    }
    EmptyState(
        icon = Icons.Rounded.CloudOff,
        title = stringResource(R.string.network_error_title),
        text = stringResource(text),
        buttonText = stringResource(if (permissionMissing) R.string.permission_button else R.string.action_retry),
        onButtonClick = if (permissionMissing) askForNetwork else onRetry,
    )
}

/** Dialog for adding a network location, or for editing or removing an existing one. */
@Composable
fun ServerDialog(
    server: SmbServer?,
    onSave: (name: String, address: String, user: String, password: String) -> Boolean,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var address by rememberSaveable { mutableStateOf(server?.address.orEmpty()) }
    var name by rememberSaveable { mutableStateOf(server?.name.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(server?.user.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var addressInvalid by rememberSaveable { mutableStateOf(false) }

    val plainText = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        imeAction = ImeAction.Next,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (server == null) R.string.network_title_add else R.string.network_title_edit))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        addressInvalid = false
                    },
                    label = { Text(stringResource(R.string.network_address)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (addressInvalid) R.string.network_address_error else R.string.network_address_hint,
                            ),
                        )
                    },
                    isError = addressInvalid,
                    singleLine = true,
                    keyboardOptions = plainText.copy(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.network_user)) },
                    supportingText = { Text(stringResource(R.string.network_user_hint)) },
                    singleLine = true,
                    keyboardOptions = plainText,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.network_password)) },
                    placeholder = {
                        if (!server?.secret.isNullOrEmpty()) Text(stringResource(R.string.network_password_unchanged))
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = plainText.copy(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.network_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (onSave(name, address, user, password)) onDismiss() else addressInvalid = true
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (server != null) {
                    TextButton(
                        onClick = {
                            onRemove()
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/**
 * Asks for a runtime permission and calls [onResult] whatever the user decided. Once Android
 * no longer shows its dialog, the app's page in the system settings opens instead.
 *
 * @param isSatisfied whether the app has what it needs; not always the same as "granted",
 * because the user may also allow access to selected videos only
 */
@Composable
private fun rememberPermissionRequest(
    permission: String?,
    isSatisfied: () -> Boolean,
    onResult: () -> Unit,
): () -> Unit {
    val activity = LocalActivity.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onResult()
        if (!isSatisfied() && activity != null && permission != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        ) {
            activity.startActivity(
                Intent(
                    SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null),
                ),
            )
        }
    }
    return { if (permission != null) launcher.launch(permission) else onResult() }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** One line of the video browser: a source, a folder or a video. */
@Composable
private fun BrowserRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    resumeAtMs: Long? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (resumeAtMs != null) {
                Text(
                    text = stringResource(R.string.video_resume_at, formatTime(resumeAtMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        trailing()
    }
}

/** `1.4 GB` or `730 MB`. */
private fun formatSize(bytes: Long): String {
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 1024) {
        String.format(Locale.getDefault(), "%.1f GB", megabytes / 1024)
    } else {
        String.format(Locale.getDefault(), "%.0f MB", megabytes)
    }
}
