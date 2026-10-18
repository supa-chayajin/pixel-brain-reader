package cloud.wafflecommons.pixelbrainreader.ui.work

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.work.LogEntry
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogSettings
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.components.PickDateDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.PickTimeDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.RemovableRow
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import cloud.wafflecommons.pixelbrainreader.ui.components.TextPromptDialog
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import cloud.wafflecommons.pixelbrainreader.ui.utils.DictationListeningDialog
import cloud.wafflecommons.pixelbrainreader.ui.utils.rememberOnDeviceDictation
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

internal fun LogKind.icon(): ImageVector = when (this) {
    LogKind.FACT -> Icons.Rounded.Info
    LogKind.DECISION -> Icons.Rounded.Gavel
    LogKind.REQUEST -> Icons.Rounded.MoveToInbox
    LogKind.REFUSAL -> Icons.Rounded.Block
    LogKind.OFF_TICKET -> Icons.Rounded.Timer
}

internal fun durationLabel(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h%02d".format(minutes % 60) else "${minutes}m"

/** The mission log (#1): this month's facts, decisions, requests, refusals and off-ticket time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionLogScreen(startWithAdd: Boolean, onBack: () -> Unit, viewModel: MissionLogViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val month by viewModel.month.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }

    val locale = androidx.compose.ui.platform.LocalLocale.current.platformLocale
    var adding by rememberSaveable { mutableStateOf(startWithAdd) }
    var editing by remember { mutableStateOf<LogEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mission log") },
                navigationIcon = {
                    CortexIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Log") },
                modifier = Modifier.padding(bottom = NavBarClearance)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.padding(bottom = NavBarClearance)) }
    ) { padding ->
        if (visibility.hideWork) {
            HiddenWork(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = NavBarClearance + 72.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "month") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = viewModel::previousMonth) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous month")
                        }
                        Text(
                            month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale) + " " + month.year,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = viewModel::nextMonth) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next month")
                        }
                    }
                }
                item(key = "summary") { KindFilters(state, filter, viewModel::setFilter) }
                state.error?.let { error ->
                    item(key = "error") {
                        SectionCard(title = "Couldn't read the log", icon = Icons.Rounded.Lock, tint = MaterialTheme.colorScheme.error, subtitle = error)
                    }
                }
                val shown = state.entries.filter { filter == null || it.kind == filter }
                if (!state.loading && shown.isEmpty() && state.error == null) {
                    item(key = "empty") {
                        Text(
                            "Nothing logged this month. Log facts, decisions, requests, refusals and off-ticket time as they happen: they become your evidence report.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                }
                shown.groupBy { it.at.toLocalDate() }.forEach { (day, entries) ->
                    item(key = "day-$day") {
                        Text(
                            day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(entries, key = { "${it.at}|${it.sourceLine}" }) { entry ->
                        EntryRow(entry, onClick = { editing = entry })
                    }
                }
                item(key = "settings") { LogSettingsCard(settings, viewModel) }
            }
        }
    }

    if (adding || editing != null) {
        LogEntrySheet(
            initial = editing,
            roles = settings.roles,
            onSave = { draft ->
                viewModel.save(editing, draft)
                adding = false
                editing = null
            },
            onDelete = editing?.let { entry -> { viewModel.delete(entry); editing = null } },
            onDismiss = {
                adding = false
                editing = null
            }
        )
    }
}

@Composable
private fun HiddenWork(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Rounded.VisibilityOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(16.dp))
        Text("Hidden outside Work mode", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            "You can still log something: it is saved without showing the rest.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindFilters(state: LogMonthState, filter: LogKind?, onFilter: (LogKind?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogKind.entries.forEach { kind ->
                val count = state.entries.count { it.kind == kind }
                FilterChip(
                    selected = filter == kind,
                    onClick = { onFilter(if (filter == kind) null else kind) },
                    label = { Text("${kind.label} $count") },
                    leadingIcon = { Icon(kind.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }
        }
        if (state.offTicketMinutes > 0) {
            Text(
                "${durationLabel(state.offTicketMinutes)} off-ticket this month",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EntryRow(entry: LogEntry, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(entry.kind.icon(), contentDescription = entry.kind.label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.text, style = MaterialTheme.typography.bodyLarge)
            val meta = listOfNotNull(
                entry.at.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
                entry.kind.label,
                entry.role,
                entry.status,
                entry.minutes?.let(::durationLabel),
                entry.requester?.let { "asked by $it" },
                entry.decider?.let { "decided by $it" },
                entry.reference
            ) + entry.tags.map { "#$it" }
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogSettingsCard(settings: MissionLogSettings, viewModel: MissionLogViewModel) {
    var editingFolder by remember { mutableStateOf(false) }
    var addingRole by remember { mutableStateOf(false) }
    SectionCard(
        title = "Where the log lives",
        subtitle = if (settings.encrypted) {
            "Encrypted in the private vault (${MissionLogSettings.ENCRYPTED_FOLDER}), one file per month. Not searchable, never sent to the AI."
        } else {
            "${settings.folder}, one markdown file per month. Put it in a work folder so Modes hides it."
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Encrypt the log", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = settings.encrypted, onCheckedChange = viewModel::setEncrypted)
        }
        if (!settings.encrypted) {
            TextButton(onClick = { editingFolder = true }) { Text("Change folder") }
        }
        Text("Earlier months stay where they were written.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Roles", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { addingRole = true }) { Text("Add") }
        }
        settings.roles.forEach { role -> RemovableRow(title = role, subtitle = null, onRemove = { viewModel.removeRole(role) }) }
    }
    if (editingFolder) {
        TextPromptDialog(
            title = "Log folder",
            label = "Vault folder",
            initial = settings.folder,
            onConfirm = { folder -> viewModel.setFolder(folder); editingFolder = false },
            onDismiss = { editingFolder = false }
        )
    }
    if (addingRole) {
        TextPromptDialog(
            title = "Role",
            label = "A hat you wear, e.g. Lead",
            confirmLabel = "Add",
            onConfirm = { role -> viewModel.addRole(role); addingRole = false },
            onDismiss = { addingRole = false }
        )
    }
}

/** Capture or edit one entry. Dictation stays on the device: this is work content. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LogEntrySheet(
    initial: LogEntry?,
    roles: List<String>,
    onSave: (LogEntry) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(initial?.kind ?: LogKind.FACT) }
    var text by remember { mutableStateOf(initial?.text.orEmpty()) }
    var role by remember { mutableStateOf(initial?.role) }
    var status by remember { mutableStateOf(initial?.status.orEmpty()) }
    var minutes by remember { mutableStateOf(initial?.minutes?.toString().orEmpty()) }
    var requester by remember { mutableStateOf(initial?.requester.orEmpty()) }
    var decider by remember { mutableStateOf(initial?.decider.orEmpty()) }
    var reference by remember { mutableStateOf(initial?.reference.orEmpty()) }
    var tags by remember { mutableStateOf(initial?.tags.orEmpty().joinToString(" ")) }
    var at by remember { mutableStateOf(initial?.at ?: LocalDateTime.now().withSecond(0).withNano(0)) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    val dictation = rememberOnDeviceDictation(
        allowNetworkFallback = false,
        onResult = { spoken -> text = if (text.isBlank()) spoken else "$text $spoken" },
        onMessage = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    )
    DictationListeningDialog(dictation)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(if (initial == null) "Log" else "Edit entry", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogKind.entries.forEach { option ->
                    FilterChip(
                        selected = kind == option,
                        onClick = { kind = option },
                        label = { Text(option.label) },
                        leadingIcon = { Icon(option.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("What happened") },
                minLines = 3,
                trailingIcon = {
                    IconButton(onClick = { dictation.start() }) { Icon(Icons.Rounded.Mic, contentDescription = "Dictate") }
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (roles.isNotEmpty()) {
                Text("Role", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    roles.forEach { option ->
                        InputChip(selected = role == option, onClick = { role = if (role == option) null else option }, label = { Text(option) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(status, { status = it }, label = { Text("Status") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    minutes,
                    { value -> minutes = value.filter(Char::isDigit).take(4) },
                    label = { Text("Minutes") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(requester, { requester = it }, label = { Text("Asked by") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(decider, { decider = it }, label = { Text("Decided by") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(reference, { reference = it }, label = { Text("Reference (ticket, link)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tags, { tags = it }, label = { Text("Tags") }, supportingText = { Text("Separated by spaces") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { pickDate = true }, label = { Text(at.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))) })
                AssistChip(onClick = { pickTime = true }, label = { Text(at.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) })
            }
            Text(
                "Roles, never names: who asked and who decided are written as roles, and the confidentiality guard holds the names you protect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                Box(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    enabled = text.isNotBlank(),
                    onClick = {
                        onSave(
                            LogEntry(
                                at = at,
                                kind = kind,
                                text = text.trim(),
                                role = role,
                                status = status.trim().ifEmpty { null },
                                minutes = minutes.toIntOrNull()?.takeIf { it > 0 },
                                requester = requester.trim().ifEmpty { null },
                                decider = decider.trim().ifEmpty { null },
                                reference = reference.trim().ifEmpty { null },
                                tags = tags.split(' ', ',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
                            )
                        )
                    }
                ) { Text("Save") }
            }
        }
    }

    if (pickDate) {
        PickDateDialog(
            initial = at.toLocalDate(),
            onConfirm = { date -> at = LocalDateTime.of(date, at.toLocalTime()); pickDate = false },
            onDismiss = { pickDate = false }
        )
    }
    if (pickTime) {
        PickTimeDialog(
            initial = at.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
            onConfirm = { hhmm -> at = LocalDateTime.of(at.toLocalDate(), LocalTime.parse(hhmm)); pickTime = false },
            onDismiss = { pickTime = false }
        )
    }
}
