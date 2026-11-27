@file:kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package `in`.melo.presentation.components

import `in`.melo.presentation.icons.MeloIcons
import `in`.melo.ui.theme.MeloAlpha
import `in`.melo.ui.theme.MeloLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.size.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import `in`.melo.ui.theme.artworkOutline
import `in`.melo.ui.theme.label
import `in`.melo.ui.theme.secondaryLabel
import `in`.melo.R
import `in`.melo.data.model.Song
import `in`.melo.ui.theme.GoogleSansRounded

internal val LocalMaterialTheme = compositionLocalOf<ColorScheme> { error("No ColorScheme provided") }

val MiniPlayerHeight = 56.dp

/**
 * The mini player's corners: a rounded rectangle, the art's corner plus the row's vertical
 * padding so the two curves run parallel.
 */
val MiniPlayerCorner = 16.dp
const val ANIMATION_DURATION_MS = 255
val MiniPlayerBottomSpacer = 8.dp

@Composable
fun getNavigationBarHeight(): Dp {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    return sanitizeNavigationBarBottomInset(insets.calculateBottomPadding())
}

@Composable
internal fun MiniPlayerContentInternal(
    song: Song,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    isCastConnecting: Boolean,
    isPreparingPlayback: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    canScroll: Boolean = true,
    coverMix: Boolean = false,
) {
    // The slim bar above the tabs: artwork, title over artist, then the transport as
    // plain glyphs on the neutral bar material (no tinted circles), as Apple Music's is.
    val hapticFeedback = LocalHapticFeedback.current
    val controlsEnabled = !isCastConnecting && !isPreparingPlayback
    val showBuffering = isBuffering || isPreparingPlayback
    val colors = MaterialTheme.colorScheme
    val ink = colors.label
    val barColor = miniPlayerColor()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            .padding(horizontal = MiniRowPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val albumArtModel = song.albumArtUriString?.takeIf { it.isNotBlank() }
        // The cover on screen before this track: with cover mix it stays under the new one
        // while that dissolves in. Read and replaced while composing, so no frame lacks it.
        val lastCover = remember { arrayOfNulls<String>(1).also { it[0] = albumArtModel } }
        val outgoing = remember(song.id) {
            lastCover[0].takeIf { coverMix && it != albumArtModel }.also { lastCover[0] = albumArtModel }
        }
        val mix = remember(song.id) { Animatable(if (outgoing != null) 0f else 1f) }
        LaunchedEffect(mix) { mix.animateTo(1f, tween(CoverMixTiming.DURATION_MS, easing = LinearEasing)) }
        val mixing by remember(mix) { derivedStateOf { mix.value < 1f } }
        val mixPattern = remember(song.id) { song.id.hashCode().toLong() }
        val reducedMotion = `in`.melo.presentation.utils.LocalReducedMotion.current
        Box(contentAlignment = Alignment.Center) {
            if (mixing && outgoing != null) {
                SmartImage(
                    model = outgoing,
                    contentDescription = null,
                    shape = MiniArtShape,
                    targetSize = Size(150, 150),
                    modifier = Modifier.size(MiniArtSize).artworkOutline(MiniArtShape),
                )
            }
            key(song.id) {
                SmartImage(
                    model = albumArtModel,
                    contentDescription = stringResource(R.string.melo_cover_of, song.title),
                    shape = MiniArtShape,
                    targetSize = Size(150, 150),
                    modifier = Modifier
                        .size(MiniArtSize)
                        .then(
                            if (outgoing == null) Modifier
                            else Modifier.coverMixReveal({ CoverMixTiming.reveal(mix.value) }, mixPattern, reducedMotion)
                        )
                        .artworkOutline(MiniArtShape),
                    placeholderModel = if (albumArtModel?.startsWith("telegram_art") == true) {
                        "$albumArtModel?quality=thumb"
                    } else null
                )
            }
            if (isCastConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = ink
                )
            }
        }
        Spacer(modifier = Modifier.width(MiniArtGap))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            val titleStyle = MaterialTheme.typography.titleMedium.copy(color = ink)
            val artistStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.secondaryLabel)

            AutoScrollingText(
                text = when {
                    isCastConnecting -> "Connecting to device…"
                    isPreparingPlayback -> "Preparing playback…"
                    else -> song.title
                },
                style = titleStyle,
                gradientEdgeColor = barColor,
                canScroll = canScroll
            )
            AutoScrollingText(
                text = if (isPreparingPlayback) "Loading audio…" else song.displayArtist,
                style = artistStyle,
                gradientEdgeColor = barColor,
                canScroll = canScroll
            )
        }

        MiniPlayerGlyph(
            enabled = controlsEnabled,
            onClick = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onPrevious()
            },
        ) {
            Icon(MeloIcons.SkipPrevious, stringResource(R.string.melo_previous), Modifier.size(MiniSkipIconSize), tint = ink)
        }
        Spacer(Modifier.width(MiniTransportGap))
        MiniPlayerGlyph(
            enabled = controlsEnabled,
            onClick = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onPlayPause()
            },
        ) {
            if (showBuffering) {
                LoadingIndicator(modifier = Modifier.size(MiniPlayIconSize), color = ink)
            } else {
                Icon(
                    imageVector = if (isPlaying) MeloIcons.Pause else MeloIcons.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.melo_pause else R.string.common_play),
                    tint = ink,
                    modifier = Modifier.size(MiniPlayIconSize)
                )
            }
        }
        Spacer(Modifier.width(MiniTransportGap))
        MiniPlayerGlyph(enabled = controlsEnabled, onClick = onNext) {
            Icon(MeloIcons.SkipNext, stringResource(R.string.melo_next), Modifier.size(MiniSkipIconSize), tint = ink)
        }
    }
}

/** A bare transport glyph with a full touch target and a soft round press highlight. */
@Composable
private fun MiniPlayerGlyph(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(MiniGlyphSlot)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .meloPress(interaction, enabled),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The mini player's fill: the bar material, but solid, because the card throws a shadow
 * and a see-through fill would show that shadow through itself.
 */
@Composable
fun miniPlayerColor(): Color = barMaterialColor().copy(alpha = 1f)

// Mini player metrics: 40dp art with 8dp corners, 12dp row padding, 40dp glyph
// slots 8dp apart, a 32dp play glyph and 25dp skips.
internal val MiniArtSize = 40.dp
private val MiniArtShape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
private val MiniArtGap = 10.dp
internal val MiniRowPadding = 12.dp
private val MiniGlyphSlot = 40.dp
private val MiniTransportGap = 8.dp
private val MiniPlayIconSize = 32.dp
private val MiniSkipIconSize = 25.dp
