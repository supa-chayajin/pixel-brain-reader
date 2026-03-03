package cloud.wafflecommons.pixelbrainreader.ui.lifeos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
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
    val groupedHabits: Map<String, List<HabitWithStats>> = emptyMap(),
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
    private val jGitProvider: JGitProvider
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
            val dayMap = mapOf(
                java.time.DayOfWeek.MONDAY to "MON",
                java.time.DayOfWeek.TUESDAY to "TUE",
                java.time.DayOfWeek.WEDNESDAY to "WED",
                java.time.DayOfWeek.THURSDAY to "THU",
                java.time.DayOfWeek.FRIDAY to "FRI",
                java.time.DayOfWeek.SATURDAY to "SAT",
                java.time.DayOfWeek.SUNDAY to "SUN"
            )
            val todayKey = dayMap[date.dayOfWeek] ?: "MON"
            
            val habitsWithStats = configs.map { habit ->
                 val habitLogs = logsMap[habit.id] ?: emptyList()
                 
                 val cleanFreq = habit.frequency.map { it.trim().uppercase() }
                 val isScheduledToday = cleanFreq.isEmpty() || cleanFreq.contains(todayKey)
                 
                 val todayLog = habitLogs.find { it.date == date.toString() }
                 val isCompletedToday = isHabitComplete(habit, todayLog)
                 val currentValue = todayLog?.value ?: 0.0
                 
                 val history = (0..6).map { i ->
                    val checkDate = date.minusDays(i.toLong()).toString()
                    val log = habitLogs.find { it.date == checkDate }
                    isHabitComplete(habit, log)
                 }.reversed()
                 
                 // Streak
                 var streak = 0
                 var checkDate = if (isCompletedToday) date else date.minusDays(1)
                 for (i in 0..365) {
                      val d = checkDate.toString()
                      val log = habitLogs.find { it.date == d }
                      if (isHabitComplete(habit, log)) {
                          streak++
                          checkDate = checkDate.minusDays(1)
                      } else {
                          break
                      }
                 }
                 
                 HabitWithStats(habit, isCompletedToday, currentValue, streak, history, isScheduledToday)
            }
            
            val todayHabitsList = habitsWithStats.filter { it.isScheduledToday }
            val groupedHabits = todayHabitsList.groupBy { habitStat ->
                val parser = cloud.wafflecommons.pixelbrainreader.data.gamification.AttributeParser
                val attr = parser.parse(habitStat.config.description)
                if (attr != null) {
                     "${attr.name} Training"
                } else {
                     "General"
                }
            }
            
            LifeOSUiState(
                 habits = configs,
                 habitsWithStats = habitsWithStats,
                 groupedHabits = groupedHabits,
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

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

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
        if (_isSyncing.value) return
        _isSyncing.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // 1. Health Connect Sync
                automateHabitsUseCase(LocalDate.now())
                
                // 2. Git Fetch & Merge (Pull)
                jGitProvider.pull()
            } catch (e: Exception) {
                android.util.Log.e("LifeOSViewModel", "Emergency sync failed", e)
            } finally {
                _isSyncing.value = false
            }
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
