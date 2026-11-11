package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
import cloud.wafflecommons.pixelbrainreader.data.review.ReviewWeek
import cloud.wafflecommons.pixelbrainreader.data.review.WeeklyReviewFormat
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/** The latest weekly review still waiting for its plan, shown on [day]'s page. */
data class WeeklyPlanPrompt(val day: LocalDate, val week: ReviewWeek)

/**
 * "Plan your week" (D6, owner 2026-11-11): from the review's Sunday night until its "Semaine
 * prochaine" is filled, read from disk each time Daily shows. A banner, not a block: the Daily
 * order and fold don't change. Nothing on a read-only device.
 */
@HiltViewModel
class WeeklyPlanViewModel @Inject constructor(
    private val noteRepository: NoteRepository,
    appClock: AppClock
) : ViewModel() {

    private val _prompt = MutableStateFlow<WeeklyPlanPrompt?>(null)
    val prompt: StateFlow<WeeklyPlanPrompt?> = _prompt.asStateFlow()

    init {
        viewModelScope.launch { appClock.today.collect { reload() } }
        viewModelScope.launch { appClock.foregroundEvents.collect { reload() } }
    }

    fun reload() {
        if (VaultWriteGate.readOnly) return
        viewModelScope.launch {
            val now = LocalDateTime.now()
            val week = ReviewWeek.lastDue(now)
            val content = noteRepository.getNoteContent(week.path)
            _prompt.value = if (content != null && WeeklyReviewFormat.planLines(content).isEmpty()) {
                WeeklyPlanPrompt(now.toLocalDate(), week)
            } else {
                null
            }
        }
    }
}

@Composable
fun WeeklyPlanBanner(prompt: WeeklyPlanPrompt, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.EditCalendar, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Plan your week",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "Week ${prompt.week.week}'s review is ready. Write next week's plan at its end.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            TextButton(onClick = onOpen) { Text("Open") }
        }
    }
}
