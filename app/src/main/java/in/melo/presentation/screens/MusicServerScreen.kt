package `in`.melo.presentation.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import `in`.melo.MeloModel
import `in`.melo.R
import `in`.melo.data.model.toSong
import `in`.melo.data.server.MusicServer
import `in`.melo.data.server.MusicServerException
import `in`.melo.data.server.MusicServerFailure
import `in`.melo.data.server.MusicServerUris
import `in`.melo.data.server.ServerPage
import `in`.melo.presentation.components.MeloChip
import `in`.melo.presentation.components.pageBackground
import `in`.melo.presentation.icons.MeloIcons
import `in`.melo.ui.theme.MeloLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicServerScreen(
    model: MeloModel,
    padding: PaddingValues,
    showBackButton: Boolean = true,
    onAppSettings: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    var addingTrack by remember { mutableStateOf<`in`.melo.data.Music?>(null) }
    val context = LocalContext.current
    val server = remember(context) { MusicServer.get(context) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var account by remember { mutableStateOf(server.account) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf(server.account?.url?.toString().orEmpty()) }
    var username by rememberSaveable { mutableStateOf(server.account?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<Int?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showAlbums by rememberSaveable { mutableStateOf(true) }
    var albumId by rememberSaveable { mutableStateOf<String?>(null) }
    var albumTitle by rememberSaveable { mutableStateOf("") }
    val showForm = account == null || editing
    var page by remember { mutableStateOf(ServerPage()) }
    var loadError by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    var canLoadMore by remember { mutableStateOf(false) }
    var offset by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    val search = query.trim()

    fun goBack() {
        if (connecting) return
        when {
            editing -> {
                editing = false
                password = ""
                connectionError = null
            }
            albumId != null -> albumId = null
            else -> onBack()
        }
    }

    fun stopServerPlayback() {
        if (MusicServerUris.isTrack(model.ui.stablePlayerState.value.currentSong?.id.orEmpty())) {
            model.player.stop()
        }
    }

    BackHandler(enabled = connecting || editing || albumId != null, onBack = ::goBack)
    LaunchedEffect(search, showAlbums, albumId, account?.libraryKey, showForm) {
        page = ServerPage()
        canLoadMore = false
        offset = 0
        loadError = null
    }
    LaunchedEffect(search, showAlbums, albumId, account?.libraryKey, showForm, offset, reload) {
        if (showForm) return@LaunchedEffect
        val currentAlbum = albumId
        loading = true
        loadError = null
        try {
            if (currentAlbum == null && search.isNotEmpty() && offset == 0) delay(320)
            val loaded = when {
                currentAlbum != null -> server.albumTracks(currentAlbum)
                search.isNotEmpty() || !showAlbums -> server.tracks(search, offset)
                else -> server.albums(offset)
            }
            page = if (offset == 0) loaded else ServerPage(
                albums = (page.albums + loaded.albums).distinctBy { it.id },
                songs = (page.songs + loaded.songs).distinctBy { it.id }
            )
            canLoadMore = currentAlbum == null && maxOf(loaded.albums.size, loaded.songs.size) == MusicServer.PAGE_SIZE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.messageResource()
        } finally {
            loading = false
        }
    }

    Scaffold(
        containerColor = pageBackground(),
        topBar = {
            TopAppBar(
                title = { Text(if (albumId != null && !showForm) albumTitle else stringResource(R.string.music_server_title)) },
                navigationIcon = {
                    if (showBackButton || albumId != null || editing) IconButton(onClick = ::goBack) {
                        Icon(MeloIcons.ArrowBack, stringResource(R.string.music_server_back))
                    }
                },
                actions = {
                    if (onAppSettings != null) IconButton(onClick = onAppSettings) {
                        Icon(MeloIcons.Settings, stringResource(R.string.common_settings))
                    }
                    if (!showForm) {
                        IconButton(onClick = {
                            address = account?.url?.toString().orEmpty()
                            username = account?.username.orEmpty()
                            editing = true
                        }) {
                            Icon(MeloIcons.Settings, stringResource(R.string.music_server_settings))
                        }
                    }
                }
            )
        }
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())
                .navigationBarsPadding().imePadding()
        ) {
            if (showForm) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 20.dp + padding.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(stringResource(R.string.music_server_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        OutlinedTextField(
                            value = address, onValueChange = { address = it }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.music_server_address)) },
                            enabled = !connecting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = username, onValueChange = { username = it }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.music_server_username)) },
                            enabled = !connecting,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = password, onValueChange = { password = it }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.music_server_password)) },
                            enabled = !connecting, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                    }
                    connectionError?.let { error ->
                        item { Text(stringResource(error), color = MaterialTheme.colorScheme.error) }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                enabled = !connecting && address.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
                                onClick = {
                                    focus.clearFocus()
                                    connecting = true
                                    connectionError = null
                                    scope.launch {
                                        try {
                                            val connected = server.signIn(address, username, password)
                                            if (connected.libraryKey != account?.libraryKey) {
                                                stopServerPlayback()
                                                albumId = null
                                                query = ""
                                            }
                                            account = connected
                                            password = ""
                                            editing = false
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Exception) {
                                            connectionError = error.messageResource()
                                        } finally {
                                            connecting = false
                                        }
                                    }
                                }
                            ) { Text(stringResource(R.string.music_server_connect)) }
                            if (connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            if (account != null) {
                                TextButton(enabled = !connecting, onClick = {
                                    connecting = true
                                    connectionError = null
                                    scope.launch {
                                        try {
                                            server.disconnect()
                                            stopServerPlayback()
                                            account = null
                                            editing = false
                                            albumId = null
                                            query = ""
                                            password = ""
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Exception) {
                                            connectionError = error.messageResource()
                                        } finally {
                                            connecting = false
                                        }
                                    }
                                }) { Text(stringResource(R.string.music_server_disconnect)) }
                            }
                        }
                    }
                }
            } else {
                if (albumId == null) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            offset = 0
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                        label = { Text(stringResource(R.string.music_server_search)) },
                        singleLine = true,
                        shape = MeloLayout.PillShape,
                        leadingIcon = { Icon(MeloIcons.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = ""; offset = 0 }) {
                                    Icon(MeloIcons.Close, stringResource(R.string.music_server_clear_search))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() })
                    )
                    if (search.isEmpty()) {
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MeloChip(stringResource(R.string.music_server_albums), selected = showAlbums, onClick = { showAlbums = true; offset = 0 })
                            MeloChip(stringResource(R.string.music_server_songs), selected = !showAlbums, onClick = { showAlbums = false; offset = 0 })
                        }
                    } else Spacer(Modifier.height(8.dp))
                }
                val queueName = if (albumId != null) albumTitle else stringResource(R.string.music_server_title)
                val queuedSongs = remember(page.songs) { page.songs.map { it.toSong() } }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 22.dp + padding.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(page.albums, key = { _, album -> "album:${album.id}" }) { _, album ->
                        ServerLibraryRow(album.artwork, album.title, album.artist) {
                            focus.clearFocus()
                            offset = 0
                            albumTitle = album.title
                            albumId = album.id
                        }
                    }
                    itemsIndexed(page.songs, key = { _, song -> song.id }) { index, song ->
                        ServerLibraryRow(song.imageUrl, song.title, listOf(song.subtitle, song.quality).filter(String::isNotBlank).joinToString(" · "),
                            onAdd = { addingTrack = song }) {
                            focus.clearFocus()
                            model.ui.playSongs(queuedSongs, queuedSongs[index], queueName)
                            model.ui.expandPlayerSheet()
                        }
                    }
                    when {
                        loading -> item {
                            Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                        loadError != null -> item {
                            Column(Modifier.padding(top = 12.dp)) {
                                Text(stringResource(requireNotNull(loadError)), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { reload++ }) { Text(stringResource(R.string.music_server_retry)) }
                            }
                        }
                        page.isEmpty -> item {
                            Text(stringResource(R.string.music_server_empty), Modifier.padding(vertical = 16.dp))
                        }
                        canLoadMore -> item {
                            TextButton(onClick = { offset += MusicServer.PAGE_SIZE }) {
                                Text(stringResource(R.string.music_server_more))
                            }
                        }
                    }
                }
            }
        }
    }
    addingTrack?.let { track ->
        `in`.melo.presentation.components.AddToLocalPlaylistSheet(track, model.ui) { addingTrack = null }
    }
}

@Composable
private fun ServerLibraryRow(artwork: String?, title: String, subtitle: String, onAdd: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = MeloLayout.SmallArtworkShape
    Row(
        Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(48.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(MeloIcons.MusicNote, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (artwork != null) AsyncImage(artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onAdd != null) IconButton(onClick = onAdd) {
            Icon(MeloIcons.Add, stringResource(R.string.playlist_add_to))
        }
    }
}

private fun Exception.messageResource(): Int = when ((this as? MusicServerException)?.failure) {
    MusicServerFailure.Address -> R.string.music_server_error_address
    MusicServerFailure.Credentials -> R.string.music_server_error_credentials
    MusicServerFailure.Connection -> R.string.music_server_error_connection
    else -> R.string.music_server_error_response
}
