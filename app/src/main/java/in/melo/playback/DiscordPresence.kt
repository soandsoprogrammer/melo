package `in`.melo.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import `in`.melo.data.NetworkMonitor
import `in`.melo.data.discord.DiscordAccountStore
import `in`.melo.data.discord.DiscordRPC
import `in`.melo.data.discord.DiscordTrack
import `in`.melo.data.isLocalTrackId
import `in`.melo.data.preferences.AppSettings
import `in`.melo.data.preferences.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the Discord presence in step with playback.
 *
 * Only a track change, a seek, a speed change or a pause needs a new presence:
 * between them Discord counts the bar down on its own clock from the timestamps
 * it was given. A pause clears the card, or it would carry on through a song
 * that has stopped.
 */
internal class DiscordPresence(
    private val context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val settings = SettingsStore.get(context)
    private val accounts = DiscordAccountStore.get(context)
    private var rpc: DiscordRPC? = null
    private var updateJob: Job? = null
    /** Whether a presence is up, so a pause with none to clear opens no connection. */
    private var presenceUp = false
    private var networkListener: AutoCloseable? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) push() else clear()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && player.isPlaying) push()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (player.isPlaying) push()
        }

        // The timestamps have the rate divided out, so a new rate invalidates them.
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (player.isPlaying) push()
        }
    }

    fun start() {
        player.addListener(listener)

        // The account, the switch and offline mode decide whether there is a
        // connection at all; everything else is a field of a payload that can be
        // re-sent over the connection already open.
        scope.launch {
            combine(accounts.account, settings.settings) { account, s ->
                account?.token?.takeIf { s.discordRpcEnabled && !s.offlineMode && it.isNotBlank() }
            }.distinctUntilChanged().collect { token ->
                // Torn down first, so switching accounts cannot leave the old
                // one's socket publishing under a profile that was disconnected.
                updateJob?.cancel()
                rpc?.let { old ->
                    val wasUp = presenceUp
                    presenceUp = false
                    rpc = null
                    // Bounded: closing waits for the clear frame to leave, and a
                    // hung teardown would read as a switch that stopped working.
                    withContext(Dispatchers.IO + NonCancellable) {
                        withTimeoutOrNull(TEARDOWN_TIMEOUT_MS) { if (wasUp) runCatching { old.close() } }
                        runCatching { old.closeRPC() }
                    }
                }
                if (token == null) return@collect
                rpc = DiscordRPC(context, token)
                // Otherwise turning the switch on would look like it did nothing for a whole song.
                if (player.isPlaying) push()
            }
        }

        scope.launch {
            settings.settings.map { it.cardFields() }.distinctUntilChanged()
                // The first value arrives at start, before anything changed.
                .drop(1)
                .collect { if (player.isPlaying) push() }
        }

        // With no card up (paused, stopped) a new status still goes out at once.
        scope.launch {
            settings.settings.map { it.discordStatus }.distinctUntilChanged().drop(1).collect { status ->
                val current = rpc ?: return@collect
                if (player.isPlaying) return@collect
                launch(Dispatchers.IO) { runCatching { current.setStatus(status) } }
            }
        }

        // A network coming back: the gateway heals itself, but its backoff climbs
        // to a minute. A socket may also not have noticed a network handover,
        // and the push is what makes it find out.
        networkListener = NetworkMonitor.onConnectionsInvalidated {
            scope.launch {
                val current = rpc ?: return@launch
                if (!NetworkMonitor.online.value) return@launch
                withContext(Dispatchers.IO) { runCatching { current.wakeUp() } }
                if (presenceUp && player.isPlaying) push()
            }
        }
    }

    private fun AppSettings.cardFields(): List<Any> = listOf(
        discordUseDetails, discordStatus, discordActivityType, discordActivityName,
        discordButton1Text, discordButton1Visible, discordButton2Text, discordButton2Visible,
        discordShowAudioQuality,
    )

    /** Publishes the current track. A no-op with no account connected, the usual case. */
    private fun push() {
        val current = rpc ?: return
        val item = player.currentMediaItem ?: return
        val track = item.toDiscordTrack() ?: return
        // Read here, on the player's thread: by the time the IO job runs the queue may have moved on.
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: item.mediaMetadata.extras?.getInt("duration")?.toLong()?.takeIf { it > 0 } ?: 0L
        val speed = player.playbackParameters.speed
        val s = settings.settings.value
        val audioQuality = discordAudioQualityLine().takeIf { s.discordShowAudioQuality }

        updateJob?.cancel()
        presenceUp = true
        updateJob = scope.launch(Dispatchers.IO) {
            current.updateSong(
                track = track,
                currentPlaybackTimeMillis = positionMs,
                durationMillis = durationMs,
                playbackSpeed = speed,
                useDetails = s.discordUseDetails,
                status = s.discordStatus,
                button1Text = s.discordButton1Text,
                button1Visible = s.discordButton1Visible,
                button2Text = s.discordButton2Text,
                button2Visible = s.discordButton2Visible,
                activityType = s.discordActivityType,
                activityName = s.discordActivityName,
                audioQuality = audioQuality,
            ).onFailure { Log.w(TAG, "Discord presence failed: ${it.message}") }
        }
    }

    /** Takes the card down but keeps the socket, so resuming needs no reconnect. */
    private fun clear() {
        val current = rpc ?: return
        if (!presenceUp) return
        presenceUp = false
        updateJob?.cancel()
        updateJob = scope.launch(Dispatchers.IO) { runCatching { current.close() } }
    }

    /** The measured format line, for lossless streams only. Lossy streams show none. */
    private fun discordAudioQualityLine(): String? = null

    private fun MediaItem.toDiscordTrack(): DiscordTrack? {
        val meta = mediaMetadata
        val title = meta.title?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return DiscordTrack(
            title = title,
            artist = meta.artist?.toString().orEmpty(),
            album = meta.extras?.getString("album").orEmpty(),
            artworkUrl = meta.artworkUri?.toString(),
            trackId = mediaId.takeIf { it.startsWith("spotify:track:") },
        )
    }

    override fun close() {
        player.removeListener(listener)
        networkListener?.close(); networkListener = null
        updateJob?.cancel()
        val old = rpc ?: return
        rpc = null
        val wasUp = presenceUp
        presenceUp = false
        // The service scope is about to be cancelled, so this outlives it on its own.
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            withTimeoutOrNull(TEARDOWN_TIMEOUT_MS) { if (wasUp) runCatching { old.close() } }
            runCatching { old.closeRPC() }
        }
    }

    private companion object {
        const val TAG = "MeloDiscord"
        const val TEARDOWN_TIMEOUT_MS = 2_000L
    }
}
