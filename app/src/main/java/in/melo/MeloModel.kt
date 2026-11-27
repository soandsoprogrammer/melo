package `in`.melo

import `in`.melo.utils.AppLocales
import `in`.melo.data.Login5Error
import `in`.melo.R
import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.protobuf.InvalidProtocolBufferException
import `in`.melo.data.CatalogDetail
import `in`.melo.data.CatalogStore
import `in`.melo.data.FavoriteChange
import `in`.melo.data.NetworkMonitor
import `in`.melo.data.SpotifyAuthRequired
import `in`.melo.data.applyFavoriteChanges
import `in`.melo.data.playlistMosaic
import `in`.melo.data.decodePins
import `in`.melo.data.encodePins
import `in`.melo.data.HomeLibrary
import `in`.melo.data.LOCAL_PLAYLIST_PREFIX
import `in`.melo.data.LocalLibrary
import `in`.melo.data.LocalPlaylistStore
import `in`.melo.data.MusicKind
import `in`.melo.data.isLocalPlaylist
import `in`.melo.data.isLocalTrack
import `in`.melo.data.source
import `in`.melo.data.PinnedStore
import `in`.melo.data.SpotifyAccount
import `in`.melo.data.spotifyAttribute
import `in`.melo.presentation.viewmodel.PlayerViewModel
import `in`.melo.data.SpotifyCatalog
import `in`.melo.data.SpotifyPlaylists
import `in`.melo.data.SpotifyLibrary
import `in`.melo.data.SpotifyLyricsSource
import `in`.melo.data.SpotifyRadio
import `in`.melo.playback.SpotifyPlayer
import `in`.melo.playback.Playback
import `in`.melo.data.SessionVault
import `in`.melo.data.Music
import `in`.melo.data.SpotifyFailure
import `in`.melo.data.SpotifyUserProfile
import `in`.melo.data.SpotifyUserProfileCodec
import `in`.melo.data.spotifyCurrentUserProfile
import `in`.melo.data.BackgroundRequests
import `in`.melo.data.SpotifySearch
import `in`.melo.data.SpotifySession
import `in`.melo.data.SpotifyTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import `in`.melo.utils.parseSongCount
import `in`.melo.utils.formatSongCount

/**
 * What a search reads. [All], the default, mixes Spotify, the music server and device files.
 * [Addons] reads the listener's add-ons only, and only when one is enabled.
 */
enum class SearchSource { All, Spotify, MusicServer, Local, Addons }

data class Discovery(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<Music> = emptyList(),
    /** Spotify's next page token. */
    val nextPage: String? = null,
    val serverOffset: Int? = null,
    val error: String? = null,
    val spotifyEnabled: Boolean = false,
    val source: SearchSource = SearchSource.All,
    val addonsEnabled: Boolean = false,
) {
    /** The source actually searched: Spotify needs an account, add-ons an enabled add-on. */
    val activeSource: SearchSource get() = when {
        !spotifyEnabled && source == SearchSource.Spotify -> SearchSource.All
        !addonsEnabled && source == SearchSource.Addons -> SearchSource.All
        else -> source
    }
    val hasMore: Boolean get() = nextPage != null || serverOffset != null
}

data class AccountUi(
    val working: Boolean = false,
    val codeLength: Int? = null,
    val codeEmail: String? = null,
    val error: String? = null,
    val restoring: Boolean = false,
)

data class LibraryUi(val content: HomeLibrary? = null, val loading: Boolean = false, val error: String? = null) {
    val items: List<Music> get() = content?.let { it.playlists + it.albums + it.artists }.orEmpty()
    val tracks: List<Music> get() = content?.savedTracks.orEmpty()
}
data class DetailUi(val content: CatalogDetail? = null, val loading: Boolean = false, val error: String? = null)

class MeloModel(private val app: Application) : AndroidViewModel(app) {
    private val uiContext get() = AppLocales.wrap(app)
    val player = SpotifyPlayer(app, viewModelScope)
    private val pinnedStore = PinnedStore(app)
    val pins = pinnedStore.pins
    fun togglePin(item: Music) = pinnedStore.toggle(item)
    val hiddenFromDial = pinnedStore.hiddenFromDial
    fun removeFromDial(item: Music) = pinnedStore.removeFromDial(item)
    fun savedDial(name: String) = pinnedStore.savedDial(name)
    fun saveDial(name: String, key: String, items: List<Music>) = pinnedStore.saveDial(name, key, items)
    fun movePin(from: Int, to: Int) = pinnedStore.move(from, to)
    val ui by lazy { PlayerViewModel(this, viewModelScope) }
    val localLibrary = LocalLibrary(app)
    private val mutableLocalTracks = MutableStateFlow<List<Music>>(emptyList())
    val localTracks = mutableLocalTracks.asStateFlow()
    val localPlaylistStore = LocalPlaylistStore(app)
    val listeningHistory = `in`.melo.data.ListeningHistoryStore(app)
    val localPlaylists = localPlaylistStore.playlists
    fun refreshLocalTracks() { viewModelScope.launch { mutableLocalTracks.value = localLibrary.load() } }
    val pinnedStoreForBackup get() = pinnedStore
    private val preferences = app.getSharedPreferences("music_library", 0)
    private val mutableSpotifyAccountProfile = MutableStateFlow<SpotifyUserProfile?>(null)
    val spotifyAccountProfile = mutableSpotifyAccountProfile.asStateFlow()
    private var lastOpenedArtist = decodePins(preferences.getString(KEY_LAST_OPEN_ARTIST, null))
        .firstOrNull { it.kind == MusicKind.Artist }
    private val savedIds = MutableStateFlow<Set<String>>(emptySet())

    private val mutableDiscovery = MutableStateFlow(Discovery())
    val discovery = mutableDiscovery.asStateFlow()
    private var searchJob: Job? = null
    private var accountJob: Job? = null
    private val mutableAccount = MutableStateFlow(AccountUi())
    val account = mutableAccount.asStateFlow()
    /** The app is used without a Spotify account: local files, a music server and multi-service playlists. */
    private val mutableGuest = MutableStateFlow(app.getSharedPreferences("music_library", 0).getBoolean(KEY_GUEST, true))
    val guest = mutableGuest.asStateFlow()
    fun continueWithoutAccount() {
        preferences.edit { putBoolean(KEY_GUEST, true) }
        mutableGuest.value = true
        if (remote == null) installLibrary(emptyLibrary(), false)
        search(discovery.value.query)
    }
    /** Back to the sign-in screen, from the settings of a guest. */
    fun showSignIn() {
        preferences.edit { putBoolean(KEY_GUEST, false) }
        mutableGuest.value = false
    }
    private val transport = SpotifyTransport()
    private val vault = SessionVault(app)
    private val devicePreferences = app.getSharedPreferences("spotify_device", 0)
    private val deviceId = devicePreferences.getString("id", null) ?: UUID.randomUUID().toString().also { id ->
        devicePreferences.edit { putString("id", id) }
    }
    private var pendingCode: CompletableDeferred<String>? = null
    private val loginFlowId by lazy { UUID.randomUUID().toString() }
    private var remote: SpotifySearch? = null
    private var catalog: SpotifyCatalog? = null
    private var collections: SpotifyLibrary? = null
    private var radio: SpotifyRadio? = null
    private var spotifyProfileJob: Job? = null
    private var libraryJob: Job? = null
    private val detailJobs = mutableMapOf<String, Job>()
    private val pendingMutations = mutableSetOf<String>()
    private val store = CatalogStore(app)
    internal val catalogStore get() = store
    /** Likes the library read does not show yet. Main thread only. */
    private val favoriteChanges = LinkedHashMap<String, FavoriteChange>()
    /** Mosaic covers of playlists without their own picture, from their stored pages. Main thread only. */
    private val playlistCovers = HashMap<String, String>()
    private fun rememberCover(detail: CatalogDetail) {
        val item = detail.item
        if (item.kind != MusicKind.Playlist || item.imageUrl == null || playlistCovers[item.id] == item.imageUrl) return
        val shown = mutableLibrary.value.content?.playlists?.firstOrNull { it.id == item.id } ?: return
        if (shown.imageUrl != null && shown.id !in playlistCovers) return
        playlistCovers[item.id] = item.imageUrl
        saveCovers()
        mutableLibrary.value.content?.let { installLibrary(it, mutableLibrary.value.loading) }
    }
    private val coverLookups = HashSet<String>()
    /** A playlist without picture whose page is on the device gets its mosaic without a request. */
    private fun coverFromStoredPage(id: String) {
        if (!coverLookups.add(id)) return
        viewModelScope.launch {
            val detail = withContext(Dispatchers.IO) { store.loadDetail(id) } ?: return@launch
            val cover = playlistMosaic(detail.tracks) ?: return@launch
            playlistCovers[id] = cover
            saveCovers()
            mutableLibrary.value.content?.let { installLibrary(it, mutableLibrary.value.loading) }
        }
    }
    private fun saveCovers() = preferences.edit { putString(KEY_PLAYLIST_COVERS, org.json.JSONObject(playlistCovers as Map<*, *>).toString()) }
    private fun loadCovers() {
        val json = runCatching { org.json.JSONObject(preferences.getString(KEY_PLAYLIST_COVERS, null) ?: return) }.getOrNull() ?: return
        json.keys().forEach { playlistCovers[it] = json.getString(it) }
    }
    private var offlineSyncJob: Job? = null
    val online = NetworkMonitor.online
    private val mutableLibrary = MutableStateFlow(LibraryUi())
    val library = mutableLibrary.asStateFlow()
    private val mutableDetails = MutableStateFlow<Map<String, DetailUi>>(emptyMap())
    val details = mutableDetails.asStateFlow()
    private val musicServer = `in`.melo.data.server.MusicServer.get(app)
    val serverLibrary = musicServer.library
    private val mutableServerPlaylists = MutableStateFlow<List<Music>>(emptyList())
    val serverPlaylists = mutableServerPlaylists.asStateFlow()
    private val serverStars = MutableStateFlow<List<Music>>(emptyList())
    val serverFavorites = serverStars.asStateFlow()
    val favorites = kotlinx.coroutines.flow.combine(savedIds, serverStars) { spotify, server -> spotify + server.map(Music::id) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())
    private var serverListsJob: Job? = null

    fun refreshServerPlaylists() {
        serverListsJob?.cancel()
        val library = serverLibrary.value
        if (library == null) { mutableServerPlaylists.value = emptyList(); return }
        serverListsJob = viewModelScope.launch {
            try {
                val playlists = musicServer.playlists()
                if (serverLibrary.value == library) {
                    mutableServerPlaylists.value = playlists
                    playlists.forEach(ui::register)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: java.io.IOException) { /* Retain the last successful list. */ }
            try {
                val starred = musicServer.starredTracks()
                if (serverLibrary.value == library) serverStars.value = starred
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: java.io.IOException) { /* Keep favorites already shown. */ }
        }
    }

    fun ownsServerPlaylist(item: Music) = musicServer.ownsPlaylist(item)

    private fun storeServerPlaylist(detail: CatalogDetail) {
        val playlist = detail.item
        ui.register(playlist)
        mutableDetails.update { it + (playlist.id to DetailUi(detail)) }
        mutableServerPlaylists.update { rows ->
            if (rows.any { it.id == playlist.id }) rows.map { if (it.id == playlist.id) playlist else it }
            else rows + playlist
        }
    }

    private fun loadServerPlaylist(item: Music) {
        val shown = mutableDetails.value[item.id]
        if (shown?.loading == true) return
        mutableDetails.update { it + (item.id to DetailUi(shown?.content, loading = true)) }
        viewModelScope.launch {
            try { storeServerPlaylist(musicServer.playlist(item.id)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableDetails.update { it + (item.id to DetailUi(shown?.content, error = uiContext.getString(R.string.music_server_error_response))) }
            }
        }
    }

    internal suspend fun createServerPlaylist(name: String, tracks: List<Music>): Music =
        musicServer.createPlaylist(name, tracks).also(::storeServerPlaylist).item

    internal suspend fun editServerPlaylist(item: Music, name: String? = null, tracks: List<Music> = emptyList(), remove: Int? = null) {
        musicServer.updatePlaylist(item, name, tracks, remove)
        loadServerPlaylist(item)
        refreshServerPlaylists()
    }

    private fun deleteServerPlaylist(item: Music) {
        if (!ownsServerPlaylist(item)) return
        viewModelScope.launch {
            try {
                musicServer.deletePlaylist(item)
                mutableDetails.update { it - item.id }
                mutableServerPlaylists.update { rows -> rows.filterNot { it.id == item.id } }
                pinnedStore.pins.value.firstOrNull { it.id == item.id }?.let(pinnedStore::toggle)
                showMessage(uiContext.getString(R.string.playlist_deleted, item.title))
                refreshServerPlaylists()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showMessage(uiContext.getString(R.string.music_server_error_response)) }
        }
    }
    private val mutableRecent = MutableStateFlow<List<Music>>(emptyList())
    val recent = mutableRecent.asStateFlow()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()

    /*
     * Everything the start-up work below can reach is declared above it. Kotlin sets properties in
     * the order they are written, and the coroutines `init` starts run at once on the main thread:
     * one that reached a property written further down found it still null, and `ui`, built then
     * for a saved local playlist, kept that null and crashed the player's queue.
     */
    private val requestedCovers = HashSet<String>()
    private var coversRequested = 0
    private var upNextJob: Job? = null
    private var radioRefreshJob: Job? = null
    private var radioAutoRefillJob: Job? = null
    private var lastRadioAutoRefillTrackId: String? = null
    private var radioAutoNoFreshSeedKey: String? = null
    private val mutableRadioRefreshInProgress = MutableStateFlow(false)
    val radioRefreshInProgress = mutableRadioRefreshInProgress.asStateFlow()
    internal val spotifyPlaylists = SpotifyPlaylists(app)
    private var lyrics: SpotifyLyricsSource? = null
    private val mutableConversion = MutableStateFlow<`in`.melo.data.PlaylistConversion?>(null)
    /** The conversion shown by the converter, kept while its screen is open. */
    val conversion = mutableConversion.asStateFlow()

    init {
        viewModelScope.launch {
            serverLibrary.collect {
                mutableServerPlaylists.value = emptyList()
                serverStars.value = emptyList()
                refreshServerPlaylists()
            }
        }
        if (mutableGuest.value) installLibrary(emptyLibrary(), false)
        `in`.melo.data.addons.AddonResolver.init(app)
        viewModelScope.launch {
            `in`.melo.data.addons.AddonStore.get(app).addons.collect { list ->
                mutableDiscovery.update { it.copy(addonsEnabled = list.any { addon -> addon.enabled }) }
            }
        }
        viewModelScope.launch {
            localPlaylistStore.playlists.collect { playlists ->
                playlists.forEach { ui.register(it.asMusic()) }
                val known = mutableDetails.value.keys.filter { it.startsWith(LOCAL_PLAYLIST_PREFIX) }
                known.forEach(::installLocalPlaylist)
            }
        }
        refreshLocalTracks()
        refreshServerPlaylists()
        viewModelScope.launch {
            // One history entry each time a queue position starts playing.
            var counted: String? = null
            player.state.collect { playback ->
                val track = playback.track
                val position = track?.let { "${playback.index}:${it.id}" }
                if (position == null) counted = null
                else if (playback.playing && position != counted) {
                    counted = position
                    withContext(Dispatchers.IO) { listeningHistory.record(track) }
                }
                if (track != null && playback.playing && mutableRecent.value.firstOrNull()?.id != track.id) {
                    mutableRecent.update { (listOf(track) + it.filterNot { previous -> previous.id == track.id }).take(12) }
                }
            }
        }
        viewModelScope.launch {
            player.state.collect(::considerRadioAutoRefill)
        }
        viewModelScope.launch {
            var wasOnline = online.value
            online.collect { now ->
                if (now) considerRadioAutoRefill(player.state.value)
                if (now && !wasOnline) refreshServerPlaylists()
                if (now && !wasOnline && collections != null) {
                    refreshSpotifyAccountProfile()
                    sendPendingFavorites()
                    val age = withContext(Dispatchers.IO) { store.libraryAgeMs() }
                    if (age > LIBRARY_TTL_MS) refreshLibrary()
                }
                wasOnline = now
            }
        }
        accountJob = viewModelScope.launch {
            mutableAccount.value = AccountUi(working = true, restoring = true)
            try {
                val saved = withContext(Dispatchers.IO) {
                    vault.removeLegacySession()
                    runCatching { vault.load() }.getOrNull()
                }
                if (saved != null) {
                    // Activity recreation must keep the session that owns active audio handles.
                    SpotifyAccount.restore(app)?.let(::finishAccount)
                }
                mutableAccount.value = AccountUi()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                showAccountError(failure)
            }
        }
    }

    fun toggleFavorite(item: Music) {
        if (`in`.melo.data.server.MusicServerUris.isTrack(item.id)) {
            viewModelScope.launch {
                if (!pendingMutations.add(item.id)) return@launch
                try {
                    val starred = serverStars.value.none { it.id == item.id }
                    musicServer.setStarred(item, starred)
                    serverStars.update { rows -> if (starred) listOf(item) + rows.filterNot { it.id == item.id } else rows.filterNot { it.id == item.id } }
                    if (mutableDetails.value.containsKey(LIKED_SONGS)) loadDetail(ui.likedSongs, refresh = true)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { showMessage(uiContext.getString(R.string.music_server_error_response)) }
                finally { pendingMutations.remove(item.id) }
            }
            return
        }
        if (item.isLocalTrack || item.isLocalPlaylist || item.source != `in`.melo.data.MusicSource.Spotify) return
        if (item.kind != MusicKind.Track && item.kind != MusicKind.Album && item.kind != MusicKind.Artist) {
            mutableMessage.value = uiContext.getString(R.string.favorite_change_unavailable)
            return
        }
        val source = collections ?: return
        if (!pendingMutations.add(item.id)) return
        val saved = item.id !in savedIds.value
        changeFavorite(FavoriteChange(item, saved))
        viewModelScope.launch {
            try { sendFavorite(source, item, saved) } finally { pendingMutations.remove(item.id) }
        }
    }

    private fun changeFavorite(change: FavoriteChange) {
        favoriteChanges[change.item.id] = change
        savePendingFavorites()
        val current = mutableLibrary.value
        if (current.content != null) installLibrary(current.content, current.loading)
        else savedIds.update { if (change.saved) it + change.item.id else it - change.item.id }
    }

    /** Refused by the service: undone. Not reached: kept and sent with the next connection. */
    private suspend fun sendFavorite(source: SpotifyLibrary, item: Music, saved: Boolean) {
        try {
            source.setSaved(item, saved)
            if (favoriteChanges[item.id]?.saved == saved) {
                favoriteChanges[item.id] = FavoriteChange(item, saved, System.currentTimeMillis())
            }
            savePendingFavorites()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (auth: SpotifyAuthRequired) { accountLost(auth) }
        catch (failure: SpotifyFailure) {
            if (failure.status == null) return
            favoriteChanges.remove(item.id)
            savePendingFavorites()
            val current = mutableLibrary.value
            savedIds.update { if (saved) it - item.id else it + item.id }
            if (current.content != null) installLibrary(current.content, current.loading)
            mutableMessage.value = readable(failure)
        } catch (failure: IOException) {
            // Kept for the next connection.
            android.util.Log.i("MeloLibrary", "Favorite kept for later: ${failure.javaClass.simpleName}")
        }
    }

    private fun savePendingFavorites() {
        val pending = favoriteChanges.values.filter { it.confirmedAtMs == null }
        preferences.edit {
            putString(KEY_PENDING_LIKES, encodePins(pending.filter { it.saved }.map { it.item }))
            putString(KEY_PENDING_UNLIKES, encodePins(pending.filterNot { it.saved }.map { it.item }))
        }
    }

    private fun loadPendingFavorites() {
        decodePins(preferences.getString(KEY_PENDING_LIKES, null)).forEach { favoriteChanges[it.id] = FavoriteChange(it, true) }
        decodePins(preferences.getString(KEY_PENDING_UNLIKES, null)).forEach { favoriteChanges[it.id] = FavoriteChange(it, false) }
    }

    private fun sendPendingFavorites() {
        val source = collections ?: return
        favoriteChanges.values.filter { it.confirmedAtMs == null }.forEach { change ->
            if (pendingMutations.add(change.item.id)) viewModelScope.launch {
                try { sendFavorite(source, change.item, change.saved) } finally { pendingMutations.remove(change.item.id) }
            }
        }
    }

    fun refreshLibrary() {
        refreshServerPlaylists()
        val source = collections ?: return
        if (!online.value && mutableLibrary.value.content != null) return
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            mutableLibrary.update { it.copy(loading = true, error = null) }
            val previous = mutableLibrary.value.content
            try {
                val result = source.load(previous) { installLibrary(it, true) }
                installLibrary(result, false)
                saveLibrary()
                withContext(BackgroundRequests) { completeSavedTracks(source) }
                keepCatalogAvailable()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (auth: SpotifyAuthRequired) { accountLost(auth) }
            catch (failure: Exception) {
                `in`.melo.data.diagnostics.DiagnosticLog.record("library", "${failure.javaClass.simpleName} \"${readable(failure)}\"")
                // What was last read stays on screen, without an error for what the connection prevented.
                mutableLibrary.update { it.copy(loading = false, error = if (it.content == null) readable(failure) else null) }
            }
        }
    }
    private fun saveLibrary() {
        val content = mutableLibrary.value.content ?: return
        viewModelScope.launch(Dispatchers.IO) { store.saveLibrary(content) }
    }
    /** Fetches, page by page, the metadata of liked songs this device does not have yet. */
    private suspend fun completeSavedTracks(source: SpotifyLibrary) {
        var pages = 0
        while (online.value) {
            val content = mutableLibrary.value.content ?: return
            if (content.savedTracksLoaded >= content.savedTrackUris.size) break
            val before = content.savedTracksLoaded
            val next = source.loadMore(content)
            if (next.savedTracksLoaded == before) break
            installLibrary(next, false)
            if (++pages % SAVE_EVERY_PAGES == 0) saveLibrary()
            delay(BACKGROUND_REQUEST_GAP_MS)
        }
        if (pages > 0) saveLibrary()
    }
    /**
     * Keeps every page of the library readable without network, a little at a time. Pages come back
     * with what the device already knows, so only what actually changed costs a metadata request.
     */
    private fun keepCatalogAvailable() {
        val catalogSource = catalog ?: return
        offlineSyncJob?.cancel()
        offlineSyncJob = viewModelScope.launch(BackgroundRequests) {
            val content = mutableLibrary.value.content ?: return@launch
            val pinned = pins.value.filter { it.id.startsWith("spotify:") && it.id != LIKED_SONGS && it.kind != MusicKind.Track }
            val items = (pinned + content.playlists + content.albums + content.artists).distinctBy(Music::id).take(MAX_BACKGROUND_PAGES)
            items.forEach { item ->
                if (!online.value) return@launch
                val age = withContext(Dispatchers.IO) { store.detailAgeMs(item.id) }
                if (age < revalidateAfterMs(item) || detailJobs[item.id]?.isActive == true) return@forEach
                try {
                    val known = withContext(Dispatchers.IO) { store.loadDetail(item.id) }
                    known?.let { catalogSource.seed(it.tracks + it.related) }
                    val full = fullDetail(catalogSource, item)
                    withContext(Dispatchers.IO) { store.saveDetail(item.id, full) }
                    rememberCover(full)
                    keepCovers(full)
                    delay(BACKGROUND_REQUEST_GAP_MS)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IOException) { /* The next start tries again. */ }
            }
        }
    }
    /** On an unmetered network, the covers a stored page shows, so it looks the same without network. */
    private fun keepCovers(detail: CatalogDetail) {
        val network = app.getSystemService(android.net.ConnectivityManager::class.java)
        if (network.isActiveNetworkMetered || coversRequested >= MAX_BACKGROUND_COVERS) return
        val loader = coil.Coil.imageLoader(app)
        (listOf(detail.item) + detail.tracks.take(COVERS_PER_PAGE)).mapNotNull(Music::imageUrl).distinct()
            .filter(requestedCovers::add).forEach { url ->
                if (coversRequested++ < MAX_BACKGROUND_COVERS) loader.enqueue(
                    coil.request.ImageRequest.Builder(app).data(url).memoryCachePolicy(coil.request.CachePolicy.DISABLED).build(),
                )
            }
    }
    private suspend fun fullDetail(source: SpotifyCatalog, item: Music, atLeast: Int = Int.MAX_VALUE, pageLimit: Int = MAX_DETAIL_PAGES): CatalogDetail {
        var detail = source.detail(item)
        var pages = 1
        val cursors = HashSet<String>()
        while (detail.nextPage != null && detail.tracks.size < atLeast && pages < pageLimit) {
            val cursor = checkNotNull(detail.nextPage)
            if (!cursors.add(cursor)) throw SpotifyFailure("Spotify returned a repeated page. Try again.")
            val next = source.detail(item, cursor)
            detail = next.copy(tracks = detail.tracks + next.tracks)
            pages++
        }
        return detail
    }
    private fun revalidateAfterMs(item: Music): Long = when (item.kind) {
        MusicKind.Playlist, MusicKind.Radio -> PLAYLIST_TTL_MS
        MusicKind.Artist -> ARTIST_TTL_MS
        else -> ALBUM_TTL_MS
    }
    private fun installLibrary(content: HomeLibrary, loading: Boolean) {
        val previous = mutableLibrary.value.content
        val merged = if (loading && content.savedTracksLoaded == 0 && previous != null) content.copy(
            albums = previous.albums, artists = previous.artists, savedTracks = previous.savedTracks,
            savedTrackCount = previous.savedTrackCount, savedTrackUris = previous.savedTrackUris,
            savedTracksLoaded = previous.savedTracksLoaded,
        ) else content
        // A read can predate a like: confirmed changes stay on top for as long as the service may lag.
        val now = System.currentTimeMillis()
        favoriteChanges.values.removeAll { it.confirmedAtMs != null && now - it.confirmedAtMs > FAVORITE_LAG_MS }
        val visible = applyFavoriteChanges(merged, favoriteChanges.values).let { library ->
            library.copy(playlists = library.playlists.map { playlist ->
                val mosaic = playlistCovers[playlist.id]
                when {
                    playlist.imageUrl == null -> mosaic?.let { playlist.copy(imageUrl = it) } ?: playlist.also { coverFromStoredPage(it.id) }
                    mosaic != null && playlist.imageUrl != mosaic -> {
                        // The playlist got a picture of its own.
                        playlistCovers.remove(playlist.id); saveCovers(); playlist
                    }
                    else -> playlist
                }
            })
        }
        // Rootlist refreshes carry the latest playlist title and other metadata. Keep the entity
        // cache and any already-open detail page in step with those refreshed library records;
        // otherwise the track list updates while a cached detail continues showing the old name.
        val refreshedPlaylists = visible.playlists.associateBy(Music::id)
        refreshedPlaylists.values.forEach(ui::register)
        if (refreshedPlaylists.isNotEmpty()) {
            mutableDetails.update { details ->
                details.mapValues { (id, state) ->
                    val latest = refreshedPlaylists[id]
                    val detail = state.content
                    if (latest == null || detail == null) return@mapValues state
                    val latestTrackCount = parseSongCount(latest.subtitle) ?: detail.total
                    if (detail.item != latest || detail.total != latestTrackCount) {
                        state.copy(content = detail.copy(item = latest, total = latestTrackCount))
                    } else state
                }
            }
        }
        val warning = visible.warning.takeIf { online.value && visible.playlists.isEmpty() }
        mutableLibrary.value = LibraryUi(visible, loading, warning)
        savedIds.value = (visible.savedTrackUris + (visible.albums + visible.artists + visible.playlists).map(Music::id)).toSet()
        val liked = mutableDetails.value[LIKED_SONGS]?.content
        if (liked != null) mutableDetails.update { it + (LIKED_SONGS to DetailUi(liked.copy(
            tracks = visible.savedTracks,
            nextPage = visible.savedTracksLoaded.takeIf { size -> size < visible.savedTrackUris.size }?.toString(),
            total = visible.savedTrackCount,
        ))) }
    }
    fun loadMoreSavedTracks() {
        val source = collections ?: return
        val current = mutableLibrary.value.content ?: return
        if (mutableLibrary.value.loading || current.savedTracksLoaded >= current.savedTrackUris.size) return
        libraryJob = viewModelScope.launch {
            mutableLibrary.update { it.copy(loading = true, error = null) }
            try { installLibrary(source.loadMore(current), false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableLibrary.update { it.copy(loading = false, error = readable(failure)) } }
        }
    }
    fun loadDetail(item: Music, refresh: Boolean = false, more: Boolean = false) {
        if (item.id == LIKED_SONGS && serverLibrary.value != null) {
            val stored = mutableLibrary.value.content?.savedTracks.orEmpty()
            val tracks = (serverStars.value + stored).distinctBy(Music::id)
            mutableDetails.update { it + (item.id to DetailUi(CatalogDetail(item, tracks))) }
            return
        }
        if (`in`.melo.data.server.MusicServerUris.isPlaylist(item.id)) { loadServerPlaylist(item); return }
        if (item.kind == MusicKind.Artist && !more) {
            if (lastOpenedArtist != item) {
                lastOpenedArtist = item
                preferences.edit { putString(KEY_LAST_OPEN_ARTIST, encodePins(listOf(item))) }
            }
        }
        if (item.isLocalPlaylist) { installLocalPlaylist(item.id); return }
        if (catalog == null && item.id != LIKED_SONGS && item.id.startsWith("spotify:") && item.kind != MusicKind.Track) {
            loadPublicSpotifyDetail(item, refresh)
            return
        }
        if (item.id == LIKED_SONGS && catalog == null) {
            // Without a Spotify account the liked songs are the saved tracks kept on the device.
            val saved = mutableLibrary.value.content ?: return
            mutableDetails.update { it + (item.id to DetailUi(CatalogDetail(item, saved.savedTracks, total = saved.savedTrackCount))) }
            return
        }
        val source = catalog ?: return
        val shown = mutableDetails.value[item.id]
        if (shown?.loading == true && !refresh) return
        detailJobs[item.id]?.cancel()
        detailJobs[item.id] = viewModelScope.launch {
            mutableDetails.update { it + (item.id to (shown ?: DetailUi()).copy(loading = true, error = null)) }
            // What the device knows is on screen at once; the service is only asked when it is old.
            var stored: CatalogDetail? = null
            if (shown?.content == null && item.id != LIKED_SONGS) {
                stored = withContext(Dispatchers.IO) { store.loadDetail(item.id) }?.let { page ->
                    // Pages stored before mosaics took the first track's cover for a playlist without picture.
                    val bare = mutableLibrary.value.content?.playlists?.firstOrNull { it.id == item.id }?.let { it.id in playlistCovers } == true
                    if (bare) page.copy(item = page.item.copy(imageUrl = playlistMosaic(page.tracks) ?: page.item.imageUrl)) else page
                }
                stored?.let {
                    source.seed(it.tracks + it.related)
                    mutableDetails.update { all -> all + (item.id to DetailUi(it, loading = true)) }
                }
            }
            val previous = stored?.let { DetailUi(it) } ?: shown
            if (!more && !refresh && item.id != LIKED_SONGS && previous?.content != null) {
                val age = withContext(Dispatchers.IO) { store.detailAgeMs(item.id) }
                // An artist page stored by the library sync has no profile yet: read it once opened.
                val incomplete = item.kind == MusicKind.Artist && previous.content.artist == null
                // Opened by the user, an artist page shows current figures (monthly listeners): it is read again
                // after an hour, while the background sync keeps its weekly pace.
                val fresh = age < if (item.kind == MusicKind.Artist) ARTIST_OPEN_TTL_MS else revalidateAfterMs(item)
                if (!online.value || (fresh && !incomplete)) {
                    mutableDetails.update { it + (item.id to DetailUi(previous.content)) }
                    return@launch
                }
            }
            try {
                val page = if (more) previous?.content?.nextPage else null
                val result = if (item.id == LIKED_SONGS) {
                    val saved = mutableLibrary.value.content
                    val from = page?.toIntOrNull() ?: 0
                    val uris = saved?.savedTrackUris.orEmpty()
                    val tracks = if (from == 0) saved?.savedTracks.orEmpty() else source.hydrate(uris.drop(from).take(100))
                    val next = if (from == 0) saved?.savedTracksLoaded ?: tracks.size else from + 100
                    CatalogDetail(item.copy(note = "${saved?.savedTrackCount ?: 0} saved tracks"), tracks,
                        nextPage = next.takeIf { it < uris.size }?.toString(), total = saved?.savedTrackCount ?: 0)
                } else if (more) source.detail(item, page)
                // Revalidating must not shorten what was already read in several pages. If the
                // previously loaded list ended at its last page, follow the refreshed page tokens
                // to the new end too; an external addition may have created a new final page.
                else {
                    val wasFullyLoaded = refresh && previous?.content?.nextPage == null
                    fullDetail(
                        source,
                        item,
                        atLeast = if (wasFullyLoaded) Int.MAX_VALUE else previous?.content?.tracks?.size ?: 0,
                        pageLimit = if (wasFullyLoaded) Int.MAX_VALUE else MAX_DETAIL_PAGES,
                    )
                }
                val merged = if (more && previous?.content != null) result.copy(tracks = previous.content.tracks + result.tracks) else result
                mutableDetails.update { it + (item.id to DetailUi(merged)) }
                if (item.id.startsWith("spotify:playlist:")) syncSpotifyPlaylistSummary(merged.item, merged.total)
                rememberCover(merged)
                if (item.id != LIKED_SONGS) withContext(Dispatchers.IO) { store.saveDetail(item.id, merged) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (auth: SpotifyAuthRequired) { accountLost(auth) }
            catch (failure: Exception) {
                val known = previous?.content
                `in`.melo.data.diagnostics.DiagnosticLog.record("catalog",
                    "${item.kind.name.lowercase()} page=${if (more) "next" else "first"} stored=${known != null} ${failure.javaClass.simpleName} \"${readable(failure)}\"")
                // Known pages stay readable; only what cannot be shown at all is an error.
                mutableDetails.update { it + (item.id to if (known != null) DetailUi(known) else DetailUi(error = readable(failure))) }
            }
        }
    }

    private fun loadPublicSpotifyDetail(item: Music, refresh: Boolean) {
        val shown = mutableDetails.value[item.id]
        if (shown?.loading == true && !refresh) return
        if (shown?.content != null && !refresh) return
        detailJobs[item.id]?.cancel()
        detailJobs[item.id] = viewModelScope.launch {
            mutableDetails.update { it + (item.id to (shown ?: DetailUi()).copy(loading = true, error = null)) }
            val stored = shown?.content ?: withContext(Dispatchers.IO) { store.loadDetail(item.id) }
            if (stored != null) {
                mutableDetails.update { it + (item.id to DetailUi(stored, loading = online.value && refresh)) }
                if (!refresh || !online.value) return@launch
            }
            if (!online.value) {
                if (stored == null) mutableDetails.update { it + (item.id to DetailUi(error = uiContext.getString(`in`.melo.R.string.spotify_link_unavailable))) }
                return@launch
            }
            try {
                val kind = item.id.substringAfter("spotify:").substringBefore(':')
                val id = item.id.substringAfterLast(':')
                val public = withContext(Dispatchers.IO) { `in`.melo.data.SpotifyPublicPlaylist.readEntity(kind, id) }
                val resolved = item.copy(
                    title = public.name.ifBlank { item.title },
                    imageUrl = public.imageUrl ?: `in`.melo.data.playlistMosaic(public.tracks),
                    subtitle = if (item.kind == MusicKind.Playlist) `in`.melo.utils.formatSongCount(public.tracks.size) else item.subtitle,
                )
                if (public.tracks.isEmpty() && item.kind != MusicKind.Artist) {
                    throw java.io.IOException(uiContext.getString(`in`.melo.R.string.spotify_link_unavailable))
                }
                val detail = CatalogDetail(resolved, public.tracks, total = public.tracks.size)
                ui.register(resolved)
                mutableDetails.update { it + (item.id to DetailUi(detail)) }
                withContext(Dispatchers.IO) { store.saveDetail(item.id, detail) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableDetails.update { it + (item.id to if (stored != null) DetailUi(stored) else DetailUi(error = readable(failure))) }
            }
        }
    }
    /** Keeps playlist counts aligned across the detail page, library cards and the disk cache. */
    private fun syncSpotifyPlaylistSummary(item: Music, total: Int) {
        if (!item.id.startsWith("spotify:playlist:")) return
        val latest = item.copy(subtitle = formatSongCount(total))
        ui.register(latest)
        mutableDetails.update { details ->
            val state = details[latest.id] ?: return@update details
            val detail = state.content ?: return@update details
            details + (latest.id to state.copy(content = detail.copy(item = latest, total = total)))
        }
        val current = mutableLibrary.value.content ?: return
        if (current.playlists.none { it.id == latest.id }) return
        val playlists = current.playlists.map { if (it.id == latest.id) latest else it }
        mutableLibrary.update { state ->
            val content = state.content ?: return@update state
            state.copy(content = content.copy(playlists = content.playlists.map { if (it.id == latest.id) latest else it }))
        }
        if (playlists != current.playlists) saveLibrary()
    }
    private fun installLocalPlaylist(id: String) {
        val playlist = localPlaylistStore.get(id) ?: return
        val music = playlist.asMusic()
        ui.register(music)
        mutableDetails.update { it + (id to DetailUi(CatalogDetail(music, playlist.tracks))) }
    }
    fun playWithRadio(track: Music) {
        play(listOf(track), 0)
        if (track.isLocalTrack || `in`.melo.data.server.MusicServerUris.isTrack(track.id) || !online.value) return
        upNextJob = viewModelScope.launch {
            try {
                player.extendContext(track.id, upNext(track))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { }
        }
    }

    /** The tracks that follow [seed] when nothing else was chosen: its radio, without the seed itself. */
    private suspend fun upNext(seed: Music): List<Music> {
        if (seed.source == `in`.melo.data.MusicSource.MusicServer) {
            return musicServer.tracks(seed.subtitle, 0).songs.filter { it.id != seed.id && it.subtitle == seed.subtitle }
        }
        return radio?.station(seed)?.tracks.orEmpty().filterNot { it.id == seed.id }
    }

    private fun considerRadioAutoRefill(playback: Playback) {
        val currentId = playback.track?.id
        val tailId = playback.contextTailId
        if (!playback.canRefreshRecommendations || currentId == null || tailId == null ||
            playback.remainingContextTracks > RADIO_REFILL_THRESHOLD
        ) {
            lastRadioAutoRefillTrackId = null
            return
        }
        if (currentId == lastRadioAutoRefillTrackId) return
        if (!online.value) return
        if (startRadioAutoRefill(tailId)) lastRadioAutoRefillTrackId = currentId
    }

    /** Starts the next radio fetch while several tracks are still queued. */
    private fun startRadioAutoRefill(expectedTailId: String): Boolean {
        if (!online.value || radioAutoRefillJob?.isActive == true) return false
        val (context, _) = player.context()
        if (context.lastOrNull()?.id != expectedTailId) return false
        val seeds = context.asReversed().asSequence()
            .filter { it.kind == MusicKind.Track && !it.isLocalTrack }
            .distinctBy(Music::id)
            .take(RADIO_REFILL_SEED_COUNT)
            .toList()
        if (seeds.isEmpty()) return true
        val seedKey = seeds.joinToString("\u001f") { it.id }
        if (seedKey == radioAutoNoFreshSeedKey) return true
        val excludedIds = context.mapTo(HashSet(), Music::id).apply {
            addAll(player.seenRadioRecommendationIds())
        }

        radioAutoRefillJob = viewModelScope.launch {
            try {
                val recommendations = freshRadioBatch(seeds, excludedIds)
                if (recommendations.isNotEmpty()) {
                    if (player.appendRadioRecommendations(expectedTailId, recommendations)) {
                        radioAutoNoFreshSeedKey = null
                    }
                } else if (player.context().first.lastOrNull()?.id == expectedTailId) {
                    radioAutoNoFreshSeedKey = seedKey
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Playback continues through the existing queue; another track can retry.
            }
        }
        return true
    }

    /** Merge up to three nearby radio seeds into one deduplicated batch. */
    private suspend fun freshRadioBatch(seeds: List<Music>, excludedIds: Set<String>): List<Music> {
        val excluded = excludedIds.toMutableSet()
        val fresh = linkedMapOf<String, Music>()
        for (seed in seeds) {
            for (track in upNext(seed)) {
                if (track.kind == MusicKind.Track && !track.isLocalTrack && excluded.add(track.id)) {
                    fresh.putIfAbsent(track.id, track)
                }
                if (fresh.size >= RADIO_REFILL_BATCH_SIZE) break
            }
            if (fresh.size >= RADIO_REFILL_BATCH_SIZE) break
        }
        return fresh.values.toList()
    }

    /** Fetches a fresh radio tail while keeping the current song and the user's queued tracks. */
    fun refreshRadioRecommendations() {
        if (radioRefreshJob?.isActive == true || !player.state.value.canRefreshRecommendations) return
        radioAutoRefillJob?.cancel()
        val playback = player.state.value
        val seed = playback.track ?: return
        if (seed.isLocalTrack || !online.value) {
            if (!online.value) mutableMessage.value = uiContext.getString(`in`.melo.R.string.queue_recommendations_offline)
            return
        }

        mutableRadioRefreshInProgress.value = true
        radioRefreshJob = viewModelScope.launch {
            try {
                val (context, currentIndex) = player.context()
                val excludedIds = context.mapTo(HashSet(), Music::id).apply {
                    addAll(player.seenRadioRecommendationIds())
                }
                val alternateSeeds = context.drop((currentIndex + 1 + playback.queuedCount).coerceAtLeast(0))
                    .asSequence()
                    .filter { it.kind == MusicKind.Track && !it.isLocalTrack }
                    .distinctBy(Music::id)
                    .take(RADIO_REFILL_SEED_COUNT - 1)
                    .toList()

                val recommendations = freshRadioBatch(
                    (listOf(seed) + alternateSeeds).distinctBy(Music::id),
                    excludedIds,
                )

                if (player.state.value.track?.id != seed.id) return@launch
                if (recommendations.isEmpty()) {
                    mutableMessage.value = uiContext.getString(`in`.melo.R.string.queue_recommendations_no_new)
                    return@launch
                }
                if (!player.replaceRadioRecommendations(seed.id, recommendations) &&
                    player.state.value.track?.id == seed.id
                ) {
                    mutableMessage.value = uiContext.getString(`in`.melo.R.string.queue_recommendations_no_new)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableMessage.value = readable(failure)
            } finally {
                mutableRadioRefreshInProgress.value = false
            }
        }
    }

    fun loadRadio(seed: Music, open: (Music) -> Unit) {
        if (seed.source == `in`.melo.data.MusicSource.MusicServer) {
            playWithRadio(seed)
            return
        }
        val source = radio ?: return
        viewModelScope.launch {
            try {
                val result = source.station(seed)
                mutableDetails.update { it + (result.item.id to DetailUi(result)) }
                open(result.item)
                val firstTrack = result.tracks.indexOfFirst { it.kind == MusicKind.Track }
                if (firstTrack >= 0) {
                    play(result.tracks, firstTrack)
                    player.markRadioContext()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableMessage.value = readable(failure) }
        }
    }
    suspend fun trackRelations(track: Music): List<Music> =
        if (track.source == `in`.melo.data.MusicSource.Spotify && catalog != null) detailForActions(track)?.related.orEmpty()
        else emptyList()

    private suspend fun detailForActions(item: Music): CatalogDetail? {
        mutableDetails.value[item.id]?.content?.let { return it }
        withContext(Dispatchers.IO) { store.loadDetail(item.id) }?.let { return it }
        val fetched = catalog?.detail(item) ?: return null
        withContext(Dispatchers.IO) { store.saveDetail(item.id, fetched) }
        return fetched
    }

    internal suspend fun createSpotifyPlaylist(name: String, tracks: List<Music>, published: Boolean): Music {
        val created = spotifyPlaylists.create(name, tracks, published)
        ui.register(created)
        val current = mutableLibrary.value.content
        if (current != null) installLibrary(current.copy(playlists = listOf(created) + current.playlists.filterNot { it.id == created.id }), false)
        mutableDetails.update { it + (created.id to DetailUi(CatalogDetail(created, tracks, total = tracks.size))) }
        withContext(Dispatchers.IO) { store.saveDetail(created.id, CatalogDetail(created, tracks, total = tracks.size)) }
        return created
    }
    internal fun spotifyPlaylistChanged(playlist: Music) {
        ui.register(playlist)
        mutableLibrary.value.content?.let { content ->
            installLibrary(content.copy(playlists = content.playlists.map { if (it.id == playlist.id) playlist else it }), false)
        }
        loadDetail(playlist, refresh = true)
    }
    internal suspend fun playlistSuggestions(playlist: Music, tracks: List<Music>): List<Music> =
        spotifyPlaylists.recommendations(playlist.id, tracks, mutableLibrary.value.content?.savedTracks.orEmpty())

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    internal suspend fun addSpotifyPlaylistTracks(playlist: Music, tracks: List<Music>) {
        val before = tracksOf(playlist)
        spotifyPlaylists.add(playlist.id, tracks) {
            detailJobs.remove(playlist.id)?.cancel()
            val content = mutableDetails.value[playlist.id]?.content ?: CatalogDetail(playlist, before)
            val total = content.total + tracks.size
            val updated = content.copy(item = content.item.copy(subtitle = formatSongCount(total)),
                tracks = before + tracks, total = total, nextPage = null)
            mutableDetails.update { it + (playlist.id to DetailUi(updated)) }
            syncSpotifyPlaylistSummary(updated.item, updated.total)
            withContext(Dispatchers.IO) { store.saveDetail(playlist.id, updated) }
            if (`in`.melo.playback.DownloadCenter.collections.value.containsKey(playlist.id)) {
                `in`.melo.playback.DownloadCenter.enqueue(app, updated.item, updated.tracks)
            }
        }
    }

    /** Melo's plays merged with the tracks Spotify reports for the account's other apps and devices. */
    internal suspend fun fullListeningHistory(): List<`in`.melo.data.HistoryEntry> {
        withContext(Dispatchers.IO) { listeningHistory.load() }
        val local = listeningHistory.entries.value
        val source = catalog ?: return `in`.melo.data.collapseRepeats(local)
        if (!online.value) return `in`.melo.data.collapseRepeats(local)
        val remote = runCatching {
            val plays = `in`.melo.data.spotifyRecentPlays(SpotifyAccount.require(app))
            val tracks = source.hydrate(plays.map { it.track }).associateBy(Music::id)
            plays.mapNotNull { play -> tracks[play.track]?.let { `in`.melo.data.HistoryEntry(it, play.playedAt, fromSpotify = true) } }
        }.getOrElse { failure -> if (failure is kotlinx.coroutines.CancellationException) throw failure; emptyList() }
        return `in`.melo.data.mergeHistory(local, remote)
    }

    /** Removes the track at [index] of the playlist as loaded, leaving other copies of it in place. */
    internal suspend fun removeSpotifyPlaylistTrack(playlist: Music, index: Int) {
        val content = mutableDetails.value[playlist.id]?.content ?: return
        val track = content.tracks.getOrNull(index) ?: return
        val occurrence = content.tracks.take(index).count { it.id == track.id }
        spotifyPlaylists.remove(playlist.id, track.id, occurrence) {
            detailJobs.remove(playlist.id)?.cancel()
            val latest = mutableDetails.value[playlist.id]?.content ?: content
            val position = if (latest.tracks.getOrNull(index)?.id == track.id) index
                else latest.tracks.withIndex().filter { it.value.id == track.id }.getOrNull(occurrence)?.index ?: return@remove
            // The next page starts at a row offset, which moves back by the removed row.
            val total = (latest.total - 1).coerceAtLeast(0)
            val updated = latest.copy(item = latest.item.copy(subtitle = formatSongCount(total)),
                tracks = latest.tracks.toMutableList().apply { removeAt(position) }, total = total,
                nextPage = latest.nextPage?.toIntOrNull()?.let { (it - 1).coerceAtLeast(0).toString() } ?: latest.nextPage)
            mutableDetails.update { it + (playlist.id to DetailUi(updated)) }
            syncSpotifyPlaylistSummary(updated.item, updated.total)
            withContext(Dispatchers.IO) { store.saveDetail(playlist.id, updated) }
        }
    }

    /** Downloads follow the account's own right to keep music offline. */
    suspend fun offlineAllowed(): Boolean {
        val session = SpotifyAccount.require(getApplication())
        val offline = spotifyAttribute(transport, session, "offline")
        return offline == null && spotifyAttribute(transport, session, "type") == "premium" || offline == "true" || offline == "1"
    }
    fun lyricsSource(): SpotifyLyricsSource? = lyrics
    fun showMessage(text: String) { mutableMessage.value = text }
    suspend fun tracksOf(item: Music): List<Music> { return when {
        item.id == LIKED_SONGS -> {
            if (online.value) collections?.let { completeSavedTracks(it) }
            (serverStars.value + mutableLibrary.value.content?.savedTracks.orEmpty()).distinctBy(Music::id)
        }
        item.isLocalPlaylist -> withLocalFiles(localPlaylistStore.get(item.id)?.tracks.orEmpty())
        `in`.melo.data.server.MusicServerUris.isPlaylist(item.id) -> musicServer.playlist(item.id).tracks
        item.isLocalTrack || item.kind == MusicKind.Track -> withLocalFiles(listOf(item))
        else -> {
            val known = detailForActions(item) ?: return emptyList()
            withLocalFiles(if (known.nextPage == null || !online.value) known.tracks
            else {
                val source = catalog ?: return withLocalFiles(known.tracks)
                val complete = fullDetail(source, item, pageLimit = Int.MAX_VALUE)
                mutableDetails.update { it + (item.id to DetailUi(complete)) }
                withContext(Dispatchers.IO) { store.saveDetail(item.id, complete) }
                complete.tracks
            })
        }
    }
    }
    /** Playback uses the queue items and matching device files. */
    fun play(queue: List<Music>, index: Int = 0) {
        upNextJob?.cancel()
        upNextJob = null
        radioRefreshJob?.cancel()
        radioAutoRefillJob?.cancel()
        mutableRadioRefreshInProgress.value = false
        lastRadioAutoRefillTrackId = null
        radioAutoNoFreshSeedKey = null
        val tracks = withLocalFiles(queue)
        val selected = tracks.getOrNull(index)
        if (selected != null && selected.isLocalTrack && selected.mediaUri == null) {
            mutableMessage.value = uiContext.getString(`in`.melo.R.string.local_file_missing, selected.title); return
        }
        player.play(tracks, index)
    }

    /** Local file references with the device file they point to, when there is one. */
    fun withLocalFiles(tracks: List<Music>): List<Music> {
        if (tracks.none { it.id.startsWith(`in`.melo.data.SPOTIFY_LOCAL_PREFIX) || (it.isLocalTrack && it.mediaUri == null) }) return tracks
        val files = mutableLocalTracks.value
        val byId = files.associateBy(Music::id)
        val index = localFileIndex(files)
        return tracks.map { track ->
            when {
                track.mediaUri != null -> track
                track.id.startsWith(`in`.melo.data.LOCAL_TRACK_PREFIX) -> byId[track.id] ?: index.match(track)
                    ?.let { track.copy(mediaUri = it.mediaUri, imageUrl = track.imageUrl ?: it.imageUrl) } ?: track
                else -> `in`.melo.data.withLocalFile(track, index)
            }
        }
    }
    @Volatile private var cachedFileIndex: `in`.melo.data.LocalFileIndex? = null
    /** Built once per list of device files, reused by every playlist and queue. */
    private fun localFileIndex(files: List<Music>): `in`.melo.data.LocalFileIndex =
        cachedFileIndex?.takeIf { it.files === files } ?: `in`.melo.data.LocalFileIndex(files).also { cachedFileIndex = it }
    fun consumeMessage() { mutableMessage.value = null }

    fun search(query: String, debounce: Boolean = true) {
        searchJob?.cancel()
        val trimmed = query.trim()
        // What is on screen stays until the first source answers for the new text: no blank list while typing.
        mutableDiscovery.update {
            it.copy(query = query, results = if (trimmed.length < 2) emptyList() else it.results,
                nextPage = null, serverOffset = null, error = null, loading = trimmed.length >= 2)
        }
        if (trimmed.length < 2) return
        if (discovery.value.activeSource == SearchSource.Local) { showDeviceResults(trimmed); return }
        if (!online.value) { showLocalResults(trimmed, null); return }
        if (discovery.value.activeSource == SearchSource.Addons) { loadAddonResults(trimmed, debounce); return }
        loadResults(trimmed, debounce)
    }

    /** Rows from every enabled add-on, played straight from the add-on that listed them. */
    private fun loadAddonResults(query: String, debounce: Boolean) {
        searchJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val rows = try {
                withContext(Dispatchers.IO) { `in`.melo.data.addons.AddonResolver.search(query) }
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
                android.util.Log.w("MeloSearch", "add-ons: ${failure.javaClass.simpleName}")
                emptyList()
            }
            val results = rows.filterNot { (_, row) -> row.isDolbyAtmos }.map { (addon, row) -> addonMusic(addon.id, row) }
                .distinctBy(Music::id)
            results.forEach(ui::register)
            mutableDiscovery.update {
                it.copy(loading = false, results = results,
                    error = if (results.isEmpty()) uiContext.getString(`in`.melo.R.string.addon_search_empty) else null)
            }
        }
    }

    private fun addonMusic(addonId: String, row: `in`.melo.data.addons.AddonTrack) = Music(
        id = `in`.melo.data.addons.AddonUris.trackId(addonId, row.id),
        title = row.title,
        subtitle = row.artist,
        kind = MusicKind.Track,
        imageUrl = row.artwork,
        durationMs = (row.durationSec ?: 0) * 1000,
        album = row.album,
        quality = listOfNotNull(
            row.format.ifBlank { null }?.uppercase(),
            when { row.isHiRes -> "Hi-Res"; row.isLossless -> "Lossless"; else -> null },
        ).joinToString(" · "),
    )

    fun setSearchSource(source: SearchSource) {
        if (discovery.value.source == source) return
        mutableDiscovery.update { it.copy(source = source) }
        search(discovery.value.query, debounce = false)
    }

    /** Device files whose title, artists or album hold every word of [query]. */
    private fun matchingDeviceFiles(query: String): List<Music> =
        mutableLocalTracks.value.filter { `in`.melo.utils.matchesWords(query, it.title, it.subtitle, it.album) }.take(MAX_LOCAL_RESULTS)

    /**
     * First page: every source of the active choice at once. Each one is shown as soon as it answers,
     * merged with what is already there; a failing one leaves the others.
     */
    private fun loadResults(query: String, debounce: Boolean) {
        val choice = discovery.value.activeSource
        searchJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val started = android.os.SystemClock.elapsedRealtime()
            val spotify = remote.takeIf { choice == SearchSource.All || choice == SearchSource.Spotify }
            val withServer = serverLibrary.value != null && (choice == SearchSource.All || choice == SearchSource.MusicServer)
            val locale = app.resources.configuration.locales.get(0) ?: Locale.getDefault()
            var spotifyItems: List<Music>? = null
            var serverSongs: List<Music>? = null
            val device = if (choice == SearchSource.All) matchingDeviceFiles(query) else emptyList()
            val failures = ArrayList<Throwable>()
            var pending = (if (spotify != null) 1 else 0) + (if (withServer) 1 else 0)
            fun publish() {
                val results = (if (choice == SearchSource.Spotify) spotifyItems.orEmpty()
                    else `in`.melo.data.SearchRanking.merge(query, listOfNotNull(
                        spotifyItems?.let { `in`.melo.data.SearchRanking.Source(it, 1.0) },
                        `in`.melo.data.SearchRanking.Source(serverSongs.orEmpty(), 1.03),
                        `in`.melo.data.SearchRanking.Source(device, 1.02, deduplicated = false),
                    )))
                val finished = pending == 0
                if (finished && results.isEmpty() && failures.isNotEmpty()) { showLocalResults(query, readableSearchFailure(failures.first())); return }
                if (!finished && results.isEmpty()) return
                mutableDiscovery.update { it.copy(loading = !finished, results = results, error = null) }
            }
            if (device.isNotEmpty()) publish()
            // Each source writes on the main thread, so the merges never overlap.
            fun <T> source(name: String, read: suspend () -> T, keep: (T) -> Unit) = launch {
                val result = try { Result.success(read()) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { Result.failure(failure) }
                android.util.Log.d("MeloSearch", "$name ${android.os.SystemClock.elapsedRealtime() - started} ms ${result.exceptionOrNull()?.let { "${it.javaClass.simpleName} ${it.message}" } ?: "ok"}")
                result.onSuccess(keep).onFailure { failures += it }
                pending--
                publish()
            }
            if (spotify != null) source("spotify", { spotify.search(query, locale, null) }) { page ->
                spotifyItems = page.items
                mutableDiscovery.update { it.copy(nextPage = page.nextToken) }
            }
            if (withServer) source("server", { musicServer.tracks(query, 0).songs }) { songs ->
                serverSongs = songs
                mutableDiscovery.update { it.copy(serverOffset = songs.size.takeIf { size -> size == `in`.melo.data.server.MusicServer.PAGE_SIZE }) }
            }
            if (pending == 0) publish()
        }
    }

    private fun readableSearchFailure(failure: Throwable): String = when (failure) {
        is `in`.melo.data.server.MusicServerException -> uiContext.getString(R.string.music_server_error_response)
        is SpotifyFailure -> spotifyFailureLabel(failure, R.string.search_request_interrupted)
        is InvalidProtocolBufferException -> uiContext.getString(R.string.spotify_error_response_invalid)
        else -> uiContext.getString(R.string.search_request_interrupted)
    }

    /** Next pages are added after what is shown, without moving it. */
    fun searchNext() {
        val state = discovery.value
        if (state.loading || !state.hasMore) return
        val query = state.query.trim()
        val spotifyToken = state.nextPage
        val serverOffset = state.serverOffset
        mutableDiscovery.update { it.copy(loading = true, error = null) }
        searchJob = viewModelScope.launch {
            val locale = app.resources.configuration.locales.get(0) ?: Locale.getDefault()
            val spotify = async { spotifyToken?.let { token -> remote?.let { source -> runCatching { source.search(query, locale, token) } } } }
            val server = async { serverOffset?.let { offset -> runCatching { musicServer.tracks(query, offset).songs } } }
            val spotifyPage = spotify.await()
            val serverPage = server.await()
            val added = `in`.melo.data.SearchRanking.merge(query, listOfNotNull(
                spotifyPage?.getOrNull()?.items?.let { `in`.melo.data.SearchRanking.Source(it) },
                serverPage?.getOrNull()?.let { `in`.melo.data.SearchRanking.Source(it, 0.97) },
            ))
            val failed = listOfNotNull(spotifyPage?.exceptionOrNull(), serverPage?.exceptionOrNull()).firstOrNull()
            mutableDiscovery.update {
                it.copy(loading = false, results = (it.results + added).distinctBy(Music::id),
                    nextPage = spotifyPage?.getOrNull()?.nextToken?.takeUnless { next -> next == spotifyToken },
                    serverOffset = serverPage?.getOrNull()?.size?.takeIf { size -> size == `in`.melo.data.server.MusicServer.PAGE_SIZE }?.let { size -> (serverOffset ?: 0) + size },
                    error = failed?.takeIf { _ -> added.isEmpty() }?.let(::readableSearchFailure))
            }
        }
    }

    /** Without the service, search what the device holds: the library, its pins and listened tracks. */
    private fun showLocalResults(query: String, failure: String?) {
        searchJob = viewModelScope.launch {
            val found = withContext(Dispatchers.Default) {
                val content = mutableLibrary.value.content
                val listened = `in`.melo.data.AudioIndex.get(app).all().map { it.track }.filter { it.title.isNotEmpty() }
                val candidates = pins.value + content?.playlists.orEmpty() + content?.albums.orEmpty() +
                    content?.artists.orEmpty() + content?.savedTracks.orEmpty() + listened +
                    localPlaylistStore.playlists.value.map { it.asMusic() } + mutableLocalTracks.value
                val wanted = query.lowercase()
                candidates.filter { it.title.lowercase().contains(wanted) || it.subtitle.lowercase().contains(wanted) }
                    .distinctBy(Music::id).take(MAX_LOCAL_RESULTS)
            }
            mutableDiscovery.update {
                it.copy(loading = false, results = found, nextPage = null, error = failure.takeIf { found.isEmpty() })
            }
        }
    }

    /** The explicit Device source must only return files indexed on this device. */
    private fun showDeviceResults(query: String) {
        searchJob = viewModelScope.launch {
            val found = withContext(Dispatchers.Default) { matchingDeviceFiles(query) }
            mutableDiscovery.update {
                it.copy(loading = false, results = found, nextPage = null, serverOffset = null, error = null)
            }
        }
    }

    fun connectSpotify(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) return
        connect { session -> session.connect(username.trim(), password, ::requestCode) }
    }

    fun connectSpotifyEmail(address: String) {
        val email = address.trim()
        if (email.isBlank()) return
        connect { session -> session.connectEmail(email, loginFlowId) { requestCode(it, email) } }
    }

    /** What Google returned for Spotify's client (code and ID token), read from the redirect before it loads. */
    fun connectSpotifyGoogle(authCode: String?, idToken: String?, redirectUri: String) {
        if (authCode.isNullOrBlank() && idToken.isNullOrBlank()) return
        connect { session -> session.connectGoogle(authCode?.takeIf(String::isNotBlank), idToken?.takeIf(String::isNotBlank), redirectUri, ::requestCode) }
    }

    private fun connect(authenticate: suspend (SpotifySession) -> Unit) {
        if (mutableAccount.value.working || remote != null) return
        accountJob = viewModelScope.launch {
            mutableAccount.value = AccountUi(working = true)
            try {
                val session = SpotifySession(app, transport, vault, deviceId)
                authenticate(session)
                finishAccount(session)
                mutableAccount.value = AccountUi()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                showAccountError(failure)
            }
        }
    }

    private suspend fun requestCode(length: Int, email: String? = null): String {
        val deferred = CompletableDeferred<String>()
        pendingCode = deferred
        mutableAccount.value = AccountUi(working = true, codeLength = length, codeEmail = email)
        return try {
            deferred.await().also { mutableAccount.value = AccountUi(working = true) }
        } finally {
            if (pendingCode === deferred) pendingCode = null
        }
    }

    fun submitCode(code: String) {
        if (code.length == mutableAccount.value.codeLength && code.all { it in '0'..'9' }) {
            pendingCode?.complete(code)
        }
    }

    private fun finishAccount(session: SpotifySession) {
        SpotifyAccount.attach(session)
        spotifyProfileJob?.cancel()
        mutableSpotifyAccountProfile.value = null
        // A guest who signs in keeps the multi-service playlists; the library now comes from Spotify.
        if (mutableLibrary.value.content?.let { it.playlists.isEmpty() && it.savedTrackCount == 0 } == true) mutableLibrary.value = LibraryUi()
        // Without the native core, refused playlist pages fall back to the public embed page.
        catalog = SpotifyCatalog(transport, session, nativePlaylist = null, nativeMetadata = null)
        collections = SpotifyLibrary(transport, session, requireNotNull(catalog))
        radio = SpotifyRadio(transport, session)
        remote = SpotifySearch(transport, session)
        lyrics = SpotifyLyricsSource(transport, session)
        mutableDiscovery.update { it.copy(spotifyEnabled = true) }
        refreshSpotifyAccountProfile(session)
        search(discovery.value.query)
        viewModelScope.launch { openCatalog() }
    }

    private fun refreshSpotifyAccountProfile(session: SpotifySession? = SpotifyAccount.current()) {
        val activeSession = session ?: return
        if (spotifyProfileJob?.isActive == true) return
        spotifyProfileJob = viewModelScope.launch {
            try {
                val username = activeSession.username()
                if (SpotifyAccount.current() !== activeSession) return@launch
                val cached = SpotifyUserProfileCodec.decode(preferences.getString(KEY_SPOTIFY_PROFILE, null))
                    ?.takeIf { it.username.equals(username, ignoreCase = true) }
                mutableSpotifyAccountProfile.value = cached
                if (!online.value) return@launch

                val fresh = spotifyCurrentUserProfile(transport, activeSession)
                if (SpotifyAccount.current() !== activeSession || !fresh.username.equals(username, ignoreCase = true)) return@launch
                val profile = fresh.copy(
                    displayName = fresh.displayName ?: cached?.displayName,
                    imageUrl = fresh.imageUrl ?: cached?.imageUrl,
                )
                mutableSpotifyAccountProfile.value = profile
                preferences.edit { putString(KEY_SPOTIFY_PROFILE, SpotifyUserProfileCodec.encode(profile)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                `in`.melo.data.diagnostics.DiagnosticLog.record(
                    "spotify-account",
                    "profile unavailable (${failure.javaClass.simpleName})",
                )
            }
        }
    }

    /** The library on disk comes first. The service is asked afterwards, and only when it can add something. */
    private suspend fun openCatalog() {
        val stored = withContext(Dispatchers.IO) { store.loadLibrary() }
        loadPendingFavorites()
        // Covers for playlists without a picture, from pages already on the device.
        loadCovers()
        stored?.playlists?.let { playlists ->
            val covers = withContext(Dispatchers.IO) {
                playlists.mapNotNull { playlist ->
                    val detail = store.loadDetail(playlist.id) ?: return@mapNotNull null
                    // Earlier versions filled a playlist without picture with its first track's cover.
                    val firstCover = detail.tracks.firstNotNullOfOrNull(Music::imageUrl)
                    val bare = playlist.imageUrl == null || playlist.id in playlistCovers ||
                        (playlist.imageUrl == detail.item.imageUrl && playlist.imageUrl == firstCover)
                    if (bare) playlist.id to (playlistMosaic(detail.tracks) ?: firstCover) else null
                }
            }
            covers.forEach { (id, url) -> if (url != null) playlistCovers[id] = url }
            saveCovers()
        }
        if (stored != null) installLibrary(stored.copy(playlists = stored.playlists.map {
            // A stored cover filled in by an earlier start yields to the mosaic computed now.
            if (it.id in playlistCovers) it.copy(imageUrl = null) else it
        }), false)
        else if (favoriteChanges.isNotEmpty()) savedIds.update { ids ->
            favoriteChanges.values.fold(ids) { all, change -> if (change.saved) all + change.item.id else all - change.item.id }
        }
        sendPendingFavorites()
        val age = withContext(Dispatchers.IO) { store.libraryAgeMs() }
        when {
            stored == null -> refreshLibrary()
            online.value && (age > LIBRARY_TTL_MS || stored.playlists.any { it.ownerUsername == null }) -> refreshLibrary()
            online.value -> collections?.let { source ->
                libraryJob = viewModelScope.launch {
                    try { withContext(BackgroundRequests) { completeSavedTracks(source) }; keepCatalogAvailable() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (auth: SpotifyAuthRequired) { accountLost(auth) }
                    catch (_: IOException) { /* The next connection completes it. */ }
                }
            }
        }
    }

    /** Only a refusal of the saved account leads back to the sign-in screen. */
    private fun accountLost(failure: SpotifyAuthRequired) {
        disconnectSpotify()
        mutableAccount.value = AccountUi(error = spotifyFailureLabel(failure, R.string.spotify_sign_in_again))
    }

    private fun showAccountError(failure: Exception) {
        if (failure is SpotifyAuthRequired) vault.clear()
        mutableAccount.value = AccountUi(
            error = when (failure) {
                is SpotifyFailure -> spotifyFailureLabel(failure, R.string.spotify_login_failed)
                is InvalidProtocolBufferException -> uiContext.getString(R.string.spotify_login_response_invalid)
                is IOException -> uiContext.getString(R.string.spotify_error_connection)
                else -> uiContext.getString(R.string.spotify_login_failed)
            },
        )
    }

    fun disconnectSpotify() {
        accountJob?.cancel()
        spotifyProfileJob?.cancel()
        spotifyProfileJob = null
        mutableSpotifyAccountProfile.value = null
        pendingCode?.cancel()
        pendingCode = null
        searchJob?.cancel()
        libraryJob?.cancel()
        detailJobs.values.forEach { it.cancel() }; detailJobs.clear()
        offlineSyncJob?.cancel()
        favoriteChanges.clear(); savePendingFavorites()
        player.stop(); SpotifyAccount.clear()
        vault.clear()
        preferences.edit { remove(KEY_SPOTIFY_PROFILE) }
        viewModelScope.launch(Dispatchers.IO) { store.clear() }
        remote = null
        catalog = null; collections = null; radio = null; lyrics = null
        mutableDetails.value = emptyMap()
        savedIds.value = emptySet(); mutableRecent.value = emptyList()
        if (mutableGuest.value) installLibrary(emptyLibrary(), false) else mutableLibrary.value = LibraryUi()
        mutableAccount.value = AccountUi()
        mutableDiscovery.update { it.copy(spotifyEnabled = false) }
        search(discovery.value.query)
    }

    /** A report for a bug ticket: entitlements, library sizes and recent failures, without identifiers. */
    suspend fun diagnosticReport(): String {
        val content = mutableLibrary.value.content
        val sizes = content?.let {
            "playlists=${it.playlists.size} albums=${it.albums.size} artists=${it.artists.size} savedTracks=${it.savedTrackCount}"
        } ?: "not loaded"
        return withContext(Dispatchers.IO) { `in`.melo.data.diagnostics.buildDiagnosticReport(app, sizes) }
    }
    fun clearDiagnostics() = `in`.melo.data.diagnostics.DiagnosticLog.clear()

    override fun onCleared() {
        spotifyPlaylists.close()
        player.close()
    }
    private fun readable(failure: Exception): String = when (failure) {
        is SpotifyFailure -> spotifyFailureLabel(failure, R.string.spotify_service_unavailable)
        is InvalidProtocolBufferException -> uiContext.getString(R.string.spotify_error_response_invalid)
        is IOException -> uiContext.getString(R.string.operation_failed)
        else -> uiContext.getString(R.string.operation_failed)
    }
    private fun spotifyFailureLabel(failure: SpotifyFailure, fallback: Int): String =
        uiContext.getString(when {
            failure is Login5Error && failure.error == 5 -> R.string.spotify_login_account_unknown
            failure is Login5Error && failure.status == 401 -> R.string.spotify_login_rejected
            failure is SpotifyAuthRequired -> R.string.spotify_sign_in_again
            failure.status == 429 -> R.string.spotify_error_rate_limit
            else -> fallback
        })

    /** Tracks to add to a playlist, from one source; nothing is kept in the search history. */
    suspend fun searchTracks(query: String, source: String, offset: Int = 0): List<Music> {
        val text = query.trim()
        if (source == TRACKS_SERVER) return if (serverLibrary.value != null) musicServer.tracks(text, offset).songs else emptyList()
        if (text.isEmpty()) return emptyList()
        return when (source) {
            TRACKS_SPOTIFY -> {
                val search = remote ?: return emptyList()
                val locale = app.resources.configuration.locales.get(0) ?: Locale.getDefault()
                search.search(text, locale, null).items.filter { it.kind == MusicKind.Track }
            }
            else -> withContext(Dispatchers.Default) {
                val wanted = text.lowercase()
                mutableLocalTracks.value.filter { it.title.lowercase().contains(wanted) || it.subtitle.lowercase().contains(wanted) || it.album.lowercase().contains(wanted) }
            }
        }
    }

    /** Saves [jpeg] as the cover of a multi-service playlist, or removes the cover when null. */
    suspend fun setLocalPlaylistCover(id: String, jpeg: ByteArray?) {
        val directory = java.io.File(app.filesDir, "playlist-covers")
        val previous = localPlaylistStore.get(id)?.cover
        val cover = if (jpeg == null) null else withContext(Dispatchers.IO) {
            directory.mkdirs()
            java.io.File(directory, "${UUID.randomUUID()}.jpg").apply { writeBytes(jpeg) }.toURI().toString()
        }
        localPlaylistStore.setCover(id, cover)
        // A new address for every change, so cached thumbnails never show the old picture.
        previous?.takeIf { it.startsWith("file:") }?.let { old ->
            withContext(Dispatchers.IO) { runCatching { java.io.File(java.net.URI(old)).takeIf { it.parentFile == directory }?.delete() } }
        }
    }

    private fun emptyLibrary() = HomeLibrary(emptyList(), emptyList(), emptyList(), emptyList(), 0)

    // Playlists: deletion, import, export --------------------------------------------------------

    /** A multi-service playlist is removed from the device; a Spotify playlist from the account's library. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun deletePlaylist(item: Music) {
        if (`in`.melo.data.server.MusicServerUris.isPlaylist(item.id)) { deleteServerPlaylist(item); return }
        if (item.isLocalPlaylist) {
            localPlaylistStore.delete(item.id)
            `in`.melo.playback.DownloadCenter.remove(app, item.id)
            pinnedStore.pins.value.firstOrNull { it.id == item.id }?.let(pinnedStore::toggle)
            mutableDetails.update { it - item.id }
            return
        }
        if (item.kind != MusicKind.Playlist || item.id == LIKED_SONGS || !item.id.startsWith("spotify:playlist:")) return
        viewModelScope.launch {
            try {
                spotifyPlaylists.unfollow(item.id)
                mutableLibrary.value.content?.let { content ->
                    installLibrary(content.copy(playlists = content.playlists.filterNot { it.id == item.id }), false)
                    saveLibrary()
                }
                pinnedStore.pins.value.firstOrNull { it.id == item.id }?.let(pinnedStore::toggle)
                `in`.melo.playback.DownloadCenter.remove(app, item.id)
                mutableDetails.update { it - item.id }
                mutableMessage.value = uiContext.getString(`in`.melo.R.string.playlist_deleted, item.title)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (auth: SpotifyAuthRequired) { accountLost(auth) }
            catch (failure: Exception) { mutableMessage.value = readable(failure) }
        }
    }

    // Converter ----------------------------------------------------------------------------------


    /** Kinds a playlist can be converted to now: Spotify and the music server require their accounts. */
    fun conversionTargets(source: Music): Map<`in`.melo.data.PlaylistKind, Boolean> {
        val from = `in`.melo.data.playlistKindOf(source) ?: return emptyMap()
        return `in`.melo.data.PlaylistKind.entries.filter { it != from }.associateWith { kind ->
            when (kind) {
                `in`.melo.data.PlaylistKind.Spotify -> remote != null
                `in`.melo.data.PlaylistKind.MusicServer -> serverLibrary.value != null
                `in`.melo.data.PlaylistKind.MultiService -> true
            }
        }
    }

    fun startConversion(source: Music, target: `in`.melo.data.PlaylistKind) {
        val current = mutableConversion.value
        if (current != null && current.source.id == source.id && current.target == target && current.state.value.created == null) return
        current?.cancel()
        val conversion = `in`.melo.data.PlaylistConversion(
            scope = viewModelScope, source = source, target = target,
            loadTracks = { tracksOf(source) },
            search = conversionSearch(target),
            // A device file can stay in a Spotify playlist as Spotify's own local file reference.
            fallback = { track -> track.takeIf { target == `in`.melo.data.PlaylistKind.Spotify && it.id.startsWith(`in`.melo.data.LOCAL_TRACK_PREFIX) } },
            parallelism = if (target == `in`.melo.data.PlaylistKind.Spotify) 2 else 4,
            cooldownMs = { 0L },
        )
        mutableConversion.value = conversion
        conversion.start()
    }

    fun endConversion() {
        mutableConversion.value?.cancel()
        mutableConversion.value = null
    }

    private fun conversionSearch(target: `in`.melo.data.PlaylistKind) = `in`.melo.data.MatchSearch { track ->
        val text = `in`.melo.data.ConversionMatching.searchText(track)
        when (target) {
            `in`.melo.data.PlaylistKind.Spotify -> {
                val search = remote ?: throw SpotifyFailure("Sign in to Spotify first.")
                val locale = app.resources.configuration.locales.get(0) ?: Locale.getDefault()
                search.search(text, locale, null).items.filter { it.kind == MusicKind.Track }
            }
            `in`.melo.data.PlaylistKind.MusicServer -> musicServer.tracks(track.title, 0).songs
            `in`.melo.data.PlaylistKind.MultiService -> emptyList()
        }
    }

    /** Creates the converted playlist in its destination. */
    internal suspend fun createConverted(target: `in`.melo.data.PlaylistKind, name: String, tracks: List<Music>): Music = when (target) {
        `in`.melo.data.PlaylistKind.MultiService -> localPlaylistStore.create(name, tracks).asMusic().also { ui.register(it) }
        `in`.melo.data.PlaylistKind.MusicServer -> createServerPlaylist(name, tracks)
        `in`.melo.data.PlaylistKind.Spotify -> {
            // One creation request stays small; the rest is added in batches.
            val created = createSpotifyPlaylist(name, tracks.take(100), published = false)
            if (tracks.size > 100) addSpotifyPlaylistTracks(created, tracks.drop(100))
            created
        }
    }

    /** The playlist as CSV, with every track's source and reference. */
    suspend fun exportCsv(item: Music): String {
        val tracks = tracksOf(item)
        return `in`.melo.data.PlaylistCsv.export(item.title, tracks)
    }

    suspend fun importCsv(text: String, fileName: String): Music {
        val imported = withContext(Dispatchers.Default) { `in`.melo.data.PlaylistCsv.import(text, fileName.substringBeforeLast('.')) }
        if (imported.tracks.isEmpty()) throw IllegalArgumentException(uiContext.getString(`in`.melo.R.string.import_no_tracks))
        return createImported(imported.name, completeSpotifyTracks(imported.tracks))
    }

    /** A Spotify playlist link, copied as a multi-service playlist. */
    suspend fun importLink(link: String): Music {
        val spotifyId = `in`.melo.data.spotifyPlaylistId(link)
            ?: throw IllegalArgumentException(uiContext.getString(`in`.melo.R.string.import_unknown_link))
        val source = catalog
        if (source != null) {
            val item = Music("spotify:playlist:$spotifyId", "", "", MusicKind.Playlist)
            val detail = fullDetail(source, item, pageLimit = Int.MAX_VALUE)
            return createImported(detail.item.title.ifBlank { "Spotify" }, detail.tracks)
        }
        val content = withContext(Dispatchers.IO) { `in`.melo.data.SpotifyPublicPlaylist.read(spotifyId) }
        if (content.tracks.isEmpty()) throw IllegalArgumentException(uiContext.getString(`in`.melo.R.string.import_no_tracks))
        return createImported(content.name.ifBlank { "Spotify" }, content.tracks)
    }

    /** Resolves a Spotify share link before Melo opens its detail page or starts its track. */
    suspend fun resolveSpotifySharedLink(link: String): Music? {
        val item = `in`.melo.data.resolveSpotifyDeepLink(link) ?: return null
        if (item.kind != MusicKind.Track) return item

        val source = catalog
        if (source != null) {
            try { source.hydrate(listOf(item.id)).firstOrNull()?.let { return it } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
        val publicTrack = try {
            withContext(Dispatchers.IO) { `in`.melo.data.SpotifyPublicPlaylist.readTrack(item.id.substringAfterLast(':')) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        return publicTrack ?: item.takeIf { source != null }
    }

    /** With an account, Spotify tracks read from a file get their covers and current metadata. */
    private suspend fun completeSpotifyTracks(tracks: List<Music>): List<Music> {
        val source = catalog ?: return tracks
        val ids = tracks.filter { it.id.startsWith("spotify:track:") && it.imageUrl == null }.map(Music::id)
        if (ids.isEmpty() || !online.value) return tracks
        val known = try { source.hydrate(ids).associateBy(Music::id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyMap() }
        return tracks.map { known[it.id] ?: it }
    }

    private fun createImported(name: String, tracks: List<Music>): Music {
        val playlist = localPlaylistStore.create(name.ifBlank { uiContext.getString(`in`.melo.R.string.import_default_name) }, withLocalFiles(tracks))
        val music = playlist.asMusic()
        ui.register(music)
        mutableMessage.value = uiContext.getString(`in`.melo.R.string.import_done, music.title, playlist.tracks.size)
        return music
    }

    companion object {
        const val LIKED_SONGS = "spotify:collection:tracks"


        private const val SEARCH_DEBOUNCE_MS = 250L
        const val TRACKS_SPOTIFY = "spotify"

        const val TRACKS_FILES = "files"
        const val TRACKS_SERVER = "server"
        private const val KEY_GUEST = "guest"
        private const val KEY_PENDING_LIKES = "pending_likes"
        private const val KEY_SPOTIFY_PROFILE = "spotify_account_profile"

        private const val KEY_PLAYLIST_COVERS = "playlist_covers"



        private const val KEY_PENDING_UNLIKES = "pending_unlikes"


        private const val FAVORITE_LAG_MS = 2 * 60 * 1000L
        private const val LIBRARY_TTL_MS = 10 * 60 * 1000L
        private const val PLAYLIST_TTL_MS = 12 * 60 * 60 * 1000L
        private const val ARTIST_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        private const val ARTIST_OPEN_TTL_MS = 60 * 60 * 1000L
        private const val ALBUM_TTL_MS = 30 * 24 * 60 * 60 * 1000L
        /** Large libraries are read in the background for offline use; Spotify answers 429 to bursts. */
        private const val BACKGROUND_REQUEST_GAP_MS = 1_000L
        private const val SAVE_EVERY_PAGES = 5
        private const val MAX_BACKGROUND_PAGES = 400
        private const val MAX_DETAIL_PAGES = 30
        private const val MAX_LOCAL_RESULTS = 60
        private const val COVERS_PER_PAGE = 60
        /** Keep disk artwork warming useful but bounded while catalog pages sync in the background. */
        private const val MAX_BACKGROUND_COVERS = 120
        private const val KEY_LAST_OPEN_ARTIST = "last_opened_artist"







        private const val RADIO_REFILL_THRESHOLD = 5
        private const val RADIO_REFILL_SEED_COUNT = 3
        private const val RADIO_REFILL_BATCH_SIZE = 50
    }
}
