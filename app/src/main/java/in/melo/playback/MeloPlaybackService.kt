package `in`.melo.playback

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import `in`.melo.BuildConfig
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import `in`.melo.MainActivity
import `in`.melo.data.MusicSource
import `in`.melo.data.musicSourceOf
import `in`.melo.data.UsageAnalytics
import `in`.melo.data.preferences.SettingsStore

@UnstableApi
class MeloPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var behavior: PlaybackBehavior? = null
    private var preloader: QueuePreloader? = null
    private var recovery: PlaybackRecovery? = null
    private var normalizingSink: NormalizingAudioSink? = null
    private var discord: DiscordPresence? = null
    private var upgrader: LosslessUpgrader? = null
    private val effectsScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate + kotlinx.coroutines.SupervisorJob())
    override fun onCreate() {
        super.onCreate()
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                NormalizingAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .apply { if (BuildConfig.BUILD_TYPE == "debug") setAudioProcessors(arrayOf(SignalProbe())) }.build())
                    .also { normalizingSink = it }
        }
        // YouTube Music requests have no known length: they use their own chain, keyed by track.
        val youtube = youTubeCacheChain(this)
        `in`.melo.data.addons.AddonResolver.init(this)
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(MeloMediaSourceFactory(this, ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this)),
                ProgressiveMediaSource.Factory(youtube.kept).setLoadErrorHandlingPolicy(ResilientLoadPolicy())))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build()
        // Buffering between tracks is still listening: count the intent to play, not only sound.
        fun updateListening() = `in`.melo.data.MeloTelemetry.setPlaying(player.isPlaying ||
            (player.playWhenReady && player.playbackState == androidx.media3.common.Player.STATE_BUFFERING))
        var lastTelemetryTrack: String? = null
        var bufferingStartedAt: Long? = null
        var listeningStartedAt: Long? = null
        var listeningCatalog = "other"
        fun telemetryCatalog(): String = when (musicSourceOf(player.currentMediaItem?.mediaId.orEmpty())) {
            MusicSource.Spotify -> "spotify_catalog"
            MusicSource.YouTubeMusic -> "youtube_music"
            MusicSource.Local -> "local"
            MusicSource.Addon -> "addon"
            MusicSource.MusicServer -> "music_server"
        }
        fun recordTrackStart() {
            val id = player.currentMediaItem?.mediaId ?: return
            if (lastTelemetryTrack != id) {
                lastTelemetryTrack = id
                UsageAnalytics.playbackStarted(this, telemetryCatalog())
            }
        }
        fun finishListeningSegment(restart: Boolean = false) {
            val startedAt = listeningStartedAt
            val now = SystemClock.elapsedRealtime()
            if (startedAt != null) UsageAnalytics.playerPlayingTime(this, now - startedAt, listeningCatalog)
            listeningStartedAt = if (restart && player.isPlaying) now else null
            if (listeningStartedAt != null) listeningCatalog = telemetryCatalog()
        }
        fun finishBuffering() {
            val startedAt = bufferingStartedAt ?: return
            bufferingStartedAt = null
            UsageAnalytics.buffering(this, SystemClock.elapsedRealtime() - startedAt, telemetryCatalog())
        }
        fun errorCategory(error: PlaybackException): String = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT -> "network"
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION -> "authorization"
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "decoder"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "unavailable"
            else -> "other"
        }
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    recordTrackStart()
                    if (listeningStartedAt == null) {
                        listeningCatalog = telemetryCatalog()
                        listeningStartedAt = SystemClock.elapsedRealtime()
                    }
                } else {
                    finishListeningSegment()
                }
                updateListening()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                    if (bufferingStartedAt == null) bufferingStartedAt = SystemClock.elapsedRealtime()
                } else {
                    finishBuffering()
                }
                updateListening()
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                finishBuffering()
                // A track switched to a better copy is the same play, not a new one.
                if (reason != androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                    mediaItem?.mediaId != lastTelemetryTrack) lastTelemetryTrack = null
                if (player.isPlaying) {
                    finishListeningSegment(restart = true)
                    recordTrackStart()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                finishBuffering()
                // An add-on copy that fails mid-play gives way to YouTube Music, at the same position.
                val failed = player.currentMediaItem
                // A switched copy is put back by LosslessUpgrader instead.
                if (failed != null && YouTubeUris.isYouTube(failed.localConfiguration?.uri) &&
                    !LosslessUpgrade.isMarked(failed.localConfiguration?.uri) &&
                    AddonPlayback.served.remove(failed.mediaId) != null) {
                    `in`.melo.data.addons.AddonResolver.forget(failed.mediaId)
                    player.prepare()
                    return
                }
                UsageAnalytics.playbackError(this@MeloPlaybackService, telemetryCatalog(), errorCategory(error))
                finishListeningSegment()
                updateListening()
            }
        })
        effectsScope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(60_000)
                if (player.isPlaying && listeningStartedAt != null) finishListeningSegment(restart = true)
            }
        }
        player.preloadConfiguration = if (SettingsStore.get(this).settings.value.preloadUpcomingTracks) {
            ExoPlayer.PreloadConfiguration(NEXT_TRACK_PRELOAD_US)
        } else ExoPlayer.PreloadConfiguration.DEFAULT
        player.addAnalyticsListener(PlaybackDiagnostics)
        player.addAnalyticsListener(AudioPipelineTracker(player).also { it.start() })
        preloader = QueuePreloader(this, player, effectsScope, youtube.recent).also { it.start() }
        recovery = PlaybackRecovery(this, player, effectsScope).also { it.start() }
        behavior = PlaybackBehavior(this, player).also { it.start() }
        discord = DiscordPresence(this, player, effectsScope).also { it.start() }
        upgrader = LosslessUpgrader(this, player, effectsScope).also { it.start() }
        EqualizerRuntime.start(this, effectsScope)
        EqualizerRuntime.setAudioSessionId(player.audioSessionId)
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = EqualizerRuntime.setAudioSessionId(audioSessionId)
            // The first track's loudness may only be known once it is read, hence READY too.
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = updateNormalization(player)
            override fun onPlaybackStateChanged(playbackState: Int) = updateNormalization(player)
        })
        watchSleepTimer(player)
        val settings = SettingsStore.get(this)
        effectsScope.launch {
            settings.settings.map { it.audioNormalization to it.preloadUpcomingTracks }.distinctUntilChanged()
                .collect { (_, preload) ->
                    updateNormalization(player)
                    player.preloadConfiguration = if (preload) ExoPlayer.PreloadConfiguration(NEXT_TRACK_PRELOAD_US)
                    else ExoPlayer.PreloadConfiguration.DEFAULT
                }
        }
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        mediaSession = MediaSession.Builder(this, player).setSessionActivity(intent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    if (controller.packageName == packageName || controller.isTrusted) super.onConnect(session, controller)
                    else MediaSession.ConnectionResult.reject()
            }).build()
    }
    /**
     * Pauses when the sleep timer runs out, the last seconds faded down, or as the last song it
     * allows ends. The player pauses that song at its very end, so none of the next one is heard.
     */
    private fun watchSleepTimer(player: ExoPlayer) {
        effectsScope.launch {
            SleepTimer.deadline.collectLatest { deadline ->
                if (deadline == null) return@collectLatest
                // Checked against the clock in short waits: a long one can oversleep a dozing CPU.
                while (true) {
                    val untilFade = deadline - SystemClock.elapsedRealtime() - SLEEP_FADE_MS
                    if (untilFade <= 0) break
                    kotlinx.coroutines.delay(untilFade.coerceAtMost(SLEEP_CHECK_MS))
                }
                val volume = player.volume
                try {
                    if (player.isPlaying) {
                        val start = SystemClock.elapsedRealtime()
                        while (true) {
                            val left = deadline - SystemClock.elapsedRealtime()
                            if (left <= 0) break
                            player.volume = volume * (left.toFloat() / (deadline - start).coerceAtLeast(1)).coerceIn(0f, 1f)
                            kotlinx.coroutines.delay(SLEEP_FADE_STEP_MS)
                        }
                    }
                    player.pause()
                } finally {
                    player.volume = volume
                }
                SleepTimer.cancel()
            }
        }
        effectsScope.launch {
            SleepTimer.songsLeft.collect { player.pauseAtEndOfMediaItems = it == 1 }
        }
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) SleepTimer.songFinished()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && SleepTimer.songsLeft.value == 1 &&
                    reason == androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) SleepTimer.cancel()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                // The queue ran out first: nothing is left to pause.
                if (playbackState == androidx.media3.common.Player.STATE_ENDED && SleepTimer.songsLeft.value > 0) SleepTimer.cancel()
            }
        })
    }
    private fun updateNormalization(player: androidx.media3.common.Player) {
        val sink = normalizingSink ?: return
        val enabled = SettingsStore.get(this).settings.value.audioNormalization
        val id = player.currentMediaItem?.mediaId
        val gain = if (!enabled || id == null) null else TrackLoudness.gainDb(this, id)
        val factor = if (gain == null) 1f else TrackLoudness.linear(gain)
        if (factor != sink.factor) android.util.Log.i("MeloAudio", "normalization enabled=$enabled gain=${gain?.let { "%.1f".format(it) }}dB volume=${"%.2f".format(factor)}")
        sink.factor = factor
        AudioPipeline.update { it.copy(normalizationGainDb = gain?.coerceAtMost(0f)) }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession
    override fun onTaskRemoved(rootIntent: Intent?) {
        val keepPlaying = SettingsStore.get(this).settings.value.keepPlayingInBackground
        if (!keepPlaying) mediaSession?.player?.pause()
        if (!keepPlaying || mediaSession?.player?.playWhenReady != true) stopSelf()
    }
    override fun onDestroy() {
        `in`.melo.data.MeloTelemetry.setPlaying(false)
        behavior?.close(); behavior = null
        preloader?.close(); preloader = null
        recovery?.close(); recovery = null
        discord?.close(); discord = null
        upgrader?.close(); upgrader = null
        effectsScope.cancel()
        EqualizerRuntime.setAudioSessionId(0)
        AudioPipeline.reset()
        mediaSession?.run { player.release(); release() }; mediaSession = null
        super.onDestroy()
    }

    internal class YouTubeChain(val recent: CacheDataSource.Factory, val kept: CacheDataSource.Factory)

    internal companion object {
        /** Downloads first, then the recent cache, then the network. Nothing is written to downloads while listening. */
        fun youTubeCacheChain(context: android.content.Context): YouTubeChain {
            val youtube = YouTubeDataSource.Factory(context)
            val flags = CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
            val recent = CacheDataSource.Factory().setCache(AudioCache.cache(context)).setCacheKeyFactory(AudioCache.keys)
                .setUpstreamDataSourceFactory(youtube).setFlags(flags)
            val recentReader = CacheDataSource.Factory().setCache(AudioCache.cache(context)).setCacheKeyFactory(AudioCache.keys)
                .setUpstreamDataSourceFactory(youtube).setCacheWriteDataSinkFactory(null).setFlags(flags)
            val recentPlayback = RecentAudioDataSourceFactory(context, recent, recentReader)
            val kept = CacheDataSource.Factory().setCache(DownloadCenter.cache(context)).setCacheKeyFactory(AudioCache.keys)
                .setUpstreamDataSourceFactory(recentPlayback).setCacheWriteDataSinkFactory(null).setFlags(flags)
            return YouTubeChain(recent, kept)
        }
        private const val NEXT_TRACK_PRELOAD_US = 30_000_000L
        private const val SLEEP_FADE_MS = 8_000L
        private const val SLEEP_FADE_STEP_MS = 100L
        private const val SLEEP_CHECK_MS = 30_000L
    }
}
