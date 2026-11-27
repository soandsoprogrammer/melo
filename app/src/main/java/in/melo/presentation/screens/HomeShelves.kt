package `in`.melo.presentation.screens

import androidx.compose.ui.res.stringResource
import `in`.melo.presentation.icons.MeloIcons
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import `in`.melo.ui.theme.GoogleSansRounded
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import `in`.melo.ui.theme.MeloLayout
import `in`.melo.data.Music
import `in`.melo.data.MusicKind
import `in`.melo.data.preferences.ShelfStyle
import `in`.melo.presentation.components.SmartImage
import `in`.melo.presentation.viewmodel.PlayerViewModel

@Composable
private fun Cover(item: Music, ui: PlayerViewModel, placeholder: Color, shape: androidx.compose.ui.graphics.Shape, modifier: Modifier) {
    Box(modifier.clip(shape).background(placeholder), contentAlignment = Alignment.Center) {
        if (item.id == ui.likedSongs.id) Icon(MeloIcons.Favorite, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        else if (item.imageUrl == null) Icon(MeloIcons.MusicNote, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else SmartImage(model = item.imageUrl, contentDescription = item.title, contentScale = ContentScale.Crop, shape = shape, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun CardTexts(item: Music, centered: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(
            item.title, Modifier.fillMaxWidth(),
            fontFamily = GoogleSansRounded, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
        Text(
            item.subtitle.ifBlank { stringResource(item.kind.labelRes) }, Modifier.fillMaxWidth().padding(top = MeloLayout.Space1),
            fontFamily = GoogleSansRounded, fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
    }
}

/** Artwork and metadata sit directly on the page, with no tinted card background. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfCoverCard(item: Music, ui: PlayerViewModel, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val round = item.kind == MusicKind.Artist
    val shape = if (round) MeloLayout.ArtistShape else MeloLayout.ArtworkShape
    `in`.melo.presentation.components.MeloShelfCard(
        title = item.title,
        subtitle = item.subtitle.ifBlank { stringResource(item.kind.labelRes) },
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = shape,
        centered = round,
    ) {
        Cover(item, ui, MaterialTheme.colorScheme.surfaceContainerHighest, shape, Modifier.fillMaxSize())
    }
}

/** A quiet row with a small cover, title, and supporting metadata. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfListRow(item: Music, ui: PlayerViewModel, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = MeloLayout.ArtworkShape
    Box(Modifier.fillMaxWidth().padding(horizontal = MeloLayout.PageGutter).clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.padding(MeloLayout.Space2), verticalAlignment = Alignment.CenterVertically) {
            Cover(item, ui, MaterialTheme.colorScheme.surfaceContainerHighest,
                if (item.kind == MusicKind.Artist) CircleShape else MeloLayout.ArtworkShape, Modifier.size(58.dp))
            CardTexts(item, false, Modifier.weight(1f).padding(horizontal = MeloLayout.Space4))
        }
    }
}

/** Lays out a shelf's items in the chosen [style]. Pills are drawn by the caller. */
@Composable
internal fun ShelfItems(
    items: List<Music>,
    style: ShelfStyle,
    ui: PlayerViewModel,
    onClick: (Music) -> Unit,
    onLongClick: (Music) -> Unit,
    pill: @Composable (Music, Modifier) -> Unit,
) {
    when (style) {
        ShelfStyle.CAROUSEL, ShelfStyle.LARGE -> {
            val width: Dp = if (style == ShelfStyle.LARGE) 212.dp else 152.dp
            LazyRow(contentPadding = PaddingValues(horizontal = MeloLayout.PageGutter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(items, key = Music::id, contentType = { it.kind }) { item ->
                    ShelfCoverCard(item, ui, Modifier.width(width), { onClick(item) }, { onLongClick(item) })
                }
            }
        }
        ShelfStyle.GRID -> Column(Modifier.padding(horizontal = MeloLayout.PageGutter), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { item -> ShelfCoverCard(item, ui, Modifier.weight(1f), { onClick(item) }, { onLongClick(item) }) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        ShelfStyle.LIST -> CompactTrackShelf(items, onClick, onLongClick)
        else -> Column(Modifier.padding(horizontal = MeloLayout.PageGutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item -> pill(item, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
