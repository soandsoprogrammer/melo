package `in`.melo.presentation.components

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.melo.R
import `in`.melo.data.audioFormatName
import `in`.melo.playback.AudioPipeline
import `in`.melo.playback.EqualizerRuntime
import `in`.melo.presentation.icons.MeloIcons
import `in`.melo.ui.glass.MeloAlertDialog
import `in`.melo.ui.glass.meloMenuColors
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

private const val STAGES = 5

/**
 * The playing track, stage by stage: the stream that was read, the decoder, any
 * change of rate, the processing Melo applies, and the device it leaves through.
 * A line runs down the stage icons and carries a pulse while the track plays.
 *
 * Every value is what the player or Android reports, never a setting read as if
 * it were the result; what is not known yet reads as a dash.
 */
@Composable
fun AudioPipelineDialog(onDismiss: () -> Unit) {
    val pipeline by AudioPipeline.current.collectAsStateWithLifecycle()
    val eq = EqualizerRuntime.manager
    val eqOn by eq.isEnabled.collectAsStateWithLifecycle()
    val eqPreset by eq.currentPresetName.collectAsStateWithLifecycle()
    val bassOn by eq.bassBoostEnabled.collectAsStateWithLifecycle()
    val bassStrength by eq.bassBoostStrength.collectAsStateWithLifecycle()
    val virtualizerOn by eq.virtualizerEnabled.collectAsStateWithLifecycle()
    val virtualizerStrength by eq.virtualizerStrength.collectAsStateWithLifecycle()
    val loudnessOn by eq.loudnessEnhancerEnabled.collectAsStateWithLifecycle()
    val loudnessStrength by eq.loudnessEnhancerStrength.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val density = LocalDensity.current
    val colors = meloMenuColors()
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // The route changes under the dialog when headphones come and go.
    var routeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(audioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { routeTick++ }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { routeTick++ }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }
    val route = remember(routeTick) { currentRoute(audioManager) }
    val mixerRateHz = remember(routeTick) {
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it > 0 }
    }
    val directSupported = remember(routeTick, pipeline.trackEncoding, pipeline.trackRateHz) {
        directSupport(pipeline.trackEncoding, pipeline.trackRateHz)
    }

    val isReducedMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val isSignalActive = pipeline.isPlaying && !isReducedMotion
    val flowAlpha by animateFloatAsState(
        targetValue = if (isSignalActive) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isSignalActive) 450 else 320,
            easing = if (isSignalActive) LinearOutSlowInEasing else FastOutLinearInEasing,
        ),
        label = "pipelineFlowAlpha",
    )
    var pulseProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isSignalActive) {
        pulseProgress = 0f
        if (!isSignalActive) return@LaunchedEffect
        val anim = Animatable(0f)
        while (isActive) {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(durationMillis = 2400, easing = LinearEasing)) { pulseProgress = value }
        }
    }

    var columnCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val stageCenters = remember { mutableStateMapOf<Int, Offset>() }
    val interactionRadiusPx = with(density) { 24.dp.toPx() }
    val stageActivation: (Int) -> Float = { idx ->
        stageActivation(idx, pulseProgress, flowAlpha, stageCenters, interactionRadiusPx)
    }
    val onStageIconPositioned: (Int, Offset) -> Unit = { idx, center ->
        // Reported on every layout pass; only a real move should redraw.
        if (stageCenters[idx] != center) stageCenters[idx] = center
    }
    val dash = "—"
    val off = stringResource(R.string.pipeline_off)

    MeloAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_pipeline)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.audio_pipeline_subtitle),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )
                Box(Modifier.fillMaxWidth()) {
                    // The flow redraws every frame while playing; on its own layer, a frame
                    // re-records only these lines, not the rows and the frosted card.
                    Spacer(
                        Modifier
                            .matchParentSize()
                            .graphicsLayer {}
                            .drawBehind {
                                drawSignalFlow(stageCenters, flowAlpha, pulseProgress, interactionRadiusPx, colors.label)
                            },
                    )
                    Column(Modifier.fillMaxWidth().onGloballyPositioned { columnCoordinates = it }) {
                        val section: @Composable (Int, ImageVector, String, @Composable () -> Unit) -> Unit =
                            { index, icon, title, rows ->
                                PipelineRule(colors.separator)
                                PipelineSection(
                                    index, icon, title, colors.label, columnCoordinates,
                                    stageActivation, onStageIconPositioned, rows,
                                )
                            }

                        // 1. The track as it was read.
                        val source = when (pipeline.sourceKind) {
                            AudioPipeline.SourceKind.None -> dash
                            AudioPipeline.SourceKind.Server -> stringResource(R.string.music_server_title)
                            AudioPipeline.SourceKind.Device -> stringResource(R.string.pipeline_source_device)
                            // Some add-ons put account details after their name; the name is enough here.
                            AudioPipeline.SourceKind.Addon -> pipeline.addonName?.substringBefore(" · ")?.trim()
                                ?.ifEmpty { null }
                                ?.let { stringResource(R.string.pipeline_source_addon, it) } ?: dash
                        }
                        val codec = pipeline.mimeType?.let { audioFormatName(it.substringAfter('/')) }
                        val container = pipeline.containerMimeType?.substringAfter('/')?.uppercase()
                            ?.takeIf { codec != null && it != codec }
                        val format = codec?.let { if (container != null) "$it ($container)" else it } ?: dash
                        val channels = when (val c = pipeline.decodedChannels ?: pipeline.channels) {
                            null -> dash
                            1 -> stringResource(R.string.pipeline_mono)
                            2 -> stringResource(R.string.pipeline_stereo)
                            else -> stringResource(R.string.pipeline_surround, c)
                        }
                        section(0, MeloIcons.MusicNote, stringResource(R.string.pipeline_track)) {
                            PipelineRow(stringResource(R.string.pipeline_source), source, colors.label, colors.secondaryLabel)
                            PipelineRow(stringResource(R.string.pipeline_format), format, colors.label, colors.secondaryLabel)
                            // A lossy codec has no depth of its own: the decoder's PCM depth is not the source's.
                            PipelineRow(
                                stringResource(R.string.pipeline_bit_depth),
                                pipeline.sourceBitDepth?.let { "$it-bit" } ?: dash, colors.label, colors.secondaryLabel,
                            )
                            PipelineRow(
                                stringResource(R.string.pipeline_sample_rate),
                                pipeline.sampleRateHz?.let(::rateText) ?: dash, colors.label, colors.secondaryLabel,
                            )
                            PipelineRow(
                                stringResource(R.string.pipeline_bitrate),
                                pipeline.bitrateKbps?.let { "$it kbps" } ?: dash, colors.label, colors.secondaryLabel,
                            )
                            PipelineRow(stringResource(R.string.pipeline_channels), channels, colors.label, colors.secondaryLabel)
                        }

                        // 2. The decoder and the PCM it hands on.
                        val decodedPcm = AudioPipeline.encodingName(pipeline.decodedEncoding)?.let { enc ->
                            pipeline.decodedRateHz?.let { "$enc / ${rateText(it)}" } ?: enc
                        } ?: dash
                        section(1, MeloIcons.Cpu, stringResource(R.string.pipeline_decoder)) {
                            PipelineRow(stringResource(R.string.pipeline_name), pipeline.decoderName ?: dash, colors.label, colors.secondaryLabel)
                            PipelineRow(stringResource(R.string.pipeline_output), decodedPcm, colors.label, colors.secondaryLabel)
                        }

                        // 3. Whether the rate changes between the decoder and the AudioTrack.
                        val inRate = pipeline.decodedRateHz ?: pipeline.sampleRateHz
                        val outRate = pipeline.trackRateHz
                        val inOut = "${inRate?.let(::rateText) ?: dash} → ${outRate?.let(::rateText) ?: dash}"
                        val resampleType = when {
                            inRate == null || outRate == null -> dash
                            inRate == outRate -> stringResource(R.string.pipeline_none)
                            else -> stringResource(R.string.pipeline_resampled)
                        }
                        section(2, MeloIcons.Tune, stringResource(R.string.pipeline_resampler)) {
                            PipelineRow(stringResource(R.string.pipeline_in_out), inOut, colors.label, colors.secondaryLabel)
                            PipelineRow(stringResource(R.string.pipeline_type), resampleType, colors.label, colors.secondaryLabel)
                        }

                        // 4. What Melo does to the samples, in the order they meet it.
                        val effectsOn = eqOn || bassOn || virtualizerOn || loudnessOn
                        val bufferText = bufferText(pipeline) ?: dash
                        val verdict = when (AudioPipeline.alteration(pipeline, effectsOn)) {
                            AudioPipeline.Alteration.None -> stringResource(R.string.pipeline_exact_yes)
                            AudioPipeline.Alteration.Speed -> stringResource(R.string.pipeline_exact_speed)
                            AudioPipeline.Alteration.Normalization -> stringResource(R.string.pipeline_exact_normalization)
                            AudioPipeline.Alteration.Volume -> stringResource(R.string.pipeline_exact_volume)
                            AudioPipeline.Alteration.Effects -> stringResource(R.string.pipeline_exact_effects)
                            AudioPipeline.Alteration.DepthReduced -> stringResource(
                                R.string.pipeline_exact_depth,
                                AudioPipeline.bitsOf(pipeline.decodedEncoding) ?: 0,
                                AudioPipeline.bitsOf(pipeline.trackEncoding) ?: 0,
                            )
                            AudioPipeline.Alteration.Resampled -> stringResource(R.string.pipeline_exact_resampled)
                            AudioPipeline.Alteration.Unknown -> stringResource(R.string.pipeline_exact_unknown)
                        }
                        section(3, MeloIcons.GraphicEq, stringResource(R.string.pipeline_dsp)) {
                            PipelineRow(
                                stringResource(R.string.pipeline_pcm_format),
                                AudioPipeline.encodingName(pipeline.trackEncoding) ?: dash, colors.label, colors.secondaryLabel,
                            )
                            PipelineRow(
                                stringResource(R.string.pipeline_normalization),
                                pipeline.normalizationGainDb?.let { "%+.1f dB".format(Locale.ROOT, it) } ?: off,
                                colors.label, colors.secondaryLabel,
                            )
                            PipelineRow(
                                stringResource(R.string.pipeline_equalizer),
                                if (eqOn) eqPreset.ifBlank { dash } else off, colors.label, colors.secondaryLabel,
                            )
                            PipelineRow(
                                stringResource(R.string.pipeline_bass_boost),
                                if (bassOn) "${bassStrength / 10}%" else off, colors.label, colors.secondaryLabel,
                            )
                            PipelineRow(
                                stringResource(R.string.pipeline_virtualizer),
                                if (virtualizerOn) "${virtualizerStrength / 10}%" else off, colors.label, colors.secondaryLabel,
                            )
                            PipelineRow(
                                stringResource(R.string.pipeline_loudness),
                                if (loudnessOn) "%+.1f dB".format(Locale.ROOT, loudnessStrength / 100f) else off,
                                colors.label, colors.secondaryLabel,
                            )
                            PipelineRow(
                                stringResource(R.string.pipeline_speed),
                                "%.2f×".format(Locale.ROOT, pipeline.speed), colors.label, colors.secondaryLabel,
                            )
                            PipelineRow(stringResource(R.string.pipeline_buffer), bufferText, colors.label, colors.secondaryLabel)
                            PipelineRow(
                                stringResource(R.string.pipeline_output_api),
                                when {
                                    pipeline.trackEncoding == null -> dash
                                    pipeline.offload -> "AudioTrack (offload)"
                                    pipeline.tunneling -> "AudioTrack (tunneled)"
                                    else -> "AudioTrack"
                                },
                                colors.label, colors.secondaryLabel,
                            )
                            PipelineRow(stringResource(R.string.pipeline_bit_exact), verdict, colors.label, colors.secondaryLabel)
                        }

                        // 5. Where it leaves, and Android's mixer after Melo.
                        val routeName = stringResource(route.kind)
                        val trackText = AudioPipeline.encodingName(pipeline.trackEncoding)?.let { enc ->
                            pipeline.trackRateHz?.let { "$enc / ${rateText(it)}" } ?: enc
                        } ?: dash
                        section(4, MeloIcons.VolumeUp, stringResource(R.string.pipeline_output_device)) {
                            PipelineRow(stringResource(R.string.pipeline_device), route.name ?: routeName, colors.label, colors.secondaryLabel)
                            // A named device gets its route under it; otherwise the route is the name.
                            if (route.name != null) {
                                PipelineRow(stringResource(R.string.pipeline_route), routeName, colors.label, colors.secondaryLabel)
                            }
                            directSupported?.let {
                                PipelineRow(
                                    stringResource(R.string.pipeline_direct),
                                    stringResource(if (it) R.string.pipeline_direct_supported else R.string.pipeline_direct_unsupported),
                                    colors.label, colors.secondaryLabel,
                                )
                            }
                            PipelineRow("AudioTrack", trackText, colors.label, colors.secondaryLabel)
                            PipelineRow(
                                stringResource(R.string.pipeline_system_mixer),
                                mixerRateHz?.let { "AudioFlinger ${rateText(it)}" } ?: dash, colors.label, colors.secondaryLabel,
                            )
                            Text(
                                stringResource(R.string.pipeline_mixer_note),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                                color = colors.secondaryLabel,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            MeloButton(
                text = stringResource(R.string.common_done), onClick = onDismiss,
                size = MeloButtonSize.Medium, style = MeloButtonStyle.Text,
            )
        },
    )
}

/** The device audio is going to, and the route it is on. */
private class OutputRoute(val kind: Int, val name: String?)

private fun routeKind(type: Int): Int = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> R.string.pipeline_route_speaker
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> R.string.pipeline_route_wired
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST -> R.string.pipeline_route_bluetooth
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> R.string.pipeline_route_usb
    AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> R.string.pipeline_route_hdmi
    else -> R.string.pipeline_route_other
}

/** Lower is preferred: the order Android routes media in when several outputs are connected. */
private fun routePriority(type: Int): Int = when (routeKind(type)) {
    R.string.pipeline_route_bluetooth -> 0
    R.string.pipeline_route_usb -> 1
    R.string.pipeline_route_wired -> 2
    R.string.pipeline_route_hdmi -> 3
    R.string.pipeline_route_speaker -> if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) 4 else 6
    else -> 5
}

private val MediaAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

private fun currentRoute(audioManager: AudioManager): OutputRoute {
    val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    // Android names the media route itself from 13 on; before that, the likeliest output stands in.
    val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching {
            audioManager.getAudioDevicesForAttributes(MediaAttributes).firstOrNull()?.let { attrs ->
                outputs.firstOrNull { it.type == attrs.type && (attrs.address.isBlank() || it.address == attrs.address) }
                    ?: outputs.firstOrNull { it.type == attrs.type }
            }
        }.getOrNull()
    } else null
    val device = routed ?: outputs.minByOrNull { routePriority(it.type) }
        ?: return OutputRoute(R.string.pipeline_route_other, null)
    val kind = routeKind(device.type)
    // The phone's own speaker reports the phone model as its name, which says nothing useful.
    val name = device.productName?.toString()?.trim()
        ?.takeIf { it.isNotEmpty() && kind != R.string.pipeline_route_speaker }
    return OutputRoute(kind, name)
}

/** Whether the route could take this PCM without the mixer; null before Android 13, which cannot say. */
private fun directSupport(encoding: Int?, rateHz: Int?): Boolean? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || encoding == null || rateHz == null) return null
    return runCatching {
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(rateHz)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        AudioManager.getDirectPlaybackSupport(format, MediaAttributes) != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
    }.getOrNull()
}

/** The AudioTrack's buffer as time and frames. */
private fun bufferText(s: AudioPipeline.Snapshot): String? {
    val bytes = s.trackBufferBytes ?: return null
    val rate = s.trackRateHz?.takeIf { it > 0 } ?: return null
    val bytesPerSample = (AudioPipeline.bitsOf(s.trackEncoding) ?: return null) / 8
    val frameBytes = bytesPerSample * (s.decodedChannels ?: s.channels ?: 2)
    if (frameBytes <= 0) return null
    val frames = bytes / frameBytes
    return "${frames * 1000L / rate} ms, $frames frames"
}

/** 44100 → "44.1 kHz". */
private fun rateText(hz: Int): String =
    "${"%.1f".format(Locale.ROOT, hz / 1000f).removeSuffix(".0")} kHz"

/** The signal line, the travelling pulse and the node rings behind the five stage icons. */
private fun DrawScope.drawSignalFlow(
    stageCenters: Map<Int, Offset>,
    flowAlpha: Float,
    pulseProgress: Float,
    interactionRadiusPx: Float,
    ink: Color,
) {
    if (stageCenters.size < STAGES) return
    val points = (0 until STAGES).map { stageCenters[it] ?: return }
    val x = points[0].x
    val y0 = points[0].y
    val totalHeight = points.last().y - y0
    if (totalHeight <= 0f) return
    val nodeRadius = 11.5.dp.toPx()

    // 1. The line between the nodes, stopping at their rims.
    val busAlpha = if (flowAlpha > 0f) 0.22f + 0.06f * flowAlpha else 0.18f
    for (i in 0 until STAGES - 1) {
        val yStart = points[i].y + nodeRadius
        val yEnd = points[i + 1].y - nodeRadius
        if (yEnd <= yStart) continue
        drawLine(ink.copy(alpha = busAlpha), Offset(x, yStart), Offset(x, yEnd), 2.dp.toPx(), StrokeCap.Butt)
        if (flowAlpha > 0f) {
            drawLine(ink.copy(alpha = 0.05f * flowAlpha), Offset(x, yStart), Offset(x, yEnd), 4.5.dp.toPx(), StrokeCap.Butt)
        }
    }

    // 2. The pulse running down it while the track plays.
    if (flowAlpha > 0f && pulseProgress <= 0.95f) {
        val visibility = (if (pulseProgress <= 0.85f) 1f else (1f - (pulseProgress - 0.85f) / 0.10f).coerceIn(0f, 1f)) * flowAlpha
        if (visibility > 0.01f) {
            val yPulse = y0 + (pulseProgress / 0.85f).coerceIn(0f, 1f) * totalHeight
            val tail = 38.dp.toPx()
            val lead = 8.dp.toPx()
            for (i in 0 until STAGES - 1) {
                val yStart = points[i].y + nodeRadius
                val yEnd = points[i + 1].y - nodeRadius
                if (yEnd <= yStart) continue
                val from = max(yStart, yPulse - tail)
                val to = min(yEnd, yPulse + lead)
                if (to <= from) continue
                val halo = Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.60f to ink.copy(alpha = 0.14f * visibility),
                    0.88f to ink.copy(alpha = 0.28f * visibility),
                    1.0f to Color.Transparent,
                    startY = yPulse - tail, endY = yPulse + lead,
                )
                drawLine(halo, Offset(x, from), Offset(x, to), 6.dp.toPx(), StrokeCap.Butt)
                val core = Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.50f to ink.copy(alpha = 0.45f * visibility),
                    0.86f to ink.copy(alpha = 0.98f * visibility),
                    1.0f to Color.Transparent,
                    startY = yPulse - tail, endY = yPulse + lead,
                )
                drawLine(core, Offset(x, from), Offset(x, to), 2.25.dp.toPx(), StrokeCap.Butt)
                if (yPulse in yStart..yEnd) {
                    val head = Offset(x, yPulse)
                    drawCircle(
                        Brush.radialGradient(listOf(ink.copy(alpha = 0.35f * visibility), Color.Transparent), head, 7.dp.toPx()),
                        radius = 7.dp.toPx(), center = head,
                    )
                    drawCircle(ink.copy(alpha = 0.98f * visibility), radius = 1.75.dp.toPx(), center = head)
                }
            }
        }
    }

    // 3. A ring at each stage, brightening as the pulse passes.
    for (i in 0 until STAGES) {
        val center = Offset(x, points[i].y)
        val act = stageActivation(i, pulseProgress, flowAlpha, stageCenters, interactionRadiusPx)
        val haloAlpha = 0.10f + 0.24f * act * flowAlpha
        drawCircle(
            Brush.radialGradient(
                0.0f to ink.copy(alpha = haloAlpha * 0.40f),
                0.45f to ink.copy(alpha = haloAlpha),
                1.0f to Color.Transparent,
                center = center, radius = nodeRadius + 5.dp.toPx(),
            ),
            radius = nodeRadius + 5.dp.toPx(), center = center,
        )
        if (flowAlpha > 0f && act > 0.02f) {
            drawCircle(ink.copy(alpha = 0.20f * act * flowAlpha), nodeRadius + 1.2.dp.toPx(), center, style = Stroke(2.dp.toPx()))
        }
        drawCircle(ink.copy(alpha = (0.35f + 0.55f * act * flowAlpha).coerceIn(0f, 1f)), nodeRadius, center, style = Stroke(1.dp.toPx()))
        if (flowAlpha > 0f && act > 0.05f) {
            drawCircle(ink.copy(alpha = 0.60f * act * flowAlpha), nodeRadius, center, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** How lit a stage is: full as the pulse crosses its icon, easing off within [interactionRadiusPx]. */
private fun stageActivation(
    stageIndex: Int,
    progress: Float,
    flowAlpha: Float,
    stageCenters: Map<Int, Offset>,
    interactionRadiusPx: Float,
): Float {
    if (flowAlpha <= 0.001f || stageCenters.size < STAGES) return 0f
    val first = stageCenters[0] ?: return 0f
    val last = stageCenters[STAGES - 1] ?: return 0f
    val totalHeight = last.y - first.y
    if (totalHeight <= 0f) return 0f
    val targetY = stageCenters[stageIndex]?.y ?: return 0f
    val yPulse = if (progress <= 0.85f) first.y + (progress / 0.85f).coerceIn(0f, 1f) * totalHeight else last.y
    val dy = abs(yPulse - targetY)
    if (dy > interactionRadiusPx) return 0f
    val raw = (0.5f - 0.5f * cos((1f - dy / interactionRadiusPx) * Math.PI.toFloat())).coerceIn(0f, 1f)
    // The last stage takes the pulse in, then lets it go.
    val absorption = if (stageIndex == STAGES - 1 && progress > 0.85f) {
        if (progress >= 0.95f) 0f else 1f - (progress - 0.85f) / 0.10f
    } else 1f
    return (raw * absorption * flowAlpha).coerceIn(0f, 1f)
}

/** One stage: an icon on the signal line, its title, and its rows. */
@Composable
private fun PipelineSection(
    stageIndex: Int,
    icon: ImageVector,
    title: String,
    ink: Color,
    columnCoordinates: LayoutCoordinates?,
    activation: (Int) -> Float,
    onIconPositioned: (Int, Offset) -> Unit,
    content: @Composable () -> Unit,
) {
    var iconCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    fun report(child: LayoutCoordinates?, parent: LayoutCoordinates?) {
        if (child == null || parent == null || !child.isAttached || !parent.isAttached) return
        onIconPositioned(stageIndex, parent.localPositionOf(child, Offset(child.size.width / 2f, child.size.height / 2f)))
    }
    // The column may not have been placed on the first pass.
    LaunchedEffect(iconCoordinates, columnCoordinates) { report(iconCoordinates, columnCoordinates) }

    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                icon, null, tint = ink,
                modifier = Modifier
                    .size(15.dp)
                    // A section growing as facts arrive moves every icon under it.
                    .onGloballyPositioned { iconCoordinates = it; report(it, columnCoordinates) }
                    .graphicsLayer { alpha = (0.80f + 0.20f * activation(stageIndex)).coerceIn(0f, 1f) },
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 11.5.sp, fontWeight = FontWeight.W600, letterSpacing = 0.5.sp,
                ),
                color = ink,
                modifier = Modifier
                    .padding(top = 3.dp)
                    .graphicsLayer { alpha = (0.65f + 0.32f * activation(stageIndex)).coerceIn(0f, 1f) },
            )
            Spacer(Modifier.height(7.dp))
            content()
        }
    }
}

@Composable
private fun PipelineRow(label: String, value: String, ink: Color, secondary: Color) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = ink)) { append("$label: ") }
            withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = secondary)) { append(value) }
        },
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
    )
}

/** Hairline between stages, starting at the text so it clears the signal line. */
@Composable
private fun PipelineRule(color: Color) {
    Box(Modifier.padding(start = 38.dp).fillMaxWidth().height(0.5.dp).background(color))
}
