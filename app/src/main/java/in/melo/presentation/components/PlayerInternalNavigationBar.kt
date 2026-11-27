package `in`.melo.presentation.components

import `in`.melo.data.TourTarget
import `in`.melo.presentation.components.onboarding.tourAnchor
import `in`.melo.presentation.components.LocalAmbientAccent
import `in`.melo.presentation.icons.meloPainter
import `in`.melo.ui.theme.MeloAlpha
import `in`.melo.ui.theme.MeloLayout
import `in`.melo.presentation.navigation.navigateToTopLevelSafely

import android.os.SystemClock
import `in`.melo.ui.glass.FlatSelectionPill
import `in`.melo.ui.glass.GlassPillMotion
import `in`.melo.ui.glass.GlassSelectionPill
import `in`.melo.ui.glass.LocalAppBackdrop
import `in`.melo.ui.glass.LocalMeloSurface
import `in`.melo.ui.glass.MeloSurfaceStyle
import `in`.melo.ui.glass.PILL_GROW_HEIGHT
import `in`.melo.ui.glass.fitInsideBar
import `in`.melo.ui.glass.glassIndicatorColor
import `in`.melo.ui.glass.meloGlass
import `in`.melo.ui.glass.pillPlacement
import `in`.melo.ui.glass.backdrop.backdrops.layerBackdrop
import `in`.melo.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import `in`.melo.ui.theme.MeloMotion
import `in`.melo.ui.theme.controlFill
import `in`.melo.ui.theme.label
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import `in`.melo.BottomNavItem
import `in`.melo.data.preferences.NavBarStyle
import `in`.melo.presentation.components.scoped.CustomNavigationBarItem
import `in`.melo.presentation.navigation.Screen
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val NavBarContentHeight = 80.dp // Altura del contenido de la barra de navegación
internal val NavBarCompactContentHeight = 64.dp
internal val NavBarContentHeightFullWidth = NavBarContentHeight // Altura del contenido de la barra de navegación en modo completo
internal val FloatingNavBarCornerRadius = 32.dp
private val MainScreenBottomGradientExtraHeight = MiniPlayerHeight + MiniPlayerBottomSpacer + 8.dp
// Some OEM freeform/floating-window modes can report a bottom inset close to the whole window height.
internal val MaxNavigationBarBottomInset = 96.dp

internal fun sanitizeNavigationBarBottomInset(systemNavBarInset: Dp): Dp {
    if (!systemNavBarInset.value.isFinite()) return 0.dp
    return systemNavBarInset.coerceIn(0.dp, MaxNavigationBarBottomInset)
}

internal fun calculatePlayerSheetCollapsedTargetY(
    containerHeightPx: Float,
    collapsedContentHeightPx: Float,
    bottomMarginPx: Float,
    bottomSpacerPx: Float
): Float {
    val safeContainerHeightPx = containerHeightPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeCollapsedContentHeightPx = collapsedContentHeightPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeBottomMarginPx = bottomMarginPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeBottomSpacerPx = bottomSpacerPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val maxTargetY = (safeContainerHeightPx - safeCollapsedContentHeightPx).coerceAtLeast(0f)

    return (safeContainerHeightPx - safeCollapsedContentHeightPx - safeBottomMarginPx - safeBottomSpacerPx)
        .coerceIn(0f, maxTargetY)
}

internal fun resolveNavBarContentHeight(compactMode: Boolean): Dp =
    if (compactMode) NavBarCompactContentHeight else NavBarContentHeight

internal fun resolveMainScreenBottomGradientHeight(compactMode: Boolean): Dp =
    resolveNavBarContentHeight(compactMode) + MainScreenBottomGradientExtraHeight

internal fun resolveNavBarSurfaceHeight(
    navBarStyle: String,
    systemNavBarInset: Dp,
    compactMode: Boolean
): Dp {
    val contentHeight = resolveNavBarContentHeight(compactMode)
    return if (navBarStyle == NavBarStyle.FULL_WIDTH) {
        contentHeight + systemNavBarInset
    } else {
        contentHeight
    }
}

internal fun resolveNavBarOccupiedHeight(
    systemNavBarInset: Dp,
    compactMode: Boolean
): Dp = resolveNavBarContentHeight(compactMode) + systemNavBarInset

@Composable
private fun PlayerInternalNavigationItemsRow(
    navController: NavHostController,
    navItems: ImmutableList<BottomNavItem>,
    currentRoute: String?,
    modifier: Modifier = Modifier,
    navBarStyle: String,
    compactMode: Boolean,
    bottomBarPadding: Dp,
    onSearchIconDoubleTap: () -> Unit
) {
    val navBarInsetPadding = sanitizeNavigationBarBottomInset(
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    )
    // Maintain invariant: bottomBarPadding + innerRowPadding = the sanitized system nav bar inset.
    // This prevents nav items from appearing behind the gesture bar during style transitions,
    // e.g. FULL_WIDTH→DEFAULT where bottomBarPadding starts at 0 and animates to systemNavBarInset.
    val innerRowPadding = (navBarInsetPadding - bottomBarPadding).coerceAtLeast(0.dp)
    val latestCurrentRoute by rememberUpdatedState(currentRoute)
    val latestOnSearchIconDoubleTap by rememberUpdatedState(onSearchIconDoubleTap)
    val latestNavigationEnabled by rememberUpdatedState(currentRoute != null)

    val rowModifier = if (navBarStyle == NavBarStyle.FULL_WIDTH) {
        modifier
            .fillMaxWidth()
            .padding(top = 0.dp, bottom = innerRowPadding, start = 12.dp, end = 12.dp)
    } else {
        modifier
            .padding(start = MeloLayout.Space3, end = MeloLayout.Space3, bottom = innerRowPadding)
            .fillMaxWidth()
    }
    // One shared pill sits behind the tabs and travels from the old tab to the new one. In the
    // Glass style it lifts off the bar as a lens while it moves (see GlassSelectionPill) and
    // lands flat; Frost and Solid carry the same motion with a flat pill.
    val selectedIndex = navItems.indexOfFirst { currentRoute != null && it.screen.route == currentRoute }
    val pillScope = rememberCoroutineScope()
    val pill = remember { GlassPillMotion(pillScope, selectedIndex.coerceAtLeast(0)) }
    val page = LocalAppBackdrop.current
    val glassPill = page != null && LocalMeloSurface.current.style == MeloSurfaceStyle.GLASS
    val barGlass = rememberLayerBackdrop()
    val tabRow = rememberLayerBackdrop()
    val pillColor = if (page != null) glassIndicatorColor() else MaterialTheme.colorScheme.controlFill
    val itemCount = navItems.size.coerceAtLeast(1)
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptics = LocalHapticFeedback.current
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    val tabStepPx = if (rowSize.width > 0) rowSize.width.toFloat() / itemCount else 0f
    pill.stepDp = tabStepPx / density.density
    val insetPx = with(density) { TabPillInset.toPx() }
    val rowHeightPx = rowSize.height.toFloat()
    val restSize = Size(tabStepPx, rowHeightPx)
    val barHeightPx = rowHeightPx + insetPx * 2
    val liftedSize = when {
        rowHeightPx <= 0f -> restSize
        glassPill -> {
            val lifted = barHeightPx + with(density) { PILL_GROW_HEIGHT.toPx() }
            Size(lifted * tabStepPx / rowHeightPx, lifted)
        }
        else -> Size(tabStepPx + insetPx * 2, barHeightPx)
    }
    val pillCenter: (Float) -> Offset = { position ->
        val x = position * tabStepPx + tabStepPx / 2f
        Offset(if (isRtl) rowSize.width - x else x, rowHeightPx / 2f)
    }
    val flatPillSize: () -> Size = {
        val live = pill.liveSize(restSize, liftedSize)
        if (glassPill) live
        else fitInsideBar(live, pillCenter(pill.position).x, -insetPx, rowSize.width + insetPx, barHeightPx)
    }
    val showPill = selectedIndex >= 0 && tabStepPx > 0f
    var lastSelectedIndex by remember { mutableIntStateOf(selectedIndex) }
    var lastTickTab by remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            if (lastSelectedIndex < 0) pill.snapTo(selectedIndex) else pill.animateTo(selectedIndex)
        }
        lastSelectedIndex = selectedIndex
        lastTickTab = selectedIndex
    }
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    Box(rowModifier, contentAlignment = Alignment.Center) {
    // Unclipped: the lifted lens stands past the bar's edges, so it is drawn over the bar.
    Box(
        Modifier
            .then(
                if (navBarStyle == NavBarStyle.FULL_WIDTH) Modifier
                else Modifier.widthIn(max = MeloLayout.FloatingBarMaxWidth)
            )
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (page != null) Modifier.meloGlass(CircleShape, exportedBackdrop = if (glassPill) barGlass else null)
                else Modifier.meloBarMaterial(CircleShape)
            )
            .clip(CircleShape)
            .padding(TabPillInset)
    ) {
    if (showPill && (!glassPill || pill.isFlat)) {
        FlatSelectionPill(
            color = pillColor,
            modifier = Modifier.matchParentSize().pillPlacement(center = { pillCenter(pill.position) }, size = flatPillSize),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { rowSize = it }
            .then(if (glassPill) Modifier.layerBackdrop(tabRow) else Modifier)
            // Drag along the bar to carry the pill across tabs, ticking at each one.
            .pointerInput(itemCount, tabStepPx, isRtl) {
                if (tabStepPx <= 0f) return@pointerInput
                val direction = if (isRtl) -1f else 1f
                var totalDrag = 0f
                var from = 0
                detectHorizontalDragGestures(
                    onDragStart = {
                        from = currentSelectedIndex
                        totalDrag = 0f
                        if (from >= 0) pill.startDrag()
                    },
                    onDragCancel = { if (from >= 0) pill.release(from) },
                    onDragEnd = {
                        if (from >= 0) {
                            val ratio = totalDrag / tabStepPx
                            val shift = when {
                                ratio > 0.35f -> maxOf(1, ratio.roundToInt())
                                ratio < -0.35f -> minOf(-1, ratio.roundToInt())
                                else -> 0
                            }
                            val next = (from + shift).coerceIn(0, navItems.lastIndex)
                            pill.release(next)
                            if (next != from && latestNavigationEnabled) {
                                navController.navigateToTopLevelSafely(navItems[next].screen.route)
                            }
                        }
                    },
                    onHorizontalDrag = { _, delta ->
                        if (from >= 0) {
                            totalDrag += delta * direction
                            val offset = when {
                                totalDrag > 0 && from == navItems.lastIndex -> totalDrag * 0.25f
                                totalDrag < 0 && from == 0 -> totalDrag * 0.25f
                                else -> totalDrag
                            }
                            pill.dragTo(from + offset / tabStepPx)
                            val near = (from + offset / tabStepPx).coerceIn(0f, navItems.lastIndex.toFloat()).roundToInt()
                            if (near != lastTickTab) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                lastTickTab = near
                            }
                        }
                    },
                )
            }
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val scope = rememberCoroutineScope()
        var lastSearchTapTimestamp by remember { mutableStateOf(0L) }
        navItems.forEach { item ->
            val isSelected = currentRoute != null && currentRoute == item.screen.route
            val selectedColor = pageAccent()
            val unselectedColor = MaterialTheme.colorScheme.label

            val iconPainterResId = if (isSelected && item.selectedIconResId != null && item.selectedIconResId != 0) {
                item.selectedIconResId
            } else {
                item.iconResId
            }
            val localizedLabel = stringResource(id = item.labelResId)
            val iconLambda: @Composable () -> Unit = remember(iconPainterResId, localizedLabel) {
                {
                    Icon(
                        painter = meloPainter(id = iconPainterResId),
                        contentDescription = localizedLabel
                    )
                }
            }
            val selectedIconLambda: @Composable () -> Unit = remember(iconPainterResId, localizedLabel) {
                {
                    Icon(
                        painter = meloPainter(id = iconPainterResId),
                        contentDescription = localizedLabel
                    )
                }
            }
            val labelLambda: (@Composable () -> Unit)? = if (compactMode) {
                null
            } else {
                remember(localizedLabel) {
                    { Text(localizedLabel) }
                }
            }
            val onClickLambda: () -> Unit = remember(item.screen.route, navController, scope) {
                click@{
                    if (!latestNavigationEnabled) {
                        lastSearchTapTimestamp = 0L
                        return@click
                    }

                    val itemRoute = item.screen.route
                    val isSearchTab = itemRoute == Screen.Search.route
                    val isAlreadySelected = latestCurrentRoute == itemRoute

                    if (isSearchTab) {
                        val now = SystemClock.elapsedRealtime()
                        val isDoubleTap = now - lastSearchTapTimestamp <= 350L
                        lastSearchTapTimestamp = now

                        if (!isAlreadySelected) {
                            if (!navController.navigateToTopLevelSafely(itemRoute)) {
                                lastSearchTapTimestamp = 0L
                                return@click
                            }
                        }

                        if (isDoubleTap) {
                            lastSearchTapTimestamp = 0L
                            if (isAlreadySelected) {
                                latestOnSearchIconDoubleTap()
                            } else {
                                scope.launch {
                                    delay(160L)
                                    latestOnSearchIconDoubleTap()
                                }
                            }
                        }
                    } else if (!isAlreadySelected) {
                        lastSearchTapTimestamp = 0L
                        navController.navigateToTopLevelSafely(itemRoute)
                    } else {
                        lastSearchTapTimestamp = 0L
                    }
                }
            }
            CustomNavigationBarItem(
                modifier = Modifier.weight(1f).then(
                    if (item.screen == Screen.Library) Modifier.tourAnchor(TourTarget.LIBRARY_TAB) else Modifier
                ),
                selected = isSelected,
                onClick = onClickLambda,
                enabled = currentRoute != null,
                compactMode = compactMode,
                icon = iconLambda,
                selectedIcon = selectedIconLambda,
                label = labelLambda,
                contentDescription = localizedLabel,
                alwaysShowLabel = true,
                selectedIconColor = selectedColor,
                unselectedIconColor = unselectedColor,
                selectedTextColor = selectedColor,
                unselectedTextColor = unselectedColor,
            )
        }
    }
    }
    if (glassPill && showPill && !pill.isFlat) {
        GlassSelectionPill(
            motion = pill,
            fillColor = pillColor,
            barGlass = barGlass,
            tabRow = tabRow,
            modifier = Modifier.matchParentSize().pillPlacement(
                center = { pillCenter(pill.position) + Offset(insetPx, insetPx) },
                size = { pill.liveSize(restSize, liftedSize) },
            ),
        )
    }
    }
    }
}

/** How far the selection pill sits in from the bar's own edge. */
private val TabPillInset = 4.dp

@Composable
fun PlayerInternalNavigationBar(
    navController: NavHostController,
    navItems: ImmutableList<BottomNavItem>,
    currentRoute: String?,
    modifier: Modifier = Modifier,
    navBarStyle: String,
    compactMode: Boolean,
    bottomBarPadding: Dp = 0.dp,
    onSearchIconDoubleTap: () -> Unit = {}
) {
    PlayerInternalNavigationItemsRow(
        navController = navController,
        navItems = navItems,
        currentRoute = currentRoute,
        navBarStyle = navBarStyle,
        compactMode = compactMode,
        bottomBarPadding = bottomBarPadding,
        onSearchIconDoubleTap = onSearchIconDoubleTap,
        modifier = modifier
    )
}
