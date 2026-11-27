package `in`.melo.presentation.components

import `in`.melo.ui.theme.controlFill
import `in`.melo.ui.theme.selectedFill
import `in`.melo.ui.glass.MeloAlertDialog
import `in`.melo.presentation.icons.MeloIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import `in`.melo.presentation.components.MeloBottomSheet as ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import `in`.melo.data.SpotifyAccount
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.melo.R
import `in`.melo.data.Music
import `in`.melo.data.isLocalTrack
import `in`.melo.data.source
import `in`.melo.presentation.viewmodel.PlayerViewModel
import `in`.melo.ui.theme.MeloLayout

/** The small marker that tells a device-only playlist from a Spotify one. */
@Composable
fun LocalBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.controlFill,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MeloLayout.ArtworkShape,
    ) {
        Text(
            stringResource(R.string.badge_local),
            modifier = Modifier.padding(horizontal = MeloLayout.Space2, vertical = MeloLayout.Space1),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun PlaylistNameDialog(title: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    MeloAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.playlist_name)) },
            )
        },
        confirmButton = {
            MeloButton(
                text = stringResource(R.string.action_save),
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
                size = MeloButtonSize.Medium,
                style = MeloButtonStyle.Text,
            )
        },
        dismissButton = {
            MeloButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                size = MeloButtonSize.Medium,
                style = MeloButtonStyle.Text,
            )
        },
    )
}

/** Chooses the playlist that receives [item]: a track, an album, an artist or a playlist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToLocalPlaylistSheet(item: Music, ui: PlayerViewModel, onDismiss: () -> Unit) {
    val playlists by ui.model.localPlaylists.collectAsStateWithLifecycle()
    val library by ui.model.library.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val addedMessage = stringResource(R.string.playlist_added)
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { username = runCatching { SpotifyAccount.require(context).username() }.getOrNull() }
    // A Spotify playlist takes Spotify tracks and local files; multi-service playlists take everything.
    // Local files are added as Spotify's own local file references.
    val isServerItem = item.source == `in`.melo.data.MusicSource.MusicServer
    val spotifyItem = !isServerItem
    val accountPlaylists = library.content?.playlists.orEmpty().filter { spotifyItem && username != null && it.ownerUsername == username }
    val serverLists by ui.model.serverPlaylists.collectAsStateWithLifecycle()
    val serverPlaylists = if (!isServerItem) emptyList() else serverLists.filter { it.id != item.id && ui.model.ownsServerPlaylist(it) }
    var creating by remember { mutableStateOf(false) }
    `in`.melo.presentation.components.MeloBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(
                stringResource(R.string.playlist_add_to),
                modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(MeloLayout.Space2)) {
                item(key = "new") {
                    Row(
                        Modifier.fillMaxWidth().clip(MeloLayout.PanelShape).clickable { creating = true }.padding(MeloLayout.Space3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(MeloIcons.Add, null, Modifier.size(44.dp).padding(8.dp))
                        Text(stringResource(R.string.spotify_playlist_new), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
                items(accountPlaylists, key = { it.id }) { playlist ->
                    Row(Modifier.fillMaxWidth().clip(MeloLayout.PanelShape).clickable(enabled = !adding) {
                        adding = true
                        scope.launch {
                            try {
                                ui.model.spotifyPlaylists.add(playlist.id, ui.model.tracksOf(item))
                                ui.model.spotifyPlaylistChanged(playlist)
                                ui.model.showMessage(addedMessage); onDismiss()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { ui.model.showMessage(failure.message.orEmpty()) }
                            finally { adding = false }
                        }
                    }.padding(MeloLayout.Space3), verticalAlignment = Alignment.CenterVertically) {
                        SmartImage(model = playlist.imageUrl, contentDescription = null, shape = MeloLayout.CardShape,
                            targetSize = SmartImageCompactListTargetSize, modifier = Modifier.size(44.dp))
                        Text(playlist.title, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Spotify", style = MaterialTheme.typography.labelSmall)
                    }
                }
                items(serverPlaylists, key = { it.id }) { playlist ->
                    Row(Modifier.fillMaxWidth().clip(MeloLayout.PanelShape).clickable(enabled = !adding) {
                        adding = true
                        scope.launch {
                            try {
                                ui.model.editServerPlaylist(playlist, tracks = ui.model.tracksOf(item))
                                ui.model.showMessage(addedMessage); onDismiss()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { ui.model.showMessage(failure.message.orEmpty()) }
                            finally { adding = false }
                        }
                    }.padding(MeloLayout.Space3), verticalAlignment = Alignment.CenterVertically) {
                        SmartImage(model = playlist.imageUrl, contentDescription = null, shape = MeloLayout.CardShape,
                            targetSize = SmartImageCompactListTargetSize, modifier = Modifier.size(44.dp))
                        Text(playlist.title, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.music_server_title), style = MaterialTheme.typography.labelSmall)
                    }
                }
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MeloLayout.PanelShape)
                            .clickable { ui.addToLocalPlaylist(playlist.id, item); onDismiss() }
                            .padding(MeloLayout.Space3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SmartImage(
                            model = playlist.asMusic().imageUrl, contentDescription = null,
                            shape = MeloLayout.CardShape, contentScale = ContentScale.Crop,
                            targetSize = SmartImageCompactListTargetSize, modifier = Modifier.size(44.dp),
                        )
                        Text(
                            playlist.name, Modifier.weight(1f).padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        LocalBadge()
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
        }
    }
    if (creating) SpotifyPlaylistEditor(ui.model, initialItem = item, onDismiss = { creating = false; onDismiss() })
}
