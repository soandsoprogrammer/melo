package `in`.melo.presentation.components

import `in`.melo.ui.glass.MeloAlertDialog
import `in`.melo.ui.glass.MeloSwitch
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import `in`.melo.R
import `in`.melo.MeloModel
import `in`.melo.data.Music
import `in`.melo.data.PlaylistKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Creation and editing use the same account form; device-only lists remain available explicitly. */
@Composable
internal fun SpotifyPlaylistEditor(
    model: MeloModel,
    playlist: Music? = null,
    initialItem: Music? = null,
    onDismiss: () -> Unit,
    onSaved: (Music?) -> Unit = {},
) {
    val context = LocalContext.current
    val coverFailure = stringResource(R.string.spotify_playlist_cover_retry)
    val saveFailure = stringResource(R.string.spotify_playlist_failed)
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(playlist?.title.orEmpty()) }
    val discovery by model.discovery.collectAsState()
    // Without a Spotify account, every new playlist is a multi-service playlist.
    val signedIn = discovery.spotifyEnabled
    val serverLibrary by model.serverLibrary.collectAsState()
    val serverAllowed = playlist == null && serverLibrary != null && (initialItem == null || PlaylistKind.MusicServer.accepts(initialItem))
    var destination by remember { mutableStateOf(when { playlist != null -> PlaylistKind.Spotify; serverAllowed -> PlaylistKind.MusicServer; signedIn && initialItem?.let { PlaylistKind.MusicServer.accepts(it) } != true -> PlaylistKind.Spotify; else -> PlaylistKind.MultiService }) }
    val local = destination == PlaylistKind.MultiService
    val onServer = destination == PlaylistKind.MusicServer
    var published by remember { mutableStateOf(false) }
    var knownVisibility by remember { mutableStateOf(playlist == null) }
    var image by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) image = uri }
    LaunchedEffect(playlist?.id) {
        if (playlist != null) try { published = model.spotifyPlaylists.published(playlist.id); knownVisibility = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = saveFailure }
    }
    MeloAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (playlist == null) R.string.spotify_playlist_new else R.string.spotify_playlist_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text(stringResource(R.string.playlist_name)) }, singleLine = true, enabled = !busy)
                val choices = buildList {
                    if (signedIn && initialItem?.let { PlaylistKind.MusicServer.accepts(it) } != true) add(PlaylistKind.Spotify to R.string.spotify_playlist_account)
                    if (serverAllowed) add(PlaylistKind.MusicServer to R.string.music_server_title)
                    add(PlaylistKind.MultiService to R.string.spotify_playlist_device)
                }
                if (playlist == null && choices.size > 1) choices.forEach { (kind, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(destination == kind, { if (!busy) destination = kind }, enabled = !busy)
                        Text(stringResource(label), Modifier.clickable(enabled = !busy) { destination = kind })
                    }
                }
                if (!onServer) {
                    if (image != null || playlist?.imageUrl != null) AsyncImage(image ?: playlist?.imageUrl, null, Modifier.size(100.dp))
                    MeloButton(
                        text = stringResource(R.string.spotify_playlist_cover),
                        onClick = { picker.launch("image/*") },
                        enabled = !busy,
                        size = MeloButtonSize.Medium,
                        style = MeloButtonStyle.Outlined,
                    )
                }
                if (!local && !onServer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.spotify_playlist_profile), Modifier.weight(1f))
                        MeloSwitch(published, { published = it }, enabled = !busy && knownVisibility)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            MeloButton(text = stringResource(R.string.action_save), enabled = !busy && name.isNotBlank() && (local || onServer && serverAllowed || knownVisibility && !onServer), onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        // Read and normalize the selected image before creating anything remotely.
                        val jpeg = if (image != null) withContext(Dispatchers.IO) { playlistJpeg(context, checkNotNull(image)) } else null
                        val tracks = initialItem?.let { model.tracksOf(it) }.orEmpty()
                        if (local) {
                            val created = model.localPlaylistStore.create(name.trim())
                            if (tracks.isNotEmpty()) model.localPlaylistStore.add(created.id, tracks)
                            if (jpeg != null) model.setLocalPlaylistCover(created.id, jpeg)
                            onSaved(model.localPlaylistStore.get(created.id)?.asMusic() ?: created.asMusic()); onDismiss()
                        } else if (onServer) {
                            onSaved(model.createServerPlaylist(name, tracks)); onDismiss()
                        } else {
                            val created = if (playlist == null) model.createSpotifyPlaylist(name, tracks, published) else playlist.also {
                                if (name.trim() != it.title) model.spotifyPlaylists.rename(it.id, name)
                                model.spotifyPlaylists.setPublished(it.id, published)
                            }
                            // Record creation immediately: an upload failure must not create a second playlist on retry.
                            try {
                                if (jpeg != null) model.spotifyPlaylists.setCover(created.id, jpeg)
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                model.showMessage(coverFailure)
                            }
                            model.spotifyPlaylistChanged(created.copy(title = name.trim()))
                            onSaved(created)
                            onDismiss()
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = saveFailure }
                    finally { busy = false }
                }
            }, size = MeloButtonSize.Medium)
        },
        dismissButton = { MeloButton(text = stringResource(R.string.action_cancel), onClick = onDismiss, enabled = !busy, size = MeloButtonSize.Medium, style = MeloButtonStyle.Text) },
    )
}

internal fun playlistJpeg(context: android.content.Context, uri: Uri): ByteArray {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri).use { requireNotNull(it); BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image illisible." }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1200) sample *= 2
    val bitmap = resolver.openInputStream(uri).use { input ->
        BitmapFactory.decodeStream(requireNotNull(input), null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("Image illisible.")
    val orientation = runCatching {
        resolver.openInputStream(uri).use { ExifInterface(requireNotNull(it)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    val oriented = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    try {
        val side = minOf(oriented.width, oriented.height)
        val crop = Bitmap.createBitmap(oriented, (oriented.width - side) / 2, (oriented.height - side) / 2, side, side)
        val square = Bitmap.createScaledBitmap(crop, 640, 640, true)
        return try { ByteArrayOutputStream().also { square.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray() }
        finally { if (square !== crop) square.recycle(); if (crop !== oriented) crop.recycle() }
    } finally { if (oriented !== bitmap) oriented.recycle(); bitmap.recycle() }
}

/** Name and cover of a multi-service playlist. */
@Composable
internal fun LocalPlaylistEditor(model: MeloModel, playlist: Music, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveFailure = stringResource(R.string.spotify_playlist_failed)
    val stored = model.localPlaylistStore.get(playlist.id)
    var name by remember { mutableStateOf(playlist.title) }
    var image by remember { mutableStateOf<Uri?>(null) }
    var removeCover by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) { image = uri; removeCover = false } }
    val shown: Any? = image ?: if (removeCover) null else stored?.cover
    MeloAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.spotify_playlist_edit_local)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text(stringResource(R.string.playlist_name)) }, singleLine = true, enabled = !busy)
                if (shown != null) AsyncImage(shown, null, Modifier.size(100.dp))
                MeloButton(
                    text = stringResource(R.string.spotify_playlist_cover),
                    onClick = { picker.launch("image/*") },
                    enabled = !busy,
                    size = MeloButtonSize.Medium,
                    style = MeloButtonStyle.Outlined,
                )
                if (shown != null) MeloButton(
                    text = stringResource(R.string.playlist_cover_remove),
                    onClick = { image = null; removeCover = true },
                    enabled = !busy,
                    size = MeloButtonSize.Medium,
                    style = MeloButtonStyle.Text,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            MeloButton(text = stringResource(R.string.action_save), enabled = !busy && name.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    try {
                        if (name.trim() != playlist.title) model.localPlaylistStore.rename(playlist.id, name)
                        val picked = image
                        when {
                            picked != null -> model.setLocalPlaylistCover(playlist.id, withContext(Dispatchers.IO) { playlistJpeg(context, picked) })
                            removeCover -> model.setLocalPlaylistCover(playlist.id, null)
                        }
                        onDismiss()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = saveFailure }
                    finally { busy = false }
                }
            }, size = MeloButtonSize.Medium)
        },
        dismissButton = { MeloButton(text = stringResource(R.string.action_cancel), onClick = onDismiss, enabled = !busy, size = MeloButtonSize.Medium, style = MeloButtonStyle.Text) },
    )
}

/** Renames a playlist stored on the music server. */
@Composable
internal fun ServerPlaylistEditor(model: MeloModel, playlist: Music, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val saveFailure = stringResource(R.string.spotify_playlist_failed)
    var name by remember { mutableStateOf(playlist.title) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    MeloAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.spotify_playlist_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(150) }, label = { Text(stringResource(R.string.playlist_name)) }, singleLine = true, enabled = !busy)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            MeloButton(text = stringResource(R.string.action_save), enabled = !busy && name.isNotBlank(), onClick = {
                busy = true; error = null
                scope.launch {
                    try { model.editServerPlaylist(playlist, name = name); onDismiss() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = saveFailure }
                    finally { busy = false }
                }
            }, size = MeloButtonSize.Medium)
        },
        dismissButton = { MeloButton(text = stringResource(R.string.action_cancel), onClick = onDismiss, enabled = !busy, size = MeloButtonSize.Medium, style = MeloButtonStyle.Text) },
    )
}
