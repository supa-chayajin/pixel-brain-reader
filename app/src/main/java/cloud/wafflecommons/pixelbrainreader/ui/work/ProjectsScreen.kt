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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectRepository
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectSummary
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

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val handoffRepository: cloud.wafflecommons.pixelbrainreader.data.work.DeskHandoffRepository,
    modesRepository: ModesRepository
) : ViewModel() {

    private val _handoffs = MutableStateFlow<List<cloud.wafflecommons.pixelbrainreader.data.work.DeskHandoffItem>>(emptyList())
    /** The open hand-offs of the project shown by [ProjectScreen]. */
    val handoffs: StateFlow<List<cloud.wafflecommons.pixelbrainreader.data.work.DeskHandoffItem>> = _handoffs.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

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
            else -> list.forEach { p ->
                SectionCard(
                    title = p.title,
                    icon = Icons.Rounded.RocketLaunch,
                    subtitle = projectLine(p),
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
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    LaunchedEffect(visibility) { viewModel.load() }
    val p = projects?.firstOrNull { it.root == root }
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
                if (projects == null) "Loading…" else "This project is hidden or no longer exists.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@FormScaffold
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
