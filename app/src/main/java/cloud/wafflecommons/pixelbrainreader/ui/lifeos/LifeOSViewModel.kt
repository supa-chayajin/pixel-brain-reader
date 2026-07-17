package cloud.wafflecommons.pixelbrainreader.ui.lifeos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.domain.lifeos.HabitScheduler
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    val logs: Map<String, List<HabitLogEntry>> = emptyMap(),
    val scopedTasks: List<DailyTaskEntity> = emptyList(),
    val selectedDate: LocalDate = LocalDate.now(),
    val isLoading: Boolean = false,
    val gamificationState: cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationState = cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationState()
)

@HiltViewModel
class LifeOSViewModel @Inject constructor(
    private val habitRepository: HabitRepository,
    private val taskRepository: TaskRepository,
    private val gamificationRepository: cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository,
    private val grantXpUseCase: cloud.wafflecommons.pixelbrainreader.domain.gamification.GrantXpUseCase,
    private val automateHabitsUseCase: cloud.wafflecommons.pixelbrainreader.domain.gamification.AutomateHabitsUseCase,
    private val jGitProvider: JGitProvider,
    private val syncOrchestrator: cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator,
    private val soundEffectManager: cloud.wafflecommons.pixelbrainreader.data.utils.SoundEffectManager
) : ViewModel() {

    private val selectedDateFlow = MutableStateFlow(LocalDate.now())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<LifeOSUiState> = selectedDateFlow.flatMapLatest { date ->
        combine(
            habitRepository.getHabitConfigsFlow(),
            habitRepository.getLogsForYearFlow(date.year),
            gamificationRepository.gamificationState,
            taskRepository.getTasksFlow(date)
        ) { configs, logsMap, gamificationState, scopedTasks ->
            val habitsWithStats = configs.map { habit ->
                 val habitLogs = logsMap[habit.id] ?: emptyList()
                 
                 val todayLog = habitLogs.find { it.date == date.toString() }
                 val isCompletedToday = isHabitComplete(habit, todayLog)
                 val currentValue = todayLog?.value ?: 0.0

                 // Most recent completion date — only INTERVAL scheduling consults it.
                 val lastCompletedDate = habitLogs
                     .filter { isHabitComplete(habit, it) }
                     .maxByOrNull { it.date }
                     ?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

                 val isScheduledToday = HabitScheduler.isScheduledOn(habit, date, lastCompletedDate)

                 val history = (0..6).map { i ->
                    val checkDate = date.minusDays(i.toLong()).toString()
                    val log = habitLogs.find { it.date == checkDate }
                    isHabitComplete(habit, log)
                 }.reversed()

                 // Streak. Interval habits complete on irregular days, so their streak is the
                 // total number of completions; weekly / bi-weekly walk back day-by-day,
                 // skipping unscheduled days via the shared scheduler.
                 val streak = if (habit.scheduleMode.equals("INTERVAL", ignoreCase = true)) {
                     habitLogs.count { isHabitComplete(habit, it) }
                 } else {
                     var s = 0
                     var checkDate = if (isCompletedToday) date else date.minusDays(1)
                     for (i in 0..365) {
                         val scheduled = HabitScheduler.isScheduledOn(habit, checkDate, null)
                         val log = habitLogs.find { it.date == checkDate.toString() }
                         if (isHabitComplete(habit, log)) {
                             s++
                             checkDate = checkDate.minusDays(1)
                         } else if (!scheduled) {
                             checkDate = checkDate.minusDays(1)
                         } else {
                             break
                         }
                     }
                     s
                 }

                 HabitWithStats(habit, isCompletedToday, currentValue, streak, history, isScheduledToday)
            }
            
            // Same category key for both views so "Today" and "All" group identically.
            val categoryOf = { habitStat: HabitWithStats ->
                val attr = cloud.wafflecommons.pixelbrainreader.data.gamification.AttributeParser
                    .parse(habitStat.config.description)
                if (attr != null) "${attr.name} Training" else "General"
            }
            val todayHabitsList = habitsWithStats.filter { it.isScheduledToday }
            val groupedHabits = todayHabitsList.groupBy(categoryOf)
            val allGroupedHabits = habitsWithStats.groupBy(categoryOf)

            LifeOSUiState(
                 habits = configs,
                 habitsWithStats = habitsWithStats,
                 groupedHabits = groupedHabits,
                 allGroupedHabits = allGroupedHabits,
                 scopedTasks = scopedTasks,
                 isLoading = false,
                 selectedDate = date,
                 gamificationState = gamificationState ?: cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationState()
            )
        }
    }.stateIn(
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

    // One-time events channel for XP toasts (omitted for brevity, handled via log/simple toast logic in UI?)
    // Let's add a simple XpGainEvent flow
    private val _xpEvents = MutableSharedFlow<cloud.wafflecommons.pixelbrainreader.data.gamification.XpGainEntry>()
    val xpEvents = _xpEvents.asSharedFlow()

    val isSyncing: StateFlow<cloud.wafflecommons.pixelbrainreader.data.sync.SyncState> = syncOrchestrator.syncState

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            habitRepository.initialize()
            try {
                automateHabitsUseCase(LocalDate.now())
            } catch (e: Exception) {
                android.util.Log.e("LifeOSViewModel", "Failed to run habit automation", e)
            }
            gamificationRepository.loadState()
        }
    }

    fun forceSyncEverything() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            syncOrchestrator.executeFullSyncCycle()
        }
    }

    fun loadData(date: LocalDate) {
         selectedDateFlow.value = date
    }

    fun toggleHabit(habitId: String) {
        viewModelScope.launch {
            val date = uiState.value.selectedDate
            val stats = uiState.value.habitsWithStats.find { it.config.id == habitId } ?: return@launch
            
            val isCompleting = !stats.isCompletedToday
            val newEntry = if (!isCompleting) {
                 HabitLogEntry(habitId, date.toString(), 0.0, HabitStatus.SKIPPED)
            } else {
                 HabitLogEntry(habitId, date.toString(), 1.0, HabitStatus.COMPLETED)
            }
            habitRepository.logHabit(date, newEntry)
            
            // Grant XP only on Completion (not unchecking)
            // And only if it's TODAY (gamification should probably be real-time/strict?)
            // We allow backfilling but maybe reduce XP? For now, standard XP for any date logic.
            if (isCompleting) {
                soundEffectManager.success()
                grantXpUseCase.execute(
                    sourceId = habitId,
                    actionType = cloud.wafflecommons.pixelbrainreader.domain.gamification.XpActionType.HABIT_DONE
                )
                // Emit event?
                // Ideally GrantXpUseCase returns the diff or we observe state.
                // We'll rely on global state update for UI change.
            }
        }
    }

    fun updateHabitValue(habitId: String, newValue: Double) {
        viewModelScope.launch {
            val date = uiState.value.selectedDate
            val habitConfig = uiState.value.habits.find { it.id == habitId } ?: return@launch
            
            // Check if becoming complete
            val wasComplete = isHabitComplete(habitConfig, uiState.value.logs[habitId]?.find { it.date == date.toString() })
            
            val status = when {
                newValue >= habitConfig.targetValue -> HabitStatus.COMPLETED
                newValue > 0 -> HabitStatus.PARTIAL
                else -> HabitStatus.SKIPPED
            }
            
            val newEntry = HabitLogEntry(habitId, date.toString(), newValue, status)
            habitRepository.logHabit(date, newEntry)
            
            // Logic: Award XP if crossing threshold? Or proportional?
            // Simple: Award on 'COMPLETED' status transition?
            // Or just allow repeated calls for now (Gameable, but simpler).
            // Let's stick to "If status becomes COMPLETED"
            val isNowComplete = status == HabitStatus.COMPLETED
            if (isNowComplete && !wasComplete) {
                 soundEffectManager.success()
                 grantXpUseCase.execute(
                    sourceId = habitId,
                    actionType = cloud.wafflecommons.pixelbrainreader.domain.gamification.XpActionType.HABIT_DONE,
                    value = newValue
                )
            }
        }
    }

    fun toggleTask(task: DailyTaskEntity) {
        viewModelScope.launch {
            val isDone = !task.isDone
            taskRepository.toggleTask(task.id, isDone)
            
            if (isDone) {
                 soundEffectManager.success()
                 grantXpUseCase.execute(
                    sourceId = task.id,
                    actionType = cloud.wafflecommons.pixelbrainreader.domain.gamification.XpActionType.TASK_DONE
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

    private fun isHabitComplete(habit: HabitConfig, log: HabitLogEntry?): Boolean {
        if (log == null) return false
        return if (habit.type == cloud.wafflecommons.pixelbrainreader.data.model.HabitType.MEASURABLE) {
            log.value >= habit.targetValue
        } else {
            log.status == HabitStatus.COMPLETED
        }
    }
}
