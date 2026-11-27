package `in`.melo.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import `in`.melo.data.NetworkMonitor
import `in`.melo.data.server.MusicServer
import `in`.melo.data.server.MusicServerUris
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import `in`.melo.data.addons.AddonMatcher
import `in`.melo.data.addons.AddonResolver
import `in`.melo.data.addons.AddonUris

/**
 * YouTube Music streams (including matched Spotify tracks) and device files, both
 * progressive. The one addition is add-on streams: a row picked from an add-on, or,
 * with Direct Source Stream on, a YouTube-queued track an add-on holds.
 */
@UnstableApi
internal class MeloMediaSourceFactory(
    context: Context,
    private val files: MediaSource.Factory,
    private val youtube: MediaSource.Factory,
) : MediaSource.Factory {
    private val app = context.applicationContext
    private val addonStreams = AddonStreamSources()
    private val musicServer = ProgressiveMediaSource.Factory(OkHttpDataSource.Factory(
        OkHttpClient.Builder().addInterceptor { MusicServer.get(app).resolveMedia(it) }.build()
    ))

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = this
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        files.setLoadErrorHandlingPolicy(policy); youtube.setLoadErrorHandlingPolicy(policy)
        addonStreams.setLoadErrorHandlingPolicy(policy)
        musicServer.setLoadErrorHandlingPolicy(policy)
        return this
    }
    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(item: MediaItem): MediaSource {
        val mediaItem = fullCopyIfKept(item)
        val uri = mediaItem.localConfiguration?.uri
        if (uri?.toString()?.toHttpUrlOrNull()?.let(MusicServerUris::isRequest) == true) {
            return musicServer.createMediaSource(mediaItem)
        }
        AddonUris.parse(uri)?.let { (addonId, trackId) ->
            return AddonMediaSource(mediaItem, { AddonResolver.streamRow(addonId, trackId) }, addonStreams, fallback = null)
        }
        if (uri?.scheme != YouTubeUris.SCHEME) return files.createMediaSource(mediaItem)
        val key = mediaItem.mediaId
        // An item switched to a better copy while it played reads that copy, already proved;
        // once it is withdrawn the item reads YouTube Music again.
        if (LosslessUpgrade.isMarked(uri)) {
            return AddonMediaSource(mediaItem, { LosslessUpgrade.streamFor(key) }, addonStreams,
                fallback = { youtube.createMediaSource(mediaItem) })
        }
        if (!NetworkMonitor.online.value || !AddonResolver.directStreamActive()) return youtube.createMediaSource(mediaItem)
        return AddonMediaSource(
            mediaItem,
            resolve = {
                // A downloaded track plays from the device: no request, and no add-on quota spent.
                if (isDownloaded(key)) null else AddonResolver.directStream(key, addonTargetOf(mediaItem))
            },
            streams = addonStreams,
            fallback = { youtube.createMediaSource(mediaItem) },
        )
    }

    /**
     * A track asked for at a capped quality still plays its full copy when one is already on the
     * device, downloaded or cached whole: it costs no data and sounds better.
     */
    private fun fullCopyIfKept(item: MediaItem): MediaItem {
        val uri = item.localConfiguration?.uri ?: return item
        if (uri.scheme != YouTubeUris.SCHEME || YouTubeUris.ceiling(uri) == null) return item
        val kept = isDownloaded(item.mediaId) ||
            runCatching { AudioCache.isAvailableOffline(app, item.mediaId) }.getOrDefault(false)
        if (!kept) return item
        return item.buildUpon().setUri(YouTubeUris.withCeiling(uri, null))
            .setCustomCacheKey(YouTubeUris.cacheKey(item.mediaId)).build()
    }

    private fun isDownloaded(trackId: String): Boolean = runCatching {
        DownloadCenter.manager(app).downloadIndex.getDownload(trackId)?.state == Download.STATE_COMPLETED
    }.getOrDefault(false)
}

/** What an add-on is asked to find for a queued track: its credits, runtime and album. */
internal fun addonTargetOf(item: MediaItem): AddonMatcher.Target {
    val metadata = item.mediaMetadata
    val match = item.localConfiguration?.uri?.let(YouTubeUris::matchQuery)
    val durationMs = metadata.extras?.getInt("duration")?.takeIf { it > 0 } ?: match?.durationMs ?: 0
    return AddonMatcher.Target(
        title = metadata.title?.toString()?.ifBlank { null } ?: match?.title.orEmpty(),
        artist = metadata.artist?.toString()?.ifBlank { null } ?: match?.artists.orEmpty(),
        durationSec = (durationMs / 1000).takeIf { it > 0 },
        album = metadata.extras?.getString("album")?.ifBlank { null } ?: match?.album?.ifBlank { null },
    )
}
