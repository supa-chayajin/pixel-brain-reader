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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.NextPlan
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.daily.ShutdownRepository
import cloud.wafflecommons.pixelbrainreader.data.daily.ShutdownState
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import javax.inject.Inject

/** After "Close the day": the evening mode offered with one tap, under the owner's name for it. */
data class ModeOffer(val mode: ContextMode, val label: String)

/**
 * The shutdown ritual's banner (D8, owner 2026-11-11): from 30 minutes before work ends on a work
 * day until the day is closed, re-read on a day change, a return to the app, a task handled and a
 * mode change, and woken at its start time. Nothing on a read-only device.
 */
@HiltViewModel
class ShutdownViewModel @Inject constructor(
    private val repository: ShutdownRepository,
    private val modesRepository: ModesRepository,
    appClock: AppClock,
    preferences: UserPreferencesRepository
) : ViewModel() {

    private val _state = MutableStateFlow<ShutdownState?>(null)
    val state: StateFlow<ShutdownState?> = _state.asStateFlow()

    private val _offer = MutableStateFlow<ModeOffer?>(null)
    val offer: StateFlow<ModeOffer?> = _offer.asStateFlow()

    private var wake: Job? = null

    init {
        viewModelScope.launch { appClock.today.collect { reload() } }
        viewModelScope.launch { appClock.foregroundEvents.collect { reload() } }
        viewModelScope.launch { preferences.rolloverHandled.collect { reload() } }
        viewModelScope.launch { modesRepository.visibility.collect { reload() } }
    }

    fun reload() {
        if (VaultWriteGate.readOnly) return
        viewModelScope.launch {
            val now = LocalDateTime.now()
            _state.value = repository.state(now)
            wake?.cancel()
            repository.nextStart(now)?.let { start ->
                wake = viewModelScope.launch {
                    delay(Duration.between(now, start).toMillis().coerceAtLeast(0))
                    reload()
                }
            }
        }
    }

    fun tomorrow(task: DailyTaskEntity) = act { day -> repository.toTomorrow(day, task) }
    fun someday(task: DailyTaskEntity) = act { day -> repository.toSomeday(day, task) }
    fun letGo(task: DailyTaskEntity) = act { day -> repository.letGo(day, task) }

    fun logWin(text: String) {
        viewModelScope.launch { repository.logWin(text) }
    }

    fun closeDay() {
        val day = _state.value?.day ?: return
        viewModelScope.launch {
            val evening = repository.closeDay(day)
            _offer.value = evening?.let { ModeOffer(it, modesRepository.visibility.value.label(it)) }
            reload()
        }
    }

    fun acceptOffer() {
        val offer = _offer.value ?: return
        _offer.value = null
        viewModelScope.launch { repository.switchTo(offer.mode) }
    }

    fun dismissOffer() {
        _offer.value = null
    }

    private fun act(block: suspend (java.time.LocalDate) -> Unit) {
        val day = _state.value?.day ?: return
        viewModelScope.launch {
            block(day)
            reload()
        }
    }
}

private const val SHOWN_TASKS = 5

@Composable
fun ShutdownBanner(
    state: ShutdownState,
    onTomorrow: (DailyTaskEntity) -> Unit,
    onSomeday: (DailyTaskEntity) -> Unit,
    onLetGo: (DailyTaskEntity) -> Unit,
    onLogWin: (String) -> Unit,
    onOpenInbox: () -> Unit,
    onCloseDay: () -> Unit,
    modifier: Modifier = Modifier
) {
    var win by rememberSaveable(state.day) { mutableStateOf("") }
    var logged by rememberSaveable(state.day) { mutableStateOf(false) }
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.WbTwilight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Wrap up your workday",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            state.tasks.take(SHOWN_TASKS).forEach { task ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (task.priority > 1) "‼️ ${task.label}" else task.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onTomorrow(task) }) {
                        Icon(Icons.Rounded.NextPlan, contentDescription = "Tomorrow")
                    }
                    IconButton(onClick = { onSomeday(task) }) {
                        Icon(Icons.Rounded.Inventory2, contentDescription = "Someday")
                    }
                    IconButton(onClick = { onLetGo(task) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Let it go")
                    }
                }
            }
            val hidden = state.tasks.size - SHOWN_TASKS
            if (hidden > 0) {
                Text(
                    text = "and $hidden more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            if (state.canLogWin) {
                if (logged) {
                    Text(
                        text = "Today's win is in the mission log.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        OutlinedTextField(
                            value = win,
                            onValueChange = { win = it },
                            label = { Text("Today's win") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                onLogWin(win)
                                logged = true
                            },
                            enabled = win.isNotBlank()
                        ) { Text("Log") }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.inboxCount > 0) {
                    TextButton(onClick = onOpenInbox, modifier = Modifier.weight(1f)) {
                        Text(if (state.inboxCount == 1) "1 note in the inbox" else "${state.inboxCount} notes in the inbox")
                    }
                }
                Button(onClick = onCloseDay) { Text("Close the day") }
            }
        }
    }
}

/** The evening mode, offered once the day is closed; never switched on its own. */
@Composable
fun ShutdownOfferCard(offer: ModeOffer, onSwitch: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Day closed. Switch to ${offer.label}?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("Not now") }
            TextButton(onClick = onSwitch) { Text("Switch") }
        }
    }
}
