package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.work.ArrangedProjects
import cloud.wafflecommons.pixelbrainreader.data.work.PairingStats
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectArrangement
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectArrangementRepository
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectAction
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectFormat
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectRepository
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectSummary
import cloud.wafflecommons.pixelbrainreader.data.work.projectBadgeText
import cloud.wafflecommons.pixelbrainreader.data.work.projectDisplayName
import cloud.wafflecommons.pixelbrainreader.data.work.TodoAnswers
import cloud.wafflecommons.pixelbrainreader.data.work.WorklogDay
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
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
    private val arrangementRepository: ProjectArrangementRepository,
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

    private val _arrangement = MutableStateFlow(ProjectArrangement())
    /** The pinned and hidden projects (the vault's, so every device lists them alike). */
    val arrangement: StateFlow<ProjectArrangement> = _arrangement.asStateFlow()

    // The list's own message: beside a project page (a wide window), the page shows [message].
    private val _arrangeMessage = MutableStateFlow<String?>(null)
    val arrangeMessage: StateFlow<String?> = _arrangeMessage.asStateFlow()
    fun clearArrangeMessage() { _arrangeMessage.value = null }

    fun load() {
        viewModelScope.launch {
            // The arrangement first, so the list never shows unarranged for a frame.
            _arrangement.value = try {
                arrangementRepository.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProjectArrangement()
            }
            _projects.value = try {
                repository.projects()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun pin(root: String) = arrange { it.pin(root) }
    fun unpin(root: String) = arrange { it.unpin(root) }
    fun hide(root: String) = arrange { it.hide(root) }
    fun show(root: String) = arrange { it.show(root) }
    fun swapPinned(a: String, b: String) = arrange { it.swapPinned(a, b) }

    /** Saves a change of the arrangement; a read-only device refuses it (and says so). */
    private fun arrange(change: (ProjectArrangement) -> ProjectArrangement) {
        viewModelScope.launch {
            val saved = try {
                arrangementRepository.update(change)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when {
                saved != null -> _arrangement.value = saved
                !VaultWriteGate.readOnly -> _arrangeMessage.value = "Couldn't save the arrangement"
            }
        }
    }
}

/**
 * Every project of the vault, the most recently active first. A navigation tab since 12.7.0:
 * [onBack] is null there (no back arrow), set when the toolkit opened it.
 */
@Composable
fun ProjectsScreen(
    onBack: (() -> Unit)?,
    onOpenProject: (root: String) -> Unit,
    selectedRoot: String? = null,
    viewModel: ProjectsViewModel = hiltViewModel()
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val arrangement by viewModel.arrangement.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    val message by viewModel.arrangeMessage.collectAsStateWithLifecycle()
    LaunchedEffect(visibility) { viewModel.load() }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearArrangeMessage() }
    }
    // The arrangement lives in the vault: a read-only device shows the phone's and can't change it.
    val canArrange = !VaultWriteGate.readOnly
    var arranging by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var hiddenOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val arranged = remember(projects, arrangement) { projects?.let { arrangement.arrange(it) } }

    FormScaffold(
        title = "Projects",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            if (canArrange && !projects.isNullOrEmpty()) {
                CortexIconButton(onClick = { arranging = true }) {
                    Icon(Icons.Rounded.Tune, contentDescription = "Arrange projects")
                }
            }
        }
    ) {
        val list = projects
        when {
            list == null || arranged == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.isEmpty() -> Text(
                "No project folder found under 20_Projects/." +
                    if (visibility.hideWork) " Work projects are hidden outside ${visibility.workLabel} mode." else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> {
                // C7: the shipping cadence across the projects shown (hidden ones aside), measured only.
                val shown = arranged.shown
                val roll = remember(shown) { ProjectFormat.pairing(shown.flatMap { it.days }) }
                val active = shown.count { it.commits > 0 || it.sessions > 0 }
                SectionCard(title = "Last $ACTIVITY_DAYS days", icon = Icons.Rounded.Insights) {
                    // The window at a glance, then what the worklogs measured.
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        BigStat(active, if (active == 1) "active project" else "active projects", Modifier.weight(1f))
                        BigStat(roll.commits, if (roll.commits == 1) "commit" else "commits", Modifier.weight(1f))
                        BigStat(roll.sessions, if (roll.sessions == 1) "AI session" else "AI sessions", Modifier.weight(1f))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    PairingLines(roll)
                }
                if (shown.isEmpty()) {
                    Text("Every project is hidden: bring one back below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                shown.forEach { p ->
                    ProjectCard(p, onOpenProject, selected = p.root == selectedRoot, pinned = arrangement.isPinned(p.root))
                }
                if (arranged.hidden.isNotEmpty()) {
                    HiddenProjects(
                        hidden = arranged.hidden,
                        open = hiddenOpen,
                        onToggle = { hiddenOpen = !hiddenOpen },
                        onShow = if (canArrange) viewModel::show else null,
                        onOpenProject = onOpenProject
                    )
                }
            }
        }
    }
    if (arranging && arranged != null) {
        ArrangeProjectsSheet(
            arranged = arranged,
            onPin = viewModel::pin,
            onUnpin = viewModel::unpin,
            onHide = viewModel::hide,
            onShow = viewModel::show,
            onSwapPinned = viewModel::swapPinned,
            onDismiss = { arranging = false }
        )
    }
}

/**
 * A project at a glance: a badge in a colour of its own (the folder's number, else its initials),
 * its name without the folder's number and underscores, its status, its last [ACTIVITY_DAYS] days
 * of commits as bars, the window's figures and the next action.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectCard(p: ProjectSummary, onOpenProject: (root: String) -> Unit, selected: Boolean = false, pinned: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    ElevatedCard(
        onClick = { onOpenProject(p.root) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        // [selected]: the project whose page is open beside the list (a wide window).
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) colors.secondaryContainer else colors.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProjectBadge(p)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (pinned) {
                            Icon(
                                Icons.Rounded.PushPin,
                                contentDescription = "Pinned",
                                tint = colors.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            projectDisplayName(p.title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        activityAgo(p.lastActivity) ?: "No activity yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                p.status?.let {
                    Spacer(Modifier.width(8.dp))
                    StatusPill(it)
                }
            }
            ActivityBars(p.days)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatChip(Icons.Rounded.Commit, plural(p.commits, "commit"))
                if (p.sessions > 0) StatChip(Icons.Rounded.SmartToy, plural(p.sessions, "AI session"))
                if (p.actions.isNotEmpty()) StatChip(Icons.Rounded.Checklist, plural(p.actions.size, "open action"))
            }
            p.nextAction?.let { NextActionBox(it.text) }
        }
    }
}

/** A figure of the window, big, over its label. */
@Composable
private fun BigStat(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The folder's number ("21") or the name's initials, on the project's own colour. */
@Composable
private fun ProjectBadge(p: ProjectSummary, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val (container, content) = badgeColors(p.root)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Text(
            projectBadgeText(p.name, p.title),
            style = if (size < 48.dp) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = content
        )
    }
}

/**
 * A soft colour per project, the same on every visit (a hash of its folder), in one of twelve hues
 * and toned for the theme: deep on a dark surface, pale on a light one.
 */
@Composable
private fun badgeColors(seed: String): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val hue = ((seed.hashCode() and Int.MAX_VALUE) % BADGE_HUES) * (360f / BADGE_HUES)
    return Color.hsl(hue, 0.42f, if (dark) 0.30f else 0.86f) to Color.hsl(hue, 0.65f, if (dark) 0.84f else 0.28f)
}

@Composable
private fun StatusPill(status: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** Commits per day over the last [ACTIVITY_DAYS] days, oldest on the left; a quiet day is a stub. */
@Composable
private fun ActivityBars(days: List<WorklogDay>) {
    val perDay = androidx.compose.runtime.remember(days) { commitsPerDay(days, LocalDate.now()) }
    val most = perDay.maxOrNull()?.takeIf { it > 0 } ?: 1
    val busy = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clearAndSetSemantics {
                contentDescription = "${plural(perDay.sum(), "commit")} in $ACTIVITY_DAYS days, at most ${perDay.max()} a day"
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        perDay.forEach { commits ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(if (commits == 0) 0.1f else 0.2f + 0.8f * commits / most)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (commits == 0) quiet else busy)
            )
        }
    }
}

@Composable
private fun StatChip(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun NextActionBox(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Next", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The hidden projects, folded at the bottom of the list: one tap opens one, "Show" brings it back. */
@Composable
private fun HiddenProjects(
    hidden: List<ProjectSummary>,
    open: Boolean,
    onToggle: () -> Unit,
    onShow: ((String) -> Unit)?,
    onOpenProject: (root: String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClickLabel = if (open) "Fold the hidden projects" else "Unfold the hidden projects", onClick = onToggle)
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Hidden (${hidden.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.animation.AnimatedVisibility(visible = open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hidden.forEach { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { onOpenProject(p.root) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProjectBadge(p, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            projectDisplayName(p.title),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (onShow != null) TextButton(onClick = { onShow(p.root) }) { Text("Show") }
                    }
                }
            }
        }
    }
}

/** Pin, order, hide and show the projects; every change is saved to the vault at once. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ArrangeProjectsSheet(
    arranged: ArrangedProjects,
    onPin: (String) -> Unit,
    onUnpin: (String) -> Unit,
    onHide: (String) -> Unit,
    onShow: (String) -> Unit,
    onSwapPinned: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberBottomSheetState(
            initialValue = androidx.compose.material3.SheetValue.Hidden,
            enabledValues = setOf(androidx.compose.material3.SheetValue.Hidden, androidx.compose.material3.SheetValue.Expanded)
        )
    ) {
        ArrangeProjectsContent(arranged, onPin, onUnpin, onHide, onShow, onSwapPinned)
    }
}

/** The arrange sheet's body, apart so a device test can draw it without the vault. */
@Composable
internal fun ArrangeProjectsContent(
    arranged: ArrangedProjects,
    onPin: (String) -> Unit,
    onUnpin: (String) -> Unit,
    onHide: (String) -> Unit,
    onShow: (String) -> Unit,
    onSwapPinned: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item(key = "intro") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Arrange projects", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Pinned projects stay on top, in this order; the others follow by latest activity. " +
                        "Hidden ones wait at the bottom of the list. Saved in your vault: every device lists them alike.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (arranged.pinned.isNotEmpty()) {
            item(key = "h-pinned") { ArrangeHeader("Pinned") }
            arranged.pinned.forEachIndexed { index, p ->
                item(key = "p:" + p.root) {
                    val name = projectDisplayName(p.title)
                    ArrangeRow(p) {
                        IconButton(enabled = index > 0, onClick = { onSwapPinned(p.root, arranged.pinned[index - 1].root) }) {
                            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move $name up")
                        }
                        IconButton(enabled = index < arranged.pinned.lastIndex, onClick = { onSwapPinned(p.root, arranged.pinned[index + 1].root) }) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move $name down")
                        }
                        IconButton(onClick = { onUnpin(p.root) }) {
                            Icon(Icons.Rounded.PushPin, contentDescription = "Unpin $name", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { onHide(p.root) }) {
                            Icon(Icons.Rounded.VisibilityOff, contentDescription = "Hide $name")
                        }
                    }
                }
            }
        }
        if (arranged.others.isNotEmpty()) {
            item(key = "h-others") { ArrangeHeader(if (arranged.pinned.isEmpty()) "By latest activity" else "Then, by latest activity") }
            arranged.others.forEach { p ->
                item(key = "o:" + p.root) {
                    val name = projectDisplayName(p.title)
                    ArrangeRow(p) {
                        IconButton(onClick = { onPin(p.root) }) {
                            Icon(Icons.Outlined.PushPin, contentDescription = "Pin $name")
                        }
                        IconButton(onClick = { onHide(p.root) }) {
                            Icon(Icons.Rounded.VisibilityOff, contentDescription = "Hide $name")
                        }
                    }
                }
            }
        }
        if (arranged.hidden.isNotEmpty()) {
            item(key = "h-hidden") { ArrangeHeader("Hidden") }
            arranged.hidden.forEach { p ->
                item(key = "x:" + p.root) {
                    ArrangeRow(p, dimmed = true) {
                        IconButton(onClick = { onShow(p.root) }) {
                            Icon(Icons.Rounded.Visibility, contentDescription = "Show ${projectDisplayName(p.title)}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArrangeHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun ArrangeRow(p: ProjectSummary, dimmed: Boolean = false, actions: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProjectBadge(p, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            projectDisplayName(p.title),
            style = MaterialTheme.typography.bodyLarge,
            color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        actions()
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

    FormScaffold(title = projectDisplayName(p?.title ?: root.substringAfterLast('/')), onBack = onBack, snackbarHostState = snackbar) {
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
                // The vault-kept TODO.md is the live list; a TODO a desktop script copies from a
                // repository is that repository's punch list, only linked (settled decision #15).
                NoteRow(if (todo.isMirror) "Punch list (mirrored)" else "Live TODO", todo.updated?.let { "updated ${it.format(DAY)}" }) { onOpenNote(todo.path) }
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
private const val BADGE_HUES = 12
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
    activityAgo(p.lastActivity, today)?.let { parts += it }
    p.status?.let { parts += it }
    return parts.joinToString(" · ").ifEmpty { "No activity yet" }
}

/** "active yesterday", "active 3 days ago", "last active Mar 2026"; null without any activity. */
internal fun activityAgo(last: LocalDate?, today: LocalDate = LocalDate.now()): String? {
    if (last == null) return null
    val ago = ChronoUnit.DAYS.between(last, today)
    return when {
        ago <= 0L -> "active today"
        ago == 1L -> "active yesterday"
        ago < 60L -> "active $ago days ago"
        else -> "last active ${last.format(DateTimeFormatter.ofPattern("MMM yyyy"))}"
    }
}

/** Commits on each of the [ACTIVITY_DAYS] days ending [today], oldest first; 0 for a day without a worklog. */
internal fun commitsPerDay(days: List<WorklogDay>, today: LocalDate): List<Int> {
    val byDate = days.groupBy { it.date }.mapValues { (_, sameDay) -> sameDay.sumOf { it.commits } }
    return (ACTIVITY_DAYS - 1 downTo 0).map { back -> byDate[today.minusDays(back.toLong())] ?: 0 }
}
