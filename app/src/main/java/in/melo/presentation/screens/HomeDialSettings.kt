package `in`.melo.presentation.screens

import `in`.melo.ui.glass.MeloSegmentedControl
import `in`.melo.ui.glass.MeloSwitch
import `in`.melo.presentation.icons.MeloIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import `in`.melo.presentation.components.MeloBottomSheet as ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.melo.R
import `in`.melo.data.Music
import `in`.melo.data.preferences.AppSettings
import `in`.melo.presentation.components.SmartImage
import `in`.melo.presentation.components.SmartImageCompactListTargetSize
import `in`.melo.ui.theme.MeloLayout
import org.json.JSONArray
import sh.calvin.reorderable.ReorderableColumn

internal fun decodeDialOrder(encoded: String): List<String> = runCatching {
    val values = JSONArray(encoded)
    (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
}.getOrDefault(emptyList())

internal fun encodeDialOrder(ids: List<String>): String = JSONArray(ids.distinct()).toString()

internal fun orderDialItems(items: List<Music>, encoded: String): List<Music> {
    val index = decodeDialOrder(encoded).withIndex().associate { it.value to it.index }
    return items.withIndex().sortedWith(
        compareBy<IndexedValue<Music>> { index[it.value.id] ?: Int.MAX_VALUE }.thenBy { it.index },
    ).map(IndexedValue<Music>::value)
}

internal fun orderDialEntries(entries: List<HomeSpeedDialEntry>, encoded: String): List<HomeSpeedDialEntry> {
    val index = decodeDialOrder(encoded).withIndex().associate { it.value to it.index }
    return entries.withIndex().sortedWith(
        compareBy<IndexedValue<HomeSpeedDialEntry>> { index[it.value.item.id] ?: Int.MAX_VALUE }.thenBy { it.index },
    ).map(IndexedValue<HomeSpeedDialEntry>::value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeDialSettingsMenuContent(
    settings: AppSettings,
    spotifyConnected: Boolean,
    onSettingsChange: ((AppSettings.() -> AppSettings) -> Unit),
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(MeloLayout.Space2),
    ) {
        DialSwitchRow(stringResource(R.string.home_dial_speed_dial), settings.homeSpeedDialEnabled) {
            onSettingsChange { copy(homeSpeedDialEnabled = it) }
        }
        if (settings.homeSpeedDialEnabled) {
            DialSwitchRow(stringResource(R.string.home_dial_artist_dial), settings.homeArtistDialEnabled) {
                onSettingsChange { copy(homeArtistDialEnabled = it) }
            }
            DialSwitchRow(stringResource(R.string.home_dial_mixes_dial), settings.homeMixesDialEnabled) {
                onSettingsChange { copy(homeMixesDialEnabled = it) }
            }
            DialSwitchRow(stringResource(R.string.home_dial_local_files), settings.homeDialLocalFiles) {
                onSettingsChange { copy(homeDialLocalFiles = it) }
            }
            DialSwitchRow(stringResource(R.string.home_dial_feeling_lucky), settings.homeLuckyTileEnabled) {
                onSettingsChange { copy(homeLuckyTileEnabled = it) }
            }
            Text(stringResource(R.string.home_dial_source), style = MaterialTheme.typography.titleSmall)
            val dialSources = listOf(
                AppSettings.DIAL_SOURCE_HISTORY to R.string.home_dial_source_history,
                AppSettings.DIAL_SOURCE_YOUTUBE to R.string.home_dial_source_youtube,
                AppSettings.DIAL_SOURCE_SPOTIFY to R.string.home_dial_source_spotify,
            )
            MeloSegmentedControl(
                options = dialSources.map { stringResource(it.second) },
                selectedIndex = dialSources.indexOfFirst { it.first == settings.homeDialSource },
                onSelect = { index -> onSettingsChange { copy(homeDialSource = dialSources[index].first) } },
                optionEnabled = { index -> dialSources[index].first != AppSettings.DIAL_SOURCE_SPOTIFY || spotifyConnected },
            )
            Text(stringResource(R.string.home_dial_rows), style = MaterialTheme.typography.titleSmall)
            val rowChoices = listOf(3, 4)
            MeloSegmentedControl(
                options = rowChoices.map { stringResource(R.string.home_dial_grid_value, it) },
                selectedIndex = rowChoices.indexOf(settings.homeDialRows),
                onSelect = { index -> onSettingsChange { copy(homeDialRows = rowChoices[index]) } },
            )
            DialSwitchRow(stringResource(R.string.home_dial_labels), settings.homeDialLabels) {
                onSettingsChange { copy(homeDialLabels = it) }
            }
            DialSwitchRow(stringResource(R.string.home_dial_pin_icon), settings.homeDialPinIcon) {
                onSettingsChange { copy(homeDialPinIcon = it) }
            }
            DialSwitchRow(stringResource(R.string.home_dial_new_release_tags), settings.homeDialNewReleaseTags) {
                onSettingsChange { copy(homeDialNewReleaseTags = it) }
            }
            Text(stringResource(R.string.home_dial_tile_style), style = MaterialTheme.typography.titleSmall)
            val tileStyles = listOf(
                AppSettings.DIAL_TILE_STYLE_PLAIN to R.string.home_dial_tile_style_plain,
                AppSettings.DIAL_TILE_STYLE_EDGE to R.string.home_dial_tile_style_edge,
                AppSettings.DIAL_TILE_STYLE_GLASS to R.string.home_dial_tile_style_glass,
            )
            MeloSegmentedControl(
                options = tileStyles.map { stringResource(it.second) },
                selectedIndex = tileStyles.indexOfFirst { it.first == settings.homeDialTileStyle },
                onSelect = { index -> onSettingsChange { copy(homeDialTileStyle = tileStyles[index].first) } },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeDialSettingsSheet(
    title: String,
    items: List<Music>,
    order: String,
    onOrderChange: (String) -> Unit,
    onDismiss: () -> Unit,
    pinnedIds: Set<String> = emptySet(),
    onTogglePin: ((Music) -> Unit)? = null,
) {
    var ordered by remember(items, order) { mutableStateOf(orderDialItems(items, order)) }
    LaunchedEffect(items, order) { ordered = orderDialItems(items, order) }
    `in`.melo.presentation.components.MeloBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MeloLayout.PageGutter)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.home_dial_reorder), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.home_dial_reorder_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ordered.isEmpty()) {
                Text(stringResource(R.string.home_dial_no_items), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ReorderableColumn(
                    list = ordered,
                    onSettle = { from, to ->
                        ordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
                        onOrderChange(encodeDialOrder(ordered.map(Music::id)))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MeloLayout.Space2),
                ) { _, item, _ ->
                    key(item.id) {
                        ReorderableItem {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = MeloLayout.PanelShape,
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MeloLayout.Space3),
                                ) {
                                    SmartImage(
                                        model = item.imageUrl,
                                        contentDescription = null,
                                        shape = MeloLayout.CardShape,
                                        contentScale = ContentScale.Crop,
                                        targetSize = SmartImageCompactListTargetSize,
                                        modifier = Modifier.size(44.dp),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            item.subtitle.ifBlank { stringResource(item.kind.labelRes) },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (onTogglePin != null) {
                                        androidx.compose.material3.IconButton(onClick = { onTogglePin(item) }) {
                                            Icon(
                                                MeloIcons.PushPin,
                                                contentDescription = stringResource(
                                                    if (item.id in pinnedIds) R.string.pin_remove else R.string.pin_add,
                                                ),
                                                tint = if (item.id in pinnedIds) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    androidx.compose.material3.IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                                        Icon(MeloIcons.DragHandle, stringResource(R.string.reorder))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        MeloSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
