package `in`.melo.presentation.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.my.kizzy.rpc.KizzyRPC
import `in`.melo.MeloModel
import `in`.melo.R
import `in`.melo.data.discord.DiscordAccount
import `in`.melo.data.discord.DiscordAccountStore
import `in`.melo.data.discord.DiscordRPC
import `in`.melo.data.discord.DiscordTrack
import `in`.melo.data.discord.SuperProperties
import `in`.melo.presentation.components.MeloButton
import `in`.melo.presentation.components.MeloButtonSize
import `in`.melo.presentation.components.MeloButtonStyle
import `in`.melo.presentation.components.MeloCard
import `in`.melo.presentation.icons.MeloIcons
import `in`.melo.presentation.navigation.Screen
import `in`.melo.presentation.navigation.navigateSafely
import `in`.melo.ui.glass.MeloAlertDialog
import `in`.melo.ui.theme.MeloLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DiscordDialog { TOKEN, STATUS, ACTIVITY_TYPE, ACTIVITY_NAME, BUTTON_1, BUTTON_2 }

/**
 * Discord rich presence: the connected account, what the card shows, and a preview
 * of it built from the playing track.
 */
@Composable
internal fun DiscordSettings(model: MeloModel, navController: NavHostController) {
    val context = LocalContext.current
    val store = remember { DiscordAccountStore.get(context) }
    val account by store.account.collectAsStateWithLifecycle()
    val settings by model.ui.settings.collectAsStateWithLifecycle()
    val player by model.ui.stablePlayerState.collectAsStateWithLifecycle()
    val position by model.ui.currentPlaybackPosition.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<DiscordDialog?>(null) }
    val connected = account != null
    val cardEnabled = connected && settings.discordRpcEnabled
    val appName = stringResource(R.string.app_name).removeSuffix(" Dev")

    // The profile is fetched again on each visit, so a renamed account or new avatar shows up.
    val token = account?.token
    LaunchedEffect(token) {
        if (token == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            KizzyRPC.getUserInfo(token, SuperProperties.userAgent, SuperProperties.superPropertiesBase64)
        }.onSuccess { store.updateProfile(it.username, it.name, it.avatar) }
    }

    val statusOptions = linkedMapOf(
        "online" to stringResource(R.string.discord_status_online),
        "idle" to stringResource(R.string.discord_status_idle),
        "dnd" to stringResource(R.string.discord_status_dnd),
    )
    val statusDetails = mapOf(
        "online" to stringResource(R.string.discord_status_online_detail),
        "idle" to stringResource(R.string.discord_status_idle_detail),
        "dnd" to stringResource(R.string.discord_status_dnd_detail),
    )
    val activityOptions = linkedMapOf(
        "listening" to stringResource(R.string.discord_activity_listening),
        "playing" to stringResource(R.string.discord_activity_playing),
        "watching" to stringResource(R.string.discord_activity_watching),
        "competing" to stringResource(R.string.discord_activity_competing),
    )
    val verbs = mapOf(
        "listening" to stringResource(R.string.discord_verb_listening),
        "playing" to stringResource(R.string.discord_verb_playing),
        "watching" to stringResource(R.string.discord_verb_watching),
        "competing" to stringResource(R.string.discord_verb_competing),
    )
    val activityName = settings.discordActivityName.ifEmpty { appName }
    val activityDetails = activityOptions.keys.associateWith { "\"${verbs[it]} $activityName\"" }

    SettingsScaffold(stringResource(R.string.discord), navController) {
        // The account: a picture with a presence dot, then who it is.
        MeloCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = if (connected) null else ({ navController.navigateSafely(Screen.DiscordLogin.route) }),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 76.dp)
                    .padding(horizontal = MeloLayout.PageGutter, vertical = MeloLayout.Space3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MeloLayout.Space3),
            ) {
                DiscordAvatar(account, settings.discordStatus)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val current = account
                    Text(
                        when {
                            current == null -> stringResource(R.string.discord_not_connected)
                            current.name.isNotBlank() -> current.name
                            else -> stringResource(R.string.discord_connected)
                        },
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            current == null -> stringResource(R.string.discord_tap_to_sign_in)
                            current.username.isNotBlank() -> "@${current.username}"
                            else -> stringResource(R.string.discord_account)
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!connected) SettingsChevron()
            }
        }

        if (!connected) {
            SettingsSection(title = null, footer = stringResource(R.string.discord_sign_in_footer)) {
                SettingsItem(
                    title = stringResource(R.string.discord_enter_token_instead),
                    subtitle = stringResource(R.string.discord_enter_token_instead_subtitle),
                    leadingIcon = { Icon(MeloIcons.Edit, null, tint = MaterialTheme.colorScheme.onSurface) },
                    onClick = { dialog = DiscordDialog.TOKEN },
                )
            }
        }

        if (!settings.discordInfoDismissed) {
            MeloCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(MeloLayout.PageGutter),
                    verticalArrangement = Arrangement.spacedBy(MeloLayout.Space3),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MeloLayout.Space3)) {
                        Icon(MeloIcons.ErrorOutline, null, tint = MaterialTheme.colorScheme.tertiary)
                        Text(
                            stringResource(R.string.discord_token_warning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        MeloButton(
                            text = stringResource(R.string.discord_got_it),
                            onClick = { model.ui.updateSettings { it.copy(discordInfoDismissed = true) } },
                            size = MeloButtonSize.Medium,
                            style = MeloButtonStyle.Tonal,
                        )
                    }
                }
            }
        }

        SettingsSection(title = stringResource(R.string.discord_rich_presence), footer = stringResource(R.string.discord_presence_footer)) {
            SwitchSettingItem(
                title = stringResource(R.string.discord_show_what_playing),
                subtitle = if (connected) "" else stringResource(R.string.discord_connect_account_first),
                checked = settings.discordRpcEnabled && connected,
                onCheckedChange = { on -> model.ui.updateSettings { it.copy(discordRpcEnabled = on) } },
                leadingIcon = { Icon(painterResource(R.drawable.ic_discord), null, tint = MaterialTheme.colorScheme.onSurface) },
                enabled = connected,
            )
            SwitchSettingItem(
                title = stringResource(R.string.discord_lead_with_song),
                subtitle = stringResource(R.string.discord_lead_with_song_subtitle),
                checked = settings.discordUseDetails,
                onCheckedChange = { on -> model.ui.updateSettings { it.copy(discordUseDetails = on) } },
                leadingIcon = { Icon(MeloIcons.MusicNote, null, tint = MaterialTheme.colorScheme.onSurface) },
                enabled = cardEnabled,
            )
            SwitchSettingItem(
                title = stringResource(R.string.discord_show_audio_quality),
                subtitle = stringResource(R.string.discord_show_audio_quality_subtitle),
                checked = settings.discordShowAudioQuality,
                onCheckedChange = { on -> model.ui.updateSettings { it.copy(discordShowAudioQuality = on) } },
                leadingIcon = { Icon(MeloIcons.Tune, null, tint = MaterialTheme.colorScheme.onSurface) },
                enabled = cardEnabled,
            )
            SwitchSettingItem(
                title = stringResource(R.string.discord_customize_card),
                subtitle = stringResource(R.string.discord_customize_card_subtitle),
                checked = settings.discordAdvancedMode,
                onCheckedChange = { on -> model.ui.updateSettings { it.copy(discordAdvancedMode = on) } },
                leadingIcon = { Icon(MeloIcons.Edit, null, tint = MaterialTheme.colorScheme.onSurface) },
                enabled = cardEnabled,
            )
        }

        if (settings.discordAdvancedMode && cardEnabled) {
            SettingsSection(title = stringResource(R.string.discord_presence)) {
                ValueRow(stringResource(R.string.discord_status), statusOptions[settings.discordStatus].orEmpty()) {
                    dialog = DiscordDialog.STATUS
                }
                ValueRow(stringResource(R.string.discord_activity), activityOptions[settings.discordActivityType].orEmpty()) {
                    dialog = DiscordDialog.ACTIVITY_TYPE
                }
                MeloCard(modifier = Modifier.fillMaxWidth(), onClick = { dialog = DiscordDialog.ACTIVITY_NAME }) {
                    SettingsRow(title = stringResource(R.string.discord_name), subtitle = activityName, leadingIcon = null) {
                        SettingsChevron()
                    }
                }
            }
            SettingsSection(title = stringResource(R.string.discord_buttons), footer = stringResource(R.string.discord_buttons_footer)) {
                ButtonRow(
                    title = stringResource(R.string.discord_first_button),
                    text = settings.discordButton1Text.ifEmpty { DiscordRPC.DEFAULT_BUTTON_1 },
                    visible = settings.discordButton1Visible,
                    onVisibleChange = { on -> model.ui.updateSettings { it.copy(discordButton1Visible = on) } },
                    onClick = { dialog = DiscordDialog.BUTTON_1 },
                )
                ButtonRow(
                    title = stringResource(R.string.discord_second_button),
                    text = settings.discordButton2Text.ifEmpty { DiscordRPC.DEFAULT_BUTTON_2 },
                    visible = settings.discordButton2Visible,
                    onVisibleChange = { on -> model.ui.updateSettings { it.copy(discordButton2Visible = on) } },
                    onClick = { dialog = DiscordDialog.BUTTON_2 },
                )
            }
        }

        val song = player.currentSong
        val track = song?.let {
            DiscordTrack(
                title = it.title,
                artist = it.displayArtist,
                album = it.album,
                artworkUrl = it.albumArtUriString,
                trackId = it.id.takeIf { id -> id.startsWith("spotify:track:") },
            )
        }
        SettingsSection(
            title = stringResource(R.string.discord_preview),
            footer = if (track == null) stringResource(R.string.discord_play_to_preview) else null,
        ) {
            MeloCard(modifier = Modifier.fillMaxWidth()) { RichPresencePreview(
                track = track,
                verb = verbs[settings.discordActivityType] ?: verbs.getValue("listening"),
                activityName = activityName,
                useDetails = settings.discordUseDetails,
                positionMs = position,
                durationMs = player.totalDuration,
                buttons = buildList {
                    if (track == null) return@buildList
                    val watch = DiscordRPC.watchUrl(track)
                    if (settings.discordButton1Visible && watch != null) add(
                        DiscordRPC.resolveVariables(settings.discordButton1Text.ifEmpty { DiscordRPC.DEFAULT_BUTTON_1 }, track) to watch,
                    )
                    if (settings.discordButton2Visible) add(
                        DiscordRPC.resolveVariables(settings.discordButton2Text.ifEmpty { DiscordRPC.DEFAULT_BUTTON_2 }, track) to DiscordRPC.PROJECT_URL,
                    )
                },
            ) }
        }

        if (connected) SettingsDestructiveRow(stringResource(R.string.discord_disconnect)) { store.clear(); forgetDiscordWebSession() }
    }

    when (dialog) {
        DiscordDialog.TOKEN -> TokenDialog(onDismiss = { dialog = null }, onSaved = { dialog = null }, store = store)
        DiscordDialog.STATUS -> SettingsOptionsSheet(
            title = stringResource(R.string.discord_status),
            options = statusOptions,
            selectedKey = settings.discordStatus,
            details = statusDetails,
            note = stringResource(R.string.discord_status_dialog_message),
            onSelect = { key -> model.ui.updateSettings { it.copy(discordStatus = key) }; dialog = null },
            onDismiss = { dialog = null },
        )
        DiscordDialog.ACTIVITY_TYPE -> SettingsOptionsSheet(
            title = stringResource(R.string.discord_activity),
            options = activityOptions,
            selectedKey = settings.discordActivityType,
            details = activityDetails,
            note = stringResource(R.string.discord_activity_dialog_message),
            onSelect = { key -> model.ui.updateSettings { it.copy(discordActivityType = key) }; dialog = null },
            onDismiss = { dialog = null },
        )
        DiscordDialog.ACTIVITY_NAME -> TextEntryDialog(
            title = stringResource(R.string.discord_name),
            message = stringResource(R.string.discord_activity_name_message, appName),
            initial = settings.discordActivityName,
            placeholder = appName,
            onDismiss = { dialog = null },
            onSave = { text -> model.ui.updateSettings { it.copy(discordActivityName = text.trim()) }; dialog = null },
        )
        DiscordDialog.BUTTON_1 -> TextEntryDialog(
            title = stringResource(R.string.discord_first_button),
            message = stringResource(R.string.discord_button_variables),
            initial = settings.discordButton1Text,
            placeholder = DiscordRPC.DEFAULT_BUTTON_1,
            onDismiss = { dialog = null },
            onSave = { text -> model.ui.updateSettings { it.copy(discordButton1Text = text.trim()) }; dialog = null },
        )
        DiscordDialog.BUTTON_2 -> TextEntryDialog(
            title = stringResource(R.string.discord_second_button),
            message = stringResource(R.string.discord_button_variables),
            initial = settings.discordButton2Text,
            placeholder = DiscordRPC.DEFAULT_BUTTON_2,
            onDismiss = { dialog = null },
            onSave = { text -> model.ui.updateSettings { it.copy(discordButton2Text = text.trim()) }; dialog = null },
        )
        null -> Unit
    }
}

/** The account picture, or Discord's mark, with the chosen presence colour in a ring at its corner. */
@Composable
private fun DiscordAvatar(account: DiscordAccount?, status: String) {
    val colors = MaterialTheme.colorScheme
    val ring = colors.surface
    Box(Modifier.size(52.dp)) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(colors.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            val avatar = account?.avatar
            if (avatar != null) {
                coil.compose.AsyncImage(avatar, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(painterResource(R.drawable.ic_discord), null, Modifier.size(28.dp), tint = colors.onSurfaceVariant)
            }
        }
        if (account != null) {
            val dot = when (status) {
                "idle" -> colors.tertiary
                "dnd" -> colors.error
                else -> colors.primary
            }
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(16.dp)
                    .border(2.5.dp, ring, CircleShape).padding(2.5.dp).clip(CircleShape).background(dot),
            )
        }
    }
}

/** A row showing its current value on the right, opening a picker. */
@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    MeloCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        SettingsRow(title = title, subtitle = null, leadingIcon = null) {
            Text(
                value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            SettingsChevron()
        }
    }
}

/** A presence button: tapping the row edits its text, the switch shows or hides it. */
@Composable
private fun ButtonRow(title: String, text: String, visible: Boolean, onVisibleChange: (Boolean) -> Unit, onClick: () -> Unit) {
    MeloCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        SettingsRow(title = title, subtitle = text, leadingIcon = null) {
            `in`.melo.ui.glass.MeloSwitch(checked = visible, onCheckedChange = onVisibleChange)
        }
    }
}

/** Roughly how the card looks on a Discord profile, so changes can be judged without opening Discord. */
@Composable
private fun RichPresencePreview(
    track: DiscordTrack?,
    verb: String,
    activityName: String,
    useDetails: Boolean,
    positionMs: Long,
    durationMs: Long,
    buttons: List<Pair<String, String>>,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(MeloLayout.PageGutter),
        verticalArrangement = Arrangement.spacedBy(MeloLayout.Space3),
    ) {
        Text(
            "$verb $activityName".uppercase(),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.W700, color = colors.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MeloLayout.Space3), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(84.dp).clip(RoundedCornerShape(6.dp)).background(colors.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                val art = track?.artworkUrl
                if (art != null) coil.compose.AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(painterResource(R.drawable.ic_discord), null, Modifier.size(36.dp), tint = colors.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val title = track?.title ?: "—"
                val artist = track?.artist ?: "—"
                Text(
                    if (useDetails) title else artist,
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (useDetails) artist else title,
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (track != null && track.album.isNotBlank()) Text(
                    track.album, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (track != null && durationMs > 0) {
                    Column(Modifier.padding(top = MeloLayout.Space1), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        LinearProgressIndicator(
                            progress = { (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                            color = colors.primary,
                            trackColor = colors.outline,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(clock(positionMs), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Text(clock(durationMs), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        buttons.forEach { (label, url) ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(8.dp))
                    .background(colors.outline.copy(alpha = 0.35f))
                    .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = MeloLayout.Space3))
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** One line of text with a note above it; an empty value falls back to [placeholder] when used. */
@Composable
private fun TextEntryDialog(
    title: String,
    message: String,
    initial: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    MeloAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MeloLayout.Space3)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            MeloButton(text = stringResource(R.string.common_save), onClick = { onSave(text) }, size = MeloButtonSize.Medium)
        },
        dismissButton = {
            MeloButton(
                text = stringResource(R.string.common_cancel), onClick = onDismiss,
                size = MeloButtonSize.Medium, style = MeloButtonStyle.Text,
            )
        },
    )
}

/** A pasted token, checked against Discord before it is kept. */
@Composable
private fun TokenDialog(onDismiss: () -> Unit, onSaved: () -> Unit, store: DiscordAccountStore) {
    var token by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var rejected by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    MeloAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.discord_token)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MeloLayout.Space3)) {
                Text(stringResource(R.string.discord_token_description), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; rejected = false },
                    label = { Text(stringResource(R.string.discord_token_label)) },
                    singleLine = true,
                    isError = rejected,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (rejected) Text(
                    stringResource(R.string.discord_token_rejected),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            MeloButton(
                text = stringResource(R.string.common_save),
                onClick = {
                    val candidate = token.trim().removeSurrounding("\"")
                    if (candidate.isEmpty() || checking) return@MeloButton
                    checking = true
                    scope.launch {
                        val info = withContext(Dispatchers.IO) {
                            KizzyRPC.getUserInfo(candidate, SuperProperties.userAgent, SuperProperties.superPropertiesBase64)
                        }
                        checking = false
                        info.onSuccess {
                            store.save(DiscordAccount(candidate, it.username, it.name, it.avatar))
                            onSaved()
                        }.onFailure { rejected = true }
                    }
                },
                size = MeloButtonSize.Medium,
            )
        },
        dismissButton = {
            MeloButton(
                text = stringResource(R.string.common_cancel), onClick = onDismiss,
                size = MeloButtonSize.Medium, style = MeloButtonStyle.Text,
            )
        },
    )
}
