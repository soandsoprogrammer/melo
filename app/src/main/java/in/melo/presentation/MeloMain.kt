package `in`.melo.presentation

import `in`.melo.presentation.components.ambientBlobs
import `in`.melo.ui.theme.MeloLayout
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader as AndroidShader
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import `in`.melo.ui.glass.backdrop.backdrops.layerBackdrop
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import `in`.melo.BottomNavItem
import `in`.melo.R
import `in`.melo.MeloModel
import `in`.melo.data.preferences.NavBarStyle
import `in`.melo.presentation.components.DismissUndoBar
import `in`.melo.presentation.components.MiniPlayerBottomSpacer
import `in`.melo.presentation.components.MiniPlayerHeight
import `in`.melo.presentation.components.FloatingNavBarCornerRadius
import `in`.melo.presentation.components.PlayerInternalNavigationBar
import `in`.melo.presentation.components.UnifiedPlayerSheetV2
import `in`.melo.presentation.components.calculatePlayerSheetCollapsedTargetY
import `in`.melo.presentation.components.resolveNavBarOccupiedHeight
import `in`.melo.presentation.components.resolveNavBarSurfaceHeight
import `in`.melo.presentation.components.sanitizeNavigationBarBottomInset
import `in`.melo.presentation.navigation.AppNavigation
import `in`.melo.presentation.navigation.openMusic
import `in`.melo.presentation.navigation.Screen
import `in`.melo.presentation.navigation.navigateToTopLevelSafely
import `in`.melo.presentation.screens.AccountScreen
import `in`.melo.presentation.utils.AppHapticsConfig
import `in`.melo.presentation.utils.LocalAppHapticsConfig
import `in`.melo.presentation.utils.NoOpHapticFeedback
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import kotlin.math.roundToInt

private data class DismissUndoBarSlice(val isVisible: Boolean = false, val durationMillis: Long = 4000L)

/** Main scaffold: floating bar, blurred content, unified player sheet. */
@Composable
fun MeloMain(model: MeloModel, pendingSharedLink: String? = null, onSharedLinkHandled: () -> Unit = {}) {
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val account by model.account.collectAsStateWithLifecycle()
    val guest by model.guest.collectAsStateWithLifecycle()
    val online by model.online.collectAsStateWithLifecycle()
    if (!discovery.spotifyEnabled && !guest) {
        if (account.restoring) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        } else {
            AccountScreen(model, account)
        }
        return
    }
    var returnedFromBackground by remember { mutableStateOf(false) }
    var appInForeground by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        appInForeground = false
        returnedFromBackground = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        appInForeground = true
        if (returnedFromBackground) model.refreshLibrary()
        returnedFromBackground = false
    }
    val playerViewModel = model.ui
    val settings by playerViewModel.settings.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val message by model.message.collectAsStateWithLifecycle()
    val spotifyLinkUnavailable = stringResource(R.string.spotify_link_unavailable)
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); model.consumeMessage() }
    }
    LaunchedEffect(pendingSharedLink) {
        val link = pendingSharedLink ?: return@LaunchedEffect
        try {
            val item = model.resolveSpotifySharedLink(link)
            if (item == null) model.showMessage(spotifyLinkUnavailable)
            else navController.openMusic(playerViewModel, item)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            model.showMessage(spotifyLinkUnavailable)
        } finally {
            onSharedLinkHandled()
        }
    }

    val commonNavItems = remember {
        persistentListOf(
            BottomNavItem("Home", R.string.nav_bar_home, R.drawable.rounded_home_24, R.drawable.home_24_rounded_filled, Screen.Home),
            BottomNavItem("Search", R.string.nav_bar_search, R.drawable.rounded_search_24, R.drawable.rounded_search_24, Screen.Search),
            BottomNavItem("Library", R.string.nav_bar_library, R.drawable.rounded_library_music_24, R.drawable.round_library_music_24, Screen.Library),
        )
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var isSearchBarActive by remember { mutableStateOf(false) }
    val routesWithHiddenNavigationBar = remember {
        setOf(Screen.Settings.route, Screen.Equalizer.route, Screen.SettingsCategory.route, Screen.PlaylistDetail.route, Screen.AlbumDetail.route,
            Screen.ArtistDetail.route, Screen.Convert.route, Screen.DiscordLogin.route, Screen.MusicServer.route)
    }
    val shouldHideNavigationBar by remember(currentRoute, isSearchBarActive) {
        derivedStateOf {
            (currentRoute == Screen.Search.route && isSearchBarActive) || currentRoute in routesWithHiddenNavigationBar
        }
    }

    val playerConfig by playerViewModel.playerConfigSlice.collectAsStateWithLifecycle()
    val navBarStyle = playerConfig.navBarStyle
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val floatingNavBarCornerRadius = FloatingNavBarCornerRadius
    val useSmoothCorners = playerConfig.useSmoothCorners
    val isMiniPlayerDismissing by playerViewModel.isMiniPlayerDismissing.collectAsStateWithLifecycle()
    val disableBlurAllOver by playerViewModel.disableBlurAllOver.collectAsStateWithLifecycle()
    val predictiveBackCollapseFraction by playerViewModel.predictiveBackCollapseFraction.collectAsStateWithLifecycle()

    val systemNavBarInset = sanitizeNavigationBarBottomInset(
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    )
    val horizontalPadding = if (navBarStyle == NavBarStyle.DEFAULT) {
        if (systemNavBarInset > 30.dp) 16.dp else systemNavBarInset
    } else {
        0.dp
    }
    val animatedBottomBarPadding by animateDpAsState(
        targetValue = if (navBarStyle == NavBarStyle.FULL_WIDTH) 0.dp else systemNavBarInset,
        animationSpec = tween(400),
        label = "BottomBarPadding"
    )
    val bottomBarPadding = animatedBottomBarPadding
    val navBarHeight = resolveNavBarSurfaceHeight(navBarStyle, systemNavBarInset, navBarCompactMode)
    val navBarOccupiedHeight by remember(systemNavBarInset, navBarCompactMode) {
        derivedStateOf { resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode) }
    }
    val navBarVisibilityProgressState = animateFloatAsState(
        targetValue = if (shouldHideNavigationBar) 0f else 1f,
        animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing),
        label = "NavBarVisibilityProgress"
    )
    val navBarVisibilityProgress by navBarVisibilityProgressState
    val visibleNavBarOccupiedHeight by remember(navBarOccupiedHeight, navBarVisibilityProgress) {
        derivedStateOf { navBarOccupiedHeight * navBarVisibilityProgress }
    }
    val miniPlayerBottomMargin by remember(systemNavBarInset, visibleNavBarOccupiedHeight) {
        derivedStateOf {
            if (visibleNavBarOccupiedHeight > systemNavBarInset) visibleNavBarOccupiedHeight else systemNavBarInset
        }
    }
    val shouldRenderNavigationBar by remember(shouldHideNavigationBar, navBarVisibilityProgress) {
        derivedStateOf { !shouldHideNavigationBar || navBarVisibilityProgress > 0.01f }
    }
    val isNavBarEffectivelyHidden by remember(shouldHideNavigationBar, navBarVisibilityProgress) {
        derivedStateOf { shouldHideNavigationBar && navBarVisibilityProgress <= 0.01f }
    }

    val hapticsEnabled = settings.hapticsEnabled
    val platformHaptics = LocalHapticFeedback.current
    val rootView = LocalView.current
    LaunchedEffect(hapticsEnabled, rootView) { rootView.rootView?.isHapticFeedbackEnabled = hapticsEnabled }
    val scopedHaptics = remember(platformHaptics, hapticsEnabled) { if (hapticsEnabled) platformHaptics else NoOpHapticFeedback }
    CompositionLocalProvider(
        LocalAppHapticsConfig provides remember(hapticsEnabled) { AppHapticsConfig(enabled = hapticsEnabled) },
        LocalHapticFeedback provides scopedHaptics,
    ) {
    // The pages, recorded with the window colour under them, for the glass bars and menus to sample.
    val windowBackground = MaterialTheme.colorScheme.background
    val paintBackdrop: androidx.compose.ui.graphics.drawscope.ContentDrawScope.() -> Unit = remember(windowBackground) {
        { drawRect(windowBackground); drawContent() }
    }
    val appBackdrop = `in`.melo.ui.glass.backdrop.backdrops.rememberLayerBackdrop(onDraw = paintBackdrop)
    // The whole screen as it shows (the pages, the bars and the player over them), for the menus
    // and sheets in windows of their own: one opened from Now Playing then has the player under
    // its glass, not the pages the player covers. Nothing in this window samples it.
    val screenBackdrop = `in`.melo.ui.glass.backdrop.backdrops.rememberLayerBackdrop(onDraw = paintBackdrop)
    val surfaceStyle = `in`.melo.ui.glass.LocalMeloSurface.current.style
    // A sign-in page is a WebView: recorded into the glass layers, its surface is drawn twice and
    // blinks every other frame. Nothing on those pages is glass, so the recording pauses there.
    val onWebPage = currentRoute == Screen.DiscordLogin.route
    val recordBackdrop = surfaceStyle != `in`.melo.ui.glass.MeloSurfaceStyle.SOLID && `in`.melo.ui.glass.isGlassSupported() &&
        !onWebPage
    // The colour background: soft blobs in the playing song's colour (the colour of the chosen
    // app icon with nothing playing, first launch included) behind every page, inside the layer the glass bars sample,
    // so the bars pick the colour up too. The selected tab takes the same colour.
    val ambientOn = settings.ambientBackground
    val playingArt by remember {
        playerViewModel.stablePlayerState.map { it.currentSong?.albumArtUriString }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    // The playing song's one accent (its cover's most colourful swatch,
    // made readable) is what the app wears. It is the blobs' main colour, with the cover's most
    // common colour beside it, and the selected tab's and the mini player's. With nothing
    // playing the blobs take the app icon's colour and the chrome takes their accent.
    val ambientDark = `in`.melo.ui.theme.LocalMeloDarkTheme.current
    val nowPlayingTint = `in`.melo.ui.theme.rememberNowPlayingTint(playingArt, ambientDark)
    val ambientSongTint = nowPlayingTint.takeIf { ambientOn && playingArt != null }
    val ambientArtColors = if (ambientSongTint != null) {
        `in`.melo.presentation.components.player.rememberMeshPalette(playingArt).colors
    } else null
    val appIconContext = androidx.compose.ui.platform.LocalContext.current
    val appIcon by remember(appIconContext) { `in`.melo.data.AppIcon.live(appIconContext) }.collectAsStateWithLifecycle()
    val ambientPrimary = ambientSongTint ?: (appIcon ?: `in`.melo.data.AppIcon.Default).ambient
    val ambientSecondary = if (ambientSongTint != null && !ambientArtColors.isNullOrEmpty()) {
        ambientArtColors[0]
    } else `in`.melo.presentation.components.ambientCompanion(ambientPrimary)
    val ambientBlobs = if (ambientOn) {
        `in`.melo.presentation.components.rememberAmbientBlobs(
            primary = ambientPrimary,
            secondary = ambientSecondary,
            // Still behind a sign-in page: the WebView would otherwise redraw with every frame of the blobs.
            animate = !`in`.melo.presentation.utils.LocalReducedMotion.current && appInForeground && !onWebPage,
            dark = ambientDark,
        )
    } else null
    // The chrome's accent while the colour background is on in Melo's own colour; while the
    // blobs follow the song they already are its accent, so the chrome takes the song's tint.
    val brandAccent = `in`.melo.presentation.components.rememberAmbientAccent(
        ambientPrimary,
        on = ambientOn && ambientSongTint == null,
        dark = ambientDark,
    )
    CompositionLocalProvider(
        `in`.melo.ui.glass.LocalAppBackdrop provides if (recordBackdrop) appBackdrop else null,
        `in`.melo.ui.glass.LocalPopupBackdrop provides if (recordBackdrop) screenBackdrop else null,
        `in`.melo.presentation.components.LocalAmbientBackground provides ambientOn,
        `in`.melo.presentation.components.LocalAmbientAccent provides if (ambientOn) brandAccent ?: nowPlayingTint else null,
        `in`.melo.ui.theme.LocalNowPlayingTint provides nowPlayingTint,
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize()
            .then(if (recordBackdrop) Modifier.layerBackdrop(screenBackdrop) else Modifier),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (shouldRenderNavigationBar) {
                val currentSongId by remember {
                    playerViewModel.stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged()
                }.collectAsStateWithLifecycle(initialValue = null)
                val showPlayerContentArea = currentSongId != null
                val navBarElevation = 0.dp
                val animatedNavBarCornerRadius = animateDpAsState(
                    targetValue = floatingNavBarCornerRadius,
                    animationSpec = tween(400),
                    label = "NavBarCornerRadius"
                )
                val animatedDefaultTopCornerRadius = animateDpAsState(
                    targetValue = if (showPlayerContentArea && !isMiniPlayerDismissing) 10.dp else floatingNavBarCornerRadius,
                    animationSpec = tween(400),
                    label = "NavBarDefaultTopCornerRadius"
                )
                var componentHeightPx by remember { mutableStateOf(0) }
                val density = LocalDensity.current
                val shadowOverflowPx = remember(navBarElevation, density) { with(density) { (navBarElevation * 8).toPx() } }
                val bottomBarPaddingPx = remember(bottomBarPadding, density) { with(density) { bottomBarPadding.toPx() } }
                val navBarElevationPx = remember(navBarElevation, density) { with(density) { navBarElevation.toPx() } }
                val navBarShapeCache = remember { NavBarShapeCache() }

                Box(
                    modifier = Modifier.fillMaxWidth().height(navBarOccupiedHeight).clipToBounds()
                ) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = bottomBarPadding)
                            .onSizeChanged { componentHeightPx = it.height }
                            .graphicsLayer {
                                val expansionHide = if (showPlayerContentArea) {
                                    playerViewModel.playerContentExpansionFraction.value.coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                val routeHide = (1f - navBarVisibilityProgressState.value).coerceIn(0f, 1f)
                                val hideFraction = maxOf(expansionHide, routeHide)
                                translationY = (componentHeightPx + shadowOverflowPx + bottomBarPaddingPx) * hideFraction
                                alpha = 1f
                            }
                            .height(navBarHeight)
                            .padding(horizontal = horizontalPadding)
                            .graphicsLayer {
                                val fraction = playerViewModel.playerContentExpansionFraction.value
                                val safeFraction = fraction.coerceIn(0f, 1f)
                                val topDp = when {
                                    navBarStyle == NavBarStyle.DEFAULT -> animatedDefaultTopCornerRadius.value
                                    navBarStyle == NavBarStyle.FULL_WIDTH -> lerp(floatingNavBarCornerRadius, 26.dp, safeFraction)
                                    showPlayerContentArea -> if (fraction < 0.2f) {
                                        lerp(floatingNavBarCornerRadius, 26.dp, (fraction / 0.2f).coerceIn(0f, 1f))
                                    } else {
                                        26.dp
                                    }
                                    else -> floatingNavBarCornerRadius
                                }
                                val bottomDp = when (navBarStyle) {
                                    NavBarStyle.FULL_WIDTH -> 0.dp
                                    else -> animatedNavBarCornerRadius.value
                                }
                                shape = navBarShapeCache.get(this, topDp.toPx(), bottomDp.toPx(), useSmoothCorners)
                                clip = true
                                shadowElevation = navBarElevationPx
                            },
                        // Keep the floating capsule inside; the animated parent surface
                        // itself must not paint a second dark card behind it.
                        color = androidx.compose.ui.graphics.Color.Transparent
                    ) {
                        PlayerInternalNavigationBar(
                            navController = navController,
                            navItems = commonNavItems,
                            currentRoute = currentRoute,
                            navBarStyle = navBarStyle,
                            compactMode = navBarCompactMode,
                            bottomBarPadding = bottomBarPadding,
                            onSearchIconDoubleTap = {},
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val containerHeight = this.maxHeight
            val screenHeightPx = remember(containerHeight, density) { with(density) { containerHeight.toPx() } }
            val showPlayerContentInitially by remember {
                playerViewModel.stablePlayerState.map { it.currentSong?.id != null }.distinctUntilChanged()
            }.collectAsStateWithLifecycle(initialValue = false)
            val miniPlayerH = with(density) { MiniPlayerHeight.toPx() }
            val totalSheetHeightWhenContentCollapsedPx = if (showPlayerContentInitially) miniPlayerH else 0f
            val bottomMargin = miniPlayerBottomMargin
            val spacerPx = with(density) { MiniPlayerBottomSpacer.toPx() }
            val bottomMarginPx = with(density) { bottomMargin.toPx() }
            val sheetCollapsedTargetY = calculatePlayerSheetCollapsedTargetY(
                containerHeightPx = screenHeightPx,
                collapsedContentHeightPx = totalSheetHeightWhenContentCollapsedPx,
                bottomMarginPx = bottomMarginPx,
                bottomSpacerPx = spacerPx
            )
            val expansionFractionProvider = remember(playerViewModel.playerContentExpansionFraction) {
                { playerViewModel.playerContentExpansionFraction.value }
            }
            val blurEffectCache = remember { BlurEffectCache() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (disableBlurAllOver) {
                                renderEffect = null
                            } else {
                                val expansion = expansionFractionProvider()
                                val fraction = (expansion * (1f - predictiveBackCollapseFraction)).coerceIn(0f, 1f)
                                val quantizedBlurPx = (fraction * 120f / 2f).roundToInt() * 2f
                                renderEffect = blurEffectCache.get(quantizedBlurPx)
                            }
                        }
                    }
                    .then(if (recordBackdrop) Modifier.layerBackdrop(appBackdrop) else Modifier)
            ) {
                Box(Modifier.fillMaxSize().ambientBlobs(ambientBlobs))
                // Nothing inside the recorded pages samples them: a surface can't blur the layer it is drawn into.
                CompositionLocalProvider(`in`.melo.ui.glass.LocalAppBackdrop provides null) {
                    AppNavigation(
                        model = model,
                        navController = navController,
                        paddingValues = innerPadding,
                        onSearchBarActiveChange = { isSearchBarActive = it },
                    )
                }
            }

            val isExpandedOrExpanding by remember {
                derivedStateOf { playerViewModel.playerContentExpansionFraction.value > 0.01f }
            }
            AnimatedVisibility(
                visible = isExpandedOrExpanding,
                enter = fadeIn(animationSpec = tween(durationMillis = 350)),
                exit = fadeOut(animationSpec = tween(durationMillis = 350)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLowest.copy(
                                alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.35f else 0.6f
                            )
                        )
                        .pointerInput(Unit) {
                            detectTapGestures { playerViewModel.collapsePlayerSheet() }
                        }
                )
            }

            UnifiedPlayerSheetV2(
                playerViewModel = playerViewModel,
                sheetCollapsedTargetY = sheetCollapsedTargetY,
                collapsedStateHorizontalPadding = horizontalPadding,
                hideMiniPlayer = false,
                containerHeight = containerHeight,
                navController = navController,
                isNavBarHidden = isNavBarEffectivelyHidden
            )

            val dismissUndoBarSlice by remember {
                playerViewModel.playerUiState
                    .map { DismissUndoBarSlice(it.showDismissUndoBar, it.undoBarVisibleDuration) }
                    .distinctUntilChanged()
            }.collectAsStateWithLifecycle(initialValue = DismissUndoBarSlice())
            AnimatedVisibility(
                visible = dismissUndoBarSlice.isVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = innerPadding.calculateBottomPadding() + MiniPlayerBottomSpacer)
                    .padding(horizontal = horizontalPadding)
            ) {
                DismissUndoBar(
                    modifier = Modifier.fillMaxWidth().height(MiniPlayerHeight).padding(horizontal = MeloLayout.Space4),
                    onUndo = playerViewModel::undoDismissPlaylist,
                    onClose = playerViewModel::hideDismissUndoBar,
                    durationMillis = dismissUndoBarSlice.durationMillis
                )
            }
        }
    }
    // The first-run screens, over the whole app; the tour's Library step shows the Library tab behind its card.
    `in`.melo.presentation.components.onboarding.OnboardingHost { library ->
        navController.navigateToTopLevelSafely(if (library) Screen.Library.route else Screen.Home.route)
    }
    }
    }
}

private class BlurEffectCache {
    private var lastRadiusPx: Float = Float.NaN
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    fun get(radiusPx: Float): androidx.compose.ui.graphics.RenderEffect? {
        if (radiusPx <= 0f) {
            lastRadiusPx = 0f
            cached = null
            return null
        }
        if (radiusPx != lastRadiusPx) {
            lastRadiusPx = radiusPx
            cached = AndroidRenderEffect
                .createBlurEffect(radiusPx, radiusPx, AndroidShader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
        return cached
    }
}

/**
 * Returns a cached Shape instance for a quantized (top, bottom) radius pair.
 * Because the instance identity is stable while the radii don't move past a
 * sub-pixel threshold, the graphics layer reuses its cached Outline between
 * frames and only re-clips when the radius actually changes.
 */
private class NavBarShapeCache {
    private var lastTopPx: Float = Float.NaN
    private var lastBottomPx: Float = Float.NaN
    private var lastSmooth: Boolean = true
    private var cached: androidx.compose.ui.graphics.Shape = RectangleShape

    fun get(
        density: androidx.compose.ui.unit.Density,
        topPx: Float,
        bottomPx: Float,
        smooth: Boolean
    ): androidx.compose.ui.graphics.Shape {
        if (smooth == lastSmooth &&
            !lastTopPx.isNaN() &&
            kotlin.math.abs(topPx - lastTopPx) < 0.5f &&
            kotlin.math.abs(bottomPx - lastBottomPx) < 0.5f
        ) {
            return cached
        }
        lastTopPx = topPx
        lastBottomPx = bottomPx
        lastSmooth = smooth
        cached = with(density) {
            DynamicSmoothCornerShape(
                useSmoothCorners = smooth,
                topRadius = topPx.toDp(),
                bottomRadius = bottomPx.toDp()
            )
        }
        return cached
    }
}

/**
 * Fixed-radius corner shape. Swaps AbsoluteSmoothCornerShape for a plain
 * RoundedCornerShape when smooth corners are disabled in settings. The radius
 * values are identical in both branches, so the animated radius behavior is
 * unchanged regardless of which delegate is active. The resulting Outline is
 * cached per (size, layoutDirection) so repeated draws are cheap.
 */
private class DynamicSmoothCornerShape(
    private val useSmoothCorners: Boolean,
    private val topRadius: androidx.compose.ui.unit.Dp,
    private val bottomRadius: androidx.compose.ui.unit.Dp
) : androidx.compose.ui.graphics.Shape {

    private var cachedSize: androidx.compose.ui.geometry.Size =
        androidx.compose.ui.geometry.Size.Unspecified
    private var cachedLayoutDirection: androidx.compose.ui.unit.LayoutDirection? = null
    private var cachedOutline: androidx.compose.ui.graphics.Outline? = null

    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): androidx.compose.ui.graphics.Outline {
        cachedOutline?.let {
            if (cachedSize == size && cachedLayoutDirection == layoutDirection) return it
        }

        val delegate: androidx.compose.ui.graphics.Shape = if (useSmoothCorners) {
            AbsoluteSmoothCornerShape(
                cornerRadiusTL = topRadius,
                smoothnessAsPercentTL = 60,
                cornerRadiusTR = topRadius,
                smoothnessAsPercentTR = 60,
                cornerRadiusBL = bottomRadius,
                smoothnessAsPercentBL = 60,
                cornerRadiusBR = bottomRadius,
                smoothnessAsPercentBR = 60
            )
        } else {
            RoundedCornerShape(
                topStart = topRadius,
                topEnd = topRadius,
                bottomEnd = bottomRadius,
                bottomStart = bottomRadius
            )
        }

        return delegate.createOutline(size, layoutDirection, density).also {
            cachedSize = size
            cachedLayoutDirection = layoutDirection
            cachedOutline = it
        }
    }
}
