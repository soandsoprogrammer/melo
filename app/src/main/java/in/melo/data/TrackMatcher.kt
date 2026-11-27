package `in`.melo.data

import java.text.Normalizer

/** Metadata used to match a recording. */
data class MatchQuery(val key: String, val title: String, val artists: String, val durationMs: Int, val album: String = "")

/** Scores recordings by their catalogue metadata. */
object TrackMatcher {
    /** 0..1: title (45 %), artist (35 %) and duration (20 %), minus versions the original is not. */
    fun score(query: MatchQuery, candidate: Music): Double {
        val title = titleSimilarity(query.title, candidate.title)
        if (title < MIN_TITLE) return 0.0
        val artist = artistSimilarity(query.artists, candidate.subtitle, candidate.title)
        val duration = durationSimilarity(query.durationMs, candidate.durationMs)
        var total = 0.45 * title + 0.35 * artist + 0.20 * duration
        val wanted = normalize(query.title + " " + query.album)
        val found = normalize(candidate.title)
        VERSION_WORDS.forEach { word -> if (found.containsWord(word) && !wanted.containsWord(word)) total -= VERSION_PENALTY }
        if (query.durationMs > 0 && candidate.durationMs > 0 && kotlin.math.abs(query.durationMs - candidate.durationMs) > 60_000) total -= 0.2
        // Same album name: most likely the same release of the recording.
        if (query.album.isNotBlank() && normalize(query.album) == normalize(candidate.album)) total += ALBUM_BONUS
        return total.coerceIn(0.0, 1.0)
    }

    internal fun titleSimilarity(wanted: String, found: String): Double {
        val a = normalize(wanted.coreTitle())
        if (a.isEmpty()) return 0.0
        // A video title often wraps the song title: "Artist - Title (Official Video)".
        val bare = found.replace(PARENTHETICALS, " ")
        val forms = listOf(found.coreTitle(), bare, bare.substringAfter(" - ", "")).map(::normalize).filter(String::isNotEmpty).distinct()
        return forms.maxOfOrNull { b ->
            if (a == b) return 1.0
            val tokens = tokenSimilarity(a, b)
            val edit = 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
            val contained = if (b.containsWord(a)) 0.9 else 0.0
            maxOf(tokens, edit, contained)
        } ?: 0.0
    }

    internal fun artistSimilarity(wanted: String, found: String, foundTitle: String): Double {
        val names = wanted.split(",", "&", " feat.", " ft.", " x ").map { normalize(it) }.filter(String::isNotEmpty)
        if (names.isEmpty()) return 0.5
        val channel = normalize(found.replace(CHANNEL_NOISE, " "))
        val haystack = "$channel ${normalize(foundTitle)}"
        val primary = names.first()
        return when {
            channel.isNotEmpty() && (channel == primary || haystack.containsWord(primary) || channel.replace(" ", "") == primary.replace(" ", "")) -> 1.0
            names.any { haystack.containsWord(it) } -> 0.8
            else -> tokenSimilarity(primary, channel) * 0.8
        }
    }

    internal fun durationSimilarity(wanted: Int, found: Int): Double {
        if (wanted <= 0 || found <= 0) return 0.5
        val seconds = kotlin.math.abs(wanted - found) / 1000.0
        return when {
            seconds <= 2 -> 1.0
            seconds >= 30 -> 0.0
            else -> 1.0 - (seconds - 2) / 28
        }
    }

    internal fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(MARKS, "")
        .replace("&", " and ")
        .replace(SEPARATORS, " ")
        .trim()

    /** "Song - Remastered 2011", "Song (feat. X)" and "Song [Live]" all compare as "Song". */
    internal fun String.coreTitle(): String = replace(PARENTHETICALS, " ")
        .replace(DASH_SUFFIX, " ")
        .replace(FEATURING, " ")
        .trim().ifEmpty { this }


    private fun String.containsWord(word: String): Boolean =
        word.isNotEmpty() && (" $this ").contains(" $word ")

    private fun tokenSimilarity(a: String, b: String): Double {
        val first = a.split(' ').filter(String::isNotEmpty).toSet()
        val second = b.split(' ').filter(String::isNotEmpty).toSet()
        if (first.isEmpty() || second.isEmpty()) return 0.0
        return (first intersect second).size.toDouble() / (first union second).size
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    private val PARENTHETICALS = Regex("""[(\[][^)\]]*[)\]]""")
    // Compiled once: normalize runs for every local file and every candidate.
    private val CHANNEL_NOISE = Regex("(?i)vevo|- topic|official")
    private val MARKS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    private val DASH_SUFFIX = Regex("""\s+-\s+.*$""")
    private val FEATURING = Regex("""(?i)\s(feat|ft)\.?\s.*$""")
    private const val ACCEPTED = 0.55
    private const val CONFIDENT = 0.8
    private const val MIN_TITLE = 0.45
    private const val ALBUM_BONUS = 0.05
    private const val VERSION_PENALTY = 0.25
    private val VERSION_WORDS = listOf(
        "live", "remix", "acoustic", "instrumental", "karaoke", "cover", "sped", "slowed", "nightcore",
        "8d", "reverb", "demo", "lyrics", "reaction", "tutorial", "lesson", "edit", "extended",
    )
}
