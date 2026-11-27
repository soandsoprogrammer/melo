package `in`.melo.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import `in`.melo.data.model.Song
import `in`.melo.data.repository.LyricsProvider
import `in`.melo.data.repository.LyricsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Asks the real lyrics services, one at a time and then all together.
 * Run with `-e lyricsNetwork true`. A service being down is logged, not failed:
 * the lookup as a whole must still find word-timed lyrics for a well-known song.
 */
@RunWith(AndroidJUnit4::class)
class LyricsProvidersNetworkTest {
    private val songs = listOf(
        Song("local:probe-1", "Blinding Lights", "The Weeknd", album = "After Hours", duration = 200_000),
        Song("local:probe-2", "Shape of You", "Ed Sheeran", album = "÷ (Deluxe)", duration = 233_000),
        Song("local:probe-3", "Bohemian Rhapsody - Remastered 2011", "Queen", album = "A Night At The Opera", duration = 355_000),
    )

    @Test fun everyProviderIsReportedAndTheRaceFindsWordTimings(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lyricsNetwork") == "true")
        val repository = LyricsRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        for (song in songs) {
            for (provider in LyricsProvider.entries) {
                val started = System.currentTimeMillis()
                val lyrics = repository.find(song, null, listOf(provider))
                val words = lyrics?.synced.orEmpty().count { !it.words.isNullOrEmpty() }
                Log.i("MeloLyrics", "${song.title} | ${provider.label}: lines=${lyrics?.synced?.size ?: lyrics?.plain?.size ?: 0} " +
                    "wordTimedLines=$words in ${System.currentTimeMillis() - started} ms")
                repository.forget(song.id)
            }
            val started = System.currentTimeMillis()
            val early = mutableListOf<Int>()
            val all = repository.find(song, null, LyricsProvider.entries) { early += it.synced?.size ?: 0 }
            val words = all?.synced.orEmpty().count { !it.words.isNullOrEmpty() }
            Log.i("MeloLyrics", "${song.title} | ALL: lines=${all?.synced?.size} wordTimedLines=$words early=$early " +
                "in ${System.currentTimeMillis() - started} ms; cached=${repository.cached(song.id)?.synced?.count { !it.words.isNullOrEmpty() }}")
            assertTrue("${song.title}: no word-timed lyrics", words > 0)
            assertTrue(repository.cached(song.id)?.synced.orEmpty().count { !it.words.isNullOrEmpty() } == words)
            repository.forget(song.id)
        }
    }
}
