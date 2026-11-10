package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.ui.Modifier
import cloud.wafflecommons.pixelbrainreader.ui.components.CardGrid
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.ViewKanban
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogRepository
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

/** This month's mission-log figures for the hub; null when the log can't be read. */
data class LogSummary(val entries: Int, val decisions: Int, val offTicketMinutes: Int)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WorkHubViewModel @Inject constructor(
    missionLogRepository: MissionLogRepository,
    modesRepository: ModesRepository,
    private val loadRecoveryRepository: cloud.wafflecommons.pixelbrainreader.data.work.LoadRecoveryRepository,
    private val preferences: cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
) : ViewModel() {

    /** The tools pinned to the top of the hub (the defaults until the user pins or unpins one). */
    val pinned: StateFlow<Set<String>> = preferences.pinnedTools
        .map { it ?: HubTool.DEFAULT_PINNED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HubTool.DEFAULT_PINNED)

    fun setPinned(tool: HubTool, pinned: Boolean) {
        viewModelScope.launch { preferences.setToolPinned(tool.id, pinned, HubTool.DEFAULT_PINNED) }
    }

    val visibility: StateFlow<Visibility> = modesRepository.visibility

    private val _overload = kotlinx.coroutines.flow.MutableStateFlow<cloud.wafflecommons.pixelbrainreader.data.work.OverloadSignal?>(null)

    /** This week's overload signal, if any (the report is cached, so the hub stays quick). */
    val overload: StateFlow<cloud.wafflecommons.pixelbrainreader.data.work.OverloadSignal?> = _overload

    private val _breakdown = kotlinx.coroutines.flow.MutableStateFlow<cloud.wafflecommons.pixelbrainreader.data.work.LoadBreakdown?>(null)

    /** Where this week's load comes from, shown inside the overload card. */
    val breakdown: StateFlow<cloud.wafflecommons.pixelbrainreader.data.work.LoadBreakdown?> = _breakdown

    init {
        viewModelScope.launch {
            val report = try {
                loadRecoveryRepository.report()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _overload.value = report?.overload
            _breakdown.value = report?.breakdown
        }
    }

    val logSummary: StateFlow<LogSummary?> = missionLogRepository.changes
        .mapLatest {
            try {
                val entries = missionLogRepository.entries(YearMonth.now())
                LogSummary(
                    entries = entries.size,
                    decisions = entries.count { it.kind == LogKind.DECISION },
                    offTicketMinutes = entries.filter { it.kind == LogKind.OFF_TICKET }.sumOf { it.minutes ?: 0 }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** The tools hub (named in Modes settings): the mission log and the tools built on it. Figures stay hidden outside Work mode. */
@Composable
fun WorkHubScreen(
    onBack: () -> Unit,
    onOpenLog: (add: Boolean) -> Unit,
    onOpen: (route: String) -> Unit,
    viewModel: WorkHubViewModel = hiltViewModel()
) {
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    val summary by viewModel.logSummary.collectAsStateWithLifecycle()
    val overload by viewModel.overload.collectAsStateWithLifecycle()
    val breakdown by viewModel.breakdown.collectAsStateWithLifecycle()
    val pinned by viewModel.pinned.collectAsStateWithLifecycle()
    var showAll by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    FormScaffold(title = visibility.appearance.toolkitName, onBack = onBack) {
        if (visibility.hideWork) {
            Text(
                "Outside ${visibility.workLabel} mode: figures are hidden. Capture still works.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!visibility.hideWork && !visibility.hideHealth) overload?.let { OverloadCard(it, breakdown) }
        SectionCard(
            title = "Mission log",
            icon = Icons.AutoMirrored.Rounded.EventNote,
            subtitle = when {
                visibility.hideWork -> "Facts, decisions, requests, refusals and off-ticket time."
                summary == null -> "Facts, decisions, requests, refusals and off-ticket time, one file per month."
                else -> summary!!.let { s ->
                    "This month: ${s.entries} entries, ${s.decisions} decisions" +
                        if (s.offTicketMinutes > 0) ", ${durationLabel(s.offTicketMinutes)} off-ticket." else "."
                }
            }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onOpenLog(true) }) { Text("Log") }
                OutlinedButton(onClick = { onOpenLog(false) }) { Text("Open") }
            }
        }
        val pinnedTools = HubTool.entries.filter { it.id in pinned }
        val otherTools = HubTool.entries.filter { it.id !in pinned }
        // Two per row on a wide window (CardGrid), pinned first as always.
        CardGrid(pinnedTools) { tool, cardModifier ->
            HubCard(tool, pinned = true, onPin = { viewModel.setPinned(tool, it) }, onClick = { onOpen(tool.route) }, modifier = cardModifier)
        }
        if (otherTools.isNotEmpty()) {
            androidx.compose.material3.TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Hide other tools" else "All tools (${otherTools.size} more)")
            }
            if (showAll) {
                Text(
                    "Tap the pin to keep a tool at the top.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CardGrid(otherTools) { tool, cardModifier ->
                    HubCard(tool, pinned = false, onPin = { viewModel.setPinned(tool, it) }, onClick = { onOpen(tool.route) }, modifier = cardModifier)
                }
            }
        }
    }
}

/** The toolkit's tools, in hub order. Ids are stored in preferences: never rename one. */
enum class HubTool(
    val id: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val subtitle: String,
    val route: String
) {
    PROJECTS("projects", "Projects", Icons.Rounded.RocketLaunch,
        "Each project's last two weeks from the worklogs, where you left off, next actions, key notes and what links to it.",
        cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.PROJECTS),
    BOARDS("boards", "Boards", Icons.Rounded.ViewKanban,
        "Any folder as a board on the vault's statuses, with next steps, blockers and target dates.",
        cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.BOARDS),
    MEETINGS("meetings", "Meetings & next actions", Icons.Rounded.Groups,
        "Meeting notes with decisions and actions; your @moi actions from every note; the weekly load.",
        cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.MEETINGS),
    OBJECTIVES("objectives", "Objectives", Icons.Rounded.Flag,
        "Notes tagged #objectif: milestones, the log entries that serve them, a weekly slot.",
        cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.OBJECTIVES),
    EVIDENCE("evidence", "Evidence report", Icons.Rounded.Summarize,
        "A month or a quarter of the log as a draft for your reviews: every number from the data.",
        cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.EVIDENCE),
    INBOX("inbox", "Inbox filing", Icons.Rounded.MoveToInbox,
        "Each inbox note with the folder and tags its closest notes suggest; filing keeps the vault's rules.",
        cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.INBOX),
    ACTIVITY("activity", "Vault activity", Icons.Rounded.BarChart,
        "What the last sync brought, commits per day, the notes that changed most and the new ones.",
        cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.ACTIVITY),
    SNIPPETS("snippets", "Snippets & runbooks", Icons.Rounded.Code,
        "Every code block of the vault, ready to copy; runbooks played step by step, with an incident log.",
        cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.SNIPPETS),
    LOAD("load", "Load vs recovery", Icons.Rounded.MonitorHeart,
        "Meetings and off-ticket time against sleep, HRV, resting heart rate and mood, with sample sizes.",
        cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.LOAD),
    LEAVE("leave", "Leave & handover", Icons.Rounded.BeachAccess,
        "A handover draft before you leave, a briefing of what changed when you're back.",
        cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.LEAVE),
    EXPLAIN_ERROR("explain_error", "Explain an error", Icons.Rounded.BugReport,
        "Paste an error: where your notes and runbooks met it before, a short explanation, a bug note.",
        cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.EXPLAIN_ERROR);

    companion object {
        /** Pinned until the user chooses: the everyday ones. */
        val DEFAULT_PINNED: Set<String> = setOf(PROJECTS.id, BOARDS.id, MEETINGS.id, INBOX.id)
    }
}

/** A hub entry that opens a tool, with a pin to keep it at the top. */
@Composable
internal fun HubCard(
    tool: HubTool,
    pinned: Boolean,
    onPin: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(
        title = tool.title,
        icon = tool.icon,
        subtitle = tool.subtitle,
        modifier = modifier.clickable(onClick = onClick),
        trailing = {
            androidx.compose.material3.IconToggleButton(checked = pinned, onCheckedChange = onPin) {
                androidx.compose.material3.Icon(
                    if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (pinned) "Unpin ${tool.title}" else "Pin ${tool.title}"
                )
            }
        }
    )
}
