package cloud.wafflecommons.pixelbrainreader.ui.modes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.CustodyException
import cloud.wafflecommons.pixelbrainreader.data.modes.KeyDate
import cloud.wafflecommons.pixelbrainreader.data.modes.LeavePeriod
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeStyle
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeResolver
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesConfig
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectRepository
import cloud.wafflecommons.pixelbrainreader.data.work.projectDisplayName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject

/** A key date with the number of days left (0 = today). */
data class KeyDateCountdown(val keyDate: KeyDate, val daysLeft: Long)

/** A project the Perso checklist offers: its root and the name the Projects tab shows. */
data class ProjectChoice(val root: String, val name: String)

@HiltViewModel
class ModesViewModel @Inject constructor(
    private val modesRepository: ModesRepository,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository,
    private val projectRepository: ProjectRepository,
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

    private val _projects = MutableStateFlow<List<ProjectChoice>>(emptyList())
    /** Every project, whatever the mode; the screen leaves out the ones in work folders. */
    val projects: StateFlow<List<ProjectChoice>> = _projects.asStateFlow()

    fun loadProjects() {
        viewModelScope.launch {
            _projects.value = try {
                projectRepository.everyProject().map { ProjectChoice(it.root, projectDisplayName(it.title)) }.sortedBy { it.name.lowercase() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
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

    fun setWithChildMode(mode: ContextMode) = update { it.copy(withChildMode = mode) }

    fun setCustodyEnabled(enabled: Boolean) = update { it.copy(custody = it.custody.copy(enabled = enabled)) }

    fun setChildName(name: String) = update { it.copy(custody = it.custody.copy(name = name.trim().take(ModeAppearance.MAX_LABEL))) }

    /** This week the child [arrives] on the handover day, or leaves on it: sets the rhythm. */
    fun setCustodyThisWeek(arrives: Boolean) = update { it.copy(custody = it.custody.withThisWeek(LocalDate.now(), arrives)) }

    fun setHandoverDay(day: DayOfWeek) = update { it.copy(custody = it.custody.withHandoverDay(day)) }

    fun addCustodyException(exception: CustodyException) = update { cfg ->
        cfg.copy(custody = cfg.custody.copy(exceptions = (cfg.custody.exceptions + exception).sortedBy { it.start }))
    }

    fun removeCustodyException(exception: CustodyException) = update { cfg ->
        cfg.copy(custody = cfg.custody.copy(exceptions = cfg.custody.exceptions - exception))
    }

    fun togglePersonalProject(root: String) = update { cfg ->
        val roots = if (root in cfg.personalProjects) cfg.personalProjects - root else cfg.personalProjects + root
        cfg.copy(personalProjects = roots.sorted())
    }

    /** A blank [ModeStyle] puts the default name and icon back. */
    fun setModeStyle(mode: ContextMode, style: ModeStyle) = update { cfg ->
        val styles = if (style == ModeStyle()) cfg.modeStyles - mode else cfg.modeStyles + (mode to style)
        cfg.copy(modeStyles = styles)
    }

    fun setToolkitName(name: String) = update { cfg ->
        val trimmed = name.trim().take(ModeAppearance.MAX_LABEL)
        cfg.copy(toolkitName = if (trimmed == ModeAppearance.DEFAULT_TOOLKIT) "" else trimmed)
    }

    fun addLeave(leave: LeavePeriod) = update { cfg -> cfg.copy(leavePeriods = (cfg.leavePeriods + leave).sortedBy { it.start }) }

    fun removeLeave(leave: LeavePeriod) = update { cfg -> cfg.copy(leavePeriods = cfg.leavePeriods - leave) }

    fun addKeyDate(keyDate: KeyDate) = update { cfg -> cfg.copy(keyDates = (cfg.keyDates + keyDate).sortedBy { it.date }) }

    fun removeKeyDate(keyDate: KeyDate) = update { cfg -> cfg.copy(keyDates = cfg.keyDates - keyDate) }

    private fun update(transform: (ModesConfig) -> ModesConfig) {
        viewModelScope.launch { modesRepository.updateConfig(transform) }
    }
}
