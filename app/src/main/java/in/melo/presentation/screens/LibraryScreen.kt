package `in`.melo.presentation.screens

import `in`.melo.presentation.icons.MeloIcons
import `in`.melo.ui.theme.MeloAlpha
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import `in`.melo.presentation.components.MeloLargeTopBar
import `in`.melo.presentation.components.MeloChip
import `in`.melo.presentation.components.barMaterialColor
import `in`.melo.ui.theme.separator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import `in`.melo.presentation.components.HomeGradientTopBar
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ime
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import `in`.melo.R
import `in`.melo.MeloModel
import `in`.melo.data.HomeLibrary
import `in`.melo.data.LocalLibrary
import `in`.melo.data.Music
import `in`.melo.data.model.LibraryTabId
import `in`.melo.data.model.Song
import `in`.melo.data.model.SortOption
import `in`.melo.data.model.toAlbum
import `in`.melo.data.model.toArtist
import `in`.melo.data.model.toPlaylist
import `in`.melo.data.model.toSong
import `in`.melo.presentation.components.AlbumGridItemRedesigned
import `in`.melo.presentation.components.AlbumListItem
import `in`.melo.presentation.components.ArtistListItem
import `in`.melo.presentation.components.LibrarySortBottomSheet
import `in`.melo.presentation.components.ListeningStatusCard
import `in`.melo.presentation.components.ListeningShelfPlaceholder
import `in`.melo.presentation.navigation.navigateToTopLevelSafely
import `in`.melo.presentation.components.MiniPlayerHeight
import `in`.melo.presentation.components.MeloTopActionsCapsule
import `in`.melo.presentation.components.MeloProfileAccountButton
import `in`.melo.presentation.components.homePress
import `in`.melo.presentation.components.MusicOptionsHost
import `in`.melo.presentation.components.PlaylistItem
import `in`.melo.presentation.components.PlaylistNameDialog
import `in`.melo.presentation.components.SongOptionsHost
import `in`.melo.presentation.components.subcomps.EnhancedSongListItem
import `in`.melo.presentation.components.subcomps.LibraryActionRow
import `in`.melo.presentation.navigation.Screen
import `in`.melo.presentation.navigation.navigateSafely
import `in`.melo.presentation.navigation.openMusic
import `in`.melo.playback.DownloadedTrack
import `in`.melo.playback.CollectionProgress
import `in`.melo.data.MusicKind
import `in`.melo.utils.formatSongCount
import `in`.melo.utils.matchesWords
import `in`.melo.presentation.viewmodel.PlayerViewModel
import `in`.melo.ui.theme.GoogleSansRounded
import `in`.melo.ui.theme.LocalMeloDarkTheme
import `in`.melo.ui.theme.MeloLayout
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

private val accountTabs = listOf(LibraryTabId.PLAYLISTS, LibraryTabId.LIKED, LibraryTabId.ALBUMS, LibraryTabId.ARTISTS, LibraryTabId.LOCAL, LibraryTabId.DOWNLOADS)
/** Without a Spotify account there are no liked songs, saved albums or followed artists. */
private val guestTabs = listOf(LibraryTabId.PLAYLISTS, LibraryTabId.LOCAL, LibraryTabId.DOWNLOADS)

private fun sortOptions(tab: LibraryTabId): List<SortOption> = when (tab) {
    LibraryTabId.PLAYLISTS -> listOf(SortOption.SongDefaultOrder, SortOption.PlaylistNameAZ, SortOption.PlaylistNameZA)
    LibraryTabId.ALBUMS -> listOf(
        SortOption.SongDefaultOrder, SortOption.AlbumTitleAZ, SortOption.AlbumTitleZA,
        SortOption.AlbumArtist, SortOption.AlbumArtistDesc,
        SortOption.AlbumReleaseYear, SortOption.AlbumReleaseYearAsc,
    )
    LibraryTabId.ARTISTS -> listOf(SortOption.SongDefaultOrder, SortOption.ArtistNameAZ, SortOption.ArtistNameZA)
    // Saved tracks are paged from Spotify: sorting would only reorder what is already loaded.
    LibraryTabId.LIKED, LibraryTabId.DOWNLOADS -> emptyList()
    LibraryTabId.LOCAL -> listOf(SortOption.SongTitleAZ, SortOption.SongTitleZA, SortOption.SongArtist, SortOption.SongArtistDesc, SortOption.SongAlbum, SortOption.SongAlbumDesc, SortOption.SongDuration, SortOption.SongDurationAsc)
}

private fun List<Music>.sortedFor(option: SortOption): List<Music> = when (option) {
    SortOption.PlaylistNameAZ, SortOption.AlbumTitleAZ, SortOption.ArtistNameAZ -> sortedBy { it.title.lowercase() }
    SortOption.PlaylistNameZA, SortOption.AlbumTitleZA, SortOption.ArtistNameZA -> sortedByDescending { it.title.lowercase() }
    SortOption.AlbumArtist -> sortedBy { it.subtitle.lowercase() }
    SortOption.AlbumArtistDesc -> sortedByDescending { it.subtitle.lowercase() }
    SortOption.AlbumReleaseYear -> sortedByDescending { it.note.toIntOrNull() ?: 0 }
    SortOption.AlbumReleaseYearAsc -> sortedBy { it.note.toIntOrNull() ?: Int.MAX_VALUE }
    SortOption.SongTitleAZ -> sortedBy { it.title.lowercase() }
    SortOption.SongTitleZA -> sortedByDescending { it.title.lowercase() }
    SortOption.SongArtist -> sortedBy { it.subtitle.lowercase() }
    SortOption.SongArtistDesc -> sortedByDescending { it.subtitle.lowercase() }
    SortOption.SongAlbum -> sortedBy { it.album.lowercase() }
    SortOption.SongAlbumDesc -> sortedByDescending { it.album.lowercase() }
    SortOption.SongDuration -> sortedByDescending { it.durationMs }
    SortOption.SongDurationAsc -> sortedBy { it.durationMs }
    else -> this
}

private fun Music.matchesLibrarySearch(query: String): Boolean =
    query.isBlank() || matchesWords(query, title, subtitle, album)

private fun List<Music>.matchingLibrarySearch(query: String): List<Music> =
    if (query.isBlank()) this else filter { it.matchesLibrarySearch(query) }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryScreen(model: MeloModel, navController: NavHostController, paddingValues: PaddingValues) {
    val ui = model.ui
    val library by model.library.collectAsStateWithLifecycle()
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val spotifyProfile by model.spotifyAccountProfile.collectAsStateWithLifecycle()
    val libraryTabs = if (discovery.spotifyEnabled) accountTabs else guestTabs
    var savedTab by rememberSaveable { mutableStateOf(0) }
    val pagerState = rememberPagerState(initialPage = savedTab.coerceIn(0, libraryTabs.lastIndex)) { libraryTabs.size }
    val tabRowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val requestedTab by ui.requestedLibraryTab.collectAsStateWithLifecycle()
    LaunchedEffect(requestedTab) {
        val requested = libraryTabs.indexOfFirst { it.storageKey == requestedTab }
        if (requested >= 0) { pagerState.scrollToPage(requested); ui.requestedLibraryTab.value = null }
    }
    val currentTab = libraryTabs[pagerState.currentPage.coerceIn(0, libraryTabs.lastIndex)]
    var librarySearchOpen by rememberSaveable { mutableStateOf(false) }
    var librarySearchQuery by rememberSaveable { mutableStateOf("") }
    val searchKeyboard = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(librarySearchOpen) {
        if (librarySearchOpen) {
            runCatching { searchFocusRequester.requestFocus(); searchKeyboard?.show() }
        } else searchKeyboard?.hide()
    }
    LaunchedEffect(currentTab) { librarySearchQuery = "" }
    BackHandler(enabled = librarySearchOpen) {
        librarySearchOpen = false
        librarySearchQuery = ""
        searchKeyboard?.hide()
    }
    var importing by remember { mutableStateOf(false) }
    var sortKeys by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    var albumsAsList by rememberSaveable { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    val localPlaylists by model.localPlaylists.collectAsStateWithLifecycle()
    val serverPlaylists by model.serverPlaylists.collectAsStateWithLifecycle()
    val localTracks by model.localTracks.collectAsStateWithLifecycle()
    val localTitle = stringResource(R.string.library_tab_local)
    val downloads by ui.downloads.collectAsStateWithLifecycle()
    val downloadCollections by ui.downloadCollections.collectAsStateWithLifecycle()
    val downloadsTitle = stringResource(R.string.library_tab_downloads)
    val searchQuery = librarySearchQuery.trim()
    val content = library.content

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { savedTab = it }
    }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage in libraryTabs.indices) {
            tabRowState.animateScrollToItem(if (pagerState.currentPage == 0) 0 else pagerState.currentPage + 1)
        }
    }

    val options = remember(currentTab) { sortOptions(currentTab) }
    val selectedSort = options.firstOrNull { it.storageKey == sortKeys[currentTab.storageKey] } ?: options.firstOrNull()
    fun sortOf(tab: LibraryTabId): SortOption =
        sortOptions(tab).firstOrNull { it.storageKey == sortKeys[tab.storageKey] } ?: SortOption.SongDefaultOrder

    val filteredPlaylists = remember(localPlaylists, serverPlaylists, content?.playlists, searchQuery, sortKeys) {
        (localPlaylists.map { it.asMusic() } + serverPlaylists + content?.playlists.orEmpty())
            .sortedFor(sortOf(LibraryTabId.PLAYLISTS)).matchingLibrarySearch(searchQuery)
    }
    val filteredAlbums = remember(content?.albums, searchQuery, sortKeys) {
        content?.albums.orEmpty().sortedFor(sortOf(LibraryTabId.ALBUMS)).matchingLibrarySearch(searchQuery)
    }
    val filteredArtists = remember(content?.artists, searchQuery, sortKeys) {
        content?.artists.orEmpty().sortedFor(sortOf(LibraryTabId.ARTISTS)).matchingLibrarySearch(searchQuery)
    }
    val filteredLocalTracks = remember(localTracks, searchQuery, sortKeys) {
        localTracks.sortedFor(sortOf(LibraryTabId.LOCAL)).matchingLibrarySearch(searchQuery)
    }
    val filteredLikedTracks = remember(content?.savedTracks, searchQuery) {
        content?.savedTracks.orEmpty().matchingLibrarySearch(searchQuery)
    }
    LaunchedEffect(searchQuery, content?.savedTracksLoaded, content?.savedTrackUris?.size) {
        if (searchQuery.isNotBlank() && content != null && content.savedTracksLoaded < content.savedTrackUris.size) {
            model.loadMoreSavedTracks()
        }
    }
    val filteredDownloadCollections = remember(downloadCollections, searchQuery) {
        downloadCollections.values.sortedBy { it.collection.item.title.lowercase() }.filter { entry ->
            entry.collection.item.matchesLibrarySearch(searchQuery) ||
                (searchQuery.isNotBlank() && entry.collection.tracks.any { it.matchesLibrarySearch(searchQuery) })
        }
    }

    val open: (Music) -> Unit = { navController.openMusic(ui, it) }
    var optionsFor by remember { mutableStateOf<Music?>(null) }
    val pin: (Music) -> Unit = {
        // The long-click handler of the card already vibrates.
        optionsFor = it
    }

    val gradientColors = if (LocalMeloDarkTheme.current) {
        listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = MeloAlpha.Tertiary), Color.Transparent)
    } else {
        listOf(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = MeloAlpha.Divider), Color.Transparent)
    }
    val gradientBrush = remember(gradientColors) { Brush.verticalGradient(colors = gradientColors) }
    val headerContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = MeloAlpha.Disabled)

    // The large "Library" title shrinks into a small one on a see-through bar as the
    // shelves scroll; the tab chips stay pinned under it on the same material.
    Scaffold(
        modifier = Modifier
            .background(brush = gradientBrush),
        containerColor = `in`.melo.presentation.components.pageBackground(),
        topBar = {
            // No fill behind the header: the list starts below it, so nothing ever runs under it.
            Column {
                // The same title bar as Home, Explore and Podcasts: large title beside the actions,
                // staying put (and full size) while the shelves scroll under it.
                HomeGradientTopBar(
                    onSettingsClick = { navController.navigateSafely(Screen.Settings.route) },
                    onHistoryClick = { navController.navigateSafely(Screen.History.route) },
                    spotifyConnected = discovery.spotifyEnabled,
                    spotifyProfile = spotifyProfile,
                    onProfileClick = { navController.navigateSafely(Screen.SettingsCategory.createRoute("account")) },
                    transparentContainer = true,
                    settingsButtonDirect = true,
                    title = stringResource(R.string.library_screen_title),
                )
                LazyRow(
                    state = tabRowState,
                    horizontalArrangement = Arrangement.spacedBy(MeloLayout.Space2),
                    contentPadding = PaddingValues(horizontal = MeloLayout.PageGutter),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    item("music_server") {
                        MeloChip(
                            label = stringResource(R.string.music_server_title),
                            selected = false,
                            onClick = { navController.navigateSafely(Screen.MusicServer.route) },
                            onWash = true,
                        )
                    }
                    itemsIndexed(libraryTabs, key = { _, tab -> tab.storageKey }) { index, tab ->
                        val selected = pagerState.currentPage == index
                        // The shared chip: glass over the colour background, solid in its colour when chosen.
                        MeloChip(
                            label = stringResource(tab.titleRes),
                            selected = selected,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            onWash = true,
                        )
                    }
                }
            }
        }
    ) { inner ->
        Box(Modifier.padding(top = inner.calculateTopPadding()).fillMaxSize()) {
            Column(Modifier.background(if (`in`.melo.presentation.components.LocalAmbientBackground.current) Color.Transparent else headerContainerColor).fillMaxSize()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = `in`.melo.presentation.components.pageBackground(),
                    shape = androidx.compose.ui.graphics.RectangleShape
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LibraryActionRow(
                                modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                                onMainActionClick = {
                                    if (currentTab == LibraryTabId.PLAYLISTS) { creatingPlaylist = true; return@LibraryActionRow }
                                    if (currentTab == LibraryTabId.DOWNLOADS) {
                                        ui.playSongsShuffled(downloads.values.filter { it.isComplete && it.track.matchesLibrarySearch(searchQuery) }.map { it.track.toSong() }, downloadsTitle)
                                        return@LibraryActionRow
                                    }
                                    if (currentTab == LibraryTabId.LOCAL) {
                                        ui.playSongsShuffled(filteredLocalTracks.map { it.toSong() }, localTitle)
                                        return@LibraryActionRow
                                    }
                                    val content = content ?: return@LibraryActionRow
                                    when (currentTab) {
                                        LibraryTabId.LIKED -> ui.playSongsShuffled(filteredLikedTracks.map { it.toSong() }, ui.likedSongs.title)
                                        LibraryTabId.ALBUMS -> filteredAlbums.randomOrNull()?.let(ui::shuffleItem)
                                        LibraryTabId.ARTISTS -> filteredArtists.randomOrNull()?.let(ui::shuffleItem)
                                        else -> Unit
                                    }
                                },
                                iconRotation = 0f,
                                showSearchButton = true,
                                isSearchOpen = librarySearchOpen,
                                onSearchClick = {
                                    if (librarySearchOpen) {
                                        librarySearchOpen = false
                                        librarySearchQuery = ""
                                        searchKeyboard?.hide()
                                    } else librarySearchOpen = true
                                },
                                showSortButton = options.isNotEmpty(),
                                onSortClick = { showSortSheet = true },
                                isPlaylistTab = currentTab == LibraryTabId.PLAYLISTS,
                                showImportButton = true,
                                onImportM3uClick = { importing = true },
                                isShuffleEnabled = stable.isShuffleEnabled,
                            )
                        }
                        if (librarySearchOpen) {
                            OutlinedTextField(
                                value = librarySearchQuery,
                                onValueChange = { librarySearchQuery = it },
                                modifier = Modifier.fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                                    .focusRequester(searchFocusRequester),
                                singleLine = true,
                                label = {
                                    Text(
                                        stringResource(R.string.library_search_hint, stringResource(currentTab.titleRes)),
                                        maxLines = 1,
                                    )
                                },
                                leadingIcon = { Icon(MeloIcons.Search, contentDescription = null) },
                                trailingIcon = if (librarySearchQuery.isNotEmpty()) ({
                                    Box(
                                        modifier = Modifier.size(48.dp).clickable(role = Role.Button) { librarySearchQuery = "" },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            MeloIcons.Close,
                                            contentDescription = stringResource(R.string.playlist_filter_clear),
                                        )
                                    }
                                }) else null,
                                shape = MeloLayout.PillShape,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { searchKeyboard?.hide() }),
                            )
                        }
                        val bottomPadding = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 24.dp
                        PullToRefreshBox(
                            isRefreshing = library.loading && content != null,
                            onRefresh = model::refreshLibrary,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (currentTab == LibraryTabId.DOWNLOADS && content == null) {
                                DownloadsTab(ui, navController, downloadsTitle, bottomPadding, filteredDownloadCollections, searchQuery)
                            } else if (content == null) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    if (library.loading) ListeningShelfPlaceholder()
                                    else ListeningStatusCard(
                                        stringResource(R.string.melo_library_error_title),
                                        library.error ?: stringResource(R.string.lib_unavailable),
                                        stringResource(R.string.melo_retry), model::refreshLibrary,
                                        Modifier.padding(24.dp)
                                    )
                                }
                            } else {
                                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                                    val tab = libraryTabs[page]
                                    val isEmpty = when (tab) {
                                        LibraryTabId.PLAYLISTS -> localPlaylists.isEmpty() && serverPlaylists.isEmpty() && content.playlists.isEmpty()
                                        LibraryTabId.LIKED -> content.savedTrackCount == 0
                                        LibraryTabId.ALBUMS -> content.albums.isEmpty()
                                        LibraryTabId.ARTISTS -> content.artists.isEmpty()
                                        else -> false
                                    }
                                    val hasSearchResults = when (tab) {
                                        LibraryTabId.PLAYLISTS -> filteredPlaylists.isNotEmpty()
                                        LibraryTabId.LIKED -> filteredLikedTracks.isNotEmpty() ||
                                            content.savedTracksLoaded < content.savedTrackUris.size
                                        LibraryTabId.ALBUMS -> filteredAlbums.isNotEmpty()
                                        LibraryTabId.ARTISTS -> filteredArtists.isNotEmpty()
                                        LibraryTabId.DOWNLOADS -> filteredDownloadCollections.isNotEmpty()
                                        LibraryTabId.LOCAL -> !model.localLibrary.hasPermission() || filteredLocalTracks.isNotEmpty()
                                    }
                                    if (searchQuery.isNotBlank() && !hasSearchResults) {
                                        LibrarySearchEmptyState(searchQuery, Modifier.fillMaxSize())
                                    } else if (isEmpty) {
                                        ListeningStatusCard(
                                            stringResource(R.string.melo_empty_library_title),
                                            stringResource(if (tab == LibraryTabId.PLAYLISTS) R.string.melo_empty_playlists_body else R.string.melo_empty_library_body),
                                            stringResource(if (tab == LibraryTabId.PLAYLISTS) R.string.melo_create_playlist else R.string.melo_discover),
                                            { if (tab == LibraryTabId.PLAYLISTS) creatingPlaylist = true
                                              else navController.navigateToTopLevelSafely(Screen.Search.route) },
                                            Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                                        )
                                    } else when (tab) {
                                        LibraryTabId.PLAYLISTS -> PlaylistsTab(filteredPlaylists, bottomPadding, open, pin)
                                        LibraryTabId.LIKED -> LikedTab(model, ui, navController, content, bottomPadding, searchQuery)
                                        LibraryTabId.ALBUMS -> AlbumsTab(filteredAlbums, albumsAsList, ui, bottomPadding, open, pin)
                                        LibraryTabId.ARTISTS -> ArtistsTab(filteredArtists, bottomPadding, open, pin)
                                        LibraryTabId.DOWNLOADS -> DownloadsTab(ui, navController, downloadsTitle, bottomPadding, filteredDownloadCollections, searchQuery)
                                        LibraryTabId.LOCAL -> LocalTab(model, ui, navController, filteredLocalTracks, localTitle, bottomPadding, searchQuery)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    MusicOptionsHost(ui, navController, optionsFor) { optionsFor = null }
    if (importing) `in`.melo.presentation.components.ImportPlaylistDialog(model, onDismiss = { importing = false }) { imported ->
        importing = false
        navController.openMusic(ui, imported)
    }
    if (creatingPlaylist) `in`.melo.presentation.components.SpotifyPlaylistEditor(model, onDismiss = { creatingPlaylist = false },
        onSaved = { created -> created?.let { ui.register(it); navController.openMusic(ui, it) } })

    if (showSortSheet && options.isNotEmpty()) {
        LibrarySortBottomSheet(
            title = stringResource(R.string.library_cd_sort_options),
            options = options,
            selectedOption = selectedSort,
            onDismiss = { showSortSheet = false },
            onOptionSelected = { option -> sortKeys = sortKeys + (currentTab.storageKey to option.storageKey) },
            showViewToggle = currentTab == LibraryTabId.ALBUMS,
            viewSectionTitle = stringResource(R.string.view_section),
            viewToggleLabel = stringResource(R.string.view_as_list),
            viewToggleChecked = albumsAsList,
            onViewToggleChange = { albumsAsList = it },
        )
    }
}

@Composable
private fun PlaylistsTab(playlists: List<Music>, bottom: Dp, open: (Music) -> Unit, onLongPress: (Music) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(`in`.melo.presentation.components.listSpacing()),
    ) {
        items(playlists, key = Music::id) { item ->
            PlaylistItem(
                playlist = item.toPlaylist(),
                onClick = { open(item) },
                onLongPress = { onLongPress(item) },
            )
        }
    }
}

@Composable
private fun AlbumsTab(
    albums: List<Music>,
    asList: Boolean,
    ui: PlayerViewModel,
    bottom: Dp,
    open: (Music) -> Unit,
    onLongPress: (Music) -> Unit,
) {
    if (asList) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(`in`.melo.presentation.components.listSpacing()),
        ) {
            items(albums, key = Music::id) { item ->
                AlbumListItem(
                    album = item.toAlbum(),
                    albumColorSchemePairFlow = ui.getAlbumColorSchemeFlow(item.imageUrl.orEmpty()),
                    onClick = { open(item) },
                    onLongPress = { onLongPress(item) },
                )
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = MeloLayout.Space4, end = MeloLayout.Space4, top = 4.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(if (`in`.melo.presentation.components.LocalCompactLists.current) 8.dp else 14.dp),
            horizontalArrangement = Arrangement.spacedBy(if (`in`.melo.presentation.components.LocalCompactLists.current) 8.dp else 14.dp),
        ) {
            items(albums, key = Music::id) { item ->
                AlbumGridItemRedesigned(
                    album = item.toAlbum(),
                    albumColorSchemePairFlow = ui.getAlbumColorSchemeFlow(item.imageUrl.orEmpty()),
                    onClick = { open(item) },
                    onLongPress = { onLongPress(item) },
                )
            }
        }
    }
}

@Composable
private fun ArtistsTab(artists: List<Music>, bottom: Dp, open: (Music) -> Unit, onLongPress: (Music) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(`in`.melo.presentation.components.listSpacing()),
    ) {
        items(artists, key = Music::id) { item ->
            ArtistListItem(artist = item.toArtist(), onClick = { open(item) }, onLongPress = { onLongPress(item) })
        }
    }
}

@Composable
private fun LikedTab(
    model: MeloModel,
    ui: PlayerViewModel,
    navController: NavHostController,
    content: HomeLibrary,
    bottom: Dp,
    searchQuery: String,
) {
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    val saved by model.favorites.collectAsStateWithLifecycle()
    val matchingTracks = remember(content.savedTracks, searchQuery) { content.savedTracks.matchingLibrarySearch(searchQuery) }
    val songs = remember(matchingTracks, saved) { matchingTracks.map { it.toSong(it.id in saved) } }
    var selected by remember { mutableStateOf<Song?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, content.savedTracksLoaded, content.savedTrackUris.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                if (searchQuery.isBlank() && last >= songs.size - 12 && content.savedTracksLoaded < content.savedTrackUris.size) model.loadMoreSavedTracks()
            }
    }
    if (searchQuery.isNotBlank() && songs.isEmpty() && content.savedTracksLoaded < content.savedTrackUris.size) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(`in`.melo.presentation.components.listSpacing()),
        ) {
            itemsIndexed(songs, key = { index, song -> "$index-${song.id}" }) { _, song ->
                val current = stable.currentSong?.id == song.id
                EnhancedSongListItem(
                    song = song,
                    isPlaying = current && stable.isPlaying,
                    isCurrentSong = current,
                    onMoreOptionsClick = { selected = it },
                    onLongPress = { selected = song },
                    onClick = { ui.playSongs(songs, song, ui.likedSongs.title) },
                )
            }
        }
    }
    SongOptionsHost(ui, navController, selected, songs, ui.likedSongs.title) { selected = null }
}

@Composable
private fun LocalTab(
    model: MeloModel,
    ui: PlayerViewModel,
    navController: NavHostController,
    tracks: List<Music>,
    title: String,
    bottom: Dp,
    searchQuery: String,
) {
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(model.localLibrary.hasPermission()) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        if (granted) model.refreshLocalTracks()
    }
    val songs = remember(tracks) { tracks.map { it.toSong() } }
    var selected by remember { mutableStateOf<Song?>(null) }
    when {
        !allowed -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.local_permission_title), style = MaterialTheme.typography.titleMedium)
            `in`.melo.presentation.components.MeloButton(
                text = stringResource(R.string.local_permission_action),
                onClick = { request.launch(LocalLibrary.PERMISSION) },
                modifier = Modifier.padding(top = 16.dp),
                size = `in`.melo.presentation.components.MeloButtonSize.Medium,
            )
        }
        songs.isEmpty() -> {
            if (searchQuery.isNotBlank()) LibrarySearchEmptyState(searchQuery, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.local_empty))
            }
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(`in`.melo.presentation.components.listSpacing()),
        ) {
            items(songs, key = { it.id }) { song ->
                val current = stable.currentSong?.id == song.id
                EnhancedSongListItem(
                    song = song,
                    isPlaying = current && stable.isPlaying,
                    isCurrentSong = current,
                    onMoreOptionsClick = { selected = it },
                    onLongPress = { selected = song },
                    onClick = { ui.playSongs(songs, song, title) },
                )
            }
        }
    }
    SongOptionsHost(ui, navController, selected, songs, title) { selected = null }
}

@Composable
private fun DownloadsTab(
    ui: PlayerViewModel,
    navController: NavHostController,
    title: String,
    bottom: Dp,
    entries: List<CollectionProgress>,
    searchQuery: String,
) {
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    val singles = entries.filter { it.collection.item.kind == MusicKind.Track }
    val songs = singles.filter { it.isComplete }.map { it.collection.item.toSong() }
    var selected by remember { mutableStateOf<Song?>(null) }
    var options by remember { mutableStateOf<Music?>(null) }
    if (entries.isEmpty()) {
        if (searchQuery.isNotBlank()) LibrarySearchEmptyState(searchQuery, Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.downloads_empty)) }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(`in`.melo.presentation.components.listSpacing()),
    ) {
        val groups = listOf(
            R.string.downloads_playlists to entries.filter { it.collection.item.kind != MusicKind.Track && it.collection.item.kind != MusicKind.Album },
            R.string.downloads_albums to entries.filter { it.collection.item.kind == MusicKind.Album },
        )
        groups.forEach { (heading, group) ->
            if (group.isNotEmpty()) item(key = "heading:$heading") {
                Text(stringResource(heading), Modifier.padding(horizontal = 8.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
            items(group, key = { it.collection.item.id }) { entry ->
                val item = entry.collection.item
                Column {
                    if (item.kind == MusicKind.Album) AlbumListItem(
                        album = item.toAlbum().copy(songCount = entry.total),
                        albumColorSchemePairFlow = ui.getAlbumColorSchemeFlow(item.imageUrl.orEmpty()),
                        onClick = { navController.openMusic(ui, item) }, onLongPress = { options = item })
                    else PlaylistItem(playlist = item.toPlaylist().copy(description = formatSongCount(entry.total)),
                        onClick = { navController.openMusic(ui, item) }, onLongPress = { options = item })
                    `in`.melo.presentation.components.CollectionDownloadProgress(ui, item)
                }
            }
        }
        if (singles.isNotEmpty()) item(key = "single_heading") {
            Text(stringResource(R.string.downloads_singles), Modifier.padding(horizontal = 8.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
        }
        items(singles, key = { it.collection.item.id }) { entry ->
            val song = entry.collection.item.toSong()
            val current = stable.currentSong?.id == song.id
            Column {
                EnhancedSongListItem(song = song, isPlaying = current && stable.isPlaying, isCurrentSong = current,
                    onMoreOptionsClick = { selected = it }, onLongPress = { selected = song },
                    onClick = { if (entry.isComplete) ui.playSongs(songs, song, title) })
                `in`.melo.presentation.components.CollectionDownloadProgress(ui, entry.collection.item)
            }
        }
    }
    SongOptionsHost(ui, navController, selected, songs, title) { selected = null }
    MusicOptionsHost(ui, navController, options) { options = null }
}

@Composable
private fun LibrarySearchEmptyState(query: String, modifier: Modifier = Modifier) {
    val imeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    Box(
        modifier.padding(24.dp),
        contentAlignment = if (imeVisible) Alignment.TopCenter else Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(top = if (imeVisible) 16.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                MeloIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.library_search_no_results, query),
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                stringResource(R.string.library_search_no_results_body),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
