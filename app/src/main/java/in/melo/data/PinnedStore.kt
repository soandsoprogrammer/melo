package `in`.melo.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Pins stay on this device: they never reach Spotify, and only hold what the UI needs to draw them. */
class PinnedStore(context: Context) {
    private val preferences = context.getSharedPreferences("pinned_items", Context.MODE_PRIVATE)
    private val mutablePins = MutableStateFlow(read())
    val pins: StateFlow<List<Music>> = mutablePins.asStateFlow()
    private val mutableHidden = MutableStateFlow(preferences.getStringSet(HIDDEN_KEY, null).orEmpty().toSet())
    /** Ids taken off the dials with "Remove from dial", kept off until pinned again. */
    val hiddenFromDial: StateFlow<Set<String>> = mutableHidden.asStateFlow()

    fun toggle(item: Music) {
        // Pinning something taken off the dial brings it back.
        if (item.id in mutableHidden.value) setHidden(mutableHidden.value - item.id)
        update { current ->
            if (current.any { it.id == item.id }) current.filterNot { it.id == item.id } else listOf(item) + current
        }
    }

    /**
     * Takes [item] off whichever dial it is on: unpinned, and kept off even where the
     * speed dial, what was played or the mixes would put it back, until it is pinned again.
     */
    fun removeFromDial(item: Music) {
        if (mutablePins.value.any { it.id == item.id }) update { current -> current.filterNot { it.id == item.id } }
        setHidden(mutableHidden.value + item.id)
    }

    private fun setHidden(value: Set<String>) {
        mutableHidden.value = value
        preferences.edit { putStringSet(HIDDEN_KEY, value) }
    }

    fun move(from: Int, to: Int) = update { current ->
        if (from !in current.indices || to !in current.indices) current
        else current.toMutableList().apply { add(to, removeAt(from)) }
    }

    private fun update(change: (List<Music>) -> List<Music>) {
        val next = change(mutablePins.value)
        mutablePins.value = next
        preferences.edit { putString(KEY, encodePins(next)) }
    }

    /**
     * The tiles dial [name] was last dealt and what they were dealt for, so the next launch shows
     * them at once instead of a dial that changes as the feed arrives.
     * Null before the first deal.
     */
    fun savedDial(name: String): Pair<String, List<Music>>? {
        val key = preferences.getString("$DIAL_PREFIX${name}_key", null) ?: return null
        return key to decodePins(preferences.getString("$DIAL_PREFIX${name}_items", null))
    }

    fun saveDial(name: String, key: String, items: List<Music>) = preferences.edit {
        putString("$DIAL_PREFIX${name}_key", key)
        putString("$DIAL_PREFIX${name}_items", encodePins(items))
    }

    /** Replaces every pin, for a restored backup. */
    fun replaceAll(items: List<Music>) = update { items }

    private fun read(): List<Music> = decodePins(preferences.getString(KEY, null))

    private companion object {
        const val KEY = "items"
        const val HIDDEN_KEY = "hidden_from_dial"
        const val DIAL_PREFIX = "saved_dial_"
    }
}

internal fun encodePins(items: List<Music>): String = JSONArray().apply {
    items.forEach { item ->
        put(JSONObject().apply {
            put("id", item.id)
            put("title", item.title)
            put("subtitle", item.subtitle)
            put("kind", item.kind.name)
            put("imageUrl", item.imageUrl ?: JSONObject.NULL)
            put("durationMs", item.durationMs)
            if (item.album.isNotEmpty()) put("album", item.album)
            if (item.mediaUri != null) put("mediaUri", item.mediaUri)
            if (item.quality.isNotEmpty()) put("quality", item.quality)
            item.ownerUsername?.let { put("ownerUsername", it) }
            if (item.note.isNotEmpty()) put("note", item.note)
            if (item.genre.isNotEmpty()) put("genre", item.genre)
            if (item.artwork != 0) put("artwork", item.artwork)
            item.audio?.let { put("audio", it) }
            if (item.tracks.isNotEmpty()) put("tracks", JSONArray(item.tracks))
        })
    }
}.toString()

internal fun decodePins(encoded: String?): List<Music> {
    if (encoded.isNullOrBlank()) return emptyList()
    return try {
        val array = JSONArray(encoded)
        (0 until array.length()).mapNotNull { index ->
            val entry = array.getJSONObject(index)
            val kind = MusicKind.entries.firstOrNull { it.name == entry.optString("kind") } ?: return@mapNotNull null
            Music(
                id = entry.getString("id"), title = entry.getString("title"),
                subtitle = `in`.melo.utils.localizeTrackCount(entry.optString("subtitle")), kind = kind,
                imageUrl = if (entry.isNull("imageUrl")) null else entry.getString("imageUrl"),
                durationMs = entry.optInt("durationMs"),
                album = entry.optString("album"),
                mediaUri = if (entry.has("mediaUri")) entry.getString("mediaUri") else null,
                quality = entry.optString("quality"),
                ownerUsername = entry.optString("ownerUsername").takeIf(String::isNotBlank),
                note = entry.optString("note"),
                genre = entry.optString("genre"),
                artwork = entry.optInt("artwork"),
                audio = if (entry.has("audio") && !entry.isNull("audio")) entry.getInt("audio") else null,
                tracks = entry.optJSONArray("tracks")?.let { list -> (0 until list.length()).map(list::getString) }.orEmpty(),
            )
        }
    } catch (_: org.json.JSONException) { emptyList() }
}
