package `in`.melo.playback

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import `in`.melo.data.addons.ResolvedStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the playing track goes through on its way out, stage by stage, as the
 * player itself reports it: the stream that was read, the decoder that read it,
 * the PCM it produced, and the AudioTrack it was written to.
 *
 * The output device and Android's mixer are not here: they belong to the route,
 * not the track, and are read where they are shown.
 */
internal object AudioPipeline {
    enum class SourceKind { None, Server, Addon, Device }

    data class Snapshot(
        val sourceKind: SourceKind = SourceKind.None,
        /** The add-on's name when an add-on serves the track. */
        val addonName: String? = null,
        /** What the add-on said about its stream, for what the container does not state. */
        val claimedBitDepth: Int? = null,
        val claimedLossless: Boolean? = null,
        val mimeType: String? = null,
        val containerMimeType: String? = null,
        val sampleRateHz: Int? = null,
        val channels: Int? = null,
        val bitrateKbps: Int? = null,
        /** The source's own PCM depth, when the container states it (FLAC, WAV). */
        val sourcePcmEncoding: Int? = null,
        val decoderName: String? = null,
        val decodedEncoding: Int? = null,
        val decodedRateHz: Int? = null,
        val decodedChannels: Int? = null,
        val trackEncoding: Int? = null,
        val trackRateHz: Int? = null,
        val trackBufferBytes: Int? = null,
        val offload: Boolean = false,
        val tunneling: Boolean = false,
        /** Gain normalization applies to this track, in dB; null when off or unknown. */
        val normalizationGainDb: Float? = null,
        val speed: Float = 1f,
        val pitch: Float = 1f,
        val volume: Float = 1f,
        val isPlaying: Boolean = false,
    ) {
        val isLossless: Boolean?
            get() = losslessMime(mimeType) ?: claimedLossless

        /** Bits per sample the source carries: only lossless sources have a depth of their own. */
        val sourceBitDepth: Int?
            get() = if (isLossless != true) null else bitsOf(sourcePcmEncoding) ?: claimedBitDepth
    }

    private val state = MutableStateFlow(Snapshot())
    val current: StateFlow<Snapshot> = state.asStateFlow()

    internal fun update(change: (Snapshot) -> Snapshot) = state.update(change)

    internal fun reset() { state.value = Snapshot() }

    /** True for lossless codecs, false for lossy ones, null for the unknown. */
    fun losslessMime(mime: String?): Boolean? = when (mime?.lowercase()) {
        null, "" -> null
        "audio/flac", "audio/x-flac", "audio/alac", "audio/raw", "audio/wav", "audio/x-wav" -> true
        else -> if (mime.startsWith("audio/")) false else null
    }

    fun bitsOf(encoding: Int?): Int? = when (encoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
        else -> null
    }

    /** "PCM16", "PCM24", "Float32": the names an AudioTrack encoding goes by. */
    fun encodingName(encoding: Int?): String? = when (encoding) {
        C.ENCODING_PCM_8BIT -> "PCM8"
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> "PCM16"
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> "PCM24"
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> "PCM32"
        C.ENCODING_PCM_FLOAT -> "Float32"
        AudioFormat.ENCODING_AC3 -> "AC-3"
        AudioFormat.ENCODING_E_AC3 -> "E-AC-3"
        null, C.ENCODING_INVALID, Format.NO_VALUE -> null
        else -> "Encoded ($encoding)"
    }

    /** Why the samples leaving Melo differ from the decoder's, first cause in the chain first. */
    enum class Alteration { None, Speed, Normalization, Volume, Effects, DepthReduced, Resampled, Unknown }

    /**
     * The first stage that changes the decoded samples before they leave Melo,
     * or [Alteration.None] when none does. [effectsOn] is the equalizer and the
     * other session effects, which the caller reads from their own state.
     */
    fun alteration(s: Snapshot, effectsOn: Boolean): Alteration = when {
        s.decodedEncoding == null || s.trackEncoding == null -> Alteration.Unknown
        s.speed != 1f || s.pitch != 1f -> Alteration.Speed
        (s.normalizationGainDb ?: 0f) < 0f -> Alteration.Normalization
        s.volume < 1f -> Alteration.Volume
        effectsOn -> Alteration.Effects
        (bitsOf(s.trackEncoding) ?: 0) < (bitsOf(s.decodedEncoding) ?: 0) -> Alteration.DepthReduced
        s.trackRateHz != null && s.decodedRateHz != null && s.trackRateHz != s.decodedRateHz -> Alteration.Resampled
        else -> Alteration.None
    }
}

/** Keeps [AudioPipeline] in step with the player, from its own analytics events. */
@UnstableApi
internal class AudioPipelineTracker(private val player: Player) : AnalyticsListener {
    fun start() {
        AudioPipeline.reset()
        source(player.currentMediaItem)
    }

    private fun source(item: MediaItem?) {
        val id = item?.mediaId
        val uri = item?.localConfiguration?.uri
        val addon: ResolvedStream? = id?.let { AddonPlayback.served[it] }
        val kind = when {
            item == null -> AudioPipeline.SourceKind.None
            addon != null -> AudioPipeline.SourceKind.Addon
            uri?.scheme == "content" || uri?.scheme == "file" -> AudioPipeline.SourceKind.Device
            else -> AudioPipeline.SourceKind.Server
        }
        val format = addon?.format
        AudioPipeline.update {
            it.copy(
                sourceKind = kind, addonName = addon?.addonName,
                claimedBitDepth = format?.bitDepth, claimedLossless = format?.isLossless,
            )
        }
    }

    override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) =
        source(mediaItem)

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        // The source can change under the same item: an upgrade swaps the stream in place.
        source(player.currentMediaItem)
        val kbps = listOf(format.bitrate, format.averageBitrate, format.peakBitrate)
            .firstOrNull { it > 0 }?.let { it / 1000 }
        AudioPipeline.update {
            it.copy(
                mimeType = format.sampleMimeType, containerMimeType = format.containerMimeType,
                sampleRateHz = format.sampleRate.takeIf { r -> r > 0 },
                channels = format.channelCount.takeIf { c -> c > 0 },
                bitrateKbps = kbps,
                sourcePcmEncoding = format.pcmEncoding.takeIf { e -> e != Format.NO_VALUE },
            )
        }
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
    ) = AudioPipeline.update { it.copy(decoderName = decoderName) }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) =
        AudioPipeline.update {
            it.copy(
                trackEncoding = audioTrackConfig.encoding, trackRateHz = audioTrackConfig.sampleRate,
                trackBufferBytes = audioTrackConfig.bufferSize, offload = audioTrackConfig.offload,
                tunneling = audioTrackConfig.tunneling,
            )
        }

    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) =
        AudioPipeline.update { it.copy(isPlaying = isPlaying) }

    override fun onPlaybackParametersChanged(eventTime: AnalyticsListener.EventTime, playbackParameters: PlaybackParameters) =
        AudioPipeline.update { it.copy(speed = playbackParameters.speed, pitch = playbackParameters.pitch) }

    override fun onVolumeChanged(eventTime: AnalyticsListener.EventTime, volume: Float) =
        AudioPipeline.update { it.copy(volume = volume) }
}
