package `in`.melo.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.datasource.HttpDataSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

@UnstableApi
internal object PlaybackDiagnostics : AnalyticsListener {
    private const val MAX_REASON = 120
    val nonSilentBuffers = AtomicLong()
    val decodedBytes = AtomicLong()
    val lastSignalAt = AtomicLong()
    val outputStarts = AtomicLong()
    @Volatile var lastError: String? = null

    private fun failure(kind: String, error: Exception) {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        val status = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        lastError = "$kind:${(error as? PlaybackException)?.errorCodeName.orEmpty()}:${causes.joinToString(">") { it.javaClass.simpleName }}:http=${status ?: 0}"
        Log.w("MeloAudio", lastError!!)
        // Messages are Melo's own for these types; others may carry addresses.
        val reason = causes.lastOrNull { it is `in`.melo.data.SpotifyFailure || it is `in`.melo.data.server.MusicServerException }
            ?.message?.take(MAX_REASON)
        `in`.melo.data.diagnostics.DiagnosticLog.record("player", lastError + (reason?.let { " \"$it\"" } ?: ""))
    }
    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) = failure("player", error)
    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) = failure("sink", audioSinkError)
    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) = failure("codec", audioCodecError)
    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) { outputStarts.incrementAndGet() }
}

@UnstableApi
internal class SignalProbe : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val size = inputBuffer.remaining()
        val samples = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var signal = false
        while (samples.remaining() >= 2) if (kotlin.math.abs(samples.short.toInt()) > 32) { signal = true; break }
        PlaybackDiagnostics.decodedBytes.addAndGet(size.toLong())
        if (signal) {
            PlaybackDiagnostics.nonSilentBuffers.incrementAndGet()
            PlaybackDiagnostics.lastSignalAt.set(SystemClock.elapsedRealtime())
        }
        replaceOutputBuffer(size).put(inputBuffer).flip()
    }
}
