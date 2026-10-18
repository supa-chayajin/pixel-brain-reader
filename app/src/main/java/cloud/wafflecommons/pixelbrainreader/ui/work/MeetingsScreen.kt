package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.work.ActionItem
import cloud.wafflecommons.pixelbrainreader.data.work.Meeting
import cloud.wafflecommons.pixelbrainreader.data.work.MeetingRepository
import cloud.wafflecommons.pixelbrainreader.data.work.WeekLoad
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.PickDateDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.PickTimeDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import cloud.wafflecommons.pixelbrainreader.ui.components.TextPromptDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

data class MeetingsState(val meetings: List<Meeting>, val load: List<WeekLoad>, val actions: List<ActionItem>, val folder: String)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MeetingsViewModel @Inject constructor(
    private val repository: MeetingRepository,
    modesRepository: ModesRepository
) : ViewModel() {

    val visibility: StateFlow<Visibility> = modesRepository.visibility

    val state: StateFlow<MeetingsState?> = combine(repository.changes, modesRepository.visibility) { _, v -> v }
        .mapLatest { v ->
            MeetingsState(
                meetings = repository.meetings().filter { v.isVisible(it.note.path) },
                load = repository.weeklyLoad(),
                actions = repository.myActions().filter { v.isVisible(it.path) },
                folder = repository.folder()
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun create(title: String, date: LocalDate, start: LocalTime?, minutes: Int?, participants: String?, open: (String) -> Unit) =
        run("Couldn't create the meeting note") { open(repository.create(title, date, start, minutes, participants)) }

    fun logDecisions(meeting: Meeting) = run("Couldn't log the decisions") {
        val added = repository.logDecisions(meeting)
        _messages.send(if (added == 0) "Every decision is already in the log." else "$added decisions added to the mission log.")
    }

    fun complete(action: ActionItem) = run("Couldn't complete the action") { repository.complete(action) }

    fun setFolder(folder: String) = run("Couldn't save the folder") { repository.setFolder(folder) }

    private fun run(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("$failure: ${e.message}")
            }
        }
    }
}

private fun LocalDate.medium(): String = format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** Meetings & requests (#6): meeting notes, their decisions and actions, and the weekly load. */
@Composable
fun MeetingsScreen(onBack: () -> Unit, onOpenNote: (String) -> Unit, onOpenActions: () -> Unit, viewModel: MeetingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }
    var creating by remember { mutableStateOf(false) }
    var editingFolder by remember { mutableStateOf(false) }

    FormScaffold(
        title = "Meetings",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = { CortexIconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, contentDescription = "New meeting") } }
    ) {
        val s = state
        if (s == null) {
            EmptyHint("Reading your meetings…")
            return@FormScaffold
        }
        if (visibility.hideWork) {
            Text("Outside Work mode: meetings in work folders are hidden.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard(
            title = "My next actions",
            icon = Icons.Rounded.TaskAlt,
            subtitle = if (s.actions.isEmpty()) "No open action of yours. In a note, write \"- [ ] @moi …\" (a due date with 📅)." else "${s.actions.size} open, soonest due first.",
            modifier = Modifier.clickable(onClick = onOpenActions)
        ) {
            s.actions.take(3).forEach { action -> Text("• ${action.text}" + (action.due?.let { " — ${it.medium()}" } ?: ""), style = MaterialTheme.typography.bodyMedium) }
        }
        LoadCard(s.load)
        Text("Recent meetings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (s.meetings.isEmpty()) EmptyHint("No meeting note yet. New ones go to ${s.folder}.")
        s.meetings.take(20).forEach { meeting ->
            SectionCard(
                title = meeting.note.title,
                icon = Icons.Rounded.Groups,
                subtitle = listOfNotNull(
                    meeting.date?.medium(),
                    meeting.durationMinutes?.let { "$it min" },
                    "${meeting.decisions.size} decisions",
                    "${meeting.actions.count { !it.done }} open actions"
                ).joinToString(" · "),
                modifier = Modifier.clickable { onOpenNote(meeting.note.path) }
            ) {
                if (meeting.decisions.isNotEmpty()) {
                    TextButton(onClick = { viewModel.logDecisions(meeting) }) { Text("Log the decisions") }
                }
            }
        }
        TextButton(onClick = { editingFolder = true }) { Text("New meetings go to ${s.folder}") }
    }

    if (creating) {
        NewMeetingSheet(
            onCreate = { title, date, start, minutes, participants ->
                viewModel.create(title, date, start, minutes, participants, onOpenNote)
                creating = false
            },
            onDismiss = { creating = false }
        )
    }
    if (editingFolder) {
        TextPromptDialog(
            title = "Meetings folder",
            label = "Vault folder",
            initial = state?.folder.orEmpty(),
            onConfirm = { viewModel.setFolder(it); editingFolder = false },
            onDismiss = { editingFolder = false }
        )
    }
}

@Composable
private fun LoadCard(load: List<WeekLoad>) {
    SectionCard(
        title = "Load, week by week",
        icon = Icons.Rounded.Insights,
        subtitle = "Meetings from your meeting notes; requests and off-ticket time from the mission log."
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("Week of", "Meetings", "Requests", "Off-ticket").forEachIndexed { index, header ->
                Text(header, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(if (index == 0) 1.4f else 1f))
            }
        }
        load.reversed().forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(week.weekStart.format(DateTimeFormatter.ofPattern("d MMM")), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.4f))
                Text(
                    "${week.meetings}" + (week.meetingMinutes?.let { " · ${durationLabel(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Text("${week.requests}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(week.offTicketMinutes?.let(::durationLabel) ?: "–", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewMeetingSheet(onCreate: (String, LocalDate, LocalTime?, Int?, String?) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var start by remember { mutableStateOf<LocalTime?>(null) }
    var minutes by remember { mutableStateOf("") }
    var participants by remember { mutableStateOf("") }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("New meeting", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { pickDate = true }, label = { Text(date.medium()) })
                AssistChip(onClick = { pickTime = true }, label = { Text(start?.toString() ?: "Start time") })
            }
            OutlinedTextField(
                minutes,
                { value -> minutes = value.filter(Char::isDigit).take(3) },
                label = { Text("Length (minutes)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(participants, { participants = it }, label = { Text("Participants (roles)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(
                "The note opens with agenda, notes, decisions and actions sections. Write your own actions as \"- [ ] @moi …\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(enabled = title.isNotBlank(), onClick = { onCreate(title.trim(), date, start, minutes.toIntOrNull(), participants) }) { Text("Create") }
            }
        }
    }
    if (pickDate) PickDateDialog(initial = date, onConfirm = { date = it; pickDate = false }, onDismiss = { pickDate = false })
    if (pickTime) PickTimeDialog(initial = start?.toString() ?: "09:00", onConfirm = { start = LocalTime.parse(it); pickTime = false }, onDismiss = { pickTime = false })
}

/** My open actions across the vault: tick them here, they are ticked in their notes. */
@Composable
fun NextActionsScreen(onBack: () -> Unit, onOpenNote: (String) -> Unit, viewModel: MeetingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }
    val today = LocalDate.now()
    FormScaffold(title = "Next actions", onBack = onBack, snackbarHostState = snackbarHostState) {
        val actions = state?.actions
        when {
            actions == null -> EmptyHint("Reading your actions…")
            actions.isEmpty() -> EmptyHint("Nothing open. Your actions are the \"- [ ] @moi …\" lines of your notes.")
            else -> actions.groupBy { it.path }.forEach { (path, items) ->
                Text(
                    path.substringAfterLast('/').removeSuffix(".md"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenNote(path) }.padding(top = 8.dp)
                )
                items.forEach { action ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = false, onCheckedChange = { viewModel.complete(action) })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(action.text, style = MaterialTheme.typography.bodyMedium)
                            action.due?.let { due ->
                                Text(
                                    "due ${due.medium()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (due < today) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
