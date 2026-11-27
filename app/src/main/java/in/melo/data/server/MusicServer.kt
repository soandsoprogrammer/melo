package `in`.melo.data.server

import android.content.Context
import android.net.Uri
import android.util.LruCache
import `in`.melo.data.AudioFileRecord
import `in`.melo.data.AudioIndex
import `in`.melo.data.CatalogDetail
import `in`.melo.data.ConversionMatching
import `in`.melo.data.Music
import `in`.melo.data.MusicKind
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

internal class MusicServerAccount(val url: HttpUrl, val username: String, val password: String) {
    val libraryKey = "${url}\n$username".digest("SHA-256").take(24)
}

internal data class ServerAlbum(val id: String, val title: String, val artist: String, val artwork: String?)
internal data class ServerPage(val albums: List<ServerAlbum> = emptyList(), val songs: List<Music> = emptyList()) {
    val isEmpty get() = albums.isEmpty() && songs.isEmpty()
}

internal object MusicServerUris {
    const val PREFIX = "music-server:"
    const val PLAYLIST_PREFIX = "music-server-playlist:"
    private const val HOST = "melo-music-server.invalid"
    fun isTrack(id: String) = id.startsWith(PREFIX)
    fun isPlaylist(id: String) = id.startsWith(PLAYLIST_PREFIX)
    fun isRequest(url: HttpUrl) = url.host == HOST
    fun isPlayback(uri: Uri?) = uri != null && uri.host == HOST && uri.pathSegments.firstOrNull() in setOf("stream", "match")
    fun fileKey(uri: Uri) = "server-" + uri.toString().digest("SHA-256")

    fun artwork(library: String, id: String): String = url("cover", library, id).toString()
    fun stream(id: String): Uri? {
        if (!isTrack(id)) return null
        val key = id.removePrefix(PREFIX)
        val library = key.substringBefore(':', "")
        val track = key.substringAfter(':', "")
        if (library.isEmpty() || track.isEmpty()) return null
        return Uri.parse(url("stream", library, track).toString())
    }

    fun playback(track: Music, library: String?): Uri? = stream(track.id) ?: library?.let {
        if (!track.id.startsWith("spotify:track:") || track.title.isBlank()) return@let null
        Uri.parse(url("match", it, track.id).newBuilder()
            .addQueryParameter("title", track.title)
            .addQueryParameter("artist", track.subtitle)
            .addQueryParameter("album", track.album)
            .addQueryParameter("duration", track.durationMs.toString()).build().toString())
    }

    private fun url(type: String, library: String, id: String): HttpUrl =
        HttpUrl.Builder().scheme("https").host(HOST).addPathSegment(type)
            .addPathSegment(library).addPathSegment(id).build()
}

internal enum class MusicServerFailure { Address, Credentials, Connection, Response, NoMatch }

internal class MusicServerException(val failure: MusicServerFailure) : IOException(failure.name)

/** OpenSubsonic at the boundary. Track and artwork identifiers elsewhere never carry credentials. */
internal class MusicServer private constructor(context: Context) {
    private val app = context.applicationContext
    private val credentials = MusicServerCredentials(context)
    private var activeAccount: MusicServerAccount? = credentials.read()
    private val mutableLibrary = kotlinx.coroutines.flow.MutableStateFlow(activeAccount?.libraryKey)
    val library: kotlinx.coroutines.flow.StateFlow<String?> = mutableLibrary
    private val random = SecureRandom()
    private val matchedTracks = LruCache<String, String>(500)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    val account: MusicServerAccount?
        @Synchronized get() = activeAccount

    suspend fun signIn(address: String, username: String, password: String): MusicServerAccount =
        withContext(Dispatchers.IO) {
            val url = normalizeAddress(address)
            val name = username.trim()
            if (name.isEmpty() || password.isEmpty()) throw MusicServerException(MusicServerFailure.Credentials)
            val tested = MusicServerAccount(url, name, password)
            request(tested, "ping")
            ensureActive()
            synchronized(this@MusicServer) {
                credentials.write(tested)
                activeAccount = tested
                matchedTracks.evictAll()
                mutableLibrary.value = tested.libraryKey
            }
            tested
        }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        synchronized(this@MusicServer) {
            credentials.clear()
            activeAccount = null
            matchedTracks.evictAll()
            mutableLibrary.value = null
        }
    }

    suspend fun albums(offset: Int): ServerPage = withContext(Dispatchers.IO) {
        val session = requireAccount()
        val json = request(session, "getAlbumList2", "type" to "alphabeticalByName", "size" to PAGE_SIZE.toString(), "offset" to offset.toString())
        ServerPage(albums = json.optJSONObject("albumList2")?.objects("album").orEmpty().mapNotNull { album(session, it) })
    }

    suspend fun tracks(query: String, offset: Int): ServerPage = withContext(Dispatchers.IO) {
        val session = requireAccount()
        val json = request(
            session, "search3", "query" to query,
            "songCount" to PAGE_SIZE.toString(), "songOffset" to offset.toString(),
            "albumCount" to if (query.isBlank()) "0" else PAGE_SIZE.toString(),
            "albumOffset" to offset.toString(), "artistCount" to "0"
        ).optJSONObject("searchResult3")
        ServerPage(
            albums = json?.objects("album").orEmpty().mapNotNull { album(session, it) },
            songs = json?.objects("song").orEmpty().mapNotNull { song(session, it) }
        )
    }

    suspend fun albumTracks(id: String): ServerPage = withContext(Dispatchers.IO) {
        val session = requireAccount()
        val json = request(session, "getAlbum", "id" to id).optJSONObject("album")
        ServerPage(songs = json?.objects("song").orEmpty().mapNotNull { song(session, it) })
    }

    suspend fun playlists(): List<Music> = withContext(Dispatchers.IO) {
        val session = requireAccount()
        request(session, "getPlaylists").optJSONObject("playlists")?.objects("playlist").orEmpty()
            .mapNotNull { playlist(session, it) }
    }

    suspend fun starredTracks(): List<Music> = withContext(Dispatchers.IO) {
        val session = requireAccount()
        request(session, "getStarred2").optJSONObject("starred2")?.objects("song").orEmpty()
            .mapNotNull { song(session, it) }
    }

    suspend fun setStarred(track: Music, starred: Boolean) = withContext(Dispatchers.IO) {
        val session = requireAccount()
        request(session, if (starred) "star" else "unstar", "id" to serverId(session, track.id, MusicServerUris.PREFIX), write = true)
        Unit
    }

    suspend fun playlist(id: String): CatalogDetail = withContext(Dispatchers.IO) {
        val session = requireAccount()
        detail(session, request(session, "getPlaylist", "id" to serverId(session, id, MusicServerUris.PLAYLIST_PREFIX)))
    }

    fun ownsPlaylist(item: Music): Boolean = account?.let { session ->
        item.id.startsWith("${MusicServerUris.PLAYLIST_PREFIX}${session.libraryKey}:") && item.ownerUsername == session.username
    } ?: false

    suspend fun createPlaylist(name: String, tracks: List<Music>): CatalogDetail = withContext(Dispatchers.IO) {
        val session = requireAccount()
        val args = listOf("name" to name.trim()) + songIds(session, tracks).map { "songId" to it }
        detail(session, request(session, "createPlaylist", *args.toTypedArray(), write = true))
    }

    suspend fun updatePlaylist(item: Music, name: String? = null, tracks: List<Music> = emptyList(), remove: Int? = null) =
        withContext(Dispatchers.IO) {
            val session = requireAccount()
            val args = buildList {
                add("playlistId" to serverId(session, item.id, MusicServerUris.PLAYLIST_PREFIX))
                name?.let { add("name" to it.trim()) }
                addAll(songIds(session, tracks).map { "songIdToAdd" to it })
                remove?.let { add("songIndexToRemove" to it.toString()) }
            }
            request(session, "updatePlaylist", *args.toTypedArray(), write = true)
            Unit
        }

    suspend fun deletePlaylist(item: Music) = withContext(Dispatchers.IO) {
        val session = requireAccount()
        request(session, "deletePlaylist", "id" to serverId(session, item.id, MusicServerUris.PLAYLIST_PREFIX), write = true)
        Unit
    }

    private fun songIds(session: MusicServerAccount, tracks: List<Music>) =
        tracks.map { serverId(session, it.id, MusicServerUris.PREFIX) }

    private fun serverId(session: MusicServerAccount, id: String, kind: String): String {
        val prefix = "$kind${session.libraryKey}:"
        if (!id.startsWith(prefix) || id.length == prefix.length) throw MusicServerException(MusicServerFailure.Response)
        return id.removePrefix(prefix)
    }

    suspend fun lyrics(trackId: String): String? = withContext(Dispatchers.IO) {
        val session = requireAccount()
        val prefix = "${MusicServerUris.PREFIX}${session.libraryKey}:"
        val id = trackId.removePrefix(prefix)
        if (id == trackId || id.isEmpty()) return@withContext null
        val lists = request(session, "getLyricsBySongId", "id" to id)
            .optJSONObject("lyricsList")?.objects("structuredLyrics").orEmpty()
        val selected = lists.firstOrNull { it.optBoolean("synced") } ?: lists.firstOrNull() ?: return@withContext null
        val synced = selected.optBoolean("synced")
        selected.objects("line").joinToString("\n") { line ->
            val text = line.optString("value")
            if (synced && line.has("start")) {
                val ms = line.optLong("start").coerceAtLeast(0L)
                String.format(Locale.ROOT, "[%02d:%02d.%03d]%s", ms / 60000, ms / 1000 % 60, ms % 1000, text)
            } else text
        }.takeIf(String::isNotBlank)
    }

    /**
     * Resolve only the application's virtual addresses. Keep the synthetic URL on the
     * response so retry messages, media-session metadata and stored queues contain no auth.
     */
    fun resolveMedia(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.pathSegments
        val session = requireAccount()
        if (path.size != 3 || path[1] != session.libraryKey || path[2].isBlank()) {
            throw MusicServerException(MusicServerFailure.Credentials)
        }
        val id = path[2]
        val url = when (path[0]) {
            "stream" -> endpoint(session, "stream", "id" to id, "format" to "raw")
            "match" -> endpoint(session, "stream", "id" to matchServerTrack(session, original.url), "format" to "raw")
            "cover" -> endpoint(session, "getCoverArt", "id" to id, "size" to "700")
            else -> throw MusicServerException(MusicServerFailure.Response)
        }
        return try {
            http.newCall(original.newBuilder().url(url).build()).execute().let { response ->
                if (path[0] != "cover" && response.isSuccessful) {
                    val bytes = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                        ?: response.header("Content-Length")?.toLongOrNull().takeIf { response.code == 200 }
                    val trackId = if (path[0] == "match") id else "${MusicServerUris.PREFIX}${session.libraryKey}:$id"
                    if (bytes != null && bytes > 0) runCatching {
                        val key = MusicServerUris.fileKey(Uri.parse(original.url.toString()))
                        AudioIndex.get(app).attachDirectFile(trackId, AudioFileRecord(key, bytes, ""))
                    }
                }
                response.newBuilder().request(original).build()
            }
        } catch (_: IOException) {
            throw MusicServerException(MusicServerFailure.Connection)
        }
    }

    private fun matchServerTrack(session: MusicServerAccount, url: HttpUrl): String {
        val lookup = url.toString()
        matchedTracks.get(lookup)?.let { return it }
        val track = Music(url.pathSegments.last(), url.queryParameter("title").orEmpty(),
            url.queryParameter("artist").orEmpty(), MusicKind.Track, album = url.queryParameter("album").orEmpty(),
            durationMs = url.queryParameter("duration")?.toIntOrNull() ?: 0)
        val candidates = request(session, "search3", "query" to track.title, "songCount" to PAGE_SIZE.toString(),
            "albumCount" to "0", "artistCount" to "0").optJSONObject("searchResult3")?.objects("song").orEmpty()
            .mapNotNull { song(session, it) }
        val match = ConversionMatching.rank(track, candidates).firstOrNull()
            ?.takeIf { it.second >= ConversionMatching.CONFIDENT }?.first
            ?: throw MusicServerException(MusicServerFailure.NoMatch)
        return serverId(session, match.id, MusicServerUris.PREFIX).also { matchedTracks.put(lookup, it) }
    }

    private fun requireAccount() = account ?: throw MusicServerException(MusicServerFailure.Credentials)

    private fun request(account: MusicServerAccount, method: String, vararg args: Pair<String, String>, write: Boolean = false): JSONObject {
        val request = Request.Builder()
        if (write) {
            request.url(endpoint(account, method, "f" to "json"))
            request.post(FormBody.Builder().apply { args.forEach { (key, value) -> add(key, value) } }.build())
        } else request.url(endpoint(account, method, "f" to "json", *args))
        val response = try {
            http.newCall(request.build()).execute().use {
                if (it.code == 401 || it.code == 403) throw MusicServerException(MusicServerFailure.Credentials)
                if (!it.isSuccessful) throw MusicServerException(MusicServerFailure.Response)
                it.body?.string().orEmpty()
            }
        } catch (error: MusicServerException) {
            throw error
        } catch (_: IOException) {
            throw MusicServerException(MusicServerFailure.Connection)
        }
        val json = runCatching { JSONObject(response).getJSONObject("subsonic-response") }.getOrElse {
            throw MusicServerException(MusicServerFailure.Response)
        }
        if (json.optString("status") != "ok") {
            val code = json.optJSONObject("error")?.optInt("code")
            throw MusicServerException(if (code in setOf(40, 41, 42, 50)) MusicServerFailure.Credentials else MusicServerFailure.Response)
        }
        return json
    }

    private fun endpoint(account: MusicServerAccount, method: String, vararg args: Pair<String, String>): HttpUrl {
        val salt = ByteArray(16).also(random::nextBytes).hex()
        return account.url.newBuilder()
            .addPathSegments("rest/$method.view")
            .addQueryParameter("u", account.username)
            .addQueryParameter("t", "${account.password}$salt".digest("MD5"))
            .addQueryParameter("s", salt)
            .addQueryParameter("c", "melo-music-server")
            .addQueryParameter("v", "1.16.1")
            .apply { args.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
    }

    private fun normalizeAddress(address: String): HttpUrl {
        val trimmed = address.trim().trimEnd('/')
        val url = (if ("://" in trimmed) trimmed else "https://$trimmed").toHttpUrlOrNull()
        if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null || MusicServerUris.isRequest(url)) {
            throw MusicServerException(MusicServerFailure.Address)
        }
        return url
    }

    private fun album(session: MusicServerAccount, row: JSONObject): ServerAlbum? {
        val id = row.optString("id").takeIf(String::isNotBlank) ?: return null
        return ServerAlbum(id, row.optString("name", row.optString("title", id)), row.optString("artist"), cover(session, row))
    }

    private fun playlist(session: MusicServerAccount, row: JSONObject): Music? {
        val id = row.optString("id").takeIf(String::isNotBlank) ?: return null
        return Music(
            id = "${MusicServerUris.PLAYLIST_PREFIX}${session.libraryKey}:$id",
            title = row.optString("name", id),
            subtitle = `in`.melo.utils.formatSongCount(row.optInt("songCount")),
            kind = MusicKind.Playlist,
            imageUrl = cover(session, row),
            ownerUsername = row.optString("owner").takeIf(String::isNotBlank),
        )
    }

    private fun detail(session: MusicServerAccount, payload: JSONObject): CatalogDetail {
        val row = payload.optJSONObject("playlist") ?: throw MusicServerException(MusicServerFailure.Response)
        val item = playlist(session, row) ?: throw MusicServerException(MusicServerFailure.Response)
        val tracks = row.objects("entry").mapNotNull { song(session, it) }
        return CatalogDetail(item, tracks, total = tracks.size)
    }

    private fun song(session: MusicServerAccount, row: JSONObject): Music? {
        val remoteId = row.optString("id").takeIf(String::isNotBlank) ?: return null
        val seconds = row.optLong("duration").coerceIn(0, Int.MAX_VALUE.toLong() / 1000)
        val format = row.optString("suffix").uppercase(Locale.ROOT)
        val bitrate = row.optInt("bitRate").takeIf { it > 0 }?.let { "$it kbps" }
        return Music(
            id = "${MusicServerUris.PREFIX}${session.libraryKey}:$remoteId",
            title = row.optString("title", remoteId),
            subtitle = row.optString("artist"),
            album = row.optString("album"),
            kind = MusicKind.Track,
            imageUrl = cover(session, row),
            durationMs = (seconds * 1000).toInt(),
            quality = listOfNotNull(format.takeIf(String::isNotBlank), bitrate).joinToString(" · ")
        )
    }

    private fun cover(session: MusicServerAccount, row: JSONObject): String? =
        row.optString("coverArt").takeIf(String::isNotBlank)?.let { MusicServerUris.artwork(session.libraryKey, it) }

    companion object {
        const val PAGE_SIZE = 60
        @Volatile private var instance: MusicServer? = null
        fun get(context: Context): MusicServer = instance ?: synchronized(this) {
            instance ?: MusicServer(context.applicationContext).also { instance = it }
        }
    }
}

private fun JSONObject.objects(key: String): List<JSONObject> {
    val array = optJSONArray(key) ?: JSONArray()
    return (0 until array.length()).mapNotNull(array::optJSONObject)
}

private fun String.digest(algorithm: String): String =
    MessageDigest.getInstance(algorithm).digest(encodeToByteArray()).hex()

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
