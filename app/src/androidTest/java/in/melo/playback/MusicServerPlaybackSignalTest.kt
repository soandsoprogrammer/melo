package `in`.melo.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import `in`.melo.data.server.MusicServer
import `in`.melo.data.server.MusicServerUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MusicServerPlaybackSignalTest {
    @Test fun playsOriginalAudioAfterSeekAndTrackChange(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val address = arguments.getString("serverUrl").orEmpty()
        val username = arguments.getString("serverUser").orEmpty()
        val password = arguments.getString("serverPassword").orEmpty()
        assumeTrue(address.isNotBlank() && username.isNotBlank() && password.isNotBlank())
        val app = instrumentation.targetContext
        val server = MusicServer.get(app)
        server.signIn(address, username, password)
        var player: ExoPlayer? = null

        suspend fun assertSignal(position: () -> Boolean) {
            withTimeout(35_000) {
                while (!withContext(Dispatchers.Main, block = { position() })) delay(100)
                val before = PlaybackDiagnostics.nonSilentBuffers.get()
                while (PlaybackDiagnostics.nonSilentBuffers.get() < before + 20) {
                    check(withContext(Dispatchers.Main) { player?.playerError } == null) { "Original audio could not be decoded" }
                    delay(100)
                }
            }
            assertTrue(SystemClock.elapsedRealtime() - PlaybackDiagnostics.lastSignalAt.get() < 2_000)
        }

        try {
            val albums = server.albums(0).albums
            assertTrue("The signed-in library has albums", albums.isNotEmpty())
            val songPage = server.tracks("", 0).songs
            assertTrue("The signed-in library has tracks", songPage.size >= 2)
            val requestedQuery = arguments.getString("serverQuery").orEmpty()
            val first = if (requestedQuery.isBlank()) songPage.first()
                else server.tracks(requestedQuery, 0).songs.first()
            val second = songPage.first { it.id != first.id }
            if (arguments.getString("serverExpectLyrics") == "true") {
                assertTrue("The server returned timed lyrics", server.lyrics(first.id)?.startsWith("[") == true)
            }
            withContext(Dispatchers.Main) {
                val renderers = object : DefaultRenderersFactory(app) {
                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean,
                    ): AudioSink = DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(SignalProbe())).build()
                }
                val local = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(app))
                player = ExoPlayer.Builder(app, renderers).setMediaSourceFactory(MeloMediaSourceFactory(app, local, local))
                    .build().apply {
                        addAnalyticsListener(PlaybackDiagnostics)
                        setMediaItems(listOf(first, second).map { track ->
                            MediaItem.Builder().setMediaId(track.id).setUri(checkNotNull(MusicServerUris.stream(track.id))).build()
                        })
                        prepare()
                        play()
                    }
            }
            assertSignal { player?.currentMediaItemIndex == 0 }
            val seekTo = first.durationMs / 2L
            withContext(Dispatchers.Main) { player!!.seekTo(seekTo) }
            assertSignal { player!!.currentPosition in seekTo..(seekTo + 12_000) }
            withContext(Dispatchers.Main) { player!!.seekToNextMediaItem() }
            assertSignal { player?.currentMediaItemIndex == 1 }
        } finally {
            withContext(Dispatchers.Main) { player?.release() }
            server.disconnect()
        }
    }
}
