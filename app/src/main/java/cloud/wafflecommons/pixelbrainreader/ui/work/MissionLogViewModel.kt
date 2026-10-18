package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.work.LogEntry
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogRepository
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

/** A month of the log, newest first; [error] when it couldn't be read (an encrypted log without the password). */
data class LogMonthState(
    val loading: Boolean = true,
    val entries: List<LogEntry> = emptyList(),
    val error: String? = null
) {
    val offTicketMinutes: Int get() = entries.filter { it.kind == LogKind.OFF_TICKET }.sumOf { it.minutes ?: 0 }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MissionLogViewModel @Inject constructor(
    private val repository: MissionLogRepository,
    modesRepository: ModesRepository
) : ViewModel() {

    val settings: StateFlow<MissionLogSettings> = repository.settings
    val visibility: StateFlow<Visibility> = modesRepository.visibility

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    private val _filter = MutableStateFlow<LogKind?>(null)
    val filter: StateFlow<LogKind?> = _filter.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val state: StateFlow<LogMonthState> = combine(_month, repository.changes, repository.settings) { month, _, _ -> month }
        .mapLatest { month ->
            try {
                LogMonthState(loading = false, entries = repository.entries(month).sortedByDescending { it.at })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogMonthState(loading = false, error = e.message ?: "The log couldn't be read.")
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogMonthState())

    init {
        viewModelScope.launch { repository.reloadSettings() }
    }

    fun previousMonth() = _month.update { it.minusMonths(1) }

    fun nextMonth() = _month.update { it.plusMonths(1) }

    fun setFilter(kind: LogKind?) {
        _filter.value = kind
    }

    /** Adds [draft], or replaces [original] by it. */
    fun save(original: LogEntry?, draft: LogEntry) = run("Couldn't save the entry") {
        if (original == null) repository.add(draft) else repository.update(original, draft)
        _month.value = YearMonth.from(draft.at)
    }

    fun delete(entry: LogEntry) = run("Couldn't delete the entry") { repository.delete(entry) }

    fun setFolder(folder: String) = run("Couldn't save the settings") {
        repository.updateSettings { it.copy(folder = folder.trim().trim('/')) }
    }

    fun setEncrypted(encrypted: Boolean) = run("Couldn't save the settings") {
        repository.updateSettings { it.copy(encrypted = encrypted) }
    }

    fun addRole(role: String) = run("Couldn't save the settings") {
        repository.updateSettings { s -> if (s.roles.any { it.equals(role, true) }) s else s.copy(roles = s.roles + role.trim()) }
    }

    fun removeRole(role: String) = run("Couldn't save the settings") {
        repository.updateSettings { it.copy(roles = it.roles - role) }
    }

    private fun run(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("$failure: ${e.message}")
            }
        }
    }

    private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
        value = transform(value)
    }
}
