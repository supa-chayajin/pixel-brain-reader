package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.ViewKanban
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    private val loadRecoveryRepository: cloud.wafflecommons.pixelbrainreader.data.work.LoadRecoveryRepository
) : ViewModel() {

    val visibility: StateFlow<Visibility> = modesRepository.visibility

    private val _overload = kotlinx.coroutines.flow.MutableStateFlow<cloud.wafflecommons.pixelbrainreader.data.work.OverloadSignal?>(null)

    /** This week's overload signal, if any (the report is cached, so the hub stays quick). */
    val overload: StateFlow<cloud.wafflecommons.pixelbrainreader.data.work.OverloadSignal?> = _overload

    init {
        viewModelScope.launch {
            _overload.value = try {
                loadRecoveryRepository.report().overload
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
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

/** Work: the mission log and the tools built on it. Figures stay hidden outside Work mode. */
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

    FormScaffold(title = "Work", onBack = onBack) {
        if (visibility.hideWork) {
            Text(
                "Outside Work mode: figures are hidden. Capture still works.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!visibility.hideWork && !visibility.hideHealth) overload?.let { OverloadCard(it) }
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
        HubCard(
            title = "Boards",
            icon = Icons.Rounded.ViewKanban,
            subtitle = "Any folder as a board on the vault's statuses, with next steps, blockers and target dates.",
            onClick = { onOpen(cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.BOARDS) }
        )
        HubCard(
            title = "Meetings & next actions",
            icon = Icons.Rounded.Groups,
            subtitle = "Meeting notes with decisions and actions; your @moi actions from every note; the weekly load.",
            onClick = { onOpen(cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.MEETINGS) }
        )
        HubCard(
            title = "Objectives",
            icon = Icons.Rounded.Flag,
            subtitle = "Notes tagged #objectif: milestones, the log entries that serve them, a weekly slot.",
            onClick = { onOpen(cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.OBJECTIVES) }
        )
        HubCard(
            title = "Evidence report",
            icon = Icons.Rounded.Summarize,
            subtitle = "A month or a quarter of the log as a draft for your reviews: every number from the data.",
            onClick = { onOpen(cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.EVIDENCE) }
        )
        HubCard(
            title = "Inbox filing",
            icon = Icons.Rounded.MoveToInbox,
            subtitle = "Each inbox note with the folder and tags its closest notes suggest; filing keeps the vault's rules.",
            onClick = { onOpen(cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.INBOX) }
        )
        HubCard(
            title = "Vault activity",
            icon = Icons.Rounded.BarChart,
            subtitle = "What the last sync brought, commits per day, the notes that changed most and the new ones.",
            onClick = { onOpen(cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.ACTIVITY) }
        )
        HubCard(
            title = "Snippets & runbooks",
            icon = Icons.Rounded.Code,
            subtitle = "Every code block of the vault, ready to copy; runbooks played step by step, with an incident log.",
            onClick = { onOpen(cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.SNIPPETS) }
        )
        HubCard(
            title = "Load vs recovery",
            icon = Icons.Rounded.MonitorHeart,
            subtitle = "Meetings and off-ticket time against sleep, HRV, resting heart rate and mood, with sample sizes.",
            onClick = { onOpen(cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.LOAD) }
        )
        HubCard(
            title = "Leave & handover",
            icon = Icons.Rounded.BeachAccess,
            subtitle = "A handover draft before you leave, a briefing of what changed when you're back.",
            onClick = { onOpen(cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes.LEAVE) }
        )
    }
}

/** A hub entry that opens a tool. */
@Composable
internal fun HubCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, subtitle: String, onClick: () -> Unit) {
    SectionCard(
        title = title,
        icon = icon,
        subtitle = subtitle,
        modifier = androidx.compose.ui.Modifier.clickable(onClick = onClick)
    )
}
