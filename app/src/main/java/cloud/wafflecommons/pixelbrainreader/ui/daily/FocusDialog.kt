package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.focus.FocusRepository
import cloud.wafflecommons.pixelbrainreader.data.focus.FocusSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Daily's + menu entry for a focus session (D9): start one, or stop the running one. */
@HiltViewModel
class FocusViewModel @Inject constructor(private val repository: FocusRepository) : ViewModel() {

    val session: StateFlow<FocusSession?> = repository.session.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun start(minutes: Int) {
        viewModelScope.launch { repository.start(minutes) }
    }

    fun stop() {
        viewModelScope.launch { repository.stop() }
    }
}

@Composable
fun FocusDialog(session: FocusSession?, onStart: (Int) -> Unit, onStop: () -> Unit, onDismiss: () -> Unit) {
    val running = session?.takeIf { !it.isOver(System.currentTimeMillis()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Timer, contentDescription = null) },
        title = { Text(if (running == null) "Focus session" else "Focus session running") },
        text = {
            if (running == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "A countdown in your notifications and status bar. Finish it to earn XP, then log what you worked on.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    FocusSession.DURATIONS.forEach { minutes ->
                        FilledTonalButton(onClick = { onStart(minutes) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (minutes == FocusSession.DEFAULT_MINUTES) "$minutes minutes (usual)" else "$minutes minutes")
                        }
                    }
                }
            } else {
                val end = LocalTime.ofInstant(Instant.ofEpochMilli(running.endMillis), ZoneId.systemDefault())
                Text(
                    "${running.minutes} minutes, until ${end.format(DateTimeFormatter.ofPattern("HH:mm"))}. Stopping now earns no XP.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            if (running != null) TextButton(onClick = onStop) { Text("Stop") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (running == null) "Cancel" else "Keep going") }
        }
    )
}
