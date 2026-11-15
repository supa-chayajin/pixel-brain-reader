package cloud.wafflecommons.pixelbrainreader.ui.privatevault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.people.CheckItem
import cloud.wafflecommons.pixelbrainreader.data.people.Feedback
import cloud.wafflecommons.pixelbrainreader.data.people.Person
import cloud.wafflecommons.pixelbrainreader.data.people.PersonKind
import cloud.wafflecommons.pixelbrainreader.data.people.PersonNote
import cloud.wafflecommons.pixelbrainreader.ui.privatevault.PeopleViewModel.ListKind
import java.time.format.DateTimeFormatter

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/** The People tab's list (12.19, P1): the people the mode shows, their open points and last 1:1. */
@Composable
fun PeopleList(viewModel: PeopleViewModel, padding: PaddingValues) {
    val state by viewModel.list.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        problem?.let { item(key = "problem") { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (state.hiddenWork > 0) {
            item(key = "hidden") {
                Text(
                    if (state.hiddenWork == 1) "1 work person stays hidden outside ${state.workLabel} mode."
                    else "${state.hiddenWork} work people stay hidden outside ${state.workLabel} mode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        when {
            !state.loaded -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            state.people.isEmpty() -> item(key = "empty") {
                Text(
                    "No one yet. Add the people you have 1:1s with: your manager, your team, family, friends. " +
                        "Each note is encrypted like your journal and never reaches the AI on its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> items(state.people, key = { it.id }) { person ->
                PersonCard(person, onClick = { viewModel.open(person.id) })
            }
        }
    }
}

@Composable
private fun PersonCard(person: Person, onClick: () -> Unit) {
    val note = person.note
    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (note == null) {
                Text("A note that doesn't open", style = MaterialTheme.typography.titleMedium)
                Text(
                    "It doesn't open with the journal's password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(kindIcon(note.kind), contentDescription = kindLabel(note.kind), modifier = Modifier.size(18.dp))
                Text(note.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (note.role.isNotBlank()) Text(note.role, style = MaterialTheme.typography.bodyMedium)
            Text(summary(note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "3 open points · 2 actions · last 1:1 12 Nov 2026". */
private fun summary(note: PersonNote): String = buildList {
    add(plural(note.openPointsLeft, "open point"))
    add(plural(note.actionsLeft, "action"))
    add(note.lastOneOnOne?.let { "last 1:1 ${it.format(DAY)}" } ?: "no 1:1 yet")
}.joinToString(" · ")

private fun plural(count: Int, word: String): String = if (count == 1) "1 $word" else "$count ${word}s"

private fun kindIcon(kind: PersonKind) = if (kind == PersonKind.WORK) Icons.Outlined.Work else Icons.Outlined.Home

private fun kindLabel(kind: PersonKind) = if (kind == PersonKind.WORK) "Work" else "Personal"

/** Adds a person: their name, work or personal, their role. */
@Composable
fun AddPersonDialog(onAdd: (String, PersonKind, String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(PersonKind.WORK) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a person") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                KindChoice(kind) { kind = it }
                OutlinedTextField(value = role, onValueChange = { role = it }, label = { Text("Role (optional)") }, singleLine = true)
                Text(
                    "The note is named with a neutral id in the vault: nothing in git says who it is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onAdd(name, kind, role) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun KindChoice(kind: PersonKind, onPick: (PersonKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PersonKind.entries.forEach { option ->
            FilterChip(
                selected = kind == option,
                onClick = { onPick(option) },
                label = { Text(kindLabel(option)) },
                leadingIcon = { Icon(kindIcon(option), contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }
    }
}

/** A person's page (12.19, D8): everything their note holds, and the extras (D9). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonPage(viewModel: PeopleViewModel, personId: String) {
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    // Every edit updates the repository's list, and the page follows it (the mode's filter aside).
    val person by viewModel.openPerson.collectAsStateWithLifecycle()
    val note = person?.note
    val readOnly = viewModel.readOnly
    BackHandler { viewModel.open(null) }

    var editing by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var deciding by rememberSaveable { mutableStateOf(false) }
    var feedbackGiven by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var taskFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(note?.name ?: if (person == null) "" else "A note that doesn't open", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.open(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (note != null && !readOnly) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Edit") },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                    onClick = { menu = false; editing = true }
                                )
                                if (note.kind == PersonKind.WORK) {
                                    DropdownMenuItem(
                                        text = { Text("Log a decision") },
                                        leadingIcon = { Icon(Icons.Outlined.Gavel, null) },
                                        onClick = { menu = false; deciding = true }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                    onClick = { menu = false; deleting = true }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (note == null) {
            // Null while the person arrives from the repository: nothing to say yet.
            if (person != null) {
                Text(
                    "This note doesn't open with the journal's password.",
                    modifier = Modifier.padding(padding).padding(16.dp)
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {},
                            label = { Text(kindLabel(note.kind)) },
                            leadingIcon = { Icon(kindIcon(note.kind), contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        if (note.role.isNotBlank()) Text(note.role, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        note.lastOneOnOne?.let { "Last 1:1: ${it.format(DAY)}" } ?: "No 1:1 written yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!readOnly) Button(onClick = { viewModel.startEntry(personId) }) { Text("New 1:1") }
                        if (!readOnly) {
                            FilledTonalButton(onClick = { viewModel.askCoach(personId) }) {
                                Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Ask Coach")
                            }
                        }
                    }
                }
            }
            item(key = "points") {
                CheckSection(
                    title = "Open points",
                    items = note.openPoints,
                    addLabel = "Add an open point",
                    readOnly = readOnly,
                    onAdd = { viewModel.addItem(personId, ListKind.OPEN_POINTS, it) },
                    onToggle = { viewModel.toggleItem(personId, ListKind.OPEN_POINTS, it) },
                    onRemove = { viewModel.removeItem(personId, ListKind.OPEN_POINTS, it) }
                )
            }
            item(key = "mine") {
                CheckSection(
                    title = "Your actions",
                    items = note.myActions,
                    addLabel = "Add one of your actions",
                    readOnly = readOnly,
                    onAdd = { viewModel.addItem(personId, ListKind.MY_ACTIONS, it) },
                    onToggle = { viewModel.toggleItem(personId, ListKind.MY_ACTIONS, it) },
                    onRemove = { viewModel.removeItem(personId, ListKind.MY_ACTIONS, it) },
                    onToTask = { taskFrom = it.text }
                )
            }
            item(key = "theirs") {
                CheckSection(
                    title = "Their actions",
                    items = note.theirActions,
                    addLabel = "Add one of their actions",
                    readOnly = readOnly,
                    onAdd = { viewModel.addItem(personId, ListKind.THEIR_ACTIONS, it) },
                    onToggle = { viewModel.toggleItem(personId, ListKind.THEIR_ACTIONS, it) },
                    onRemove = { viewModel.removeItem(personId, ListKind.THEIR_ACTIONS, it) }
                )
            }
            item(key = "log") {
                PageSection("1:1s") {
                    if (note.oneOnOnes.isEmpty()) Hint("No 1:1 written yet.")
                    note.oneOnOnes.sortedByDescending { it.date }.forEach { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !readOnly) { viewModel.startEntry(personId, entry.date) }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(entry.date.format(DAY), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            if (entry.text.isNotBlank()) {
                                Text(entry.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            item(key = "feedback") {
                PageSection("Feedback") {
                    if (note.feedback.isEmpty()) Hint("No feedback noted yet.")
                    note.feedback.sortedByDescending { it.date }.forEach { feedback ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${feedback.date.format(DAY)} · ${if (feedback.given) "given" else "received"} · ${feedback.text}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (!readOnly) {
                                IconButton(onClick = { viewModel.removeFeedback(personId, feedback) }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Remove")
                                }
                            }
                        }
                    }
                    if (!readOnly) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { feedbackGiven = true }) { Text("Feedback given") }
                            TextButton(onClick = { feedbackGiven = false }) { Text("Feedback received") }
                        }
                    }
                }
            }
            item(key = "context") {
                PageSection("Context") { if (note.context.isBlank()) Hint("Nothing yet: Edit in the menu.") else Text(note.context) }
            }
            item(key = "personal") {
                PageSection("Personal") { if (note.personal.isBlank()) Hint("Birthday, family, things to remember: Edit in the menu.") else Text(note.personal) }
            }
        }
    }

    if (editing) {
        EditPersonDialog(note = note ?: return, onSave = { name, kind, role, context, personal ->
            viewModel.edit(personId, name, kind, role, context, personal)
            editing = false
        }, onDismiss = { editing = false })
    }
    if (deleting && note != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${note.name}'s note?") },
            text = { Text("The note leaves the vault. Its older versions stay in git history, encrypted.") },
            confirmButton = { TextButton(onClick = { deleting = false; viewModel.delete(personId) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } }
        )
    }
    if (deciding && note != null) {
        DecisionDialog(role = note.role, onLog = { text, decidedBy ->
            viewModel.logDecision(text, decidedBy) { ok -> if (ok) deciding = false }
        }, onDismiss = { deciding = false })
    }
    feedbackGiven?.let { given ->
        TextDialog(
            title = if (given) "Feedback you gave" else "Feedback you received",
            label = "What was said",
            onSave = { viewModel.addFeedback(personId, given, it); feedbackGiven = null },
            onDismiss = { feedbackGiven = null }
        )
    }
    taskFrom?.let { action ->
        TaskDialog(initial = action, onAdd = { text ->
            viewModel.actionToTask(text) { ok -> if (ok) taskFrom = null }
        }, onDismiss = { taskFrom = null })
    }
}

@Composable
private fun PageSection(title: String, content: @Composable () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CheckSection(
    title: String,
    items: List<CheckItem>,
    addLabel: String,
    readOnly: Boolean,
    onAdd: (String) -> Unit,
    onToggle: (CheckItem) -> Unit,
    onRemove: (CheckItem) -> Unit,
    onToTask: ((CheckItem) -> Unit)? = null
) {
    var draft by rememberSaveable(title) { mutableStateOf("") }
    PageSection(title) {
        if (items.isEmpty()) Hint("Nothing here.")
        // Open ones first, then the done ones.
        items.sortedBy { it.done }.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = item.done, onCheckedChange = { onToggle(item) }, enabled = !readOnly)
                Column(Modifier.weight(1f)) {
                    Text(
                        item.text,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                        color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                    item.added?.let { Text(it.format(DAY), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (!readOnly && onToTask != null && !item.done) {
                    IconButton(onClick = { onToTask(item) }) { Icon(Icons.Outlined.AddTask, contentDescription = "Add to today's tasks") }
                }
                if (!readOnly) {
                    IconButton(onClick = { onRemove(item) }) { Icon(Icons.Outlined.Close, contentDescription = "Remove") }
                }
            }
        }
        if (!readOnly) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(addLabel) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(enabled = draft.isNotBlank(), onClick = { onAdd(draft); draft = "" }) { Text("Add") }
            }
        }
    }
}

@Composable
private fun EditPersonDialog(
    note: PersonNote,
    onSave: (String, PersonKind, String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(note.name) }
    var kind by rememberSaveable { mutableStateOf(note.kind) }
    var role by rememberSaveable { mutableStateOf(note.role) }
    var context by rememberSaveable { mutableStateOf(note.context) }
    var personal by rememberSaveable { mutableStateOf(note.personal) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                KindChoice(kind) { kind = it }
                OutlinedTextField(value = role, onValueChange = { role = it }, label = { Text("Role") }, singleLine = true)
                OutlinedTextField(value = context, onValueChange = { context = it }, label = { Text("Context") }, minLines = 3)
                OutlinedTextField(value = personal, onValueChange = { personal = it }, label = { Text("Personal details") }, minLines = 3)
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onSave(name, kind, role, context, personal) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun TextDialog(title: String, label: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, minLines = 2) },
        confirmButton = { Button(enabled = text.isNotBlank(), onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** D9: a decision to the mission log, by role. Never the name: the log keeps roles. */
@Composable
private fun DecisionDialog(role: String, onLog: (String, String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var decidedBy by rememberSaveable { mutableStateOf(role) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log a decision") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("The decision") }, minLines = 2)
                OutlinedTextField(value = decidedBy, onValueChange = { decidedBy = it }, label = { Text("Decided by (a role)") }, singleLine = true)
                Hint("It goes to your mission log with this role, never a name.")
            }
        },
        confirmButton = { Button(enabled = text.isNotBlank(), onClick = { onLog(text, decidedBy) }) { Text("Log it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** D9: one of your actions as a task for today, in words you edit first. */
@Composable
private fun TaskDialog(initial: String, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to today's tasks") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Task") }, minLines = 2)
                Hint("The task is plain text on today's board and in the daily note: leave names out.")
            }
        },
        confirmButton = { Button(enabled = text.isNotBlank(), onClick = { onAdd(text) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A 1:1's entry, written in place of the person's page (D7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneOnOneEditor(viewModel: PeopleViewModel) {
    val draft by viewModel.entry.collectAsStateWithLifecycle()
    val entry = draft ?: return
    val name = viewModel.person(entry.personId)?.note?.name.orEmpty()
    BackHandler { viewModel.saveEntry() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("1:1 · ${entry.date.format(DAY)}", fontWeight = FontWeight.SemiBold)
                        if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = { viewModel.saveEntry() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Save and close") } },
                actions = { TextButton(onClick = { viewModel.closeEntry() }) { Text("Discard") } }
            )
        }
    ) { padding ->
        OutlinedTextField(
            value = entry.text,
            onValueChange = viewModel::editEntry,
            placeholder = { Text("What you talked about, what was decided, what comes next…") },
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxWidth().heightIn(min = 240.dp)
        )
    }
}

/** Ask Coach's answer (D9): shown here under the journal's FLAG_SECURE, never saved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachSheet(viewModel: PeopleViewModel) {
    val coach by viewModel.coach.collectAsStateWithLifecycle()
    val state = coach ?: return
    ModalBottomSheet(onDismissRequest = viewModel::closeCoach) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Prepare the next 1:1", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Hint("From the role, the open points and the actions; the name stays out. Nothing of it is saved.")
            HorizontalDivider()
            when {
                state.loading -> Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                else -> SelectionContainer { Text(state.answer.orEmpty(), style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}
