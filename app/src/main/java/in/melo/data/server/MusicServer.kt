package `in`.melo.data.server

import android.content.Context
import android.net.Uri
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
    private const val HOST = "melo-music-server.invalid"
    fun isTrack(id: String) = id.startsWith(PREFIX)
    fun isRequest(url: HttpUrl) = url.host == HOST

    fun artwork(library: String, id: String): String = url("cover", library, id).toString()
    fun stream(id: String): Uri? {
        if (!isTrack(id)) return null
        val key = id.removePrefix(PREFIX)
        val library = key.substringBefore(':', "")
        val track = key.substringAfter(':', "")
        if (library.isEmpty() || track.isEmpty()) return null
        return Uri.parse(url("stream", library, track).toString())
    }

    private fun url(type: String, library: String, id: String): HttpUrl =
        HttpUrl.Builder().scheme("https").host(HOST).addPathSegment(type)
            .addPathSegment(library).addPathSegment(id).build()
}

internal enum class MusicServerFailure { Address, Credentials, Connection, Response }

internal class MusicServerException(val failure: MusicServerFailure) : IOException(failure.name)

/** OpenSubsonic at the boundary. Track and artwork identifiers elsewhere never carry credentials. */
internal class MusicServer private constructor(context: Context) {
    private val credentials = MusicServerCredentials(context)
    private var activeAccount: MusicServerAccount? = credentials.read()
    private val random = SecureRandom()
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
            }
            tested
        }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        synchronized(this@MusicServer) {
            credentials.clear()
            activeAccount = null
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
            "cover" -> endpoint(session, "getCoverArt", "id" to id, "size" to "700")
            else -> throw MusicServerException(MusicServerFailure.Response)
        }
        return try {
            http.newCall(original.newBuilder().url(url).build()).execute()
                .newBuilder().request(original).build()
        } catch (_: IOException) {
            throw MusicServerException(MusicServerFailure.Connection)
        }
    }

    private fun requireAccount() = account ?: throw MusicServerException(MusicServerFailure.Credentials)

    private fun request(account: MusicServerAccount, method: String, vararg args: Pair<String, String>): JSONObject {
        val jsonUrl = endpoint(account, method, "f" to "json", *args)
        val response = try {
            http.newCall(Request.Builder().url(jsonUrl).build()).execute().use {
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
