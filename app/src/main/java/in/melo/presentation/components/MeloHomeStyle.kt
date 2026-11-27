package `in`.melo.presentation.components

import `in`.melo.ui.theme.MeloAlpha
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import `in`.melo.presentation.utils.LocalReducedMotion

val LocalHomeGradients = staticCompositionLocalOf { true }
val LocalHomePressMotion = staticCompositionLocalOf { true }

/** A subtle light-catching rim for artwork thumbnails. */
fun Modifier.artworkRim(shape: Shape, glass: Boolean = false): Modifier = drawWithCache {
    val width = if (glass) 1.25.dp.toPx() else 0.75.dp.toPx()
    val outline = shape.createOutline(size.copy(width = size.width - width, height = size.height - width), layoutDirection, this)
    val rim = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (glass) 0.48f else 0.22f),
        0.22f to Color.White.copy(alpha = 0.16f),
        0.65f to Color.White.copy(alpha = 0.06f),
        1f to Color.White.copy(alpha = 0.16f),
    )
    onDrawWithContent {
        drawContent()
        translate(width / 2f, width / 2f) { drawOutline(outline, rim, style = Stroke(width)) }
    }
}

@Composable
fun Modifier.meloPress(source: MutableInteractionSource, enabled: Boolean = true): Modifier {
    val pressed by source.collectIsPressedAsState()
    val motionEnabled = enabled && LocalHomePressMotion.current && !LocalReducedMotion.current
    val scale by animateFloatAsState(
        if (pressed && motionEnabled) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 650f), label = "artworkPress",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
fun Modifier.homePress(source: MutableInteractionSource): Modifier = meloPress(source)
