package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.rounded.BeachAccess
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
import java.time.YearMonth
import javax.inject.Inject

/** This month's mission-log figures for the hub; null when the log can't be read. */
data class LogSummary(val entries: Int, val decisions: Int, val offTicketMinutes: Int)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WorkHubViewModel @Inject constructor(
    missionLogRepository: MissionLogRepository,
    modesRepository: ModesRepository
) : ViewModel() {

    val visibility: StateFlow<Visibility> = modesRepository.visibility

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

    FormScaffold(title = "Work", onBack = onBack) {
        if (visibility.hideWork) {
            Text(
                "Outside Work mode: figures are hidden. Capture still works.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
