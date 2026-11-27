package `in`.melo.data.addons

import `in`.melo.BuildConfig
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * One add-on server and the three questions Melo asks it: what it is, what it
 * holds for a query, and where one of its tracks can be streamed from.
 *
 * One instance is kept per configured add-on, so answers are shared across
 * tracks for a short while. A request already on its way is joined rather than
 * sent again: the add-on did the work, and a caller giving up early should not
 * make the next one ask twice.
 */
class AddonClient(rawBaseUrl: String, private val pickedQuality: String? = null) {

    /** The quality pick this client was built with. */
    val picked: String? get() = pickedQuality

    /** Where the add-on lives, without `/manifest.json` or trailing slashes. */
    val baseUrl: String = normalizeBase(rawBaseUrl)

    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manifests = SharedCalls<AddonManifest>(MANIFEST_TTL_MS, calls)
    private val searches = SharedCalls<AddonSearchResponse>(SEARCH_TTL_MS, calls)
    private val streams = SharedCalls<AddonStream>(STREAM_TTL_MS, calls)

    /**
     * When the add-on may be asked again after answering 429. Held for the server
     * rather than for one request, so the calls queued behind it wait as well.
     */
    @Volatile private var quietUntilMs = 0L

    /** The add-on's manifest, or the reason it cannot be used. */
    suspend fun manifest(): Result<AddonManifest> = manifests.get(baseUrl) {
        fetch<AddonManifest>(manifestUrl(baseUrl)).mapCatching { manifest ->
            if (manifest.id.isBlank()) throw AddonException(AddonProblem.NotAnAddon)
            if (!manifest.isPlayable) throw AddonException(AddonProblem.NoSearch)
            manifest
        }.recoverCatching { failure ->
            // A missing manifest means there is no add-on here, not a missing track.
            if (failure is AddonNotFound) throw AddonException(AddonProblem.NoManifest)
            throw failure
        }
    }

    /**
     * Whether `/search` answers, without a manifest: an add-on that never published
     * one still works. Zero tracks is a working answer, so read the [Result].
     */
    suspend fun probeSearch(): Result<Int> =
        fetch<AddonSearchResponse>(endpoint(listOf("search"), mapOf("q" to PROBE_QUERY))).map { it.tracks.size }

    /**
     * Tracks the add-on holds for [query]. [title] and [artist] go along on their own
     * as well, so an add-on that reads them can tell which words name the singer.
     */
    suspend fun search(query: String, tier: String, title: String = "", artist: String = ""): Result<List<AddonTrack>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return Result.success(emptyList())
        val params = settingsFor(tier) +
            listOf("title" to title.trim(), "artist" to artist.trim()).filter { it.second.isNotEmpty() }
        return searches.get(keyOf(trimmed, params.toString())) {
            fetch<AddonSearchResponse>(endpoint(listOf("search"), params + ("q" to trimmed)))
        }.map { it.tracks }
    }

    /**
     * Where one of the add-on's own track ids can be played from. The id goes in as
     * a path segment, so an id holding `/` or `?` cannot turn into another endpoint.
     */
    suspend fun stream(trackId: String, tier: String): Result<AddonStream> {
        val params = settingsFor(tier)
        return streams.get(keyOf(trackId, params.toString())) {
            fetch<AddonStream>(endpoint(listOf("stream", trackId), params))
        }
    }

    /**
     * The query parameters sent with every request. The add-on's declared defaults
     * go first, as an unchanged host would send them. Then `quality`: the listener's
     * pick while the add-on still lists it, else its first option. The value comes
     * from the add-on's own list, since an add-on offering `max|high|low` does not
     * understand Melo's tier names; those are sent only when it lists nothing.
     */
    private suspend fun settingsFor(tier: String): Map<String, String> {
        val manifest = manifest().getOrNull()
        val declared = manifest?.settings.orEmpty()
        val params = LinkedHashMap<String, String>()
        declared.forEach { setting ->
            val key = setting.key.takeIf { it.isNotBlank() } ?: return@forEach
            setting.defaultValue?.let { params[key] = it }
        }
        if (tier.isNotBlank()) {
            val options = declared.firstOrNull { it.key == QUALITY_KEY }?.options.orEmpty().mapNotNull { it.stringValue }
            val chosen = when {
                tier != TIER_LOSSLESS -> matchTier(tier, options)
                manifest == null -> pickedQuality
                else -> pickedQuality?.takeIf { it in options } ?: options.firstOrNull()
            }
            params[QUALITY_KEY] = chosen ?: tier
        }
        return params
    }

    /** The option that best answers a request below the top tier, read from what each option says. */
    private fun matchTier(tier: String, options: List<String>): String? {
        if (options.isEmpty()) return null
        options.firstOrNull { it.equals(tier, ignoreCase = true) }?.let { return it }
        val wanted = if (tier.equals(TIER_LOW, ignoreCase = true)) LOW_WORDS else HIGH_WORDS
        return options.firstOrNull { option -> wanted.any { it in option.lowercase() } } ?: options.first()
    }

    private fun endpoint(segments: List<String>, params: Map<String, String>): String {
        val builder = (baseUrl.toHttpUrlOrNull() ?: throw AddonException(AddonProblem.BadAddress)).newBuilder()
        segments.forEach(builder::addPathSegment)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    private suspend inline fun <reified T> fetch(url: String): Result<T> =
        runCatching { JSON.decodeFromString<T>(body(url)) }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                if (failure !is AddonNotFound) Log.w(TAG, "add-on call failed ${redact(url)}: ${failure.message}")
            }

    private suspend fun body(url: String): String = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            (quietUntilMs - System.currentTimeMillis()).takeIf { it > 0 }?.let { delay(it) }
            val request = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build()
            val wait = client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful ->
                        return@withContext response.body?.string()?.takeIf { it.isNotBlank() }
                            ?: throw AddonException(AddonProblem.EmptyAnswer)
                    response.code == 401 -> throw AddonException(AddonProblem.NeedsAttestation)
                    response.code == 404 -> throw AddonNotFound()
                    response.code >= 500 -> throw AddonUnavailable("HTTP ${response.code}")
                    response.code != 429 -> throw AddonException(AddonProblem.Refused, "HTTP ${response.code}")
                    attempt >= MAX_RETRIES -> throw AddonUnavailable("rate limited")
                    else -> retryAfterMs(response.header("Retry-After"), attempt)
                }
            }
            quietUntilMs = System.currentTimeMillis() + wait
            attempt++
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }

    private fun retryAfterMs(header: String?, attempt: Int): Long {
        val stated = header?.trim()?.toDoubleOrNull()?.times(1000)?.toLong()
        return (stated ?: (BACKOFF_BASE_MS shl attempt)).coerceIn(BACKOFF_BASE_MS, BACKOFF_CAP_MS)
    }

    /** Forgets every answer, for a Test button that must really ask. */
    fun clear() {
        manifests.clear(); searches.clear(); streams.clear()
        quietUntilMs = 0L
    }

    private fun keyOf(vararg parts: String) = parts.joinToString("|") { "${it.length}:$it" }

    companion object {
        private const val TAG = "MeloAddons"

        const val TIER_LOSSLESS = "LOSSLESS"
        const val TIER_HIGH = "HIGH"
        const val TIER_LOW = "LOW"
        internal const val QUALITY_KEY = "quality"

        private val HIGH_WORDS = listOf("high", "320", "normal", "standard")
        private val LOW_WORDS = listOf("low", "96", "128", "min")

        const val MANIFEST_TTL_MS = 10 * 60 * 1000L
        const val SEARCH_TTL_MS = 10 * 60 * 1000L
        /** Shorter: stream URLs are often signed and expire. */
        const val STREAM_TTL_MS = 5 * 60 * 1000L
        private const val MAX_RETRIES = 2
        private const val BACKOFF_BASE_MS = 500L
        private const val BACKOFF_CAP_MS = 8_000L
        private const val PROBE_QUERY = "music"

        private val USER_AGENT = "Melo/${BuildConfig.VERSION_NAME}"

        internal val JSON = Json {
            isLenient = true
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        /** Melo's client, so offline mode stops add-ons too; longer waits, since add-ons resolve upstream. */
        internal val client: OkHttpClient by lazy {
            `in`.melo.data.CatalogHttp.client.newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build()
        }

        /** Trims the address and drops a trailing slash or a pasted `manifest.json`. */
        fun normalizeBase(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            val lastSegment = trimmed.substringAfterLast('/', "")
            return if (lastSegment.endsWith(".json", ignoreCase = true) && trimmed.contains("://")) {
                trimmed.dropLast(lastSegment.length).trimEnd('/')
            } else {
                trimmed
            }
        }

        fun manifestUrl(base: String): String = "$base/manifest.json"

        /** Scheme and host only: add-on addresses often carry a personal token in the path. */
        fun redact(url: String): String = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/***" } ?: "***"
    }
}

/** Why an add-on could not be used, so the screen can say it in the listener's language. */
enum class AddonProblem { NotAnAddon, NoSearch, NoManifest, BadAddress, EmptyAnswer, NeedsAttestation, Refused }

class AddonException(val problem: AddonProblem, detail: String? = null) : Exception(detail ?: problem.name)

class AddonUnavailable(message: String) : Exception(message)

class AddonNotFound : Exception("Not held by this add-on")

/**
 * Answers kept for [ttlMs], with a request in flight joined by later callers.
 * Failures are not kept, so the next caller asks again.
 */
internal class SharedCalls<T>(private val ttlMs: Long, private val scope: CoroutineScope) {
    private class Done<T>(val result: Result<T>, val atMs: Long)

    private val entries = HashMap<String, Deferred<Done<T>>>()

    suspend fun get(key: String, load: suspend () -> Result<T>): Result<T> {
        val call = synchronized(entries) {
            val now = System.currentTimeMillis()
            entries.values.removeAll { call ->
                val done = call.finished() ?: return@removeAll false
                done.result.isFailure || now - done.atMs > ttlMs
            }
            entries.getOrPut(key) {
                scope.async { Done(runCatching { load().getOrThrow() }, System.currentTimeMillis()) }
            }
        }
        return call.await().result
    }

    private fun Deferred<Done<T>>.finished(): Done<T>? =
        if (isCompleted && !isCancelled) runCatching { @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) getCompleted() }.getOrNull() else null

    fun clear() = synchronized(entries) { entries.clear() }
}
