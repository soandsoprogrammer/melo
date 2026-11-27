package `in`.melo.data.preferences

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** Every user-facing preference of the app. Never holds account material. */
data class AppSettings(
    val themeMode: String = THEME_DARK,
    val language: String = LANGUAGE_SYSTEM,
    /** Turns off the blur behind the player, lyrics and pages, for older or slower phones. */
    val disableBlur: Boolean = false,
    /** Soft colour from the playing song drifting behind the main pages. */
    val ambientBackground: Boolean = true,
    val showScrollbar: Boolean = true,
    val playerTheme: String = ThemePreference.GLOBAL,
    val playerArtworkGradient: Boolean = true,
    /** Album, playlist and artist pages are painted in their cover's colours. */
    val detailPageTint: Boolean = true,
    /** The portrait player runs the cover to its edges, dissolving into the backdrop, instead of a square card. */
    val fullScreenCoverArt: Boolean = true,
    /** The next cover dissolves in over the last when the track changes, instead of sliding in. */
    val albumCoverMix: Boolean = true,
    /** Album and playlist pages show the whole cover as a full-width square instead of a cropped banner. */
    val fullScreenDetailCover: Boolean = false,
    /** Plays a queued track from the first add-on holding the same recording, as an alternative source. */
    val directSourceStream: Boolean = false,
    /** How far, in seconds, an add-on copy's length may differ from the queued track's and still count as it. */
    val addonLengthToleranceSec: Int = 5,
    /** While a track plays the music server's copy, looks for a lossless add-on copy and switches to it in place. */
    val losslessUpgrade: Boolean = true,
    /** How the cover's colours are drawn behind the player: [BACKDROP_BLEND] or [BACKDROP_MESH]. */
    val playerBackdropStyle: String = BACKDROP_BLEND,
    /** The player's position bar: [PROGRESS_PLAIN], [PROGRESS_GLOW] or [PROGRESS_WAVE]. */
    val playerProgressStyle: String = PROGRESS_PLAIN,
    val palette: String = AlbumArtPaletteStyle.default.storageKey,
    val carouselStyle: String = CarouselStyle.NO_PEEK,
    val showPlayerFileInfo: Boolean = true,
    val showQualityInLists: Boolean = true,
    val collagePattern: String = CollagePattern.default.storageKey,
    val collageAutoRotate: Boolean = false,
    val navBarStyle: String = NavBarStyle.FULL_WIDTH,
    val navBarCompact: Boolean = false,
    val immersiveLyrics: Boolean = true,
    val immersiveLyricsTimeoutMs: Long = 4000L,
    /** The third-party lyrics services left on, by key, comma-separated. See [lyricsProviderList]. */
    val lyricsProviders: String = `in`.melo.data.repository.LyricsProvider.entries.joinToString(",") { it.key },
    val defaultTab: String = TAB_HOME,
    val keepPlayingInBackground: Boolean = true,
    val pauseOnVolumeZero: Boolean = false,
    val resumeOnHeadphones: Boolean = false,
    val persistentShuffle: Boolean = false,
    val showQueueHistory: Boolean = true,
    val audioCacheLimitMb: Int = 4096,
    val preloadTracks: Int = 2,
    val preloadOnMobile: Boolean = false,
    val cachePlayedTracks: Boolean = true,
    val preloadUpcomingTracks: Boolean = true,
    val offlineMode: Boolean = false,
    val tapBackgroundClosesPlayer: Boolean = false,
    val hapticsEnabled: Boolean = true,
    /** Source badge on each track of a multi-service playlist. */
    val showSourceBadges: Boolean = true,
    /** Smaller rows and spacing in track and library lists. */
    val compactLists: Boolean = false,
    /** Home shelves: order, visibility, layout and size, as [HomeLayout] encodes them. Empty: defaults. */
    val homeShelves: String = "",
    /** Source after pins on the Home Speed Dial: the music server, Spotify, or combined listening history. */
    val homeDialSource: String = DIAL_SOURCE_SERVER,
    /** Visible tile rows per Speed Dial page. */
    val homeDialRows: Int = 4,
    /** Whether the home Speed Dial is shown. */
    val homeSpeedDialEnabled: Boolean = true,
    /** Whether the Mixes page sits beside Speed Dial. */
    val homeMixesDialEnabled: Boolean = true,
    /** Whether songs from the phone's own files fill Speed Dial tiles. Pinned ones always show. */
    val homeDialLocalFiles: Boolean = false,
    /** Whether a separate Artist Dial page is shown below Speed Dial. */
    val homeArtistDialEnabled: Boolean = true,
    /** Whether the randomized radio tile appears on Speed Dial. */
    val homeLuckyTileEnabled: Boolean = true,
    val homeDialLabels: Boolean = true,
    val homeGradients: Boolean = true,
    /** Whether pinned dial tiles show their pin badge. */
    val homeDialPinIcon: Boolean = true,
    /** Artwork rim style: plain, edge, or glass. */
    val homeDialTileStyle: String = DIAL_TILE_STYLE_GLASS,
    val homeDialNewReleaseTags: Boolean = true,
    /** User order for Artist Dial cards, stored as a JSON string array. */
    val homeArtistDialOrder: String = "",
    /** Album and playlist page buttons: order and which ones are in the bar, as [DetailActions] encodes them. */
    val albumActions: String = "",
    val playlistActions: String = "",
    /** What swiping a track row does, as [`in`.melo.presentation.components.TrackSwipeAction] keys. */
    val swipeRight: String = "queue",
    val swipeLeft: String = "none",
    /** [`in`.melo.data.AudioQuality] keys for streaming on Wi-Fi, on mobile data, and for downloads. */
    val streamingQualityWifi: String = "lossless",
    val streamingQualityMobile: String = "high",
    val downloadQuality: String = "very_high",
    /** Loudness normalization of Spotify and the music server tracks from the loudness each service publishes. */
    val audioNormalization: Boolean = false,
    /** The ⋮ button at the top right of the full player. */
    val showPlayerMenuButton: Boolean = true,
    /** Horizontal swipe on the mini player: [MINI_SWIPE_TRACKS], [MINI_SWIPE_DISMISS] or [MINI_SWIPE_OFF]. */
    val miniPlayerSwipe: String = MINI_SWIPE_TRACKS,
    /** The Spotify / Music Server / Device choice above search results. Hidden: every search reads all sources. */
    val showSearchSources: Boolean = false,
    /** Firebase usage analytics; off until the user agrees on the first-run screen, and changeable in Privacy settings. */
    val analyticsEnabled: Boolean = false,
    /** Bars, menus and cards: "glass", "frost" or "solid". See [`in`.melo.ui.glass.MeloSurfaceStyle]. */
    val surfaceStyle: String = "glass",
    /** Glass options: transparency 0..1, blur in dp, saturation. */
    val glassTransparency: Float = 0.9f,
    val glassBlur: Float = 10f,
    val glassSaturation: Float = 0.9f,
    /**
     * The colour washed into the glass: a [`in`.melo.ui.glass.MeloGlassTint]
     * key. The playing song's by default, so the bars take the song's colour.
     */
    val glassTint: String = "song",
    /** How far the glass is darkened under the tint, 0..1. */
    val glassTintDarkness: Float = 0.3f,
    /** Discord rich presence. The account itself lives in [`in`.melo.data.discord.DiscordAccountStore]. */
    val discordRpcEnabled: Boolean = true,
    /** Puts the song title on the bold line of the card instead of the artist. */
    val discordUseDetails: Boolean = false,
    val discordShowAudioQuality: Boolean = true,
    /** Shows the status, activity and button rows. */
    val discordAdvancedMode: Boolean = false,
    /** "online", "idle" or "dnd". */
    val discordStatus: String = "online",
    /** "listening", "playing", "watching" or "competing". */
    val discordActivityType: String = "listening",
    /** Empty: the app's name. */
    val discordActivityName: String = "",
    val discordButton1Text: String = "",
    val discordButton1Visible: Boolean = true,
    val discordButton2Text: String = "",
    val discordButton2Visible: Boolean = true,
    val discordInfoDismissed: Boolean = false,
) {
    fun actions(page: DetailPage): List<ActionSlot> =
        DetailActions.decode(page, if (page == DetailPage.ALBUM) albumActions else playlistActions)
    val homeLayout: List<ShelfConfig> get() = HomeLayout.decode(homeShelves)
    fun toJson(): JSONObject = JSONObject()
        .put("themeMode", themeMode).put("language", language)
        .put("disableBlur", disableBlur).put("ambientBackground", ambientBackground).put("showScrollbar", showScrollbar).put("playerTheme", playerTheme)
        .put("playerArtworkGradient", playerArtworkGradient)
        .put("detailPageTint", detailPageTint)
        .put("fullScreenCoverArt", fullScreenCoverArt).put("albumCoverMix", albumCoverMix).put("fullScreenDetailCover", fullScreenDetailCover)
        .put("directSourceStream", directSourceStream).put("addonLengthToleranceSec", addonLengthToleranceSec)
        .put("losslessUpgrade", losslessUpgrade)
        .put("playerBackdropStyle", playerBackdropStyle).put("playerProgressStyle", playerProgressStyle)
        .put("palette", palette).put("carouselStyle", carouselStyle).put("showPlayerFileInfo", showPlayerFileInfo)
        .put("showQualityInLists", showQualityInLists).put("collagePattern", collagePattern)
        .put("collageAutoRotate", collageAutoRotate).put("navBarStyle", navBarStyle).put("navBarCompact", navBarCompact)
        .put("immersiveLyrics", immersiveLyrics)
        .put("immersiveLyricsTimeoutMs", immersiveLyricsTimeoutMs).put("lyricsProviders", lyricsProviders)
        .put("defaultTab", defaultTab).put("keepPlayingInBackground", keepPlayingInBackground)
        .put("pauseOnVolumeZero", pauseOnVolumeZero).put("resumeOnHeadphones", resumeOnHeadphones)
        .put("persistentShuffle", persistentShuffle).put("showQueueHistory", showQueueHistory)
        .put("audioCacheLimitMb", audioCacheLimitMb).put("preloadTracks", preloadTracks)
        .put("preloadOnMobile", preloadOnMobile)
        .put("cachePlayedTracks", cachePlayedTracks).put("preloadUpcomingTracks", preloadUpcomingTracks)
        .put("offlineMode", offlineMode)
        .put("tapBackgroundClosesPlayer", tapBackgroundClosesPlayer).put("hapticsEnabled", hapticsEnabled)
        .put("showSourceBadges", showSourceBadges).put("compactLists", compactLists).put("homeShelves", homeShelves)
        .put("homeDialSource", homeDialSource).put("homeDialRows", homeDialRows)
        .put("homeSpeedDialEnabled", homeSpeedDialEnabled).put("homeMixesDialEnabled", homeMixesDialEnabled)
        .put("homeDialLocalFiles", homeDialLocalFiles)
        .put("homeArtistDialEnabled", homeArtistDialEnabled).put("homeLuckyTileEnabled", homeLuckyTileEnabled)
        .put("homeDialLabels", homeDialLabels).put("homeDialPinIcon", homeDialPinIcon)
        .put("homeGradients", homeGradients)
        .put("homeDialTileStyle", homeDialTileStyle).put("homeDialNewReleaseTags", homeDialNewReleaseTags)
        .put("homeArtistDialOrder", homeArtistDialOrder)
        .put("albumActions", albumActions).put("playlistActions", playlistActions)
        .put("swipeRight", swipeRight).put("swipeLeft", swipeLeft)
        .put("streamingQualityWifi", streamingQualityWifi).put("streamingQualityMobile", streamingQualityMobile)
        .put("downloadQuality", downloadQuality).put("audioNormalization", audioNormalization)
        .put("showPlayerMenuButton", showPlayerMenuButton).put("miniPlayerSwipe", miniPlayerSwipe)
        .put("showSearchSources", showSearchSources)
        .put("analyticsEnabled", analyticsEnabled)
        .put("surfaceStyle", surfaceStyle).put("glassTransparency", glassTransparency.toDouble())
        .put("glassBlur", glassBlur.toDouble()).put("glassSaturation", glassSaturation.toDouble())
        .put("glassTint", glassTint).put("glassTintDarkness", glassTintDarkness.toDouble())
        .put("discordRpcEnabled", discordRpcEnabled).put("discordUseDetails", discordUseDetails)
        .put("discordShowAudioQuality", discordShowAudioQuality).put("discordAdvancedMode", discordAdvancedMode)
        .put("discordStatus", discordStatus).put("discordActivityType", discordActivityType)
        .put("discordActivityName", discordActivityName)
        .put("discordButton1Text", discordButton1Text).put("discordButton1Visible", discordButton1Visible)
        .put("discordButton2Text", discordButton2Text).put("discordButton2Visible", discordButton2Visible)
        .put("discordInfoDismissed", discordInfoDismissed)

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val LANGUAGE_SYSTEM = "system"
        const val TAB_HOME = "home"
        const val TAB_EXPLORE = "explore"
        const val TAB_SEARCH = "search"
        const val TAB_LIBRARY = "library"
        const val TAB_PODCASTS = "podcasts"
        const val MINI_SWIPE_TRACKS = "tracks"
        const val MINI_SWIPE_DISMISS = "dismiss"
        const val MINI_SWIPE_OFF = "off"
        const val DIAL_SOURCE_HISTORY = "history"
        const val DIAL_SOURCE_SERVER = "server"
        const val DIAL_SOURCE_SPOTIFY = "spotify"
        const val BACKDROP_BLEND = "blend"
        const val BACKDROP_MESH = "mesh"
        const val PROGRESS_PLAIN = "plain"
        const val PROGRESS_GLOW = "glow"
        const val PROGRESS_WAVE = "wave"
        const val DIAL_TILE_STYLE_PLAIN = "plain"
        const val DIAL_TILE_STYLE_EDGE = "edge"
        const val DIAL_TILE_STYLE_GLASS = "glass"

        /** Unknown or missing fields keep their default, so old backups stay valid. */
        fun fromJson(json: JSONObject): AppSettings {
            val d = AppSettings()
            val savedPreloadTracks = if (json.optBoolean("preloadEntireQueue", false)) 10
                else json.optInt("preloadTracks", d.preloadTracks)
            return AppSettings(
                themeMode = json.optString("themeMode", d.themeMode),
                language = json.optString("language", d.language),
                disableBlur = json.optBoolean("disableBlur", d.disableBlur),
                ambientBackground = json.optBoolean("ambientBackground", d.ambientBackground),
                showScrollbar = json.optBoolean("showScrollbar", d.showScrollbar),
                playerTheme = json.optString("playerTheme", d.playerTheme),
                playerArtworkGradient = json.optBoolean("playerArtworkGradient", d.playerArtworkGradient),
                detailPageTint = json.optBoolean("detailPageTint", d.detailPageTint),
                fullScreenCoverArt = json.optBoolean("fullScreenCoverArt", d.fullScreenCoverArt),
                albumCoverMix = json.optBoolean("albumCoverMix", d.albumCoverMix),
                fullScreenDetailCover = json.optBoolean("fullScreenDetailCover", d.fullScreenDetailCover),
                directSourceStream = json.optBoolean("directSourceStream", d.directSourceStream),
                addonLengthToleranceSec = json.optInt("addonLengthToleranceSec", d.addonLengthToleranceSec).coerceIn(1, 15),
                losslessUpgrade = json.optBoolean("losslessUpgrade", d.losslessUpgrade),
                playerBackdropStyle = json.optString("playerBackdropStyle", d.playerBackdropStyle)
                    .takeIf { it in setOf(BACKDROP_BLEND, BACKDROP_MESH) } ?: d.playerBackdropStyle,
                playerProgressStyle = json.optString("playerProgressStyle", d.playerProgressStyle)
                    .takeIf { it in setOf(PROGRESS_PLAIN, PROGRESS_GLOW, PROGRESS_WAVE) } ?: d.playerProgressStyle,
                palette = json.optString("palette", d.palette),
                carouselStyle = json.optString("carouselStyle", d.carouselStyle),
                showPlayerFileInfo = json.optBoolean("showPlayerFileInfo", d.showPlayerFileInfo),
                showQualityInLists = json.optBoolean("showQualityInLists", d.showQualityInLists),
                collagePattern = json.optString("collagePattern", d.collagePattern),
                collageAutoRotate = json.optBoolean("collageAutoRotate", d.collageAutoRotate),
                navBarStyle = json.optString("navBarStyle", d.navBarStyle),
                navBarCompact = json.optBoolean("navBarCompact", d.navBarCompact),
                immersiveLyrics = json.optBoolean("immersiveLyrics", d.immersiveLyrics),
                immersiveLyricsTimeoutMs = json.optLong("immersiveLyricsTimeoutMs", d.immersiveLyricsTimeoutMs),
                // Before there were several providers, one switch turned LRCLIB off; keep that choice as "none".
                lyricsProviders = if (json.has("lyricsProviders")) json.optString("lyricsProviders")
                    else if (json.optBoolean("lyricsFallback", true)) d.lyricsProviders else "",
                defaultTab = json.optString("defaultTab", d.defaultTab),
                keepPlayingInBackground = json.optBoolean("keepPlayingInBackground", d.keepPlayingInBackground),
                pauseOnVolumeZero = json.optBoolean("pauseOnVolumeZero", d.pauseOnVolumeZero),
                resumeOnHeadphones = json.optBoolean("resumeOnHeadphones", d.resumeOnHeadphones),
                persistentShuffle = json.optBoolean("persistentShuffle", d.persistentShuffle),
                showQueueHistory = json.optBoolean("showQueueHistory", d.showQueueHistory),
                audioCacheLimitMb = json.optInt("audioCacheLimitMb", d.audioCacheLimitMb).coerceIn(512, 32768),
                preloadTracks = savedPreloadTracks.coerceIn(1, 20),
                preloadOnMobile = json.optBoolean("preloadOnMobile", d.preloadOnMobile),
                cachePlayedTracks = json.optBoolean("cachePlayedTracks", d.cachePlayedTracks),
                preloadUpcomingTracks = json.optBoolean("preloadUpcomingTracks", savedPreloadTracks != 0),
                offlineMode = json.optBoolean("offlineMode", d.offlineMode),
                tapBackgroundClosesPlayer = json.optBoolean("tapBackgroundClosesPlayer", d.tapBackgroundClosesPlayer),
                hapticsEnabled = json.optBoolean("hapticsEnabled", d.hapticsEnabled),
                showSourceBadges = json.optBoolean("showSourceBadges", d.showSourceBadges),
                compactLists = json.optBoolean("compactLists", d.compactLists),
                homeShelves = json.optString("homeShelves", d.homeShelves),
                homeDialSource = json.optString("homeDialSource", d.homeDialSource)
                    .takeIf { it in setOf(DIAL_SOURCE_HISTORY, DIAL_SOURCE_SERVER, DIAL_SOURCE_SPOTIFY) }
                    ?: d.homeDialSource,
                homeDialRows = json.optInt("homeDialRows", d.homeDialRows).coerceIn(3, 4),
                homeSpeedDialEnabled = json.optBoolean("homeSpeedDialEnabled", d.homeSpeedDialEnabled),
                homeMixesDialEnabled = json.optBoolean("homeMixesDialEnabled", d.homeMixesDialEnabled),
                homeDialLocalFiles = json.optBoolean("homeDialLocalFiles", d.homeDialLocalFiles),
                homeArtistDialEnabled = json.optBoolean("homeArtistDialEnabled", d.homeArtistDialEnabled),
                homeLuckyTileEnabled = json.optBoolean("homeLuckyTileEnabled", d.homeLuckyTileEnabled),
                homeDialLabels = json.optBoolean("homeDialLabels", d.homeDialLabels),
                homeGradients = json.optBoolean("homeGradients", d.homeGradients),
                homeDialPinIcon = json.optBoolean("homeDialPinIcon", d.homeDialPinIcon),
                homeDialTileStyle = json.optString("homeDialTileStyle", d.homeDialTileStyle)
                    .takeIf { it in setOf(DIAL_TILE_STYLE_PLAIN, DIAL_TILE_STYLE_EDGE, DIAL_TILE_STYLE_GLASS) }
                    ?: d.homeDialTileStyle,
                homeDialNewReleaseTags = json.optBoolean("homeDialNewReleaseTags", d.homeDialNewReleaseTags),
                homeArtistDialOrder = json.optString("homeArtistDialOrder", d.homeArtistDialOrder),
                albumActions = json.optString("albumActions", d.albumActions),
                playlistActions = json.optString("playlistActions", d.playlistActions),
                swipeRight = json.optString("swipeRight", d.swipeRight),
                swipeLeft = json.optString("swipeLeft", d.swipeLeft),
                streamingQualityWifi = json.optString("streamingQualityWifi", d.streamingQualityWifi),
                streamingQualityMobile = json.optString("streamingQualityMobile", d.streamingQualityMobile),
                downloadQuality = json.optString("downloadQuality", d.downloadQuality),
                audioNormalization = json.optBoolean("audioNormalization", d.audioNormalization),
                showPlayerMenuButton = json.optBoolean("showPlayerMenuButton", d.showPlayerMenuButton),
                miniPlayerSwipe = json.optString("miniPlayerSwipe", d.miniPlayerSwipe)
                    .takeIf { it in setOf(MINI_SWIPE_TRACKS, MINI_SWIPE_DISMISS, MINI_SWIPE_OFF) } ?: d.miniPlayerSwipe,
                showSearchSources = json.optBoolean("showSearchSources", d.showSearchSources),
                analyticsEnabled = json.optBoolean("analyticsEnabled", d.analyticsEnabled),
                surfaceStyle = `in`.melo.ui.glass.MeloSurfaceStyle.of(json.optString("surfaceStyle", d.surfaceStyle)).key,
                glassTransparency = json.optDouble("glassTransparency", d.glassTransparency.toDouble()).toFloat().coerceIn(0f, 1f),
                glassBlur = json.optDouble("glassBlur", d.glassBlur.toDouble()).toFloat().coerceIn(0f, 32f),
                glassSaturation = json.optDouble("glassSaturation", d.glassSaturation.toDouble()).toFloat().coerceIn(0f, 2f),
                glassTint = `in`.melo.ui.glass.MeloGlassTint.of(json.optString("glassTint", d.glassTint)).key,
                glassTintDarkness = json.optDouble("glassTintDarkness", d.glassTintDarkness.toDouble()).toFloat().coerceIn(0f, 1f),
                discordRpcEnabled = json.optBoolean("discordRpcEnabled", d.discordRpcEnabled),
                discordUseDetails = json.optBoolean("discordUseDetails", d.discordUseDetails),
                discordShowAudioQuality = json.optBoolean("discordShowAudioQuality", d.discordShowAudioQuality),
                discordAdvancedMode = json.optBoolean("discordAdvancedMode", d.discordAdvancedMode),
                discordStatus = json.optString("discordStatus", d.discordStatus)
                    .takeIf { it in setOf("online", "idle", "dnd") } ?: d.discordStatus,
                discordActivityType = json.optString("discordActivityType", d.discordActivityType)
                    .takeIf { it in setOf("listening", "playing", "watching", "competing") } ?: d.discordActivityType,
                discordActivityName = json.optString("discordActivityName", d.discordActivityName),
                discordButton1Text = json.optString("discordButton1Text", d.discordButton1Text),
                discordButton1Visible = json.optBoolean("discordButton1Visible", d.discordButton1Visible),
                discordButton2Text = json.optString("discordButton2Text", d.discordButton2Text),
                discordButton2Visible = json.optBoolean("discordButton2Visible", d.discordButton2Visible),
                discordInfoDismissed = json.optBoolean("discordInfoDismissed", d.discordInfoDismissed),
            )
        }
    }
}

class SettingsStore private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = mutable.asStateFlow()

    private fun read(): AppSettings = preferences.getString(KEY, null)?.let { saved ->
        try { AppSettings.fromJson(JSONObject(saved)) } catch (_: org.json.JSONException) { null }
    } ?: AppSettings()

    fun update(change: (AppSettings) -> AppSettings) {
        mutable.update(change)
        preferences.edit { putString(KEY, mutable.value.toJson().toString()) }
    }

    companion object {
        private const val KEY = "settings"
        @Volatile private var instance: SettingsStore? = null
        /** Shared with the playback service, which runs in the same process. */
        fun get(context: Context): SettingsStore = instance ?: synchronized(this) {
            instance ?: SettingsStore(context).also { instance = it }
        }
    }
}

/** The enabled lyrics providers, in their default order. */
fun AppSettings.lyricsProviderList(): List<`in`.melo.data.repository.LyricsProvider> {
    val keys = lyricsProviders.split(',').map(String::trim).toSet()
    return `in`.melo.data.repository.LyricsProvider.entries.filter { it.key in keys }
}

fun AppSettings.withLyricsProvider(provider: `in`.melo.data.repository.LyricsProvider, on: Boolean): AppSettings {
    val keys = lyricsProviderList().map { it.key }.toMutableSet().apply { if (on) add(provider.key) else remove(provider.key) }
    return copy(lyricsProviders = `in`.melo.data.repository.LyricsProvider.entries.filter { it.key in keys }.joinToString(",") { it.key })
}
