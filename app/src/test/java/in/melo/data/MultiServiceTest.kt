package `in`.melo.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiServiceTest {
    private val SERVER_TRACK_PREFIX = "music-server:library:"

    private fun server(id: String, title: String, artist: String, seconds: Int) =
        Music("$SERVER_TRACK_PREFIX$id", title, artist, MusicKind.Track, durationMs = seconds * 1000)

    // Best match ---------------------------------------------------------------------------------

    @Test fun bestMatchPrefersTheOriginalRecording() {
        val query = MatchQuery("spotify:track:x", "One More Time", "Daft Punk", 320_357, "Discovery")
        val candidates = listOf(
            server("cover", "One More Time (Piano Cover)", "Some Pianist", 300),
            server("live", "One More Time (Live)", "Daft Punk", 372),
            server("other", "One more time", "C-YU", 172),
            server("orig", "One More Time", "Daft Punk", 320),
        )
        assertEquals("${SERVER_TRACK_PREFIX}orig", candidates.maxByOrNull { TrackMatcher.score(query, it) }?.id)
    }

    @Test fun bestMatchReadsVideoTitlesAndChannels() {
        val query = MatchQuery("spotify:track:y", "Blinding Lights", "The Weeknd", 200_040)
        val videos = listOf(
            server("lyrics", "Blinding Lights (Lyrics)", "Lyric Channel", 201),
            server("official", "The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO", 263),
            server("audio", "The Weeknd - Blinding Lights (Official Audio)", "TheWeekndVEVO", 201),
        )
        assertEquals("${SERVER_TRACK_PREFIX}audio", videos.maxByOrNull { TrackMatcher.score(query, it) }?.id)
    }

    @Test fun bestMatchRefusesUnrelatedTracks() {
        val query = MatchQuery("spotify:track:z", "Clair de lune", "Claude Debussy", 300_000)
        val candidates = listOf(server("a", "Moonlight Sonata", "Beethoven", 900), server("b", "Lune", "Someone", 180))
        assertNull(candidates.firstOrNull { TrackMatcher.score(query, it) >= ConversionMatching.CONFIDENT })
    }

    @Test fun titlesCompareWithoutVersionsAndFeaturings() {
        assertEquals(1.0, TrackMatcher.titleSimilarity("Song - Remastered 2011", "Song (Remastered)"), 0.0)
        assertEquals(1.0, TrackMatcher.titleSimilarity("Song (feat. Someone)", "Song"), 0.0)
        assertEquals(1.0, TrackMatcher.titleSimilarity("Café", "Cafe"), 0.0)
        assertTrue(TrackMatcher.durationSimilarity(200_000, 201_500) == 1.0)
        assertTrue(TrackMatcher.durationSimilarity(200_000, 260_000) == 0.0)
    }

    // CSV ----------------------------------------------------------------------------------------

    @Test fun csvKeepsSourcesAndReferences() {
        val tracks = listOf(
            Music("spotify:track:4uLU6hMCjMI75M1A2tKUQC", "Never, \"Gonna\"", "Rick Astley", MusicKind.Track, durationMs = 213_000,
                album = "Whenever", imageUrl = "https://i.scdn.co/image/ab67616d0000b273abc"),
            server("dQw4w9WgXcQ", "Line\nbreak", "Artist", 212),
            Music("${LOCAL_TRACK_PREFIX}12", "Local", "Me", MusicKind.Track, mediaUri = "content://media/external/audio/media/12"),
            Music("spotify:local:Artist:Album:Title:180", "Title", "Artist", MusicKind.Track, durationMs = 180_000),
        )
        val csv = PlaylistCsv.export("Mix, été", tracks)
        assertTrue(csv.startsWith("playlist,source,id,title,artists,album,duration_ms,image_url,url\r\n"))
        assertTrue(csv.contains(",music_server,"))
        assertTrue(csv.contains("music-server:library:dQw4w9WgXcQ"))
        val imported = PlaylistCsv.import(csv, "fallback")
        assertEquals("Mix, été", imported.name)
        assertEquals(tracks.map(Music::id), imported.tracks.map(Music::id))
        assertEquals(tracks.map(Music::title), imported.tracks.map(Music::title))
        assertEquals(213_000, imported.tracks.first().durationMs)
        assertEquals(listOf(MusicSource.Spotify, MusicSource.MusicServer, MusicSource.Local, MusicSource.Local), imported.tracks.map { it.source })
    }

    @Test fun csvRejectsForeignFilesAndSkipsUnknownRows() {
        val result = runCatching { PlaylistCsv.import("name,artist\nA,B\n", "x") }
        assertTrue(result.isFailure)
        val partial = PlaylistCsv.import("id,title\nspotify:album:x,Album\nmusic-server:library:abc,Song\n", "Fallback")
        assertEquals("Fallback", partial.name)
        assertEquals(listOf("music-server:library:abc"), partial.tracks.map(Music::id))
    }

    // Spotify local files -------------------------------------------------------------------------

    @Test fun spotifyLocalReferencesAreDecoded() {
        val track = checkNotNull(spotifyLocalTrack("spotify:local:Daft+Punk:Discovery:One+More+Time%3A+Edit:320"))
        assertEquals("One More Time: Edit", track.title)
        assertEquals("Daft Punk", track.subtitle)
        assertEquals("Discovery", track.album)
        assertEquals(320_000, track.durationMs)
        assertTrue(track.isLocalTrack)
        assertNull(spotifyLocalTrack("spotify:track:abc"))
    }

    @Test fun localReferencesFindTheirFile() {
        val files = listOf(
            Music("${LOCAL_TRACK_PREFIX}1", "One More Time", "Daft Punk", MusicKind.Track, durationMs = 320_500, mediaUri = "content://1"),
            Music("${LOCAL_TRACK_PREFIX}2", "One More Time", "Other", MusicKind.Track, durationMs = 100_000, mediaUri = "content://2"),
        )
        val reference = checkNotNull(spotifyLocalTrack("spotify:local:Daft+Punk:Discovery:One+More+Time:320"))
        assertEquals("content://1", withLocalFile(reference, files).mediaUri)
        val missing = checkNotNull(spotifyLocalTrack("spotify:local:Nobody::Unknown:100"))
        assertNull(withLocalFile(missing, files).mediaUri)
    }

    @Test fun playlistPagesKeepLocalReferencesInOrder() {
        val page = proto {
            writeByteArray(5, proto {
                writeByteArray(3, proto { writeString(1, "spotify:track:4uLU6hMCjMI75M1A2tKUQC") })
                writeByteArray(3, proto { writeString(1, "spotify:local:A:B:C:10") })
                writeByteArray(3, proto { writeString(1, "spotify:episode:4uLU6hMCjMI75M1A2tKUQC") })
            })
        }
        val decoded = decodePlaylistPage(page, Music("spotify:playlist:x", "", "", MusicKind.Playlist))
        assertEquals(listOf("spotify:track:4uLU6hMCjMI75M1A2tKUQC", "spotify:local:A:B:C:10"), decoded.entries)
        assertEquals(listOf("spotify:track:4uLU6hMCjMI75M1A2tKUQC"), decoded.uris)
    }

    // Links --------------------------------------------------------------------------------------


    @Test fun spotifyEmbedPagesListTracks() {
        val data = JSONObject().put("props", JSONObject().put("pageProps", JSONObject().put("state", JSONObject().put("data",
            JSONObject().put("entity", JSONObject().put("name", "Mix").put("trackList", org.json.JSONArray()
                .put(JSONObject().put("uri", "spotify:track:4uLU6hMCjMI75M1A2tKUQC").put("title", "Song").put("subtitle", "Artist").put("duration", 1000))
                .put(JSONObject().put("uri", "spotify:local:A:B:Local:5").put("title", "Local").put("subtitle", "A"))))))))
        val content = checkNotNull(SpotifyPublicPlaylist.parse("""<html><script id="__NEXT_DATA__" type="application/json">$data</script></html>"""))
        assertEquals("Mix", content.name)
        assertEquals(listOf("spotify:track:4uLU6hMCjMI75M1A2tKUQC", "spotify:local:A:B:Local:5"), content.tracks.map(Music::id))
    }

    // InnerTube parsing --------------------------------------------------------------------------

    private fun run(text: String, pageType: String? = null) = JSONObject().put("text", text).apply {
        if (pageType != null) put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject()
            .put("browseEndpointContextSupportedConfigs", JSONObject().put("browseEndpointContextMusicConfig", JSONObject().put("pageType", pageType)))))
    }

    @Test fun localFilesBecomeSpotifyLocalReferences() {
        val file = Music("${LOCAL_TRACK_PREFIX}9", "Title: part 2", "A & B", MusicKind.Track, album = "Été", durationMs = 61_900)
        val uri = checkNotNull(spotifyLocalUri(file))
        assertEquals("spotify:local:A+%26+B:%C3%89t%C3%A9:Title%3A+part+2:61", uri)
        val back = checkNotNull(spotifyLocalTrack(uri))
        assertEquals(listOf("Title: part 2", "A & B", "Été", "61000"), listOf(back.title, back.subtitle, back.album, back.durationMs.toString()))
        assertNull(spotifyLocalUri(server("x", "t", "a", 1)))
    }

    @Test fun homeLayoutKeepsChoicesAndFillsNewShelves() {
        val chosen = listOf(
            `in`.melo.data.preferences.ShelfConfig(`in`.melo.data.preferences.HomeShelf.ALBUMS, true, `in`.melo.data.preferences.ShelfStyle.GRID, 6, showTitle = false),
            `in`.melo.data.preferences.ShelfConfig(`in`.melo.data.preferences.HomeShelf.PINS, false, `in`.melo.data.preferences.ShelfStyle.PILLS, 10),
        )
        val decoded = `in`.melo.data.preferences.HomeLayout.decode(`in`.melo.data.preferences.HomeLayout.encode(chosen))
        assertEquals(chosen, decoded.take(2))
        assertEquals(`in`.melo.data.preferences.HomeShelf.entries.toSet(), decoded.map { it.shelf }.toSet())
        // A style a shelf does not support falls back to its default.
        val wrong = `in`.melo.data.preferences.HomeLayout.decode("""[{"shelf":"liked","style":"grid","visible":true,"count":4}]""")
        assertEquals(`in`.melo.data.preferences.ShelfStyle.CARD, wrong.first().style)
        assertEquals(`in`.melo.data.preferences.HomeLayout.DEFAULT, `in`.melo.data.preferences.HomeLayout.decode("not json"))
    }

}
