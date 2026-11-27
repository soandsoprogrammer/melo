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
import `in`.melo.data.HomeLocalTaste
import `in`.melo.data.HomeMood
import `in`.melo.data.HomeRecommendationPick
import `in`.melo.data.HomeRecommendationState
import `in`.melo.data.HomeRecommendationType
import `in`.melo.data.HomeRecommendationCache
import `in`.melo.data.HomeRecommendationCacheCodec
import `in`.melo.data.HomeRecommendationEngine
import `in`.melo.data.YouTubeHomeChip
import `in`.melo.data.YouTubeHomeState
import `in`.melo.data.YouTubeExploreCategory
import `in`.melo.data.YouTubeExploreCache
import `in`.melo.data.YouTubeExploreCacheCodec
import `in`.melo.data.YouTubeExploreState
import `in`.melo.data.LOCAL_PLAYLIST_PREFIX
import `in`.melo.data.LocalLibrary
import `in`.melo.data.LocalPlaylistStore
import `in`.melo.data.MusicKind
import `in`.melo.data.isLocalPlaylist
import `in`.melo.data.isLocalTrack
import `in`.melo.data.source
import `in`.melo.data.isPodcastSearchContainer
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
import `in`.melo.data.youtube.YTM_PLAYLIST_PREFIX
import `in`.melo.data.youtube.YTM_TRACK_PREFIX
import `in`.melo.data.youtube.isYouTubeTrack
import `in`.melo.data.youtube.YouTubeAccount
import `in`.melo.data.youtube.YouTubeAccountInfo
import `in`.melo.data.youtube.YouTubeAccountPlaylist
import `in`.melo.data.youtube.YouTubeAuthRequired
import `in`.melo.data.youtube.YouTubeFailure
import `in`.melo.data.youtube.YouTubeLibrary
import `in`.melo.data.youtube.YouTubeMusic
import `in`.melo.data.youtube.YouTubePages
import `in`.melo.data.youtube.YouTubePodcastFeed
import `in`.melo.data.youtube.YouTubePodcastFeedState
import `in`.melo.data.youtube.YouTubePodcasts
import `in`.melo.data.youtube.YTM_ARTIST_PREFIX
import `in`.melo.data.youtube.YTM_ALBUM_PREFIX
import `in`.melo.data.youtube.youTubeVideoId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
 * What a search reads. [All], the default, mixes Spotify (with an account), YouTube Music and device files.
 * [Addons] reads the listener's add-ons only, and only when one is enabled.
 */
enum class SearchSource { All, Spotify, YouTubeMusic, Local, Addons }

data class Discovery(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<Music> = emptyList(),
    /** Spotify's next page token. */
    val nextPage: String? = null,
    /** YouTube Music's continuation token. */
    val youTubeNext: String? = null,
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
    val hasMore: Boolean get() = nextPage != null || youTubeNext != null
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

private fun decodeYouTubeHomeChips(raw: String?): List<YouTubeHomeChip> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val chips = org.json.JSONArray(raw)
        (0 until chips.length()).mapNotNull { index ->
            val chip = chips.optJSONObject(index) ?: return@mapNotNull null
            val title = chip.optString("title")
            val params = chip.optString("params")
            if (title.isBlank() || params.isBlank()) null else YouTubeHomeChip(title, params)
        }
    }.getOrDefault(emptyList())
}

private fun encodeYouTubeHomeChips(chips: List<YouTubeHomeChip>): String = org.json.JSONArray().apply {
    chips.forEach { put(org.json.JSONObject().put("title", it.title).put("params", it.params)) }
}.toString()

private data class YouTubeHomeFeedCache(
    val listener: String,
    val shelves: List<`in`.melo.data.YouTubeHomeShelf>,
    val continuation: String?,
)

private fun encodeYouTubeHomeFeed(cache: YouTubeHomeFeedCache): String = org.json.JSONObject()
    .put("version", 1)
    .put("listener", cache.listener)
    .put("continuation", cache.continuation)
    .put("shelves", org.json.JSONArray().apply {
    cache.shelves.take(40).forEach { shelf ->
            put(org.json.JSONObject()
                .put("title", shelf.title)
                .put("items", org.json.JSONArray().apply {
                    shelf.items.take(24).forEach { item ->
                        put(org.json.JSONObject()
                            .put("id", item.id)
                            .put("title", item.title)
                            .put("subtitle", item.subtitle)
                            .put("kind", item.kind.name)
                            .put("image", item.imageUrl)
                            .put("duration", item.durationMs)
                            .put("album", item.album)
                            .put("note", item.note))
                    }
                }))
        }
    }).toString()

private fun decodeYouTubeHomeFeed(raw: String?): YouTubeHomeFeedCache? = runCatching {
    val json = org.json.JSONObject(raw ?: return null)
    if (json.optInt("version") != 1) return null
    val shelvesJson = json.optJSONArray("shelves") ?: return null
    val shelves = (0 until shelvesJson.length()).mapNotNull { index ->
        val shelf = shelvesJson.optJSONObject(index) ?: return@mapNotNull null
        val rows = shelf.optJSONArray("items") ?: return@mapNotNull null
        val items = (0 until rows.length()).mapNotNull { itemIndex ->
            val item = rows.optJSONObject(itemIndex) ?: return@mapNotNull null
            val kind = MusicKind.entries.firstOrNull { it.name == item.optString("kind") } ?: return@mapNotNull null
            Music(
                id = item.optString("id"), title = item.optString("title"), subtitle = item.optString("subtitle"),
                kind = kind, imageUrl = item.optString("image").takeIf(String::isNotBlank),
                durationMs = item.optInt("duration"), album = item.optString("album"), note = item.optString("note"),
            ).takeIf { it.id.isNotBlank() && it.title.isNotBlank() }
        }
        `in`.melo.data.YouTubeHomeShelf(shelf.optString("title"), items).takeIf { it.title.isNotBlank() && items.isNotEmpty() }
    }
    YouTubeHomeFeedCache(
        listener = json.optString("listener"),
        shelves = shelves,
        continuation = json.optString("continuation").takeIf(String::isNotBlank),
    )
}.getOrNull()

private data class HomeRecommendationRequest(
    val key: String,
    val listener: String,
    val hour: String,
    val online: Boolean,
    val youtubeAvailable: Boolean,
)

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
    val favorites = savedIds.asStateFlow()
    /**
     * YouTube Music tracks the user liked, newest first. Spotify's liked songs cannot hold them, so
     * they are kept on the device and shown at the top of the liked songs; with a Google account they
     * are also liked on YouTube Music.
     */
    private val youTubeLikes = MutableStateFlow(decodePins(preferences.getString(KEY_YOUTUBE_LIKES, null)))

    private val mutableDiscovery = MutableStateFlow(Discovery())
    val discovery = mutableDiscovery.asStateFlow()
    private var searchJob: Job? = null
    private var accountJob: Job? = null
    private val mutableAccount = MutableStateFlow(AccountUi())
    val account = mutableAccount.asStateFlow()
    /** The app is used without a Spotify account: local files, YouTube Music and multi-service playlists. */
    private val mutableGuest = MutableStateFlow(app.getSharedPreferences("music_library", 0).getBoolean(KEY_GUEST, false))
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
    private val mutableRecent = MutableStateFlow<List<Music>>(emptyList())
    val recent = mutableRecent.asStateFlow()
    private val initialHomeRecommendationCache = HomeRecommendationCacheCodec.decode(
        preferences.getString(KEY_HOME_RECOMMENDATIONS, null),
    )
    private val mutableHomeRecommendations = MutableStateFlow(
        initialHomeRecommendationCache?.takeIf { it.listener == recommendationListenerKey() }?.state ?: HomeRecommendationState(),
    )
    val homeRecommendations = mutableHomeRecommendations.asStateFlow()
    private var homeRecommendationKey: String? = initialHomeRecommendationCache?.takeIf {
        it.listener == recommendationListenerKey() && it.hour == recommendationHourKey()
    }?.let { "${it.listener}|${it.hour}" }
    private var activeHomeRecommendationListener: String? = initialHomeRecommendationCache?.listener
        ?.takeIf { it == recommendationListenerKey() }
    private var homeRecommendationJob: Job? = null
    private var pendingHomeRecommendationRequest: HomeRecommendationRequest? = null
    private val initialYouTubeHomeFeed = decodeYouTubeHomeFeed(preferences.getString(KEY_HOME_FEED, null))
        ?.takeIf { it.listener == recommendationListenerKey() }
    private val mutableYouTubeHome = MutableStateFlow(
        YouTubeHomeState(
            chips = decodeYouTubeHomeChips(preferences.getString(KEY_HOME_CATEGORY_CHIPS, null)),
            personalizedShelves = initialYouTubeHomeFeed?.shelves.orEmpty(),
            personalizedContinuation = initialYouTubeHomeFeed?.continuation,
        ),
    )
    val youTubeHome = mutableYouTubeHome.asStateFlow()
    private var homeCategoriesJob: Job? = null
    private var homeCategoryJob: Job? = null
    private var homeCategoryMoreJob: Job? = null
    private var homeFeedMoreJob: Job? = null
    private var homeCategoryIdentity: String? = null
    private var exploreRegion = YouTubeMusic.contentRegion()
    private val initialExploreCache = YouTubeExploreCacheCodec.decode(
        preferences.getString(KEY_YOUTUBE_EXPLORE_CACHE, null),
    )?.takeIf { it.region == exploreRegion }
    private var exploreLoadedAt = initialExploreCache?.savedAtMs ?: 0L
    private val mutableYouTubeExplore = MutableStateFlow(
        initialExploreCache?.let { YouTubeExploreState(sections = it.sections) } ?: YouTubeExploreState(),
    )
    val youTubeExplore = mutableYouTubeExplore.asStateFlow()
    private var exploreJob: Job? = null
    private var exploreCategoryJob: Job? = null
    private var navigationWarmupJob: Job? = null
    private var homeStartupSettled = false
    private val exploreArtworkSemaphore = Semaphore(1)
    private val exploreArtRequests = HashSet<String>()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()

    // YouTube Music account ----------------------------------------------------------------------

    val youTubeAccount = YouTubeAccount.info
    private val mutableYouTubePlaylists = MutableStateFlow(decodePins(preferences.getString(KEY_YOUTUBE_PLAYLISTS, null)))
    /** The Google account's YouTube Music playlists, as last read; kept on the device between starts. */
    val youTubePlaylists = mutableYouTubePlaylists.asStateFlow()
    private val mutableYouTubeLoading = MutableStateFlow(false)
    val youTubeLoading = mutableYouTubeLoading.asStateFlow()
    private val mutableYouTubeEditable = MutableStateFlow(preferences.getStringSet(KEY_YOUTUBE_EDITABLE, null).orEmpty().toSet())
    /** Playlists whose page showed the owner's editing header. */
    val youTubeEditable = mutableYouTubeEditable.asStateFlow()
    /** Row identifiers of the account's playlists as last read, to remove one row. Main thread only. */
    private val youTubeRows = HashMap<String, YouTubeAccountPlaylist>()
    private var youTubeJob: Job? = null

    private var podcastFeedIdentity = podcastListenerKey()
    private var podcastFeedRegion = YouTubeMusic.contentRegion()
    private val initialPodcastCache = YouTubePodcasts.cached(app, podcastFeedIdentity, podcastFeedRegion)
    private var podcastFeedLoadedAt = initialPodcastCache?.savedAtMs ?: 0L
    private val mutablePodcastFeed = MutableStateFlow(YouTubePodcastFeedState(feed = initialPodcastCache?.feed))
    val podcastFeed = mutablePodcastFeed.asStateFlow()
    private var podcastFeedJob: Job? = null
    private var podcastMoreJob: Job? = null

    /** Checks the cookies of Google's sign-in page, keeps them, then reads the library. */
    suspend fun connectGoogle(cookie: String, visitorData: String?, dataSyncId: String?): YouTubeAccountInfo {
        val info = withContext(Dispatchers.IO) { YouTubeAccount.connect(cookie, visitorData, dataSyncId) }
        // The cookies are now kept encrypted: the WebView's own copy is removed.
        runCatching { android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() } }
        refreshYouTubeLibrary()
        loadPodcastFeed(force = true)
        return info
    }

    /** Forgets the Google account on this device. Its playlists stay on YouTube Music. */
    fun disconnectGoogle() {
        youTubeJob?.cancel()
        YouTubeAccount.clear()
        runCatching { android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() } }
        val ids = mutableYouTubePlaylists.value.map(Music::id).toSet()
        ids.forEach { detailJobs.remove(it)?.cancel() }
        mutableYouTubePlaylists.value = emptyList()
        mutableYouTubeEditable.value = emptySet()
        youTubeRows.clear()
        preferences.edit { remove(KEY_YOUTUBE_PLAYLISTS); remove(KEY_YOUTUBE_EDITABLE) }
        mutableDetails.update { it - ids }
        // Pages read with the account (private playlists included) do not stay on the device.
        viewModelScope.launch(Dispatchers.IO) { ids.forEach(store::removeDetail) }
        loadPodcastFeed(force = true)
    }

    private fun youTubeAccountLost(failure: YouTubeAuthRequired) {
        disconnectGoogle()
        mutableMessage.value = uiContext.getString(`in`.melo.R.string.google_session_closed)
        `in`.melo.data.diagnostics.DiagnosticLog.record("youtube-account", "signed out: ${failure.message}")
    }

    fun refreshYouTubeLibrary() {
        if (!YouTubeAccount.signedIn || !online.value || youTubeJob?.isActive == true) return
        youTubeJob = viewModelScope.launch {
            mutableYouTubeLoading.value = true
            try {
                val playlists = withContext(Dispatchers.IO) { YouTubeLibrary.libraryPlaylists() }
                installYouTubePlaylists(playlists)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth) }
            catch (failure: Exception) {
                `in`.melo.data.diagnostics.DiagnosticLog.record("youtube-library", "${failure.javaClass.simpleName} \"${failure.message}\"")
            } finally { mutableYouTubeLoading.value = false }
        }
    }

    private fun installYouTubePlaylists(playlists: List<Music>) {
        // A playlist without a cover of its own shows the covers of its tracks, once its page is known.
        val shown = playlists.map { playlist ->
            if (playlist.imageUrl != null) playlist
            else mutableDetails.value[playlist.id]?.content?.item?.imageUrl?.let { playlist.copy(imageUrl = it) } ?: playlist
        }
        shown.forEach { ui.register(it) }
        mutableYouTubePlaylists.value = shown
        preferences.edit { putString(KEY_YOUTUBE_PLAYLISTS, encodePins(shown)) }
    }

    private fun markYouTubeEditable(id: String, editable: Boolean) {
        val next = if (editable) mutableYouTubeEditable.value + id else mutableYouTubeEditable.value - id
        if (next == mutableYouTubeEditable.value) return
        mutableYouTubeEditable.value = next
        preferences.edit { putStringSet(KEY_YOUTUBE_EDITABLE, next) }
    }

    /** Whether the signed-in account can change this YouTube Music playlist. */
    fun youTubePlaylistOwned(item: Music): Boolean {
        if (!item.id.startsWith(YTM_PLAYLIST_PREFIX) || youTubeAccount.value == null) return false
        if (item.id in youTubeEditable.value) return true
        val listed = mutableYouTubePlaylists.value.firstOrNull { it.id == item.id } ?: item
        return item.id != YTM_PLAYLIST_PREFIX + "LM" && listed.ownerUsername != null && listed.ownerUsername == youTubeAccount.value?.name
    }

    /** Reads a YouTube Music playlist: with the account when there is one (private playlists, row ids), anonymously otherwise. */
    private suspend fun fetchYouTubePlaylist(item: Music): CatalogDetail {
        val id = item.id.removePrefix(YTM_PLAYLIST_PREFIX)
        val (title, image, tracks) = if (YouTubeAccount.signedIn) {
            val playlist = withContext(Dispatchers.IO) { YouTubeLibrary.playlist(id) }
            youTubeRows[item.id] = playlist
            markYouTubeEditable(item.id, playlist.editable)
            Triple(playlist.title, playlist.imageUrl, playlist.tracks)
        } else {
            val playlist = withContext(Dispatchers.IO) { `in`.melo.data.youtube.YouTubeMusic.playlist(id) }
            Triple(playlist.title, playlist.imageUrl, playlist.tracks)
        }
        val music = item.copy(title = title.ifBlank { item.title }, subtitle = `in`.melo.utils.formatSongCount(tracks.size),
            imageUrl = image ?: playlistMosaic(tracks) ?: item.imageUrl, tracks = tracks.map(Music::id))
        val detail = CatalogDetail(music, tracks, total = tracks.size)
        installYouTubeDetail(detail)
        return detail
    }

    private suspend fun installYouTubeDetail(detail: CatalogDetail) {
        val item = detail.item
        ui.register(item)
        mutableDetails.update { it + (item.id to DetailUi(detail)) }
        withContext(Dispatchers.IO) { store.saveDetail(item.id, detail) }
        val listed = mutableYouTubePlaylists.value
        if (listed.any { it.id == item.id }) installYouTubePlaylists(listed.map {
            if (it.id == item.id) it.copy(title = item.title, imageUrl = it.imageUrl ?: item.imageUrl) else it
        })
    }

    private fun loadYouTubePlaylist(item: Music, refresh: Boolean) {
        val shown = mutableDetails.value[item.id]
        if (!refresh && shown?.content != null && shown.error == null) return
        if (shown?.loading == true && !refresh) return
        detailJobs[item.id]?.cancel()
        detailJobs[item.id] = viewModelScope.launch {
            var known = shown?.content
            if (known == null) known = withContext(Dispatchers.IO) { store.loadDetail(item.id) }
            mutableDetails.update { it + (item.id to DetailUi(known, loading = online.value)) }
            if (!online.value) {
                if (known == null) mutableDetails.update { it + (item.id to DetailUi(error = uiContext.getString(`in`.melo.R.string.youtube_playlist_failed))) }
                return@launch
            }
            try { fetchYouTubePlaylist(item) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth); mutableDetails.update { it + (item.id to DetailUi(known)) } }
            catch (failure: Exception) {
                `in`.melo.data.diagnostics.DiagnosticLog.record("youtube-playlist", "${failure.javaClass.simpleName} \"${failure.message}\"")
                mutableDetails.update { it + (item.id to if (known != null) DetailUi(known)
                    else DetailUi(error = uiContext.getString(`in`.melo.R.string.youtube_playlist_failed))) }
            }
        }
    }

    /** YouTube Music album and artist pages: kept on the device like Spotify's, read again when older than a day. */
    private fun loadYouTubePage(item: Music, refresh: Boolean) {
        val shown = mutableDetails.value[item.id]
        if (!refresh && shown?.content != null && shown.error == null) return
        if (shown?.loading == true && !refresh) return
        detailJobs[item.id]?.cancel()
        detailJobs[item.id] = viewModelScope.launch {
            val known = shown?.content ?: withContext(Dispatchers.IO) { store.loadDetail(item.id) }
            val age = withContext(Dispatchers.IO) { store.detailAgeMs(item.id) }
            if (known != null && (!online.value || (!refresh && age < YOUTUBE_PAGE_TTL_MS))) {
                mutableDetails.update { it + (item.id to DetailUi(known)) }
                return@launch
            }
            mutableDetails.update { it + (item.id to DetailUi(known, loading = true)) }
            try {
                val page = withContext(Dispatchers.IO) {
                    if (item.id.startsWith(`in`.melo.data.youtube.YTM_ALBUM_PREFIX))
                        `in`.melo.data.youtube.YouTubePages.album(item.id.removePrefix(`in`.melo.data.youtube.YTM_ALBUM_PREFIX))
                    else `in`.melo.data.youtube.YouTubePages.artist(item.id.removePrefix(`in`.melo.data.youtube.YTM_ARTIST_PREFIX))
                }
                (page.related + page.item).forEach(ui::register)
                mutableDetails.update { it + (item.id to DetailUi(page)) }
                withContext(Dispatchers.IO) { store.saveDetail(item.id, page) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                `in`.melo.data.diagnostics.DiagnosticLog.record("youtube-page", "${item.kind.name.lowercase()} ${failure.javaClass.simpleName} \"${failure.message}\"")
                mutableDetails.update { it + (item.id to if (known != null) DetailUi(known) else DetailUi(error = readable(failure))) }
            }
        }
    }

    private suspend fun youTubeTracks(item: Music): List<Music> {
        mutableDetails.value[item.id]?.content?.takeIf { it.nextPage == null }?.let { return it.tracks }
        if (online.value) try { return fetchYouTubePlaylist(item).tracks }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth) }
        catch (_: IOException) { /* What the device has is used. */ }
        return withContext(Dispatchers.IO) { store.loadDetail(item.id) }?.tracks.orEmpty()
    }

    /** Creates a playlist on the Google account. Only YouTube Music tracks go in. */
    internal suspend fun createYouTubePlaylist(name: String, tracks: List<Music>, public: Boolean): Music {
        val wanted = tracks.filter { it.youTubeVideoId != null }
        val result = try {
            withContext(Dispatchers.IO) {
                YouTubeLibrary.create(name, wanted.mapNotNull { it.youTubeVideoId }, if (public) YouTubeLibrary.PUBLIC else YouTubeLibrary.PRIVATE)
            }
        } catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth); throw auth }
        val id = result.id
        val kept = wanted.filterNot { it.youTubeVideoId in result.refused }
        reportRefused(result.refused.size)
        val created = Music(YTM_PLAYLIST_PREFIX + id, name.trim(), `in`.melo.utils.formatSongCount(kept.size), MusicKind.Playlist,
            imageUrl = playlistMosaic(kept) ?: kept.firstNotNullOfOrNull(Music::imageUrl), tracks = kept.map(Music::id),
            ownerUsername = youTubeAccount.value?.name)
        markYouTubeEditable(created.id, true)
        installYouTubePlaylists(listOf(created) + mutableYouTubePlaylists.value.filterNot { it.id == created.id })
        installYouTubeDetail(CatalogDetail(created, kept))
        return created
    }

    internal suspend fun addYouTubePlaylistTracks(playlist: Music, tracks: List<Music>) {
        val kept = tracks.filter { it.youTubeVideoId != null }
        if (kept.isEmpty()) throw IllegalArgumentException(uiContext.getString(`in`.melo.R.string.youtube_playlist_only_youtube))
        val refused = try { withContext(Dispatchers.IO) { YouTubeLibrary.add(playlist.id, kept.mapNotNull { it.youTubeVideoId }) } }
        catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth); throw auth }
        reportRefused(refused.size)
        detailJobs.remove(playlist.id)?.cancel()
        // New rows have ids only the page knows: the next removal reads it again.
        youTubeRows.remove(playlist.id)
        val content = mutableDetails.value[playlist.id]?.content ?: CatalogDetail(playlist, emptyList())
        val tracksNow = content.tracks + kept.filterNot { it.youTubeVideoId in refused }
        installYouTubeDetail(content.copy(item = content.item.copy(subtitle = `in`.melo.utils.formatSongCount(tracksNow.size),
            imageUrl = content.item.imageUrl ?: playlistMosaic(tracksNow)), tracks = tracksNow, total = tracksNow.size))
    }

    private fun reportRefused(count: Int) {
        if (count > 0) mutableMessage.value = uiContext.resources.getQuantityString(`in`.melo.R.plurals.youtube_tracks_refused, count, count)
    }

    /** Removes the row at [index] of the playlist as shown; other copies of the same track stay. */
    internal suspend fun removeYouTubePlaylistTrack(playlist: Music, index: Int) {
        val content = mutableDetails.value[playlist.id]?.content ?: return
        val track = content.tracks.getOrNull(index) ?: return
        val videoId = track.youTubeVideoId ?: return
        val occurrence = content.tracks.take(index).count { it.id == track.id }
        fun rowOf(rows: YouTubeAccountPlaylist) = rows.tracks.withIndex().filter { it.value.id == track.id }.getOrNull(occurrence)?.index
        try {
            var rows = youTubeRows[playlist.id]?.takeIf { it.tracks.map(Music::id) == content.tracks.map(Music::id) }
            if (rows == null) rows = withContext(Dispatchers.IO) { YouTubeLibrary.playlist(playlist.id) }
            val row = rowOf(rows) ?: throw YouTubeFailure(uiContext.getString(`in`.melo.R.string.youtube_playlist_row_gone))
            val setVideoId = rows.setVideoIds[row] ?: throw YouTubeFailure(uiContext.getString(`in`.melo.R.string.youtube_playlist_row_gone))
            withContext(Dispatchers.IO) { YouTubeLibrary.remove(playlist.id, videoId, setVideoId) }
            detailJobs.remove(playlist.id)?.cancel()
            val remaining = rows.copy(tracks = rows.tracks.filterIndexed { i, _ -> i != row },
                setVideoIds = rows.setVideoIds.filterIndexed { i, _ -> i != row })
            youTubeRows[playlist.id] = remaining
            val item = content.item.copy(subtitle = `in`.melo.utils.formatSongCount(remaining.tracks.size), tracks = remaining.tracks.map(Music::id))
            installYouTubeDetail(content.copy(item = item, tracks = remaining.tracks, total = remaining.tracks.size))
        } catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth); throw auth }
    }

    /** Whether the playlist is public, read from its page. */
    internal suspend fun youTubePlaylistPublic(playlist: Music): Boolean {
        val rows = youTubeRows[playlist.id] ?: withContext(Dispatchers.IO) { YouTubeLibrary.playlist(playlist.id) }.also { youTubeRows[playlist.id] = it }
        return rows.privacy == YouTubeLibrary.PUBLIC
    }

    internal suspend fun editYouTubePlaylist(playlist: Music, name: String, public: Boolean) {
        try {
            withContext(Dispatchers.IO) {
                if (name.trim() != playlist.title) YouTubeLibrary.rename(playlist.id, name)
                YouTubeLibrary.setPrivacy(playlist.id, if (public) YouTubeLibrary.PUBLIC else YouTubeLibrary.PRIVATE)
            }
        } catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth); throw auth }
        youTubeRows[playlist.id]?.let { youTubeRows[playlist.id] = it.copy(title = name.trim(), privacy = if (public) YouTubeLibrary.PUBLIC else YouTubeLibrary.PRIVATE) }
        val renamed = playlist.copy(title = name.trim())
        ui.register(renamed)
        installYouTubePlaylists(mutableYouTubePlaylists.value.map { if (it.id == playlist.id) it.copy(title = renamed.title) else it })
        mutableDetails.value[playlist.id]?.content?.let { installYouTubeDetail(it.copy(item = it.item.copy(title = renamed.title))) }
    }

    /** Keep the Podcasts tab warm and show its last matching feed before a network refresh finishes. */
    fun loadPodcastFeed(force: Boolean = false) {
        val identity = podcastListenerKey()
        val region = YouTubeMusic.contentRegion()
        if (identity != podcastFeedIdentity || region != podcastFeedRegion) {
            podcastFeedJob?.cancel()
            podcastMoreJob?.cancel()
            podcastFeedIdentity = identity
            podcastFeedRegion = region
            val cached = YouTubePodcasts.cached(app, identity, region)
            podcastFeedLoadedAt = cached?.savedAtMs ?: 0L
            mutablePodcastFeed.value = YouTubePodcastFeedState(feed = cached?.feed)
        }

        if (podcastFeedJob?.isActive == true) return
        val current = mutablePodcastFeed.value
        if (!force && current.feed != null && System.currentTimeMillis() - podcastFeedLoadedAt < PODCAST_FEED_TTL_MS) return
        if (!online.value) {
            if (current.feed == null) mutablePodcastFeed.update { it.copy(error = uiContext.getString(`in`.melo.R.string.podcasts_offline_message)) }
            return
        }

        mutablePodcastFeed.update {
            it.copy(loading = it.feed == null, refreshing = it.feed != null, error = null)
        }
        val requestIdentity = podcastFeedIdentity
        val requestRegion = podcastFeedRegion
        podcastFeedJob = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { YouTubePodcasts.home() }
                if (requestIdentity != podcastFeedIdentity || requestRegion != podcastFeedRegion) return@launch
                val now = System.currentTimeMillis()
                podcastFeedLoadedAt = now
                YouTubePodcasts.saveCache(app, requestIdentity, requestRegion, loaded, now)
                mutablePodcastFeed.value = YouTubePodcastFeedState(feed = loaded)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (requestIdentity == podcastFeedIdentity && requestRegion == podcastFeedRegion) {
                    mutablePodcastFeed.update { it.copy(error = uiContext.getString(R.string.podcasts_load_error_message)) }
                }
            } finally {
                if (requestIdentity == podcastFeedIdentity && requestRegion == podcastFeedRegion &&
                    kotlinx.coroutines.currentCoroutineContext().isActive
                ) {
                    mutablePodcastFeed.update { it.copy(loading = false, refreshing = false) }
                }
            }
        }
    }

    fun refreshPodcastFeed() = loadPodcastFeed(force = true)

    fun loadMorePodcastFeed() {
        val current = mutablePodcastFeed.value
        val feed = current.feed ?: return
        val token = feed.continuation ?: return
        if (current.loadingMore || podcastMoreJob?.isActive == true) return
        val identity = podcastFeedIdentity
        val region = podcastFeedRegion
        mutablePodcastFeed.update { it.copy(loadingMore = true) }
        podcastMoreJob = viewModelScope.launch {
            try {
                val next = withContext(Dispatchers.IO) { YouTubePodcasts.more(token) }
                val latest = mutablePodcastFeed.value.feed
                if (identity != podcastFeedIdentity || region != podcastFeedRegion || latest?.continuation != token) return@launch
                val merged = YouTubePodcastFeed(
                    shelves = YouTubePodcasts.mergeShelves(latest.shelves + next.shelves),
                    continuation = next.continuation,
                )
                mutablePodcastFeed.update { it.copy(feed = merged, error = null) }
                YouTubePodcasts.saveCache(app, identity, region, merged, podcastFeedLoadedAt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (identity == podcastFeedIdentity && region == podcastFeedRegion) {
                    mutablePodcastFeed.update { it.copy(error = uiContext.getString(R.string.podcasts_load_error_message)) }
                }
            } finally {
                if (identity == podcastFeedIdentity && region == podcastFeedRegion &&
                    kotlinx.coroutines.currentCoroutineContext().isActive
                ) mutablePodcastFeed.update { it.copy(loadingMore = false) }
            }
        }
    }

    private fun podcastListenerKey(): String = YouTubeAccount.info.value?.let { info ->
        info.handle.ifBlank { info.name }
    }?.lowercase(Locale.ROOT) ?: if (YouTubeAccount.signedIn) "signed-in" else "anonymous"

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
        refreshYouTubeLibrary()
        loadPodcastFeed()
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
                if (now) loadPodcastFeed()
                if (now && !wasOnline) refreshYouTubeLibrary()
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
        if (item.kind == MusicKind.Track && item.isYouTubeTrack) { toggleYouTubeLike(item); return }
        if (item.isLocalTrack || item.isLocalPlaylist || item.source != `in`.melo.data.MusicSource.Spotify || item.id.startsWith("ytm:")) return
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

    private fun toggleYouTubeLike(item: Music) {
        val liked = youTubeLikes.value.none { it.id == item.id }
        val next = if (liked) listOf(item.copy(mediaUri = null)) + youTubeLikes.value else youTubeLikes.value.filterNot { it.id == item.id }
        youTubeLikes.value = next
        preferences.edit { putString(KEY_YOUTUBE_LIKES, encodePins(next)) }
        val current = mutableLibrary.value
        if (current.content != null) installLibrary(current.content, current.loading)
        else savedIds.update { if (liked) it + item.id else it - item.id }
        val videoId = item.youTubeVideoId ?: return
        if (!YouTubeAccount.signedIn || !online.value) return
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { YouTubeLibrary.like(videoId, liked) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth) }
            // The like stays on the device; YouTube Music gets it the next time it is changed.
            catch (failure: IOException) {
                `in`.melo.data.diagnostics.DiagnosticLog.record("youtube-library", "like ${failure.javaClass.simpleName} \"${failure.message}\"")
            }
        }
    }

    /** The liked songs with the YouTube Music likes on top, replacing those an earlier merge put there. */
    private fun withYouTubeLikes(library: HomeLibrary): HomeLibrary {
        val likes = youTubeLikes.value
        val spotifyUris = library.savedTrackUris.filterNot { it.startsWith(YTM_TRACK_PREFIX) }
        val removed = library.savedTrackUris.size - spotifyUris.size
        return library.copy(
            savedTracks = likes + library.savedTracks.filterNot { it.isYouTubeTrack },
            savedTrackUris = likes.map(Music::id) + spotifyUris,
            savedTrackCount = (library.savedTrackCount - removed).coerceAtLeast(0) + likes.size,
            savedTracksLoaded = (library.savedTracksLoaded - removed).coerceAtLeast(0) + likes.size,
        )
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
        refreshYouTubeLibrary()
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
        val visible = withYouTubeLikes(applyFavoriteChanges(merged, favoriteChanges.values)).let { library ->
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
        if (item.kind == MusicKind.Artist && !more) {
            if (lastOpenedArtist != item) {
                lastOpenedArtist = item
                preferences.edit { putString(KEY_LAST_OPEN_ARTIST, encodePins(listOf(item))) }
            }
        }
        if (item.isLocalPlaylist) { installLocalPlaylist(item.id); return }
        if (item.id.startsWith(YTM_PLAYLIST_PREFIX)) { loadYouTubePlaylist(item, refresh || more); return }
        if (item.id.startsWith(`in`.melo.data.youtube.YTM_ALBUM_PREFIX) || item.id.startsWith(`in`.melo.data.youtube.YTM_ARTIST_PREFIX)) {
            loadYouTubePage(item, refresh); return
        }
        if (catalog == null && item.id != LIKED_SONGS && item.id.startsWith("spotify:") && item.kind != MusicKind.Track) {
            loadPublicSpotifyDetail(item, refresh)
            return
        }
        if (item.id == LIKED_SONGS && catalog == null) {
            // Without a Spotify account the liked songs are the YouTube Music likes kept on the device.
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
    fun refreshHomeRecommendations(force: Boolean = false) {
        val isOnline = online.value
        if (!isOnline) return
        val listener = recommendationListenerKey()
        val hour = recommendationHourKey()
        val key = "$listener|$hour"
        if (!force && key == homeRecommendationKey) return
        if (activeHomeRecommendationListener != listener) {
            mutableHomeRecommendations.value = HomeRecommendationState()
            activeHomeRecommendationListener = listener
        }
        homeRecommendationKey = key
        pendingHomeRecommendationRequest = HomeRecommendationRequest(
            key = key,
            listener = listener,
            hour = hour,
            online = isOnline,
            youtubeAvailable = YouTubeAccount.signedIn,
        )
        if (homeRecommendationJob?.isActive == true) return
        homeRecommendationJob = viewModelScope.launch {
            try {
                while (true) {
                    val request = pendingHomeRecommendationRequest ?: break
                    pendingHomeRecommendationRequest = null
                    mutableHomeRecommendations.update { it.copy(loading = true) }
                    val result = try {
                        buildHomeRecommendations(request)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (request.key == homeRecommendationKey) {
                        if (result != null) {
                            mutableHomeRecommendations.value = result
                            val cache = HomeRecommendationCache(request.listener, request.hour,
                                result.copy(youtubeRecent = result.youtubeRecent.take(HOME_YOUTUBE_RECENT)))
                            preferences.edit { putString(KEY_HOME_RECOMMENDATIONS, HomeRecommendationCacheCodec.encode(cache)) }
                        } else {
                            homeRecommendationKey = null
                            mutableHomeRecommendations.update { it.copy(loading = false) }
                        }
                    }
                }
            } finally {
                homeRecommendationJob = null
                if (pendingHomeRecommendationRequest != null && online.value) {
                    refreshHomeRecommendations(force = true)
                }
            }
        }
    }

    /** Pull-to-refresh updates the library, recommendation dial, Home feed, and selected category. */
    fun refreshHome() {
        refreshLibrary()
        refreshHomeRecommendations(force = true)
        refreshYouTubeHomeCategories(force = true)
    }

    /** Reads the personalized Home page and category chips, then appends discovery shelves. */
    fun refreshYouTubeHomeCategories(force: Boolean = false) {
        if (!online.value) return
        val identity = prepareHomeCategoryIdentity()
        if (!force && (mutableYouTubeHome.value.chips.isNotEmpty() || mutableYouTubeHome.value.personalizedShelves.isNotEmpty() ||
                homeCategoriesJob?.isActive == true)) return
        homeCategoriesJob?.cancel()
        mutableYouTubeHome.update {
            it.copy(loadingCategories = true, personalizedLoading = true, error = null, personalizedError = null)
        }
        homeCategoriesJob = viewModelScope.launch {
            try {
                val (response, supplements) = withContext(Dispatchers.IO) {
                    kotlinx.coroutines.coroutineScope {
                        val home = async { YouTubeLibrary.homeRequest("browse", org.json.JSONObject().put("browseId", "FEmusic_home")) }
                        val extraPages = listOf("FEmusic_new_releases", "FEmusic_explore").map { browseId ->
                            async {
                                try {
                                    val page = YouTubeLibrary.homeRequest("browse", org.json.JSONObject().put("browseId", browseId))
                                    YouTubePages.homePage(page).shelves
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            }
                        }
                        home.await() to extraPages.map { it.await() }.flatten()
                    }
                }
                if (identity != recommendationListenerKey()) return@launch
                val fetchedChips = YouTubePages.homeChips(response)
                val basePage = YouTubePages.homePage(response)
                val chips = fetchedChips.ifEmpty { mutableYouTubeHome.value.chips }
                if (chips.isNotEmpty()) preferences.edit { putString(KEY_HOME_CATEGORY_CHIPS, encodeYouTubeHomeChips(chips)) }
                val before = mutableYouTubeHome.value
                val selected = before.selected?.let { old -> chips.firstOrNull { it.params == old.params } ?: old }
                val fullFeed = mergeHomeShelves(basePage.shelves + supplements)
                preferences.edit {
                    putString(KEY_HOME_FEED, encodeYouTubeHomeFeed(YouTubeHomeFeedCache(identity, fullFeed, basePage.continuation)))
                }
                mutableYouTubeHome.update {
                    it.copy(
                        chips = chips,
                        selected = selected,
                        personalizedShelves = fullFeed,
                        personalizedContinuation = basePage.continuation,
                        personalizedLoading = false,
                        personalizedLoadingMore = false,
                        personalizedError = null,
                        loadingCategories = false,
                        error = null,
                        personalizedLoadedAt = System.currentTimeMillis(),
                    )
                }
                if (selected != null) loadYouTubeHomeCategory(selected, identity, keepExisting = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (identity == recommendationListenerKey()) {
                    mutableYouTubeHome.update {
                        it.copy(
                            loadingCategories = false,
                            personalizedLoading = false,
                            error = readable(failure),
                            personalizedError = readable(failure),
                        )
                    }
                }
            }
        }
    }

    /** Selects a Home category; tapping the active chip restores the ordinary Home page. */
    fun selectYouTubeHomeCategory(chip: YouTubeHomeChip) {
        val current = mutableYouTubeHome.value
        if (current.selected?.params == chip.params) {
            homeCategoryJob?.cancel()
            homeCategoryMoreJob?.cancel()
            mutableYouTubeHome.update {
                it.copy(selected = null, shelves = emptyList(), continuation = null, loading = false,
                    loadingMore = false, error = null)
            }
            return
        }
        val identity = prepareHomeCategoryIdentity()
        val selected = current.chips.firstOrNull { it.params == chip.params } ?: chip
        mutableYouTubeHome.update {
            it.copy(selected = selected, shelves = emptyList(), continuation = null, loading = true,
                loadingMore = false, error = null)
        }
        loadYouTubeHomeCategory(selected, identity, keepExisting = false)
    }

    /** Reloads the selected category after a pull, retaining its current rows until the new page arrives. */
    private fun loadYouTubeHomeCategory(chip: YouTubeHomeChip, identity: String, keepExisting: Boolean) {
        homeCategoryJob?.cancel()
        homeCategoryMoreJob?.cancel()
        mutableYouTubeHome.update {
            it.copy(selected = chip, loading = true, loadingMore = false, error = null,
                shelves = if (keepExisting) it.shelves else emptyList(),
                continuation = if (keepExisting) it.continuation else null)
        }
        homeCategoryJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    val response = YouTubeLibrary.homeRequest("browse", org.json.JSONObject()
                        .put("browseId", "FEmusic_home").put("params", chip.params))
                    YouTubePages.homePage(response)
                }
                if (identity != recommendationListenerKey() || mutableYouTubeHome.value.selected?.params != chip.params) return@launch
                mutableYouTubeHome.update {
                    it.copy(shelves = mergeHomeShelves(page.shelves), continuation = page.continuation,
                        loading = false, error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (identity == recommendationListenerKey() && mutableYouTubeHome.value.selected?.params == chip.params) {
                    mutableYouTubeHome.update { it.copy(loading = false, error = readable(failure)) }
                }
            }
        }
    }

    /**
     * What the Mixes Dial deals from besides Home's loaded shelves, read afresh for each deal:
     * YouTube Music's "Mixed for you" page; Home's further pages when
     * the loaded ones lack "From the community", up to [MixesDial.COMMUNITY_PAGES] of them; and
     * the playlists of Explore's "Decades" category. A source that fails is left empty.
     */
    suspend fun mixesDialSources(loaded: List<`in`.melo.data.YouTubeHomeShelf>): MixesDialSources =
        withContext(Dispatchers.IO) {
            kotlinx.coroutines.coroutineScope {
                val mixed = async { mixesDialRead("mixed for you") { YouTubeLibrary.mixedForYou() } }
                val more = async {
                    if (`in`.melo.data.MixesDial.shelfPlaylists(loaded, `in`.melo.data.MixesDial.FROM_THE_COMMUNITY).isNotEmpty()) {
                        emptyList()
                    } else mixesDialRead("community") { communityPages() }
                }
                val decades = async { mixesDialRead("decades") { decadesPlaylists() } }
                MixesDialSources(mixed.await(), more.await(), decades.await())
            }
        }

    private suspend fun <T> mixesDialRead(what: String, read: suspend () -> List<T>): List<T> = try {
        read().also { android.util.Log.d("MixesDial", "$what: ${it.size}") }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        android.util.Log.w("MixesDial", "$what unavailable: ${failure.message}")
        emptyList()
    }

    /** Home read from the top, a few pages on, until "From the community" turns up. */
    private fun communityPages(): List<`in`.melo.data.YouTubeHomeShelf> {
        val read = ArrayList<`in`.melo.data.YouTubeHomeShelf>()
        var page = YouTubePages.homePage(YouTubeLibrary.homeRequest("browse", org.json.JSONObject().put("browseId", "FEmusic_home")))
        var pages = 0
        while (true) {
            read += page.shelves
            if (`in`.melo.data.MixesDial.shelfPlaylists(read, `in`.melo.data.MixesDial.FROM_THE_COMMUNITY).isNotEmpty()) break
            val token = page.continuation?.takeIf { pages++ < `in`.melo.data.MixesDial.COMMUNITY_PAGES } ?: break
            page = YouTubePages.homeContinuationPage(
                YouTubeLibrary.homeRequest("browse", org.json.JSONObject().put("continuation", token)),
            )
        }
        return read
    }

    /** Every playlist of Explore's "Decades" category; its place on Explore is kept once found. */
    private fun decadesPlaylists(): List<Music> {
        val category = decadesCategory ?: run {
            val sections = mutableYouTubeExplore.value.sections.ifEmpty {
                YouTubePages.exploreCategories(
                    YouTubeLibrary.homeRequest("browse", org.json.JSONObject().put("browseId", "FEmusic_moods_and_genres")),
                )
            }
            sections.flatMap { it.categories }
                .firstOrNull { it.title.trim().equals(`in`.melo.data.MixesDial.DECADES, ignoreCase = true) }
                ?.also { decadesCategory = it }
        } ?: return emptyList()
        val body = org.json.JSONObject().put("browseId", category.browseId)
        category.params?.let { body.put("params", it) }
        return YouTubePages.browseShelves(YouTubeLibrary.homeRequest("browse", body))
            .flatMap { it.items }
            .filter(`in`.melo.data.MixesDial::isYouTubePlaylist)
    }

    @Volatile private var decadesCategory: YouTubeExploreCategory? = null

    /** Loads the selected category's next Home page as the list approaches its end. */
    fun loadMoreYouTubeHomeCategory() {
        val state = mutableYouTubeHome.value
        val chip = state.selected ?: return
        val token = state.continuation ?: return
        if (!online.value || state.loading || state.loadingMore || homeCategoryMoreJob?.isActive == true) return
        val identity = prepareHomeCategoryIdentity()
        mutableYouTubeHome.update { it.copy(loadingMore = true) }
        homeCategoryMoreJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    val response = YouTubeLibrary.homeRequest("browse", org.json.JSONObject().put("continuation", token))
                    YouTubePages.homeContinuationPage(response)
                }
                if (identity != recommendationListenerKey() || mutableYouTubeHome.value.selected?.params != chip.params) return@launch
                val before = mutableYouTubeHome.value.shelves
                val merged = mergeHomeShelves(before + page.shelves)
                val oldItems = before.sumOf { it.items.size }
                val newItems = merged.sumOf { it.items.size }
                mutableYouTubeHome.update {
                    it.copy(shelves = merged, continuation = page.continuation.takeIf { newItems > oldItems },
                        loadingMore = false, error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (identity == recommendationListenerKey() && mutableYouTubeHome.value.selected?.params == chip.params) {
                    mutableYouTubeHome.update { it.copy(loadingMore = false, error = readable(failure)) }
                }
            }
        }
    }

    /** Continue the default YouTube Music Home feed as its last shelves approach the viewport. */
    fun loadMoreYouTubeHomeFeed() {
        val state = mutableYouTubeHome.value
        val token = state.personalizedContinuation ?: return
        if (!online.value || state.personalizedLoading || state.personalizedLoadingMore || homeFeedMoreJob?.isActive == true) return
        val identity = prepareHomeCategoryIdentity()
        mutableYouTubeHome.update { it.copy(personalizedLoadingMore = true) }
        homeFeedMoreJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    val response = YouTubeLibrary.homeRequest("browse", org.json.JSONObject().put("continuation", token))
                    YouTubePages.homeContinuationPage(response)
                }
                if (identity != recommendationListenerKey()) return@launch
                val before = mutableYouTubeHome.value.personalizedShelves
                val merged = mergeHomeShelves(before + page.shelves)
                val oldItems = before.sumOf { it.items.size }
                val newItems = merged.sumOf { it.items.size }
                val next = page.continuation.takeIf { newItems > oldItems }
                preferences.edit {
                    putString(KEY_HOME_FEED, encodeYouTubeHomeFeed(YouTubeHomeFeedCache(identity, merged, next)))
                }
                mutableYouTubeHome.update {
                    it.copy(
                        personalizedShelves = merged,
                        personalizedContinuation = next,
                        personalizedLoadingMore = false,
                        personalizedError = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (identity == recommendationListenerKey()) {
                    mutableYouTubeHome.update { it.copy(personalizedLoadingMore = false, personalizedError = readable(failure)) }
                }
            }
        }
    }

    private fun prepareHomeCategoryIdentity(): String {
        val identity = recommendationListenerKey()
        if (homeCategoryIdentity != identity) {
            homeCategoryIdentity = identity
            homeCategoryJob?.cancel()
            homeCategoryMoreJob?.cancel()
            homeFeedMoreJob?.cancel()
            mutableYouTubeHome.update {
                it.copy(selected = null, shelves = emptyList(), continuation = null, loading = false,
                    loadingMore = false, error = null, personalizedShelves = emptyList(),
                    personalizedContinuation = null, personalizedLoading = false, personalizedLoadingMore = false,
                    personalizedError = null)
            }
        }
        return identity
    }

    private fun mergeHomeShelves(shelves: List<`in`.melo.data.YouTubeHomeShelf>): List<`in`.melo.data.YouTubeHomeShelf> {
        val positions = LinkedHashMap<String, Int>()
        val merged = ArrayList<`in`.melo.data.YouTubeHomeShelf>()
        shelves.forEach { shelf ->
            val key = shelf.title.trim().lowercase(Locale.ROOT)
            val index = positions[key]
            if (index == null) {
                positions[key] = merged.size
                merged += shelf.copy(items = shelf.items.distinctBy(Music::id))
            } else {
                val existing = merged[index]
                val items = (existing.items + shelf.items).distinctBy(Music::id)
                merged[index] = existing.copy(items = items)
            }
        }
        return merged.filter { it.items.isNotEmpty() }
    }

    private fun syncExploreRegion() {
        val currentRegion = YouTubeMusic.contentRegion()
        if (currentRegion == exploreRegion) return
        exploreJob?.cancel()
        exploreRegion = currentRegion
        val cached = YouTubeExploreCacheCodec.decode(
            preferences.getString(KEY_YOUTUBE_EXPLORE_CACHE, null),
        )?.takeIf { it.region == currentRegion }
        exploreLoadedAt = cached?.savedAtMs ?: 0L
        mutableYouTubeExplore.value = cached?.let { YouTubeExploreState(sections = it.sections) }
            ?: YouTubeExploreState()
    }

    /** Loads cached Explore categories immediately, then refreshes them when their six-hour cache expires. */
    fun refreshYouTubeExplore(force: Boolean = false) {
        syncExploreRegion()
        if (!online.value) return
        if (exploreJob?.isActive == true && !force) return
        if (!force && mutableYouTubeExplore.value.sections.isNotEmpty() &&
            System.currentTimeMillis() - exploreLoadedAt < YOUTUBE_EXPLORE_TTL_MS
        ) return
        exploreJob?.cancel()
        mutableYouTubeExplore.update { it.copy(loading = force || it.sections.isEmpty(), error = null) }
        val requestRegion = exploreRegion
        exploreJob = viewModelScope.launch {
            try {
                val sections = withContext(Dispatchers.IO) {
                    val response = YouTubeLibrary.homeRequest("browse", org.json.JSONObject().put("browseId", "FEmusic_moods_and_genres"))
                    YouTubePages.exploreCategories(response)
                }
                if (requestRegion != exploreRegion || requestRegion != YouTubeMusic.contentRegion()) return@launch
                val previousArt = mutableYouTubeExplore.value.sections.flatMap { it.categories }
                    .mapNotNull { category -> category.imageUrl?.let { category.key to it } }.toMap()
                val enriched = sections.map { section ->
                    section.copy(categories = section.categories.map { category ->
                        category.copy(imageUrl = category.imageUrl ?: previousArt[category.key])
                    })
                }
                val savedAt = System.currentTimeMillis()
                exploreLoadedAt = savedAt
                preferences.edit {
                    putString(
                        KEY_YOUTUBE_EXPLORE_CACHE,
                        YouTubeExploreCacheCodec.encode(YouTubeExploreCache(requestRegion, savedAt, enriched)),
                    )
                }
                mutableYouTubeExplore.update { it.copy(sections = enriched, loading = false, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (requestRegion == exploreRegion) mutableYouTubeExplore.update {
                    it.copy(loading = false, error = if (it.sections.isEmpty()) readable(failure) else null)
                }
            }
        }
    }

    /** Queues one quiet-start pass for Explore and the last artist the user opened. */
    fun scheduleNavigationWarmup(
        includeLastArtist: Boolean = true,
        includeExploreArtwork: Boolean = true,
    ) {
        if (navigationWarmupJob?.isActive == true) return
        navigationWarmupJob = viewModelScope.launch {
            delay(NAVIGATION_WARMUP_DELAY_MS)
            if (!awaitHomeStartupQuiet()) return@launch
            if (!canWarmNavigation()) return@launch

            refreshYouTubeExplore()
            exploreJob?.join()
            if (includeLastArtist && canWarmNavigation()) preloadLastOpenedArtist()
            if (includeExploreArtwork && canWarmNavigation()) preloadYouTubeExploreArtwork()
        }
    }

    fun cancelNavigationWarmup() {
        navigationWarmupJob?.cancel()
        navigationWarmupJob = null
    }

    private fun canWarmNavigation(): Boolean {
        return navigationWarmupAllowed() && !player.state.value.buffering
    }

    private fun navigationWarmupAllowed(): Boolean {
        if (!online.value) return false
        val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)
        if (connectivity?.isActiveNetworkMetered != false) return false
        val activityManager = app.getSystemService(android.app.ActivityManager::class.java)
        return activityManager?.isLowRamDevice != true
    }

    private suspend fun preloadLastOpenedArtist() {
        val artist = lastOpenedArtist?.takeIf { it.kind == MusicKind.Artist } ?: return
        if (artist.id.startsWith("spotify:") && (catalog == null || offlineSyncJob?.isActive == true)) return
        loadDetail(artist)
        detailJobs[artist.id]?.join()
    }

    /** Starts a category's playlist shelves and keeps the category card's artwork for later visits. */
    fun selectYouTubeExploreCategory(category: YouTubeExploreCategory) {
        exploreCategoryJob?.cancel()
        mutableYouTubeExplore.update {
            it.copy(selected = category, shelves = emptyList(), loadingCategory = true, categoryError = null)
        }
        exploreCategoryJob = viewModelScope.launch {
            try {
                val shelves = withContext(Dispatchers.IO) {
                    val body = org.json.JSONObject().put("browseId", category.browseId)
                    category.params?.let { body.put("params", it) }
                    val response = YouTubeLibrary.homeRequest("browse", body)
                    YouTubePages.browseShelves(response)
                }
                val art = shelves.asSequence().flatMap { it.items.asSequence() }.mapNotNull(Music::imageUrl).firstOrNull()
                mutableYouTubeExplore.update { current ->
                    val selected = current.selected?.takeIf { it.key == category.key } ?: return@update current
                    val updated = selected.copy(imageUrl = selected.imageUrl ?: art)
                    current.copy(
                        selected = updated,
                        sections = current.sections.map { section ->
                            section.copy(categories = section.categories.map { if (it.key == updated.key) updated else it })
                        },
                        shelves = shelves,
                        loadingCategory = false,
                        categoryError = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableYouTubeExplore.update { it.copy(loadingCategory = false, categoryError = readable(failure)) }
            }
        }
    }

    fun closeYouTubeExploreCategory() {
        exploreCategoryJob?.cancel()
        mutableYouTubeExplore.update { it.copy(selected = null, shelves = emptyList(), loadingCategory = false, categoryError = null) }
    }

    /** Fetches artwork for visible Explore cards without competing with Home's first load. */
    fun loadYouTubeExploreArtwork(category: YouTubeExploreCategory) {
        if (!online.value || category.imageUrl != null || !exploreArtRequests.add(category.key)) return
        viewModelScope.launch {
            try {
                resolveYouTubeExploreArtwork(category, prefetchImage = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The card keeps its service-provided color and can still open the category.
            } finally {
                exploreArtRequests.remove(category.key)
            }
        }
    }

    private suspend fun awaitHomeStartupQuiet(): Boolean {
        if (homeStartupSettled) return true
        val deadline = android.os.SystemClock.elapsedRealtime() + STARTUP_REQUEST_WAIT_MS
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!isHomeStartupBusy()) {
                delay(NAVIGATION_WARMUP_QUIET_MS)
                if (!isHomeStartupBusy()) {
                    homeStartupSettled = true
                    return true
                }
            }
            delay(NAVIGATION_WARMUP_POLL_MS)
        }
        return false
    }

    private fun isHomeStartupBusy(): Boolean = mutableAccount.value.restoring || mutableLibrary.value.loading || listOfNotNull(
        youTubeJob,
        podcastFeedJob,
        homeCategoriesJob,
        homeCategoryJob,
        homeRecommendationJob,
    ).any(Job::isActive)

    private suspend fun preloadYouTubeExploreArtwork() {
        mutableYouTubeExplore.value.sections.asSequence()
            .flatMap { it.categories.asSequence() }
            .distinctBy(YouTubeExploreCategory::key)
            .take(MAX_EXPLORE_ART_PREFETCH)
            .forEach { category ->
                if (!canWarmNavigation()) return
                try {
                    resolveYouTubeExploreArtwork(category, prefetchImage = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // One unavailable category should not stop the remaining warm-up.
                }
            }
    }

    private suspend fun resolveYouTubeExploreArtwork(
        category: YouTubeExploreCategory,
        prefetchImage: Boolean,
    ) {
        val latestCategory = mutableYouTubeExplore.value.sections.asSequence()
            .flatMap { it.categories.asSequence() }
            .firstOrNull { it.key == category.key } ?: return
        val art = latestCategory.imageUrl ?: exploreArtworkSemaphore.withPermit {
            val refreshed = mutableYouTubeExplore.value.sections.asSequence()
                .flatMap { it.categories.asSequence() }
                .firstOrNull { it.key == category.key } ?: return@withPermit null
            refreshed.imageUrl ?: withContext(Dispatchers.IO) {
                val body = org.json.JSONObject().put("browseId", refreshed.browseId)
                refreshed.params?.let { body.put("params", it) }
                val response = YouTubeLibrary.homeRequest("browse", body)
                YouTubePages.browseShelves(response).asSequence()
                    .flatMap { it.items.asSequence() }.mapNotNull(Music::imageUrl).firstOrNull()
            }?.also { resolved -> updateYouTubeExploreArtwork(category, resolved) }
        } ?: return

        if (prefetchImage) {
            val request = coil.request.ImageRequest.Builder(app)
                .data(art)
                .size(coil.size.Size(300, 300))
                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                .allowHardware(false)
                .build()
            coil.Coil.imageLoader(app).execute(request)
        }
    }

    private fun updateYouTubeExploreArtwork(category: YouTubeExploreCategory, imageUrl: String) {
        var updatedSections: List<`in`.melo.data.YouTubeExploreSection>? = null
        mutableYouTubeExplore.update { current ->
            if (current.sections.none { section -> section.categories.any { it.key == category.key } }) return@update current
            current.sections.map { section ->
                section.copy(categories = section.categories.map {
                    if (it.key == category.key) it.copy(imageUrl = imageUrl) else it
                })
            }.also { updatedSections = it }.let { current.copy(sections = it) }
        }
        updatedSections?.let { sections ->
            val savedAt = exploreLoadedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            preferences.edit {
                putString(
                    KEY_YOUTUBE_EXPLORE_CACHE,
                    YouTubeExploreCacheCodec.encode(YouTubeExploreCache(exploreRegion, savedAt, sections)),
                )
            }
        }
    }

    private suspend fun buildHomeRecommendations(request: HomeRecommendationRequest): HomeRecommendationState {
        withContext(Dispatchers.IO) { listeningHistory.load() }
        val localEntries = listeningHistory.entries.value
        val localTaste = homeLocalTaste(localEntries)
        var accountHistory = emptyList<`in`.melo.data.HomeHistoryTrack>()
        var mixes = emptyList<Music>()
        if (request.online && request.youtubeAvailable) {
            try {
                val data = kotlinx.coroutines.coroutineScope {
                    val history = async(Dispatchers.IO) { YouTubeLibrary.recentHistoryRows(limit = 2_000) }
                    val mixedForYou = async(Dispatchers.IO) { YouTubeLibrary.mixedForYou() }
                    history.await() to mixedForYou.await()
                }
                accountHistory = data.first
                mixes = data.second
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: YouTubeAuthRequired) {
                youTubeAccountLost(auth)
            } catch (_: Exception) {
                // Melo's local Replay chart still supplies useful artist seeds if the account feed is unavailable.
            }
        }
        if (accountHistory.isEmpty()) {
            accountHistory = localEntries.asSequence().mapNotNull { entry ->
                val videoId = entry.track.youTubeVideoId ?: return@mapNotNull null
                `in`.melo.data.HomeHistoryTrack(entry.track,
                    YouTubePages.rememberedRelations(videoId)?.artists.orEmpty())
            }.distinctBy { it.track.id }.toList()
        }
        val picks = HomeRecommendationEngine.build(accountHistory, mixes, localTaste)
        val artists = accountHistory.asSequence().flatMap { it.artists.asSequence() }
            .filter { it.kind == MusicKind.Artist }
            .distinctBy(Music::id).take(36).toList()
        return HomeRecommendationState(
            picks = picks,
            // As many as the speed dial holds: its Recents, after YouTube's own Speed dial shelf.
            youtubeRecent = accountHistory.map { it.track }.take(HOME_YOUTUBE_RECENT),
            youtubeArtists = artists,
            youtubeMixes = mixes.filter { it.kind == MusicKind.Playlist }.distinctBy(Music::id),
        )
    }

    private fun homeLocalTaste(entries: List<`in`.melo.data.HistoryEntry>): HomeLocalTaste {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val monthStart = calendar.timeInMillis
        val month = entries.asSequence().filter { it.playedAt >= monthStart && it.track.kind == MusicKind.Track }.toList()
        val artists = month.mapNotNull { entry ->
            entry.track.subtitle.split(" · ", ",", ";").firstOrNull()?.trim()?.takeIf(String::isNotBlank)
        }.groupingBy { it }.eachCount().entries.sortedByDescending(Map.Entry<String, Int>::value)
            .take(10).map(Map.Entry<String, Int>::key)
        val songs = month.groupBy { it.track.id }.values
            .sortedByDescending { plays -> plays.sumOf { (it.track.durationMs.coerceAtLeast(180_000)).toLong() } }
            .take(5).map { it.first().track }
        val genres = month.map { it.track.genre.trim() }.filter(String::isNotBlank)
            .groupingBy { it }.eachCount().entries.sortedByDescending(Map.Entry<String, Int>::value)
            .take(3).map(Map.Entry<String, Int>::key)
        return HomeLocalTaste(artists, songs, genres)
    }

    private fun recommendationListenerKey(): String = YouTubeAccount.info.value?.let { info ->
        info.handle.takeIf(String::isNotBlank) ?: info.name
    }?.lowercase(Locale.ROOT) ?: if (YouTubeAccount.signedIn) "signed-in" else "signed-out"

    private fun recommendationHourKey(): String = SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.ROOT).format(Date())

    fun playHomeMood(mood: HomeMood) {
        val title = mood.name.lowercase(Locale.ROOT).replace('_', ' ')
        val item = Music("melo:home-mood:${mood.name}", title, "", MusicKind.Playlist)
        playHomeRecommendation(HomeRecommendationPick(
            item = item,
            type = HomeRecommendationType.MOOD,
            title = title,
            mood = mood,
        ))
    }

    fun playHomeRecommendation(pick: HomeRecommendationPick): Job? {
        if (!online.value) {
            mutableMessage.value = uiContext.getString(`in`.melo.R.string.home_mood_offline)
            return null
        }
        return viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { listeningHistory.load() }
                val localHistory = listeningHistory.entries.value.map { it.track }
                val tracks = HomeRecommendationEngine.queue(pick, localHistory)
                if (tracks.isEmpty()) throw YouTubeFailure("YouTube Music did not return tracks for this recommendation.")
                play(tracks)
                // Top Picks starts from a generated recommendation queue. Mark that context so
                // the player can extend it with another radio batch before the queue runs out.
                player.markRadioContext()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: YouTubeAuthRequired) {
                youTubeAccountLost(auth)
            } catch (failure: Exception) {
                mutableMessage.value = readable(failure)
            }
        }
    }


    /**
     * A track chosen on its own plays at once and its radio follows it, as in YouTube Music and Spotify,
     * instead of leaving a queue of one. The song never waits for the network: a radio that cannot be
     * loaded is not worth a message, the track simply plays alone.
     */
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
        if (seed.source == `in`.melo.data.MusicSource.MusicServer) return emptyList()
        if (seed.source == `in`.melo.data.MusicSource.YouTubeMusic ||
            (seed.source == `in`.melo.data.MusicSource.Spotify && catalog == null)) {
            val videoId = seed.youTubeVideoId ?: withContext(Dispatchers.IO) {
                `in`.melo.data.youtube.TrackMatcher.resolve(`in`.melo.data.youtube.MatchQuery(
                    key = seed.id, title = seed.title, artists = seed.subtitle,
                    durationMs = seed.durationMs, album = seed.album,
                ))
            } ?: return emptyList()
            return withContext(Dispatchers.IO) {
                YouTubeMusic.watchQueue("RDAMVM$videoId", videoId, limit = 50)
            }.filterNot { it.id == seed.id || it.youTubeVideoId == videoId }
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
            } catch (auth: YouTubeAuthRequired) {
                youTubeAccountLost(auth)
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
            } catch (auth: YouTubeAuthRequired) {
                youTubeAccountLost(auth)
            } catch (failure: Exception) {
                mutableMessage.value = readable(failure)
            } finally {
                mutableRadioRefreshInProgress.value = false
            }
        }
    }

    fun loadRadio(seed: Music, open: (Music) -> Unit) {
        if (seed.source == `in`.melo.data.MusicSource.YouTubeMusic ||
            (seed.source == `in`.melo.data.MusicSource.Spotify && catalog == null)) {
            if (!online.value || seed.source == `in`.melo.data.MusicSource.Local) return
            viewModelScope.launch {
                try {
                    val videoId = seed.youTubeVideoId ?: withContext(Dispatchers.IO) {
                        `in`.melo.data.youtube.TrackMatcher.resolve(`in`.melo.data.youtube.MatchQuery(
                            key = seed.id, title = seed.title, artists = seed.subtitle,
                            durationMs = seed.durationMs, album = seed.album,
                        ))
                    } ?: throw YouTubeFailure("YouTube Music could not find this radio seed.")
                    val tracks = withContext(Dispatchers.IO) {
                        YouTubeMusic.watchQueue("RDAMVM$videoId", videoId, limit = 100)
                    }
                    val firstTrack = tracks.indexOfFirst { it.kind == MusicKind.Track }
                    if (firstTrack >= 0) {
                        play(tracks, firstTrack)
                        player.markRadioContext()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    mutableMessage.value = readable(failure)
                }
            }
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
    /** Album and artists of a track, read through the catalogue contract used by detail pages. */
    /**
     * The album and artists of a track. Spotify tracks use Spotify's pages when an account is signed in;
     * YouTube Music tracks, and Spotify tracks without an account, use YouTube Music's.
     */
    suspend fun trackRelations(track: Music): List<Music> {
        if (track.isLocalTrack) return emptyList()
        if (track.source == `in`.melo.data.MusicSource.Spotify && catalog != null) return detailForActions(track)?.related.orEmpty()
        if (!online.value) return emptyList()
        val videoId = track.youTubeVideoId ?: withContext(Dispatchers.IO) {
            `in`.melo.data.youtube.TrackMatcher.resolve(`in`.melo.data.youtube.MatchQuery(
                key = track.id, title = track.title, artists = track.subtitle, durationMs = track.durationMs, album = track.album,
            ))
        } ?: return emptyList()
        val found = withContext(Dispatchers.IO) { `in`.melo.data.youtube.YouTubePages.relations(videoId, track.title, track.subtitle) }
            ?: return emptyList()
        return listOfNotNull(found.album) + found.artists
    }
    /** Menu actions read the stored page first, so they work the same without network. */
    private suspend fun detailForActions(item: Music): CatalogDetail? {
        mutableDetails.value[item.id]?.content?.let { return it }
        withContext(Dispatchers.IO) { store.loadDetail(item.id) }?.let { return it }
        val fetched = when {
            item.id.startsWith(`in`.melo.data.youtube.YTM_ALBUM_PREFIX) -> withContext(Dispatchers.IO) {
                `in`.melo.data.youtube.YouTubePages.album(item.id.removePrefix(`in`.melo.data.youtube.YTM_ALBUM_PREFIX))
            }
            item.id.startsWith(`in`.melo.data.youtube.YTM_ARTIST_PREFIX) -> withContext(Dispatchers.IO) {
                `in`.melo.data.youtube.YouTubePages.artist(item.id.removePrefix(`in`.melo.data.youtube.YTM_ARTIST_PREFIX))
            }
            else -> catalog?.detail(item)
        } ?: return null
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
            mutableLibrary.value.content?.savedTracks.orEmpty()
        }
        item.isLocalPlaylist -> withLocalFiles(localPlaylistStore.get(item.id)?.tracks.orEmpty())
        item.id.startsWith(YTM_PLAYLIST_PREFIX) -> youTubeTracks(item)
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
    /** Without a Spotify account, Spotify tracks play through their YouTube Music match. */
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
                nextPage = null, youTubeNext = null, error = null, loading = trimmed.length >= 2)
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
            val withYouTube = choice == SearchSource.All || choice == SearchSource.YouTubeMusic
            val locale = app.resources.configuration.locales.get(0) ?: Locale.getDefault()
            var spotifyItems: List<Music>? = null
            var youTubeMixed: List<Music>? = null
            var youTubeSongs: List<Music>? = null
            val device = if (choice == SearchSource.All) matchingDeviceFiles(query) else emptyList()
            val failures = ArrayList<Throwable>()
            var pending = (if (spotify != null) 1 else 0) + (if (withYouTube) 2 else 0)
            fun publish() {
                val youTube = (youTubeMixed.orEmpty() + youTubeSongs.orEmpty()).distinctBy(Music::id)
                val results = (if (choice == SearchSource.Spotify) spotifyItems.orEmpty()
                    else `in`.melo.data.SearchRanking.merge(query, listOfNotNull(
                        spotifyItems?.let { `in`.melo.data.SearchRanking.Source(it, 1.0) },
                        `in`.melo.data.SearchRanking.Source(youTube, if (spotify != null) 0.97 else 1.0),
                        `in`.melo.data.SearchRanking.Source(device, 1.02, deduplicated = false),
                    ))).filterNot { it.isPodcastSearchContainer() }
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
            if (withYouTube) {
                source("youtube-mixed", { withContext(Dispatchers.IO) { `in`.melo.data.youtube.YouTubePages.searchAll(query) } }) { youTubeMixed = it }
                source("youtube-songs", { withContext(Dispatchers.IO) { `in`.melo.data.youtube.YouTubeMusic.searchSongsPage(query) } }) { page ->
                    youTubeSongs = page.tracks
                    mutableDiscovery.update { it.copy(youTubeNext = page.next) }
                }
            }
        }
    }

    private fun readableSearchFailure(failure: Throwable): String = when (failure) {
        is `in`.melo.data.youtube.YouTubeFailure -> uiContext.getString(`in`.melo.R.string.youtube_search_failed)
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
        val youTubeToken = state.youTubeNext
        mutableDiscovery.update { it.copy(loading = true, error = null) }
        searchJob = viewModelScope.launch {
            val locale = app.resources.configuration.locales.get(0) ?: Locale.getDefault()
            val spotify = async { spotifyToken?.let { token -> remote?.let { source -> runCatching { source.search(query, locale, token) } } } }
            val youTube = async(Dispatchers.IO) { youTubeToken?.let { token -> runCatching { `in`.melo.data.youtube.YouTubeMusic.searchMore(token) } } }
            val spotifyPage = spotify.await()
            val youTubePage = youTube.await()
            val added = `in`.melo.data.SearchRanking.merge(query, listOfNotNull(
                spotifyPage?.getOrNull()?.items?.let { `in`.melo.data.SearchRanking.Source(it) },
                youTubePage?.getOrNull()?.tracks?.let { `in`.melo.data.SearchRanking.Source(it, 0.97) },
            )).filterNot { it.isPodcastSearchContainer() }
            val failed = listOfNotNull(spotifyPage?.exceptionOrNull(), youTubePage?.exceptionOrNull()).firstOrNull()
            mutableDiscovery.update {
                it.copy(loading = false, results = (it.results + added).distinctBy(Music::id),
                    nextPage = spotifyPage?.getOrNull()?.nextToken?.takeUnless { next -> next == spotifyToken },
                    youTubeNext = youTubePage?.getOrNull()?.next?.takeUnless { next -> next == youTubeToken },
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
                it.copy(loading = false, results = found, nextPage = null, youTubeNext = null, error = null)
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
    suspend fun searchTracks(query: String, source: String): List<Music> {
        val text = query.trim()
        if (text.length < 2) return emptyList()
        return when (source) {
            TRACKS_SPOTIFY -> {
                val search = remote ?: return emptyList()
                val locale = app.resources.configuration.locales.get(0) ?: Locale.getDefault()
                search.search(text, locale, null).items.filter { it.kind == MusicKind.Track }
            }
            TRACKS_YOUTUBE -> withContext(Dispatchers.IO) { `in`.melo.data.youtube.YouTubeMusic.search(text).tracks }
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
        if (item.isLocalPlaylist) {
            localPlaylistStore.delete(item.id)
            `in`.melo.playback.DownloadCenter.remove(app, item.id)
            pinnedStore.pins.value.firstOrNull { it.id == item.id }?.let(pinnedStore::toggle)
            mutableDetails.update { it - item.id }
            return
        }
        if (item.id.startsWith(YTM_PLAYLIST_PREFIX)) { deleteYouTubePlaylist(item); return }
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


    /** Kinds a playlist can be converted to now: Spotify needs its account, YouTube Music a Google account. */
    fun conversionTargets(source: Music): Map<`in`.melo.data.PlaylistKind, Boolean> {
        val from = `in`.melo.data.playlistKindOf(source) ?: return emptyMap()
        return `in`.melo.data.PlaylistKind.entries.filter { it != from }.associateWith { kind ->
            when (kind) {
                `in`.melo.data.PlaylistKind.Spotify -> remote != null
                `in`.melo.data.PlaylistKind.YouTubeMusic -> YouTubeAccount.signedIn
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
            cooldownMs = { if (target == `in`.melo.data.PlaylistKind.Spotify) 0L else `in`.melo.data.youtube.YouTubeMusic.cooldownMs() },
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
            `in`.melo.data.PlaylistKind.YouTubeMusic -> withContext(Dispatchers.IO) {
                // Songs first; videos only when no song is close, as for playback without a Spotify account.
                val songs = `in`.melo.data.youtube.YouTubeMusic.searchSongs(text)
                val best = `in`.melo.data.ConversionMatching.rank(track, songs).firstOrNull()?.second ?: 0.0
                if (best >= `in`.melo.data.ConversionMatching.CONFIDENT) songs
                else songs + runCatching { `in`.melo.data.youtube.YouTubeMusic.searchVideos(text) }.getOrDefault(emptyList())
            }
            `in`.melo.data.PlaylistKind.MultiService -> emptyList()
        }
    }

    /** Creates the converted playlist in its destination. */
    internal suspend fun createConverted(target: `in`.melo.data.PlaylistKind, name: String, tracks: List<Music>): Music = when (target) {
        `in`.melo.data.PlaylistKind.MultiService -> localPlaylistStore.create(name, tracks).asMusic().also { ui.register(it) }
        `in`.melo.data.PlaylistKind.YouTubeMusic -> createYouTubePlaylist(name, tracks, public = false)
        `in`.melo.data.PlaylistKind.Spotify -> {
            // One creation request stays small; the rest is added in batches.
            val created = createSpotifyPlaylist(name, tracks.take(100), published = false)
            if (tracks.size > 100) addSpotifyPlaylistTracks(created, tracks.drop(100))
            created
        }
    }

    /** The account's own playlist is deleted; a saved one only leaves the library. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun deleteYouTubePlaylist(item: Music) {
        if (!YouTubeAccount.signedIn) return
        val owned = youTubePlaylistOwned(item)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { if (owned) YouTubeLibrary.delete(item.id) else YouTubeLibrary.removeFromLibrary(item.id) }
                installYouTubePlaylists(mutableYouTubePlaylists.value.filterNot { it.id == item.id })
                markYouTubeEditable(item.id, false)
                youTubeRows.remove(item.id)
                pinnedStore.pins.value.firstOrNull { it.id == item.id }?.let(pinnedStore::toggle)
                `in`.melo.playback.DownloadCenter.remove(app, item.id)
                mutableDetails.update { it - item.id }
                withContext(Dispatchers.IO) { store.removeDetail(item.id) }
                mutableMessage.value = uiContext.getString(`in`.melo.R.string.playlist_deleted, item.title)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth) }
            catch (failure: Exception) { mutableMessage.value = uiContext.getString(R.string.youtube_playlist_failed) }
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

    /** A YouTube, YouTube Music or Spotify playlist link, copied as a multi-service playlist. */
    suspend fun importLink(link: String): Music {
        `in`.melo.data.youtube.youTubePlaylistId(link)?.let { id ->
            val playlist = withContext(Dispatchers.IO) { `in`.melo.data.youtube.YouTubeMusic.playlist(id) }
            return createImported(playlist.title, playlist.tracks)
        }
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
        private const val PODCAST_FEED_TTL_MS = 6L * 60 * 60 * 1000
        private const val YOUTUBE_PAGE_TTL_MS = 24L * 60 * 60 * 1000
        private const val SEARCH_DEBOUNCE_MS = 250L
        const val TRACKS_SPOTIFY = "spotify"
        const val TRACKS_YOUTUBE = "youtube"
        const val TRACKS_FILES = "files"
        private const val KEY_GUEST = "guest"
        private const val KEY_PENDING_LIKES = "pending_likes"
        private const val KEY_SPOTIFY_PROFILE = "spotify_account_profile"
        private const val KEY_YOUTUBE_LIKES = "youtube_likes"
        private const val KEY_PLAYLIST_COVERS = "playlist_covers"
        private const val KEY_HOME_RECOMMENDATIONS = "home_recommendations"
        private const val KEY_HOME_CATEGORY_CHIPS = "home_category_chips"
        private const val KEY_HOME_FEED = "home_feed"
        private const val KEY_PENDING_UNLIKES = "pending_unlikes"
        private const val KEY_YOUTUBE_PLAYLISTS = "youtube_playlists"
        private const val KEY_YOUTUBE_EDITABLE = "youtube_editable"
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
        private const val KEY_YOUTUBE_EXPLORE_CACHE = "youtube_explore_cache"
        private const val YOUTUBE_EXPLORE_TTL_MS = 6L * 60 * 60 * 1000
        private const val NAVIGATION_WARMUP_DELAY_MS = 1_500L
        private const val NAVIGATION_WARMUP_QUIET_MS = 600L
        private const val NAVIGATION_WARMUP_POLL_MS = 300L
        private const val STARTUP_REQUEST_WAIT_MS = 15_000L
        private const val MAX_EXPLORE_ART_PREFETCH = 8
        private const val RADIO_REFILL_THRESHOLD = 5
        private const val RADIO_REFILL_SEED_COUNT = 3
        private const val RADIO_REFILL_BATCH_SIZE = 50
    }
}

/** What [MeloModel.mixesDialSources] read for one deal of the Mixes Dial. */
data class MixesDialSources(
    val mixedForYou: List<Music>,
    /** Home's further pages, read for the community's shelves; empty when the loaded ones had them. */
    val morePages: List<`in`.melo.data.YouTubeHomeShelf>,
    val decades: List<Music>,
)

/** How many of the account's YouTube history songs Home keeps: the speed dial's size. */
private const val HOME_YOUTUBE_RECENT = 36
