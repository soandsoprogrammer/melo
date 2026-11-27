package `in`.melo.data.discord

import android.content.Context
import `in`.melo.R
import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage
import java.util.Locale

/** What the presence card needs to know about the playing track. */
data class DiscordTrack(
    val title: String,
    val artist: String,
    val album: String,
    /** Cover art; Discord can only show an http(s) URL. */
    val artworkUrl: String?,
    /** A publicly shareable Spotify track ID, when available. */
    val trackId: String?,
)

/**
 * Publishes what's playing to Discord as a Rich Presence activity.
 *
 * Discord has no API for a third-party app to set a user's presence, so this signs
 * in with the account's own token over the gateway its Android client uses (see
 * [SuperProperties]). The settings page warns about this before asking for a login.
 *
 * ```
 *   Listening to Melo              <- activityName, or the app's name
 *   +----+  Song title             <- details
 *   |art |  Artist                 <- state
 *   +----+  Hi-Res Lossless · FLAC · 24-bit · 96 kHz
 *           ------ 1:04 / 3:47     <- from the timestamps
 *   [ Listen on Spotify ]    <- button 1
 *   [ Visit Melo              ]    <- button 2
 * ```
 */
class DiscordRPC(
    val context: Context,
    token: String,
) : KizzyRPC(
    token = token,
    os = "Android",
    browser = "Discord Android",
    device = android.os.Build.DEVICE,
    userAgent = SuperProperties.userAgent,
    superPropertiesBase64 = SuperProperties.superPropertiesBase64,
) {
    /**
     * Pushes [track] as the current activity.
     *
     * The position becomes a start/end timestamp pair, which Discord counts down
     * on its own clock, so a presence only needs re-sending when something changes.
     * [playbackSpeed] is divided out of both: at 1.5x the wall-clock time left is
     * not the media time left.
     */
    suspend fun updateSong(
        track: DiscordTrack,
        currentPlaybackTimeMillis: Long,
        durationMillis: Long,
        playbackSpeed: Float = 1.0f,
        useDetails: Boolean = false,
        status: String = "online",
        button1Text: String = "",
        button1Visible: Boolean = true,
        button2Text: String = "",
        button2Visible: Boolean = true,
        activityType: String = "listening",
        activityName: String = "",
        audioQuality: String? = null,
    ) = runCatching {
        val currentTime = System.currentTimeMillis()
        val adjustedPlaybackTime = (currentPlaybackTimeMillis / playbackSpeed).toLong()
        val calculatedStartTime = currentTime - adjustedPlaybackTime

        val titleWithRate = if (playbackSpeed != 1.0f) {
            "${track.title} [${String.format(Locale.ROOT, "%.2fx", playbackSpeed)}]"
        } else {
            track.title
        }

        val remainingDuration = (durationMillis - currentPlaybackTimeMillis).coerceAtLeast(0L)
        val adjustedRemainingDuration = (remainingDuration / playbackSpeed).toLong()

        val watch = watchUrl(track)
        val buttonsList = mutableListOf<Pair<String, String>>()
        // A device file has no page to open.
        if (button1Visible && watch != null) {
            buttonsList.add(resolveVariables(button1Text.ifEmpty { DEFAULT_BUTTON_1 }, track) to watch)
        }
        if (button2Visible) {
            buttonsList.add(resolveVariables(button2Text.ifEmpty { DEFAULT_BUTTON_2 }, track) to PROJECT_URL)
        }

        val type = when (activityType) {
            "playing" -> Type.PLAYING
            "watching" -> Type.WATCHING
            "competing" -> Type.COMPETING
            else -> Type.LISTENING
        }

        setActivity(
            name = activityName.ifEmpty { appName() },
            details = titleWithRate,
            state = track.artist,
            detailsUrl = watch,
            // Anything that is not an http URL (a device file's artwork) sends
            // none, and Discord draws the application's icon instead.
            // Discord shows an outside image only once an application has mirrored it.
            // With no application a raw URL draws a broken "?" tile, so none is sent.
            largeImage = track.artworkUrl?.takeIf { it.startsWith("http") && DiscordApplication.ID.isNotBlank() }
                ?.let(RpcImage::ExternalImage),
            smallImage = null,
            // The card's third line, absent for ordinary lossy streams.
            largeText = audioQuality,
            smallText = null,
            buttons = buttonsList.ifEmpty { null },
            type = type,
            statusDisplayType = if (useDetails) StatusDisplayType.DETAILS else StatusDisplayType.STATE,
            since = currentTime,
            startTime = calculatedStartTime,
            endTime = currentTime + adjustedRemainingDuration,
            // An empty id would be sent as is and refused, so none until one exists.
            applicationId = DiscordApplication.ID.ifBlank { null },
            status = status,
        )
    }

    private fun appName(): String = context.getString(R.string.app_name).removeSuffix(" Dev")

    companion object {
        const val PROJECT_URL = "https://github.com/aarush-io/melo"
        const val DEFAULT_BUTTON_1 = "Listen on Spotify"
        const val DEFAULT_BUTTON_2 = "Visit Melo"

        fun watchUrl(track: DiscordTrack): String? =
            track.trackId?.let { "https://open.spotify.com/track/" + it.removePrefix("spotify:track:") }

        /** Replaces {song_name}, {artist_name} and {album_name}. */
        fun resolveVariables(text: String, track: DiscordTrack): String = text
            .replace("{song_name}", track.title)
            .replace("{artist_name}", track.artist)
            .replace("{album_name}", track.album)
    }
}
