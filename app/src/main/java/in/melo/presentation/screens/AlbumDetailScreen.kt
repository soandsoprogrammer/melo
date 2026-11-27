package `in`.melo.presentation.screens

import `in`.melo.ui.glass.backdrop.backdrops.LayerBackdrop
import androidx.compose.foundation.layout.size
import `in`.melo.ui.glass.pressShade
import `in`.melo.ui.glass.pressSink
import `in`.melo.ui.glass.rememberPressDent
import `in`.melo.presentation.icons.MeloIcons
import `in`.melo.ui.theme.MeloAlpha
import `in`.melo.data.source
import `in`.melo.presentation.utils.shareMusic
import `in`.melo.presentation.utils.spotifyShareUrl
import androidx.compose.foundation.layout.FlowRow
import `in`.melo.ui.theme.MeloStatusBarStyle
import `in`.melo.utils.formatSongCount
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImagePainter
import coil.size.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.FilledTonalIconButton
import androidx.navigation.NavHostController
import `in`.melo.MeloModel
import `in`.melo.data.Music
import `in`.melo.data.model.toSong
import `in`.melo.presentation.components.SmartImage
import `in`.melo.presentation.components.MeloIconButton
import `in`.melo.presentation.components.SongOptionsHost
import `in`.melo.presentation.components.subcomps.EnhancedSongListItem
import `in`.melo.presentation.navigation.openMusic
import `in`.melo.ui.theme.LocalMeloDarkTheme
import `in`.melo.utils.shapes.RoundedStarShape
import androidx.compose.foundation.lazy.itemsIndexed
import `in`.melo.R
import `in`.melo.data.model.Song
import `in`.melo.data.model.Album
import `in`.melo.presentation.viewmodel.PlayerViewModel


@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlbumDetailScreen(
    model: MeloModel,
    navController: NavHostController,
    albumId: Long,
) {
    val ui = model.ui
    val playerViewModel = ui
    val item = remember(albumId) { ui.entity(albumId) }
    if (item == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val details by model.details.collectAsStateWithLifecycle()
    val pins by model.pins.collectAsStateWithLifecycle()
    val saved by model.favorites.collectAsStateWithLifecycle()
    val stablePlayerState by ui.stablePlayerState.collectAsStateWithLifecycle()
    LaunchedEffect(item.id) { model.loadDetail(item) }
    val detail = details[item.id]
    val current = detail?.content?.item ?: item
    var selectedSong by remember { mutableStateOf<Song?>(null) }
    val songs = remember(detail?.content?.tracks, saved) {
        detail?.content?.tracks.orEmpty().map { it.toSong(it.id in saved) }
    }
    CollectionDetailLayout(current, detail?.content?.total ?: songs.size, ui, navController, rememberLazyListState()) {
        item(key = "album_actions") {
            // Play loads the tracks itself, so it waits only on an album that came back empty.
            DetailPlayRow(onPlay = { ui.playItem(current) }, onShuffle = { ui.shuffleItem(current) },
                enabled = detail?.content == null || songs.isNotEmpty())
            // Server albums cannot be saved to Spotify or seed a Spotify radio.
            val spotifyAlbum = current.source == `in`.melo.data.MusicSource.Spotify
            DetailActionsRow(current, spotifyAlbum, current.id in saved, pins.any { it.id == current.id },
                { model.toggleFavorite(current) }, { ui.togglePin(current) },
                { ui.startRadio(current) { navController.openMusic(ui, it) } }, ui, showRadio = spotifyAlbum)
            `in`.melo.presentation.components.CollectionDownloadProgress(ui, current)
        }
        if (detail?.error != null) item { Text(detail.error, color = MaterialTheme.colorScheme.error) }
        if (songs.isEmpty() && detail?.loading != false) item {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
        }
        itemsIndexed(songs, key = { index, song -> "album:$index:${song.id}" }) { _, song ->
            val isCurrent = stablePlayerState.currentSong?.id == song.id
            EnhancedSongListItem(song = song, isCurrentSong = isCurrent,
                isPlaying = isCurrent && stablePlayerState.isPlaying, showAlbumArt = false,
                onMoreOptionsClick = { selectedSong = it }, onLongPress = { selectedSong = song },
                onClick = { ui.playSongs(songs, song, current.title) })
        }
    }
    SongOptionsHost(ui, navController, selectedSong, songs, current.title) { selectedSong = null }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollapsingAlbumTopBar(
    album: Album,
    songsCount: Int,
    collapseFraction: Float,
    headerHeight: Dp,
    headerImageRequestSize: Size,
    onHeaderArtworkState: ((AsyncImagePainter.State) -> Unit)? = null,
    /** How bright the cover's top band is, for the back button's tone; null until read. */
    artTopLuminance: Float? = null,
    /** The list under the bar, frosted once it folds; null draws it solid. */
    listBackdrop: LayerBackdrop? = null,
    onBackPressed: () -> Unit,
) {
    val surfaceColor = MaterialTheme.colorScheme.background
    // The bar's tone: over the open header, the cover's top band decides; folded, the page does.
    val pageDark = LocalMeloDarkTheme.current
    val chromeDark = if (collapseFraction < 0.5f && artTopLuminance != null) artTopLuminance < 0.4f else pageDark
    val statusBarColor = if (chromeDark) Color.Black.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.30f)

    // Animation Values
    val backgroundAlpha = collapseFraction
    val headerContentAlpha = 1f - (collapseFraction * 2).coerceAtMost(1f)
    val showExpandedArtwork = headerContentAlpha > 0.01f
    val headerOverlayBrush = remember(surfaceColor, headerContentAlpha) {
        Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                surfaceColor.copy(alpha = 0.30f * headerContentAlpha),
                surfaceColor.copy(alpha = 0.90f * headerContentAlpha),
                surfaceColor.copy(alpha = headerContentAlpha)
            )
        )
    }
    val statusBarBrush = remember(statusBarColor) {
        Brush.verticalGradient(
            colors = listOf(
                statusBarColor,
                Color.Transparent
            )
        )
    }
    val solidAlpha = (collapseFraction * 2f).coerceIn(0f, 1f)
    val expandedStatusBarFallback = remember(statusBarColor, surfaceColor) {
        statusBarColor.compositeOver(surfaceColor)
    }
    val fallbackStatusBarColor = remember(expandedStatusBarFallback, surfaceColor, solidAlpha) {
        lerpColor(expandedStatusBarFallback, surfaceColor, solidAlpha)
    }

    // Title animation
    val titleScale = lerp(1f, 0.75f, collapseFraction)
    val titlePaddingStart = lerp(16.dp, 58.dp, collapseFraction)
    val titleMaxLines = if (collapseFraction < 0.5f) 2 else 1
    val titleVerticalBias = lerp(1f, -1f, collapseFraction)
    val animatedTitleAlignment =
        BiasAlignment(horizontalBias = -1f, verticalBias = titleVerticalBias)
    val titleContainerHeight = lerp(88.dp, 56.dp, collapseFraction)
    val yOffsetCorrection = lerp((titleContainerHeight / 2) - 64.dp, 0.dp, collapseFraction)
    val metaLine = listOfNotNull(
        album.artist.takeIf(String::isNotBlank),
        formatSongCount(songsCount),
    ).joinToString(" · ")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .clipToBounds()
    ) {
        MeloStatusBarStyle(color = fallbackStatusBarColor, useDarkIcons = !chromeDark)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
        ) {
            // Its own layer: the frost fades in with the fold without fading the header with it.
            Box(Modifier.matchParentSize().foldedBar(listBackdrop, surfaceColor, backgroundAlpha))
            if (showExpandedArtwork) {
                SmartImage(
                    model = album.albumArtUriString,
                    contentDescription = stringResource(R.string.album_cover_for, album.title),
                    contentScale = ContentScale.Crop,
                    targetSize = headerImageRequestSize,
                    allowHardware = true,
                    crossfadeDurationMillis = 0,
                    alpha = headerContentAlpha,
                    onState = onHeaderArtworkState,
                    modifier = Modifier.fillMaxSize()
                )
                if (listBackdrop != null) CoverEdgeBlur(album.albumArtUriString, headerImageRequestSize, headerContentAlpha)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(headerOverlayBrush)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(statusBarBrush)
                    .align(Alignment.TopCenter)
            )

            // Top bar content (buttons, title, etc.)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                // Floats over the cover in glass; its glyph turns white or black with what is
                // behind it, the cover while the header is open and the page once it has folded.
                ChromeTheme(dark = chromeDark) {
                    GlassCircleButton(
                        icon = MeloIcons.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        onClick = onBackPressed,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 6.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .align(animatedTitleAlignment)
                        .height(titleContainerHeight)
                        .fillMaxWidth()
                        .offset(y = yOffsetCorrection)
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = titlePaddingStart, end = 120.dp)
                            .graphicsLayer {
                                scaleX = titleScale
                                scaleY = titleScale
                            },
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = album.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = titleMaxLines,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = metaLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

            }
        }
    }
}

// A plain round play button, as Apple Music uses, in place of the old star.
internal val DetailFabShape = androidx.compose.foundation.shape.CircleShape

/** Buttons of an album or artist page, laid out as chosen in Settings. */
@Composable
internal fun DetailActionsRow(
    item: Music,
    canSave: Boolean,
    isSaved: Boolean,
    isPinned: Boolean,
    onSave: () -> Unit,
    onPin: () -> Unit,
    onRadio: () -> Unit,
    ui: PlayerViewModel,
    showRadio: Boolean = true,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val actions = buildList {
        add(downloadAction(ui, item))
        if (canSave) add(ActionSpec(`in`.melo.data.preferences.DetailAction.SAVE,
            if (isSaved) MeloIcons.Favorite else MeloIcons.FavoriteBorder,
            stringResource(if (isSaved) R.string.saved_remove else R.string.saved_add), onSave, selected = isSaved))
        add(ActionSpec(`in`.melo.data.preferences.DetailAction.PIN,
            if (isPinned) MeloIcons.PushPinFill else MeloIcons.PushPin,
            stringResource(if (isPinned) R.string.pin_remove else R.string.pin_add_home), onPin, selected = isPinned))
        if (showRadio) add(ActionSpec(`in`.melo.data.preferences.DetailAction.RADIO, MeloIcons.Radio, stringResource(R.string.radio_related), onRadio))
        if (item.spotifyShareUrl() != null) add(ActionSpec(`in`.melo.data.preferences.DetailAction.SHARE, MeloIcons.Share,
            stringResource(R.string.share_item, item.title), { context.shareMusic(item) }))
    }
    DetailActionBar(`in`.melo.data.preferences.DetailPage.ALBUM, actions, ui)
}

