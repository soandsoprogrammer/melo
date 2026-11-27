package `in`.melo.data

import androidx.annotation.StringRes
import `in`.melo.R
import `in`.melo.data.server.MusicServerUris

enum class MusicKind(@StringRes val labelRes: Int) {
    Track(R.string.search_history_kind_track), Album(R.string.search_history_kind_album), Playlist(R.string.search_history_kind_playlist), Artist(R.string.search_history_kind_artist), Radio(R.string.radio)
}

data class Music(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: MusicKind,
    val artwork: Int = 0,
    val imageUrl: String? = null,
    val audio: Int? = null,
    val durationMs: Int = 0,
    val tracks: List<String> = emptyList(),
    val genre: String = "",
    val note: String = "",
    val album: String = "",
    /** Playable location of a local file; Spotify tracks resolve theirs from [id]. */
    val mediaUri: String? = null,
    /** Known audio format, shown when the quality setting is on. */
    val quality: String = "",
    val ownerUsername: String? = null,
)

/** Where a track's audio comes from. */
enum class MusicSource { Local, Spotify, Addon, MusicServer }

val Music.source: MusicSource get() = musicSourceOf(id)

fun musicSourceOf(id: String): MusicSource = when {
    MusicServerUris.isTrack(id) || MusicServerUris.isPlaylist(id) -> MusicSource.MusicServer
    isLocalTrackId(id) -> MusicSource.Local
    `in`.melo.data.addons.AddonUris.isAddonTrack(id) -> MusicSource.Addon
    else -> MusicSource.Spotify
}
