package `in`.melo.presentation.screens

import `in`.melo.presentation.icons.MeloIcons
import `in`.melo.presentation.icons.meloPainter
import `in`.melo.presentation.components.SmartImageListTargetSize
import `in`.melo.utils.formatSongCount
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImagePainter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import `in`.melo.data.Music
import `in`.melo.data.MusicKind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.util.Log
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusModifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import androidx.compose.ui.res.stringResource
import `in`.melo.data.model.SearchFilterType
import `in`.melo.presentation.components.AutoScrollingText
import `in`.melo.R
import `in`.melo.data.model.Song
import `in`.melo.data.model.Album
import `in`.melo.data.model.Artist
import `in`.melo.data.model.Playlist
import `in`.melo.ui.theme.GoogleSansRounded
import `in`.melo.ui.theme.LocalMeloDarkTheme
import `in`.melo.presentation.viewmodel.ColorSchemePair
import `in`.melo.presentation.viewmodel.PlayerViewModel
import `in`.melo.presentation.components.SmartImage
import `in`.melo.presentation.components.ShimmerBox
import `in`.melo.presentation.components.AutoScrollingTextOnDemand
import `in`.melo.presentation.components.MeloButton
import `in`.melo.presentation.components.MeloButtonSize
import `in`.melo.presentation.components.MeloButtonStyle
import `in`.melo.presentation.components.MeloListRow
import `in`.melo.presentation.components.subcomps.AutoSizingTextToFill
import `in`.melo.presentation.components.subcomps.TightWrapText
import `in`.melo.presentation.components.MiniPlayerHeight
import `in`.melo.utils.formatDuration
import `in`.melo.ui.theme.ExpTitleTypography
import `in`.melo.ui.theme.MeloAlpha
import `in`.melo.ui.theme.MeloLayout

@Composable
fun SearchResultSectionHeader(title: String) {
    `in`.melo.presentation.components.MeloSectionHeader(title, contentPadding = PaddingValues(horizontal = 4.dp))
}

@Composable
fun EmptySearchResults(searchQuery: String, colorScheme: ColorScheme) {
    `in`.melo.presentation.components.MeloMessageState(
        title = if (searchQuery.isNotBlank()) {
            stringResource(R.string.search_no_results_for_query, searchQuery)
        } else {
            stringResource(R.string.search_nothing_found)
        },
        message = stringResource(R.string.search_try_different_or_filters),
        icon = MeloIcons.Search,
        modifier = Modifier.padding(top = MeloLayout.Space8),
    )
}

@Composable
fun SearchResultAlbumItem(
    album: Album,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    isSelectionMode: Boolean = false,
    onLongPress: () -> Unit = {},
    onSelectionToggle: () -> Unit = {},
) {
    MeloListRow(
        title = album.title,
        subtitle = album.artist,
        imageModel = album.albumArtUriString,
        artContentDescription = stringResource(R.string.common_album_art_for_title, album.title),
        playContentDescription = stringResource(R.string.common_play_album),
        onClick = onOpenClick,
        onPlayClick = onPlayClick,
        playContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = MeloAlpha.Secondary),
        playContentColor = MaterialTheme.colorScheme.onSecondary,
        isSelected = isSelected,
        selectionIndex = selectionIndex,
        isSelectionMode = isSelectionMode,
        onLongClick = onLongPress,
        onSelectionToggle = onSelectionToggle,
    )
}

@Composable
fun SearchResultArtistItem(
    artist: Artist,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val imageUrl = artist.effectiveImageUrl.takeUnless { it.isNullOrBlank() }
    val imageDescription = if (imageUrl != null) {
        stringResource(R.string.search_cd_artist_image_for_name, artist.name)
    } else {
        null
    }
    MeloListRow(
        title = artist.name,
        subtitle = stringResource(R.string.search_artist_kind),
        imageModel = imageUrl,
        artContentDescription = imageDescription,
        playContentDescription = stringResource(R.string.search_cd_play_artist),
        onClick = onOpenClick,
        onPlayClick = onPlayClick,
        artist = true,
        fallbackPainter = if (imageUrl == null) meloPainter(R.drawable.rounded_artist_24) else null,
        fallbackContentDescription = if (imageUrl == null) stringResource(R.string.common_artist) else null,
        playContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = MeloAlpha.Secondary),
        playContentColor = MaterialTheme.colorScheme.onTertiary,
        onLongClick = onLongPress,
    )
}

@Composable
fun SearchResultPlaylistItem(
    playlist: Playlist,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    isSelectionMode: Boolean = false,
    onLongPress: () -> Unit = {},
    onSelectionToggle: () -> Unit = {},
) {
    MeloListRow(
        title = playlist.name,
        subtitle = playlist.description.ifBlank { stringResource(R.string.search_playlist_kind) },
        imageModel = playlist.coverImageUri,
        artContentDescription = stringResource(R.string.search_cd_playlist_art_for_name, playlist.name),
        playContentDescription = stringResource(R.string.search_cd_play_playlist),
        onClick = onOpenClick,
        onPlayClick = onPlayClick,
        isSelected = isSelected,
        selectionIndex = selectionIndex,
        isSelectionMode = isSelectionMode,
        onLongClick = onLongPress,
        onSelectionToggle = onSelectionToggle,
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SearchFilterChip(
    filterType: SearchFilterType,
    currentFilter: SearchFilterType,
    onSelect: (SearchFilterType) -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = filterType == currentFilter

    val labelResId = when (filterType) {
        SearchFilterType.ALL -> R.string.common_all
        SearchFilterType.SONGS -> R.string.library_tab_songs
        SearchFilterType.ALBUMS -> R.string.library_tab_albums
        SearchFilterType.ARTISTS -> R.string.library_tab_artists
        SearchFilterType.PLAYLISTS -> R.string.library_tab_playlists
    }

    `in`.melo.presentation.components.MeloChip(
        label = stringResource(labelResId),
        selected = selected,
        onClick = { onSelect(filterType) },
        modifier = modifier,
    )
}

/** What was opened or played from search, with its picture, kind and a button to remove it. */
@Composable
fun SearchResultHistoryList(
    items: List<Music>,
    onOpen: (Music) -> Unit,
    onDelete: (Music) -> Unit,
    onClearAll: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.search_recent_searches), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            MeloButton(
                text = stringResource(R.string.search_action_clear_all),
                onClick = onClearAll,
                size = MeloButtonSize.Medium,
                style = MeloButtonStyle.Text,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding),
        ) {
            items(items, key = { "history_${it.kind}_${it.id}" }, contentType = { "search_history" }) { item ->
                Row(
                modifier = Modifier.fillMaxWidth().clip(MeloLayout.CardShape)
                        .clickable { onOpen(item) }.padding(vertical = MeloLayout.Space2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    `in`.melo.presentation.components.SmartImage(
                        model = item.imageUrl, contentDescription = null,
                        shape = if (item.kind == MusicKind.Artist) MeloLayout.ArtistShape else MeloLayout.ArtworkShape,
                        modifier = Modifier.size(MeloLayout.ArtList),
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val kind = stringResource(when (item.kind) {
                            MusicKind.Track -> R.string.search_history_kind_track
                            MusicKind.Album -> R.string.search_history_kind_album
                            MusicKind.Artist -> R.string.search_history_kind_artist
                            else -> R.string.search_history_kind_playlist
                        })
                        val detail = item.subtitle.takeIf { it.isNotBlank() && item.kind != MusicKind.Artist }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Where the result comes from, as in the result list. A multi-service playlist has no single source.
                            val source = when {
                                item.id.startsWith(`in`.melo.data.LOCAL_PLAYLIST_PREFIX) -> null
                                `in`.melo.data.server.MusicServerUris.isPlaylist(item.id) -> `in`.melo.data.MusicSource.MusicServer
                                else -> `in`.melo.data.musicSourceOf(item.id)
                            }
                            if (source != null && `in`.melo.presentation.components.LocalSourceBadges.current) {
                                `in`.melo.presentation.components.SourceBadge(source, Modifier.padding(end = MeloLayout.Space2),
                                    MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(listOfNotNull(kind, detail).joinToString(" • "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = { onDelete(item) }) {
                        Icon(MeloIcons.Close, stringResource(R.string.search_cd_delete_search_history_item),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MeloAlpha.Secondary))
                    }
                }
            }
        }
    }
}
