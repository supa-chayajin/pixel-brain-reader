package cloud.wafflecommons.pixelbrainreader.ui.lifeos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.gamification.AttributeParser
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationState
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncState
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.utils.SoundEffectManager
import cloud.wafflecommons.pixelbrainreader.domain.gamification.GrantXpUseCase
import cloud.wafflecommons.pixelbrainreader.domain.gamification.XpActionType
import cloud.wafflecommons.pixelbrainreader.domain.lifeos.HabitStatsCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class HabitWithStats(
    val config: HabitConfig,
    val isCompletedToday: Boolean,
    val currentValue: Double, // New field for Measurable
    val currentStreak: Int,
    val history: List<Boolean>, // Last 7 days, boolean status
    val isScheduledToday: Boolean // [NEW] For visual dimming
)

data class LifeOSUiState(
    val habits: List<HabitConfig> = emptyList(),
    val habitsWithStats: List<HabitWithStats> = emptyList(),
    val groupedHabits: Map<String, List<HabitWithStats>> = emptyMap(),      // today's scheduled habits
    val allGroupedHabits: Map<String, List<HabitWithStats>> = emptyMap(),   // every habit (for the "All" view)
    val scopedTasks: List<DailyTaskEntity> = emptyList(),
    val selectedDate: LocalDate = LocalDate.now(),
    val isLoading: Boolean = false,
    val gamificationState: GamificationState = GamificationState()
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class LifeOSViewModel @Inject constructor(
    private val habitRepository: HabitRepository,
    private val taskRepository: TaskRepository,
    gamificationRepository: GamificationRepository,
    private val grantXpUseCase: GrantXpUseCase,
    private val syncOrchestrator: SyncOrchestrator,
    private val soundEffectManager: SoundEffectManager,
    appClock: AppClock
) : ViewModel() {

    /**
     * Follows the real current day ([AppClock.today]) — the date used to be captured once at
     * ViewModel creation, so "today" went stale after midnight — and computes the per-habit stats
     * off the main thread with the indexed [HabitStatsCalculator].
     */
    val uiState: StateFlow<LifeOSUiState> = appClock.today
        .flatMapLatest { date ->
            combine(
                habitRepository.getHabitConfigsFlow(),
                // Two years of logs, so streaks and interval scheduling see across January 1st.
                habitRepository.getLogsBetweenFlow(LocalDate.of(date.year - 1, 1, 1), LocalDate.of(date.year, 12, 31)),
                gamificationRepository.gamificationState,
                taskRepository.getTasksFlow(date)
            ) { configs, logsByHabit, gamificationState, scopedTasks ->
                buildState(date, configs, logsByHabit, gamificationState, scopedTasks)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LifeOSUiState(isLoading = true)
        )

    val todayHabits: StateFlow<List<HabitWithStats>> = uiState
        .map { state -> state.habitsWithStats.filter { it.isScheduledToday } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val isSyncing: StateFlow<SyncState> = syncOrchestrator.syncState

    /**
     * Completions rewarded from this screen ("habitId@date", "task:id"). The UI state can lag a tap
     * behind, so a fast double tap used to see "not completed" twice and grant the XP twice.
     * Main thread only.
     */
    private val rewardedCompletions = mutableSetOf<String>()

    init {
        // Cheap: only imports the vault config when Room has no habits yet (e.g. right after login).
        // Health-driven habits are refreshed app-wide by ForegroundRefresher and every sync.
        viewModelScope.launch { habitRepository.initialize() }
    }

    /** Pull-to-refresh: a full, user-initiated sync (bypasses the automatic cooldown). */
    fun forceSyncEverything() {
        syncOrchestrator.requestSync(force = true)
    }

    fun toggleHabit(habitId: String) {
        val state = uiState.value
        val stats = state.habitsWithStats.find { it.config.id == habitId } ?: return
        // Automatic (Health-Connect) habits are read-only — their state is reconciled from
        // health data, never a manual tap. Ignore any toggle that slips past the UI guard.
        if (!stats.config.autoSource.isNullOrBlank()) return

        val date = state.selectedDate
        val isCompleting = !stats.isCompletedToday
        val rewardKey = "$habitId@$date"
        val grantXp = isCompleting && rewardedCompletions.add(rewardKey)
        if (!isCompleting) rewardedCompletions.remove(rewardKey)

        viewModelScope.launch {
            val entry = if (isCompleting) {
                HabitLogEntry(habitId, date.toString(), 1.0, HabitStatus.COMPLETED)
            } else {
                HabitLogEntry(habitId, date.toString(), 0.0, HabitStatus.SKIPPED)
            }
            habitRepository.logHabit(date, entry)

            if (isCompleting) soundEffectManager.success()
            if (grantXp) {
                grantXpUseCase.execute(sourceId = habitId, actionType = XpActionType.HABIT_DONE)
            }
        }
    }

    fun updateHabitValue(habitId: String, newValue: Double) {
        val state = uiState.value
        val stats = state.habitsWithStats.find { it.config.id == habitId } ?: return
        val habitConfig = stats.config
        // Automatic (Health-Connect) habits are read-only — reject manual value edits.
        if (!habitConfig.autoSource.isNullOrBlank()) return

        val date = state.selectedDate
        val status = when {
            newValue >= habitConfig.targetValue -> HabitStatus.COMPLETED
            newValue > 0 -> HabitStatus.PARTIAL
            else -> HabitStatus.SKIPPED
        }
        // Reward only the transition to COMPLETED. The completion state used to be read from a map
        // that was never populated, so EVERY update at or above the target granted XP again.
        val rewardKey = "$habitId@$date"
        val isNowComplete = status == HabitStatus.COMPLETED
        val grantXp = isNowComplete && !stats.isCompletedToday && rewardedCompletions.add(rewardKey)
        if (!isNowComplete) rewardedCompletions.remove(rewardKey)

        viewModelScope.launch {
            habitRepository.logHabit(date, HabitLogEntry(habitId, date.toString(), newValue, status))
            if (grantXp) {
                soundEffectManager.success()
                grantXpUseCase.execute(
                    sourceId = habitId,
                    actionType = XpActionType.HABIT_DONE,
                    value = newValue
                )
            }
        }
    }

    fun toggleTask(task: DailyTaskEntity) {
        val isDone = !task.isDone
        val rewardKey = "task:${task.id}"
        val grantXp = isDone && rewardedCompletions.add(rewardKey)
        if (!isDone) rewardedCompletions.remove(rewardKey)

        viewModelScope.launch {
            taskRepository.toggleTask(task.id, isDone)
            if (grantXp) {
                soundEffectManager.success()
                grantXpUseCase.execute(
                    sourceId = task.id,
                    actionType = XpActionType.TASK_DONE
                )
            }
        }
    }

    fun addDebugHabit() {
        viewModelScope.launch {
            val randomId = java.util.UUID.randomUUID().toString()
            val newHabit = HabitConfig(
                id = randomId,
                title = "New Habit ${randomId.take(4)}",
                description = "Created via Debug FAB (+VIG)",
                frequency = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
            )
            habitRepository.addHabitConfig(newHabit)
        }
    }

    private fun buildState(
        date: LocalDate,
        configs: List<HabitConfig>,
        logsByHabit: Map<String, List<HabitLogEntry>>,
        gamificationState: GamificationState,
        scopedTasks: List<DailyTaskEntity>
    ): LifeOSUiState {
        val habitsWithStats = configs.map { habit ->
            val stats = HabitStatsCalculator.compute(habit, logsByHabit[habit.id].orEmpty(), date)
            HabitWithStats(
                config = habit,
                isCompletedToday = stats.isCompletedToday,
                currentValue = stats.currentValue,
                currentStreak = stats.currentStreak,
                history = stats.history,
                isScheduledToday = stats.isScheduledToday
            )
        }

        // Same category key for both views so "Today" and "All" group identically.
        val categoryById = configs.associate { habit ->
            habit.id to (AttributeParser.parse(habit.description)?.let { "${it.name} Training" } ?: "General")
        }
        val categoryOf = { habitStat: HabitWithStats -> categoryById.getValue(habitStat.config.id) }

        return LifeOSUiState(
            habits = configs,
            habitsWithStats = habitsWithStats,
            groupedHabits = habitsWithStats.filter { it.isScheduledToday }.groupBy(categoryOf),
            allGroupedHabits = habitsWithStats.groupBy(categoryOf),
            scopedTasks = scopedTasks,
            isLoading = false,
            selectedDate = date,
            gamificationState = gamificationState
        )
    }
}
