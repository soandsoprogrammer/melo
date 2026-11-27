package `in`.melo.data.addons
import kotlin.math.abs
import java.text.Normalizer
import java.util.Locale
/**
 * Decides whether an add-on's row is the very recording a queued track names.
 *
 * Strict on purpose: the answer is played from the first note in place of the
 * listener's track. Titles compare by their core words and must carry the same
 * version marks (a live take or a remix is another recording), the credited
 * artists must overlap, and the runtime must agree. Japanese kana titles also
 * compare by their romanised form, so either script finds the other.
 */
internal object AddonMatcher {
    /** What is known of the queued track. */
    data class Target(
        val title: String,
        val artist: String = "",
        val durationSec: Int? = null,
        val album: String? = null,
    )
    /** Searches to try in turn: title with the lead artist, the title alone, then the title in another script. */
    fun queries(target: Target): List<String> {
        val title = searchableTitle(target.title, target.artist)
        if (title.isBlank()) return emptyList()
        val artist = primaryArtist(target.artist)
        val out = if (artist.isBlank()) mutableListOf(title) else mutableListOf("$title $artist", title)
        val latin = romajiOf(title).trim()
        val otherScript = latin.takeIf { it.isNotBlank() && it != title && it.all { c -> c in LATIN_QUERY_CHARS } }
            ?: scriptMates(target.title).firstOrNull { it.isNotBlank() && it != title }
        otherScript?.let { out += it }
        return out.distinct()
    }
    internal fun searchableTitle(title: String, artist: String = ""): String =
        parseTitle(title, artist).let { (it.words + it.versions).joinToString(" ") }
    internal fun primaryArtist(artist: String): String {
        val primary = normalize(artist).split(ARTIST_SEPARATORS).firstOrNull()?.trim().orEmpty()
        return primary.takeUnless { it in GENERIC_CATALOG_LABELS }.orEmpty()
    }
    fun sharesArtist(wanted: String, got: String): Boolean {
        val want = artistNames(wanted)
        val have = artistNames(got)
        return want.isNotEmpty() && have.isNotEmpty() &&
                want.any { w -> have.any { h -> sameArtist(w, h) } }
    }
    /** Candidates that match at all, best first; rows crediting the artist go ahead of rows that do not. */
    fun ranked(candidates: List<AddonTrack>, target: Target): List<AddonTrack> =
        candidates
            .mapNotNull { candidate -> score(candidate, target)?.let { candidate to it } }
            .let { scored ->
                val credited = scored.filter { (candidate, _) ->
                    artistScore(target.artist, candidate.artist) != null
                }
                credited.ifEmpty { scored }
            }
            .sortedByDescending { it.second }
            .map { it.first }
    /** A confidence score, or null when the row is another recording. */
    fun score(candidate: AddonTrack, target: Target): Int? {
        val wanted = parseTitle(target.title, target.artist)
        val got = parseTitle(candidate.title, candidate.artist)
        if (wanted.core.isBlank() || got.core.isBlank()) return null
        val isTitleMatch = when {
            wanted.core == got.core -> true
            romajiOf(wanted.core) == romajiOf(got.core) -> true
            isTranslationMatch(wanted, got) -> true
            wanted.core.length <= 3 || got.core.length <= 3 -> false
            else -> wanted.core.contains(got.core) || got.core.contains(wanted.core)
        }
        if (!isTitleMatch) return null
        val albumMarks = albumVersionMarkers(candidate.album)
        val effectiveVersions = got.versions + (albumMarks intersect NOVOCAL_TAKES) +
            (albumMarks intersect wanted.versions)
        if (wanted.versions != effectiveVersions) return null
        val creditedArtist = artistScore(target.artist, candidate.artist)
        val duration = durationScore(
            target.durationSec,
            candidate.durationSec,
            allowVideoDrift = creditedArtist != null,
        ) ?: return null
        val artist = creditedArtist
            ?: CREDITS_DISAGREE.takeIf {
                withinSeconds(candidate, target, CREDIT_OVERRIDE_SEC)
            }
            ?: return null
        return BASE + artist + duration + albumScore(target.album, candidate.album) +
                contextScore(wanted, got)
    }
    fun withinSeconds(candidate: AddonTrack, target: Target, seconds: Int): Boolean {
        val wanted = target.durationSec ?: return false
        val got = candidate.durationSec ?: return false
        return abs(wanted - got) <= seconds
    }
    internal data class TitleParts(
        val words: List<String>,
        val core: String,
        val versions: Set<String>,
        val context: Set<String>,
    )
    internal fun parseTitle(raw: String, artist: String = ""): TitleParts {
        val versions = sortedSetOf<String>()
        val context = mutableSetOf<String>()
        var text = normalize(raw)
        text = text.replace(PT_BR_FEATURING, " ")
        repeat(BRACKET_PASSES) {
            if (!BRACKETED.containsMatchIn(text)) return@repeat
            text = BRACKETED.replace(text) { match ->
                classify(match.groupValues[1], versions, context)
                " "
            }
        }
        text.indexOfFirst { it == '(' || it == '[' }.takeIf { it >= 0 }?.let { open ->
            classify(text.substring(open), versions, context)
            text = text.substring(0, open)
        }
        repeat(DASH_PASSES) {
            val dash = DASH.find(text) ?: return@repeat
            val head = text.substring(0, dash.range.first)
            val tail = text.substring(dash.range.last + 1)
            text = if (isArtistName(head, artist)) {
                classify(head, versions, context)
                tail
            } else if (!hasFilingLatin(head) && hasFilingLatin(tail) && !isArtistName(tail, artist)) {
                classify(head, versions, context)
                tail
            } else {
                classify(tail, versions, context)
                head
            }
        }
        text = text.replace(FEATURING, " ")
        val containsCJK = text.any { Character.UnicodeBlock.of(it) in CJK_BLOCKS }
        var words = text.split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() && it !in JOINING_WORDS }
        while (words.size > 1) {
            val last = words.last()
            if (last in TRAILING_NOISE) words = words.dropLast(1)
            else if (last in TRAILING_TAKES) {
                versions += last
                words = words.dropLast(1)
            } else break
        }
        val coreString = if (containsCJK) {
            text.replace(Regex("""[\s\p{Punct}・～〜ー-]+"""), "")
        } else {
            foldLatin(words.joinToString(""))
        }
        return TitleParts(
            words = words,
            core = coreString,
            versions = versions,
            context = context,
        )
    }
    private fun classify(
        segment: String,
        versions: MutableSet<String>,
        context: MutableSet<String>,
    ) {
        val words = segment.split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        if (words.joinToString("") in NEUTRAL_SEGMENTS) return
        val marks = words.filter { it in VERSION_WORDS }
        if (marks.isNotEmpty()) {
            versions += marks
            return
        }
        context += words.filter { (it.length > 2 || containsCJKChar(it)) && it !in NOISE_WORDS }
    }
    internal fun isTranslationMatch(wanted: TitleParts, got: TitleParts): Boolean {
        val wantedCJK = containsCJKChar(wanted.core) && !hasFilingLatin(wanted.core)
        val gotCJK = containsCJKChar(got.core) && !hasFilingLatin(got.core)
        val wantedLatin = hasFilingLatin(wanted.core)
        val gotLatin = hasFilingLatin(got.core)
        if ((wantedCJK && gotLatin) || (gotCJK && wantedLatin)) {
            if (foldLatin(wanted.context.joinToString("")) == got.core) return true
            if (foldLatin(got.context.joinToString("")) == wanted.core) return true
        }
        return false
    }
    private fun containsCJKChar(text: String): Boolean = text.any {
        Character.UnicodeBlock.of(it) in CJK_BLOCKS
    }
    private fun isArtistName(text: String, artist: String): Boolean {
        if (artist.isBlank()) return false
        val words =
            text.split(WORD_SPLIT).map { it.replace(NON_ALNUM, "") }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val credited = normalize(artist).split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() }
            .toSet()
        return words.all { it in credited }
    }
    internal fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(CJK_OPEN, "(")
            .replace(CJK_CLOSE, ")")
    internal fun foldLatin(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when {
                c == 'ı' -> append('i')
                c.code < 0x80 || Character.UnicodeScript.of(c.code) != Character.UnicodeScript.LATIN -> append(c)
                else -> Normalizer.normalize(c.toString(), Normalizer.Form.NFD).forEach {
                    if (Character.getType(it) != Character.NON_SPACING_MARK.toInt()) append(it)
                }
            }
        }
    }
    internal fun hasFilingLatin(text: String): Boolean =
        text.split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() }
            .any {
                LATIN.containsMatchIn(it) && it !in VERSION_WORDS && it !in NOISE_WORDS &&
                    it !in TRAILING_NOISE && it !in JOINING_WORDS
            }
    internal fun scriptMates(raw: String): List<String> {
        val text = normalize(raw)
        val dash = DASH.find(text) ?: return emptyList()
        val head = searchableTitle(text.substring(0, dash.range.first))
        val tail = searchableTitle(text.substring(dash.range.last + 1))
        if (head.isBlank() || tail.isBlank() || head == tail) return emptyList()
        return if (romajiOf(head) == tail || romajiOf(tail) == head) listOf(head, tail)
        else emptyList()
    }
    fun romajiOf(text: String): String {
        val out = StringBuilder()
        var i = 0
        var geminate = false
        var lastVowel: Char? = null
        fun emit(roma: String) {
            var r = roma
            if (geminate) {
                geminate = false
                r = when {
                    r.startsWith("ch") -> "t$r"
                    r.startsWith("sh") -> "s$r"
                    r.startsWith("ts") -> "t$r"
                    else -> "${r[0]}$r"
                }
            }
            out.append(r)
            lastVowel = r.lastOrNull { it in "aeiou" }
        }
        while (i < text.length) {
            var c = text[i]
            if (c in 'ァ'..'ヶ') c = (c.code - 0x60).toChar()
            when (c) {
                'っ' -> { geminate = true; i++ }
                'ー' -> { lastVowel?.let(out::append); i++ }
                'ん' -> {
                    val peek = text.getOrNull(i + 1)?.let { nc ->
                        val fc = if (nc in 'ァ'..'ヶ') (nc.code - 0x60).toChar() else nc
                        KANA_ROMAJI[fc]?.firstOrNull()
                    }
                    emit(if (peek == 'b' || peek == 'm' || peek == 'p') "m" else "n")
                    i++
                }
                else -> {
                    val base = KANA_ROMAJI[c]
                    if (base == null) {
                        out.append(c); lastVowel = null; geminate = false; i++
                    } else {
                        val n1 = text.getOrNull(i + 1)?.let { nc ->
                            if (nc in 'ァ'..'ヶ') (nc.code - 0x60).toChar() else nc
                        }
                        val small = n1?.let { SMALL_Y[it] ?: SMALL_V[it] }
                        if (small != null) {
                            val cons = CONS_OVERRIDES.entries
                                .firstOrNull { base.startsWith(it.key) }?.value
                                ?: base.dropLast(1)
                            val glide = if (cons == "sh" || cons == "ch" || cons == "j") small.removePrefix("y") else small
                            emit(cons + glide)
                            i += 2
                        } else {
                            emit(base); i++
                        }
                    }
                }
            }
        }
        return out.toString()
    }
    private fun artistScore(wanted: String, got: String): Int? {
        val want = artistNames(wanted)
        val have = artistNames(got)
        if (want.isEmpty() || have.isEmpty()) return 0
        val shared = sharesArtist(wanted, got)
        if (!shared) return null
        return if (want == have) ARTIST_EXACT else ARTIST_SHARED
    }
    internal fun artistNames(value: String): Set<List<String>> = foldLatin(normalize(value))
        .split(ARTIST_SEPARATORS)
        .map { name ->
            name.split(WORD_SPLIT)
                .map { it.replace(NON_ALNUM, "") }
                .filter { it.length > 1 }
        }
        .filter { it.isNotEmpty() }
        .toSet()
    private fun sameArtist(a: List<String>, b: List<String>) = runOf(a, b) || runOf(b, a)
    private fun runOf(outer: List<String>, inner: List<String>): Boolean {
        if (inner.isEmpty() || inner.size > outer.size) return false
        return (0..outer.size - inner.size).any { at ->
            outer.subList(at, at + inner.size) == inner
        }
    }
    private fun durationScore(wanted: Int?, got: Int?, allowVideoDrift: Boolean = false): Int? {
        if (wanted == null || got == null) return 0
        val drift = abs(wanted - got)
        return when {
            drift > DURATION_LIMIT_SEC && allowVideoDrift && drift <= VIDEO_DURATION_LIMIT_SEC -> 0
            drift > DURATION_LIMIT_SEC -> null
            drift <= DURATION_TIGHT_SEC -> DURATION_TIGHT
            else -> DURATION_LOOSE
        }
    }
    private fun albumScore(wanted: String?, got: String?): Int {
        val want = albumKey(wanted) ?: return 0
        val have = albumKey(got) ?: return 0
        return if (want == have) ALBUM_EXACT else 0
    }
    internal fun albumVersionMarkers(album: String?): Set<String> =
        if (album.isNullOrBlank()) emptySet() else parseTitle(album).versions
    private fun albumKey(value: String?): String? {
        var text = normalize(value.orEmpty()).trim()
        if (text.isEmpty()) return null
        repeat(BRACKET_PASSES) { text = BRACKETED.replace(text, " ") }
        val words = text.split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() && it !in ALBUM_NOISE_WORDS }
        return words.joinToString("").takeIf { it.isNotEmpty() }
    }
    private fun contextScore(wanted: TitleParts, got: TitleParts): Int =
        if (wanted.context.any { it in got.context }) CONTEXT_SHARED else 0
    // Score weights.
    private const val BASE = 100
    private const val ARTIST_EXACT = 25
    private const val ARTIST_SHARED = 10
    private const val CREDITS_DISAGREE = -30
    private const val CREDIT_OVERRIDE_SEC = 2
    private const val DURATION_TIGHT = 40
    private const val DURATION_LOOSE = 15
    private const val ALBUM_EXACT = 35
    private const val CONTEXT_SHARED = 20
    private const val DURATION_TIGHT_SEC = 3
    const val DURATION_LIMIT_SEC = 30
    private const val VIDEO_DURATION_LIMIT_SEC = 90
    private const val BRACKET_PASSES = 3
    private const val DASH_PASSES = 3
    private val CJK_BLOCKS = setOf(
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
    )
    private val BRACKETED = Regex("""[(\[]([^()\[\]]*)[)\]]""")
    private val DASH = Regex("""\s+[-–—|]+\s+""")
    private val FEATURING = Regex("""\b(feat|ft|featuring|with)\b.*""")
    private val PT_BR_FEATURING = Regex(
        """\bpart\.\s+(?!(?:\d|[ivx]+\b))[^)\]]*[)\]]?|\bpart\s+(?:esp\.?|especial)\b[^)\]]*[)\]]?""",
        RegexOption.IGNORE_CASE,
    )
    private val WORD_SPLIT = Regex("""[\s.·~〜]+""")
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]""")
    private val LATIN = Regex("[a-z0-9]")
    private const val LATIN_QUERY_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789 "
    private val KANA_ROMAJI: Map<Char, String> = run {
        fun row(consonant: String, kana: String, vowels: String = "aiueo"): Map<Char, String> =
            kana.toList().zip(vowels.toList()).associate { (k, v) -> k to "$consonant$v" }
        buildMap {
            putAll(row("", "あいうえお"))
            putAll(row("k", "かきくけこ")); putAll(row("g", "がぎぐげご"))
            putAll(row("s", "さしすせそ")); put('し', "shi")
            putAll(row("z", "ざじずぜぞ")); put('じ', "ji")
            putAll(row("t", "たちつてと")); put('ち', "chi"); put('つ', "tsu")
            putAll(row("d", "だぢづでど")); put('ぢ', "ji"); put('づ', "zu")
            putAll(row("n", "なにぬねの"))
            putAll(row("h", "はひふへほ")); put('ふ', "fu")
            putAll(row("b", "ばびぶべぼ")); putAll(row("p", "ぱぴぷぺぽ"))
            putAll(row("m", "まみむめも"))
            put('や', "ya"); put('ゆ', "yu"); put('よ', "yo")
            putAll(row("r", "らりるれろ"))
            put('わ', "wa"); put('を', "wo"); put('ん', "n")
            put('ゔ', "vu")
            put('ぁ', "a"); put('ぃ', "i"); put('ぅ', "u"); put('ぇ', "e"); put('ぉ', "o")
            put('ゃ', "ya"); put('ゅ', "yu"); put('ょ', "yo"); put('ゎ', "wa")
            put('ゕ', "ka"); put('ゖ', "ke")
        }
    }
    private val SMALL_Y = mapOf('ゃ' to "ya", 'ゅ' to "yu", 'ょ' to "yo")
    private val SMALL_V = mapOf('ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o")
    private val CONS_OVERRIDES = mapOf(
        "shi" to "sh", "chi" to "ch", "tsu" to "ts", "fu" to "f", "ji" to "j", "zu" to "z",
    )
    private val CJK_OPEN = Regex("[「『【〈《〔［｛]")
    private val CJK_CLOSE = Regex("[」』】〉》〕］｝]")
    private val ARTIST_SEPARATORS =
        Regex("""\s*(?:[,&/;·|、，×]|\band\b|\bx\b|\bvs\.?\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b)\s*""")
    private val VERSION_WORDS = setOf(
        "remix", "remixes", "rmx", "refix", "flip", "bootleg", "mashup", "medley",
        "live", "concert", "unplugged", "acoustic", "instrumental", "karaoke",
        "vocals", "vocal", "acapella", "acappella", "backing", "stems", "stem",
        "cover", "demo", "reprise", "remake", "rework", "extended", "edit",
        "version", "mix", "dub", "vip", "session", "sessions",
        "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "symphonic",
        "part", "pt", "chapter",
        "カバー", "カラオケ", "リミックス", "ライブ", "アコースティック",
        "インスト", "バージョン", "歌ってみた", "踊ってみた", "弾いてみた",
        "翻唱", "现场", "混音",
        "커버", "라이브", "리믹스",
    )
    private val TRAILING_TAKES = setOf(
        "instrumental", "karaoke", "acapella", "acappella", "remix", "rmx", "nightcore",
    )
    private val NOVOCAL_TAKES = setOf(
        "instrumental", "karaoke", "acapella", "acappella", "backing", "stems", "stem",
    )
    private val NEUTRAL_SEGMENTS = setOf(
        "albumversion", "originalversion", "originalmix", "singleversion",
        "radioversion", "radioedit", "stereoversion", "monoversion",
        "studioversion", "fullversion", "standardversion", "explicitversion",
        "deluxeversion", "originaltrack",
    )
    private val GENERIC_CATALOG_LABELS = setOf(
        "música", "musica", "vídeo", "video", "song", "album", "álbum", "artista", "artist",
    )
    private val NOISE_WORDS = setOf(
        "official", "video", "audio", "lyrics", "lyric", "lyrical", "visualizer",
        "song", "songs", "full", "music", "the", "and", "from", "feat", "ft",
        "featuring", "with", "new", "latest", "free", "download", "remaster",
        "remastered", "explicit", "clean", "bonus", "track", "deluxe", "original",
        "album", "single", "hd", "hq", "4k", "mp3",
    )
    private val ALBUM_NOISE_WORDS = setOf(
        "album", "deluxe", "edition", "expanded", "remaster", "remastered",
        "version", "explicit", "clean", "bonus", "anniversary",
    )
    private val TRAILING_NOISE = setOf(
        "song", "songs", "video", "audio", "lyrics", "lyric", "lyrical",
        "official", "full", "hd", "hq", "4k", "mp3", "ost", "soundtrack",
    )
    private val JOINING_WORDS = setOf("and")
}
