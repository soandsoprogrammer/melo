package `in`.melo.presentation.screens

import `in`.melo.presentation.icons.MeloIcons
import `in`.melo.presentation.icons.meloPainter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import `in`.melo.presentation.components.MeloBottomSheet as ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import `in`.melo.R
import `in`.melo.MeloModel
import `in`.melo.data.ConversionMatching
import `in`.melo.data.ConversionRow
import `in`.melo.data.MatchState
import `in`.melo.data.Music
import `in`.melo.data.PlaylistConversion
import `in`.melo.data.PlaylistKind
import `in`.melo.data.source
import `in`.melo.presentation.components.SmartImage
import `in`.melo.presentation.components.SmartImageCompactListTargetSize
import `in`.melo.presentation.components.SourceBadge
import `in`.melo.presentation.components.MeloButton
import `in`.melo.presentation.components.MeloButtonSize
import `in`.melo.presentation.components.MeloButtonStyle
import `in`.melo.presentation.components.MeloCard
import `in`.melo.presentation.components.MeloIconButton
import `in`.melo.presentation.navigation.openMusic
import `in`.melo.utils.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import `in`.melo.ui.theme.MeloLayout
import `in`.melo.ui.theme.MeloAlpha

@Composable
internal fun playlistKindName(kind: PlaylistKind): String = stringResource(when (kind) {
    PlaylistKind.Spotify -> R.string.search_source_spotify
    PlaylistKind.MusicServer -> R.string.music_server_title
    PlaylistKind.MultiService -> R.string.badge_local
})

@Composable
private fun PlaylistKindIcon(kind: PlaylistKind, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) = when (kind) {
    PlaylistKind.Spotify -> Icon(meloPainter(R.drawable.ic_source_spotify), null, modifier, tint = tint)
    PlaylistKind.MusicServer -> Icon(meloPainter(R.drawable.rounded_library_music_24), null, modifier, tint = tint)
    PlaylistKind.MultiService -> Icon(MeloIcons.LibraryMusic, null, modifier, tint = tint)
}

/** "Convert to…": the destinations a playlist can go to, with the account each one needs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConvertTargetSheet(model: MeloModel, item: Music, onDismiss: () -> Unit, onPick: (PlaylistKind) -> Unit) {
    val server by model.serverLibrary.collectAsStateWithLifecycle()
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val targets = remember(item.id, server, discovery.spotifyEnabled) { model.conversionTargets(item) }
    `in`.melo.presentation.components.MeloBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = MeloLayout.PageGutter).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(MeloLayout.Space2)) {
            Text(stringResource(R.string.convert_to), Modifier.padding(start = 8.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            targets.forEach { (kind, available) ->
                Row(
                    Modifier.fillMaxWidth().clip18().clickable(enabled = available) { onPick(kind) }.padding(MeloLayout.Space3).alpha(if (available) 1f else MeloAlpha.Tertiary),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistKindIcon(kind, Modifier.size(28.dp))
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(playlistKindName(kind), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(when {
                            kind == PlaylistKind.MultiService -> R.string.convert_target_multi
                            !available && kind == PlaylistKind.Spotify -> R.string.convert_needs_spotify
                            !available -> R.string.music_server_description
                            kind == PlaylistKind.Spotify -> R.string.convert_target_spotify
                            else -> R.string.music_server_title
                        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.size(MeloLayout.Space4))
        }
    }
}

private fun Modifier.clip18() = clip(MeloLayout.PanelShape)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConvertScreen(model: MeloModel, navController: NavHostController, playlistId: String, targetKey: String) {
    val ui = model.ui
    val source = remember(playlistId) { ui.entity(playlistId) }
    val target = remember(targetKey) { PlaylistKind.fromKey(targetKey) }
    if (source == null || target == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    LaunchedEffect(playlistId, target) { model.startConversion(source, target) }
    val conversion by model.conversion.collectAsStateWithLifecycle()
    val session = conversion?.takeIf { it.source.id == playlistId && it.target == target }
    fun leave() { model.endConversion(); navController.popBackStack() }
    BackHandler { leave() }
    `in`.melo.presentation.components.MeloPageScaffold(
        topBar = {
            TopAppBar(
                colors = `in`.melo.presentation.components.meloPageBarColors(),
                title = {
                    Column {
                        Text(stringResource(R.string.convert_title), fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(source.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(MeloIcons.ArrowForward, null, Modifier.padding(horizontal = MeloLayout.Space2).size(14.dp))
                            PlaylistKindIcon(target, Modifier.size(14.dp))
                            Text(playlistKindName(target), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                },
                navigationIcon = {
                    MeloIconButton(
                        icon = MeloIcons.ArrowBack,
                        contentDescription = stringResource(R.string.action_cancel),
                        onClick = ::leave,
                    )
                },
            )
        },
    ) { inner ->
        if (session == null) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
        } else ConversionContent(model, navController, session, inner)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConversionContent(model: MeloModel, navController: NavHostController, session: PlaylistConversion, inner: PaddingValues) {
    val state by session.state.collectAsState()
    val createFailed = stringResource(R.string.convert_failed)
    val createdMessage = stringResource(R.string.convert_done)
    var onlyReview by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.created) {
        val created = state.created ?: return@LaunchedEffect
        model.showMessage(createdMessage.format(created.title))
        model.endConversion()
        navController.popBackStack()
        navController.openMusic(model.ui, created)
    }
    LaunchedEffect(state.toReview) { if (state.toReview == 0) onlyReview = false }
    val rows = if (onlyReview) state.rows.filter { it.needsReview } else state.rows
    // The mini player floats over the bottom of the screen while something is loaded.
    val hasSong by model.ui.derivedHasSong.collectAsStateWithLifecycle()
    Column(Modifier.padding(inner).fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(MeloLayout.Space3), verticalArrangement = Arrangement.spacedBy(MeloLayout.Space2)) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(bottom = MeloLayout.Space2), verticalArrangement = Arrangement.spacedBy(MeloLayout.Space3)) {
                    OutlinedTextField(state.name, session::rename, Modifier.fillMaxWidth(), singleLine = true, enabled = !state.creating,
                        label = { Text(stringResource(R.string.playlist_name)) })
                    when {
                        state.reading -> {
                            Text(stringResource(R.string.convert_reading), style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        state.loadError != null -> Text(stringResource(R.string.convert_read_failed), color = MaterialTheme.colorScheme.error)
                        !state.finished -> {
                            Text(stringResource(R.string.convert_searching, state.searched, state.rows.size), style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(
                                progress = { if (state.rows.isEmpty()) 0f else state.searched.toFloat() / state.rows.size },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        else -> Text(stringResource(R.string.convert_summary, state.tracks.size, state.rows.size), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.toReview > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                        `in`.melo.presentation.components.MeloChip(stringResource(R.string.convert_to_review, state.toReview), onlyReview, { onlyReview = !onlyReview })
                    }
                }
            }
            items(rows, key = { it.position }) { row ->
                ConversionRowItem(row, enabled = !state.creating, onOpen = { picking = row.position },
                    onRemove = { session.remove(row.position) }, onRestore = { session.restore(row.position) })
            }
        }
        MeloCard(modifier = Modifier.fillMaxWidth(), shape = MeloLayout.PanelShape) {
            Column(Modifier.fillMaxWidth().padding(MeloLayout.Space4).navigationBarsPadding()
                .padding(bottom = if (hasSong) `in`.melo.presentation.components.MiniPlayerHeight else 0.dp),
                verticalArrangement = Arrangement.spacedBy(MeloLayout.Space2)) {
                state.error?.let { message ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        MeloButton(stringResource(R.string.action_ok), session::dismissError, size = MeloButtonSize.Medium, style = MeloButtonStyle.Text)
                    }
                }
                if (state.creating) LinearProgressIndicator(Modifier.fillMaxWidth())
                MeloButton(
                    text = stringResource(R.string.convert_create, `in`.melo.utils.formatSongCount(state.tracks.size)),
                    onClick = {
                        session.create(
                            creator = { name, tracks -> model.createConverted(session.target, name, tracks) },
                            failed = { createFailed },
                        )
                    },
                    enabled = state.finished && state.tracks.isNotEmpty() && !state.creating && state.name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    picking?.let { position ->
        state.rows.firstOrNull { it.position == position }?.let { row ->
            MatchPickerSheet(model, session, row) { picking = null }
        }
    }
}

@Composable
private fun ConversionRowItem(row: ConversionRow, enabled: Boolean, onOpen: () -> Unit, onRemove: () -> Unit, onRestore: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        row.removed -> `in`.melo.presentation.components.pageCardColor(colors.surfaceContainerLow)
        row.needsReview && row.choice == null -> colors.errorContainer
        row.needsReview -> colors.tertiaryContainer
        else -> `in`.melo.presentation.components.pageCardColor(colors.surfaceContainer)
    }
    val content = when {
        row.removed -> colors.onSurfaceVariant
        row.needsReview && row.choice == null -> colors.onErrorContainer
        row.needsReview -> colors.onTertiaryContainer
        else -> colors.onSurface
    }
    val strike = if (row.removed) TextDecoration.LineThrough else null
    Surface(
        color = background, contentColor = content, shape = MeloLayout.PanelShape,
        modifier = Modifier.fillMaxWidth().alpha(if (row.removed) 0.6f else 1f),
        onClick = onOpen, enabled = enabled && row.state != MatchState.Searching,
    ) {
        Row(Modifier.padding(start = 12.dp, top = MeloLayout.Space3, bottom = MeloLayout.Space3, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MeloLayout.Space2)) {
                if (row.state != MatchState.Same) Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(row.original.source, Modifier.padding(end = MeloLayout.Space2))
                    Text("${row.original.title} · ${row.original.subtitle}", style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = strike, color = content.copy(alpha = MeloAlpha.Secondary))
                }
                val choice = row.choice
                when {
                    row.state == MatchState.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.convert_row_searching), Modifier.padding(start = MeloLayout.Space3), style = MaterialTheme.typography.bodyMedium)
                    }
                    choice != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        SmartImage(model = choice.imageUrl, contentDescription = null, shape = MeloLayout.CardShape,
                            targetSize = SmartImageCompactListTargetSize, modifier = Modifier.size(42.dp))
                        Column(Modifier.weight(1f).padding(start = MeloLayout.Space3)) {
                            Text(choice.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = strike)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SourceBadge(choice.source, Modifier.padding(end = MeloLayout.Space2))
                                Text(choice.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (row.reviewed && !row.removed) Icon(MeloIcons.Check, null, Modifier.padding(start = MeloLayout.Space2).size(18.dp))
                    }
                    else -> Text(stringResource(if (row.failed) R.string.convert_row_failed else R.string.convert_row_missing),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                if (row.needsReview) Text(stringResource(if (row.choice == null) R.string.convert_row_choose else R.string.convert_row_check),
                    style = MaterialTheme.typography.labelMedium)
            }
            if (row.state != MatchState.Searching) {
                if (row.removed) MeloIconButton(MeloIcons.Undo, stringResource(R.string.convert_restore), onRestore, enabled = enabled)
                else MeloIconButton(MeloIcons.Close, stringResource(R.string.convert_remove), onRemove, enabled = enabled)
            }
        }
    }
}

/** The matches found for a track, a search of the destination, and keeping or leaving out the track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchPickerSheet(model: MeloModel, session: PlaylistConversion, row: ConversionRow, onDismiss: () -> Unit) {
    val initial = remember(row.position) { ConversionMatching.searchText(row.original) }
    var query by remember(row.position) { mutableStateOf(initial) }
    var results by remember(row.position) { mutableStateOf<List<Music>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val searchSource = when (session.target) {
        PlaylistKind.Spotify -> MeloModel.TRACKS_SPOTIFY
        PlaylistKind.MusicServer -> MeloModel.TRACKS_SERVER
        PlaylistKind.MultiService -> null
    }
    LaunchedEffect(query) {
        if (searchSource == null || query == initial || query.trim().length < 2) { results = null; loading = false; return@LaunchedEffect }
        delay(350)
        loading = true
        try { results = model.searchTracks(query, searchSource) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { results = emptyList() }
        finally { loading = false }
    }
    // Lazy list keys must be unique: a search can return the same track twice.
    val shown = (results ?: row.candidates).distinctBy(Music::id)
    `in`.melo.presentation.components.MeloBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(MeloLayout.Space3)) {
            Column(Modifier.padding(start = 8.dp)) {
                Text(row.original.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(row.original.source, Modifier.padding(end = MeloLayout.Space2))
                    Text(listOfNotNull(row.original.subtitle.takeIf(String::isNotBlank), row.original.album.takeIf(String::isNotBlank),
                        row.original.durationMs.takeIf { it > 0 }?.let { formatDuration(it.toLong()) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (searchSource != null) OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(MeloIcons.Search, null) }, shape = MeloLayout.PillShape,
                label = { Text(stringResource(R.string.convert_search_on, playlistKindName(session.target))) },
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(MeloLayout.Space1)) {
                if (shown.isEmpty() && !loading && searchSource != null) item {
                    Text(stringResource(R.string.convert_no_candidates), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(shown, key = { it.id }) { candidate ->
                    Row(
                        Modifier.fillMaxWidth().clip18().clickable { session.choose(row.position, candidate); onDismiss() }.padding(vertical = MeloLayout.Space2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = candidate.id == row.choice?.id, onClick = { session.choose(row.position, candidate); onDismiss() })
                        SmartImage(model = candidate.imageUrl, contentDescription = null, shape = MeloLayout.CardShape,
                            targetSize = SmartImageCompactListTargetSize, modifier = Modifier.size(44.dp))
                        Column(Modifier.weight(1f).padding(horizontal = MeloLayout.Space3)) {
                            Text(candidate.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SourceBadge(candidate.source, Modifier.padding(end = MeloLayout.Space2))
                                Text(listOfNotNull(candidate.subtitle.takeIf(String::isNotBlank),
                                    candidate.durationMs.takeIf { it > 0 }?.let { formatDuration(it.toLong()) }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        MeloIconButton(MeloIcons.PlayArrow, stringResource(R.string.convert_listen), onClick = { model.play(listOf(candidate), 0) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MeloLayout.Space2, Alignment.End)) {
                if (row.removed) MeloButton(stringResource(R.string.convert_restore), { session.restore(row.position); onDismiss() }, size = MeloButtonSize.Medium, style = MeloButtonStyle.Outlined)
                else MeloButton(stringResource(R.string.convert_remove), { session.remove(row.position); onDismiss() }, size = MeloButtonSize.Medium, style = MeloButtonStyle.Outlined)
                if (row.needsReview && row.choice != null) MeloButton(stringResource(R.string.convert_keep_match), { session.confirm(row.position); onDismiss() }, size = MeloButtonSize.Medium)
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}
