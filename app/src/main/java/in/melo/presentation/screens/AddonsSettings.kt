package `in`.melo.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import `in`.melo.MeloModel
import `in`.melo.R
import `in`.melo.data.addons.AddonConfig
import `in`.melo.data.addons.AddonException
import `in`.melo.data.addons.AddonProblem
import `in`.melo.data.addons.AddonStore
import `in`.melo.presentation.components.MeloButton
import `in`.melo.presentation.components.MeloButtonSize
import `in`.melo.presentation.components.MeloButtonStyle
import `in`.melo.presentation.components.MeloCard
import `in`.melo.presentation.icons.MeloIcons
import `in`.melo.ui.glass.MeloAlertDialog
import `in`.melo.ui.glass.MeloSwitch
import `in`.melo.ui.theme.MeloLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add-ons: other people's catalogues Melo can search and play from, in the
 * listener's order of preference, and whether queued tracks prefer them.
 */
@Composable
internal fun AddonsSettings(model: MeloModel, navController: NavHostController) {
    val context = LocalContext.current
    val store = remember { AddonStore.get(context) }
    val addons by store.addons.collectAsStateWithLifecycle()
    val settings by model.ui.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf<String?>(null) }
    var pickingQuality by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<AddonConfig?>(null) }
    /** What the last check of each add-on found, until the page is left. */
    val checks = remember { mutableStateMapOf<String, String>() }
    val anyEnabled = addons.any { it.enabled }

    fun check(config: AddonConfig) {
        checks[config.id] = context.getString(R.string.addons_checking)
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.identify(config.url) }
            result.onSuccess { store.refresh(config.id, it) }
            checks[config.id] = result.fold(
                { context.getString(R.string.addons_check_ok) },
                { context.getString(addonProblemText(it)) },
            )
        }
    }

    SettingsScaffold(stringResource(R.string.settings_addons_title), navController) {

        SettingsSection(title = stringResource(R.string.addons_section), footer = stringResource(R.string.addons_footer)) {
            addons.forEach { config ->
                val quality = config.qualityOptions.firstOrNull { it.value == config.pickedQuality }?.label
                    ?: config.qualityOptions.firstOrNull()?.label
                val subtitle = checks[config.id] ?: listOfNotNull(
                    config.version.ifBlank { null }?.let { stringResource(R.string.addons_version, it) },
                    quality,
                    if (config.needsLosslessOutput) stringResource(R.string.addons_needs_lossless_output) else null,
                ).joinToString(" · ")
                MeloCard(modifier = Modifier.fillMaxWidth(), onClick = { managing = config.id }) {
                    SettingsRow(
                        title = config.name,
                        subtitle = subtitle,
                        leadingIcon = { Icon(MeloIcons.Link, null, tint = MaterialTheme.colorScheme.onSurface) },
                    ) {
                        MeloSwitch(checked = config.enabled, onCheckedChange = { on -> store.setEnabled(config.id, on) })
                    }
                }
            }
            SettingsItem(
                title = stringResource(R.string.addons_add),
                subtitle = stringResource(R.string.addons_add_subtitle),
                leadingIcon = { Icon(MeloIcons.Add, null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { adding = true },
            )
        }
    }

    if (adding) AddAddonDialog(store, onDismiss = { adding = false }, onAdded = { config ->
        adding = false
        Toast.makeText(context, context.getString(R.string.addons_added, config.name), Toast.LENGTH_SHORT).show()
    })

    val managed = addons.firstOrNull { it.id == managing }
    if (managed != null) {
        val index = addons.indexOf(managed)
        val actions = linkedMapOf<String, String>().apply {
            if (managed.qualityOptions.isNotEmpty()) put("quality", stringResource(R.string.addons_quality))
            put("check", stringResource(R.string.addons_check))
            if (index > 0) put("up", stringResource(R.string.addons_move_up))
            if (index < addons.lastIndex) put("down", stringResource(R.string.addons_move_down))
            put("remove", stringResource(R.string.addons_remove))
        }
        val currentQuality = managed.qualityOptions.firstOrNull { it.value == managed.pickedQuality }
            ?: managed.qualityOptions.firstOrNull()
        SettingsOptionsSheet(
            title = managed.name,
            options = actions,
            selectedKey = "",
            details = buildMap { currentQuality?.let { put("quality", it.label) } },
            onSelect = { key ->
                managing = null
                when (key) {
                    "quality" -> pickingQuality = managed.id
                    "check" -> check(managed)
                    "up" -> store.move(managed.id, -1)
                    "down" -> store.move(managed.id, 1)
                    "remove" -> removing = managed
                }
            },
            onDismiss = { managing = null },
        )
    }

    val picking = addons.firstOrNull { it.id == pickingQuality }
    if (picking != null) {
        SettingsOptionsSheet(
            title = stringResource(R.string.addons_quality),
            options = picking.qualityOptions.associate { it.value to it.label },
            selectedKey = picking.pickedQuality ?: picking.qualityOptions.firstOrNull()?.value.orEmpty(),
            note = stringResource(R.string.addons_quality_note),
            onSelect = { value -> store.setQuality(picking.id, value); pickingQuality = null },
            onDismiss = { pickingQuality = null },
        )
    }

    removing?.let { config ->
        SettingsConfirmDialog(
            title = stringResource(R.string.addons_remove_title, config.name),
            text = stringResource(R.string.addons_remove_message),
            confirm = stringResource(R.string.addons_remove),
            onConfirm = {
                store.remove(config.id)
                if (store.enabled().isEmpty()) model.ui.updateSettings { it.copy(directSourceStream = false) }
            },
            onDismiss = { removing = null },
        )
    }
}

/** Paste an address; the add-on is asked what it is before it joins the list. */
@Composable
private fun AddAddonDialog(store: AddonStore, onDismiss: () -> Unit, onAdded: (AddonConfig) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val entered = url.trim()
        if (entered.isEmpty() || working) return
        if (store.hasUrl(entered)) { error = context.getString(R.string.addons_duplicate); return }
        working = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.identify(entered) }
            working = false
            result.onSuccess { manifest -> onAdded(store.add(entered, manifest)) }
                .onFailure { error = context.getString(addonProblemText(it)) }
        }
    }

    MeloAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.addons_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MeloLayout.Space3)) {
                Text(stringResource(R.string.addons_add_message), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    placeholder = { Text(stringResource(R.string.addons_add_placeholder)) },
                    singleLine = true,
                    enabled = !working,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    trailingIcon = if (working) ({ CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            MeloButton(
                text = stringResource(R.string.addons_add_confirm), onClick = ::submit,
                enabled = url.isNotBlank() && !working, size = MeloButtonSize.Medium,
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

private fun addonProblemText(failure: Throwable): Int = when ((failure as? AddonException)?.problem) {
    AddonProblem.NotAnAddon -> R.string.addon_problem_not_an_addon
    AddonProblem.NoSearch -> R.string.addon_problem_no_search
    AddonProblem.NoManifest -> R.string.addon_problem_no_manifest
    AddonProblem.BadAddress -> R.string.addon_problem_bad_address
    AddonProblem.EmptyAnswer -> R.string.addon_problem_empty
    AddonProblem.NeedsAttestation -> R.string.addon_problem_attestation
    AddonProblem.Refused -> R.string.addon_problem_refused
    null -> R.string.addon_problem_unreachable
}
