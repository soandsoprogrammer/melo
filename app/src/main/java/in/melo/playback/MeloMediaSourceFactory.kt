package `in`.melo.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import `in`.melo.data.server.MusicServerUris
import `in`.melo.data.addons.AddonMatcher
import `in`.melo.data.addons.AddonResolver
import `in`.melo.data.addons.AddonUris

/** Plays original server audio, local files and streams supplied by an enabled add-on. */
@UnstableApi
internal class MeloMediaSourceFactory(
    private val files: MediaSource.Factory,
    private val server: MediaSource.Factory,
) : MediaSource.Factory {
    private val addonStreams = AddonStreamSources()

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = this
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        files.setLoadErrorHandlingPolicy(policy)
        server.setLoadErrorHandlingPolicy(policy)
        addonStreams.setLoadErrorHandlingPolicy(policy)
        return this
    }
    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(item: MediaItem): MediaSource {
        val uri = item.localConfiguration?.uri
        if (MusicServerUris.isPlayback(uri)) return server.createMediaSource(item)
        AddonUris.parse(uri)?.let { (addonId, trackId) ->
            return AddonMediaSource(item, { AddonResolver.streamRow(addonId, trackId) }, addonStreams, fallback = null)
        }
        return files.createMediaSource(item)
    }
}

/** What an add-on is asked to find for a queued track: its credits, runtime and album. */
internal fun addonTargetOf(item: MediaItem): AddonMatcher.Target {
    val metadata = item.mediaMetadata
    val durationMs = metadata.extras?.getInt("duration")?.takeIf { it > 0 } ?: 0
    return AddonMatcher.Target(
        title = metadata.title?.toString()?.ifBlank { null }.orEmpty(),
        artist = metadata.artist?.toString()?.ifBlank { null }.orEmpty(),
        durationSec = (durationMs / 1000).takeIf { it > 0 },
        album = metadata.extras?.getString("album")?.ifBlank { null },
    )
}
