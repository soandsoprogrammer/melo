package `in`.melo.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import `in`.melo.data.AudioIndex
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.pow

/**
 * Loudness of tracks as their service publishes it. Spotify gives each file's integrated loudness
 * and true peak, which are kept in [AudioIndex].
 */
object TrackLoudness {
    /** Spotify's default normalization level, in LUFS. */
    private const val TARGET_LUFS = -14f
    /** Headroom kept under 0 dBFS when a quiet track would be raised. */
    private const val PEAK_CEILING_DB = -1f


    /** Gain to apply to [mediaId] in dB, or null when its loudness is unknown (local files included). */
    fun gainDb(context: Context, mediaId: String): Float? {
        if (!mediaId.startsWith("spotify:track:")) return null
        val values = AudioIndex.get(context).loudness(mediaId) ?: return null
        return spotifyGainDb(values[0], values[1])
    }

    internal fun spotifyGainDb(loudnessDb: Float, truePeakDb: Float): Float? {
        if (!loudnessDb.isFinite()) return null
        val gain = TARGET_LUFS - loudnessDb
        return if (truePeakDb.isFinite()) min(gain, PEAK_CEILING_DB - truePeakDb) else gain
    }

    /**
     * Linear factor for the output volume. Volume can only lower the signal: louder tracks are brought
     * down to the target, quieter ones keep their level instead of being raised with a limiter.
     */
    fun linear(gainDb: Float?): Float = if (gainDb == null || gainDb >= 0f) 1f else 10f.pow(gainDb / 20f).coerceIn(0.05f, 1f)
}

/**
 * Multiplies the player's volume by the current track's normalization factor. The factor is set from the
 * main thread and applied on the playback thread, where the sink's own calls arrive.
 */
@UnstableApi
internal class NormalizingAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    @Volatile var factor: Float = 1f
    private var volume = 1f
    private var applied = Float.NaN

    override fun setVolume(volume: Float) {
        this.volume = volume
        apply()
    }

    /** The PCM the decoder hands over, before Melo's own stages touch it. */
    override fun configure(config: AudioSink.AudioSinkConfig) {
        val inputFormat = config.format
        AudioPipeline.update {
            it.copy(
                decodedEncoding = inputFormat.pcmEncoding.takeIf { e -> e != androidx.media3.common.Format.NO_VALUE },
                decodedRateHz = inputFormat.sampleRate.takeIf { r -> r > 0 },
                decodedChannels = inputFormat.channelCount.takeIf { c -> c > 0 },
            )
        }
        super.configure(config)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (factor != applied) apply()
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    private fun apply() {
        val current = factor
        applied = current
        super.setVolume(volume * current)
    }
}
