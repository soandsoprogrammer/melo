package `in`.melo.presentation.screens

import androidx.compose.foundation.layout.offset
import `in`.melo.ui.theme.separator
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.nestedscroll.nestedScroll
import `in`.melo.presentation.icons.MeloIcons
import `in`.melo.ui.theme.artworkOutline
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import `in`.melo.MeloModel
import `in`.melo.R
import `in`.melo.data.Music
import `in`.melo.data.youtube.YTM_PODCAST_PREFIX
import `in`.melo.data.youtube.YouTubePodcastShelf
import `in`.melo.data.youtube.YouTubePodcastShow
import `in`.melo.data.youtube.YouTubePodcasts
import `in`.melo.presentation.components.HomeGradientTopBar
import `in`.melo.presentation.components.ListeningStatusCard
import `in`.melo.presentation.components.ListeningShelfPlaceholder
import `in`.melo.presentation.components.MiniPlayerHeight
import `in`.melo.presentation.components.MusicOptionsHost
import `in`.melo.presentation.components.SmartImage
import `in`.melo.presentation.components.artworkRim
import `in`.melo.presentation.components.homePress
import `in`.melo.presentation.navigation.Screen
import `in`.melo.presentation.navigation.navigateSafely
import `in`.melo.presentation.viewmodel.PlayerViewModel
import `in`.melo.ui.theme.MeloLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastsScreen(model: MeloModel, navController: NavHostController, paddingValues: PaddingValues) {
    val ui = model.ui
    val settings by ui.settings.collectAsStateWithLifecycle()
    val youtubeAccount by model.youTubeAccount.collectAsStateWithLifecycle()
    val spotifyProfile by model.spotifyAccountProfile.collectAsStateWithLifecycle()
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val podcastState by model.podcastFeed.collectAsStateWithLifecycle()
    val hasSong by ui.stablePlayerStateHasSongForPodcasts()
    var query by rememberSaveable { mutableStateOf("") }
    var searchShelves by remember { mutableStateOf<List<YouTubePodcastShelf>>(emptyList()) }
    val searchFailureText = stringResource(R.string.podcasts_search_error_title)
    var searchError by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchRetry by rememberSaveable { mutableIntStateOf(0) }
    var optionsFor by remember { mutableStateOf<Music?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val feed = podcastState.feed
    val feedError = podcastState.error
    val feedLoading = podcastState.loading
    val refreshing = podcastState.refreshing
    val loadingMore = podcastState.loadingMore

    LaunchedEffect(youtubeAccount) { model.loadPodcastFeed() }
    LaunchedEffect(query, searchRetry) {
        val term = query.trim()
        if (term.length < 2) {
            searchShelves = emptyList()
            searchError = null
            searching = false
            return@LaunchedEffect
        }
        searchShelves = emptyList()
        searchError = null
        searching = true
        try {
            delay(350)
            searchShelves = withContext(Dispatchers.IO) { YouTubePodcasts.search(term) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            searchShelves = emptyList()
            searchError = searchFailureText
        } finally {
            if (currentCoroutineContext().isActive) searching = false
        }
    }

    val showingSearch = query.trim().length >= 2
    val visibleShelves = if (showingSearch) searchShelves else feed?.shelves.orEmpty()
    val bottomPadding = paddingValues.calculateBottomPadding() + 38.dp + if (hasSong) MiniPlayerHeight else 0.dp

    // The title scrolls away with the page rather than staying as a filled bar over
    // it, leaving only a soft fade under the status bar.
    val topBarScroll = `in`.melo.presentation.components.rememberScrolledUnder()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(topBarScroll.connection),
        containerColor = `in`.melo.presentation.components.pageBackground(),
        topBar = {
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(0, topBarScroll.offsetPx.toInt()) }) {
            HomeGradientTopBar(
                onSettingsClick = { navController.navigateSafely(Screen.Settings.route) },
                onHistoryClick = { navController.navigateSafely(Screen.History.route) },
                profileImageUrl = youtubeAccount?.photoUrl,
                spotifyConnected = discovery.spotifyEnabled,
                spotifyProfile = spotifyProfile,
                onProfileClick = { navController.navigateSafely(Screen.SettingsCategory.createRoute("account")) },
                transparentContainer = true,
                // Podcasts has no options of its own, so the button is Settings itself, not a menu holding only it.
                settingsButtonDirect = true,
                title = stringResource(R.string.podcasts_title),
            )
            }
            `in`.melo.presentation.components.StatusBarFade { topBarScroll.fraction }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { model.refreshPodcastFeed() },
            modifier = Modifier.fillMaxSize().background(`in`.melo.presentation.components.pageBackground()),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 12.dp,
                    bottom = bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(MeloLayout.SectionGap),
            ) {
                item(key = "podcasts_header") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = MeloLayout.PageGutter),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = CircleShape,
                            leadingIcon = { Icon(MeloIcons.Search, contentDescription = null) },
                            trailingIcon = if (query.isNotEmpty()) {
                                { IconButton(onClick = { query = "" }) { Icon(MeloIcons.Close, stringResource(R.string.action_cancel)) } }
                            } else null,
                            placeholder = { Text(stringResource(R.string.podcasts_search_hint)) },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                            ),
                        )
                    }
                }
                if (showingSearch && searching) {
                    item(key = "podcast_search_loading") {
                        ListeningShelfPlaceholder(label = stringResource(R.string.podcasts_searching))
                    }
                } else if (showingSearch && searchError != null) {
                    item(key = "podcast_search_error") {
                        ListeningStatusCard(
                            title = stringResource(R.string.podcasts_search_error_title),
                            message = searchError.orEmpty(),
                            action = stringResource(R.string.podcasts_retry),
                            onAction = { searchRetry += 1 },
                            modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
                        )
                    }
                } else if (showingSearch && searchShelves.isEmpty() && !searching) {
                    item(key = "podcast_search_empty") {
                        ListeningStatusCard(
                            title = stringResource(R.string.podcasts_no_results_title),
                            message = stringResource(R.string.podcasts_no_results_body),
                            action = stringResource(R.string.action_cancel),
                            onAction = { query = "" },
                            modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
                        )
                    }
                } else if (!showingSearch && feedLoading && feed == null) {
                    item(key = "podcasts_loading") {
                        ListeningShelfPlaceholder(label = stringResource(R.string.podcasts_loading))
                    }
                } else if (!showingSearch && feedError != null && feed == null) {
                    item(key = "podcasts_error") {
                        ListeningStatusCard(
                            title = stringResource(R.string.podcasts_load_error_title),
                            message = feedError.orEmpty(),
                            action = stringResource(R.string.podcasts_retry),
                            onAction = { model.refreshPodcastFeed() },
                            modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
                        )
                    }
                } else if (!showingSearch && visibleShelves.isEmpty()) {
                    item(key = "podcasts_empty") {
                        ListeningStatusCard(
                            title = stringResource(R.string.podcasts_empty_title),
                            message = stringResource(R.string.podcasts_empty_body),
                            action = stringResource(R.string.podcasts_retry),
                            onAction = { model.refreshPodcastFeed() },
                            modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
                        )
                    }
                }
                itemsIndexed(
                    items = visibleShelves,
                    key = { index, shelf -> "podcast_shelf:${shelf.title}:${shelf.episodes}:$index" },
                ) { _, shelf ->
                    PodcastShelfSection(
                        shelf = shelf,
                        onOpenShow = { item ->
                            navController.navigateSafely(
                                Screen.PodcastDetail.createRoute(item.id.removePrefix(YTM_PODCAST_PREFIX)),
                            )
                        },
                        onPlayEpisode = ui::playItem,
                        onLongPress = { optionsFor = it },
                    )
                }
                if (!showingSearch && feed?.continuation != null) {
                    item(key = "podcasts_load_more") {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = MeloLayout.PageGutter),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            `in`.melo.presentation.components.MeloButton(
                                text = stringResource(if (loadingMore) R.string.podcasts_loading_more else R.string.podcasts_load_more),
                                onClick = model::loadMorePodcastFeed,
                                enabled = !loadingMore,
                                size = `in`.melo.presentation.components.MeloButtonSize.Medium,
                                style = `in`.melo.presentation.components.MeloButtonStyle.Tonal,
                            )
                        }
                    }
                }
                if (!showingSearch && feedError != null && feed != null) {
                    item(key = "podcasts_more_error") {
                        Text(
                            text = feedError.orEmpty(),
                            modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
    MusicOptionsHost(ui, navController, optionsFor) { optionsFor = null }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PodcastShelfSection(
    shelf: YouTubePodcastShelf,
    onOpenShow: (Music) -> Unit,
    onPlayEpisode: (Music) -> Unit,
    onLongPress: (Music) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MeloLayout.Space3)) {
        `in`.melo.presentation.components.MeloSectionHeader(shelf.title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = MeloLayout.PageGutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(shelf.items, key = { index, item -> "${item.id}:$index" }) { _, item ->
                PodcastCard(
                    item = item,
                    episode = shelf.episodes,
                    onClick = { if (shelf.episodes) onPlayEpisode(item) else onOpenShow(item) },
                    onLongPress = { onLongPress(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PodcastCard(
    item: Music,
    episode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val titleBlockHeight = with(LocalDensity.current) { 44.sp.toDp() }
    val subtitleBlockHeight = with(LocalDensity.current) { 40.sp.toDp() }
    Card(
        modifier = Modifier
            .width(168.dp)
            .homePress(interactions)
            .artworkOutline(MeloLayout.CardShape)
            .combinedClickable(role = Role.Button, interactionSource = interactions, indication = null, onClick = onClick, onLongClick = onLongPress)
            .semantics {
                contentDescription = listOf(item.title, item.subtitle, item.note)
                    .filter(String::isNotBlank)
                    .joinToString(", ")
                role = Role.Button
            },
        shape = MeloLayout.CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                SmartImage(
                    model = item.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(if (episode) 16f / 9f else 1f),
                )
                if (episode) {
                    FilledIconButton(
                        onClick = onClick,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(40.dp),
                    ) {
                        Icon(MeloIcons.PlayArrow, contentDescription = stringResource(R.string.podcasts_play_episode))
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(start = MeloLayout.Space3, end = MeloLayout.Space3, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(MeloLayout.Space1),
            ) {
                Text(item.title, modifier = Modifier.height(titleBlockHeight), style = MaterialTheme.typography.titleSmall.copy(lineHeight = 22.sp),
                    fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val subtitle = if (episode && item.note.isNotBlank()) "${item.subtitle} · ${item.note}" else item.subtitle
                Text(
                    subtitle,
                    modifier = Modifier.height(subtitleBlockHeight),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PodcastDetailScreen(model: MeloModel, navController: NavHostController, browseId: String) {
    val ui = model.ui
    var podcast by remember(browseId) { mutableStateOf<YouTubePodcastShow?>(null) }
    var episodeQuery by rememberSaveable(browseId) { mutableStateOf("") }
    val episodeFailureText = stringResource(R.string.podcasts_episode_load_failed)
    var error by remember(browseId) { mutableStateOf<String?>(null) }
    var loading by remember(browseId) { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var retry by rememberSaveable(browseId) { mutableIntStateOf(0) }
    var optionsFor by remember { mutableStateOf<Music?>(null) }

    LaunchedEffect(browseId, retry) {
        if (podcast == null) loading = true else refreshing = true
        error = null
        try {
            podcast = withContext(Dispatchers.IO) { YouTubePodcasts.show(browseId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = episodeFailureText
        } finally {
            if (currentCoroutineContext().isActive) {
                loading = false
                refreshing = false
            }
        }
    }
    val visibleEpisodes = remember(podcast?.episodes, podcast?.item?.title, podcast?.item?.imageUrl, episodeQuery) {
        val term = episodeQuery.trim().lowercase(Locale.ROOT)
        val show = podcast?.item
        podcast?.episodes.orEmpty().map { episode ->
            val episodeArtist = episode.subtitle.takeUnless {
                it.isBlank() || it.equals("Unknown Artist", ignoreCase = true) ||
                    it.equals("Episode", ignoreCase = true) || it.equals("Podcast", ignoreCase = true)
            } ?: show?.title.orEmpty()
            episode.copy(
                subtitle = episodeArtist,
                album = episode.album.ifBlank { show?.title.orEmpty() },
            )
        }.filter { episode ->
            term.isEmpty() || episode.title.lowercase(Locale.ROOT).contains(term)
        }
    }
    `in`.melo.presentation.components.MeloPageScaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        podcast?.item?.title ?: stringResource(R.string.podcasts_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(MeloIcons.ArrowBack, stringResource(R.string.podcasts_back))
                    }
                },
                colors = `in`.melo.presentation.components.meloPageBarColors(),
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { retry += 1 },
            modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = MeloLayout.PageGutter,
                    end = MeloLayout.PageGutter,
                    top = 12.dp,
                    bottom = 38.dp + MiniPlayerHeight,
                ),
                verticalArrangement = Arrangement.spacedBy(MeloLayout.Space4),
            ) {
                podcast?.let { show ->
                    item(key = "podcast_info") {
                        Row(horizontalArrangement = Arrangement.spacedBy(MeloLayout.Space4), verticalAlignment = Alignment.CenterVertically) {
                            SmartImage(
                                model = show.item.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                shape = MeloLayout.ArtworkShape,
                                modifier = Modifier.size(108.dp),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MeloLayout.Space2)) {
                                Text(show.item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(show.item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    item(key = "podcast_episodes_title") {
                        Text(stringResource(R.string.podcasts_episodes), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    item(key = "podcast_episode_search") {
                        OutlinedTextField(
                            value = episodeQuery,
                            onValueChange = { episodeQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = CircleShape,
                            leadingIcon = { Icon(MeloIcons.Search, contentDescription = null) },
                            trailingIcon = if (episodeQuery.isNotEmpty()) {
                                { IconButton(onClick = { episodeQuery = "" }) { Icon(MeloIcons.Close, stringResource(R.string.action_cancel)) } }
                            } else null,
                            placeholder = { Text(stringResource(R.string.podcasts_search_episode_hint)) },
                        )
                    }
                    itemsIndexed(visibleEpisodes, key = { index, item -> "${item.id}:$index" }) { _, episode ->
                        PodcastEpisodeRow(
                            episode = episode,
                            onClick = { ui.playItem(episode) },
                            onLongPress = { optionsFor = episode },
                        )
                    }
                    if (show.episodes.isEmpty() && !loading) {
                        item(key = "podcast_no_episodes") {
                            Text(stringResource(R.string.podcasts_no_episodes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (show.episodes.isNotEmpty() && visibleEpisodes.isEmpty()) {
                        item(key = "podcast_no_episode_matches") {
                            Text(stringResource(R.string.podcasts_no_episode_matches), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (loading && podcast == null) item(key = "podcast_loading") {
                    ListeningShelfPlaceholder(label = stringResource(R.string.podcasts_loading_show))
                }
                if (error != null && podcast == null) item(key = "podcast_error") {
                    ListeningStatusCard(
                        title = stringResource(R.string.podcasts_load_error_title),
                        message = error.orEmpty(),
                        action = stringResource(R.string.podcasts_retry),
                        onAction = { retry += 1 },
                    )
                }
            }
        }
    }
    MusicOptionsHost(ui, navController, optionsFor) { optionsFor = null }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PodcastEpisodeRow(episode: Music, onClick: () -> Unit, onLongPress: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongPress)
            .semantics { contentDescription = "${episode.title}, ${episode.subtitle}"; role = Role.Button },
        shape = MeloLayout.CardShape,
        color = `in`.melo.presentation.components.pageCardColor(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            Modifier.padding(MeloLayout.Space3),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmartImage(
                model = episode.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                shape = MeloLayout.ArtworkShape,
                modifier = Modifier.width(112.dp).aspectRatio(16f / 9f),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(episode.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(episode.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (episode.note.isNotBlank()) Text(episode.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledIconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
                Icon(MeloIcons.PlayArrow, contentDescription = stringResource(R.string.podcasts_play_episode))
            }
        }
    }
}

@Composable
private fun PlayerViewModel.stablePlayerStateHasSongForPodcasts() = remember(this) {
    derivedHasSong
}.collectAsStateWithLifecycle()
