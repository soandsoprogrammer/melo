package `in`.melo.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.google.android.material.color.utilities.Hct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The colours a page built around one piece of artwork (album, playlist, artist, the
 * player) paints itself in, as a finished set.
 *
 * Screens never pick a colour off the artwork themselves: they all take this, so every
 * artwork page is tinted by the same recipe and every text colour on it is already
 * known to be readable on its background.
 */
@Immutable
data class ArtworkPalette(
    /** The page's background: the artwork's hue, nearly black (dark) or nearly white (light). */
    val background: Color,
    /** Raised things on [background]: glass buttons, chips, the shuffle/play pills. */
    val elevated: Color,
    /** The artwork's own colour, made readable on [background]: Play, icons, links. */
    val accent: Color,
    /** The text drawn on [accent] (the Play button's label). */
    val onAccent: Color,
    /** Titles on [background]. */
    val onBackground: Color,
    /** Second-line text on [background]. */
    val onBackgroundVariant: Color,
    /** Hairlines on [background]. */
    val divider: Color,
)

/** Smallest contrast an accent or second-line text keeps against its background (WCAG AA). */
internal const val MIN_TEXT_CONTRAST = 4.5

/** Below this chroma the artwork counts as grey and the page stays grey too. */
private const val NEUTRAL_CHROMA = 8.0

/**
 * Builds the palette from one seed colour, the same seed the player's colours come from
 * ([extractSeedColor]).
 */
fun artworkPaletteFrom(seed: Color, dark: Boolean): ArtworkPalette {
    val hct = Hct.fromInt(seed.toArgb())
    val hue = hct.hue
    val chroma = if (hct.chroma < NEUTRAL_CHROMA) 0.0 else hct.chroma
    fun tone(tone: Double, maxChroma: Double) = Color(Hct.from(hue, minOf(chroma, maxChroma), tone).toInt())

    val background = if (dark) tone(10.0, 18.0) else tone(96.0, 10.0)
    val elevated = if (dark) tone(20.0, 16.0) else tone(89.0, 14.0)
    val onBackground = if (dark) tone(98.0, 4.0) else tone(10.0, 6.0)
    val variant = readableOn(background, if (dark) 72.0 else 42.0, dark) { tone(it, 10.0) }
    val accent = readableOn(background, if (dark) 80.0 else 40.0, dark) { tone(it, 56.0) }
    return ArtworkPalette(
        background = background,
        elevated = elevated,
        accent = accent,
        onAccent = if (accent.luminance() > 0.4f) Color(0xFF111111) else Color.White,
        onBackground = onBackground,
        onBackgroundVariant = variant,
        divider = onBackground.copy(alpha = 0.14f),
    )
}

/** The theme's own colours, for a page with no artwork (or before it has loaded). */
fun neutralArtworkPalette(scheme: androidx.compose.material3.ColorScheme): ArtworkPalette = ArtworkPalette(
    background = scheme.background,
    elevated = scheme.surfaceContainerHigh,
    accent = scheme.primary,
    onAccent = scheme.onPrimary,
    onBackground = scheme.onBackground,
    onBackgroundVariant = scheme.onSurfaceVariant,
    divider = scheme.outlineVariant,
)

/** Moves [startTone] away from the background until the colour is readable on it. */
private inline fun readableOn(background: Color, startTone: Double, dark: Boolean, make: (Double) -> Color): Color {
    var t = startTone
    var color = make(t)
    while (contrast(color, background) < MIN_TEXT_CONTRAST && t in 1.0..99.0) {
        t += if (dark) 2.0 else -2.0
        color = make(t)
    }
    return color
}

internal fun contrast(a: Color, b: Color): Double {
    val la = a.luminance() + 0.05
    val lb = b.luminance() + 0.05
    return if (la > lb) (la / lb).toDouble() else (lb / la).toDouble()
}

/** Seeds already read, by artwork URL, so a page seen before is tinted on its first frame. */
private val seedCache = LruCache<String, Color>(128)

private suspend fun loadSeed(context: Context, url: String): Color? = withContext(Dispatchers.IO) {
    seedCache.get(url)?.let { return@withContext it }
    val request = ImageRequest.Builder(context)
        .data(url)
        .allowHardware(false)
        .size(Size(128, 128))
        .bitmapConfig(Bitmap.Config.ARGB_8888)
        .build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return@withContext null
    val bitmap = result.drawable.toBitmap(128, 128, Bitmap.Config.ARGB_8888)
    withContext(Dispatchers.Default) { extractSeedColor(bitmap) }.also { seedCache.put(url, it) }
}

/** The colour the artwork at [url] is tinted by; null until read, or with no artwork. */
@Composable
fun rememberArtworkSeed(url: String?): Color? {
    val context = LocalContext.current
    var seed by remember(url) { mutableStateOf(url?.let(seedCache::get)) }
    LaunchedEffect(url) {
        if (url != null && seed == null) seed = runCatching { loadSeed(context, url) }.getOrNull()
    }
    return seed
}

/**
 * The [ArtworkPalette] for the artwork at [url], in the current theme.
 *
 * Artwork read before is tinted from the first frame. Artwork seen for the first time
 * starts in the theme's own colours and warms into its own over [MeloMotion.Emphasised]
 * (a cut with "Remove animations" on).
 */
@Composable
fun rememberArtworkPalette(url: String?, dark: Boolean = LocalMeloDarkTheme.current): ArtworkPalette {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var seed by remember(url) { mutableStateOf(url?.let(seedCache::get)) }
    LaunchedEffect(url) {
        if (url != null && seed == null) seed = runCatching { loadSeed(context, url) }.getOrNull()
    }
    val target = remember(seed, dark, scheme) { seed?.let { artworkPaletteFrom(it, dark) } ?: neutralArtworkPalette(scheme) }
    val spec = MeloMotion.emphasised<Color>()
    @Composable fun animate(color: Color) = animateColorAsState(color, spec, label = "artworkPalette").value
    return ArtworkPalette(
        background = animate(target.background),
        elevated = animate(target.elevated),
        accent = animate(target.accent),
        onAccent = animate(target.onAccent),
        onBackground = animate(target.onBackground),
        onBackgroundVariant = animate(target.onBackgroundVariant),
        divider = animate(target.divider),
    )
}

/*
 * The now-playing tint: the one accent a sleeve gets,
 * which the whole app wears for the playing song (the colour background, the selected tab, the
 * mini player's progress). Modified for Melo on 2026-11-24: read with Coil 2 and Melo's caches.
 */

/**
 * The playing song's colour, for chrome that takes it on. Null while nothing plays, so each
 * caller keeps its own colour then. Provided once, at the app's root, from [rememberNowPlayingTint].
 */
val LocalNowPlayingTint = androidx.compose.runtime.compositionLocalOf<Color?> { null }

/** Accent swatches already read, by artwork URL. */
private val accentSwatchCache = LruCache<String, Color>(128)

private suspend fun loadAccentSwatch(context: Context, url: String): Color? = withContext(Dispatchers.IO) {
    accentSwatchCache.get(url)?.let { return@withContext it }
    val request = ImageRequest.Builder(context)
        .data(`in`.melo.data.largestSpotifyImage(url) ?: url)
        .allowHardware(false)
        .size(Size(ACCENT_PALETTE_PX, ACCENT_PALETTE_PX))
        .bitmapConfig(Bitmap.Config.ARGB_8888)
        .build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return@withContext null
    val bitmap = result.drawable.toBitmap(config = Bitmap.Config.ARGB_8888)
    val swatches = withContext(Dispatchers.Default) {
        // Unfiltered: the default filter throws near-black and near-white away, which is the
        // wrong answer for a dark or a monochrome sleeve.
        androidx.palette.graphics.Palette.from(bitmap).maximumColorCount(ACCENT_SWATCH_COUNT).clearFilters().generate().swatches
    }
    if (swatches.isEmpty()) return@withContext null
    Color(pickAccentSwatch(swatches.map { it.rgb to it.population })).also { accentSwatchCache.put(url, it) }
}

/**
 * The accent the artwork at [artworkUrl] gives (its one accent swatch, made legible on the
 * theme's background), or null while nothing plays. Until a new sleeve has been read the last
 * song's colour holds, and the change crossfades rather than cuts unless motion is reduced.
 */
@Composable
fun rememberNowPlayingTint(artworkUrl: String?, dark: Boolean = LocalMeloDarkTheme.current): Color? {
    val context = LocalContext.current
    val reduced = `in`.melo.presentation.utils.LocalReducedMotion.current
    var swatch by remember(artworkUrl) { mutableStateOf(artworkUrl?.let(accentSwatchCache::get)) }
    LaunchedEffect(artworkUrl) {
        if (artworkUrl != null && swatch == null) swatch = runCatching { loadAccentSwatch(context, artworkUrl) }.getOrNull()
    }
    var settled by remember { mutableStateOf<Color?>(null) }
    val read = swatch
    val target = when {
        artworkUrl == null -> null
        read != null -> accentOf(read, dark)
        else -> settled
    }
    androidx.compose.runtime.SideEffect { settled = target }
    if (target == null) return null
    val spec: androidx.compose.animation.core.AnimationSpec<Color> =
        if (reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(TINT_FADE_MS)
    return animateColorAsState(target, spec, label = "nowPlayingTint").value
}

/**
 * The one accent colour a sleeve gets out of its quantised swatches (`rgb to population`).
 * Scored on HSL chroma against the square root of population: chroma is what makes a colour
 * read as a colour, and the square root keeps a speck from winning over a colour the sleeve is
 * made of. Near-black swatches are never picked while anything else is there; a sleeve that is
 * nothing but near-black gets its brightest swatch.
 */
internal fun pickAccentSwatch(swatches: List<Pair<Int, Int>>): Int {
    require(swatches.isNotEmpty())
    val hsl = FloatArray(3)
    fun lightness(rgb: Int): Float {
        androidx.core.graphics.ColorUtils.colorToHSL(rgb, hsl)
        return hsl[2]
    }
    val candidates = swatches.filter { (rgb, _) -> lightness(rgb) >= ACCENT_MIN_LIGHTNESS }
    if (candidates.isEmpty()) return swatches.maxBy { (rgb, _) -> lightness(rgb) }.first
    return candidates.maxBy { (rgb, population) ->
        androidx.core.graphics.ColorUtils.colorToHSL(rgb, hsl)
        val chroma = hsl[1] * (1f - kotlin.math.abs(2f * hsl[2] - 1f))
        // Pale tints are marked down on top of chroma's own falloff, so a cream loses to a real colour.
        val pale = if (hsl[2] > ACCENT_PALE_FROM) 0.5f else 1f
        (chroma * pale + ACCENT_CHROMA_FLOOR) * kotlin.math.sqrt(population.toFloat())
    }.first
}

/**
 * [color] made into an accent: saturated enough to read as a colour and lit so it stays legible
 * on the theme's background, so an accent taken from anything else (the colour background's
 * colour, say) sits beside the song's as one family.
 */
fun accentOf(color: Color, dark: Boolean): Color = color.withHsl(
    saturation = { adaptedArtworkSaturation(it, minimum = 0.55f, maximum = 1f) },
    lightness = { if (dark) it.coerceIn(0.62f, 0.78f) else it.coerceIn(0.30f, 0.44f) },
)

/**
 * Keeps neutral artwork neutral instead of inventing a hue for it: HSL greys have hue zero, so
 * raising one to a saturation floor makes red. A little real colour is kept as it is; a clearly
 * coloured swatch is strengthened enough to be legible.
 */
internal fun adaptedArtworkSaturation(source: Float, minimum: Float, maximum: Float): Float {
    val saturation = source.coerceIn(0f, 1f)
    return if (saturation < CHROMATIC_SATURATION_THRESHOLD) saturation else saturation.coerceIn(minimum, maximum)
}

private fun Color.withHsl(saturation: (Float) -> Float = { it }, lightness: (Float) -> Float = { it }): Color {
    val hsl = FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(toArgb(), it) }
    hsl[1] = saturation(hsl[1]).coerceIn(0f, 1f)
    hsl[2] = lightness(hsl[2]).coerceIn(0f, 1f)
    return Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
}

private const val ACCENT_MIN_LIGHTNESS = 0.16f
private const val ACCENT_PALE_FROM = 0.80f
private const val ACCENT_CHROMA_FLOOR = 0.01f
private const val CHROMATIC_SATURATION_THRESHOLD = 0.12f
private const val ACCENT_SWATCH_COUNT = 24
private const val ACCENT_PALETTE_PX = 128
/** Short: a surface settling into its colour, not an effect in itself. */
private const val TINT_FADE_MS = 260
