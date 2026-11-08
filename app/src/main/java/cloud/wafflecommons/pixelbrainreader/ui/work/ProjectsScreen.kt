package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.work.PairingStats
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectAction
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectFormat
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectRepository
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectSummary
import cloud.wafflecommons.pixelbrainreader.data.work.TodoAnswers
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val handoffRepository: cloud.wafflecommons.pixelbrainreader.data.work.DeskHandoffRepository,
    private val todoRepository: cloud.wafflecommons.pixelbrainreader.data.work.ProjectTodoRepository,
    modesRepository: ModesRepository
) : ViewModel() {

    private val _handoffs = MutableStateFlow<List<cloud.wafflecommons.pixelbrainreader.data.work.DeskHandoffItem>>(emptyList())
    /** The open hand-offs of the project shown by [ProjectScreen]. */
    val handoffs: StateFlow<List<cloud.wafflecommons.pixelbrainreader.data.work.DeskHandoffItem>> = _handoffs.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    /** The project shown by [ProjectScreen]: Loading until read, then the summary or Missing. */
    sealed interface ProjectState {
        data object Loading : ProjectState
        data object Missing : ProjectState
        data class Loaded(val summary: ProjectSummary) : ProjectState
    }

    private val _project = MutableStateFlow<ProjectState>(ProjectState.Loading)
    val project: StateFlow<ProjectState> = _project.asStateFlow()

    fun loadProject(root: String) {
        viewModelScope.launch {
            val p = try {
                repository.project(root)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _project.value = p?.let { ProjectState.Loaded(it) } ?: ProjectState.Missing
        }
    }

    fun loadHandoffs(root: String) {
        viewModelScope.launch { _handoffs.value = safeHandoffs(root) }
    }

    fun addHandoff(root: String, title: String, text: String) {
        viewModelScope.launch {
            try {
                handoffRepository.add(root, title, text)
                _message.value = "Handed off — your desktop sees it after the next sync"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Couldn't save the hand-off"
            }
            _handoffs.value = safeHandoffs(root)
        }
    }

    /** Writes [text] under [question] in the project TODO (read from disk first), then reloads the page. */
    fun answer(root: String, question: ProjectAction, text: String) {
        viewModelScope.launch {
            val ok = try { todoRepository.answer(question, text) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            _message.value = if (ok) "Answered: your next desktop session reads it in the TODO" else "That question changed meanwhile — refreshed"
            loadProject(root)
        }
    }

    fun completeHandoff(root: String, item: cloud.wafflecommons.pixelbrainreader.data.work.DeskHandoffItem) {
        viewModelScope.launch {
            val ok = try { handoffRepository.complete(root, item) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            if (!ok) _message.value = "That hand-off changed meanwhile — refreshed"
            _handoffs.value = safeHandoffs(root)
        }
    }

    private suspend fun safeHandoffs(root: String) = try {
        handoffRepository.items(root).filter { !it.done }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    val visibility: StateFlow<Visibility> = modesRepository.visibility

    private val _projects = MutableStateFlow<List<ProjectSummary>?>(null)
    /** null while loading. */
    val projects: StateFlow<List<ProjectSummary>?> = _projects.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _projects.value = try {
                repository.projects()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

/** Every project of the vault, the most recently active first. */
@Composable
fun ProjectsScreen(
    onBack: () -> Unit,
    onOpenProject: (root: String) -> Unit,
    selectedRoot: String? = null,
    viewModel: ProjectsViewModel = hiltViewModel()
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    LaunchedEffect(visibility) { viewModel.load() }

    FormScaffold(title = "Projects", onBack = onBack) {
        val list = projects
        when {
            list == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.isEmpty() -> Text(
                "No project folder found under 20_Projects/." +
                    if (visibility.hideWork) " Work projects are hidden outside ${visibility.workLabel} mode." else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> {
                // C7: the shipping cadence across the visible projects, measured only.
                val roll = androidx.compose.runtime.remember(list) { ProjectFormat.pairing(list.flatMap { it.days }) }
                val active = list.count { it.commits > 0 || it.sessions > 0 }
                SectionCard(
                    title = "Last $ACTIVITY_DAYS days",
                    icon = Icons.Rounded.Insights,
                    subtitle = "${plural(active, "active project")} · ${plural(roll.commits, "commit")} · ${plural(roll.sessions, "AI session")}"
                ) {
                    PairingLines(roll)
                }
                list.forEach { p -> ProjectCard(p, onOpenProject, selected = p.root == selectedRoot) }
            }
        }
    }
}

@Composable
private fun ProjectCard(p: ProjectSummary, onOpenProject: (root: String) -> Unit, selected: Boolean = false) {
    SectionCard(
        title = p.title,
        icon = Icons.Rounded.RocketLaunch,
        subtitle = projectLine(p),
        selected = selected,
        modifier = Modifier.clickable { onOpenProject(p.root) }
    ) {
        p.nextAction?.let {
            Text(
                "Next: ${it.text}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** One project's page: activity, where you left off, next actions, key notes, links. */
@Composable
fun ProjectScreen(
    root: String,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    viewModel: ProjectsViewModel = hiltViewModel()
) {
    val state by viewModel.project.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    LaunchedEffect(root, visibility) { viewModel.loadProject(root) }
    // Side by side in the hub, the list and this page share the ViewModel: until this project loads,
    // it may still hold the one opened before, which must not show under this one's title.
    val loaded = (state as? ProjectsViewModel.ProjectState.Loaded)?.summary
    val stale = loaded != null && loaded.root != root
    val p = loaded?.takeUnless { stale }
    val handoffs by viewModel.handoffs.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(root) { viewModel.loadHandoffs(root) }
    val snackbar = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    FormScaffold(title = p?.title ?: root.substringAfterLast('/'), onBack = onBack, snackbarHostState = snackbar) {
        if (p == null) {
            Text(
                if (state == ProjectsViewModel.ProjectState.Loading || stale) "Loading…" else "This project is hidden or no longer exists.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@FormScaffold
        }
        // The desktop's latest report on this project, one line (Step 5): what was done, decided, left.
        p.latestReport?.let { report ->
            NoteRow("📄 Latest Claude report: ${report.title}", p.latestReportDate?.format(DAY)) { onOpenNote(report.path) }
        }
        SectionCard(title = "Last ${ACTIVITY_DAYS} days", icon = Icons.Rounded.Insights, subtitle = projectLine(p)) {
            if (p.days.isEmpty()) {
                Text("No worklog in this window.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                p.days.take(7).forEach { d ->
                    Text(
                        "${d.date.format(DAY)} · ${plural(d.commits, "commit")}" +
                            (if (d.sessions > 0) " · ${plural(d.sessions, "AI session")}" else ""),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                PairingLines(ProjectFormat.pairing(p.days))
            }
        }
        p.leftOff?.takeIf { it.subjects.isNotEmpty() }?.let { day ->
            SectionCard(title = "Where you left off", icon = Icons.Rounded.History, subtitle = day.date.format(DAY)) {
                day.subjects.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        HandoffCard(
            handoffs = handoffs,
            onSend = { text -> viewModel.addHandoff(p.root, p.title, text) },
            onDone = { viewModel.completeHandoff(p.root, it) }
        )
        SectionCard(
            title = "Next actions",
            icon = Icons.Rounded.Checklist,
            subtitle = if (p.actions.isEmpty()) "No open checkbox in this project's notes." else "${p.actions.size} open, yours first"
        ) {
            p.actions.take(8).forEach { a ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenNote(a.path) }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(if (a.mine) "☐ @me" else "☐", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(a.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            p.todoNote?.let { todo ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                NoteRow("Session punch list (TODO)", todo.updated?.let { "updated ${it.format(DAY)}" }) { onOpenNote(todo.path) }
            }
        }
        if (p.claudeQueue.isNotEmpty()) {
            ClaudeQueueCard(
                queue = p.claudeQueue,
                onOpenTodo = { onOpenNote(p.claudeQueue.first().path) },
                onAnswer = { question, text -> viewModel.answer(p.root, question, text) }
            )
        }
        if (p.keyNotes.isNotEmpty() || p.recentNotes.isNotEmpty()) {
            SectionCard(title = "Notes", icon = Icons.AutoMirrored.Rounded.Article) {
                p.keyNotes.forEach { n -> NoteRow(n.title, n.status) { onOpenNote(n.path) } }
                if (p.keyNotes.isNotEmpty() && p.recentNotes.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                p.recentNotes.forEach { n -> NoteRow(n.title, n.updated?.format(DAY)) { onOpenNote(n.path) } }
            }
        }
        if (p.linkedFrom.isNotEmpty()) {
            SectionCard(
                title = "Linked from",
                icon = Icons.Rounded.Link,
                subtitle = "${plural(p.linkedFrom.size, "note")} outside the project link here"
            ) {
                p.linkedFrom.take(10).forEach { path ->
                    NoteRow(path.substringAfterLast('/').removeSuffix(".md"), path.substringBeforeLast('/')) { onOpenNote(path) }
                }
            }
        }
    }
}

/** Hand-off to desktop (C13): a note for your next desktop session on this project. */
@Composable
private fun HandoffCard(
    handoffs: List<cloud.wafflecommons.pixelbrainreader.data.work.DeskHandoffItem>,
    onSend: (String) -> Unit,
    onDone: (cloud.wafflecommons.pixelbrainreader.data.work.DeskHandoffItem) -> Unit
) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    SectionCard(
        title = "Hand off to desktop",
        icon = Icons.Rounded.Computer,
        subtitle = "Shown when your next desktop session on this project starts."
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Next: …") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                if (text.isNotBlank()) { onSend(text); text = "" }
            }),
            trailingIcon = {
                androidx.compose.material3.IconButton(
                    onClick = { if (text.isNotBlank()) { onSend(text); text = "" } },
                    enabled = text.isNotBlank()
                ) {
                    androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Hand off")
                }
            }
        )
        handoffs.forEach { h ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = androidx.compose.ui.semantics.Role.Checkbox) { onDone(h) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("☐", color = MaterialTheme.colorScheme.primary)
                Text(h.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                h.at?.let {
                    Text(it.format(DateTimeFormatter.ofPattern("d MMM HH:mm")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (handoffs.isNotEmpty()) {
            Text("Tap one when it's done.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The project TODO's lines that aren't yours: what the desktop sessions do once you give the go.
 * The first few, each group under its `##` heading; the whole card opens the TODO.
 */
@Composable
private fun ClaudeQueueCard(queue: List<ProjectAction>, onOpenTodo: () -> Unit, onAnswer: (ProjectAction, String) -> Unit) {
    // Questions get their own list with an Answer button; the preview shows the rest.
    val questions = queue.filter(TodoAnswers::isQuestion)
    val tasks = queue - questions.toSet()
    var answering by remember { mutableStateOf<ProjectAction?>(null) }
    SectionCard(
        title = "Claude's queue",
        icon = Icons.Rounded.SmartToy,
        subtitle = "${queue.size} open in the TODO, done on your go",
        modifier = Modifier.clickable(onClickLabel = "Open the TODO", onClick = onOpenTodo)
    ) {
        tasks.take(QUEUE_PREVIEW).forEachIndexed { i, a ->
            if (a.section != null && a.section != tasks.getOrNull(i - 1)?.section) {
                Text(
                    a.section,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("☐", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(a.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (tasks.size > QUEUE_PREVIEW) {
            Text(
                "+ ${tasks.size - QUEUE_PREVIEW} more, tap to open the TODO",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (questions.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Open questions", style = MaterialTheme.typography.labelLarge)
            questions.take(QUESTIONS_SHOWN).forEach { q ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("❓ ${q.text}", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { answering = q }) { Text("Answer") }
                }
            }
        }
    }
    answering?.let { q ->
        AnswerDialog(question = q.text, onAnswer = { text -> onAnswer(q, text); answering = null }, onDismiss = { answering = null })
    }
}

/** One answer under a TODO question, for the next desktop session to read. */
@Composable
private fun AnswerDialog(question: String, onAnswer: (String) -> Unit, onDismiss: () -> Unit) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Answer") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(question, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text("Your answer") })
                Text(
                    "Written under the question in the TODO; the desktop session reads it there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onAnswer(text) }) { Text("Answer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The AI-pairing figures (C7): what the worklogs measured, "not recorded" where an older worklog
 * didn't write it. No cost estimate, no score.
 */
@Composable
private fun PairingLines(stats: PairingStats) {
    if (stats.sessions == 0) {
        Text("No AI session in this window.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    PairingRow("AI sessions", "${stats.sessions} on ${plural(stats.sessionDays, "day")}")
    PairingRow("Prompts per session", stats.promptsPerSession?.let(::oneDecimal) ?: NOT_RECORDED)
    PairingRow("Commits per session", stats.commitsPerSession?.let(::oneDecimal) ?: NOT_RECORDED)
    PairingRow("Tool calls", stats.toolCalls?.toString() ?: NOT_RECORDED)
    PairingRow("Cache reads", stats.cacheReadShare?.let { "${(it * 100).roundToInt()}% of tokens" } ?: NOT_RECORDED)
    PairingRow(
        "Top tools",
        stats.topTools?.let { tools -> tools.joinToString(" · ") { (name, calls) -> "$name $calls" }.ifEmpty { "none" } } ?: NOT_RECORDED
    )
}

@Composable
private fun PairingRow(label: String, value: String) {
    // The short label keeps its width; the value takes the rest, right-aligned. (With the weight on
    // the label, a long value — the top tools — squeezed it to one letter a line in a narrow column.)
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

private const val NOT_RECORDED = "not recorded"

/** "5.3": one decimal under ten, whole numbers above; the UI is English, so always a dot. */
private fun oneDecimal(x: Double): String = if (x >= 10) x.roundToInt().toString() else ((x * 10).roundToInt() / 10.0).toString()

@Composable
private fun NoteRow(title: String, detail: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
    androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))
}

private const val ACTIVITY_DAYS = 14
private const val QUEUE_PREVIEW = 3
private const val QUESTIONS_SHOWN = 5
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

private fun plural(n: Int, one: String) = "$n $one${if (n == 1) "" else "s"}"

/** "12 commits · 3 AI sessions in 14 days · active 2 days ago". */
internal fun projectLine(p: ProjectSummary, today: LocalDate = LocalDate.now()): String {
    val parts = mutableListOf<String>()
    if (p.commits > 0 || p.sessions > 0) {
        parts += buildString {
            append(plural(p.commits, "commit"))
            if (p.sessions > 0) append(" · ").append(plural(p.sessions, "AI session"))
            append(" in $ACTIVITY_DAYS days")
        }
    }
    p.lastActivity?.let { last ->
        val ago = ChronoUnit.DAYS.between(last, today)
        parts += when {
            ago <= 0L -> "active today"
            ago == 1L -> "active yesterday"
            ago < 60L -> "active $ago days ago"
            else -> "last active ${last.format(DateTimeFormatter.ofPattern("MMM yyyy"))}"
        }
    }
    p.status?.let { parts += it }
    return parts.joinToString(" · ").ifEmpty { "No activity yet" }
}
