package `in`.melo.playback

import android.net.Uri
import android.os.Handler
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import `in`.melo.data.addons.AddonClient
import `in`.melo.data.addons.ResolvedStream
import `in`.melo.data.addons.StreamTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Which queue items are playing from an add-on right now, by media id. */
internal object AddonPlayback {
    val served = ConcurrentHashMap<String, ResolvedStream>()
}

/**
 * Builds the source an add-on answer plays through: a plain file, a DASH manifest
 * or an HLS playlist, read straight from the add-on's host and never cached as
 * the original audio. Protected content is refused: no DRM session exists.
 */
@UnstableApi
internal class AddonStreamSources {
    private val http = OkHttpDataSource.Factory(AddonClient.client)
    private val noDrm = { _: MediaItem -> DrmSessionManager.DRM_UNSUPPORTED }
    private var policy: LoadErrorHandlingPolicy? = null

    fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) { this.policy = policy }

    fun create(original: MediaItem, resolved: ResolvedStream): MediaSource {
        val stream = resolved.stream
        val transport = stream.transport
        val item = original.buildUpon().setUri(Uri.parse(stream.url))
            .setMimeType(
                when (transport) {
                    StreamTransport.Dash -> MimeTypes.APPLICATION_MPD
                    StreamTransport.Hls -> MimeTypes.APPLICATION_M3U8
                    StreamTransport.File -> null
                },
            )
            // Each answer is its own file: a shared key would mix it with the original.
            .setCustomCacheKey(null)
            .build()
        val factory: MediaSource.Factory = when (transport) {
            StreamTransport.Dash -> DashMediaSource.Factory(http)
            StreamTransport.Hls -> HlsMediaSource.Factory(http)
            StreamTransport.File -> ProgressiveMediaSource.Factory(http)
        }
        factory.setDrmSessionManagerProvider(noDrm)
        policy?.let(factory::setLoadErrorHandlingPolicy)
        return factory.createMediaSource(item)
    }
}

/**
 * A queue item whose source is only known once an add-on has answered. The
 * lookup runs off the playback thread; meanwhile the item reports no timeline,
 * as any source still preparing does. [resolve] returning null hands the item
 * to [fallback], which is how a track no add-on holds still plays.
 */
@UnstableApi
internal class AddonMediaSource(
    private var mediaItem: MediaItem,
    private val resolve: suspend () -> ResolvedStream?,
    private val streams: AddonStreamSources,
    private val fallback: (() -> MediaSource)?,
) : CompositeMediaSource<Unit>() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lookup: Job? = null
    private var child: MediaSource? = null
    private var failure: IOException? = null

    override fun getMediaItem(): MediaItem = mediaItem

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean = false

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        val handler = Handler(Util.getCurrentOrMainLooper())
        lookup = scope.launch {
            val resolved = runCatching { withTimeoutOrNull(RESOLVE_BUDGET_MS) { resolve() } }
                .onFailure { Log.w(TAG, "add-on lookup failed: ${it.message}") }
                .getOrNull()
            val asked = coroutineContext[Job]
            handler.post { attach(asked, resolved) }
        }
    }

    private fun attach(asked: Job?, resolved: ResolvedStream?) {
        // Released, or released and prepared again, while the add-on was answering.
        if (asked == null || lookup !== asked) return
        val source = when {
            resolved != null -> {
                AddonPlayback.served[mediaItem.mediaId] = resolved
                streams.create(mediaItem, resolved)
            }
            fallback != null -> {
                AddonPlayback.served.remove(mediaItem.mediaId)
                fallback.invoke()
            }
            else -> {
                failure = IOException("No add-on stream for ${mediaItem.mediaId}")
                return
            }
        }
        child = source
        prepareChildSource(Unit, source)
    }

    override fun maybeThrowSourceInfoRefreshError() {
        failure?.let { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun onChildSourceInfoRefreshed(childSourceId: Unit, mediaSource: MediaSource, newTimeline: Timeline) {
        // The child's item carries the add-on's URL; the queue keeps seeing its own.
        refreshSourceInfo(OwnItemTimeline(newTimeline, mediaItem))
    }

    private class OwnItemTimeline(timeline: Timeline, private val item: MediaItem) : ForwardingTimeline(timeline) {
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
            super.getWindow(windowIndex, window, defaultPositionProjectionUs)
            window.mediaItem = item
            return window
        }
    }

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        checkNotNull(child).createPeriod(id, allocator, startPositionUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        checkNotNull(child).releasePeriod(mediaPeriod)
    }

    override fun releaseSourceInternal() {
        lookup?.cancel()
        lookup = null
        failure = null
        // The answer may expire, so a later prepare asks again.
        child = null
        super.releaseSourceInternal()
    }

    private companion object {
        const val TAG = "MeloAddons"
        /** Past this, the add-on is treated as not holding the track and the fallback plays. */
        const val RESOLVE_BUDGET_MS = 12_000L
    }
}
