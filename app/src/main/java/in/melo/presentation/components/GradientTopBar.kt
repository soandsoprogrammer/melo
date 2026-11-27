package `in`.melo.presentation.components

import `in`.melo.data.TourTarget
import `in`.melo.presentation.components.onboarding.tourAnchor
import `in`.melo.ui.glass.meloMenuColors
import `in`.melo.ui.glass.meloPopupPanel
import `in`.melo.ui.glass.PopupWindowEffects
import `in`.melo.ui.glass.rememberPopupFrost
import `in`.melo.presentation.icons.MeloIcons
import `in`.melo.presentation.icons.meloPainter
import `in`.melo.ui.theme.MeloAlpha
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.tween

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults.topAppBarColors
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import `in`.melo.R
import `in`.melo.data.SpotifyUserProfile
import `in`.melo.ui.theme.GoogleSansRounded
import `in`.melo.ui.theme.MeloStatusBarStyle
import androidx.compose.ui.res.stringResource
import `in`.melo.ui.theme.MeloLayout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreGradientTopBar(
    title: String,
    startColor: Color,
    endColor: Color,
    contentColor: Color,
    scrollBehavior: TopAppBarScrollBehavior,
    onNavigationIconClick: () -> Unit,
) {
    val gradientBrush = remember(startColor, endColor) {
        Brush.verticalGradient(colors = listOf(startColor, endColor))
    }

    MeloStatusBarStyle(color = startColor)

    LargeTopAppBar(
        scrollBehavior = scrollBehavior,
        title = {
            Text(
                modifier = Modifier.padding(start = MeloLayout.Space2),
                text = title,
                color = contentColor,
                fontFamily = GoogleSansRounded
            )
        },
        expandedHeight = 160.dp,
        modifier = Modifier.background(brush = gradientBrush),
        navigationIcon = {
            IconButton(
                modifier = Modifier.padding(start = MeloLayout.Space3),
                onClick = onNavigationIconClick,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = contentColor
                )
            ) {
                Icon(
                    imageVector = MeloIcons.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = startColor
                )
            }
        },
        colors = topAppBarColors(
            containerColor = Color.Transparent, // Background is handled by the gradient brush
            scrolledContainerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer, // Or a color that contrasts well with your typical gradient
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer // Same as title
        )
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeGradientTopBar(
    onSettingsClick: () -> Unit,
    homeDialSettingsContent: (@Composable () -> Unit)? = null,
    onReorderPinsClick: (() -> Unit)? = null,
    onHistoryClick: () -> Unit,
    profileImageUrl: String? = null,
    profileName: String? = null,
    profileHandle: String? = null,
    spotifyConnected: Boolean = false,
    spotifyProfile: SpotifyUserProfile? = null,
    onProfileClick: (() -> Unit)? = null,
    isScrolled: Boolean = false,
    /** 0 to 1 as the page scrolls: the large title shrinks into the bar. */
    titleFold: () -> Float = { 0f },
    transparentContainer: Boolean = false,
    refreshing: Boolean = false,
    pullFraction: () -> Float = { 0f },
    animateLogo: Boolean = true,
    settingsButtonDirect: Boolean = false,
    title: String? = null,
    /** Shows Melo's M as the page title instead of [title], which is then read out by screen readers. */
    markAsTitle: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    var menuExpanded by remember { mutableStateOf(false) }
    var dialSettingsExpanded by remember { mutableStateOf(false) }
    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    MeloStatusBarStyle(color = colors.background)
    Box(Modifier.fillMaxWidth()) {
        val topBarContainerColor = if (transparentContainer) Color.Transparent else colors.background
        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    // The title and its mark shrink into the bar together as the page scrolls.
                    modifier = Modifier.graphicsLayer {
                        val s = 1f - 0.22f * titleFold().coerceIn(0f, 1f)
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    },
                ) {
                    if (title != null) {
                        // A large page title, as every tab has; the logo only appears to show a refresh.
                        // A new page's name rises in as the old one lifts away.
                        androidx.compose.animation.AnimatedContent(
                            targetState = title.takeUnless { markAsTitle },
                            transitionSpec = {
                                (fadeIn(tween(220, delayMillis = 60)) +
                                    androidx.compose.animation.slideInVertically(tween(280)) { it / 3 }) togetherWith
                                    (androidx.compose.animation.fadeOut(tween(160)) +
                                        androidx.compose.animation.slideOutVertically(tween(220)) { -it / 3 }) using
                                    androidx.compose.animation.SizeTransform(clip = false) { _, _ -> tween(280) }
                            },
                            label = "homePageTitle",
                        ) { shown ->
                            if (shown == null) {
                                MeloMark(refreshing, pullFraction, animateLogo, height = 38.dp, contentDescription = title)
                            } else {
                                Text(
                                    text = shown,
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    modifier = Modifier.semantics { heading() },
                                )
                            }
                        }
                        // Beside a written title, Melo's M stays small and doubles as the
                        // pull-to-refresh indicator.
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !markAsTitle,
                            enter = fadeIn(tween(220, delayMillis = 60)) + androidx.compose.animation.expandHorizontally(tween(280)),
                            exit = androidx.compose.animation.fadeOut(tween(160)) + androidx.compose.animation.shrinkHorizontally(tween(280)),
                        ) {
                            MeloMark(refreshing, pullFraction, animateLogo, height = 30.dp)
                        }
                    } else {
                        MeloMark(refreshing, pullFraction, animateLogo, height = 34.dp)
                        MeloWordmark()
                    }
                }
            },
            // The actions are drawn in the Box overlay below so their bounds don't participate in
            // the title's layout and opening a panel can never change the app-bar measurement.
            actions = { Spacer(Modifier.width(0.dp)) },
            colors = topAppBarColors(
                containerColor = topBarContainerColor,
                scrolledContainerColor = topBarContainerColor,
            ),
        )
        MeloTopActionsCapsule(
            modifier = Modifier.align(Alignment.CenterEnd).offset(y = statusBarInset / 2).zIndex(1f)
                .padding(end = 16.dp),
        ) {
            // While a newer Melo is waiting, its arrow takes the history button's place and brings
            // the update back up; history is still a tap away on the speed dial.
            val context = androidx.compose.ui.platform.LocalContext.current
            val updater = remember(context) { `in`.melo.update.AppUpdater.get(context) }
            val pendingUpdate by updater.pendingRelease.collectAsState()
            androidx.compose.animation.Crossfade(pendingUpdate != null, Modifier.tourAnchor(TourTarget.HISTORY), label = "historyOrUpdate") { update ->
                if (update) {
                    IconButton(onClick = { updater.reopen() }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            MeloIcons.Downloaded,
                            stringResource(R.string.update_available),
                            tint = colors.primary,
                        )
                    }
                } else {
                    IconButton(onClick = onHistoryClick, modifier = Modifier.size(48.dp)) {
                        Icon(MeloIcons.History, stringResource(R.string.history_title))
                    }
                }
            }
            Box(Modifier.tourAnchor(TourTarget.HOME_MENU)) {
                IconButton(
                    onClick = {
                        if (settingsButtonDirect) onSettingsClick()
                        else if (homeDialSettingsContent != null) dialSettingsExpanded = true
                        else menuExpanded = true
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    if (settingsButtonDirect) {
                        Icon(meloPainter(R.drawable.rounded_settings_24), stringResource(R.string.common_settings))
                    } else {
                        Icon(MeloIcons.MoreVert, stringResource(R.string.player_cd_more_options))
                    }
                }
                if (homeDialSettingsContent == null && !settingsButtonDirect) {
                    MeloMenu(expanded = menuExpanded, onDismiss = { menuExpanded = false }) {
                        MeloMenuItem(
                            label = stringResource(R.string.common_settings),
                            icon = { Icon(meloPainter(R.drawable.rounded_settings_24), contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onSettingsClick()
                            },
                        )
                    }
                }
            }
            if (onProfileClick != null) Box(Modifier.tourAnchor(TourTarget.PROFILE)) {
                MeloProfileAccountButton(
                    profileImageUrl = profileImageUrl,
                    profileName = profileName,
                    profileHandle = profileHandle,
                    spotifyConnected = spotifyConnected,
                    spotifyProfile = spotifyProfile,
                    onProfileClick = onProfileClick,
                    onSettingsClick = onSettingsClick,
                )
            }
        }
    }
    if (dialSettingsExpanded && homeDialSettingsContent != null) {
        HomeDialSettingsOverlay(
            content = homeDialSettingsContent,
            onDismiss = { dialSettingsExpanded = false },
            onReorderPinsClick = onReorderPinsClick?.let { callback ->
                {
                    dialSettingsExpanded = false
                    callback()
                }
            },
        )
    }
}

@Composable
fun MeloTopActionsCapsule(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.meloPageGlass(CircleShape),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun MeloProfileAccountButton(
    profileImageUrl: String?,
    profileName: String? = null,
    profileHandle: String? = null,
    spotifyConnected: Boolean = false,
    spotifyProfile: SpotifyUserProfile? = null,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    var accountMenuExpanded by remember { mutableStateOf(false) }
    val accountAvatarUrl = profileImageUrl?.takeIf(String::isNotBlank) ?: spotifyProfile?.imageUrl
    IconButton(onClick = { accountMenuExpanded = true }, modifier = Modifier.size(48.dp)) {
        if (!accountAvatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = accountAvatarUrl,
                contentDescription = stringResource(R.string.settings_accounts_title),
                modifier = Modifier.size(34.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                MeloIcons.AccountCircle,
                contentDescription = stringResource(R.string.settings_accounts_title),
                modifier = Modifier.size(26.dp),
            )
        }
    }
    if (accountMenuExpanded) {
        HomeAccountMenuOverlay(
            profileName = profileName,
            profileHandle = profileHandle,
            profileImageUrl = profileImageUrl,
            spotifyConnected = spotifyConnected,
            spotifyProfile = spotifyProfile,
            onDismiss = { accountMenuExpanded = false },
            onManageAccountsClick = {
                accountMenuExpanded = false
                onProfileClick()
            },
            onSettingsClick = {
                accountMenuExpanded = false
                onSettingsClick()
            },
        )
    }
}

@Composable
private fun HomeDialSettingsOverlay(
    content: @Composable () -> Unit,
    onDismiss: () -> Unit,
    onReorderPinsClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val frost = rememberPopupFrost()
        PopupWindowEffects(frost, 14.dp)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val panelTop = statusInset + 10.dp
            val availableHeight = maxHeight - panelTop - navigationInset - 16.dp
            // Let the home-dial controls use the screen below the system bars so the whole menu
            // fits without scrolling on normal phone layouts. Keep the scroll fallback for short windows.
            val panelMaxHeight = availableHeight.coerceAtLeast(220.dp)
            val panelWidth = minOf(maxWidth * 0.72f, maxWidth - 24.dp, 288.dp)
            val shape = MeloLayout.PanelShape
            val noRipple = remember { MutableInteractionSource() }
            Box(
                Modifier.matchParentSize()
                    .background(`in`.melo.ui.glass.menuScrimColor())
                    .clickable(interactionSource = noRipple, indication = null, onClick = onDismiss),
            )
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(160)) + scaleIn(
                    animationSpec = tween(180),
                    initialScale = 0.96f,
                    transformOrigin = TransformOrigin(1f, 0f),
                ),
                modifier = Modifier.align(Alignment.TopEnd).zIndex(2f),
            ) {
                Surface(
                    modifier = Modifier.padding(top = panelTop, end = 12.dp)
                        .width(panelWidth)
                        .heightIn(max = panelMaxHeight)
                        .meloPopupPanel(shape, frost),
                    shape = shape,
                    color = Color.Transparent,
                    contentColor = meloMenuColors().label,
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(
                                colors.onSurface.copy(alpha = if (LocalSurfaceGradients.current) 0.025f else 0f),
                                Color.Transparent,
                            )))
                            .verticalScroll(rememberScrollState())
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    ) {
                        content()
                        if (onReorderPinsClick != null) {
                            HorizontalDivider(
                                Modifier.padding(horizontal = 16.dp, vertical = MeloLayout.Space1),
                                color = colors.onSurface.copy(alpha = MeloAlpha.Divider),
                            )
                            OverlayPanelAction(
                                label = stringResource(R.string.home_dial_reorder_pins),
                                icon = { Icon(MeloIcons.SwapVert, contentDescription = null) },
                                onClick = onReorderPinsClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeAccountMenuOverlay(
    profileName: String?,
    profileHandle: String?,
    profileImageUrl: String?,
    spotifyConnected: Boolean,
    spotifyProfile: SpotifyUserProfile?,
    onDismiss: () -> Unit,
    onManageAccountsClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val frost = rememberPopupFrost()
        PopupWindowEffects(frost, 14.dp)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val panelTop = statusInset + 10.dp
            val availableHeight = maxHeight - panelTop - navigationInset - 16.dp
            val panelMaxHeight = minOf(maxHeight * 0.72f, availableHeight).coerceAtLeast(250.dp)
            val panelWidth = (maxWidth - 24.dp).coerceAtLeast(0.dp)
            val shape = MeloLayout.PanelShape
            val noRipple = remember { MutableInteractionSource() }
            Box(
                Modifier.matchParentSize()
                    .background(`in`.melo.ui.glass.menuScrimColor())
                    .clickable(interactionSource = noRipple, indication = null, onClick = onDismiss),
            )
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(160)) + scaleIn(
                    animationSpec = tween(180),
                    initialScale = 0.96f,
                    transformOrigin = TransformOrigin(1f, 0f),
                ),
                modifier = Modifier.align(Alignment.TopEnd).zIndex(2f),
            ) {
                Surface(
                    modifier = Modifier.padding(top = panelTop, end = 12.dp)
                        .width(panelWidth)
                        .heightIn(max = panelMaxHeight)
                        .meloPopupPanel(shape, frost),
                    shape = shape,
                    color = Color.Transparent,
                    contentColor = meloMenuColors().label,
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(
                                colors.onSurface.copy(alpha = if (LocalSurfaceGradients.current) 0.025f else 0f),
                                Color.Transparent,
                            )))
                            .verticalScroll(rememberScrollState())
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    ) {
                        Text(
                            text = stringResource(R.string.settings_accounts_title),
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp)
                                .semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        // One row shape for the accounts and the actions alike, as an iOS menu:
                        // the same height, the same leading slot and the same text inset.
                        AccountMenuRow(
                            title = stringResource(R.string.search_source_spotify),
                            detail = spotifyProfile?.displayName?.takeIf(String::isNotBlank)
                                ?: stringResource(if (spotifyConnected) R.string.home_account_connected else R.string.home_account_not_connected),
                            leading = { AccountServiceMark(spotifyProfile?.imageUrl.takeIf { spotifyConnected }, R.drawable.ic_source_spotify) },
                            onClick = onManageAccountsClick,
                        )
                        HorizontalDivider(
                            Modifier.padding(start = 20.dp, end = 20.dp, top = MeloLayout.Space1, bottom = MeloLayout.Space1),
                            color = colors.onSurface.copy(alpha = MeloAlpha.Divider),
                        )
                        AccountMenuRow(
                            title = stringResource(R.string.home_account_manage),
                            leading = { Icon(MeloIcons.AccountCircle, contentDescription = null, modifier = Modifier.size(24.dp)) },
                            onClick = onManageAccountsClick,
                        )
                        AccountMenuRow(
                            title = stringResource(R.string.common_settings),
                            leading = { Icon(meloPainter(R.drawable.rounded_settings_24), contentDescription = null, modifier = Modifier.size(24.dp)) },
                            onClick = onSettingsClick,
                        )
                        Spacer(Modifier.height(MeloLayout.Space2))
                    }
                }
            }
        }
    }
}

/**
 * A row of the account menu: a [leading] mark in a 32dp slot, the title, and a quieter
 * [detail] under it when there is one. Every row is the same height whatever it holds.
 */
@Composable
private fun AccountMenuRow(title: String, leading: @Composable () -> Unit, onClick: () -> Unit, detail: String? = null) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (detail != null) Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** The account's picture when it has one, else the service's own mark, both 32dp round. */
@Composable
private fun AccountServiceMark(imageUrl: String?, serviceIcon: Int) {
    if (!imageUrl.isNullOrBlank()) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = Modifier.size(32.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Icon(meloPainter(serviceIcon), null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OverlayPanelAction(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { icon() }
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(start = MeloLayout.Space3),
            textAlign = TextAlign.Start,
            maxLines = 1,
        )
    }
}
