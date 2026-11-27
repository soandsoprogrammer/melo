package `in`.melo.presentation.components

import `in`.melo.presentation.icons.MeloIcons
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import `in`.melo.R
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import `in`.melo.data.model.Album
import `in`.melo.data.model.Artist
import `in`.melo.data.model.Playlist
import `in`.melo.presentation.viewmodel.ColorSchemePair
import `in`.melo.ui.theme.MeloLayout
import `in`.melo.ui.theme.artworkOutline
import `in`.melo.utils.formatSongCount

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumGridItemRedesigned(
    album: Album,
    albumColorSchemePairFlow: StateFlow<ColorSchemePair?>,
    onClick: () -> Unit,
    isLoading: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    onLongPress: () -> Unit = {},
    onSelectionToggle: () -> Unit = {},
) {
    if (isLoading) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShimmerBox(Modifier.fillMaxWidth().aspectRatio(1f).clip(MeloLayout.ArtworkShape))
        ShimmerBox(Modifier.fillMaxWidth(0.7f).height(16.dp).clip(MeloLayout.ArtworkShape))
        ShimmerBox(Modifier.fillMaxWidth(0.5f).height(14.dp).clip(MeloLayout.ArtworkShape))
    } else MeloShelfCard(
        title = album.title,
        subtitle = album.artist,
        onClick = { if (isSelectionMode) onSelectionToggle() else onClick() },
        onLongClick = onLongPress,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).semantics { selected = isSelected },
        overlay = {
            if (isSelected) {
                Box(Modifier.fillMaxSize().border(2.dp, MaterialTheme.colorScheme.primary, MeloLayout.ArtworkShape))
                SelectionMark(selectionIndex, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
        },
    ) {
        SmartImage(album.albumArtUriString, null, Modifier.fillMaxSize(), targetSize = coil.size.Size(512, 512))
    }
}

@Composable
fun ArtistListItem(artist: Artist, onClick: () -> Unit, isLoading: Boolean = false, onLongPress: () -> Unit = {}) {
    LibraryArtworkRow(artist.name, if (artist.songCount > 0) formatSongCount(artist.songCount) else stringResource(R.string.common_artist), artist.effectiveImageUrl, CircleShape,
        isLoading, false, null, onClick, onLongPress)
}

@Composable
fun AlbumListItem(
    album: Album,
    albumColorSchemePairFlow: StateFlow<ColorSchemePair?>,
    onClick: () -> Unit,
    isLoading: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    onLongPress: () -> Unit = {},
    onSelectionToggle: () -> Unit = {},
) {
    LibraryArtworkRow(album.title, album.artist, album.albumArtUriString, MeloLayout.ArtworkShape,
        isLoading, isSelected, selectionIndex, { if (isSelectionMode) onSelectionToggle() else onClick() }, onLongPress)
}

@Composable
fun PlaylistItem(
    playlist: Playlist,
    onClick: () -> Unit,
    isAddingToPlaylist: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    selectionIndex: Int = -1,
    onLongPress: () -> Unit = {},
    onPlaylistSelectionToggle: () -> Unit = {},
) {
    LibraryArtworkRow(playlist.name, playlist.description.ifBlank { formatSongCount(playlist.songCount) },
        playlist.coverImageUri, MeloLayout.ArtworkShape, false, isSelected,
        selectionIndex.takeIf { it >= 0 }?.plus(1),
        { if (isSelectionMode) onPlaylistSelectionToggle() else onClick() }, onLongPress,
        badge = {
            if (playlist.id.startsWith("local:")) LocalBadge()
            else if (`in`.melo.data.server.MusicServerUris.isPlaylist(playlist.id)) SourceBadge(`in`.melo.data.MusicSource.MusicServer)
        })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryArtworkRow(
    title: String, subtitle: String, image: String?, shape: Shape,
    loading: Boolean, selected: Boolean, selectionIndex: Int?, onClick: () -> Unit, onLongPress: () -> Unit,
    badge: (@Composable () -> Unit)? = null,
) {
    val compact = LocalCompactLists.current
    val artSize = if (compact) 40.dp else 56.dp
    Row(
        Modifier.fillMaxWidth().clip(MeloLayout.CardShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongPress)
            .semantics { this.selected = selected }.padding(horizontal = MeloLayout.Space4, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) ShimmerBox(Modifier.size(artSize).clip(shape))
        else SmartImage(image, null, Modifier.size(artSize).artworkOutline(shape), shape = shape, targetSize = SmartImageListTargetSize)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(MeloLayout.Space1)) {
            if (loading) {
                ShimmerBox(Modifier.fillMaxWidth(0.7f).height(18.dp).clip(MeloLayout.ArtworkShape))
                ShimmerBox(Modifier.fillMaxWidth(0.45f).height(14.dp).clip(MeloLayout.ArtworkShape))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MeloLayout.Space2)) {
                    Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    badge?.invoke()
                }
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) SelectionMark(selectionIndex)
    }
}

@Composable
private fun SelectionMark(index: Int?, modifier: Modifier = Modifier) {
    Box(modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
        if (index != null) Text(index.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
        else Icon(MeloIcons.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}
