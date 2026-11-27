package `in`.melo.playback

import android.content.Context
import `in`.melo.data.server.MusicServerException
import `in`.melo.data.server.MusicServerFailure
import `in`.melo.data.server.MusicServerUris
import android.net.ConnectivityManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import `in`.melo.data.AudioIndex
import `in`.melo.data.Music
import `in`.melo.data.MusicKind
import `in`.melo.data.NetworkMonitor
import `in`.melo.data.isLocalTrack
import `in`.melo.data.preferences.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException

/** Reads the next queued tracks ahead into the recent cache, from the music server. */
@UnstableApi
internal class QueuePreloader(
    context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
    private val serverSources: CacheDataSource.Factory,
) : Player.Listener, AutoCloseable {
    private val app = context.applicationContext
    private val index = AudioIndex.get(app)
    private val settings = SettingsStore.get(app)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private var networkJob: Job? = null
    private var settingsJob: Job? = null
    private var connectionListener: AutoCloseable? = null
    private var job: Job? = null
    private var revision = 0L
    private var describedId: String? = null
    private var closed = false

    fun start() {
        player.addListener(this)
        connectionListener = NetworkMonitor.onConnectionsInvalidated {
            scope.launch { job?.cancel(); job = null; schedule() }
        }
        networkJob = scope.launch {
            NetworkMonitor.online.collect { online ->
                if (online) schedule() else { job?.cancel(); job = null }
            }
        }
        settingsJob = scope.launch {
            settings.settings.collect {
                // Respect Wi-Fi-only immediately, including a fill already in progress.
                job?.cancel(); job = null
                schedule()
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { markPlayed(); schedule() }
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) schedule()
    }
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) { markPlayed(); schedule() }
    }

    private fun markPlayed() {
        val item = player.currentMediaItem ?: return
        if (describedId == item.mediaId || !player.isPlaying) return
        describedId = item.mediaId
        val track = musicOf(item).takeUnless(Music::isLocalTrack) ?: return
        scope.launch(Dispatchers.IO) { index.describe(track, played = true) }
    }

    /** A queue change reprioritizes the next file; it never abandons the one being completed. */
    private fun schedule() {
        revision++
        if (closed || !NetworkMonitor.online.value || job?.isActive == true) return
        job = scope.launch {
            delay(SETTLE_MS)
            do {
                val observed = revision
                val s = settings.settings.value
                if (!s.preloadUpcomingTracks) return@launch
                // A queue put back from the last session waits unprepared until Play; read nothing for it yet.
                if (player.playbackState == Player.STATE_IDLE && !player.playWhenReady) return@launch
                val current = player.currentMediaItemIndex
                val items = if (current in 0 until player.mediaItemCount) {
                    preloadRange(current, player.mediaItemCount, s.preloadTracks).map { player.getMediaItemAt(it) }
                } else emptyList()
                fillServer(items.drop(1).filter { MusicServerUris.isPlayback(it.localConfiguration?.uri) }.take(MAX_SERVER_AHEAD), s)
                withContext(Dispatchers.IO) { index.flush() }
            } while (revision != observed && currentCoroutineContext().isActive)
        }
    }

    /** The next server tracks of the queue, read ahead into the recent cache. */
    private suspend fun fillServer(items: List<MediaItem>, s: `in`.melo.data.preferences.AppSettings) {
        for (item in items) {
            if (!NetworkMonitor.online.value || (!s.preloadOnMobile && connectivity.isActiveNetworkMetered)) return
            val uri = item.localConfiguration?.uri ?: continue
            val available = withContext(Dispatchers.IO) { AudioCache.isAvailableOffline(app, item.mediaId) }
            if (available) continue
            val key = item.localConfiguration?.customCacheKey ?: AudioCache.key(MusicServerUris.fileKey(uri))
            val spec = DataSpec.Builder().setUri(uri).setKey(key).build()
            val writer = CacheWriter(serverSources.createDataSourceForDownloading(), spec, null, null)
            try { withContext(Dispatchers.IO) { runInterruptible { writer.cache() } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { /* Played from the network when its turn comes. */ }
            finally { writer.cancel() }
        }
    }

    override fun close() {
        closed = true
        job?.cancel(); networkJob?.cancel(); settingsJob?.cancel()
        connectionListener?.close()
        player.removeListener(this)
    }

    private companion object {
        const val SETTLE_MS = 1_000L
        const val MAX_SERVER_AHEAD = 2
    }
}

/** Brings playback back by itself when the network does, and moves on to what is on the device without it. */
@UnstableApi
internal class PlaybackRecovery(
    context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
) : Player.Listener, AutoCloseable {
    private val app = context.applicationContext
    private var attempts = 0
    private var lastFailureAt = 0L
    private var retry: Job? = null

    fun start() {
        player.addListener(this)
        scope.launch {
            NetworkMonitor.online.collect { online ->
                if (online && player.playerError != null) { attempts = 0; player.prepare() }
            }
        }
    }

    // READY alone does not reset the count: a track that fails after its clear lead-in becomes READY
    // again at every retry, and would otherwise stop and restart forever.
    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { attempts = 0 }

    override fun onPlayerError(error: PlaybackException) {
        retry?.cancel()
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFailureAt > STABLE_PLAYBACK_MS) attempts = 0
        lastFailureAt = now
        if (!player.playWhenReady) return
        if (!NetworkMonitor.online.value) { skipToAvailable(); return }
        if (attempts >= MAX_ATTEMPTS || (!error.isConnectionFailure() && !error.hasTimeout())) return
        attempts++
        retry = scope.launch {
            delay(attempts * RETRY_STEP_MS)
            if (player.playerError != null) player.prepare()
        }
    }

    private fun skipToAvailable() {
        val current = player.currentMediaItemIndex
        val targets = (current + 1 until player.mediaItemCount).map { it to player.getMediaItemAt(it) }
        retry = scope.launch {
            for ((position, item) in targets) {
                if (player.currentMediaItemIndex != current || NetworkMonitor.online.value) return@launch
                if (MusicServerUris.isPlayback(item.localConfiguration?.uri) &&
                    !withContext(Dispatchers.IO) { AudioCache.isAvailableOffline(app, item.mediaId) }) continue
                if (player.currentMediaItemIndex != current || player.playerError == null || !player.playWhenReady) return@launch
                player.seekToDefaultPosition(position)
                player.prepare()
                return@launch
            }
        }
    }

    private fun PlaybackException.isConnectionFailure() = errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
        errorCode == PlaybackException.ERROR_CODE_TIMEOUT

    private fun Throwable.hasTimeout(): Boolean = generateSequence(this) { it.cause }
        .take(12).any { it is java.util.concurrent.TimeoutException }

    override fun close() {
        retry?.cancel()
        player.removeListener(this)
    }

    private companion object {
        const val MAX_ATTEMPTS = 4
        const val RETRY_STEP_MS = 1_500L
        const val STABLE_PLAYBACK_MS = 60_000L
    }
}

/** Retries at a steady pace: a lost connection usually comes back within seconds, and fails again just as fast. */
@UnstableApi
internal class ResilientLoadPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val failure = generateSequence<Throwable>(loadErrorInfo.exception) { it.cause }.take(8)
            .filterIsInstance<MusicServerException>().firstOrNull()?.failure
        if (failure == MusicServerFailure.NoMatch || failure == MusicServerFailure.Credentials) return C.TIME_UNSET
        val base = super.getRetryDelayMsFor(loadErrorInfo)
        return if (base == C.TIME_UNSET) base else minOf(base, MAX_RETRY_DELAY_MS)
    }
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = if (NetworkMonitor.online.value) ONLINE_RETRIES else OFFLINE_RETRIES

    private companion object {
        const val MAX_RETRY_DELAY_MS = 2_000L
        const val ONLINE_RETRIES = 6
        const val OFFLINE_RETRIES = 1
    }
}

internal fun musicOf(item: MediaItem) = Music(item.mediaId, item.mediaMetadata.title?.toString().orEmpty(),
    item.mediaMetadata.artist?.toString().orEmpty(), MusicKind.Track, imageUrl = item.mediaMetadata.artworkUri?.toString(),
    durationMs = item.mediaMetadata.extras?.getInt("duration") ?: 0, artwork = item.mediaMetadata.extras?.getInt("artwork") ?: 0,
    album = item.mediaMetadata.extras?.getString("album").orEmpty(), mediaUri = item.mediaMetadata.extras?.getString("mediaUri"),
    quality = item.mediaMetadata.extras?.getString("quality").orEmpty())

/** The queue length never expands the prefetch window beyond twenty following tracks. */
internal fun preloadRange(current: Int, count: Int, following: Int): IntRange =
    if (current !in 0 until count) IntRange.EMPTY else current..minOf(current + following.coerceIn(0, 20), count - 1)
