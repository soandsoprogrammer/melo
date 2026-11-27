package `in`.melo.presentation.screens

import androidx.compose.foundation.layout.offset
import androidx.compose.material3.TopAppBarDefaults
import `in`.melo.ui.theme.separator
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.nestedscroll.nestedScroll
import `in`.melo.presentation.icons.MeloIcons
import `in`.melo.ui.theme.MeloAlpha
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import `in`.melo.MeloModel
import `in`.melo.R
import `in`.melo.data.Music
import `in`.melo.data.MusicKind
import `in`.melo.data.YouTubeExploreCategory
import `in`.melo.data.YouTubeExploreSection
import `in`.melo.presentation.components.HomeGradientTopBar
import `in`.melo.presentation.components.ListeningStatusCard
import `in`.melo.presentation.components.ListeningShelfPlaceholder
import `in`.melo.presentation.components.MiniPlayerHeight
import `in`.melo.presentation.components.MusicOptionsHost
import `in`.melo.presentation.components.SmartImage
import `in`.melo.presentation.navigation.Screen
import `in`.melo.presentation.navigation.navigateSafely
import `in`.melo.presentation.navigation.openMusic
import `in`.melo.presentation.utils.rememberReducedMotion
import `in`.melo.ui.theme.MeloLayout
import `in`.melo.ui.theme.LocalMeloDarkTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeExploreScreen(model: MeloModel, navController: NavHostController, paddingValues: PaddingValues) {
    val state by model.youTubeExplore.collectAsStateWithLifecycle()
    val youtubeAccount by model.youTubeAccount.collectAsStateWithLifecycle()
    val spotifyProfile by model.spotifyAccountProfile.collectAsStateWithLifecycle()
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val hasSong by model.ui.derivedHasSong.collectAsStateWithLifecycle()
    val reducedMotion = rememberReducedMotion()
    var optionsFor by remember { mutableStateOf<Music?>(null) }
    BackHandler(enabled = state.selected != null) { model.closeYouTubeExploreCategory() }
    LaunchedEffect(model) { model.refreshYouTubeExplore() }
    // The title scrolls away with the page rather than staying as a filled bar over
    // it, leaving only a soft fade under the status bar.
    val topBarScroll = `in`.melo.presentation.components.rememberScrolledUnder()
    LaunchedEffect(state.selected?.key) { topBarScroll.reset() }
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
                isScrolled = false,
                transparentContainer = true,
                settingsButtonDirect = true,
                title = stringResource(R.string.explore_title),
            )
            }
            `in`.melo.presentation.components.StatusBarFade { topBarScroll.fraction }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = if (state.selected == null) state.loading else state.loadingCategory,
            onRefresh = {
                val selected = state.selected
                if (selected == null) model.refreshYouTubeExplore(force = true)
                else model.selectYouTubeExploreCategory(selected)
            },
            modifier = Modifier.fillMaxSize().background(`in`.melo.presentation.components.pageBackground()),
        ) {
            AnimatedContent(
                targetState = state.selected?.key,
                transitionSpec = {
                    if (reducedMotion) {
                        fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                    } else {
                        val forward = initialState == null && targetState != null
                        (fadeIn(tween(190)) + slideInHorizontally(tween(240)) { width -> if (forward) width / 8 else -width / 8 }) togetherWith
                            (fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { width -> if (forward) -width / 8 else width / 8 })
                    }
                },
                label = "explorePageTransition",
            ) { categoryKey ->
                val category = categoryKey?.let { key ->
                    state.selected?.takeIf { it.key == key }
                        ?: state.sections.asSequence().flatMap { it.categories.asSequence() }.firstOrNull { it.key == key }
                }
                if (category == null) {
                    ExploreCategoryGrid(
                        sections = state.sections,
                        loading = state.loading,
                        error = state.error,
                        onRetry = { model.refreshYouTubeExplore(force = true) },
                        onCategoryClick = model::selectYouTubeExploreCategory,
                        onCategoryVisible = model::loadYouTubeExploreArtwork,
                        bottomPadding = paddingValues.calculateBottomPadding() + if (hasSong) MiniPlayerHeight else 0.dp,
                        topPadding = innerPadding.calculateTopPadding(),
                    )
                } else {
                    val current = state.selected?.key == category.key
                    ExploreCategoryPage(
                        category = category,
                        shelves = if (current) state.shelves else emptyList(),
                        loading = if (current) state.loadingCategory else true,
                        error = if (current) state.categoryError else null,
                        model = model,
                        navController = navController,
                        onBack = model::closeYouTubeExploreCategory,
                        onRetry = { model.selectYouTubeExploreCategory(category) },
                        onLongPress = { optionsFor = it },
                        bottomPadding = paddingValues.calculateBottomPadding() + if (hasSong) MiniPlayerHeight else 0.dp,
                        topPadding = innerPadding.calculateTopPadding(),
                    )
                }
            }
        }
    }
    MusicOptionsHost(model.ui, navController, optionsFor) { optionsFor = null }
}

@Composable
private fun ExploreCategoryGrid(
    sections: List<YouTubeExploreSection>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onCategoryClick: (YouTubeExploreCategory) -> Unit,
    onCategoryVisible: (YouTubeExploreCategory) -> Unit,
    bottomPadding: Dp,
    topPadding: Dp,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = ((maxWidth - MeloLayout.PageGutter * 2 + 12.dp) / (220.dp + 12.dp))
            .toInt().coerceIn(2, 6)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = topPadding + 8.dp,
                bottom = bottomPadding + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(MeloLayout.Space3),
        ) {
            item(key = "explore_title") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = MeloLayout.PageGutter).padding(bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.explore_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                loading && sections.isEmpty() -> item(key = "explore_loading") { ListeningShelfPlaceholder() }
                error != null && sections.isEmpty() -> item(key = "explore_error") {
                    ListeningStatusCard(
                        title = stringResource(R.string.explore_unavailable), message = error,
                        action = stringResource(R.string.melo_retry), onAction = onRetry,
                        modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
                    )
                }
                sections.isEmpty() -> item(key = "explore_empty") {
                    ListeningStatusCard(
                        title = stringResource(R.string.explore_empty_title),
                        message = stringResource(R.string.explore_empty_body),
                        action = stringResource(R.string.melo_retry), onAction = onRetry,
                        modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
                    )
                }
                else -> sections.forEachIndexed { sectionIndex, section ->
                    if (section.title.isNotBlank()) item(key = "explore_section_title_$sectionIndex") {
                        Text(
                            section.title,
                            modifier = Modifier.padding(horizontal = MeloLayout.PageGutter).padding(top = MeloLayout.Space2),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    val rows = section.categories.distinctBy(YouTubeExploreCategory::key).chunked(columns)
                    items(rows, key = { row -> "explore_${sectionIndex}_${row.first().key}" }) { row ->
                        Row(
                            Modifier.padding(horizontal = MeloLayout.PageGutter),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            row.forEach { category ->
                                ExploreCategoryCard(
                                    category = category,
                                    onClick = { onCategoryClick(category) },
                                    onVisible = { onCategoryVisible(category) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExploreCategoryCard(
    category: YouTubeExploreCategory,
    onClick: () -> Unit,
    onVisible: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(category.key, category.imageUrl) { if (category.imageUrl == null) onVisible() }
    val foreground = if (LocalMeloDarkTheme.current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary
    val base = remember(category.stripeColor, category.title) {
        category.stripeColor?.let { Color(it) } ?: exploreFallbackColor(category.title)
    }
    Box(
        modifier = modifier
            .aspectRatio(1.72f)
            .clip(MeloLayout.PanelShape)
            .background(base)
            .combinedClickable(onClick = onClick, role = androidx.compose.ui.semantics.Role.Button)
            .semantics(mergeDescendants = true) {},
    ) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(base, base.copy(alpha = 0.58f), Color.Black.copy(alpha = 0.45f)))))
        category.imageUrl?.let { art ->
            SmartImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().fillMaxWidth(0.72f).align(Alignment.CenterEnd),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .28f), .65f to Color.Transparent)))
        Text(
            category.title,
            modifier = Modifier.align(Alignment.TopStart).padding(horizontal = MeloLayout.Space4, vertical = MeloLayout.Space3),
            style = MaterialTheme.typography.titleMedium.copy(
                shadow = Shadow(Color.Black.copy(alpha = MeloAlpha.Disabled), Offset(0f, 1f), 5f),
            ),
            color = foreground,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ExploreCategoryPage(
    category: YouTubeExploreCategory,
    shelves: List<`in`.melo.data.YouTubeHomeShelf>,
    loading: Boolean,
    error: String?,
    model: MeloModel,
    navController: NavHostController,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLongPress: (Music) -> Unit,
    bottomPadding: Dp,
    topPadding: Dp,
) {
    val ui = model.ui
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding + 8.dp, bottom = bottomPadding + 28.dp),
        verticalArrangement = Arrangement.spacedBy(MeloLayout.SectionGap),
    ) {
        item(key = "explore_category_header") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(MeloIcons.ArrowBack, stringResource(R.string.explore_back))
                }
                Text(
                    category.title,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(MeloIcons.Explore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (loading && shelves.isEmpty()) item(key = "explore_category_loading") { ListeningShelfPlaceholder() }
        if (error != null) item(key = "explore_category_error") {
            ListeningStatusCard(
                title = category.title, message = error, action = stringResource(R.string.melo_retry), onAction = onRetry,
                modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
            )
        }
        if (!loading && error == null && shelves.isEmpty()) item(key = "explore_category_empty") {
            ListeningStatusCard(
                title = category.title, message = stringResource(R.string.explore_category_empty),
                action = stringResource(R.string.melo_retry), onAction = onRetry,
                modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
            )
        }
        shelves.forEachIndexed { index, shelf ->
            item(key = "explore_shelf_${shelf.title}_$index") {
                Column(verticalArrangement = Arrangement.spacedBy(MeloLayout.Space3)) {
                    Text(
                        shelf.title,
                        modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = MeloLayout.PageGutter),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(shelf.items, key = Music::id) { item ->
                            ExplorePlaylistTile(
                                item = item,
                                onClick = {
                                    if (item.kind == MusicKind.Track) model.loadRadio(item) { selected -> navController.openMusic(ui, selected) }
                                    else navController.openMusic(ui, item)
                                },
                                onLongClick = { onLongPress(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExplorePlaylistTile(item: Music, onClick: () -> Unit, onLongClick: () -> Unit) {
    `in`.melo.presentation.components.MeloShelfCard(
        title = item.title,
        subtitle = item.subtitle.ifBlank { stringResource(item.kind.labelRes) },
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.width(MeloLayout.ShelfCardWidth).padding(bottom = 4.dp),
        titleLines = 2,
    ) {
        SmartImage(
            model = item.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun exploreFallbackColor(title: String): Color = when ((title.hashCode() and Int.MAX_VALUE) % 8) {
    0 -> Color(0xFFCC6A55)
    1 -> Color(0xFFC07A92)
    2 -> Color(0xFF9C8AC0)
    3 -> Color(0xFF8090C8)
    4 -> Color(0xFFD0A060)
    5 -> Color(0xFF6A88B0)
    6 -> Color(0xFF7AAED0)
    else -> Color(0xFF86B890)
}
