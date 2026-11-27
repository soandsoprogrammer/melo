package `in`.melo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Turns Spotify share URLs, short links, and `spotify:` URIs into Melo catalogue items. */
internal suspend fun resolveSpotifyDeepLink(input: String): Music? {
    val candidates = sequenceOf(input.trim(), SPOTIFY_SHARED_LINK.find(input)?.value)
        .filterNotNull().distinct()
    for (candidate in candidates) {
        spotifyMusicFromLink(candidate)?.let { return it }

        val shortUrl = candidate.toHttpUrlOrNull() ?: continue
        if (!shortUrl.isHttps || shortUrl.host !in SPOTIFY_SHORT_LINK_HOSTS) continue
        val destination = withContext(Dispatchers.IO) {
            `in`.melo.data.CatalogHttp.client.newCall(
                Request.Builder().url(shortUrl).get().build(),
            ).execute().use { response -> response.request.url.toString() }
        }
        spotifyMusicFromLink(destination)?.let { return it }
    }
    return null
}

private fun spotifyMusicFromLink(input: String): Music? {
    val text = input.trim()
    val canonical = canonicalMusicUri(text.substringBefore('?').substringBefore('#'))
    if (canonical != null) return spotifyMusic(canonical)

    val url = text.toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.host !in SPOTIFY_LINK_HOSTS) return null

    val segments = url.pathSegments.filter(String::isNotBlank).toMutableList()
    if (segments.firstOrNull()?.startsWith("intl-") == true) segments.removeAt(0)
    if (segments.firstOrNull() == "embed") segments.removeAt(0)

    val kind: String
    val id: String
    if (segments.firstOrNull() == "user" && segments.getOrNull(2) == "playlist") {
        kind = "playlist"
        id = segments.getOrNull(3) ?: return null
    } else {
        kind = segments.getOrNull(0) ?: return null
        id = segments.getOrNull(1) ?: return null
    }
    return canonicalMusicUri("spotify:$kind:$id")?.let(::spotifyMusic)
}

private fun spotifyMusic(uri: String): Music {
    val kind = when (uri.substringAfter("spotify:").substringBefore(':')) {
        "track" -> MusicKind.Track
        "album" -> MusicKind.Album
        "artist" -> MusicKind.Artist
        else -> MusicKind.Playlist
    }
    return Music(id = uri, title = "", subtitle = "", kind = kind)
}

private val SPOTIFY_LINK_HOSTS = setOf("open.spotify.com")
private val SPOTIFY_SHORT_LINK_HOSTS = setOf("spotify.link")
private val SPOTIFY_SHARED_LINK = Regex(
    """(?i)(?:https://(?:open\.spotify\.com|spotify\.link)/[^\s<>"']+|spotify:(?:track|album|artist|playlist):[A-Za-z0-9]{21,22})""",
)
