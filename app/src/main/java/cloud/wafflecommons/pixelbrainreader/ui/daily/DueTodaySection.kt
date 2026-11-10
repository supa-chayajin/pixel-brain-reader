package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.daily.DueItem
import cloud.wafflecommons.pixelbrainreader.data.daily.DueKind
import cloud.wafflecommons.pixelbrainreader.data.daily.DueToday
import cloud.wafflecommons.pixelbrainreader.data.daily.DueTodayRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * The "Due today" block ([cloud.wafflecommons.pixelbrainreader.data.daily.DueToday]): re-read when
 * the day or the mode changes and each time the app comes back to the foreground. Reads only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DueTodayViewModel @Inject constructor(
    private val repository: DueTodayRepository,
    modesRepository: ModesRepository,
    appClock: AppClock
) : ViewModel() {

    private val reads = MutableStateFlow(0)

    /** The day the items are for, and the items. */
    val due: StateFlow<Pair<LocalDate, List<DueItem>>?> =
        combine(appClock.today, modesRepository.visibility, reads) { today, visibility, _ -> today to visibility }
            .mapLatest { (today, visibility) ->
                try {
                    today to repository.due(today, visibility)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    today to emptyList()
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { appClock.foregroundEvents.collect { reads.update { it + 1 } } }
    }
}

/**
 * Milestones and your actions due today or overdue, a row each (a tap opens the note), then the chores
 * due as one line (a tap opens Chores) so they never bury the rest (owner, 2026-11-10).
 */
@Composable
fun DueTodaySection(
    items: List<DueItem>,
    today: LocalDate,
    onOpenNote: (String) -> Unit,
    onOpenChores: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "⏰ Due today",
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        items.filter { it.kind != DueKind.CHORE }.forEach { item ->
            DueRow(
                icon = if (item.kind == DueKind.MILESTONE) Icons.Rounded.Flag else Icons.Rounded.TaskAlt,
                text = item.text,
                context = if (item.kind == DueKind.MILESTONE) "Objective · ${item.context}" else item.context,
                late = item.overdueDays(today),
                onClick = { item.path?.let(onOpenNote) }
            )
        }
        DueToday.choreSummary(items)?.let { chores ->
            DueRow(
                icon = Icons.Rounded.CleaningServices,
                text = if (chores.count == 1) "1 chore due" else "${chores.count} chores due",
                context = chores.rooms.joinToString(", "),
                late = java.time.temporal.ChronoUnit.DAYS.between(chores.oldestDue, today),
                onClick = onOpenChores
            )
        }
    }
}

@Composable
private fun DueRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    context: String,
    late: Long,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = context,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    late <= 0 -> "today"
                    late == 1L -> "1 day late"
                    else -> "$late days late"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (late > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
