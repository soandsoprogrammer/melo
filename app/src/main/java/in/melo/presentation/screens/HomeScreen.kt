package `in`.melo.presentation.screens

import `in`.melo.data.TourTarget
import `in`.melo.presentation.components.onboarding.tourAnchor
import `in`.melo.ui.theme.controlFill
import `in`.melo.presentation.icons.MeloIcons
import `in`.melo.ui.theme.MeloAlpha
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.runtime.CompositionLocalProvider
import `in`.melo.presentation.components.LocalHomeGradients
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import `in`.melo.presentation.components.MeloBottomSheet as ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.withContext
import `in`.melo.R
import `in`.melo.utils.formatSongCount
import `in`.melo.MeloModel
import `in`.melo.data.Music
import `in`.melo.data.MusicKind
import `in`.melo.data.HomeMood
import `in`.melo.data.HomeRecommendationType
import `in`.melo.data.HomeRecommendationPick
import `in`.melo.data.YouTubeHomeShelf
import `in`.melo.data.YouTubeHomeChip
import `in`.melo.data.MixesDial
import `in`.melo.data.ArtistDialFill
import `in`.melo.data.source
import `in`.melo.data.model.toSong
import `in`.melo.data.preferences.CollagePattern
import `in`.melo.presentation.components.AlbumArtCollage
import androidx.compose.ui.res.stringResource
import kotlinx.collections.immutable.toImmutableList
import `in`.melo.data.preferences.HomeShelf
import `in`.melo.data.preferences.ShelfStyle
import `in`.melo.data.preferences.AppSettings
import `in`.melo.ui.theme.MeloLayout
import `in`.melo.presentation.components.ListeningStatusCard
import `in`.melo.presentation.components.ListeningShelfPlaceholder
import `in`.melo.presentation.components.ListeningHomeHeader
import `in`.melo.presentation.components.HomeGradientTopBar
import `in`.melo.presentation.components.MiniPlayerHeight
import `in`.melo.presentation.components.MusicOptionsHost
import `in`.melo.presentation.components.SmartImage
import `in`.melo.presentation.components.SmartImageCompactListTargetSize
import `in`.melo.presentation.components.resolveMainScreenBottomGradientHeight
import `in`.melo.presentation.navigation.Screen
import `in`.melo.presentation.navigation.navigateSafely
import `in`.melo.presentation.navigation.navigateToTopLevelSafely
import `in`.melo.presentation.navigation.openMusic
import `in`.melo.presentation.viewmodel.PlayerViewModel
import sh.calvin.reorderable.ReorderableColumn

private const val COLLAGE_ITEMS = 6
private const val COLLAGE_MINIMUM = 3

/** Where a Home shelf's songs go in the YouTube speed dial, by its title, in the languages YouTube names it in. */
private fun youTubeDialShelfRank(title: String): Int = when (title.trim().lowercase()) {
    "speed dial" -> 0
    "listen again", "nghe lại", "もう一度聴く" -> 1
    "forgotten favorites", "forgotten favourites", "giai điệu quen thuộc", "よく聴いたお気に入りの曲" -> 2
    "quick picks", "lựa chọn nhanh", "chọn nhanh đài phát", "クイック ミックス" -> 3
    else -> YOUTUBE_DIAL_OTHER_SHELF
}

private const val YOUTUBE_DIAL_OTHER_SHELF = 4

private fun categoryDialEntries(shelves: List<YouTubeHomeShelf>): List<HomeSpeedDialEntry> {
    val longest = shelves.maxOfOrNull { it.items.size } ?: return emptyList()
    val picks = ArrayList<Music>()
    for (round in 0 until longest) {
        shelves.forEach { shelf -> shelf.items.getOrNull(round)?.let(picks::add) }
    }
    return picks.map(::HomeSpeedDialEntry)
}

/**
 * The home canvas: Speed Dial above Melo's recommendations and library shelves.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(model: MeloModel, navController: NavHostController, paddingValues: PaddingValues) {
    val ui = model.ui
    val pins by model.pins.collectAsStateWithLifecycle()
    // Taken off the dials with "Remove from dial"; kept off until pinned again.
    val hiddenFromDial by model.hiddenFromDial.collectAsStateWithLifecycle()
    val downloads by ui.downloadCollections.collectAsStateWithLifecycle()
    val downloadsTitle = stringResource(R.string.home_downloads)
    val settings by ui.settings.collectAsStateWithLifecycle()
    val localPlaylists by model.localPlaylists.collectAsStateWithLifecycle()
    val youTubePlaylists by model.youTubePlaylists.collectAsStateWithLifecycle()
    val youTubeAccount by model.youTubeAccount.collectAsStateWithLifecycle()
    val spotifyProfile by model.spotifyAccountProfile.collectAsStateWithLifecycle()
    val localBadge = stringResource(R.string.badge_local)
    val playlistsTitle = stringResource(R.string.home_playlists)
    val albumsTitle = stringResource(R.string.home_albums)
    val artistsTitle = stringResource(R.string.home_artists)
    val recent by model.recent.collectAsStateWithLifecycle()
    val localFiles by model.localTracks.collectAsStateWithLifecycle()
    val shelfTitles = mapOf(
        HomeShelf.PINS to stringResource(R.string.home_pins),
        HomeShelf.RECENT to stringResource(R.string.home_recent),
        HomeShelf.MULTI_SERVICE to stringResource(R.string.home_multi_service),
        HomeShelf.SPOTIFY_PLAYLISTS to stringResource(R.string.home_spotify_playlists),
        HomeShelf.YOUTUBE_PLAYLISTS to stringResource(R.string.home_youtube_playlists),
        HomeShelf.LOCAL_FILES to stringResource(R.string.home_local_files),
    )
    val library by model.library.collectAsStateWithLifecycle()
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val online by model.online.collectAsStateWithLifecycle()
    val homeRecommendations by model.homeRecommendations.collectAsStateWithLifecycle()
    val homeDetails by model.details.collectAsStateWithLifecycle()
    val youTubeHome by model.youTubeHome.collectAsStateWithLifecycle()
    val recordedHistory by model.listeningHistory.entries.collectAsStateWithLifecycle()
    var spotifyHistory by remember(model) { mutableStateOf<List<Music>>(emptyList()) }
    LaunchedEffect(model) {
        withContext(Dispatchers.IO) { model.listeningHistory.load() }
    }
    LaunchedEffect(model, discovery.spotifyEnabled, online) {
        spotifyHistory = if (discovery.spotifyEnabled && online) {
            try {
                model.fullListeningHistory().map { it.track }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }
    val hasSong by ui.stablePlayerStateHasSong()
    val navBarCompactMode by ui.navBarCompactMode.collectAsStateWithLifecycle()
    val content = library.content
    val homeRecent = remember(recent, spotifyHistory, recordedHistory, homeRecommendations.youtubeRecent) {
        (recent + spotifyHistory + recordedHistory.map { it.track } + homeRecommendations.youtubeRecent)
            .filter { it.kind == MusicKind.Track }
            .distinctBy(Music::id)
            .take(12)
    }
    LaunchedEffect(model, online, youTubeAccount) {
        model.refreshHomeRecommendations()
        model.refreshYouTubeHomeCategories(force = true)
        while (true) {
            delay(60_000)
            model.refreshHomeRecommendations()
        }
    }
    val filteredHome = youTubeHome.selected != null
    val recentConfig = settings.homeLayout.firstOrNull { it.shelf == HomeShelf.RECENT }
    val recentFeed = if (recentConfig?.visible == true) homeRecent.take(recentConfig.count) else emptyList()
    val recentTitle = shelfTitles[HomeShelf.RECENT].orEmpty()
    val personalizedHome = youTubeHome.personalizedShelves
    val activeDialSource = if (settings.homeDialSource == AppSettings.DIAL_SOURCE_SPOTIFY && !discovery.spotifyEnabled) {
        AppSettings.DIAL_SOURCE_YOUTUBE
    } else settings.homeDialSource
    val spotifyDialRefreshKey = HomeDialRounds.shuffle
    val dialContext = androidx.compose.ui.platform.LocalContext.current
    val newReleaseIds = remember(personalizedHome) {
        personalizedHome.filter { it.title.contains("release", ignoreCase = true) || it.title.contains("new music", ignoreCase = true) }
            .flatMap(YouTubeHomeShelf::items).mapTo(HashSet(), Music::id)
    }
    // Melo's own stations, dealt with YouTube's mixes; kept by id to open them as stations.
    val stationPicks = remember(homeRecommendations.picks) {
        homeRecommendations.picks.filter { it.type in setOf(
            HomeRecommendationType.MOOD,
            HomeRecommendationType.PERSONAL_STATION,
            HomeRecommendationType.NEW_RELEASE_MIX,
            HomeRecommendationType.DISCOVERY_STATION,
        ) && it.item.kind == MusicKind.Playlist }.associateBy { it.item.id }
    }
    // Only the playlists Spotify made for this listener (Daily Mixes, Discover Weekly, Release
    // Radar…), not the listener's own playlists or editorial ones.
    val spotifyMixes = remember(discovery.spotifyEnabled, content?.playlists) {
        if (discovery.spotifyEnabled) content?.playlists.orEmpty().filter(MixesDial::isSpotifyMix) else emptyList()
    }
    fun mixEntry(item: Music): HomeSpeedDialEntry = stationPicks[item.id]
        ?.let { pick -> HomeSpeedDialEntry(pick.item, pick.title, pick.artworkUrls.firstOrNull(), pick) }
        ?: HomeSpeedDialEntry(item)
    // Before the first deal: the mixes already at hand, so the page isn't empty while it's dealt.
    val mixEntries = remember(homeRecommendations.youtubeMixes, stationPicks, personalizedHome, spotifyMixes, hiddenFromDial) {
        (homeRecommendations.youtubeMixes + personalizedHome.flatMap(YouTubeHomeShelf::items).filter(MixesDial::isYouTubeMix) +
            stationPicks.values.map(HomeRecommendationPick::item) + spotifyMixes)
            .filter { it.kind == MusicKind.Playlist && it.id !in hiddenFromDial }
            .distinctBy(Music::id)
            .take(MixesDial.SIZE)
            .map(::mixEntry)
    }
    // What was played lately (on the
    // account and here), then YouTube's own Speed dial shelf, then the rest of Home's feed, so the
    // dial is always full.
    val playedLately = remember(recent, spotifyHistory, recordedHistory, homeRecommendations.youtubeRecent) {
        (recent + spotifyHistory + recordedHistory.map { it.track } + homeRecommendations.youtubeRecent)
            .filter { it.kind == MusicKind.Track }
            .distinctBy(Music::id)
    }
    fun shelvesRanked(rank: Int) = personalizedHome.filter { youTubeDialShelfRank(it.title) == rank }.flatMap(YouTubeHomeShelf::items)
    val dialHistory = remember(playedLately, personalizedHome) {
        playedLately + shelvesRanked(0) + personalizedHome
            .filterNot { youTubeDialShelfRank(it.title) == 0 }
            .flatMap(YouTubeHomeShelf::items)
    }
    // The tiles past the pins, for the dial's source. For
    // YouTube, its own Speed dial shelf whole and in its order, then filled to the dial's size the
    // way YouTube fills that shelf: Listen again, what was played lately, forgotten favourites,
    // Quick picks, then the history above. Every kind of tile counts: songs, albums, playlists
    // and artists. Spotify's picks go ahead of the YouTube dial, which fills what they can't.
    val livePool = remember(
        personalizedHome, playedLately, dialHistory, content, activeDialSource, discovery.spotifyEnabled,
        spotifyHistory, spotifyDialRefreshKey, settings.homeDialLocalFiles, settings.homeArtistDialEnabled, hiddenFromDial,
    ) {
        val youTubeDial = buildList {
            addAll(shelvesRanked(0))
            addAll(shelvesRanked(1))
            addAll(playedLately)
            addAll(shelvesRanked(2))
            addAll(shelvesRanked(3))
            addAll(dialHistory)
        }
        when (activeDialSource) {
            AppSettings.DIAL_SOURCE_HISTORY -> dialHistory
            AppSettings.DIAL_SOURCE_SPOTIFY -> buildList {
                // Shuffled by the refresh key alone, so only a refresh deals them anew.
                addAll(buildList {
                    addAll(spotifyHistory.filter { it.source == `in`.melo.data.MusicSource.Spotify })
                    addAll(content?.savedTracks.orEmpty())
                }.distinctBy(Music::id).shuffled(kotlin.random.Random(spotifyDialRefreshKey)))
                addAll(youTubeDial)
            }
            else -> youTubeDial
        }.asSequence()
            .filter { settings.homeDialLocalFiles || it.source != `in`.melo.data.MusicSource.Local }
            // With the artist dial on, artists have their own page.
            .filterNot { settings.homeArtistDialEnabled && it.kind == MusicKind.Artist }
            .filterNot { it.id in hiddenFromDial }
            .distinctBy(Music::id)
            .toList()
    }
    // A deal keeps more than the dial shows, so a tile taken off it is replaced from the round's
    // own deal and not from what was just played.
    val livePicks = remember(livePool) { livePool.take(SPEED_DIAL_MAX_ITEMS) }
    // Dealt once a round (launch or pull to refresh) when the round's feed has arrived, and kept
    // so a song starting doesn't move the tiles; History is built as it goes.
    val dialRound = HomeDialRounds.round
    val dialKey = "$activeDialSource|${settings.homeDialLocalFiles}|${settings.homeArtistDialEnabled}"
    val feedFresh = youTubeHome.personalizedLoadedAt > HomeDialRounds.roundStartedAt
    val dealtDial = HomeDialRounds.speedDial
    // The dials the last launch dealt, shown at once until
    // this launch's are dealt, instead of a dial that changes as the history and the feed arrive.
    val savedSpeedDial = remember(model) { model.savedDial(SAVED_SPEED_DIAL) }
    val savedMixes = remember(model) { model.savedDial(SAVED_MIXES_DIAL) }
    val speedDialPicks = when {
        activeDialSource == AppSettings.DIAL_SOURCE_HISTORY -> livePicks
        dealtDial != null && dealtDial.key == dialKey -> dealtDial.items
        dealtDial == null && savedSpeedDial != null && savedSpeedDial.first == dialKey -> savedSpeedDial.second
        else -> livePicks
    }
    val speedDialEntries = remember(
        filteredHome, youTubeHome.shelves, pins, speedDialPicks, livePicks.takeIf { speedDialPicks.size < SPEED_DIAL_SIZE }, newReleaseIds, settings.homeArtistDialEnabled, hiddenFromDial,
    ) {
        if (filteredHome) {
            // A mood's dial is its songs; a mood with no songs keeps its playlists so the page isn't blank.
            val entries = categoryDialEntries(youTubeHome.shelves).distinctBy { it.item.id }
                .filterNot { it.item.id in hiddenFromDial }
            entries.filter { it.item.kind == MusicKind.Track }.ifEmpty { entries }.take(SPEED_DIAL_MAX_ITEMS)
        } else {
            // Pins first, whatever they are (artists' are on their own dial when it's on), then the dealt tiles.
            val dialPins = if (settings.homeArtistDialEnabled) pins.filterNot { it.kind == MusicKind.Artist } else pins
            // Only the round's deal: what was just played doesn't move its tiles. Only a deal
            // too short for the dial is topped up with the live picks.
            val picks = speedDialPicks.filterNot { item -> item.id in hiddenFromDial || pins.any { it.id == item.id } }
            val fill = if (dialPins.size + picks.size < SPEED_DIAL_SIZE) livePicks.filterNot { item -> pins.any { it.id == item.id } } else emptyList()
            (dialPins + picks + fill)
                .distinctBy(Music::id)
                .take(maxOf(SPEED_DIAL_SIZE, dialPins.size))
                .map { HomeSpeedDialEntry(it, isNewRelease = it.id in newReleaseIds) }
        }
    }
    // The artists' dial: the pins, the artists played most in the app, then the
    // account's, the library's and the feed's artists. The played-most are ranked once a round,
    // so a song starting doesn't move the tiles.
    val feedArtists = remember(personalizedHome) {
        personalizedHome.flatMap(YouTubeHomeShelf::items).filter { it.kind == MusicKind.Artist }
    }
    val playedMostArtists = remember(dialRound, settings.homeArtistDialEnabled, homeRecommendations.youtubeArtists, content?.artists, feedArtists) {
        if (!settings.homeArtistDialEnabled) emptyList()
        else ArtistDialFill.mostPlayed(
            model.listeningHistory.entries.value,
            homeRecommendations.youtubeArtists + content?.artists.orEmpty() + feedArtists,
        )
    }
    val artistDialEntries = remember(pins, playedMostArtists, homeRecommendations.youtubeArtists, content?.artists, feedArtists, settings.homeArtistDialOrder, homeDetails, hiddenFromDial) {
        val artistPins = pins.filter { it.kind == MusicKind.Artist }
        val artists = ArtistDialFill.tiles(
            pins = artistPins,
            mostPlayed = playedMostArtists,
            account = homeRecommendations.youtubeArtists,
            library = content?.artists.orEmpty(),
            feed = feedArtists,
            hidden = hiddenFromDial,
        ).map { HomeSpeedDialEntry(it) }
        val pinnedArtistIds = artistPins.mapTo(HashSet(), Music::id)
        (artists.filter { it.item.id in pinnedArtistIds } +
            orderDialEntries(artists.filterNot { it.item.id in pinnedArtistIds }, settings.homeArtistDialOrder))
            .map { entry ->
                val photo = homeDetails[entry.item.id]?.content?.item?.imageUrl
                if (entry.artworkUrl.isNullOrBlank() && photo != null) entry.copy(artworkUrl = photo) else entry
            }
    }
    val showArtistDial = !filteredHome && settings.homeSpeedDialEnabled && settings.homeArtistDialEnabled && artistDialEntries.isNotEmpty()
    var refreshRequested by remember { mutableStateOf(false) }
    val servicesBusy = library.loading || homeRecommendations.loading || youTubeHome.personalizedLoading ||
        youTubeHome.loadingCategories || youTubeHome.loading
    var displayedSpeedDialEntries by remember(model) { mutableStateOf(speedDialEntries) }
    val dealtMixes = HomeDialRounds.mixes
    // A mix taken off the round's dial leaves its place to the next mix at hand.
    val roundMixEntries = (dealtMixes?.items ?: savedMixes?.second?.takeIf { it.isNotEmpty() })
        ?.let { dealt -> (dealt.filterNot { it.id in hiddenFromDial }.map(::mixEntry) + mixEntries).distinctBy { it.item.id }.take(MixesDial.SIZE) }
        ?: mixEntries
    var displayedMixEntries by remember(model) { mutableStateOf(roundMixEntries) }
    val hasLuckyPick = settings.homeLuckyTileEnabled && (
        homeRecommendations.picks.any { it.type != HomeRecommendationType.LATEST_RELEASE } ||
            homeRecent.isNotEmpty() || pins.any { it.kind == MusicKind.Track }
        )
    var displayedLuckyTile by remember(model) { mutableStateOf(hasLuckyPick) }
    val roundReady = feedFresh && !refreshRequested && !servicesBusy
    // The round's speed dial, dealt once from the fresh feed.
    LaunchedEffect(dialRound, dialKey, roundReady) {
        val dealt = HomeDialRounds.speedDial
        if (roundReady && activeDialSource != AppSettings.DIAL_SOURCE_HISTORY &&
            (dealt == null || dealt.round != dialRound || dealt.key != dialKey)
        ) {
            // At launch the last launch's tiles stay where they were while they are still to be
            // had, and the fresh picks fill in after them; a pull to refresh deals all anew.
            val kept = savedSpeedDial?.takeIf { dealt == null && dialRound == 0 && it.first == dialKey }?.second
            val items = if (kept == null) livePicks else {
                val available = livePool.mapTo(HashSet(), Music::id)
                (kept.filter { it.id in available } + livePicks).distinctBy(Music::id).take(SPEED_DIAL_MAX_ITEMS)
            }
            HomeDialRounds.speedDial = DealtDial(dialRound, dialKey, items)
            model.saveDial(SAVED_SPEED_DIAL, dialKey, items)
        }
    }
    // The round's mixes, dealt once from the fresh feed and the further sources read for them.
    LaunchedEffect(dialRound, roundReady, settings.homeMixesDialEnabled) {
        if (!roundReady || !settings.homeMixesDialEnabled || HomeDialRounds.mixes?.round == dialRound) return@LaunchedEffect
        val sources = model.mixesDialSources(personalizedHome)
        val listened = recordedHistory.filter { it.track.genre.isNotBlank() }
            .groupBy { it.track.genre }
            .mapValues { (_, plays) -> plays.sumOf { it.track.durationMs.toLong() } }
        val dealt = MixesDial.deal(
            home = personalizedHome + sources.morePages,
            mixedForYou = sources.mixedForYou + homeRecommendations.youtubeMixes,
            stations = stationPicks.values.map(HomeRecommendationPick::item),
            decades = sources.decades,
            spotify = spotifyMixes,
            listened = listened,
        )
        if (dealt.isEmpty()) return@LaunchedEffect
        // At launch the last launch's mixes stay while they are still offered, as the speed dial's tiles do.
        val kept = savedMixes?.second?.takeIf { HomeDialRounds.mixes == null && dialRound == 0 }
        val items = if (kept == null) dealt else {
            val offered = (personalizedHome + sources.morePages).flatMap(YouTubeHomeShelf::items).mapTo(HashSet(), Music::id)
            offered += (dealt + sources.mixedForYou + sources.decades + homeRecommendations.youtubeMixes + spotifyMixes).map(Music::id)
            offered += stationPicks.keys
            (kept.filter { it.id in offered } + dealt).distinctBy(Music::id).take(MixesDial.SIZE)
        }
        HomeDialRounds.mixes = DealtDial(dialRound, "", items)
        model.saveDial(SAVED_MIXES_DIAL, "", items)
    }
    LaunchedEffect(refreshRequested, servicesBusy, speedDialEntries, roundMixEntries, hasLuckyPick) {
        if (!refreshRequested && !servicesBusy) {
            // Keep the current dial intact while all refresh sources load, then
            // commit one complete snapshot so its tiles can transition together,
            // with the new tiles' art already fetched.
            if (speedDialEntries != displayedSpeedDialEntries) prefetchDialArt(dialContext, speedDialEntries)
            if (roundMixEntries != displayedMixEntries) prefetchDialArt(dialContext, roundMixEntries)
            displayedSpeedDialEntries = speedDialEntries
            displayedMixEntries = roundMixEntries
            displayedLuckyTile = hasLuckyPick
        }
    }
    LaunchedEffect(refreshRequested, servicesBusy) {
        if (refreshRequested && !servicesBusy) {
            delay(180)
            refreshRequested = false
        }
    }
    val homeRequestBusy = refreshRequested || (filteredHome && youTubeHome.loading) ||
        (!filteredHome && personalizedHome.isEmpty() && youTubeHome.personalizedLoading)
    var completingLoad by remember { mutableStateOf(false) }
    LaunchedEffect(homeRequestBusy) {
        if (homeRequestBusy) completingLoad = true
        else {
            delay(180)
            completingLoad = false
        }
    }
    val showHomeSkeleton = homeRequestBusy || completingLoad
    val showSpeedDialSkeleton = showHomeSkeleton && displayedSpeedDialEntries.isEmpty()
    val pullState = rememberPullToRefreshState()
    val hasArtistPage = showArtistDial && !showHomeSkeleton
    val hasArtistPageState = rememberUpdatedState(hasArtistPage)
    val homeSpeedDialVisible = settings.homeSpeedDialEnabled &&
        (displayedSpeedDialEntries.isNotEmpty() || youTubeHome.chips.isNotEmpty() || showSpeedDialSkeleton)
    val hasHomeChips = youTubeHome.chips.isNotEmpty()
    val homeChipsIndexOffset = if (hasHomeChips) 1 else 0
    // First feed shelf, after the pinned chips, Speed Dial and the artist dial.
    val feedCategoriesIndex = homeChipsIndexOffset + (if (homeSpeedDialVisible) 1 else 0) + (if (hasArtistPage) 1 else 0)
    var homeChipsPx by remember { mutableIntStateOf(0) }
    val speedDialItemIds = remember(displayedSpeedDialEntries) { displayedSpeedDialEntries.mapTo(HashSet()) { it.item.id } }
    val overflowPins = remember(pins, speedDialItemIds, filteredHome) {
        if (filteredHome) emptyList() else pins.filterNot { it.id in speedDialItemIds }
    }
    val listState = rememberLazyListState()
    val homeScope = rememberCoroutineScope()
    val selectHomeCategory: (YouTubeHomeChip) -> Unit = { chip ->
        model.selectYouTubeHomeCategory(chip)
        homeScope.launch { listState.animateScrollToItem(0) }
    }
    val density = LocalDensity.current
    val scrollThresholdPx = remember(density) { with(density) { 180.dp.toPx() } }
    val isScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > scrollThresholdPx }
    }
    var requestedHomePage by remember(model) { mutableStateOf<String?>(null) }
    LaunchedEffect(youTubeHome.selected?.params) { requestedHomePage = null }
    LaunchedEffect(listState, youTubeHome.selected?.params, youTubeHome.continuation, youTubeHome.personalizedContinuation) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val token = if (youTubeHome.selected != null) youTubeHome.continuation else youTubeHome.personalizedContinuation
            token.takeIf {
                info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 4
            }
        }.collect { token ->
            if (token != null && token != requestedHomePage) {
                requestedHomePage = token
                if (youTubeHome.selected != null) model.loadMoreYouTubeHomeCategory() else model.loadMoreYouTubeHomeFeed()
            }
        }
    }
    var reordering by rememberSaveable { mutableStateOf(false) }
    var reorderingArtists by rememberSaveable { mutableStateOf(false) }
    val open: (Music) -> Unit = { navController.openMusic(ui, it) }
    var optionsFor by remember { mutableStateOf<Music?>(null) }
    var optionsFromDial by remember { mutableStateOf(false) }
    val showOptions: (Music) -> Unit = {
        // The long-click handler of the card already vibrates.
        optionsFromDial = false
        optionsFor = it
    }
    val showDialOptions: (Music) -> Unit = {
        optionsFromDial = true
        optionsFor = it
    }
    val openLibrary: () -> Unit = { navController.navigateToTopLevelSafely(Screen.Library.route) }

    val decay = rememberSplineBasedDecay<Float>()
    val evenFling = remember(decay) { EvenFling(decay) }
    // Every page settles just under the pinned chips, never behind them: the dials where they
    // sit on first open, the feed with a small gap so its first heading reads clearly.
    val homeFeedGapPx = remember(density) { with(density) { MeloLayout.Space2.roundToPx() } }
    // The mood chips scroll away with the list: the speed dial rests where it is with the list
    // at its top, under them; the artists' dial and the feed rest just under the title.
    val homeHeroRestPx = { if (hasHomeChips) homeChipsPx + listState.layoutInfo.mainAxisItemSpacing else 0 }
    val homeDialRestPx = { listState.layoutInfo.mainAxisItemSpacing }
    val homeFeedRestPx = { homeFeedGapPx }
    val dialRestFor: (Any) -> Int = { key -> if (key == "artist_dial") homeDialRestPx() else homeHeroRestPx() }
    // 0 with the list at its top, 1 once the chips have gone: the title shrinks into the bar.
    val headerFold by remember(hasHomeChips) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / homeChipsPx.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        }
    }
    val dialFling = remember(listState, decay) {
        DialPageFling(
            listState, decay,
            restOffsetPx = dialRestFor,
            nextOffsetPx = homeFeedRestPx,
            artistPageFollows = { hasArtistPageState.value },
        )
    }
    FrameBeatWhileScrolling(listState)
    // The large title names the page under the middle of the screen: Home over the speed dial,
    // Artists over the artist dial, For You once the feed has taken over.
    val homePage by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val under = info.visibleItemsInfo.firstOrNull { it.offset <= middle && it.offset + it.size > middle }?.key
            when {
                under == "artist_dial" -> HomeTitlePage.ARTISTS
                under == "speed_dial" || under == "home_feed_categories" -> HomeTitlePage.HOME
                listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 -> HomeTitlePage.HOME
                else -> HomeTitlePage.FOR_YOU
            }
        }
    }
    CompositionLocalProvider(
        LocalHomeGradients provides settings.homeGradients,
    ) {
    // The colour background behind Home is drawn by the app's root, behind every page.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                HomeGradientTopBar(
                    onSettingsClick = { navController.navigateSafely(Screen.Settings.route) },
                    homeDialSettingsContent = {
                        HomeDialSettingsMenuContent(
                            settings = settings,
                            spotifyConnected = discovery.spotifyEnabled,
                            onSettingsChange = { change -> ui.updateSettings { current -> change(current) } },
                        )
                    },
                    onReorderPinsClick = if (pins.size > 1) ({ reordering = true }) else null,
                    onHistoryClick = { navController.navigateSafely(Screen.History.route) },
                    profileImageUrl = youTubeAccount?.photoUrl,
                    profileName = youTubeAccount?.name,
                    profileHandle = youTubeAccount?.handle,
                    spotifyConnected = discovery.spotifyEnabled,
                    spotifyProfile = spotifyProfile,
                    onProfileClick = { navController.navigateSafely(Screen.SettingsCategory.createRoute("account")) },
                    isScrolled = isScrolled,
                    titleFold = { headerFold },
                    transparentContainer = true,
                    refreshing = showHomeSkeleton,
                    pullFraction = { pullState.distanceFraction },
                    animateLogo = !`in`.melo.presentation.utils.LocalReducedMotion.current,
                    markAsTitle = homePage == HomeTitlePage.HOME,
                    title = stringResource(
                        when (homePage) {
                            HomeTitlePage.HOME -> R.string.nav_bar_home
                            HomeTitlePage.ARTISTS -> R.string.home_dial_artists_title
                            HomeTitlePage.FOR_YOU -> R.string.home_page_for_you
                        }
                    ),
                )
            }
        ) { innerPadding ->
            val screenHeight = viewportHeight
            val dialPageHeight = (
                screenHeight - innerPadding.calculateTopPadding()
                ).coerceAtLeast(360.dp)
            // The mini player's room is kept whether or not a song is on, so
            // a song starting never reshapes the dial's tiles.
            val dialFooter = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 8.dp + 12.dp
            // Each dial is one full screen under the pinned chips: the grid takes the
            // room, the page ends at the bottom of the screen and the feed waits below it.
            val chipsRowHeight = if (hasHomeChips) {
                if (homeChipsPx > 0) with(density) { homeChipsPx.toDp() } else HOME_CHIPS_ROW_HEIGHT
            } else 0.dp
            val dialVisibleTop = chipsRowHeight + HOME_DIAL_TOP_GAP
            val heroPageHeight = (dialPageHeight - dialVisibleTop).coerceAtLeast(360.dp)
            val heroFooter = dialFooter
            val heroGridRoom = (heroPageHeight - heroFooter - HOME_DIAL_HERO_GRID_RESERVE).coerceAtLeast(180.dp)
            val artistPageHeight = (dialPageHeight - HOME_DIAL_TOP_GAP).coerceAtLeast(360.dp)
            val titledGridRoom = (artistPageHeight - dialFooter - HOME_DIAL_HERO_GRID_RESERVE - 64.dp).coerceAtLeast(180.dp)
            Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = refreshRequested,
                state = pullState,
                indicator = {},
                onRefresh = {
                    refreshRequested = true
                    HomeDialRounds.nextRound(reshuffle = activeDialSource == AppSettings.DIAL_SOURCE_SPOTIFY)
                    model.refreshHome()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    flingBehavior = if (homeSpeedDialVisible && !`in`.melo.presentation.utils.LocalReducedMotion.current) dialFling else evenFling,
                    modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
                    contentPadding = PaddingValues(
                        top = 0.dp,
                        bottom = paddingValues.calculateBottomPadding() + 38.dp + MiniPlayerHeight
                    ),
                    verticalArrangement = Arrangement.spacedBy(MeloLayout.SectionGap)
                ) {
                    // The one row of mood chips, under the title: it scrolls away with Speed Dial
                    // so the pages below get the whole screen.
                    if (hasHomeChips) {
                        item(key = "home_feed_categories") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    // Measured with its padding: the whole row Speed Dial rests under.
                                    .onSizeChanged { homeChipsPx = it.height }
                                    .padding(vertical = MeloLayout.Space2),
                            ) {
                                HomeCategoryChips(youTubeHome.chips, youTubeHome.selected, selectHomeCategory)
                            }
                        }
                    }
                    if (homeSpeedDialVisible) {
                        item(key = "speed_dial", contentType = "speed_dial") {
                            // Keyed by the Home tab's entries too, so the pager starts afresh on
                            // Speed Dial each time Home is opened from another tab.
                            key(youTubeHome.selected?.params, HomeReselect.entries) {
                                HomeDialPager(
                                    mixesAvailable = !filteredHome && settings.homeMixesDialEnabled,
                                    speedDial = {
                                        HomeSpeedDial(
                                            entries = displayedSpeedDialEntries,
                                            pinnedIds = if (filteredHome) emptySet() else pins.mapTo(HashSet(), Music::id),
                                            categoryChips = emptyList(),
                                            selectedCategory = null,
                                            onCategoryClick = {},
                                            onItemClick = { entry ->
                                                val pick = entry.recommendation
                                                when {
                                                    pick != null && pick.type == HomeRecommendationType.LATEST_RELEASE -> open(pick.item)
                                                    pick != null -> model.playHomeRecommendation(pick)
                                                    entry.item.kind == MusicKind.Track -> ui.playTrack(entry.item)
                                                    else -> open(entry.item)
                                                }
                                            },
                                            onItemLongPress = { entry -> showDialOptions(entry.item) },
                                            hasLuckyTile = !filteredHome && displayedLuckyTile,
                                            onLuckyClick = {
                                                val picks = homeRecommendations.picks.filter { it.type != HomeRecommendationType.LATEST_RELEASE }
                                                val pick = picks.randomOrNull()
                                                if (pick != null) {
                                                    model.playHomeRecommendation(pick)
                                                } else {
                                                    (homeRecent + pins.filter { it.kind == MusicKind.Track })
                                                        .distinctBy(Music::id)
                                                        .randomOrNull()
                                                        ?.let(ui::playTrack)
                                                    null
                                                }
                                            },
                                            onHistoryClick = { navController.navigateSafely(Screen.History.route) },
                                            onReorderPins = null,
                                            onCustomize = null,
                                            // The pinned chips above already give the grid its top margin.
                                            showHeading = !hasHomeChips,
                                            sharedPagerHeader = false,
                                            maxPages = SPEED_DIAL_MAX_PAGES,
                                            loading = showSpeedDialSkeleton,
                                            rows = settings.homeDialRows,
                                            pageHeight = heroPageHeight,
                                            footerReserve = heroFooter,
                                            maxGridHeight = heroGridRoom,
                                            showLabels = settings.homeDialLabels,
                                            showPinIcon = settings.homeDialPinIcon,
                                            tileStyle = settings.homeDialTileStyle,
                                            showNewReleaseTags = settings.homeDialNewReleaseTags,
                                            showHistoryTile = !filteredHome,
                                            onScrollToFeed = {
                                                val nextPage = if (hasArtistPage) homeChipsIndexOffset + 1 else feedCategoriesIndex
                                                homeScope.launch { listState.animateScrollToItem(nextPage, -(if (hasArtistPage) homeDialRestPx() else homeFeedRestPx())) }
                                            },
                                        )
                                    },
                                    mixesDial = {
                                        HomeSpeedDial(
                                            entries = displayedMixEntries,
                                            pinnedIds = emptySet(),
                                            categoryChips = emptyList(),
                                            selectedCategory = null,
                                            onCategoryClick = {},
                                            onItemClick = { entry ->
                                                entry.recommendation?.let(model::playHomeRecommendation) ?: open(entry.item)
                                            },
                                            onItemLongPress = { entry -> showDialOptions(entry.item) },
                                            hasLuckyTile = false,
                                            onLuckyClick = { null },
                                            onHistoryClick = {},
                                            onReorderPins = null,
                                            title = stringResource(R.string.home_dial_mixes_title),
                                            showHeading = false,
                                            sharedPagerHeader = false,
                                            maxPages = 1,
                                            loading = showHomeSkeleton && displayedMixEntries.isEmpty(),
                                            rows = settings.homeDialRows,
                                            pageHeight = heroPageHeight,
                                            footerReserve = heroFooter,
                                            maxGridHeight = heroGridRoom,
                                            showLabels = settings.homeDialLabels,
                                            showPinIcon = false,
                                            tileStyle = settings.homeDialTileStyle,
                                            showNewReleaseTags = settings.homeDialNewReleaseTags,
                                            showHistoryTile = false,
                                            showMixBadges = true,
                                            onScrollToFeed = {
                                                val nextPage = if (hasArtistPage) homeChipsIndexOffset + 1 else feedCategoriesIndex
                                                homeScope.launch { listState.animateScrollToItem(nextPage, -(if (hasArtistPage) homeDialRestPx() else homeFeedRestPx())) }
                                            },
                                        )
                                    },
                                    modifier = Modifier.pullUnderChips(HOME_DIAL_PULL_UP).then(Modifier.tourAnchor(TourTarget.SPEED_DIAL)),
                                )
                            }
                        }
                    }
                    if (hasArtistPage) {
                        item(key = "artist_dial", contentType = "artist_dial") {
                            HomeSpeedDial(
                                entries = artistDialEntries,
                                pinnedIds = pins.filter { it.kind == MusicKind.Artist }.mapTo(HashSet(), Music::id),
                                categoryChips = emptyList(),
                                selectedCategory = null,
                                onCategoryClick = {},
                                onItemClick = { entry -> open(entry.item) },
                                onItemLongPress = { entry -> showDialOptions(entry.item) },
                                hasLuckyTile = false,
                                onLuckyClick = { null },
                                onHistoryClick = {},
                                onReorderPins = if (artistDialEntries.size > 1) ({ reorderingArtists = true }) else null,
                                title = stringResource(R.string.home_dial_artists_title),
                                showTitleText = false,
                                rows = settings.homeDialRows,
                                pageHeight = artistPageHeight,
                                footerReserve = dialFooter,
                                maxGridHeight = titledGridRoom,
                                showLabels = settings.homeDialLabels,
                                showPinIcon = settings.homeDialPinIcon,
                                tileStyle = settings.homeDialTileStyle,
                                showNewReleaseTags = false,
                                showHistoryTile = false,
                                onMissingArtwork = { artist -> model.loadDetail(artist) },
                                onScrollToFeed = {
                                    homeScope.launch { listState.animateScrollToItem(feedCategoriesIndex, -homeFeedRestPx()) }
                                },
                                modifier = Modifier.pullUnderChips(HOME_DIAL_PULL_UP),
                            )
                        }
                    }

                    if (showHomeSkeleton) {
                        item(key = "home_feed_skeleton") { HomeFeedSkeleton() }
                    } else if (!filteredHome) {

                    if (homeRecommendations.picks.isNotEmpty() || recentFeed.isNotEmpty() || HomeMood.coreMoods.isNotEmpty()) {
                        item(key = "for_you_discovery", contentType = "for_you_discovery") {
                            ForYouShelf(
                                picks = homeRecommendations.picks,
                                moods = HomeMood.coreMoods,
                                recent = recentFeed,
                                onPickClick = { pick ->
                                    if (pick.type == HomeRecommendationType.LATEST_RELEASE) open(pick.item)
                                    else model.playHomeRecommendation(pick)
                                },
                                onLongPress = showOptions,
                                onMoodClick = model::playHomeMood,
                                onShowAllMoods = { navController.navigateToTopLevelSafely(Screen.Explore.route) },
                                onRecentClick = { selected ->
                                    ui.playSongs(homeRecent.map { it.toSong() }, selected.toSong(), recentTitle)
                                },
                                onRecentLongPress = showOptions,
                                onOpenHistory = { navController.navigateSafely(Screen.History.route) },
                            )
                        }
                    }
                    if (youTubeHome.personalizedLoading && personalizedHome.isEmpty()) {
                        item(key = "youtube_personalized_loading") { ListeningShelfPlaceholder() }
                    }
                    if (youTubeHome.personalizedError != null && personalizedHome.isEmpty()) {
                        item(key = "youtube_personalized_error") {
                            ListeningStatusCard(
                                title = stringResource(R.string.home_personalized_feed_title),
                                message = youTubeHome.personalizedError.orEmpty(),
                                action = stringResource(R.string.melo_retry),
                                onAction = { model.refreshYouTubeHomeCategories(force = true) },
                                modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
                            )
                        }
                    }
                    personalizedHome.forEachIndexed { index, shelf ->
                        val style = if (shelf.items.all { it.kind == MusicKind.Track }) ShelfStyle.LIST else ShelfStyle.CAROUSEL
                        homeShelf(
                            key = "youtube_personalized_home_$index",
                            title = shelf.title,
                            items = shelf.items.take(24),
                            style = style,
                            ui = ui,
                            onClick = { selected ->
                                if (selected.kind == MusicKind.Track) model.loadRadio(selected, open) else open(selected)
                            },
                            onLongPress = showOptions,
                            openAll = null,
                        )
                    }
                    if (youTubeHome.personalizedLoadingMore) {
                        item(key = "youtube_personalized_loading_more") { ListeningShelfPlaceholder() }
                    }
                    if (content == null && library.loading) {
                        item(key = "library_loading") {
                            ListeningShelfPlaceholder()
                        }
                    }
                    library.error?.let { message ->
                        item(key = "library_error") {
                            ListeningStatusCard(
                                title = stringResource(R.string.melo_library_error_title),
                                message = message,
                                action = stringResource(R.string.melo_retry),
                                onAction = model::refreshLibrary,
                                modifier = Modifier.padding(horizontal = MeloLayout.PageGutter)
                            )
                        }
                    }
                    val hasListeningContent = pins.isNotEmpty() || recent.isNotEmpty() || localFiles.isNotEmpty() ||
                        localPlaylists.isNotEmpty() || youTubePlaylists.isNotEmpty() || downloads.isNotEmpty() ||
                        content?.let { it.savedTrackCount > 0 || it.playlists.isNotEmpty() || it.albums.isNotEmpty() || it.artists.isNotEmpty() } == true
                    if (!hasListeningContent && !library.loading && library.error == null) {
                        item(key = "empty_home") {
                            ListeningStatusCard(
                                stringResource(R.string.melo_empty_home_title),
                                stringResource(R.string.melo_empty_home_body),
                                stringResource(R.string.melo_discover),
                                { navController.navigateToTopLevelSafely(Screen.Search.route) },
                                Modifier.padding(horizontal = MeloLayout.PageGutter)
                            )
                        }
                    }
                    // Shelves in the order, layout and size chosen in Settings → Home.
                    settings.homeLayout.filter { it.visible && it.shelf != HomeShelf.RECENT }.forEach { config ->
                        val multi = localPlaylists.map { it.asMusic().copy(subtitle = localBadge + " · " + it.asMusic().subtitle) }
                        val openTab: (`in`.melo.data.model.LibraryTabId) -> () -> Unit = { tab -> { ui.requestedLibraryTab.value = tab.storageKey; openLibrary() } }
                        val title = when (config.shelf) {
                            HomeShelf.DOWNLOADS -> downloadsTitle
                            HomeShelf.PLAYLISTS -> playlistsTitle
                            HomeShelf.ALBUMS -> albumsTitle
                            HomeShelf.ARTISTS -> artistsTitle
                            else -> shelfTitles[config.shelf].orEmpty()
                        }
                        val (items, openAll) = when (config.shelf) {
                            HomeShelf.PINS -> overflowPins to null
                            HomeShelf.RECENT -> recent to null
                            HomeShelf.DOWNLOADS -> downloads.values.filter { it.isComplete }.map { it.collection.item } to openTab(`in`.melo.data.model.LibraryTabId.DOWNLOADS)
                            HomeShelf.LIKED -> emptyList<Music>() to null
                            HomeShelf.PLAYLISTS -> (multi + youTubePlaylists + content?.playlists.orEmpty()) to openTab(`in`.melo.data.model.LibraryTabId.PLAYLISTS)
                            HomeShelf.MULTI_SERVICE -> multi to openTab(`in`.melo.data.model.LibraryTabId.PLAYLISTS)
                            HomeShelf.SPOTIFY_PLAYLISTS -> content?.playlists.orEmpty() to openTab(`in`.melo.data.model.LibraryTabId.PLAYLISTS)
                            HomeShelf.YOUTUBE_PLAYLISTS -> youTubePlaylists to openTab(`in`.melo.data.model.LibraryTabId.PLAYLISTS)
                            HomeShelf.ALBUMS -> content?.albums.orEmpty() to openTab(`in`.melo.data.model.LibraryTabId.ALBUMS)
                            HomeShelf.ARTISTS -> content?.artists.orEmpty() to openTab(`in`.melo.data.model.LibraryTabId.ARTISTS)
                            HomeShelf.LOCAL_FILES -> localFiles to openTab(`in`.melo.data.model.LibraryTabId.LOCAL)
                        }
                        when {
                            config.shelf == HomeShelf.LIKED -> if (content != null && content.savedTrackCount > 0) item(key = "liked", contentType = "liked") {
                                LikedSongsCard(
                                    count = content.savedTrackCount,
                                    artworks = content.savedTracks.mapNotNull(Music::imageUrl).distinct().take(3),
                                    onOpen = { open(ui.likedSongs) },
                                    onLongPress = { showOptions(ui.likedSongs) },
                                    onPlay = {
                                        // The count is known before the tracks: without them, the list itself is opened.
                                        val first = content.savedTracks.firstOrNull()
                                        if (first == null) open(ui.likedSongs)
                                        else ui.playSongs(content.savedTracks.map { it.toSong() }, first.toSong(), ui.likedSongs.title)
                                    },
                                )
                            }
                            config.shelf == HomeShelf.PINS && config.style == ShelfStyle.COLLAGE -> if (overflowPins.isNotEmpty()) {
                                item(key = "pins_collage", contentType = "pins_collage") {
                                    PinsCollage(overflowPins.take(config.count), settings.collagePattern, settings.collageAutoRotate, ui, { open(it) }, { showOptions(it) }, { reordering = true })
                                }
                            }
                            else -> homeShelf(config.shelf.key, title.takeIf { config.showTitle }, items.take(config.count), config.style, ui,
                                onClick = { item ->
                                    val tracks = items.filter { it.kind == MusicKind.Track }
                                    if (item.kind == MusicKind.Track && tracks.size > 1) ui.playSongs(tracks.map { it.toSong() }, item.toSong(), title)
                                    else if (item.kind == MusicKind.Track) ui.playItem(item) else open(item)
                                },
                                onLongPress = showOptions, openAll = openAll)
                        }
                    }
                    } else {
                        if (youTubeHome.loading && youTubeHome.shelves.isEmpty()) {
                            item(key = "home_category_loading") { ListeningShelfPlaceholder() }
                        }
                        youTubeHome.error?.let { message ->
                            item(key = "home_category_error") {
                                ListeningStatusCard(
                                    title = youTubeHome.selected?.title.orEmpty(),
                                    message = message,
                                    action = stringResource(R.string.melo_retry),
                                    onAction = { model.refreshYouTubeHomeCategories(force = true) },
                                    modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
                                )
                            }
                        }
                        if (!youTubeHome.loading && youTubeHome.error == null && youTubeHome.shelves.isEmpty()) {
                            item(key = "home_category_empty") {
                                ListeningStatusCard(
                                    title = youTubeHome.selected?.title.orEmpty(),
                                    message = stringResource(R.string.home_category_empty),
                                    action = stringResource(R.string.melo_retry),
                                    onAction = { model.refreshYouTubeHomeCategories(force = true) },
                                    modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
                                )
                            }
                        }
                        youTubeHome.shelves.forEachIndexed { index, shelf ->
                            val style = if (shelf.items.all { it.kind == MusicKind.Track }) ShelfStyle.LIST else ShelfStyle.CAROUSEL
                            homeShelf(
                                key = "youtube_home_${youTubeHome.selected?.params}_${index}",
                                title = shelf.title,
                                items = shelf.items.take(20),
                                style = style,
                                ui = ui,
                                onClick = { selected ->
                                    if (selected.kind == MusicKind.Track) model.loadRadio(selected, open) else open(selected)
                                },
                                onLongPress = showOptions,
                                openAll = null,
                            )
                        }
                        if (youTubeHome.loadingMore) {
                            item(key = "home_category_loading_more") { ListeningShelfPlaceholder() }
                        }
                    }
                }
            }
            }
        }
        if (!`in`.melo.ui.glass.pagesUnderGlass()) Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(resolveMainScreenBottomGradientHeight(navBarCompactMode))
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.2f to Color.Transparent,
                            0.8f to MaterialTheme.colorScheme.surfaceContainerLowest,
                            1.0f to MaterialTheme.colorScheme.surfaceContainerLowest
                        )
                    )
                )
        )
    }

    }

    if (reordering) PinsReorderSheet(pins, ui, model, onDismiss = { reordering = false })
    if (reorderingArtists) HomeDialSettingsSheet(
        title = stringResource(R.string.home_dial_artists_title),
        items = artistDialEntries.map(HomeSpeedDialEntry::item),
        order = settings.homeArtistDialOrder,
        onOrderChange = { order -> ui.updateSettings { it.copy(homeArtistDialOrder = order) } },
        onDismiss = { reorderingArtists = false },
        pinnedIds = pins.filter { it.kind == MusicKind.Artist }.mapTo(HashSet(), Music::id),
        onTogglePin = model::togglePin,
    )
    MusicOptionsHost(
        ui, navController, optionsFor,
        onRemoveFromDial = optionsFor?.takeIf { optionsFromDial }?.let { item -> { model.removeFromDial(item) } },
    ) { optionsFor = null }
}

@Composable
private fun PlayerViewModel.stablePlayerStateHasSong() = remember(this) {
    derivedHasSong
}.collectAsStateWithLifecycle()

private fun androidx.compose.foundation.lazy.LazyListScope.homeShelf(
    key: String,
    title: String?,
    items: List<Music>,
    style: ShelfStyle,
    ui: PlayerViewModel,
    onClick: (Music) -> Unit,
    onLongPress: (Music) -> Unit,
    openAll: (() -> Unit)?,
) {
    if (items.isEmpty()) return
    item(key = key, contentType = "shelf_${style.key}") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) SectionHeader(title, openAll)
            ShelfItems(items, style, ui, onClick, onLongPress) { item, modifier ->
                PinnedPill(item, ui, modifier, { onClick(item) }, { onLongPress(item) })
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, onOpen: (() -> Unit)?) {
    `in`.melo.presentation.components.MeloSectionHeader(title, onOpen = onOpen)
}

@Composable
private fun PinsCollage(
    pins: List<Music>,
    patternKey: String,
    autoRotate: Boolean,
    ui: PlayerViewModel,
    open: (Music) -> Unit,
    showOptions: (Music) -> Unit,
    onReorder: () -> Unit,
) {
    val songs = remember(pins) { pins.take(COLLAGE_ITEMS).map { it.toSong() }.toImmutableList() }
    val pattern = remember(patternKey, autoRotate) {
        if (autoRotate) CollagePattern.entries.random() else CollagePattern.fromStorageKey(patternKey)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // A collage only shows artwork: it needs enough elements to read as one.
        if (songs.size >= COLLAGE_MINIMUM) AlbumArtCollage(
            modifier = Modifier.fillMaxWidth(),
            songs = songs,
            padding = 14.dp,
            height = 400.dp,
            pattern = pattern,
            onSongClick = { song -> pins.firstOrNull { it.id == song.id }?.let(open) },
        )
        pins.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = MeloLayout.PageGutter),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { item ->
                    PinnedPill(item, ui, Modifier.weight(1f), { open(item) }, { showOptions(item) })
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (pins.size > 1) Box(Modifier.fillMaxWidth().padding(horizontal = MeloLayout.PageGutter), contentAlignment = Alignment.CenterEnd) {
            FilledIconButton(
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.controlFill,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                onClick = onReorder,
            ) {
                Icon(MeloIcons.SwapVert, contentDescription = stringResource(R.string.pins_reorder))
            }
        }
    }
}

private val PinPillHeight = 64.dp
private val PinPillArtSize = 52.dp

/** The recently played pill, tinted from the artwork. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedPill(
    item: Music,
    ui: PlayerViewModel,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val container = `in`.melo.presentation.components.pageCardColor(MaterialTheme.colorScheme.surfaceContainer)
    val title = MaterialTheme.colorScheme.onSurface
    val shape = MeloLayout.CardShape
    val artPadding = (PinPillHeight - PinPillArtSize) / 2
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .height(PinPillHeight)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = artPadding, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MeloLayout.Space3)
        ) {
            if (item.id == ui.likedSongs.id) {
                Box(
                    Modifier.size(PinPillArtSize).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MeloIcons.Favorite, null, tint = MaterialTheme.colorScheme.onPrimary)
                }
            } else {
                SmartImage(
                    model = item.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    shape = CircleShape,
                    targetSize = SmartImageCompactListTargetSize,
                    modifier = Modifier.size(PinPillArtSize)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = title
                )
                Text(
                    text = item.subtitle.ifBlank { stringResource(item.kind.labelRes) },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = title.copy(alpha = MeloAlpha.Secondary)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LikedSongsCard(
    count: Int,
    artworks: List<String>,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onPlay: () -> Unit,
) {
    val shape = MeloLayout.CardShape
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MeloLayout.PageGutter)
            .clip(shape)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = shape,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(MeloLayout.ArtworkShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                if (artworks.isNotEmpty()) SmartImage(
                    model = artworks.first(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = MeloAlpha.Tertiary)))
                Icon(MeloIcons.Favorite, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f).padding(horizontal = MeloLayout.Space4)) {
                Text(stringResource(R.string.liked_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    formatSongCount(count),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            FilledIconButton(
                onClick = onPlay,
                modifier = Modifier.size(MeloLayout.ControlSize),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    contentColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Icon(MeloIcons.PlayArrow, stringResource(R.string.liked_play), Modifier.size(30.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinsReorderSheet(pins: List<Music>, ui: PlayerViewModel, model: MeloModel, onDismiss: () -> Unit) {
    `in`.melo.presentation.components.MeloBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            stringResource(R.string.reorder),
            modifier = Modifier.padding(horizontal = MeloLayout.PageGutter, vertical = 8.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        ReorderableColumn(
            list = pins,
            onSettle = { from, to -> model.movePin(from, to) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(MeloLayout.Space2),
        ) { _, item, _ ->
            key(item.id) {
            ReorderableItem {
                Surface(
                    color = `in`.melo.presentation.components.pageCardColor(MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = MeloLayout.PanelShape,
                ) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmartImage(
                            model = item.imageUrl,
                            contentDescription = null,
                            shape = MeloLayout.CardShape,
                            contentScale = ContentScale.Crop,
                            targetSize = SmartImageCompactListTargetSize,
                            modifier = Modifier.size(44.dp),
                        )
                        Text(
                            item.title,
                            Modifier.weight(1f).padding(horizontal = 12.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { ui.togglePin(item) }) {
                            Icon(MeloIcons.PushPin, stringResource(R.string.pin_remove))
                        }
                        IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                            Icon(MeloIcons.DragHandle, stringResource(R.string.reorder))
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * Draws a dial [by] higher than the list's section gap would put it, so the grid sits close
 * under the pinned chips while the rest of the feed keeps its wider gaps.
 */
private fun Modifier.pullUnderChips(by: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = by.roundToPx().coerceAtMost(placeable.height)
    layout(placeable.width, placeable.height - shift) { placeable.place(0, -shift) }
}

/** The names Home's dials are saved under for the next launch. */
private const val SAVED_SPEED_DIAL = "speed"
private const val SAVED_MIXES_DIAL = "mixes"

/** Which page of Home the large title names. */
private enum class HomeTitlePage { HOME, ARTISTS, FOR_YOU }
