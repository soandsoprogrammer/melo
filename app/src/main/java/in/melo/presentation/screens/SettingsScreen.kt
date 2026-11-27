package `in`.melo.presentation.screens

import `in`.melo.ui.glass.MeloSwitch
import `in`.melo.presentation.icons.MeloIcons
import `in`.melo.presentation.icons.meloPainter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import `in`.melo.presentation.components.TrackSwipeAction

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.roundToInt
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.imageLoader
import `in`.melo.presentation.components.PrivacyPolicyLink
import `in`.melo.presentation.components.meloPanel
import `in`.melo.presentation.components.MeloButton
import `in`.melo.presentation.components.MeloButtonSize
import `in`.melo.presentation.components.MeloButtonStyle
import `in`.melo.presentation.components.MeloCard
import `in`.melo.presentation.components.MeloIconButton
import `in`.melo.R
import `in`.melo.MeloModel
import `in`.melo.data.UsageAnalytics
import `in`.melo.data.preferences.AlbumArtPaletteStyle
import `in`.melo.data.preferences.AppSettings
import `in`.melo.data.preferences.lyricsProviderList
import `in`.melo.data.preferences.withLyricsProvider
import `in`.melo.data.preferences.CarouselStyle
import `in`.melo.data.preferences.CollagePattern
import `in`.melo.data.preferences.NavBarStyle
import `in`.melo.data.preferences.ThemePreference
import `in`.melo.presentation.navigation.Screen
import `in`.melo.presentation.navigation.navigateSafely
import `in`.melo.ui.theme.GoogleSansRounded
import `in`.melo.utils.AppLocales
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import `in`.melo.ui.theme.MeloLayout

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsScaffold(title: String, navController: NavHostController, content: @Composable ColumnScope.() -> Unit) {
    `in`.melo.presentation.components.MeloPageScaffold(
        topBar = {
            TopAppBar(
                colors = `in`.melo.presentation.components.meloPageBarColors(),
                title = { Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp)) },
                navigationIcon = {
                    MeloIconButton(
                        icon = MeloIcons.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.padding(start = MeloLayout.Space2),
                    )
                },
            )
        }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                // Room for the mini player and the navigation bar under the last item.
                .padding(PaddingValues(start = MeloLayout.PageGutter, end = MeloLayout.PageGutter, top = MeloLayout.Space2,
                    bottom = MeloLayout.Space8 + `in`.melo.presentation.components.MiniPlayerHeight +
                        androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())),
            verticalArrangement = Arrangement.spacedBy(MeloLayout.Space6),
            content = content,
        )
    }
}

@Composable
private fun CategoryRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    MeloCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        SettingsRow(
            title = title,
            subtitle = subtitle,
            leadingIcon = { Icon(icon, null, Modifier.size(MeloLayout.IconDefault), tint = MaterialTheme.colorScheme.onSurface) },
        ) { SettingsChevron() }
    }
}


@Composable
fun SettingsScreen(model: MeloModel, navController: NavHostController) {
    val settings by model.ui.settings.collectAsStateWithLifecycle()
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val google by model.youTubeAccount.collectAsStateWithLifecycle()
    val spotifyProfile by model.spotifyAccountProfile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun open(id: String) = navController.navigateSafely(Screen.SettingsCategory.createRoute(id))
    SettingsScaffold(stringResource(R.string.common_settings), navController) {
        // Settings open on who is signed in, then the switch used most, then the pages from the
        // everyday ones to the rarely visited ones.
        `in`.melo.presentation.components.MeloGroupedList {
            val services = listOfNotNull(
                stringResource(R.string.search_source_spotify).takeIf { discovery.spotifyEnabled },
                stringResource(R.string.search_source_youtube).takeIf { google != null },
            ).joinToString(" · ")
            val spotify = spotifyProfile.takeIf { discovery.spotifyEnabled }
            SettingsAccountRow(
                title = spotify?.displayName ?: google?.name ?: stringResource(R.string.settings_accounts_title),
                subtitle = services.ifEmpty { stringResource(R.string.settings_account_guest_subtitle) },
                picture = { AccountPicture(spotify?.imageUrl ?: google?.photoUrl) },
                onClick = { open("account") },
            )
        }
        `in`.melo.presentation.components.MeloGroupedList {
            SwitchSettingItem(stringResource(R.string.offline_mode_title), stringResource(R.string.offline_mode_description), settings.offlineMode,
                { enabled -> model.ui.updateSettings { it.copy(offlineMode = enabled) } },
                leadingIcon = { Icon(MeloIcons.Download, null, tint = MaterialTheme.colorScheme.onSurface) })
        }
        SettingsSection(title = stringResource(R.string.settings_group_look)) {
            CategoryRow(stringResource(R.string.settings_category_appearance_title), stringResource(R.string.settings_category_appearance_subtitle), MeloIcons.Palette) { open("appearance") }
            CategoryRow(stringResource(R.string.settings_home_title), stringResource(R.string.settings_home_subtitle), MeloIcons.Home) { open("home") }
            CategoryRow(stringResource(R.string.settings_pages_title), stringResource(R.string.settings_pages_subtitle), MeloIcons.Tune) { open("pages") }
        }
        SettingsSection(title = stringResource(R.string.settings_group_listening)) {
            CategoryRow(stringResource(R.string.settings_category_playback_title), stringResource(R.string.settings_category_playback_subtitle), MeloIcons.MusicNote) { open("playback") }
            CategoryRow(stringResource(R.string.settings_addons_title), stringResource(R.string.settings_addons_subtitle), MeloIcons.Link) { open("addons") }
            CategoryRow(stringResource(R.string.settings_storage_title), stringResource(R.string.settings_storage_subtitle), MeloIcons.Storage) { open("storage") }
        }
        SettingsSection(title = stringResource(R.string.settings_group_app)) {
            CategoryRow(stringResource(R.string.settings_category_behavior_title), stringResource(R.string.settings_category_behavior_subtitle), MeloIcons.Settings) { open("behavior") }
            CategoryRow(stringResource(R.string.discord), stringResource(R.string.discord_settings_subtitle),
                androidx.compose.ui.graphics.vector.ImageVector.vectorResource(R.drawable.ic_discord)) { open("discord") }
            CategoryRow(stringResource(R.string.settings_privacy_title), stringResource(R.string.settings_privacy_subtitle),
                MeloIcons.Lock) { open("privacy") }
            CategoryRow(stringResource(R.string.settings_category_backup_title), stringResource(R.string.settings_category_backup_subtitle), MeloIcons.Backup) { open("backup") }
        }
        SettingsSection(title = stringResource(R.string.settings_group_about)) {
            CategoryRow(stringResource(R.string.update_settings_title),
                stringResource(R.string.update_installed, `in`.melo.BuildConfig.VERSION_NAME),
                MeloIcons.Download) { `in`.melo.update.AppUpdater.get(context).check(manual = true) }
            CategoryRow(stringResource(R.string.settings_discord_title), stringResource(R.string.settings_discord_subtitle),
                MeloIcons.Forum) {
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://discord.gg/h3Hzk4seXj")))
                }
            }
            CategoryRow(stringResource(R.string.diagnostics_title), stringResource(R.string.diagnostics_subtitle),
                MeloIcons.BugReport) { open("diagnostics") }
        }
    }
}

/** A signed-in account's picture filling its circle, or a person glyph when there is none. */
@Composable
private fun AccountPicture(url: String?) {
    if (url != null) coil.compose.AsyncImage(url, null, Modifier.fillMaxSize().clip(CircleShape),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else Icon(MeloIcons.Person, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun SettingsCategoryScreen(model: MeloModel, navController: NavHostController, category: String) {
    when (category) {
        "appearance" -> AppearanceSettings(model, navController)
        "playback" -> PlaybackSettings(model, navController)
        "behavior" -> BehaviorSettings(model, navController)
        "storage" -> StorageSettings(model, navController)
        "audio_cache" -> AudioCacheScreen(model, navController)
        "backup" -> BackupSettings(model, navController)
        "account" -> AccountSettings(model, navController)
        "home" -> HomeSettings(model, navController)
        "pages" -> PageActionsSettings(model, navController)
        "diagnostics" -> DiagnosticsSettings(model, navController)
        "privacy" -> PrivacySettings(model, navController)
        "discord" -> DiscordSettings(model, navController)
        "addons" -> AddonsSettings(model, navController)
    }
}

@Composable
private fun PrivacySettings(model: MeloModel, navController: NavHostController) {
    val settings by model.ui.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_privacy_title), navController) {
        `in`.melo.presentation.components.MeloGroupedList {
        SwitchSettingItem(
            title = stringResource(R.string.settings_analytics_title),
            subtitle = stringResource(R.string.settings_analytics_subtitle),
            checked = settings.analyticsEnabled,
            onCheckedChange = { enabled ->
                model.ui.updateSettings { it.copy(analyticsEnabled = enabled) }
                UsageAnalytics.setEnabled(context, enabled)
            },
            leadingIcon = { Icon(MeloIcons.Lock, null, tint = MaterialTheme.colorScheme.secondary) },
        )
        }
        PrivacyPolicyLink()
    }
}

/** How Melo looks: the theme, the player, lists, the navigation bar and the lyrics screen. */
@Composable
private fun AppearanceSettings(model: MeloModel, navController: NavHostController) {
    val ui = model.ui
    val s by ui.settings.collectAsStateWithLifecycle()
    val update = ui::updateSettings
    SettingsScaffold(stringResource(R.string.settings_category_appearance_title), navController) {
        SettingsSection(
            title = stringResource(R.string.settings_app_icon_title),
            footer = stringResource(R.string.settings_app_icon_footer),
        ) { AppIconPicker() }
        SettingsSection(title = stringResource(R.string.settings_section_theme)) {
            ThemeSelectorItem(
                label = stringResource(R.string.settings_app_theme_title),
                description = stringResource(R.string.settings_app_theme_subtitle),
                options = mapOf(
                    AppSettings.THEME_SYSTEM to stringResource(R.string.settings_language_system),
                    AppSettings.THEME_LIGHT to stringResource(R.string.settings_theme_light),
                    AppSettings.THEME_DARK to stringResource(R.string.settings_theme_dark),
                ),
                selectedKey = s.themeMode,
                onSelectionChanged = { key -> update { it.copy(themeMode = key) } },
                leadingIcon = { Icon(MeloIcons.LightMode, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            ThemeSelectorItem(
                label = stringResource(R.string.settings_app_style_title),
                description = stringResource(R.string.settings_app_style_subtitle),
                options = mapOf(
                    `in`.melo.ui.glass.MeloSurfaceStyle.GLASS.key to stringResource(R.string.settings_app_style_glass),
                    `in`.melo.ui.glass.MeloSurfaceStyle.FROST.key to stringResource(R.string.settings_app_style_frost),
                    `in`.melo.ui.glass.MeloSurfaceStyle.SOLID.key to stringResource(R.string.settings_app_style_solid),
                ),
                selectedKey = s.surfaceStyle,
                onSelectionChanged = { key -> update { it.copy(surfaceStyle = key) } },
                leadingIcon = { Icon(MeloIcons.Tab, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            if (s.surfaceStyle == `in`.melo.ui.glass.MeloSurfaceStyle.GLASS.key) GlassOptionSliders(s, update)
            SwitchSettingItem(stringResource(R.string.settings_ambient_background_title), stringResource(R.string.settings_ambient_background_subtitle), s.ambientBackground, { v -> update { it.copy(ambientBackground = v) } }, leadingIcon = { Icon(MeloIcons.ColorPalette, null, tint = MaterialTheme.colorScheme.secondary) })
            SwitchSettingItem(stringResource(R.string.settings_disable_blur_all_over_title), stringResource(R.string.settings_disable_blur_all_over_subtitle), s.disableBlur, { v -> update { it.copy(disableBlur = v) } }, leadingIcon = { Icon(MeloIcons.BlurOff, null, tint = MaterialTheme.colorScheme.secondary) })
        }
        SettingsSection(title = stringResource(R.string.settings_now_playing_section)) {
            ThemeSelectorItem(
                label = stringResource(R.string.settings_player_theme_title),
                description = stringResource(R.string.settings_player_theme_subtitle),
                options = mapOf(
                    ThemePreference.ALBUM_ART to stringResource(R.string.settings_player_theme_album),
                    ThemePreference.GLOBAL to stringResource(R.string.settings_player_theme_app),
                ),
                selectedKey = s.playerTheme,
                onSelectionChanged = { key -> update { it.copy(playerTheme = key) } },
                leadingIcon = { Icon(MeloIcons.PlayCircle, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            ThemeSelectorItem(
                label = stringResource(R.string.settings_album_art_palette_title),
                description = stringResource(R.string.settings_album_art_palette_subtitle),
                options = AlbumArtPaletteStyle.entries.associate { it.storageKey to stringResource(it.labelRes) },
                selectedKey = s.palette,
                onSelectionChanged = { key -> update { it.copy(palette = key) } },
                leadingIcon = { Icon(MeloIcons.Artwork, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            SwitchSettingItem(
                stringResource(R.string.settings_player_artwork_gradient_title),
                stringResource(R.string.settings_player_artwork_gradient_subtitle),
                s.playerArtworkGradient,
                { value -> update { it.copy(playerArtworkGradient = value) } },
                leadingIcon = { Icon(MeloIcons.Gradient, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            SwitchSettingItem(
                stringResource(R.string.settings_full_screen_cover_art_title),
                stringResource(R.string.settings_full_screen_cover_art_subtitle),
                s.fullScreenCoverArt,
                { value -> update { it.copy(fullScreenCoverArt = value) } },
                leadingIcon = { Icon(meloPainter(R.drawable.rounded_fullscreen_24), null, tint = MaterialTheme.colorScheme.secondary) },
            )
            SwitchSettingItem(
                stringResource(R.string.settings_album_cover_mix_title),
                stringResource(R.string.settings_album_cover_mix_subtitle),
                s.albumCoverMix,
                { value -> update { it.copy(albumCoverMix = value) } },
                leadingIcon = { Icon(MeloIcons.Album, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            if (s.playerArtworkGradient) {
                ThemeSelectorItem(
                    label = stringResource(R.string.settings_player_backdrop_title),
                    description = stringResource(R.string.settings_player_backdrop_subtitle),
                    options = mapOf(
                        AppSettings.BACKDROP_BLEND to stringResource(R.string.settings_player_backdrop_blend),
                        AppSettings.BACKDROP_MESH to stringResource(R.string.settings_player_backdrop_mesh),
                    ),
                    selectedKey = s.playerBackdropStyle,
                    onSelectionChanged = { key -> update { it.copy(playerBackdropStyle = key) } },
                    leadingIcon = { Icon(MeloIcons.Backdrop, null, tint = MaterialTheme.colorScheme.secondary) },
                )
            }
            ThemeSelectorItem(
                label = stringResource(R.string.settings_player_progress_title),
                description = stringResource(R.string.settings_player_progress_subtitle),
                options = mapOf(
                    AppSettings.PROGRESS_PLAIN to stringResource(R.string.settings_player_progress_plain),
                    AppSettings.PROGRESS_GLOW to stringResource(R.string.settings_player_progress_glow),
                    AppSettings.PROGRESS_WAVE to stringResource(R.string.settings_player_progress_wave),
                ),
                selectedKey = s.playerProgressStyle,
                onSelectionChanged = { key -> update { it.copy(playerProgressStyle = key) } },
                leadingIcon = { Icon(MeloIcons.PlayCircle, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            ThemeSelectorItem(
                label = stringResource(R.string.settings_carousel_style_title),
                description = stringResource(R.string.settings_carousel_style_subtitle),
                options = mapOf(
                    CarouselStyle.NO_PEEK to stringResource(R.string.settings_carousel_none),
                    CarouselStyle.ONE_PEEK to stringResource(R.string.settings_carousel_one),
                    CarouselStyle.TWO_PEEK to stringResource(R.string.settings_carousel_two),
                ),
                selectedKey = s.carouselStyle,
                onSelectionChanged = { key -> update { it.copy(carouselStyle = key) } },
                leadingIcon = { Icon(meloPainter(R.drawable.rounded_view_carousel_24), null, tint = MaterialTheme.colorScheme.secondary) },
            )
            SwitchSettingItem(stringResource(R.string.settings_show_player_file_info_title), stringResource(R.string.settings_show_player_file_info_subtitle), s.showPlayerFileInfo, { v -> update { it.copy(showPlayerFileInfo = v) } }, leadingIcon = { Icon(meloPainter(R.drawable.rounded_attach_file_24), null, tint = MaterialTheme.colorScheme.secondary) })
            SwitchSettingItem(stringResource(R.string.settings_player_menu_title), stringResource(R.string.settings_player_menu_subtitle), s.showPlayerMenuButton, { v -> update { it.copy(showPlayerMenuButton = v) } }, leadingIcon = { Icon(MeloIcons.MoreVert, null, tint = MaterialTheme.colorScheme.secondary) })
        }
        SettingsSection(title = stringResource(R.string.settings_lists_section)) {
            SwitchSettingItem(stringResource(R.string.settings_compact_lists_title), stringResource(R.string.settings_compact_lists_subtitle), s.compactLists, { v -> update { it.copy(compactLists = v) } }, leadingIcon = { Icon(meloPainter(R.drawable.rounded_view_week_24), null, tint = MaterialTheme.colorScheme.secondary) })
            SwitchSettingItem(stringResource(R.string.settings_source_badges_title), stringResource(R.string.settings_source_badges_subtitle), s.showSourceBadges, { v -> update { it.copy(showSourceBadges = v) } }, leadingIcon = { Icon(MeloIcons.Tag, null, tint = MaterialTheme.colorScheme.secondary) })
            SwitchSettingItem(stringResource(R.string.settings_quality_lists_title), stringResource(R.string.settings_quality_lists_subtitle), s.showQualityInLists, { v -> update { it.copy(showQualityInLists = v) } }, leadingIcon = { Icon(meloPainter(R.drawable.outline_high_quality_24), null, tint = MaterialTheme.colorScheme.secondary) })
            SwitchSettingItem(stringResource(R.string.settings_show_scrollbar_title), stringResource(R.string.settings_show_scrollbar_subtitle), s.showScrollbar, { v -> update { it.copy(showScrollbar = v) } }, leadingIcon = { Icon(MeloIcons.UnfoldMore, null, tint = MaterialTheme.colorScheme.secondary) })
        }
        SettingsSection(title = stringResource(R.string.settings_navigation_bar_section)) {
            ThemeSelectorItem(
                label = stringResource(R.string.settings_navbar_style_title),
                description = stringResource(R.string.settings_navbar_style_subtitle),
                options = mapOf(
                    NavBarStyle.DEFAULT to stringResource(R.string.settings_navbar_floating),
                    NavBarStyle.FULL_WIDTH to stringResource(R.string.settings_navbar_full),
                ),
                selectedKey = s.navBarStyle,
                onSelectionChanged = { key -> update { it.copy(navBarStyle = key) } },
                leadingIcon = { Icon(MeloIcons.Columns, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            SwitchSettingItem(stringResource(R.string.settings_compact_mode_title), stringResource(R.string.settings_compact_mode_subtitle), s.navBarCompact, { v -> update { it.copy(navBarCompact = v) } }, leadingIcon = { Icon(meloPainter(R.drawable.rounded_view_week_24), null, tint = MaterialTheme.colorScheme.secondary) })
        }
        SettingsSection(title = stringResource(R.string.settings_lyrics_screen_section)) {
            SwitchSettingItem(stringResource(R.string.settings_immersive_lyrics_title), stringResource(R.string.settings_immersive_lyrics_subtitle), s.immersiveLyrics, { v -> update { it.copy(immersiveLyrics = v) } }, leadingIcon = { Icon(meloPainter(R.drawable.rounded_lyrics_24), null, tint = MaterialTheme.colorScheme.secondary) })
        }
    }
}

/** What Melo plays and how: sound, the queue, background playback and lyrics sources. */
@Composable
private fun PlaybackSettings(model: MeloModel, navController: NavHostController) {
    val s by model.ui.settings.collectAsStateWithLifecycle()
    val update = model.ui::updateSettings
    SettingsScaffold(stringResource(R.string.settings_category_playback_title), navController) {
        SettingsSection(
            title = stringResource(R.string.settings_section_quality),
            footer = stringResource(R.string.settings_quality_footer),
        ) {
            val rungs = `in`.melo.data.AudioQuality.entries.associate { rung ->
                rung.key to stringResource(
                    when (rung) {
                        `in`.melo.data.AudioQuality.LOW -> R.string.audio_quality_low
                        `in`.melo.data.AudioQuality.NORMAL -> R.string.audio_quality_normal
                        `in`.melo.data.AudioQuality.HIGH -> R.string.audio_quality_high
                        `in`.melo.data.AudioQuality.LOSSLESS -> R.string.audio_quality_lossless
                    }
                )
            }
            ThemeSelectorItem(
                label = stringResource(R.string.settings_quality_wifi_title),
                description = stringResource(R.string.settings_quality_description),
                options = rungs,
                selectedKey = `in`.melo.data.AudioQuality.of(s.streamingQualityWifi, `in`.melo.data.AudioQuality.WifiDefault).key,
                onSelectionChanged = { key -> update { it.copy(streamingQualityWifi = key) } },
                leadingIcon = { Icon(MeloIcons.Wifi, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            ThemeSelectorItem(
                label = stringResource(R.string.settings_quality_mobile_title),
                description = stringResource(R.string.settings_quality_description),
                options = rungs,
                selectedKey = `in`.melo.data.AudioQuality.of(s.streamingQualityMobile, `in`.melo.data.AudioQuality.MobileDefault).key,
                onSelectionChanged = { key -> update { it.copy(streamingQualityMobile = key) } },
                leadingIcon = { Icon(MeloIcons.SignalCellularAlt, null, tint = MaterialTheme.colorScheme.secondary) },
            )
        }
        SettingsSection(title = stringResource(R.string.settings_section_audio)) {
            SettingsItem(
                title = stringResource(R.string.settings_category_equalizer_title),
                subtitle = stringResource(R.string.settings_category_equalizer_subtitle),
                leadingIcon = { Icon(MeloIcons.GraphicEq, null, tint = MaterialTheme.colorScheme.secondary) },
                onClick = { navController.navigateSafely(Screen.Equalizer.route) },
                trailingIcon = { SettingsChevron() },
            )
            SwitchSettingItem(stringResource(R.string.settings_normalization_title), stringResource(R.string.settings_normalization_subtitle), s.audioNormalization, { v -> update { it.copy(audioNormalization = v) } }, leadingIcon = { Icon(meloPainter(R.drawable.rounded_volume_down_24), null, tint = MaterialTheme.colorScheme.secondary) })
        }
        SettingsSection(title = stringResource(R.string.settings_section_queue)) {
            SwitchSettingItem(stringResource(R.string.settings_persistent_shuffle_title), stringResource(R.string.settings_persistent_shuffle_subtitle), s.persistentShuffle, { v -> update { it.copy(persistentShuffle = v) } }, leadingIcon = { Icon(meloPainter(R.drawable.rounded_shuffle_24), null, tint = MaterialTheme.colorScheme.secondary) })
            SwitchSettingItem(stringResource(R.string.settings_show_queue_history_title), stringResource(R.string.settings_show_queue_history_subtitle), s.showQueueHistory, { v -> update { it.copy(showQueueHistory = v) } }, leadingIcon = { Icon(meloPainter(R.drawable.rounded_queue_music_24), null, tint = MaterialTheme.colorScheme.secondary) })
        }
        SettingsSection(title = stringResource(R.string.settings_section_background)) {
            SwitchSettingItem(stringResource(R.string.settings_keep_playing_title), stringResource(R.string.settings_keep_playing_subtitle), s.keepPlayingInBackground, { v -> update { it.copy(keepPlayingInBackground = v) } }, leadingIcon = { Icon(MeloIcons.MusicNote, null, tint = MaterialTheme.colorScheme.secondary) })
            SwitchSettingItem(stringResource(R.string.settings_pause_on_volume_zero), stringResource(R.string.settings_pause_on_volume_zero_desc), s.pauseOnVolumeZero, { v -> update { it.copy(pauseOnVolumeZero = v) } }, leadingIcon = { Icon(meloPainter(R.drawable.rounded_volume_down_24), null, tint = MaterialTheme.colorScheme.secondary) })
            SwitchSettingItem(stringResource(R.string.settings_headphones_resume_title), stringResource(R.string.settings_headphones_resume_subtitle), s.resumeOnHeadphones, { v -> update { it.copy(resumeOnHeadphones = v) } }, leadingIcon = { Icon(meloPainter(R.drawable.rounded_headphones_24), null, tint = MaterialTheme.colorScheme.secondary) })
        }
        SettingsSection(title = stringResource(R.string.settings_lyrics_sources_section)) {
            val enabled = s.lyricsProviderList()
            `in`.melo.data.repository.LyricsProvider.entries.forEach { provider ->
                val detail = stringResource(provider.detail) + if (provider.wordTimed) " · " + stringResource(R.string.lyrics_provider_word_timed) else ""
                SwitchSettingItem(provider.label, detail, provider in enabled, { v -> update { it.withLyricsProvider(provider, v) } }, leadingIcon = { Icon(meloPainter(R.drawable.rounded_lyrics_24), null, tint = MaterialTheme.colorScheme.secondary) })
            }
        }
    }
}

/** General: language and the start tab, then how touches behave across the app. */
@Composable
private fun BehaviorSettings(model: MeloModel, navController: NavHostController) {
    val s by model.ui.settings.collectAsStateWithLifecycle()
    val update = model.ui::updateSettings
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_category_behavior_title), navController) {
        SettingsSection(title = null) {
            ThemeSelectorItem(
                label = stringResource(R.string.settings_app_language_title),
                description = stringResource(R.string.settings_app_language_subtitle),
                options = mapOf(
                    AppSettings.LANGUAGE_SYSTEM to stringResource(R.string.settings_language_system),
                    "zh-CN" to "简体中文", "fr" to "Français", "en" to "English",
                ),
                selectedKey = s.language,
                onSelectionChanged = { key -> update { it.copy(language = key) }; AppLocales.apply(context, key) },
                leadingIcon = { Icon(MeloIcons.Language, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            ThemeSelectorItem(
                label = stringResource(R.string.settings_default_tab_title),
                description = stringResource(R.string.settings_default_tab_subtitle),
                options = mapOf(
                    AppSettings.TAB_HOME to stringResource(R.string.nav_bar_home),
                    AppSettings.TAB_EXPLORE to stringResource(R.string.nav_bar_explore),
                    AppSettings.TAB_SEARCH to stringResource(R.string.nav_bar_search),
                    AppSettings.TAB_LIBRARY to stringResource(R.string.nav_bar_library),
                    AppSettings.TAB_PODCASTS to stringResource(R.string.nav_bar_podcasts),
                ),
                selectedKey = s.defaultTab,
                onSelectionChanged = { key -> update { it.copy(defaultTab = key) } },
                leadingIcon = { Icon(meloPainter(R.drawable.tab_24), null, tint = MaterialTheme.colorScheme.secondary) },
            )
            SwitchSettingItem(stringResource(R.string.settings_haptic_feedback_title), stringResource(R.string.settings_haptic_feedback_subtitle), s.hapticsEnabled, { v -> update { it.copy(hapticsEnabled = v) } }, leadingIcon = { Icon(MeloIcons.Vibration, null, tint = MaterialTheme.colorScheme.secondary) })
        }
        SettingsSection(title = stringResource(R.string.settings_section_track_swipes)) {
            val swipeOptions = mapOf(
                TrackSwipeAction.NONE.key to stringResource(R.string.swipe_action_none),
                TrackSwipeAction.ADD_TO_QUEUE.key to stringResource(R.string.swipe_action_queue),
                TrackSwipeAction.PLAY_NEXT.key to stringResource(R.string.swipe_action_next),
                TrackSwipeAction.LIKE.key to stringResource(R.string.swipe_action_like),
                TrackSwipeAction.REMOVE.key to stringResource(R.string.swipe_action_remove),
            )
            ThemeSelectorItem(
                label = stringResource(R.string.settings_swipe_right_title), description = stringResource(R.string.settings_swipe_right_subtitle),
                options = swipeOptions, selectedKey = TrackSwipeAction.of(s.swipeRight).key,
                onSelectionChanged = { key -> update { it.copy(swipeRight = key) } },
                leadingIcon = { Icon(MeloIcons.ArrowForward, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            ThemeSelectorItem(
                label = stringResource(R.string.settings_swipe_left_title), description = stringResource(R.string.settings_swipe_left_subtitle),
                options = swipeOptions, selectedKey = TrackSwipeAction.of(s.swipeLeft).key,
                onSelectionChanged = { key -> update { it.copy(swipeLeft = key) } },
                leadingIcon = { Icon(MeloIcons.ArrowBackward, null, tint = MaterialTheme.colorScheme.secondary) },
            )
        }
        SettingsSection(title = stringResource(R.string.settings_section_player)) {
            ThemeSelectorItem(
                label = stringResource(R.string.settings_mini_swipe_title), description = stringResource(R.string.settings_mini_swipe_subtitle),
                options = mapOf(
                    AppSettings.MINI_SWIPE_TRACKS to stringResource(R.string.settings_mini_swipe_tracks),
                    AppSettings.MINI_SWIPE_DISMISS to stringResource(R.string.settings_mini_swipe_dismiss),
                    AppSettings.MINI_SWIPE_OFF to stringResource(R.string.settings_mini_swipe_off),
                ),
                selectedKey = s.miniPlayerSwipe,
                onSelectionChanged = { key -> update { it.copy(miniPlayerSwipe = key) } },
                leadingIcon = { Icon(MeloIcons.SwipeLeft, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            SwitchSettingItem(stringResource(R.string.settings_tap_bg_closes_title), stringResource(R.string.settings_tap_bg_closes_subtitle), s.tapBackgroundClosesPlayer, { v -> update { it.copy(tapBackgroundClosesPlayer = v) } }, leadingIcon = { Icon(meloPainter(R.drawable.rounded_touch_app_24), null, tint = MaterialTheme.colorScheme.secondary) })
        }
        SettingsSection(title = stringResource(R.string.settings_section_search)) {
            SwitchSettingItem(stringResource(R.string.settings_search_sources_title), stringResource(R.string.settings_search_sources_subtitle), s.showSearchSources, { v -> update { it.copy(showSearchSources = v) } }, leadingIcon = { Icon(MeloIcons.Search, null, tint = MaterialTheme.colorScheme.secondary) })
        }
    }
}

private fun sizeUnit(bytes: String, octets: String) = if (java.util.Locale.getDefault().language == "fr") octets else bytes

internal fun formatSize(bytes: Long): String = when {
    // French writes octets (Ko, Mo, Go); every other language gets bytes (KB, MB, GB).
    bytes >= 1L shl 30 -> String.format(java.util.Locale.getDefault(), "%.1f %s", bytes / (1L shl 30).toDouble(), sizeUnit("GB", "Go"))
    bytes >= 1L shl 20 -> String.format(java.util.Locale.getDefault(), "%.0f %s", bytes / (1L shl 20).toDouble(), sizeUnit("MB", "Mo"))
    else -> "${bytes / 1024} ${sizeUnit("KB", "Ko")}"
}

@Composable
private fun StorageSettings(model: MeloModel, navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imageBytes by remember { mutableLongStateOf(0L) }
    var downloadBytes by remember { mutableLongStateOf(0L) }
    var revision by remember { mutableStateOf(0) }
    val downloads by model.ui.downloads.collectAsStateWithLifecycle()
    val settings by model.ui.settings.collectAsStateWithLifecycle()
    var audioBytes by remember { mutableLongStateOf(0L) }
    var catalogBytes by remember { mutableLongStateOf(0L) }
    val storedDownloadBytes by model.ui.downloadsStoredBytes.collectAsStateWithLifecycle()
    LaunchedEffect(revision, downloads.size, storedDownloadBytes) {
        audioBytes = withContext(Dispatchers.IO) { model.ui.audioCacheBytes() }
        catalogBytes = withContext(Dispatchers.IO) { model.ui.catalogBytes() }
        imageBytes = withContext(Dispatchers.IO) { context.imageLoader.diskCache?.size ?: 0L }
        downloadBytes = withContext(Dispatchers.IO) { model.ui.downloadsSizeBytes() }
    }
    var confirmClearDownloads by remember { mutableStateOf(false) }
    SettingsScaffold(stringResource(R.string.settings_storage_title), navController) {
        SettingsSection(title = stringResource(R.string.settings_section_downloads)) {
            ActionSettingsItem(
                title = stringResource(R.string.library_tab_downloads),
                subtitle = stringResource(R.string.downloads_storage, downloads.size, formatSize(downloadBytes)),
                icon = { Icon(MeloIcons.Download, null, tint = MaterialTheme.colorScheme.secondary) },
                primaryActionLabel = stringResource(R.string.common_clear),
                onPrimaryAction = { confirmClearDownloads = true },
                enabled = downloads.isNotEmpty(),
            )
        }
        SettingsSection(title = stringResource(R.string.settings_section_cache)) {
            SettingsItem(
                title = stringResource(R.string.audio_cache_title),
                subtitle = stringResource(R.string.audio_cache_usage, formatSize(audioBytes), formatSize(settings.audioCacheLimitMb * 1024L * 1024L)),
                leadingIcon = { Icon(MeloIcons.LibraryMusic, null, tint = MaterialTheme.colorScheme.secondary) },
                onClick = { navController.navigateSafely(Screen.SettingsCategory.createRoute("audio_cache")) },
                trailingIcon = { SettingsChevron() },
            )
            ActionSettingsItem(
                title = stringResource(R.string.catalog_cache_title),
                subtitle = stringResource(R.string.catalog_cache_subtitle, formatSize(catalogBytes)),
                icon = { Icon(MeloIcons.CloudOff, null, tint = MaterialTheme.colorScheme.secondary) },
                primaryActionLabel = stringResource(R.string.common_clear),
                onPrimaryAction = {
                    scope.launch {
                        withContext(Dispatchers.IO) { model.ui.clearCatalog() }
                        revision++
                    }
                },
            )
            ActionSettingsItem(
                title = stringResource(R.string.settings_cache_images_title),
                subtitle = formatSize(imageBytes),
                icon = { Icon(MeloIcons.Image, null, tint = MaterialTheme.colorScheme.secondary) },
                primaryActionLabel = stringResource(R.string.common_clear),
                onPrimaryAction = {
                    scope.launch {
                        withContext(Dispatchers.IO) { context.imageLoader.diskCache?.clear() }
                        context.imageLoader.memoryCache?.clear()
                        revision++
                    }
                },
            )
            ActionSettingsItem(
                title = stringResource(R.string.settings_cache_lyrics_title),
                subtitle = stringResource(R.string.settings_cache_lyrics_subtitle),
                icon = { Icon(meloPainter(R.drawable.rounded_lyrics_24), null, tint = MaterialTheme.colorScheme.secondary) },
                primaryActionLabel = stringResource(R.string.common_clear),
                onPrimaryAction = { model.ui.clearSavedLyrics() },
            )
        }
    }
    if (confirmClearDownloads) SettingsConfirmDialog(
        title = stringResource(R.string.settings_downloads_clear_title),
        text = stringResource(R.string.settings_downloads_clear_text, downloads.size, formatSize(downloadBytes)),
        confirm = stringResource(R.string.settings_downloads_clear_action),
        onConfirm = { model.ui.removeAllDownloads() },
        onDismiss = { confirmClearDownloads = false },
    )
}

@Composable
private fun DiagnosticsSettings(model: MeloModel, navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableLongStateOf(0L) }
    LaunchedEffect(revision) { report = null; report = model.diagnosticReport() }
    val copied = stringResource(R.string.diagnostics_copied)
    val chooser = stringResource(R.string.diagnostics_share_action)
    SettingsScaffold(stringResource(R.string.diagnostics_title), navController) {
        Text(stringResource(R.string.diagnostics_description), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = MeloLayout.PageGutter))
        `in`.melo.presentation.components.MeloGroupedList {
        ActionSettingsItem(
            title = stringResource(R.string.diagnostics_send_title),
            subtitle = stringResource(R.string.diagnostics_send_subtitle),
            icon = { Icon(MeloIcons.BugReport, null, tint = MaterialTheme.colorScheme.secondary) },
            primaryActionLabel = stringResource(R.string.diagnostics_share_action),
            onPrimaryAction = {
                val text = report ?: return@ActionSettingsItem
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "Melo diagnostic report")
                    .putExtra(android.content.Intent.EXTRA_TEXT, text)
                context.startActivity(android.content.Intent.createChooser(send, chooser))
            },
            secondaryActionLabel = stringResource(R.string.diagnostics_copy_action),
            onSecondaryAction = {
                val text = report ?: return@ActionSettingsItem
                context.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("Melo diagnostic report", text))
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            },
            enabled = report != null,
        )
        ActionSettingsItem(
            title = stringResource(R.string.diagnostics_clear_title),
            subtitle = stringResource(R.string.diagnostics_clear_subtitle),
            icon = { Icon(MeloIcons.Restore, null, tint = MaterialTheme.colorScheme.secondary) },
            primaryActionLabel = stringResource(R.string.diagnostics_clear_action),
            onPrimaryAction = { scope.launch { model.clearDiagnostics(); revision++ } },
        )
        }
        MeloCard(color = `in`.melo.presentation.components.pageCardColor(MaterialTheme.colorScheme.surfaceContainer), shape = MeloLayout.CardShape, modifier = Modifier.fillMaxWidth()) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(report ?: stringResource(R.string.diagnostics_loading), modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun BackupSettings(model: MeloModel, navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exported = stringResource(R.string.backup_exported)
    val restored = stringResource(R.string.backup_restored)
    val failed = stringResource(R.string.backup_failed)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                val content = model.ui.exportBackup()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } }
            }.isSuccess
            Toast.makeText(context, if (ok) exported else failed, Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                val content = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
                model.ui.restoreBackup(checkNotNull(content))
            }.isSuccess
            Toast.makeText(context, if (ok) restored else failed, Toast.LENGTH_SHORT).show()
        }
    }
    SettingsScaffold(stringResource(R.string.settings_category_backup_title), navController) {
        `in`.melo.presentation.components.MeloGroupedList {
        ActionSettingsItem(
            title = stringResource(R.string.settings_export_backup_title),
            subtitle = stringResource(R.string.backup_export_subtitle),
            icon = { Icon(meloPainter(R.drawable.outline_save_24), null, tint = MaterialTheme.colorScheme.secondary) },
            primaryActionLabel = stringResource(R.string.backup_export_action),
            onPrimaryAction = { exportLauncher.launch("melo-backup.json") },
        )
        ActionSettingsItem(
            title = stringResource(R.string.settings_import_backup_title),
            subtitle = stringResource(R.string.backup_import_subtitle),
            icon = { Icon(MeloIcons.Restore, null, tint = MaterialTheme.colorScheme.secondary) },
            primaryActionLabel = stringResource(R.string.backup_import_action),
            onPrimaryAction = { importLauncher.launch(arrayOf("application/json")) },
        )
        }
    }
}

/**
 * Both accounts first, each with its picture, then how links open, then the ways out at the end,
 * in red and each confirmed, as a phone's account page does.
 */
@Composable
private fun AccountSettings(model: MeloModel, navController: NavHostController) {
    val context = LocalContext.current
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val google by model.youTubeAccount.collectAsStateWithLifecycle()
    val spotifyProfile by model.spotifyAccountProfile.collectAsStateWithLifecycle()
    var confirmSpotify by remember { mutableStateOf(false) }
    var confirmGoogle by remember { mutableStateOf(false) }
    SettingsScaffold(stringResource(R.string.settings_accounts_title), navController) {
        `in`.melo.presentation.components.MeloGroupedList {
            if (discovery.spotifyEnabled) SettingsAccountRow(
                title = spotifyProfile?.displayName ?: stringResource(R.string.search_source_spotify),
                subtitle = stringResource(R.string.search_source_spotify),
                picture = { AccountPicture(spotifyProfile?.imageUrl) },
                onClick = null,
                trailing = { Icon(meloPainter(R.drawable.ic_source_spotify), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            ) else SettingsItem(
                title = stringResource(R.string.settings_account_signin_title),
                subtitle = stringResource(R.string.settings_account_signin_subtitle),
                leadingIcon = { Icon(meloPainter(R.drawable.ic_source_spotify), null, tint = MaterialTheme.colorScheme.secondary) },
                onClick = { model.showSignIn() },
                trailingIcon = { SettingsChevron() },
            )
            val account = google
            if (account != null) SettingsAccountRow(
                title = account.name,
                subtitle = account.handle.ifBlank { stringResource(R.string.search_source_youtube) },
                picture = { AccountPicture(account.photoUrl) },
                onClick = { model.refreshYouTubeLibrary() },
                trailing = { Icon(meloPainter(R.drawable.ic_source_youtube_music), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            ) else SettingsItem(
                title = stringResource(R.string.google_connect_title),
                subtitle = stringResource(R.string.google_connect_subtitle),
                leadingIcon = { Icon(meloPainter(R.drawable.ic_source_youtube_music), null, tint = MaterialTheme.colorScheme.secondary) },
                onClick = { navController.navigateSafely(Screen.GoogleSignIn.route) },
                trailingIcon = { SettingsChevron() },
            )
        }
        SettingsSection(title = stringResource(R.string.settings_section_links), footer = stringResource(R.string.settings_spotify_links_subtitle)) {
            SettingsItem(
                title = stringResource(R.string.settings_spotify_links_title),
                subtitle = "",
                leadingIcon = { Icon(MeloIcons.Link, null, tint = MaterialTheme.colorScheme.secondary) },
                onClick = {
                    val packageUri = Uri.fromParts("package", context.packageName, null)
                    val preferredLinks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, packageUri)
                    } else {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                    }
                    try {
                        context.startActivity(preferredLinks)
                    } catch (_: Exception) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
                    }
                },
                trailingIcon = { SettingsChevron() },
            )
        }
        if (discovery.spotifyEnabled || google != null) `in`.melo.presentation.components.MeloGroupedList {
            if (discovery.spotifyEnabled) SettingsDestructiveRow(stringResource(R.string.settings_logout_spotify)) { confirmSpotify = true }
            if (google != null) SettingsDestructiveRow(stringResource(R.string.google_disconnect_title)) { confirmGoogle = true }
        }
    }
    if (confirmSpotify) SettingsConfirmDialog(
        title = stringResource(R.string.settings_logout_confirm),
        text = stringResource(R.string.settings_logout_subtitle),
        confirm = stringResource(R.string.settings_logout_title),
        onConfirm = { model.disconnectSpotify() },
        onDismiss = { confirmSpotify = false },
    )
    if (confirmGoogle) SettingsConfirmDialog(
        title = stringResource(R.string.google_disconnect_confirm),
        text = stringResource(R.string.google_disconnect_subtitle),
        confirm = stringResource(R.string.google_disconnect_action),
        onConfirm = { model.disconnectGoogle() },
        onDismiss = { confirmGoogle = false },
    )
}

@Composable
private fun shelfName(shelf: `in`.melo.data.preferences.HomeShelf): String = stringResource(when (shelf) {
    `in`.melo.data.preferences.HomeShelf.PINS -> R.string.home_pins
    `in`.melo.data.preferences.HomeShelf.RECENT -> R.string.home_recent
    `in`.melo.data.preferences.HomeShelf.DOWNLOADS -> R.string.home_downloads
    `in`.melo.data.preferences.HomeShelf.LIKED -> R.string.liked_title
    `in`.melo.data.preferences.HomeShelf.PLAYLISTS -> R.string.home_playlists
    `in`.melo.data.preferences.HomeShelf.MULTI_SERVICE -> R.string.home_multi_service
    `in`.melo.data.preferences.HomeShelf.SPOTIFY_PLAYLISTS -> R.string.home_spotify_playlists
    `in`.melo.data.preferences.HomeShelf.YOUTUBE_PLAYLISTS -> R.string.home_youtube_playlists
    `in`.melo.data.preferences.HomeShelf.ALBUMS -> R.string.home_albums
    `in`.melo.data.preferences.HomeShelf.ARTISTS -> R.string.home_artists
    `in`.melo.data.preferences.HomeShelf.LOCAL_FILES -> R.string.home_local_files
})

@Composable
private fun styleName(style: `in`.melo.data.preferences.ShelfStyle): String = stringResource(when (style) {
    `in`.melo.data.preferences.ShelfStyle.CAROUSEL -> R.string.shelf_style_carousel
    `in`.melo.data.preferences.ShelfStyle.LARGE -> R.string.shelf_style_large
    `in`.melo.data.preferences.ShelfStyle.GRID -> R.string.shelf_style_grid
    `in`.melo.data.preferences.ShelfStyle.LIST -> R.string.shelf_style_list
    `in`.melo.data.preferences.ShelfStyle.PILLS -> R.string.shelf_style_pills
    `in`.melo.data.preferences.ShelfStyle.COLLAGE -> R.string.shelf_style_collage
    `in`.melo.data.preferences.ShelfStyle.CARD -> R.string.shelf_style_card
})

/** Which shelves the home screen shows, in which order, with which layout and how many items. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HomeSettings(model: MeloModel, navController: NavHostController) {
    val s by model.ui.settings.collectAsStateWithLifecycle()
    val shelves = s.homeLayout
    fun save(list: List<`in`.melo.data.preferences.ShelfConfig>) =
        model.ui.updateSettings { it.copy(homeShelves = `in`.melo.data.preferences.HomeLayout.encode(list)) }
    fun change(index: Int, update: (`in`.melo.data.preferences.ShelfConfig) -> `in`.melo.data.preferences.ShelfConfig) =
        save(shelves.mapIndexed { position, config -> if (position == index) update(config) else config })
    fun move(from: Int, to: Int) { if (to in shelves.indices) save(shelves.toMutableList().apply { add(to, removeAt(from)) }) }
    SettingsScaffold(stringResource(R.string.settings_home_title), navController) {
        SettingsSection(title = stringResource(R.string.settings_section_home_style)) {
            SwitchSettingItem(stringResource(R.string.home_gradients_setting), stringResource(R.string.home_gradients_setting_subtitle), s.homeGradients, { v -> model.ui.updateSettings { it.copy(homeGradients = v) } }, leadingIcon = { Icon(MeloIcons.Style, null, tint = MaterialTheme.colorScheme.secondary) })
            ThemeSelectorItem(
                label = stringResource(R.string.settings_collage_pattern_title),
                description = stringResource(R.string.settings_collage_pattern_subtitle),
                options = CollagePattern.entries.associate { it.storageKey to stringResource(it.labelRes) },
                selectedKey = s.collagePattern,
                onSelectionChanged = { key -> model.ui.updateSettings { it.copy(collagePattern = key) } },
                leadingIcon = { Icon(meloPainter(R.drawable.rounded_view_column_24), null, tint = MaterialTheme.colorScheme.secondary) },
            )
            SwitchSettingItem(stringResource(R.string.settings_auto_rotate_patterns_title), stringResource(R.string.settings_auto_rotate_patterns_subtitle), s.collageAutoRotate, { v -> model.ui.updateSettings { it.copy(collageAutoRotate = v) } }, leadingIcon = { Icon(meloPainter(R.drawable.rounded_shuffle_24), null, tint = MaterialTheme.colorScheme.secondary) })
        }
        SettingsSection(title = stringResource(R.string.settings_section_shelves), footer = stringResource(R.string.settings_home_description)) {
        shelves.forEachIndexed { index, config ->
            MeloCard(color = `in`.melo.presentation.components.pageCardColor(MaterialTheme.colorScheme.surfaceContainer), shape = MeloLayout.PanelShape, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = MeloLayout.PageGutter, end = MeloLayout.Space2, top = MeloLayout.Space2, bottom = MeloLayout.Space3), verticalArrangement = Arrangement.spacedBy(MeloLayout.Space2)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(shelfName(config.shelf), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        MeloIconButton(MeloIcons.KeyboardArrowUp, stringResource(R.string.shelf_move_up),
                            onClick = { move(index, index - 1) }, enabled = index > 0)
                        MeloIconButton(MeloIcons.KeyboardArrowDown, stringResource(R.string.shelf_move_down),
                            onClick = { move(index, index + 1) }, enabled = index < shelves.lastIndex)
                        MeloSwitch(config.visible, { visible -> change(index) { it.copy(visible = visible) } })
                    }
                    if (config.visible) {
                        if (config.shelf.styles.size > 1) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(MeloLayout.Space2)) {
                            config.shelf.styles.forEach { style ->
                                `in`.melo.presentation.components.MeloChip(styleName(style), config.style == style, { change(index) { it.copy(style = style) } })
                            }
                        }
                        if (config.shelf.counts) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(MeloLayout.Space2),
                            itemVerticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.shelf_items), Modifier.padding(end = 4.dp), style = MaterialTheme.typography.labelLarge)
                            `in`.melo.data.preferences.HomeLayout.COUNTS.forEach { count ->
                                `in`.melo.presentation.components.MeloChip(count.toString(), config.count == count, { change(index) { it.copy(count = count) } })
                            }
                        }
                        if (config.style != `in`.melo.data.preferences.ShelfStyle.CARD && config.style != `in`.melo.data.preferences.ShelfStyle.COLLAGE) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.shelf_show_title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            MeloSwitch(config.showTitle, { shown -> change(index) { it.copy(showTitle = shown) } })
                        }
                    }
                }
            }
        }
        }
        MeloButton(
            text = stringResource(R.string.shelf_reset),
            onClick = { model.ui.updateSettings { it.copy(homeShelves = "") } },
            modifier = Modifier.fillMaxWidth(),
            size = MeloButtonSize.Medium,
            style = MeloButtonStyle.Text,
        )
    }
}

/** Which buttons album and playlist pages show in their bar, in which order; the others go to the ⋮ menu. */
@Composable
private fun PageActionsSettings(model: MeloModel, navController: NavHostController) {
    val s by model.ui.settings.collectAsStateWithLifecycle()
    SettingsScaffold(stringResource(R.string.settings_pages_title), navController) {
        SwitchSettingItem(
            title = stringResource(R.string.settings_pages_tint_title),
            subtitle = stringResource(R.string.settings_pages_tint_subtitle),
            checked = s.detailPageTint,
            onCheckedChange = { value -> model.ui.updateSettings { it.copy(detailPageTint = value) } },
            leadingIcon = { Icon(MeloIcons.Palette, null, tint = MaterialTheme.colorScheme.secondary) },
        )
        SwitchSettingItem(
            title = stringResource(R.string.settings_full_screen_detail_cover_title),
            subtitle = stringResource(R.string.settings_full_screen_detail_cover_subtitle),
            checked = s.fullScreenDetailCover,
            onCheckedChange = { value -> model.ui.updateSettings { it.copy(fullScreenDetailCover = value) } },
            leadingIcon = { Icon(meloPainter(R.drawable.rounded_fullscreen_24), null, tint = MaterialTheme.colorScheme.secondary) },
        )
        Text(stringResource(R.string.settings_pages_description), Modifier.padding(horizontal = MeloLayout.PageGutter),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(`in`.melo.data.preferences.DetailPage.ALBUM to R.string.settings_pages_album,
            `in`.melo.data.preferences.DetailPage.PLAYLIST to R.string.settings_pages_playlist).forEach { (page, title) ->
            val slots = s.actions(page)
            fun save(list: List<`in`.melo.data.preferences.ActionSlot>) = model.ui.updateSettings {
                val encoded = `in`.melo.data.preferences.DetailActions.encode(list)
                if (page == `in`.melo.data.preferences.DetailPage.ALBUM) it.copy(albumActions = encoded) else it.copy(playlistActions = encoded)
            }
            fun move(from: Int, to: Int) { if (to in slots.indices) save(slots.toMutableList().apply { add(to, removeAt(from)) }) }
            SettingsSection(title = stringResource(title), icon = {}) {
            slots.forEachIndexed { index, slot ->
                MeloCard(color = `in`.melo.presentation.components.pageCardColor(MaterialTheme.colorScheme.surfaceContainer), shape = MeloLayout.PanelShape, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(actionName(slot.action), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(if (slot.shown) R.string.settings_pages_in_bar else R.string.settings_pages_in_menu),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MeloIconButton(MeloIcons.KeyboardArrowUp, stringResource(R.string.shelf_move_up),
                            onClick = { move(index, index - 1) }, enabled = index > 0)
                        MeloIconButton(MeloIcons.KeyboardArrowDown, stringResource(R.string.shelf_move_down),
                            onClick = { move(index, index + 1) }, enabled = index < slots.lastIndex)
                        MeloSwitch(slot.shown, { shown -> save(slots.mapIndexed { i, it -> if (i == index) it.copy(shown = shown) else it }) })
                    }
                }
            }
            }
        }
        MeloButton(
            text = stringResource(R.string.settings_pages_reset),
            onClick = { model.ui.updateSettings { it.copy(albumActions = "", playlistActions = "") } },
            modifier = Modifier.fillMaxWidth(),
            size = MeloButtonSize.Medium,
            style = MeloButtonStyle.Text,
        )
    }
}

/** The Glass style's options. Each slider is saved when the finger lifts, so a drag is one write. */
@Composable
private fun GlassOptionSliders(s: AppSettings, change: (AppSettings.() -> AppSettings) -> Unit) {
    var transparency by remember(s.glassTransparency) { mutableFloatStateOf(s.glassTransparency) }
    var blur by remember(s.glassBlur) { mutableFloatStateOf(s.glassBlur) }
    var saturation by remember(s.glassSaturation) { mutableFloatStateOf(s.glassSaturation) }
    SliderSettingsItem(
        label = stringResource(R.string.settings_glass_transparency),
        value = transparency, valueRange = 0f..1f, steps = 0,
        onValueChange = { transparency = it },
        onValueChangeFinished = { change { copy(glassTransparency = transparency) } },
        valueText = { "${(it * 100).roundToInt()}%" },
    )
    SliderSettingsItem(
        label = stringResource(R.string.settings_glass_blur),
        value = blur, valueRange = 0f..`in`.melo.ui.glass.MeloSurfaceOptions.MAX_BLUR, steps = 0,
        onValueChange = { blur = it },
        onValueChangeFinished = { change { copy(glassBlur = blur) } },
        valueText = { "${it.roundToInt()} dp" },
    )
    SliderSettingsItem(
        label = stringResource(R.string.settings_glass_saturation),
        value = saturation, valueRange = 0f..`in`.melo.ui.glass.MeloSurfaceOptions.MAX_SATURATION, steps = 0,
        onValueChange = { saturation = it },
        onValueChangeFinished = { change { copy(glassSaturation = saturation) } },
        valueText = { "${(it * 100).roundToInt()}%" },
    )
    // The glass tint and tint darkness.
    ThemeSelectorItem(
        label = stringResource(R.string.settings_glass_tint),
        description = stringResource(R.string.settings_glass_tint_subtitle),
        options = mapOf(
            `in`.melo.ui.glass.MeloGlassTint.SONG.key to stringResource(R.string.settings_glass_tint_song),
            `in`.melo.ui.glass.MeloGlassTint.WHITE.key to stringResource(R.string.settings_glass_tint_white),
            `in`.melo.ui.glass.MeloGlassTint.BLACK.key to stringResource(R.string.settings_glass_tint_black),
            `in`.melo.ui.glass.MeloGlassTint.BLUE.key to stringResource(R.string.settings_glass_tint_blue),
            `in`.melo.ui.glass.MeloGlassTint.PURPLE.key to stringResource(R.string.settings_glass_tint_purple),
            `in`.melo.ui.glass.MeloGlassTint.PINK.key to stringResource(R.string.settings_glass_tint_pink),
            `in`.melo.ui.glass.MeloGlassTint.ORANGE.key to stringResource(R.string.settings_glass_tint_orange),
            `in`.melo.ui.glass.MeloGlassTint.GREEN.key to stringResource(R.string.settings_glass_tint_green),
        ),
        selectedKey = s.glassTint,
        onSelectionChanged = { key -> change { copy(glassTint = key) } },
        leadingIcon = { Icon(MeloIcons.Palette, null, tint = MaterialTheme.colorScheme.secondary) },
    )
    var darkness by remember(s.glassTintDarkness) { mutableFloatStateOf(s.glassTintDarkness) }
    SliderSettingsItem(
        label = stringResource(R.string.settings_glass_tint_darkness),
        value = darkness, valueRange = 0f..1f, steps = 0,
        onValueChange = { darkness = it },
        onValueChangeFinished = { change { copy(glassTintDarkness = darkness) } },
        valueText = { "${(it * 100).roundToInt()}%" },
    )
}
