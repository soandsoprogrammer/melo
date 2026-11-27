package `in`.melo.presentation.screens

import `in`.melo.ui.glass.MeloSegmentedControl
import `in`.melo.presentation.icons.meloPainter
import `in`.melo.ui.theme.controlFill
import `in`.melo.SearchSource
import `in`.melo.ui.theme.MeloAlpha
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import `in`.melo.presentation.components.meloPanel
import `in`.melo.ui.theme.MeloLayout
import `in`.melo.R
import `in`.melo.MeloModel
import `in`.melo.data.Music
import `in`.melo.data.MusicKind
import `in`.melo.data.model.SearchFilterType
import `in`.melo.data.model.Song
import `in`.melo.data.model.toAlbum
import `in`.melo.data.model.toArtist
import `in`.melo.data.model.toPlaylist
import `in`.melo.data.model.toSong
import `in`.melo.presentation.components.MiniPlayerHeight
import `in`.melo.presentation.components.MusicOptionsHost
import `in`.melo.presentation.components.SongOptionsHost
import `in`.melo.presentation.components.resolveMainScreenBottomGradientHeight
import `in`.melo.presentation.components.subcomps.EnhancedSongListItem
import `in`.melo.presentation.navigation.openMusic
import kotlinx.coroutines.flow.distinctUntilChanged

private fun SearchFilterType.kind(): MusicKind? = when (this) {
    SearchFilterType.ALL -> null
    SearchFilterType.SONGS -> MusicKind.Track
    SearchFilterType.ALBUMS -> MusicKind.Album
    SearchFilterType.ARTISTS -> MusicKind.Artist
    SearchFilterType.PLAYLISTS -> MusicKind.Playlist
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    model: MeloModel,
    navController: NavHostController,
    paddingValues: PaddingValues,
    onSearchBarActiveChange: (Boolean) -> Unit,
) {
    val ui = model.ui
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val history by ui.searchHistory.collectAsStateWithLifecycle()
    val visibleHistory = history
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    val navBarCompactMode by ui.navBarCompactMode.collectAsStateWithLifecycle()
    val saved by model.favorites.collectAsStateWithLifecycle()
    var searchQuery by rememberSaveable { mutableStateOf(discovery.query) }
    var filter by rememberSaveable { mutableStateOf(SearchFilterType.ALL) }
    var selected by remember { mutableStateOf<Song?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val statusBarTopInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val colorScheme = MaterialTheme.colorScheme
    val appSettings by ui.settings.collectAsStateWithLifecycle()
    val server by model.serverLibrary.collectAsStateWithLifecycle()
    val showSources = appSettings.showSearchSources
    val searchSources = remember(discovery.spotifyEnabled, discovery.addonsEnabled) {
        SearchSource.entries.filter {
            (it != SearchSource.Spotify || discovery.spotifyEnabled) && (it != SearchSource.MusicServer || server != null) && (it != SearchSource.Addons || discovery.addonsEnabled)
        }
    }
    val activeSourceIndex = searchSources.indexOf(discovery.activeSource).coerceAtLeast(0)
    val nextSource = searchSources[(activeSourceIndex + 1) % searchSources.size]
    val activeSourceLabel = stringResource(discovery.activeSource.labelRes())
    val results = remember(discovery.results, filter) {
        val kind = filter.kind()
        discovery.results.filter { kind == null || it.kind == kind }
    }
    val listState = rememberLazyListState()
    val open: (Music) -> Unit = {
        ui.rememberSearchResult(it)
        navController.openMusic(ui, it)
    }
    val playFromHistory: (Music) -> Unit = { item ->
        ui.rememberSearchResult(item)
        if (item.kind == MusicKind.Track) ui.playTrack(item)
        else navController.openMusic(ui, item)
    }
    var optionsFor by remember { mutableStateOf<Music?>(null) }
    val showOptions: (Music) -> Unit = {
        // The long-click handler of the card already vibrates.
        optionsFor = it
    }
    LaunchedEffect(listState, results.size, discovery.hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (discovery.hasMore && last >= listState.layoutInfo.totalItemsCount - 6) model.searchNext() }
    }

    Box(Modifier.fillMaxSize().background(`in`.melo.presentation.components.pageBackground())) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = statusBarTopInset + 12.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                `in`.melo.presentation.components.MeloSearchField(
                    query = searchQuery,
                    onQueryChange = {
                        searchQuery = it
                        model.search(it)
                    },
                    onSearch = { query ->
                        model.search(query, false)
                        keyboard?.hide()
                    },
                    placeholder = stringResource(R.string.search_placeholder),
                    clearDescription = stringResource(R.string.search_cd_clear_search_query),
                    searchIconDescription = stringResource(R.string.search_cd_search_icon),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    modifier = Modifier.size(MeloLayout.ControlVisual),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.controlFill,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    onClick = {
                        keyboard?.hide()
                        model.setSearchSource(nextSource)
                    }
                ) {
                    AnimatedContent(
                        targetState = discovery.activeSource,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(180)) +
                                scaleIn(initialScale = 0.76f, animationSpec = tween(240))) togetherWith
                                (fadeOut(animationSpec = tween(100)) +
                                    scaleOut(targetScale = 0.76f, animationSpec = tween(100)))
                        },
                        label = "Search source logo",
                    ) { source ->
                        Icon(
                            painter = meloPainter(source.iconRes()),
                            contentDescription = stringResource(R.string.search_source_picker_cd, activeSourceLabel),
                            tint = source.tint(),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }

            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (searchQuery.isBlank()) {
                    if (visibleHistory.isNotEmpty()) {
                        SearchResultHistoryList(
                            items = visibleHistory,
                            onOpen = playFromHistory,
                            onDelete = ui::forgetSearch,
                            onClearAll = ui::clearSearchHistory,
                            bottomPadding = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 24.dp,
                        )
                    }
                } else {
                    // All is the default; this optional row offers quick source switching.
                    if (showSources) androidx.compose.foundation.layout.BoxWithConstraints(
                        Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp, end = 8.dp)
                    ) {
                        val fits = maxWidth >= SOURCE_SEGMENT_MIN_WIDTH * searchSources.size
                        // Too narrow for every source at a readable width: the selector keeps that
                        // width and scrolls sideways.
                        androidx.compose.foundation.layout.Box(
                            if (fits) Modifier.fillMaxWidth()
                            else Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())
                        ) {
                            MeloSegmentedControl(
                                options = searchSources.map { stringResource(it.labelRes()) },
                                selectedIndex = searchSources.indexOf(discovery.activeSource),
                                onSelect = { model.setSearchSource(searchSources[it]) },
                                modifier = if (fits) Modifier
                                    else Modifier.width(SOURCE_SEGMENT_MIN_WIDTH * searchSources.size),
                            )
                        }
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        SearchFilterType.entries.forEach { type ->
                            SearchFilterChip(type, filter, { filter = it })
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        when {
                            discovery.error != null && results.isEmpty() -> Text(
                                discovery.error.orEmpty(),
                                Modifier.padding(24.dp),
                                color = MaterialTheme.colorScheme.error,
                            )
                            results.isEmpty() && discovery.loading -> Box(Modifier.fillMaxSize().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
                                ContainedLoadingIndicator()
                            }
                            results.isEmpty() -> EmptySearchResults(searchQuery = searchQuery, colorScheme = colorScheme)
                            else -> LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    top = 8.dp,
                                    bottom = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 24.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // "All": one list, most relevant first, kinds and sources mixed, as Spotify's own search.
                                // A kind filter: that kind only, in the same order.
                                items(results, key = { "${it.kind}-${it.id}" }, contentType = { it.kind }) { item ->
                                    when (item.kind) {
                                        MusicKind.Track -> {
                                            val song = item.toSong(item.id in saved)
                                            val current = stable.currentSong?.id == song.id
                                            EnhancedSongListItem(
                                                song = song,
                                                isPlaying = current && stable.isPlaying,
                                                isCurrentSong = current,
                                                onMoreOptionsClick = { selected = it },
                                                onLongPress = { selected = song },
                                                onClick = {
                                                    ui.rememberSearchResult(item)
                                                    ui.playTrack(item)
                                                },
                                                showSourceBadge = true,
                                            )
                                        }
                                        MusicKind.Album -> SearchResultAlbumItem(
                                            album = item.toAlbum(),
                                            onOpenClick = { open(item) },
                                            onPlayClick = { ui.shuffleItem(item) },
                                            onLongPress = { showOptions(item) },
                                        )
                                        MusicKind.Artist -> SearchResultArtistItem(
                                            artist = item.toArtist(),
                                            onOpenClick = { open(item) },
                                            onPlayClick = { ui.shuffleItem(item) },
                                            onLongPress = { showOptions(item) },
                                        )
                                        else -> SearchResultPlaylistItem(
                                            playlist = item.toPlaylist(),
                                            onOpenClick = { open(item) },
                                            onPlayClick = { ui.shuffleItem(item) },
                                            onLongPress = { showOptions(item) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!`in`.melo.ui.glass.pagesUnderGlass()) Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(resolveMainScreenBottomGradientHeight(navBarCompactMode))
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.2f to Color.Transparent,
                            0.8f to MaterialTheme.colorScheme.surfaceContainerLowest,
                            1.0f to MaterialTheme.colorScheme.surfaceContainerLowest
                        )
                    )
                )
        )
    }
    SongOptionsHost(ui, navController, selected, emptyList(), searchQuery) { selected = null }
    MusicOptionsHost(ui, navController, optionsFor) { optionsFor = null }
}

private val SOURCE_SEGMENT_MIN_WIDTH = 92.dp

private fun SearchSource.labelRes(): Int = when (this) {
    SearchSource.All -> R.string.search_source_all
    SearchSource.Spotify -> R.string.search_source_spotify
    SearchSource.MusicServer -> R.string.music_server_title
    SearchSource.Local -> R.string.search_source_device
    SearchSource.Addons -> R.string.search_source_addons
}

private fun SearchSource.iconRes(): Int = when (this) {
    SearchSource.All -> R.drawable.ic_melo_mark
    SearchSource.Spotify -> R.drawable.ic_source_spotify
    SearchSource.MusicServer -> R.drawable.rounded_library_music_24
    SearchSource.Local -> R.drawable.rounded_library_music_24
    SearchSource.Addons -> R.drawable.outline_high_quality_24
}

@Composable
private fun SearchSource.tint(): Color = when (this) {
    SearchSource.All -> MaterialTheme.colorScheme.primary
    SearchSource.Spotify -> Color(0xFF1DB954)
    SearchSource.MusicServer -> MaterialTheme.colorScheme.secondary
    SearchSource.Local -> MaterialTheme.colorScheme.onSurfaceVariant
    SearchSource.Addons -> MaterialTheme.colorScheme.tertiary
}
