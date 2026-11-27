package `in`.melo.presentation.components.player

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import `in`.melo.presentation.components.SafeOriginalAlbumArtSize
import `in`.melo.presentation.components.CurrentLyricStrip
import `in`.melo.data.preferences.AppSettings
import `in`.melo.presentation.icons.MeloIcons
import `in`.melo.presentation.icons.meloPainter
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import `in`.melo.presentation.components.artworkBottomFade
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
// import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults // Removed
// import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState // Removed
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.stringResource
import androidx.media3.common.util.UnstableApi
import `in`.melo.R
import `in`.melo.presentation.components.CastBottomSheet
import `in`.melo.utils.LyricsUtils
import `in`.melo.utils.LyricsImportValidationResult
import `in`.melo.utils.LyricsImportSecurity
import `in`.melo.utils.LyricsImportFailureReason
import `in`.melo.presentation.viewmodel.LyricsSearchUiState
import `in`.melo.presentation.components.subcomps.FetchLyricsDialog
import `in`.melo.presentation.components.LyricsSheet
import `in`.melo.data.model.Song
import `in`.melo.data.preferences.AlbumArtQuality
import `in`.melo.data.preferences.CarouselStyle
import `in`.melo.data.preferences.FullPlayerLoadingTweaks
import `in`.melo.presentation.components.AlbumCarouselSection
import `in`.melo.presentation.components.LocalMaterialTheme
import `in`.melo.presentation.viewmodel.PlayerSheetState
import `in`.melo.presentation.viewmodel.PlayerViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

internal const val PREVIOUS_TRACK_RESTART_THRESHOLD_MS = 10_000L
internal const val SKIP_COMMAND_GUARD_MS = 96L

internal enum class SkipDirection { PREVIOUS, NEXT }

internal fun validateLyricsImport(context: Context, uri: Uri): LyricsImportValidationResult {
    val resolver = context.contentResolver
    var fileName = ""
    var fileSize: Long? = null
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        val name = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        val size = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            if (name != -1) fileName = cursor.getString(name)
            if (size != -1 && !cursor.isNull(size)) fileSize = cursor.getLong(size)
        }
    }
    return resolver.openInputStream(uri)?.use { input ->
        LyricsImportSecurity.validateImportedLyricsFile(fileName, resolver.getType(uri), input, fileSize)
    } ?: LyricsImportValidationResult.Invalid(LyricsImportFailureReason.EMPTY_CONTENT)
}

@androidx.annotation.OptIn(UnstableApi::class)
@SuppressLint("StateFlowValueCalledInComposition")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FullPlayerContent(
    currentSong: Song?,
    currentPlaybackQueue: ImmutableList<Song>,
    currentQueueSourceName: String,
    currentMediaItemIndex: Int = -1,
    isShuffleEnabled: Boolean,
    shuffleTransitionInProgress: Boolean,
    repeatMode: Int,
    allowRealtimeUpdates: Boolean = true,
    expansionFractionProvider: () -> Float,
    currentSheetState: PlayerSheetState,
    carouselStyle: String,
    loadingTweaks: FullPlayerLoadingTweaks,
    isSheetDragGestureActive: Boolean = false,
    playerViewModel: PlayerViewModel, // For stable state like totalDuration and lyrics
    // State Providers
    currentPositionProvider: () -> Long,
    isPlayingProvider: () -> Boolean,
    isBufferingProvider: () -> Boolean,
    playWhenReadyProvider: () -> Boolean,
    isFavoriteProvider: () -> Boolean,
    repeatModeProvider: () -> Int,
    isShuffleEnabledProvider: () -> Boolean,
    totalDurationProvider: () -> Long,
    // State
    isCastConnecting: Boolean = false,
    // Event Handlers
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCollapse: () -> Unit,
    onShowQueueClicked: () -> Unit,
    onShowSongOptions: (Song) -> Unit = {},
    onQueueDragStart: () -> Unit,
    onQueueDrag: (Float) -> Unit,
    onQueueRelease: (Float, Float) -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onFavoriteToggle: () -> Unit
) {
    var retainedSong by remember { mutableStateOf(currentSong) }
    LaunchedEffect(currentSong?.id) {
        if (currentSong != null) {
            retainedSong = currentSong
        }
    }

    val song = currentSong ?: retainedSong ?: return // Keep the player visible while transitioning
    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    var showLyricsSheet by remember { mutableStateOf(false) }
    var selectedLyricsMode by rememberSaveable(song.id) { mutableStateOf<Boolean?>(null) }
    var showOutputSheet by remember { mutableStateOf(false) }
    var showAudioPipeline by remember { mutableStateOf(false) }
    var showFetchLyricsDialog by remember { mutableStateOf(false) }
    var saveLyricsLauncherContent by remember { mutableStateOf<String?>(null) }
    val lyricsSearchUiState by playerViewModel.lyricsSearchUiState.collectAsStateWithLifecycle()
    val lyricsSyncOffset by playerViewModel.lyricsSyncOffset.collectAsStateWithLifecycle()
    val isImmersiveTemporarilyDisabled by playerViewModel.isImmersiveTemporarilyDisabled.collectAsStateWithLifecycle()
    val appSettings by playerViewModel.settings.collectAsStateWithLifecycle()
    val stableForLyrics by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val currentLyrics = stableForLyrics.lyrics
    val context = LocalContext.current
    val resources = LocalResources.current
    val lyricsImportScope = rememberCoroutineScope()
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        lyricsImportScope.launch {
            val validation = withContext(Dispatchers.IO) { validateLyricsImport(context, uri) }
            when (validation) {
                is LyricsImportValidationResult.Valid -> {
                    playerViewModel.importLyricsFromFile(validation.value)
                    showFetchLyricsDialog = false
                    showLyricsSheet = true
                }
                is LyricsImportValidationResult.Invalid -> playerViewModel.sendToast(resources.getString(LyricsImportSecurity.messageResource(validation.reason)))
            }
        }
    }
    val saveLyricsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        val content = saveLyricsLauncherContent
        if (uri != null && content != null) lyricsImportScope.launch(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        }
    }
    // Looked up with the song, not only when the lyrics open: the strip above the scrubber reads them.
    LaunchedEffect(song.id) { playerViewModel.loadLyricsForCurrentSong() }
    var lyricsLookedUp by remember(song.id) { mutableStateOf(false) }
    LaunchedEffect(song.id, stableForLyrics.isLoadingLyrics, currentLyrics) {
        if (stableForLyrics.isLoadingLyrics || currentLyrics != null) lyricsLookedUp = true
    }
    LaunchedEffect(lyricsSearchUiState) {
        if (lyricsSearchUiState is LyricsSearchUiState.Success && showFetchLyricsDialog) {
            showFetchLyricsDialog = false
            showLyricsSheet = true
            playerViewModel.resetLyricsSearchState()
        }
    }
    val onLyricsClick = { showLyricsSheet = true }
    // The fold between the player and its lyrics: one eased movement each way.
    val lyricsMorph = remember { LyricsMorph(Animatable(0f)) }
    LaunchedEffect(showLyricsSheet) {
        lyricsMorph.progress.animateTo(
            targetValue = if (showLyricsSheet) 1f else 0f,
            animationSpec = tween(LYRICS_MORPH_MS, easing = FastOutSlowInEasing),
        )
    }
    val lyricsComposed by remember { derivedStateOf { showLyricsSheet || lyricsMorph.progress.value > 0f } }
    if (showFetchLyricsDialog) {
        FetchLyricsDialog(
            uiState = lyricsSearchUiState,
            currentSong = song,
            onConfirm = { forcePick -> playerViewModel.fetchLyricsForCurrentSong(forcePick) },
            onPickResult = { playerViewModel.acceptLyricsSearchResultForCurrentSong(it) },
            onManualSearch = { title, artist -> playerViewModel.searchLyricsManually(title, artist) },
            onDismiss = {
                showFetchLyricsDialog = false
                playerViewModel.resetLyricsSearchState()
            },
            onImport = { filePickerLauncher.launch(LyricsImportSecurity.pickerMimeTypes()) },
        )
    }
    var showArtistPicker by rememberSaveable { mutableStateOf(false) }
    
    // Single subscription — replaces 11 independent collectAsStateWithLifecycle calls.
    // distinctUntilChanged in the ViewModel ensures this only emits when something
    // actually changed, batching multiple rapid updates into one recomposition.
    val fullPlayerSlice by playerViewModel.fullPlayerSlice.collectAsStateWithLifecycle()
    val currentSongArtists = fullPlayerSlice.currentSongArtists
    val albumArtQuality = fullPlayerSlice.albumArtQuality
    val isRemotePlaybackActive = false
    val audioMetaText = fullPlayerSlice.audioMetaText
    val navigationBarBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val queueGestureBottomExclusion = maxOf(20.dp, navigationBarBottomInset + 8.dp)
    val queueGestureBottomExclusionPx = with(LocalDensity.current) {
        queueGestureBottomExclusion.toPx()
    }

    var totalDrag by remember { mutableStateOf(0f) }

    // totalDurationValue is derived from stablePlayerState, so it's fine.
    // OPTIMIZATION: Use passed provider instead of collecting flow
    val totalDurationValue = totalDurationProvider()

    // Over the artwork backdrop the ink is white with a cast of the cover's hue, changing
    // with each cover; on a plain theme it is the scheme's own.
    val playerOnBaseColor = if (appSettings.playerArtworkGradient) rememberPlayerInk()
        else LocalMaterialTheme.current.onPrimaryContainer
    val playerAccentColor = LocalMaterialTheme.current.primary
    // The top buttons wear the dock's glass and its ink.
    val chromeDark = LocalMaterialTheme.current.surface.luminance() < 0.5f
    val chromeContent = LocalMaterialTheme.current.onSurface
    val playerOnAccentColor = LocalMaterialTheme.current.onPrimary
    val transportPlayPauseColors = expressivePlayPauseButtonColors(LocalMaterialTheme.current)
    val transportSkipColors = expressiveSkipButtonColors(LocalMaterialTheme.current)
    val transportSkipButtonColors = TransportButtonColors(
        container = playerAccentColor,
        content = playerOnAccentColor
    )
    val progressActiveColor = playerOnBaseColor

    val placeholderColor = playerOnBaseColor.copy(alpha = 0.1f)
    val placeholderOnColor = playerOnBaseColor.copy(alpha = 0.2f)

    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val onAlbumSongSelected: (Song, Int) -> Unit = { newSong, index ->
        playerViewModel.showAndPlaySong(
            song = newSong,
            contextSongs = currentPlaybackQueue,
            queueName = currentQueueSourceName,
            indexInQueue = index
        )
    }

    val onSongMetadataQueueClick = {
        showSongInfoBottomSheet = true
        onShowQueueClicked()
    }

    val onSongMetadataArtistClick = {
        val resolvedArtistId = currentSongArtists.firstOrNull { it.id != 0L && it.id != -1L }?.id
        when {
            currentSongArtists.size > 1 -> showArtistPicker = true
            resolvedArtistId != null -> playerViewModel.triggerArtistNavigationFromPlayer(resolvedArtistId)
            else -> playerViewModel.openArtistOf(song)
        }
    }

    var pendingCarouselIndex by remember { mutableStateOf<Int?>(null) }
    val currentQueueIndex = remember(song.id, currentMediaItemIndex, currentPlaybackQueue) {
        resolveQueueIndex(
            queue = currentPlaybackQueue,
            songId = song.id,
            currentMediaItemIndex = currentMediaItemIndex
        )
    }
    val skipRequests = remember {
        MutableSharedFlow<SkipDirection>(
            extraBufferCapacity = 16
        )
    }
    val latestQueue by rememberUpdatedState(currentPlaybackQueue)
    val latestSongId by rememberUpdatedState(song.id)
    val latestCurrentQueueIndex by rememberUpdatedState(currentQueueIndex)
    val latestRepeatMode by rememberUpdatedState(repeatMode)
    val latestIsRemotePlaybackActive by rememberUpdatedState(isRemotePlaybackActive)
    val latestCurrentPositionProvider by rememberUpdatedState(currentPositionProvider)
    val latestOnNext by rememberUpdatedState(onNext)
    val latestOnPrevious by rememberUpdatedState(onPrevious)

    LaunchedEffect(currentQueueIndex, pendingCarouselIndex) {
        if (pendingCarouselIndex == currentQueueIndex) {
            pendingCarouselIndex = null
        }
    }

    LaunchedEffect(pendingCarouselIndex, currentQueueIndex) {
        val targetIndex = pendingCarouselIndex ?: return@LaunchedEffect
        kotlinx.coroutines.delay(900)
        if (pendingCarouselIndex == targetIndex && currentQueueIndex != targetIndex) {
            pendingCarouselIndex = null
        }
    }

    LaunchedEffect(skipRequests) {
        skipRequests.collect { direction ->
            when (direction) {
                SkipDirection.NEXT -> latestOnNext()
                SkipDirection.PREVIOUS -> latestOnPrevious()
            }

            kotlinx.coroutines.delay(SKIP_COMMAND_GUARD_MS)
        }
    }

    fun predictSkipCarouselIndex(direction: SkipDirection): Int? {
        val queueSnapshot = latestQueue
        val baseIndex = pendingCarouselIndex
            ?: latestCurrentQueueIndex
            ?: queueSnapshot.indexOfFirst { it.id == latestSongId }.takeIf { it >= 0 }

        return when (direction) {
            SkipDirection.NEXT -> predictSkipNextCarouselIndex(
                currentIndex = baseIndex,
                queue = queueSnapshot,
                repeatMode = latestRepeatMode,
                isRemotePlaybackActive = latestIsRemotePlaybackActive
            )
            SkipDirection.PREVIOUS -> predictSkipPreviousCarouselIndex(
                currentIndex = baseIndex,
                queue = queueSnapshot,
                currentPositionMs = latestCurrentPositionProvider(),
                repeatMode = latestRepeatMode,
                isRemotePlaybackActive = latestIsRemotePlaybackActive
            )
        }
    }

    fun requestSkip(direction: SkipDirection) {
        val predictedTargetIndex = predictSkipCarouselIndex(direction)
        if (skipRequests.tryEmit(direction) && predictedTargetIndex != null) {
            pendingCarouselIndex = predictedTargetIndex
        }
    }

    val onNextWithOptimisticCarousel = {
        requestSkip(SkipDirection.NEXT)
        Unit
    }

    val onPreviousWithOptimisticCarousel = {
        requestSkip(SkipDirection.PREVIOUS)
        Unit
    }

    // Portrait on a phone-shaped screen only: on a wide one a full-width square would
    // leave no room for the controls under it, so the card stays.
    val configuration = LocalConfiguration.current
    val fullBleedCover = appSettings.fullScreenCoverArt && !isLandscape &&
        configuration.screenWidthDp <= configuration.screenHeightDp * FULL_BLEED_MAX_ASPECT
    val albumCoverSection: @Composable (Modifier) -> Unit = { modifier ->
        FullPlayerAlbumCoverSection(
            fullBleed = fullBleedCover,
            song = song,
            currentPlaybackQueue = currentPlaybackQueue,
            currentMediaItemIndex = currentQueueIndex ?: currentMediaItemIndex,
            carouselStyle = carouselStyle,
            loadingTweaks = loadingTweaks,
            isSheetDragGestureActive = isSheetDragGestureActive,
            expansionFractionProvider = expansionFractionProvider,
            currentSheetState = currentSheetState,
            isPlayingProvider = isPlayingProvider,
            playWhenReadyProvider = playWhenReadyProvider,
            placeholderColor = placeholderColor,
            placeholderOnColor = placeholderOnColor,
            albumArtQuality = albumArtQuality,
            coverMix = appSettings.albumCoverMix,
            requestedScrollIndex = pendingCarouselIndex,
            onSongSelected = onAlbumSongSelected,
            onAlbumClick = { albumSong -> playerViewModel.openAlbumOf(albumSong) },
            modifier = modifier
        )
    }

    val playerProgressSection: @Composable () -> Unit = {
        FullPlayerProgressSection(
            song = song,
            audioMetaText = audioMetaText,
            qualityUpgrading = fullPlayerSlice.qualityUpgrading,
            currentPositionProvider = currentPositionProvider,
            totalDurationValue = totalDurationValue,
            onSeek = onSeek,
            expansionFractionProvider = expansionFractionProvider,
            isPlayingProvider = isPlayingProvider,
            currentSheetState = currentSheetState,
            progressActiveColor = progressActiveColor,
            playerOnBaseColor = playerOnBaseColor,
            progressStyle = appSettings.playerProgressStyle,
            allowRealtimeUpdates = allowRealtimeUpdates,
            isSheetDragGestureActive = isSheetDragGestureActive,
            loadingTweaks = loadingTweaks,
            onAudioMetaClick = { showAudioPipeline = true },
        )
    }

    val lyricStripSection: @Composable () -> Unit = {
        CurrentLyricStrip(
            synced = currentLyrics?.synced,
            hasPlainLyrics = !currentLyrics?.plain.isNullOrEmpty(),
            loading = stableForLyrics.isLoadingLyrics || !lyricsLookedUp,
            trackKey = song.id,
            positionFlow = playerViewModel.currentPlaybackPosition,
            offsetMs = lyricsSyncOffset,
            isPlaying = stableForLyrics.isPlaying,
            durationMs = totalDurationValue,
            ink = playerOnBaseColor,
            onClick = onLyricsClick,
        )
    }

    val controlsSection: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FullPlayerControlsSection(
                loadingTweaks = loadingTweaks,
                isSheetDragGestureActive = isSheetDragGestureActive,
                expansionFractionProvider = expansionFractionProvider,
                currentSheetState = currentSheetState,
                placeholderColor = placeholderColor,
                placeholderOnColor = placeholderOnColor,
                isPlayingProvider = isPlayingProvider,
                isBufferingProvider = isBufferingProvider,
                onPrevious = onPreviousWithOptimisticCarousel,
                onPlayPause = onPlayPause,
                onNext = onNextWithOptimisticCarousel,
                transportPlayPauseColors = transportPlayPauseColors,
                transportSkipColors = transportSkipButtonColors,
                isShuffleEnabledProvider = isShuffleEnabledProvider,
                shuffleTransitionInProgress = shuffleTransitionInProgress,
                repeatModeProvider = repeatModeProvider,
                isFavoriteProvider = isFavoriteProvider,
                onShuffleToggle = onShuffleToggle,
                onRepeatToggle = onRepeatToggle,
                onFavoriteToggle = onFavoriteToggle,
                toggleGap = LocalDeckSpread.current.transport,
            )
            AudioStatsCaption(
                format = audioMetaText,
                ink = playerOnBaseColor,
                onClick = { showAudioPipeline = true },
            )
        }
    }

    val portraitSongMetadataSection: @Composable () -> Unit = {
        FullPlayerSongMetadataSection(
            song = song,
            currentSongArtists = currentSongArtists,
            loadingTweaks = loadingTweaks,
            isSheetDragGestureActive = isSheetDragGestureActive,
            expansionFractionProvider = expansionFractionProvider,
            currentSheetState = currentSheetState,
            placeholderColor = placeholderColor,
            placeholderOnColor = placeholderOnColor,
            isLandscape = false,
            onLyricsClick = onLyricsClick,
            playerOnBaseColor = playerOnBaseColor,
            playerViewModel = playerViewModel,
            chipColor = playerOnAccentColor.copy(alpha = 0.8f),
            chipContentColor = LocalMaterialTheme.current.onSurface,
            onQueueClick = onSongMetadataQueueClick,
            onArtistClick = onSongMetadataArtistClick,
            isPlayingProvider = isPlayingProvider
        )
    }

    val landscapeSongMetadataSection: @Composable () -> Unit = {
        FullPlayerSongMetadataSection(
            song = song,
            currentSongArtists = currentSongArtists,
            loadingTweaks = loadingTweaks,
            isSheetDragGestureActive = isSheetDragGestureActive,
            expansionFractionProvider = expansionFractionProvider,
            currentSheetState = currentSheetState,
            placeholderColor = placeholderColor,
            placeholderOnColor = placeholderOnColor,
            isLandscape = true,
            onLyricsClick = onLyricsClick,
            playerOnBaseColor = playerOnBaseColor,
            playerViewModel = playerViewModel,
            chipColor = playerOnAccentColor.copy(alpha = 0.8f),
            chipContentColor = LocalMaterialTheme.current.onSurface,
            onQueueClick = onSongMetadataQueueClick,
            onArtistClick = onSongMetadataArtistClick,
            isPlayingProvider = isPlayingProvider
        )
    }

    CompositionLocalProvider(LocalLyricsMorph provides lyricsMorph) {
    Box(Modifier.fillMaxSize()) {
        if (appSettings.playerArtworkGradient) {
            // Pinned to the sheet while the player slides in (see LocalFullPlayerSlide).
            val slide = `in`.melo.presentation.components.scoped.LocalFullPlayerSlide.current
            val pinned = Modifier.matchParentSize().graphicsLayer { translationY = -slide() }
            if (appSettings.playerBackdropStyle == AppSettings.BACKDROP_MESH) {
                PlayerMeshBackdrop(song.albumArtUriString, pinned)
            } else {
                PlayerArtworkBackdrop(song.albumArtUriString, pinned)
            }
        }
    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.pointerInput(currentSheetState, queueGestureBottomExclusionPx) {
            val queueDragActivationThresholdPx = 4.dp.toPx()
            val quickFlickVelocityThreshold = -520f

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Check condition AFTER the down event occurs
                val isFullyExpanded = currentSheetState == PlayerSheetState.EXPANDED && expansionFractionProvider() >= 0.99f

                if (!isFullyExpanded) {
                    return@awaitEachGesture
                }

                val bottomGestureBoundaryY =
                    (size.height.toFloat() - queueGestureBottomExclusionPx).coerceAtLeast(0f)
                if (down.position.y >= bottomGestureBoundaryY) {
                    // Let the system Home/back gesture win near the bottom edge.
                    return@awaitEachGesture
                }

                // Proceed with gesture logic
                var dragConsumedByQueue = false
                val velocityTracker = VelocityTracker()
                var totalDrag = 0f
                velocityTracker.addPosition(down.uptimeMillis, down.position)

                drag(down.id) { change ->
                    val dragAmount = change.positionChange().y
                    totalDrag += dragAmount
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    val isDraggingUp = totalDrag < -queueDragActivationThresholdPx

                    if (isDraggingUp && !dragConsumedByQueue) {
                        dragConsumedByQueue = true
                        onQueueDragStart()
                    }

                    if (dragConsumedByQueue) {
                        change.consume()
                        onQueueDrag(dragAmount)
                    }
                }

                val velocity = velocityTracker.calculateVelocity().y
                if (dragConsumedByQueue) {
                    onQueueRelease(totalDrag, velocity)
                } else if (
                    totalDrag < -(queueDragActivationThresholdPx * 2f) &&
                    velocity < quickFlickVelocityThreshold
                ) {
                    // Treat short/fast upward flick as queue-open intent.
                    onQueueRelease(totalDrag, velocity)
                }
            }
        },
        topBar = {
            // MD3: TopAppBar 在竖屏时滑入，横屏时向上滑出淡出
            AnimatedVisibility(
                visible = !isLandscape,
                enter = fadeIn(animationSpec = tween(350, easing = FastOutSlowInEasing)) +
                        slideInVertically(
                            initialOffsetY = { -it / 2 },
                            animationSpec = tween(350, easing = FastOutSlowInEasing)
                        ),
                exit = fadeOut(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                       slideOutVertically(
                           targetOffsetY = { -it / 2 },
                           animationSpec = tween(220, easing = FastOutSlowInEasing)
                       )
            ) {
                TopAppBar(
                    modifier = Modifier.graphicsLayer {
                        val fraction = expansionFractionProvider()
                        // TopBar should always fade in smoothly, ignoring delayAll to avoid empty UI
                        val startThreshold = 0f
                        val endThreshold = 1f
                        alpha = ((fraction - startThreshold) / (endThreshold - startThreshold)).coerceIn(0f, 1f)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = LocalMaterialTheme.current.onPrimaryContainer,
                    ),
                    title = {
                        Text(
                            modifier = Modifier.padding(start = 18.dp),
                            text = stringResource(R.string.player_now_playing),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    navigationIcon = {
                        Box(
                            modifier = Modifier
                                // Ancho total = 14dp de padding + 42dp del botón
                                .width(56.dp)
                                .height(42.dp),
                            // 2. Alinea el contenido (el botón) al final (derecha) y centrado verticalmente
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            // 3. Tu botón circular original, sin cambios
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .playerDockGlass(CircleShape, chromeDark)
                                    .clickable(onClick = onCollapse),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = meloPainter(R.drawable.rounded_keyboard_arrow_down_24),
                                    contentDescription = stringResource(R.string.player_cd_collapse),
                                    tint = chromeContent
                                )
                            }
                        }
                    },
                    actions = {
                        Row(
                            modifier = Modifier
                                .padding(end = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(height = 42.dp, width = 50.dp)
                                    .playerDockGlass(CircleShape, chromeDark)
                                    .clickable { showOutputSheet = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = meloPainter(R.drawable.rounded_cast_24),
                                    contentDescription = stringResource(R.string.player_cd_cast),
                                    tint = chromeContent
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(height = 42.dp, width = 50.dp)
                                    .playerDockGlass(CircleShape, chromeDark)
                                    .clickable {
                                        showSongInfoBottomSheet = true
                                        onShowQueueClicked()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = meloPainter(R.drawable.rounded_queue_music_24),
                                    contentDescription = stringResource(R.string.player_cd_open_queue),
                                    tint = chromeContent
                                )
                            }
                            if (appSettings.showPlayerMenuButton) Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .playerDockGlass(CircleShape, chromeDark)
                                    .clickable { onShowSongOptions(song.copy(duration = totalDurationProvider().takeIf { it > 0 } ?: song.duration)) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = MeloIcons.MoreVert,
                                    contentDescription = stringResource(R.string.player_cd_more_options),
                                    tint = chromeContent
                                )
                            }
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        // MD3: 方向变化时先 alpha=0 再淡入新布局，避免双布局同时测量导致错位
        var contentVisible by remember(isLandscape) { mutableStateOf(false) }
        LaunchedEffect(isLandscape) { contentVisible = true }
        val contentAlpha by animateFloatAsState(
            targetValue = if (contentVisible) 1f else 0f,
            animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
            label = "orientationAlpha"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha }
        ) {
            if (isLandscape) {
                FullPlayerLandscapeContent(
                    paddingValues = paddingValues,
                    albumCoverSection = albumCoverSection,
                    songMetadataSection = landscapeSongMetadataSection,
                    playerProgressSection = playerProgressSection,
                    controlsSection = controlsSection
                )
            } else {
                FullPlayerPortraitContent(
                    paddingValues = paddingValues,
                    fullBleed = fullBleedCover,
                    albumCoverSection = albumCoverSection,
                    songMetadataSection = portraitSongMetadataSection,
                    lyricStripSection = lyricStripSection,
                    playerProgressSection = playerProgressSection,
                    controlsSection = controlsSection
                )
            }
        }
    }
    }
    // The lyrics come in over the player as its cover and credits fold into their header, and
    // go back out the same way; the travelling copies are drawn over both.
    if (lyricsComposed) Box(Modifier.fillMaxSize().graphicsLayer { alpha = lyricsMorph.value() }) {
        LyricsSheet(
            stablePlayerStateFlow = playerViewModel.stablePlayerState,
            playbackPositionFlow = playerViewModel.currentPlaybackPosition,
            lyricsSearchUiState = lyricsSearchUiState,
            resetLyricsForCurrentSong = {
                showLyricsSheet = false
                playerViewModel.resetLyricsForCurrentSong()
            },
            onSearchLyrics = { forcePick -> playerViewModel.fetchLyricsForCurrentSong(forcePick) },
            onPickResult = { playerViewModel.acceptLyricsSearchResultForCurrentSong(it) },
            onManualSearch = { title, artist -> playerViewModel.searchLyricsManually(title, artist) },
            onImportLyrics = { filePickerLauncher.launch(LyricsImportSecurity.pickerMimeTypes()) },
            onDismissLyricsSearch = { playerViewModel.resetLyricsSearchState() },
            lyricsSyncOffset = lyricsSyncOffset,
            onLyricsSyncOffsetChange = { playerViewModel.setLyricsSyncOffset(song.id, it) },
            lyricsTextStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = null),
            // The lyrics take the player's artwork backdrop with them, a shade deeper so white
            // lines read on any cover.
            backdrop = if (appSettings.playerArtworkGradient) {
                { m ->
                    Box(m) {
                        if (appSettings.playerBackdropStyle == AppSettings.BACKDROP_MESH) {
                            PlayerMeshBackdrop(song.albumArtUriString, Modifier.matchParentSize())
                        } else {
                            PlayerArtworkBackdrop(song.albumArtUriString, Modifier.matchParentSize())
                        }
                        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.24f)))
                    }
                }
            } else null,
            colorScheme = LocalMaterialTheme.current,
            onBackClick = { showLyricsSheet = false },
            initialShowSyncedLyrics = selectedLyricsMode,
            onLyricsModeChanged = { selectedLyricsMode = it },
            onSaveLyricsToFile = { lyricSong, lyrics, preferSynced -> saveLyricsLauncherContent = LyricsUtils.toLrcString(lyrics, preferSynced); saveLyricsLauncher.launch(lyricSong.title + ".lrc") },
            onTranslateViaAi = {},
            onSeekTo = { playerViewModel.seekTo(it) },
            onPlayPause = { playerViewModel.playPause() },
            onNext = onNext,
            onPrev = onPrevious,
            immersiveLyricsEnabled = appSettings.immersiveLyrics,
            immersiveLyricsTimeout = appSettings.immersiveLyricsTimeoutMs,
            isImmersiveTemporarilyDisabled = isImmersiveTemporarilyDisabled,
            onSetImmersiveTemporarilyDisabled = { playerViewModel.setImmersiveTemporarilyDisabled(it) },
            isShuffleEnabled = isShuffleEnabled,
            repeatMode = repeatMode,
            isFavoriteProvider = isFavoriteProvider,
            onShuffleToggle = onShuffleToggle,
            onRepeatToggle = onRepeatToggle,
            onFavoriteToggle = onFavoriteToggle,
            animateIn = false,
            interactive = showLyricsSheet,
        )
    }
    if (lyricsComposed) {
        val lyricsInk = if (appSettings.playerArtworkGradient) Color.White else playerOnBaseColor
        LyricsMorphOverlay(
            morph = lyricsMorph,
            artModel = song.albumArtUriString,
            artTargetSize = remember(albumArtQuality) {
                if (albumArtQuality.maxSize == 0) SafeOriginalAlbumArtSize
                else coil.size.Size(albumArtQuality.maxSize, albumArtQuality.maxSize)
            },
            title = song.title.ifBlank { stringResource(R.string.common_unknown_track) },
            artist = song.displayArtist.ifBlank { stringResource(R.string.common_unknown_artist) },
            titleStyle = playerTitleStyle(),
            artistStyle = playerArtistStyle(),
            titleInk = playerOnBaseColor,
            artistInk = playerOnBaseColor.copy(alpha = PLAYER_ARTIST_INK_ALPHA),
            headerTitleStyle = TextStyle(fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
            headerArtistStyle = TextStyle(fontSize = 15.sp, lineHeight = 19.sp),
            headerInk = lyricsInk,
            headerArtistInk = lyricsInk.copy(alpha = 0.62f),
        )
    }
    }

    if (showOutputSheet) {
        CastBottomSheet(
            playerViewModel = playerViewModel,
            onDismiss = { showOutputSheet = false },
            onOpenAudioPipeline = { showOutputSheet = false; showAudioPipeline = true },
        )
    }

    if (showAudioPipeline) {
        `in`.melo.presentation.components.AudioPipelineDialog(onDismiss = { showAudioPipeline = false })
    }

    val artistPickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    if (showArtistPicker && currentSongArtists.isNotEmpty()) {
        PlayerArtistPickerBottomSheet(
            song = song,
            artists = currentSongArtists,
            sheetState = artistPickerSheetState,
            onDismiss = { showArtistPicker = false },
            onArtistClick = { artist ->
                playerViewModel.triggerArtistNavigationFromPlayer(artist.id)
                showArtistPicker = false
            }
        )
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
internal fun FullPlayerAlbumCoverSection(
    song: Song,
    currentPlaybackQueue: ImmutableList<Song>,
    currentMediaItemIndex: Int,
    carouselStyle: String,
    loadingTweaks: FullPlayerLoadingTweaks,
    isSheetDragGestureActive: Boolean,
    expansionFractionProvider: () -> Float,
    currentSheetState: PlayerSheetState,
    isPlayingProvider: () -> Boolean,
    playWhenReadyProvider: () -> Boolean,
    placeholderColor: Color,
    placeholderOnColor: Color,
    albumArtQuality: AlbumArtQuality,
    requestedScrollIndex: Int?,
    onSongSelected: (Song, Int) -> Unit,
    onAlbumClick: (Song) -> Unit,
    modifier: Modifier = Modifier,
    /** Runs the cover the screen's width and dissolves its foot, instead of a rounded card. */
    fullBleed: Boolean = false,
    coverMix: Boolean = false,
) {
    val shouldDelay = loadingTweaks.delayAll || loadingTweaks.delayAlbumCarousel
    // A full-screen cover doesn't shrink: that would open bare backdrop down the screen's edges.
    val shouldApplyPausedScale = !fullBleed && !isPlayingProvider() && !playWhenReadyProvider()
    // Use a short deterministic tween instead of spring(StiffnessLow). The original
    // spring took ~1s to settle, producing ~60 frames of graphicsLayer invalidations
    // that overlapped with any subsequent sheet-collapse gesture. A 260 ms tween
    // finishes well before the user can start the next gesture, keeping the album
    // art's "pause squish" visible but removing the long tail of frame work.
    val albumArtScale by animateFloatAsState(
        targetValue = if (shouldApplyPausedScale) 0.95f else 1f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "AlbumArtScale"
    )

    val morph = LocalLyricsMorph.current
    val restingCorner = if (fullBleed) 0.dp else 18.dp
    if (morph != null) SideEffect {
        morph.playerArtCorner = restingCorner
        morph.playerArtFades = fullBleed
    }
    val miniStartPx = LocalMiniCoverStartPx.current
    val cardInsetPx = with(LocalDensity.current) { 16.dp.toPx() }
    BoxWithConstraints(
        modifier = modifier
            // Where the lyrics fold starts from: the whole full-screen cover, or the card at
            // the centre of the carousel.
            .lyricsMorphPlayer(MorphPart.ART) { bounds ->
                if (fullBleed) bounds else {
                    val side = minOf(bounds.width, bounds.height - cardInsetPx)
                    Rect(bounds.center.x - side / 2f, bounds.center.y - side / 2f, bounds.center.x + side / 2f, bounds.center.y + side / 2f)
                }
            }
            .dockFlight(expansionFractionProvider, miniStartPx, restingCorner)
            .fillMaxWidth()
            .padding(vertical = if (fullBleed) 0.dp else 8.dp)
    ) {
        // Full screen, the cover takes whatever height it is given down to the deck, cropped to fill.
        val carouselHeight = if (fullBleed) {
            if (constraints.hasBoundedHeight && maxHeight > maxWidth) maxHeight else maxWidth
        } else when (carouselStyle) {
            CarouselStyle.NO_PEEK -> maxWidth
            CarouselStyle.ONE_PEEK -> maxWidth * 0.8f
            CarouselStyle.TWO_PEEK -> maxWidth * 0.6f
            else -> maxWidth * 0.8f
        }

        DelayedContent(
            shouldDelay = shouldDelay,
            showPlaceholders = loadingTweaks.showPlaceholders,
            applyPlaceholderDelayOnClose = loadingTweaks.applyPlaceholdersOnClose,
            switchOnDragRelease = loadingTweaks.switchOnDragRelease,
            isSheetDragGestureActive = isSheetDragGestureActive,
            sharedBoundsModifier = Modifier.fillMaxWidth().height(carouselHeight),
            expansionFractionProvider = expansionFractionProvider,
            isExpandedOverride = currentSheetState == PlayerSheetState.EXPANDED,
            normalStartThreshold = 0.08f,
            delayAppearThreshold = loadingTweaks.contentAppearThresholdPercent / 100f,
            delayCloseThreshold = 1f - (loadingTweaks.contentCloseThresholdPercent / 100f),
            placeholder = {
                if (loadingTweaks.transparentPlaceholders) {
                    Box(
                        Modifier
                            .height(carouselHeight)
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = albumArtScale
                                scaleY = albumArtScale
                            }
                    )
                } else {
                    AlbumPlaceholder(
                        height = carouselHeight,
                        color = placeholderColor,
                        onColor = placeholderOnColor,
                        modifier = Modifier.graphicsLayer {
                            scaleX = albumArtScale
                            scaleY = albumArtScale
                        }
                    )
                }
            }
        ) {
            AlbumCarouselSection(
                currentSong = song,
                queue = currentPlaybackQueue,
                expansionFraction = 1f,
                currentMediaItemIndex = currentMediaItemIndex,
                requestedScrollIndex = requestedScrollIndex,
                onSongSelected = { newSong, index ->
                    if (newSong.id != song.id || index != currentMediaItemIndex) {
                        onSongSelected(newSong, index)
                    }
                },
                onAlbumClick = onAlbumClick,
                // Edge to edge, one cover at a time: a peek or a gap would read as a seam.
                carouselStyle = if (fullBleed) CarouselStyle.NO_PEEK else carouselStyle,
                itemSpacing = if (fullBleed) 0.dp else 8.dp,
                itemCornerRadius = if (fullBleed) 0.dp else 18.dp,
                fillHeight = fullBleed,
                modifier = Modifier
                    .height(carouselHeight)
                    .graphicsLayer {
                        scaleX = albumArtScale
                        scaleY = albumArtScale
                    }
                    // The foot fills back in as the cover flies down to the mini player.
                    .then(if (fullBleed) Modifier.artworkBottomFade(floor = { dockProgress(expansionFractionProvider()) }) else Modifier),
                albumArtQuality = albumArtQuality,
                coverMix = coverMix,
            )
        }
    }
}

@Composable
internal fun FullPlayerPortraitContent(
    paddingValues: PaddingValues,
    albumCoverSection: @Composable (Modifier) -> Unit,
    songMetadataSection: @Composable () -> Unit,
    playerProgressSection: @Composable () -> Unit,
    controlsSection: @Composable () -> Unit,
    fullBleed: Boolean = false,
    lyricStripSection: @Composable () -> Unit = {},
) {
    if (fullBleed) {
        FullBleedPortraitContent(paddingValues, albumCoverSection) {
            PlayerDeck(songMetadataSection, lyricStripSection, playerProgressSection, controlsSection)
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(
                horizontal = 24.dp,
                vertical = 0.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceAround
    ) {
        albumCoverSection(Modifier)
        PlayerDeck(songMetadataSection, lyricStripSection, playerProgressSection, controlsSection)
    }
}

/**
 * The credits, the line being sung, the scrubber and the transport as one block at its
 * natural height, so nothing opens up between the scrubber and the buttons under it.
 */
@Composable
private fun PlayerDeck(
    songMetadataSection: @Composable () -> Unit,
    lyricStripSection: @Composable () -> Unit,
    playerProgressSection: @Composable () -> Unit,
    controlsSection: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.align(Alignment.Start)) {
            songMetadataSection()
        }
        val spread = LocalDeckSpread.current
        Spacer(Modifier.height(4.dp + spread.title))
        lyricStripSection()
        playerProgressSection()
        Spacer(Modifier.height(4.dp + spread.transport))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            controlsSection()
        }
    }
}

/** Widest screen, as a share of its height, on which the cover can run full width. */
private const val FULL_BLEED_MAX_ASPECT = 0.62f

/** The strip between the status bar and the top of the square cover. */
private val FULL_BLEED_TOP_STRIP = 40.dp

/** Between the square cover's foot and the title; the cover's dissolve runs halfway into it. */
private val FULL_BLEED_TITLE_GAP = 20.dp

/** The most a tall screen adds around the transport, split evenly above and below it. */
private val FULL_BLEED_SPREAD_MAX = 48.dp

/** The room left under the full-screen player's deck. */
private val FULL_BLEED_DECK_FOOT = 12.dp

/** Height a tall screen hands the deck: around the transport, and under the title. */
private data class DeckSpread(val transport: Dp = 0.dp, val title: Dp = 0.dp)

private val LocalDeckSpread = compositionLocalOf { DeckSpread() }

/**
 * The cover as a full-width square under the top bar, never stretched, so nothing is cut off its
 * sides; the backdrop runs from the screen's top edge to just past it. The deck sits at the foot.
 * Height a tall screen has spare goes first to the gaps around the transport, up to
 * [FULL_BLEED_SPREAD_MAX], then half above the cover and half under the title, so the
 * cover and the credits stay together in the middle of what is left.
 */
@Composable
private fun FullBleedPortraitContent(
    paddingValues: PaddingValues,
    albumCoverSection: @Composable (Modifier) -> Unit,
    deck: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottomPad = paddingValues.calculateBottomPadding()
        // The deck's height without anything granted to it, so what is granted next never
        // feeds back into what is measured.
        var naturalDeckPx by remember { mutableIntStateOf(0) }
        val squareBottom = statusTop + FULL_BLEED_TOP_STRIP + maxWidth
        val (halfSpreadPx, titleExtraPx) = with(density) {
            val slackPx = if (naturalDeckPx == 0) 0 else (
                (maxHeight - bottomPad - FULL_BLEED_DECK_FOOT - squareBottom - FULL_BLEED_TITLE_GAP).roundToPx() -
                    naturalDeckPx
                ).coerceAtLeast(0)
            val half = minOf(slackPx, FULL_BLEED_SPREAD_MAX.roundToPx()) / 2
            // What the transport can't use goes between the cover and the title, so the cover
            // stays the square it is rather than growing and cropping in further.
            half to slackPx - half * 2
        }
        val grantedPx = halfSpreadPx * 2 + titleExtraPx
        val spread = with(density) { DeckSpread(transport = halfSpreadPx.toDp(), title = titleExtraPx.toDp()) }
        albumCoverSection(Modifier.height(squareBottom + FULL_BLEED_TITLE_GAP / 2))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomPad)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            CompositionLocalProvider(LocalDeckSpread provides spread) {
                Box(Modifier.fillMaxWidth().onSizeChanged { naturalDeckPx = it.height - grantedPx }) { deck() }
            }
            Spacer(Modifier.height(FULL_BLEED_DECK_FOOT))
        }
    }
}

@Composable
internal fun FullPlayerLandscapeContent(
    paddingValues: PaddingValues,
    albumCoverSection: @Composable (Modifier) -> Unit,
    songMetadataSection: @Composable () -> Unit,
    playerProgressSection: @Composable () -> Unit,
    controlsSection: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(
                horizontal = 24.dp,
                vertical = 0.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        albumCoverSection(
            Modifier
                .fillMaxHeight()
                .weight(1f)
        )
        Spacer(Modifier.width(9.dp))
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(
                    horizontal = 0.dp,
                    vertical = 0.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            songMetadataSection()
            playerProgressSection()
            controlsSection()
        }
    }
}
