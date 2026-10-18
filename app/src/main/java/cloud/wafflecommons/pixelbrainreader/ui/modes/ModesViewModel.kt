package cloud.wafflecommons.pixelbrainreader.ui.modes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.KeyDate
import cloud.wafflecommons.pixelbrainreader.data.modes.LeavePeriod
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeResolver
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesConfig
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A key date with the number of days left (0 = today). */
data class KeyDateCountdown(val keyDate: KeyDate, val daysLeft: Long)

@HiltViewModel
class ModesViewModel @Inject constructor(
    private val modesRepository: ModesRepository,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository,
    appClock: AppClock
) : ViewModel() {

    val visibility: StateFlow<Visibility> = modesRepository.visibility
    val config: StateFlow<ModesConfig> = modesRepository.config

    val countdowns: StateFlow<List<KeyDateCountdown>> =
        combine(modesRepository.config, modesRepository.visibility, appClock.today) { cfg, vis, today ->
            ModeResolver.upcomingKeyDates(cfg, today, vis).map { (key, days) -> KeyDateCountdown(key, days) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    /** Folders that can be marked as work: top-level folders and their direct children. */
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    fun loadFolders() {
        viewModelScope.launch {
            _folders.value = vaultDiscoveryRepository.getAllFolderPaths()
                .filter { path -> path.count { it == '/' } <= 1 && !path.startsWith(".") }
                .sorted()
        }
    }

    fun setMode(mode: ContextMode) {
        viewModelScope.launch { modesRepository.setManualMode(mode) }
    }

    fun followSchedule() {
        viewModelScope.launch { modesRepository.clearManualMode() }
    }

    fun setScreenShareSafe(enabled: Boolean) {
        viewModelScope.launch { modesRepository.setScreenShareSafe(enabled) }
    }

    fun toggleWorkFolder(folder: String) = update { cfg ->
        val folders = if (folder in cfg.workFolders) cfg.workFolders - folder else cfg.workFolders + folder
        cfg.copy(workFolders = folders.sorted())
    }

    fun setScheduleEnabled(enabled: Boolean) = update { it.copy(scheduleEnabled = enabled) }

    fun toggleWorkDay(isoDay: Int) = update { cfg ->
        val days = if (isoDay in cfg.workDays) cfg.workDays - isoDay else cfg.workDays + isoDay
        cfg.copy(workDays = days.sorted())
    }

    fun setWorkHours(start: String, end: String) = update { it.copy(workStart = start, workEnd = end) }

    fun setOutsideHoursMode(mode: ContextMode) = update { it.copy(outsideHoursMode = mode) }

    fun addLeave(leave: LeavePeriod) = update { cfg -> cfg.copy(leavePeriods = (cfg.leavePeriods + leave).sortedBy { it.start }) }

    fun removeLeave(leave: LeavePeriod) = update { cfg -> cfg.copy(leavePeriods = cfg.leavePeriods - leave) }

    fun addKeyDate(keyDate: KeyDate) = update { cfg -> cfg.copy(keyDates = (cfg.keyDates + keyDate).sortedBy { it.date }) }

    fun removeKeyDate(keyDate: KeyDate) = update { cfg -> cfg.copy(keyDates = cfg.keyDates - keyDate) }

    private fun update(transform: (ModesConfig) -> ModesConfig) {
        viewModelScope.launch { modesRepository.updateConfig(transform) }
    }
}
