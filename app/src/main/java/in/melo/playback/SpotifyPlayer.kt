package `in`.melo.playback

import androidx.annotation.StringRes
import `in`.melo.R
import `in`.melo.utils.AppLocales
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import `in`.melo.data.Music
import `in`.melo.data.MusicKind
import `in`.melo.data.server.MusicServer
import `in`.melo.data.server.MusicServerException
import `in`.melo.data.server.MusicServerFailure
import `in`.melo.data.server.MusicServerUris
import `in`.melo.data.isLocalTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Playback(
    val queue: List<Music> = emptyList(), val index: Int = 0, val playing: Boolean = false,
    val buffering: Boolean = false, val positionMs: Int = 0, val durationMs: Int = 0,
    val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF, val error: String? = null,
    val canNext: Boolean = false,
    val playWhenReady: Boolean = false,
    /** Measured from the selected audio track, for the file information of the player. */
    val audioFormat: String = "",
    /** Tracks the user queued, waiting right after the current one. */
    val queuedCount: Int = 0,
    /** True while this context can replace its generated radio recommendations. */
    val canRefreshRecommendations: Boolean = false,
    /** Number of tracks remaining after the current one in this logical context. */
    val remainingContextTracks: Int = 0,
    /** Last track in the logical context, including tracks outside the player's loaded window. */
    val contextTailId: String? = null,
) { val track: Music? get() = queue.getOrNull(index) }

/** Outlives the activity, like the playback service whose queue it describes. */
private object ShuffleMemory {
    var enabled = false
    private var loaded = false
    /** Restores the last choice once, when the setting asks for it. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val settings = `in`.melo.data.preferences.SettingsStore.get(context).settings.value
        enabled = settings.persistentShuffle && context.getSharedPreferences("player_state", 0).getBoolean("shuffle", false)
    }
    fun save(context: Context) = context.getSharedPreferences("player_state", 0).edit().putBoolean("shuffle", enabled).apply()
    /** Queue order to restore when shuffle is turned off. */
    var original: List<Music>? = null
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SpotifyPlayer(context: Context, scope: CoroutineScope) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(Playback())
    val state = mutableState.asStateFlow()
    private var controller: MediaController? = null
    private var pending: Pair<List<Music>, Int>? = null
    private val future = MediaController.Builder(context,
        SessionToken(context, ComponentName(context, MeloPlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) queueChanged = true
            update(player)
        }
    }
    /**
     * The queue as [Music], rebuilt only when the timeline changes. A long queue (a playlist of
     * a few thousand tracks) would otherwise be converted and compared on the main thread at every
     * position tick and player event, delaying taps and track changes.
     */
    private var queue: List<Music> = emptyList()
    private var queueChanged = true
    /**
     * The player holds a window of the context around the current track, as Spotify's own player
     * does: a few thousand media items would make every timeline change slow to copy between the
     * controller and the session. The rest of the context waits here and is added as playback moves.
     */
    private var before: List<Music> = emptyList()
    private var after: List<Music> = emptyList()
    private var hasRadioRecommendations = false
    /** Prevent a refresh from rotating back to a recommendation batch already shown this session. */
    private val seenRadioRecommendationIds = LinkedHashSet<String>()
    init {
        ShuffleMemory.load(appContext)
        future.addListener({
            try {
                controller = future.get().also { it.addListener(listener) }
                val player = controller ?: return@addListener
                when {
                    pending != null -> pending?.let { (queue, index) -> pending = null; load(queue, index) }
                    // A fresh service has nothing loaded: put back what was playing when Melo closed.
                    player.mediaItemCount == 0 -> restore(player)
                    else -> update(player)
                }
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(buffering = false, error = "The player could not start.") }
        }, ContextCompat.getMainExecutor(context))
        scope.launch { while (true) { delay(300); controller?.let(::update) } }
    }
    /**
     * Loads the queue Melo was left at without preparing it, so nothing is fetched until Play is
     * pressed; the player then starts the same track at the same second.
     */
    private fun restore(player: Player) {
        val snapshot = LastQueue.load(appContext)
        if (snapshot == null) { update(player); return }
        val tracks = snapshot.entries.map { it.track }
        val queued = snapshot.entries.map { it.queued }
        val (shown, earlier, later) = window(tracks, snapshot.index)
        before = earlier; after = later
        queueChanged = true
        val from = windowStart(snapshot.index)
        val items = shown.mapIndexed { i, track -> mediaItem(track, queued = queued[from + i]) }
        player.setMediaItems(items, snapshot.index - from, snapshot.positionMs)
        update(player)
    }
    fun play(queue: List<Music>, index: Int = 0) {
        val selected = queue.getOrNull(index) ?: return
        if (!selected.isPlayableTrack()) return
        hasRadioRecommendations = false
        seenRadioRecommendationIds.clear()
        val playable = queue.filter(Music::isPlayableTrack)
        val first = queue.take(index).count(Music::isPlayableTrack)
        // Shuffle reorders the queue itself: the chosen track plays first and every other one follows.
        ShuffleMemory.original = playable.takeIf { ShuffleMemory.enabled }
        val tracks = if (ShuffleMemory.enabled) listOf(playable[first]) + (playable.take(first) + playable.drop(first + 1)).shuffled() else playable
        val start = if (ShuffleMemory.enabled) 0 else first
        mutableState.value = Playback(window(tracks, start).first, start - windowStart(start), buffering = true,
            durationMs = selected.durationMs, shuffle = ShuffleMemory.enabled, canNext = start < tracks.lastIndex)
        if (controller == null) { pending = tracks to start; return }
        load(tracks, start)
    }
    private fun windowStart(start: Int) = (start - WINDOW_BEHIND).coerceAtLeast(0)
    /** The part of [tracks] the player holds when [start] plays, and what stays out before and after it. */
    private fun window(tracks: List<Music>, start: Int): Triple<List<Music>, List<Music>, List<Music>> {
        val from = windowStart(start)
        val to = (start + WINDOW_AHEAD + 1).coerceAtMost(tracks.size)
        return Triple(tracks.subList(from, to).toList(), tracks.subList(0, from).toList(), tracks.subList(to, tracks.size).toList())
    }
    private fun load(tracks: List<Music>, start: Int) {
        val player = controller ?: return
        val (shown, earlier, later) = window(tracks, start)
        before = earlier; after = later
        queueChanged = true
        // As in Spotify, the tracks the user queued survive a new context and play right after its first track.
        val queued = userQueue(player)
        val first = start - windowStart(start)
        val items = shown.map(::mediaItem).toMutableList().apply { addAll(first + 1, queued.map { mediaItem(it, queued = true) }) }
        player.setMediaItems(items, first, 0L); player.prepare(); player.play()
    }
    /** The tracks the user queued, waiting right after the current one. */
    private fun userQueue(player: Player): List<Music> {
        val count = player.mediaItemCount
        if (count == 0) return emptyList()
        return (player.currentMediaItemIndex + 1 until count).asSequence()
            .map(player::getMediaItemAt).takeWhile(MediaItem::isQueued).map(::music).toList()
    }
    /**
     * Spotify's "Add to queue": after the current track and after what the user already queued,
     * before the rest of the playlist or album. Queued tracks are never shuffled.
     */
    fun addToQueue(tracks: List<Music>) { controller?.let { player ->
        val valid = tracks.filter(Music::isPlayableTrack)
        if (valid.isEmpty()) return
        if (player.mediaItemCount == 0) { play(valid, 0); return }
        val position = player.currentMediaItemIndex + 1 + userQueue(player).size
        player.addMediaItems(position, valid.map { mediaItem(it, queued = true) })
        queueChanged = true; update(player)
    } }
    /** "Play next": at the head of the user's queue. */
    fun playNext(tracks: List<Music>) { controller?.let { player ->
        val valid = tracks.filter(Music::isPlayableTrack)
        if (valid.isEmpty()) return
        if (player.mediaItemCount == 0) { play(valid, 0); return }
        player.addMediaItems(player.currentMediaItemIndex + 1, valid.map { mediaItem(it, queued = true) })
        queueChanged = true; update(player)
    } }
    /** Adds radio behind the current context only while its seed is still the current track. */
    fun extendContext(seedId: String, tracks: List<Music>) {
        val player = controller
        if (player == null) {
            val (pendingTracks, index) = pending ?: return
            if (pendingTracks.getOrNull(index)?.id != seedId) return
            val known = pendingTracks.mapTo(HashSet(), Music::id)
            val added = tracks.filter(Music::isPlayableTrack).filter { known.add(it.id) }
            if (added.isEmpty()) return
            pending = (pendingTracks + added) to index
            ShuffleMemory.original = ShuffleMemory.original?.let { it + added }
            hasRadioRecommendations = true
            seenRadioRecommendationIds.addAll(added.map(Music::id))
            mutableState.value = mutableState.value.copy(
                canRefreshRecommendations = true,
                remainingContextTracks = (pendingTracks.size + added.size - index - 1).coerceAtLeast(0),
                contextTailId = null,
            )
            return
        }
        if (player.currentMediaItem?.mediaId != seedId) return
        val known = (before + queue + after).mapTo(HashSet(), Music::id)
        val added = tracks.filter(Music::isPlayableTrack)
            .filter { it.id !in seenRadioRecommendationIds }
            .filter { known.add(it.id) }
        if (added.isEmpty()) return
        after = after + added
        ShuffleMemory.original = ShuffleMemory.original?.let { it + added }
        hasRadioRecommendations = true
        seenRadioRecommendationIds.addAll(added.map(Music::id))
        update(player)
    }
    /** Marks a queue loaded from a radio station as eligible for recommendation refresh. */
    fun markRadioContext() {
        if (mutableState.value.track == null) return
        hasRadioRecommendations = true
        val player = controller
        if (player != null) {
            val currentIndex = player.currentMediaItemIndex
            val manuallyQueuedIds = userQueue(player).mapTo(HashSet(), Music::id)
            val currentAndFollowing = (currentIndex.coerceAtLeast(0) until player.mediaItemCount)
                .map { music(player.getMediaItemAt(it)) }
                .filterNot { it.id in manuallyQueuedIds }
            seenRadioRecommendationIds.addAll((currentAndFollowing + after).map(Music::id))
            update(player)
        } else {
            pending?.let { (tracks, index) ->
                seenRadioRecommendationIds.addAll(tracks.drop(index + 1).map(Music::id))
            }
            mutableState.value = mutableState.value.copy(
                canRefreshRecommendations = true,
                contextTailId = null,
            )
        }
    }
    /** IDs returned in any radio batch for this playback context, including replaced batches. */
    fun seenRadioRecommendationIds(): Set<String> = seenRadioRecommendationIds.toSet()
    /** Replaces only the generated radio tail, keeping the current track and the user's queue. */
    fun replaceRadioRecommendations(seedId: String, tracks: List<Music>): Boolean {
        val player = controller ?: return false
        if (!hasRadioRecommendations || player.currentMediaItem?.mediaId != seedId) return false

        val currentIndex = player.currentMediaItemIndex
        val current = music(player.currentMediaItem ?: return false)
        val earlier = before + (0 until currentIndex).map { music(player.getMediaItemAt(it)) }
        val queued = userQueue(player)
        val known = (earlier + current + queued).mapTo(HashSet(), Music::id)
        val replacement = tracks.asSequence()
            .filter(Music::isPlayableTrack)
            .filter { it.id !in seenRadioRecommendationIds }
            .filter { known.add(it.id) }
            .toList()
        if (replacement.isEmpty()) return false

        if (ShuffleMemory.enabled) {
            val original = ShuffleMemory.original.orEmpty()
            val originalIndex = original.indexOfFirst { it.id == seedId }
            val originalEarlier = if (originalIndex >= 0) original.take(originalIndex) else earlier
            ShuffleMemory.original = originalEarlier + current + replacement
        }
        replaceAroundCurrent(player, earlier, replacement, queued)
        hasRadioRecommendations = true
        seenRadioRecommendationIds.addAll(replacement.map(Music::id))
        queueChanged = true
        update(player)
        return true
    }
    /** Adds a prefetched batch only if the same radio context is still at its expected tail. */
    fun appendRadioRecommendations(expectedTailId: String, tracks: List<Music>): Boolean {
        val player = controller ?: return false
        if (!hasRadioRecommendations) return false
        val currentContext = context().first
        if (currentContext.lastOrNull()?.id != expectedTailId) return false

        val known = currentContext.mapTo(HashSet(), Music::id)
        val added = tracks.asSequence()
            .filter(Music::isPlayableTrack)
            .filter { it.id !in seenRadioRecommendationIds }
            .filter { known.add(it.id) }
            .toList()
        if (added.isEmpty()) return false

        after = after + added
        ShuffleMemory.original = ShuffleMemory.original?.let { it + added }
        seenRadioRecommendationIds.addAll(added.map(Music::id))
        queueChanged = true
        update(player)
        return true
    }
    /** The whole context and the position of the current track in it. */
    fun context(): Pair<List<Music>, Int> = (before + queue) + after to before.size + mutableState.value.index
    /** Keeps a margin of tracks on both sides of the current one, and the player small. */
    private fun refill(player: Player) {
        val count = player.mediaItemCount
        if (count == 0) return
        val current = player.currentMediaItemIndex
        if (count - 1 - current < REFILL_AT) {
            // With "repeat all", the start of the context follows its end.
            val source = if (after.isEmpty() && player.repeatMode == Player.REPEAT_MODE_ALL) before else after
            if (source.isNotEmpty()) {
                val chunk = source.take(CHUNK)
                if (source === after) after = after.drop(CHUNK) else before = before.drop(CHUNK)
                player.addMediaItems(chunk.map(::mediaItem)); queueChanged = true
            }
        }
        if (current < REFILL_AT && before.isNotEmpty()) {
            val chunk = before.takeLast(CHUNK)
            before = before.dropLast(CHUNK)
            player.addMediaItems(0, chunk.map(::mediaItem)); queueChanged = true
        } else if (current > WINDOW_BEHIND + CHUNK) {
            val dropped = (0 until current - WINDOW_BEHIND).map { music(player.getMediaItemAt(it)) }
            before = before + dropped
            player.removeMediaItems(0, dropped.size); queueChanged = true
        }
    }
    fun toggle() {
        val player = controller ?: return
        togglePlayback(player)
    }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPrevious() }
    fun previousTrack() {
        val player = controller ?: return
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0)
    }
    fun seek(positionMs: Int) { controller?.let { player ->
        val failed = player.playerError != null
        player.seekTo(positionMs.coerceAtLeast(0).toLong())
        if (failed) player.prepare()
    } }
    fun shuffle() { controller?.let { player ->
        val queued = userQueue(player)
        val window = (0 until player.mediaItemCount).map { music(player.getMediaItemAt(it)) }
        val local = player.currentMediaItemIndex
        // The user's queue stays apart: never shuffled, always first after the current track.
        val items = before + window.take(local + 1) + window.drop(local + 1 + queued.size) + after
        val current = before.size + local
        ShuffleMemory.enabled = !ShuffleMemory.enabled
        ShuffleMemory.save(appContext)
        val original = ShuffleMemory.original
        when {
            items.isEmpty() -> ShuffleMemory.original = null
            ShuffleMemory.enabled -> {
                ShuffleMemory.original = items
                replaceAroundCurrent(player, emptyList(), (items.take(current) + items.drop(current + 1)).shuffled(), queued)
            }
            original != null -> {
                ShuffleMemory.original = null
                val position = original.indexOfFirst { it.id == items.getOrNull(current)?.id }
                if (position >= 0) replaceAroundCurrent(player, original.take(position), original.drop(position + 1), queued)
            }
        }
        queueChanged = true
        update(player)
    } }
    /** The current item is never removed, so its audio keeps playing while the queue changes around it. */
    private fun replaceAroundCurrent(player: Player, earlier: List<Music>, later: List<Music>, queued: List<Music> = emptyList()) {
        val current = player.currentMediaItemIndex
        if (current + 1 < player.mediaItemCount) player.removeMediaItems(current + 1, player.mediaItemCount)
        if (current > 0) player.removeMediaItems(0, current)
        val shownAfter = later.take(WINDOW_AHEAD)
        val shownBefore = earlier.takeLast(WINDOW_BEHIND)
        before = earlier.dropLast(WINDOW_BEHIND); after = later.drop(WINDOW_AHEAD)
        val following = queued.map { mediaItem(it, queued = true) } + shownAfter.map(::mediaItem)
        if (following.isNotEmpty()) player.addMediaItems(1, following)
        if (shownBefore.isNotEmpty()) player.addMediaItems(0, shownBefore.map(::mediaItem))
    }
    fun repeat() { controller?.let { it.repeatMode = (it.repeatMode + 1) % 3 } }
    fun stop() {
        pending = null; ShuffleMemory.original = null
        queueChanged = true
        before = emptyList(); after = emptyList()
        hasRadioRecommendations = false
        seenRadioRecommendationIds.clear()
        savedLists = null; savedCurrent = null
        LastQueue.clear(appContext)
        controller?.stop(); controller?.clearMediaItems(); mutableState.value = Playback(shuffle = ShuffleMemory.enabled)
    }
    fun pause() { controller?.pause() }
    fun setVolume(volume: Float) { controller?.volume = volume }
    fun volume(): Float = controller?.volume ?: 1f
    fun seekToItem(index: Int) { controller?.let { player ->
        val failed = player.playerError != null
        player.seekToDefaultPosition(index)
        if (failed) player.prepare()
        player.play()
    } }
    fun insert(index: Int, tracks: List<Music>) {
        val valid = tracks.filter(Music::isPlayableTrack)
        if (valid.isEmpty()) return
        controller?.let {
            it.addMediaItems(index.coerceIn(0, it.mediaItemCount), valid.map(::mediaItem))
            queueChanged = true; update(it)
        }
    }
    fun removeAt(index: Int) { controller?.let { player ->
        val id = player.getMediaItemAt(index).mediaId
        ShuffleMemory.original = ShuffleMemory.original?.let { list ->
            list.indexOfFirst { it.id == id }.let { found -> if (found < 0) list else list.take(found) + list.drop(found + 1) }
        }
        player.removeMediaItem(index); queueChanged = true; update(player)
    } }
    fun move(from: Int, to: Int) { controller?.let { it.moveMediaItem(from, to); queueChanged = true; update(it) } }
    fun clearExceptCurrent() { controller?.let { player ->
        val current = player.currentMediaItemIndex
        if (current + 1 < player.mediaItemCount) player.removeMediaItems(current + 1, player.mediaItemCount)
        if (current > 0) player.removeMediaItems(0, current)
        ShuffleMemory.original = null
        before = emptyList(); after = emptyList()
        hasRadioRecommendations = false
        seenRadioRecommendationIds.clear()
        queueChanged = true
        update(player)
    } }
    private fun update(player: Player) {
        refill(player)
        if (queueChanged || queue.size != player.mediaItemCount) {
            queue = (0 until player.mediaItemCount).map { music(player.getMediaItemAt(it)) }
            queueChanged = false
        }
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val duration = player.duration.takeUnless { it == C.TIME_UNSET || it < 0 }
            ?: queue.getOrNull(index)?.durationMs?.toLong() ?: 0
        val remainingContextTracks = (player.mediaItemCount - player.currentMediaItemIndex - 1)
            .coerceAtLeast(0) + after.size
        val contextTailId = after.lastOrNull()?.id ?: queue.lastOrNull()?.id
        val error = player.playerError?.let { failure ->
            val notFound = generateSequence<Throwable>(failure) { it.cause }.take(12)
                .filterIsInstance<MusicServerException>().any { it.failure == MusicServerFailure.NoMatch }
            val message = when {
                !`in`.melo.data.NetworkMonitor.online.value -> R.string.playback_error_offline
                notFound -> R.string.music_server_no_match
                else -> playbackErrorResource(failure.errorCode)
            }
            AppLocales.wrap(appContext).getString(message)
        }
        mutableState.value = Playback(queue, index, player.isPlaying, player.playbackState == Player.STATE_BUFFERING,
            player.currentPosition.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ShuffleMemory.enabled, player.repeatMode, error, player.hasNextMediaItem() || after.isNotEmpty(), player.playWhenReady, currentAudioFormat(player),
            userQueue(player).size, hasRadioRecommendations, remainingContextTracks, contextTailId)
        remember(player)
    }
    /** What the queue looked like when it was last written down, compared by identity. */
    private var savedLists: Triple<List<Music>, List<Music>, List<Music>>? = null
    private var savedCurrent: String? = null
    private var savedPosition = -1L
    private var savedPlaying = false
    /** Writes the queue down when it or the current track changes, and the position every few seconds. */
    private fun remember(player: Player) {
        val current = player.currentMediaItem?.mediaId ?: return
        val lists = savedLists
        if (lists == null || lists.first !== before || lists.second !== queue || lists.third !== after || current != savedCurrent) {
            savedLists = Triple(before, queue, after)
            savedCurrent = current
            val window = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            val entries = before.map { LastQueue.Entry(it, false) } +
                queue.mapIndexed { i, track -> LastQueue.Entry(track, window.getOrNull(i)?.isQueued() == true) } +
                after.map { LastQueue.Entry(it, false) }
            LastQueue.saveQueue(appContext, entries, before.size + player.currentMediaItemIndex)
        }
        val position = player.currentPosition
        val playing = player.isPlaying
        // Every few seconds while playing, and straight away when playback stops or seeks.
        if (playing != savedPlaying || kotlin.math.abs(position - savedPosition) >= if (playing) 4_000 else 1) {
            savedPlaying = playing
            savedPosition = position
            LastQueue.saveProgress(appContext, current, position)
        }
    }
    private fun mediaItem(track: Music, queued: Boolean = false): MediaItem =
        mediaItem(track, queued, MusicServer.get(appContext).library.value)
    private companion object {
        const val WINDOW_BEHIND = 20
        const val WINDOW_AHEAD = 100
        const val REFILL_AT = 10
        const val CHUNK = 50
    }
    override fun close() { controller?.removeListener(listener); MediaController.releaseFuture(future); controller = null }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun currentAudioFormat(player: Player): String {
    val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return ""
    val format = (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) ?: return ""
    return `in`.melo.data.audioQualityLabel(format.sampleMimeType, format.sampleRate.takeIf { it > 0 }, format.bitrate.takeIf { it > 0 })
}
internal fun togglePlayback(player: Player) {
    if (player.playerError != null) { player.prepare(); player.play() }
    // A buffering player may already have playWhenReady=true without producing audio yet.
    // Treat the control as Play until playback is actually advancing, so tapping it cannot
    // cancel the pending start.
    else if (player.isPlaying && player.playbackState != Player.STATE_BUFFERING) player.pause() else {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }
}
@StringRes
internal fun playbackErrorResource(code: Int): Int = when (code) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_TIMEOUT -> R.string.playback_error_connection
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> R.string.playback_error_stream
    PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
    PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED -> R.string.playback_error_authorization
    PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION -> R.string.playback_error_spotify_policy
    PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
    PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
    PlaybackException.ERROR_CODE_DECODING_FAILED -> R.string.playback_error_decode
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> R.string.playback_error_output
    else -> R.string.playback_error_generic
}
private fun Music.isPlayableTrack(): Boolean = kind == MusicKind.Track &&
    (id.startsWith("spotify:track:") ||
        `in`.melo.data.addons.AddonUris.isAddonTrack(id) ||
        `in`.melo.data.server.MusicServerUris.isTrack(id) || (isLocalTrack && mediaUri != null))

/** Set on the tracks the user queued ("Add to queue", "Play next"). */
private const val QUEUED = "queued"
private fun MediaItem.isQueued(): Boolean = mediaMetadata.extras?.getBoolean(QUEUED) == true

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun mediaItem(track: Music, queued: Boolean, library: String?): MediaItem {
    val server = MusicServerUris.playback(track, library)
    return MediaItem.Builder().setMediaId(track.id)
        .setUri(server ?: `in`.melo.data.addons.AddonUris.uriOf(track.id) ?: track.mediaUri?.let(Uri::parse) ?: Uri.parse(track.id))
        .apply { if (server != null) setCustomCacheKey(AudioCache.key(MusicServerUris.fileKey(server))) }
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.subtitle)
            .setArtworkUri(`in`.melo.data.largestSpotifyImage(track.imageUrl)?.let(Uri::parse)).setIsPlayable(true).setIsBrowsable(false)
            .setExtras(Bundle().apply {
                putInt("duration", track.durationMs); putInt("artwork", track.artwork)
                putString("album", track.album); putString("mediaUri", track.mediaUri); putString("quality", track.quality)
                if (queued) putBoolean(QUEUED, true)
            }).build()).build()
}
private fun music(item: MediaItem) = musicOf(item)
