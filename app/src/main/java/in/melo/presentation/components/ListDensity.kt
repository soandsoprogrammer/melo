package `in`.melo.presentation.components

import `in`.melo.presentation.icons.MeloIcons
import `in`.melo.presentation.icons.meloPainter
import `in`.melo.ui.theme.MeloAlpha
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import `in`.melo.R
import `in`.melo.data.MusicSource

/** Compact lists: smaller rows and spacing in the library, playlists and albums. Off by default. */
val LocalCompactLists = staticCompositionLocalOf { false }

/** Whether the tracks of a multi-service playlist show where they come from. */
val LocalSourceBadges = staticCompositionLocalOf { true }

/** Space between the rows of a track or library list. */
@Composable
fun listSpacing(): Dp = if (LocalCompactLists.current) 3.dp else 8.dp

/** A small mark of where a track's audio comes from: the service's logo, or a folder for a file on the device. */
@Composable
fun SourceBadge(source: MusicSource, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current.copy(alpha = MeloAlpha.Secondary)) {
    val description = stringResource(when (source) {
        MusicSource.Spotify -> R.string.source_spotify
        MusicSource.YouTubeMusic -> R.string.source_youtube
        MusicSource.Local -> R.string.source_local
        MusicSource.Addon -> R.string.source_addon
        MusicSource.MusicServer -> R.string.music_server_title
    })
    val iconModifier = modifier.size(14.dp)
    when (source) {
        MusicSource.Spotify -> Icon(meloPainter(R.drawable.ic_source_spotify), description, iconModifier, tint = tint)
        MusicSource.YouTubeMusic -> Icon(meloPainter(R.drawable.ic_source_youtube_music), description, iconModifier, tint = tint)
        MusicSource.Local -> Icon(MeloIcons.Folder, description, iconModifier, tint = tint)
        MusicSource.Addon -> Icon(MeloIcons.Link, description, iconModifier, tint = tint)
        MusicSource.MusicServer -> Icon(MeloIcons.MusicNote, description, iconModifier, tint = tint)
    }
}
