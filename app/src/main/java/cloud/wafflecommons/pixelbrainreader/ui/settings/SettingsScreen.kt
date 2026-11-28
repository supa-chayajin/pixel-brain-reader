@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.BuildConfig
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.data.sync.PendingConflict
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.VaultGraphDialog
import cloud.wafflecommons.pixelbrainreader.ui.sync.PendingConflictViewModel
import cloud.wafflecommons.pixelbrainreader.ui.utils.StaggeredEntry

/**
 * The Settings home (13.4, S1 and S6): a search field over every setting, then the eight topics in
 * the owner's order, all on one page and nothing folded. A row opens its page; the two device
 * switches and the two actions (the link check, Log out) act in place. While the field holds a
 * query, its hits replace the topics. A wide window shows the same page in one centred column
 * (settled decision #17). The home writes nothing: each page that changes the vault says so itself.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpen: (route: String) -> Unit,
    onOpenNote: (String) -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    pendingConflictViewModel: PendingConflictViewModel = hiltViewModel()
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    // A sync stopped on a conflict (12.15, S1): Vault & device lists it while it waits.
    val pendingConflict = pendingConflictViewModel.pending.collectAsStateWithLifecycle().value
    LaunchedEffect(Unit) { pendingConflictViewModel.reload() }
    val vaultGraphReport = viewModel.vaultGraphReport.collectAsStateWithLifecycle().value
    val isAnalyzingGraph = viewModel.isAnalyzingGraph.collectAsStateWithLifecycle().value
    var showGraphDialog by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    // The field's text is its own synchronous state, never a flow's echo.
    var query by rememberSaveable { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    val appearance = uiState.appearance
    val conflictPending = pendingConflict != null
    val settingsContext = remember(appearance, conflictPending) {
        SettingsContext(
            appearance = appearance,
            readOnly = VaultWriteGate.readOnly,
            psn = BuildConfig.PSN_CLIENT_ID.isNotBlank(),
            xbox = BuildConfig.XBOX_CLIENT_ID.isNotBlank(),
            conflictPending = conflictPending
        )
    }

    // Logging out forgets the token only; the next login picks the vault and the device's role.
    if (confirmLogout) {
        LogOutConfirmDialog(
            onConfirm = {
                confirmLogout = false
                onLogout()
            },
            onDismiss = { confirmLogout = false }
        )
    }
    if (showGraphDialog) {
        VaultGraphDialog(
            report = vaultGraphReport,
            isLoading = isAnalyzingGraph,
            onRefresh = viewModel::analyzeVaultGraph,
            onOpenNote = { path ->
                showGraphDialog = false
                onOpenNote(path)
            },
            onDismiss = { showGraphDialog = false }
        )
    }

    // What a row that isn't a link does: its switch, or its action.
    val onSwitch: (SettingsEntry, Boolean) -> Unit = { entry, on ->
        when (entry.key) {
            SettingsIndex.SOUND_EFFECTS -> viewModel.setSoundEffects(on)
            SettingsIndex.SCREEN_SHARE_SAFE -> viewModel.setScreenShareSafe(on)
            else -> Unit
        }
    }
    val onAction: (SettingsEntry) -> Unit = { entry ->
        when (entry.key) {
            SettingsIndex.VAULT_LINKS -> {
                showGraphDialog = true
                viewModel.analyzeVaultGraph()
            }
            SettingsIndex.LOG_OUT -> {
                confirmLogout = true
            }
            else -> Unit
        }
    }

    // The header stays put as the page scrolls (13.2.2); the snackbar clears the floating bar.
    FormScaffold(title = "Settings", onBack = onBack, snackbarHostState = snackbarHostState, icon = Icons.Rounded.Settings) {
        SettingsSearchField(query = query, onQueryChange = { query = it })

        if (query.isBlank()) {
            val home = remember(settingsContext) { SettingsIndex.home(settingsContext) }
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                home.forEachIndexed { index, (topic, entries) ->
                    StaggeredEntry(index = index) {
                        SettingsTopicSection(title = topic.titleFor(appearance), icon = topic.icon) {
                            entries.forEach { entry ->
                                row {
                                    SettingsEntryRow(
                                        entry = entry,
                                        title = SettingsIndex.text(entry.title, appearance),
                                        description = homeDescription(entry, appearance, pendingConflict),
                                        state = uiState,
                                        onOpen = { entry.route?.let(onOpen) },
                                        onSwitch = onSwitch,
                                        onAction = onAction
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val hits = remember(query, settingsContext) { SettingsSearch.search(query, settingsContext) }
            if (hits.isEmpty()) {
                EmptyHint("No setting matches “${query.trim()}”.")
            } else {
                SettingsGroup {
                    hits.forEach { hit ->
                        row {
                            SettingsEntryRow(
                                entry = hit.entry,
                                title = hit.title,
                                description = "${hit.where} · ${hit.description}",
                                state = uiState,
                                onOpen = {
                                    hit.entry.route?.let { route ->
                                        // The page opens at that setting, once (S6).
                                        hit.entry.anchor?.let { anchor -> SettingsFocus.request(route, anchor) }
                                        onOpen(route)
                                    }
                                },
                                onSwitch = onSwitch,
                                onAction = onAction
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One entry as a row: a link to its page, a device switch, or an action. */
@Composable
private fun SettingsEntryRow(
    entry: SettingsEntry,
    title: String,
    description: String,
    state: SettingsViewModel.SettingsUiState,
    onOpen: () -> Unit,
    onSwitch: (SettingsEntry, Boolean) -> Unit,
    onAction: (SettingsEntry) -> Unit
) {
    when (entry.kind) {
        EntryKind.SWITCH -> {
            val checked = when (entry.key) {
                SettingsIndex.SOUND_EFFECTS -> state.soundEffects
                SettingsIndex.SCREEN_SHARE_SAFE -> state.screenShareSafe
                else -> false
            }
            SettingsSwitchRow(
                title = title,
                checked = checked,
                onCheckedChange = { on -> onSwitch(entry, on) },
                description = description,
                icon = entry.icon
            )
        }
        EntryKind.ACTION -> SettingsRow(title = title, description = description, icon = entry.icon, onClick = { onAction(entry) })
        EntryKind.PAGE -> SettingsRow(title = title, description = description, icon = entry.icon, onClick = onOpen)
    }
}

/**
 * A home row's line: the index's, with the owner's names. The sync conflict says how many files wait,
 * and This device & version shows the version and the device's role.
 */
private fun homeDescription(entry: SettingsEntry, appearance: ModeAppearance, pending: PendingConflict?): String = when (entry.key) {
    SettingsIndex.SYNC_CONFLICT -> pending?.let { conflict ->
        val files = conflict.paths.size
        (if (files == 1) "1 file" else "$files files") + " changed both here and on GitHub: syncing waits for your choice"
    } ?: SettingsIndex.text(entry.description, appearance)
    SettingsIndex.DEVICE -> "Pixel Brain Reader ${BuildConfig.VERSION_NAME} · " +
        (if (VaultWriteGate.readOnly) "read-only device" else "this device writes the vault")
    else -> SettingsIndex.text(entry.description, appearance)
}

/** The search (S6): a name or a few words, the topics' rows give way to every setting that matches. */
@Composable
private fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search settings") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            {
                CortexIconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear the search")
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
    )
}

@Composable
private fun LogOutConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log out?") },
        text = {
            Text(
                "This device forgets its GitHub token. Your notes stay on GitHub and in this " +
                    "device's copy; logging in again needs a token."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm, shapes = ButtonDefaults.shapes()) { Text("Log out") } },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
    )
}
