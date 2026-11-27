package `in`.melo.presentation.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import `in`.melo.data.canvas.CanvasArtwork
import `in`.melo.data.model.Song
import `in`.melo.data.model.toMusic
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * The picked lines on the share video's clock: which stretches of the track
 * play, back to back, and when each line is lit in the result.
 */
internal class ShareTimeline(
    /** Start and end of each stretch of the track, in track milliseconds. */
    val segments: List<Pair<Long, Long>>,
    /** Each sung line, by its index in the request, and when it is lit, in output μs. */
    val windows: List<LineWindow>,
    val durationMs: Long,
) {
    /** Where in the track the video is at [outputUs]: the stretches laid end to end. */
    fun trackMsAt(outputUs: Long): Long {
        var left = (outputUs / 1000).coerceAtLeast(0L)
        for ((start, end) in segments) {
            val length = end - start
            if (left < length) return start + left
            left -= length
        }
        return segments.lastOrNull()?.second ?: 0L
    }
}

internal class LineWindow(val line: Int, val fromUs: Long, val toUs: Long)

/**
 * The timeline for [lines], or null when they carry no timing (unsynced
 * lyrics). Lines that run on from each other share one stretch, so a verse
 * plays as one take; a skip ("(...)" on the card) cuts straight to the next.
 */
internal fun shareTimeline(lines: List<LyricsShareLine>): ShareTimeline? {
    val segments = mutableListOf<Pair<Long, Long>>()
    val windows = mutableListOf<LineWindow>()
    var cursor = 0L
    lines.forEachIndexed { index, line ->
        val start = line.startMs ?: return@forEachIndexed
        val room = MAX_SHARE_MS - cursor
        if (room <= MIN_LINE_MS) return@forEachIndexed
        val end = minOf(line.endMs ?: return@forEachIndexed, start + room)
        if (end - start < MIN_LINE_MS) return@forEachIndexed
        val last = segments.lastOrNull()
        if (last != null && last.second == start) {
            segments[segments.lastIndex] = last.first to end
        } else {
            segments += start to end
        }
        windows += LineWindow(index, cursor * 1000, (cursor + end - start) * 1000)
        cursor += end - start
    }
    return if (windows.isEmpty()) null else ShareTimeline(segments, windows, cursor)
}

/**
 * The lyrics card as an MP4, ready for a story: under the card, the song's moving cover when
 * [clip] is given, blurred and dimmed a little so white type stays legible, or else the song's
 * blurred colours, still. The cover loops underneath for as long as the lines take.
 *
 * When the lines are synced the video is the song itself: the stretches the lines are sung over
 * are cut from the same server audio playback reads (a Spotify track is matched to it
 * first), and [painter] is asked for the card with each line lit as the vocal reaches it. If the
 * audio can't be had the same video is made silent; unsynced lyrics get the still card, held for
 * [MIN_LENGTH_MS].
 *
 * Returns the file in the share folder, or null when nothing could be exported.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal suspend fun exportLyricsVideo(
    context: Context,
    request: LyricsShareRequest,
    clip: CanvasArtwork?,
    /** The card without its backdrop, drawn at any moment of the song. */
    painter: LyricsCardPainter,
    /** A story: Apple's 3:4 rendition crops to 9:16 with less lost than its square one. */
    portrait: Boolean,
): File? {
    val folder = File(context.cacheDir, SHARE_FOLDER).apply { mkdirs() }
    val out = File(folder, VIDEO_FILE_NAME)
    val timeline = shareTimeline(request.lines)
    // H.264 wants even sides; the card's height is whatever its lines came to.
    val width = painter.width and 1.inv()
    val height = painter.height and 1.inv()
    val overlay = SyncedOverlay(painter, timeline, width, height)
    val lengthMs = timeline?.durationMs ?: MIN_LENGTH_MS

    // The still backdrop, as an image the export can hold for the video's length.
    val backdrop = if (clip == null) {
        val cover = request.artworkUrl?.let { loadArtwork(context, it) }
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(folder, BACKDROP_FILE_NAME)
                val picture = renderLyricsShareBackdrop(cover, request.artworkUrl, height)
                file.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                file
            }.getOrNull()
        } ?: return null
    } else {
        null
    }
    val urls = clip?.let {
        listOfNotNull(it.portraitUrl.takeIf { portrait }, it.url, it.fallbackUrl).distinct()
    }

    val audio = timeline?.let { songAudio(context, request.song) }?.let { (factory, uri) ->
        ShareAudio(factory, uri, timeline, request.syncOffsetMs)
    }

    suspend fun attempt(withAudio: ShareAudio?): Boolean {
        if (backdrop != null) {
            val still = EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(Uri.fromFile(backdrop))
                    .setMimeType(MimeTypes.IMAGE_PNG)
                    .setImageDurationMs(lengthMs)
                    .build(),
            )
                .setFrameRate(FRAME_RATE)
                .setEffects(Effects(emptyList(), listOf(fit(width, height))))
                .build()
            return transform(context, EditedMediaItemSequence.Builder(still).build(), withAudio, overlay, out)
        }
        for (url in urls.orEmpty()) {
            val item = EditedMediaItem.Builder(MediaItem.fromUri(url))
                .setRemoveAudio(true)
                .setEffects(clipEffects(width, height))
                .build()
            val video = if (withAudio != null) {
                // The song sets the length; the cover loops under it.
                EditedMediaItemSequence.Builder(item).setIsLooping(true).build()
            } else {
                val length = probeLengthMs(context, url) ?: continue
                val repeats = ceil(lengthMs / length.toDouble()).toInt().coerceIn(1, MAX_REPEATS)
                EditedMediaItemSequence.Builder(List(repeats) { item }).build()
            }
            if (transform(context, video, withAudio, overlay, out)) return true
        }
        return false
    }

    if (audio != null && attempt(audio)) return out
    return if (attempt(null)) out else null
}

/** Scaled and cropped to fill the card's frame. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun fit(width: Int, height: Int) =
    Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)

/**
 * Blurred at a quarter of the size and scaled back up: the same picture as blurring the full frame
 * with four times the radius, for a sixteenth of the pixels. Then dimmed, under white type.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun clipEffects(width: Int, height: Int) = Effects(
    emptyList(),
    listOf(
        fit((width / 4) and 1.inv(), (height / 4) and 1.inv()),
        GaussianBlur(BLUR_SIGMA),
        fit(width, height),
        RgbAdjustment.Builder().setRedScale(DIM).setGreenScale(DIM).setBlueScale(DIM).build(),
    ),
)

/** The clip's length, read off a muted player that is released straight after. */
@androidx.annotation.OptIn(UnstableApi::class)
private suspend fun probeLengthMs(context: Context, url: String): Long? = withContext(Dispatchers.Main) {
    val player = ExoPlayer.Builder(context).build()
    try {
        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && cont.isActive) {
                            cont.resume(player.duration.takeIf { it != C.TIME_UNSET && it > 0 })
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
                player.volume = 0f
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
            }
        }
    } finally {
        player.release()
    }
}

/**
 * The card over every frame, drawn at the moment of the song that frame plays, the sweep moving
 * as it does on the lyrics screen. One bitmap, redrawn in place on the export's GL thread; the
 * redraw changes its generation id, which is what tells the overlay to upload it. Without a
 * timeline (unsynced lyrics) the still card, drawn once.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class SyncedOverlay(
    private val painter: LyricsCardPainter,
    private val timeline: ShareTimeline?,
    width: Int,
    height: Int,
) : BitmapOverlay() {
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private var drawnAt: Long? = Long.MIN_VALUE

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val at = timeline?.trackMsAt(presentationTimeUs)
        if (at != drawnAt) {
            painter.render(at, bitmap)
            drawnAt = at
        }
        return bitmap
    }
}

/**
 * Which items are the song: those go through the song's own reader, everything else (the
 * backdrop picture) through a plain one.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class RoutingSourceFactory(
    private val plain: MediaSource.Factory,
    private val song: MediaSource.Factory,
) : MediaSource.Factory {
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        plain.setDrmSessionManagerProvider(provider)
        song.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        plain.setLoadErrorHandlingPolicy(policy)
        song.setLoadErrorHandlingPolicy(policy)
    }

    override fun getSupportedTypes(): IntArray = plain.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        if (mediaItem.localConfiguration?.tag === SongAudio) song.createMediaSource(mediaItem)
        else plain.createMediaSource(mediaItem)
}

/** Marks the song's own items for [RoutingSourceFactory]. */
private object SongAudio

/** Where the share video's audio is read from, and which stretches of it. */
private class ShareAudio(val factory: DataSource.Factory, val uri: Uri, val timeline: ShareTimeline, val offsetMs: Long)

/** Uses the same server or device audio as playback. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun songAudio(context: Context, song: Song): Pair<DataSource.Factory, Uri>? {
    if (song.isLocal) {
        val uri = (song.mediaUri ?: song.id).takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
        return DefaultDataSource.Factory(context) to uri
    }
    val server = `in`.melo.data.server.MusicServer.get(context)
    val uri = `in`.melo.data.server.MusicServerUris.playback(song.toMusic(), server.library.value) ?: return null
    return `in`.melo.playback.ServerAudioSource.factory(context) to uri
}

@androidx.annotation.OptIn(UnstableApi::class)
private suspend fun transform(
    context: Context,
    video: EditedMediaItemSequence,
    /** The song and the stretches to cut from it; null for a silent video. */
    audio: ShareAudio?,
    overlay: BitmapOverlay,
    out: File,
): Boolean {
    val sequences = mutableListOf(video)
    var songFactory: MediaSource.Factory = DefaultMediaSourceFactory(context)
    if (audio != null) {
        val pieces = audio.timeline.segments.map { (start, end) ->
            EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(audio.uri)
                    .setTag(SongAudio)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            // Line times are lyric time; the song plays them earlier by the offset.
                            .setStartPositionMs((start - audio.offsetMs).coerceAtLeast(0L))
                            .setEndPositionMs((end - audio.offsetMs).coerceAtLeast(1L))
                            .build(),
                    )
                    .build(),
            )
                .setRemoveVideo(true)
                .build()
        }
        sequences += EditedMediaItemSequence.Builder(pieces).build()
        songFactory = DefaultMediaSourceFactory(audio.factory)
    }
    // The card goes on over the whole composition, so its clock is the video's own.
    val composition = Composition.Builder(sequences)
        .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlay)))))
        .build()

    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setAssetLoaderFactory(
                    DefaultAssetLoaderFactory(
                        context,
                        DefaultDecoderFactory.Builder(context).build(),
                        Clock.DEFAULT,
                        RoutingSourceFactory(DefaultMediaSourceFactory(context), songFactory),
                        DataSourceBitmapLoader(context),
                    ),
                )
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build(),
                        )
                        .build(),
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        Log.w(TAG, "lyrics video export failed (audio=${audio != null}): ${exportException.errorCodeName}")
                        if (cont.isActive) cont.resume(false)
                    }
                })
                .build()
            out.delete()
            transformer.start(composition, out.path)
            // The sheet closed mid-export: stop encoding, on the thread it runs on.
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
        }
    }
}

/** The still card written to the share folder as a PNG, or null. */
internal suspend fun writeLyricsImage(context: Context, card: Bitmap): File? = withContext(Dispatchers.IO) {
    runCatching {
        val folder = File(context.cacheDir, SHARE_FOLDER).apply { mkdirs() }
        File(folder, IMAGE_FILE_NAME).also { file ->
            file.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }.getOrNull()
}

/** [file] handed to another app through the share folder. */
internal fun shareFileIntent(context: Context, file: File, mime: String): Intent? = runCatching {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}.getOrNull()

/**
 * Copies [file] into the device's Movies/Melo (a video) or Pictures/Melo (an image), where a
 * gallery will find it.
 */
internal suspend fun saveToGallery(context: Context, file: File, label: String, video: Boolean): Boolean =
    withContext(Dispatchers.IO) {
        val slug = label.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').lowercase(Locale.ROOT).take(60)
        val name = "melo-lyrics-$slug-${System.currentTimeMillis() / 1000}.${if (video) "mp4" else "png"}"
        val mime = if (video) VIDEO_MIME else IMAGE_MIME
        val directory = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                // Pending while it is written: the gallery doesn't list a row until it is cleared,
                // so "Saved" never names a half-copied file.
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/Melo")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri: Uri = resolver.insert(collection, values) ?: error("no row")
                try {
                    val written = resolver.openOutputStream(uri)?.use { sink ->
                        file.inputStream().use { it.copyTo(sink) }
                    } ?: error("no stream")
                    check(written == file.length()) { "short write" }
                    val published = resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null,
                    )
                    check(published == 1) { "not published" }
                } catch (e: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(directory), "Melo")
                dir.mkdirs()
                file.copyTo(File(dir, name), overwrite = true)
            }
            true
        }.getOrDefault(false)
    }

internal const val VIDEO_MIME = "video/mp4"
internal const val IMAGE_MIME = "image/png"

private const val SHARE_FOLDER = "shared"
private const val VIDEO_FILE_NAME = "lyrics.mp4"
private const val IMAGE_FILE_NAME = "lyrics.png"

/** Long enough to read five lines in a story before it loops. */
private const val MIN_LENGTH_MS = 10_000L

/** Instagram's limit for one story clip. */
internal const val MAX_SHARE_MS = 60_000L

/** Shorter than this and a "line" is a stamp glitch, not something sung. */
private const val MIN_LINE_MS = 300L

private const val FRAME_RATE = 30
private const val BACKDROP_FILE_NAME = "lyrics-backdrop.png"

/** A silent video repeats a short clip at most this often to reach its length. */
private const val MAX_REPEATS = 6
private const val PROBE_TIMEOUT_MS = 10_000L

/** The moving cover's blur, at a quarter of the frame, and how far it is dimmed under the type. */
private const val BLUR_SIGMA = 12f
private const val DIM = 0.6f
private const val TAG = "LyricsShareVideo"

/** Comfortably past what Instagram re-encodes a 1080p story to. */
private const val VIDEO_BITRATE = 10_000_000
