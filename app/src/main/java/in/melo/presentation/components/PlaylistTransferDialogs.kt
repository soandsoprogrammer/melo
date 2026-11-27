package `in`.melo.presentation.components

import `in`.melo.ui.glass.MeloAlertDialog
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.melo.R
import `in`.melo.MeloModel
import `in`.melo.data.Music
import `in`.melo.data.isLocalPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Imports a CSV file exported by Melo, or a YouTube, YouTube Music or Spotify playlist link. */
@Composable
fun ImportPlaylistDialog(model: MeloModel, onDismiss: () -> Unit, onImported: (Music) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.import_failed)
    val emptyCsv = stringResource(R.string.playlist_csv_empty)
    val invalidCsv = stringResource(R.string.playlist_csv_invalid)
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run(action: suspend () -> Music) {
        busy = true; error = null
        scope.launch {
            try { onImported(action()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = when (failure.message) {
                    "The file is empty." -> emptyCsv
                    "This file was not exported by Melo." -> invalidCsv
                    else -> failed
                }
            }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            val (name, text) = withContext(Dispatchers.IO) { displayName(context, uri) to readText(context, uri) }
            model.importCsv(text, name)
        }
    }
    MeloAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.playlist_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MeloButton(
                    text = stringResource(R.string.import_from_file),
                    onClick = { picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "*/*")) },
                    enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    size = MeloButtonSize.Medium,
                    style = MeloButtonStyle.Outlined,
                )
                Text(stringResource(R.string.import_from_link), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = link, onValueChange = { link = it.trim() }, singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.import_link_label)) }, modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Text(stringResource(R.string.import_running), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            MeloButton(
                text = stringResource(R.string.import_action),
                onClick = { run { model.importLink(link) } },
                enabled = !busy && link.isNotBlank(),
                size = MeloButtonSize.Medium,
            )
        },
        dismissButton = {
            MeloButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                enabled = !busy,
                size = MeloButtonSize.Medium,
                style = MeloButtonStyle.Text,
            )
        },
    )
}

/** Returns a function that asks where to save a playlist as CSV, then writes it there. */
@Composable
fun rememberPlaylistCsvExport(model: MeloModel): (Music) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exported = stringResource(R.string.playlist_exported)
    val failed = stringResource(R.string.backup_failed)
    var pending by remember { mutableStateOf<Music?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val item = pending
        pending = null
        if (uri != null && item != null) scope.launch {
            val ok = try {
                val csv = model.exportCsv(item)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } ?: error("No output")
                }
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            Toast.makeText(context, if (ok) exported else failed, Toast.LENGTH_SHORT).show()
        }
    }
    return { item ->
        pending = item
        launcher.launch(item.title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "playlist" } + ".csv")
    }
}

/** Confirms, then deletes a multi-service playlist from the device or a Spotify playlist from the library. */
@Composable
fun DeletePlaylistDialog(model: MeloModel, item: Music, onDismiss: () -> Unit, onConfirmed: () -> Unit = {}) {
    MeloAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_delete)) },
        text = {
            Text(stringResource(when {
                item.isLocalPlaylist -> R.string.playlist_delete_confirm
                item.id.startsWith(`in`.melo.data.youtube.YTM_PLAYLIST_PREFIX) ->
                    if (model.youTubePlaylistOwned(item)) R.string.playlist_delete_youtube_confirm else R.string.playlist_remove_youtube_confirm
                else -> R.string.playlist_delete_spotify_confirm
            }, item.title))
        },
        confirmButton = {
            MeloButton(
                text = stringResource(R.string.action_delete),
                onClick = { onDismiss(); model.deletePlaylist(item); onConfirmed() },
                size = MeloButtonSize.Medium,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
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

private fun readText(context: android.content.Context, uri: Uri): String =
    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Unreadable file")

private fun displayName(context: android.content.Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "playlist.csv"
