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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.daily.RolloverOffer
import cloud.wafflecommons.pixelbrainreader.data.daily.RolloverTask
import cloud.wafflecommons.pixelbrainreader.data.daily.TaskRollover
import cloud.wafflecommons.pixelbrainreader.data.daily.TaskRolloverRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * The rollover offer on Daily ([TaskRollover]): re-read when the day changes and each time the app
 * comes back to the foreground (a pull may have brought the earlier note); today's board and the
 * handled keys are followed live, so a brought task leaves the card at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TaskRolloverViewModel @Inject constructor(
    private val repository: TaskRolloverRepository,
    dashboardRepository: DailyDashboardRepository,
    private val appClock: AppClock
) : ViewModel() {

    private val reads = MutableStateFlow(0)

    val offer: StateFlow<RolloverOffer?> = combine(appClock.today, reads) { today, _ -> today }
        .flatMapLatest { today ->
            combine(
                flow { emit(repository.earlierDay(today)) },
                dashboardRepository.getLiveTasks(today),
                repository.handled
            ) { earlier, todayTasks, handled ->
                earlier?.let { (from, tasks) -> TaskRollover.offer(from, today, tasks, todayTasks, handled) }
            }
        }
        // A note that can't be read just means no offer: the board itself says what went wrong.
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { appClock.foregroundEvents.collect { reads.update { it + 1 } } }
    }

    fun bring(task: RolloverTask) = bringAll(listOf(task))

    fun bringAll(tasks: List<RolloverTask> = offer.value?.tasks.orEmpty()) {
        val today = offer.value?.to ?: return
        viewModelScope.launch { repository.bring(today, tasks) }
    }

    fun dismiss(task: RolloverTask) = dismissAll(listOf(task))

    fun dismissAll(tasks: List<RolloverTask> = offer.value?.tasks.orEmpty()) {
        val today = offer.value?.to ?: return
        viewModelScope.launch { repository.dismiss(today, tasks) }
    }
}

private const val SHOWN_TASKS = 5

/** The earlier day's unfinished tasks, each with Bring (to today's board) and Dismiss. */
@Composable
fun TaskRolloverCard(
    offer: RolloverOffer,
    onBring: (RolloverTask) -> Unit,
    onDismiss: (RolloverTask) -> Unit,
    onBringAll: () -> Unit,
    onDismissAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val from = if (offer.from == offer.to.minusDays(1)) "yesterday"
        else offer.from.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Unfinished $from",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            offer.tasks.take(SHOWN_TASKS).forEach { task ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (task.priority > 1) "‼️ ${task.label}" else task.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onBring(task) }) {
                        Icon(Icons.Rounded.Add, contentDescription = "Bring to today")
                    }
                    IconButton(onClick = { onDismiss(task) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Dismiss")
                    }
                }
            }
            if (offer.tasks.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hidden = offer.tasks.size - SHOWN_TASKS
                    if (hidden > 0) {
                        Text(
                            text = "and $hidden more",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    TextButton(onClick = onDismissAll) { Text("Dismiss all") }
                    TextButton(onClick = onBringAll) { Text("Bring all") }
                }
            }
        }
    }
}
