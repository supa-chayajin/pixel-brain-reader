package cloud.wafflecommons.pixelbrainreader.ui.daily

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.GratitudeEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyBriefingRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyMoodData
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.NewsRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.ScratchRepository
import cloud.wafflecommons.pixelbrainreader.data.usecase.SyncHealthDataUseCase
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class DailyNoteState(
    val date: LocalDate = LocalDate.now(),
    val moodData: DailyMoodData? = null,
    val healthMetrics: cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics? = null,
    
    // Core Dashboard Content (Room)
    val mantra: String = "Stay safe my friend, and don't you dare go hollow!",
    val ideasContent: String = "",
    val notesContent: String = "",
    
    val noteIntro: String = "", // Kept for compatibility
    val noteOutro: String = "", // Kept for compatibility
    val metadata: Map<String, String> = emptyMap(),
    val weatherData: WeatherData? = null,
    
    // Room-Backed Live Data
    val timelineEvents: List<TimelineEntryEntity> = emptyList(),
    val dailyTasks: List<DailyTaskEntity> = emptyList(),
    
    val wellnessStats: List<Pair<String, String>> = emptyList(),
    val weather: cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData? = null,
    val briefing: cloud.wafflecommons.pixelbrainreader.data.model.BriefingData? = null,
    val isLoading: Boolean = true,
    val briefingState: MorningBriefingUiState = MorningBriefingUiState(),
    val topDailyTags: List<String> = emptyList(),
    val scratchNotes: List<cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity> = emptyList(),
    val userMessage: String? = null,
    val moodTrend: List<DailyMoodPoint> = emptyList()
)

data class DailyMoodPoint(
    val date: LocalDate,
    val score: Float,
    val emoji: String,
    val avgBpm: Int = 0
)

data class MorningBriefingUiState(
    val weather: WeatherData? = null,
    val moodTrend: List<DailyMoodPoint> = emptyList(),
    val topTags: List<String> = emptyList(),
    val quote: String = "",
    val quoteAuthor: String = "",
    val oracleInsight: String? = null,
    val isExpanded: Boolean = true,
    val isLoading: Boolean = true
)

@HiltViewModel
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DailyNoteViewModel @Inject constructor(
    private val moodRepository: MoodRepository,
    private val newsRepository: NewsRepository,
    private val fileRepository: FileRepository,
    private val weatherRepository: WeatherRepository,
    private val secretManager: SecretManager,
    private val dashboardRepository: DailyDashboardRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val scratchRepository: ScratchRepository,
    private val userPrefs: cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository,
    private val jGitProvider: cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider,
    private val gamificationRepository: cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository,
    private val syncHealthDataUseCase: SyncHealthDataUseCase,
    private val dailyNoteRepository: cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository,
    private val taskRepository: cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository,
    private val syncOrchestrator: cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator,
    private val googleCalendarRepository: cloud.wafflecommons.pixelbrainreader.data.repository.GoogleCalendarRepository,
    private val healthMetricsRepository: cloud.wafflecommons.pixelbrainreader.data.repository.HealthMetricsRepository
) : ViewModel() {

    /**
     * Tracks `/event` command lines already dispatched, keyed by date, to keep
     * the debouncer's auto-scan idempotent: re-saving a note that still
     * contains a previously-processed command must not create a duplicate.
     * Entries for inactive dates are dropped when [loadDailyNote] swaps date.
     */
    private val processedEventCommands = mutableMapOf<LocalDate, MutableSet<String>>()

    // [NEW] Reactive Date Selection
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    private val _isLoading = MutableStateFlow(false)
    private val _weatherRefreshTrigger = MutableStateFlow(0)

    fun refreshWeather() {
        _weatherRefreshTrigger.value++
    }

    // Global Sync State for PullToRefresh
    val isSyncing: StateFlow<cloud.wafflecommons.pixelbrainreader.data.sync.SyncState> = syncOrchestrator.syncState

    /**
     * Triggers a full Git→Health→Git sync cycle.
     * Called by PullToRefresh on DailyNoteScreen.
     */
    fun triggerSync() {
        viewModelScope.launch(Dispatchers.IO) {
            syncOrchestrator.executeFullSyncCycle()
            // Reload the current day's data after sync
            loadDailyNote(_selectedDate.value)
        }
    }

    // RFC-009: Gratitude Express Flow
    val gratitudes: StateFlow<List<GratitudeEntity>> = _selectedDate.flatMapLatest { date ->
        dailyNoteRepository.getGratitudesStream(date)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val gamificationState = gamificationRepository.gamificationState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _currentDayMood: StateFlow<DailyMoodData?> = _selectedDate
        .flatMapLatest { date ->
           moodRepository.getDailyMood(date)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _healthMetrics = combine(_selectedDate, syncOrchestrator.syncState) { date, _ -> date }
        .flatMapLatest { date ->
            healthMetricsRepository.getMetricsFlow(date)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _moodTrend = combine(_selectedDate, syncOrchestrator.syncState) { date, _ -> date }
        .flatMapLatest { date ->
            combine(
                moodRepository.getMoodFlow(),
                healthMetricsRepository.getMetricsHistoryFlow(date, 7)
            ) { moods, healthMetricsList ->
                val recentMoods = mutableListOf<DailyMoodPoint>()
                (6 downTo 0).forEach { offset ->
                    val d = date.minusDays(offset.toLong())
                    val dayMoods = moods.filter { it.date == d.toString() }
                    
                    val dhm = healthMetricsList.find { it.date == d.toString() }
                    val avgBpm = dhm?.averageHeartRate ?: 0
                    
                    if (dayMoods.isNotEmpty()) {
                        val avg = dayMoods.map { it.score }.average()
                        val emoji = when {
                            avg < 1.8 -> "😫"
                            avg.isNaN() -> "😐"
                            avg < 2.6 -> "😞"
                            avg < 3.4 -> "😐"
                            avg < 4.2 -> "🙂"
                            else -> "🤩"
                        }
                        recentMoods.add(DailyMoodPoint(d, avg.toFloat(), emoji, avgBpm))
                    } else {
                        recentMoods.add(DailyMoodPoint(d, 0f, "∅", avgBpm))
                    }
                }
                recentMoods
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Sentinel emitted when weather can't be resolved (location denied, no fix,
    // or the fetch failed). temperature is intentionally NOT "--°C" so the card
    // renders an "unavailable" state instead of spinning forever; code = -2 flags it.
    private val weatherUnavailable = cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData(
        emoji = "⚠️",
        temperature = "—",
        location = "Location or network unavailable",
        description = "Unavailable",
        code = -2
    )

    // Weather Flow (Reactive).
    // The previous version caught Exception (which includes CancellationException)
    // and tried emit(null). When flatMapLatest cancelled mid-fetch, the catch
    // swallowed the CE, then emit(null) re-threw CE because the coroutine was
    // already dead — so the StateFlow never received a value and the UI stuck
    // on the Loading placeholder. Now we split CE vs real failure and let
    // cancellation propagate cleanly.
    private val _weather = combine(_selectedDate, _weatherRefreshTrigger) { date, trigger ->
        Log.d("WeatherFlow", "Combine: date=$date trigger=$trigger")
        date
    }
        .flatMapLatest { date ->
            flow {
                Log.d("WeatherFlow", "Fetching weather for $date")
                val result: cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData? = try {
                    if (date == LocalDate.now()) {
                        weatherRepository.getCurrentWeatherAndLocation()
                    } else {
                        weatherRepository.getHistoricalWeather(date)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // flatMapLatest replaced us; let the framework see it.
                    Log.d("WeatherFlow", "Fetch cancelled for $date")
                    throw e
                } catch (e: Exception) {
                    Log.e("WeatherFlow", "Fetch failed for $date", e)
                    null
                }
                Log.d("WeatherFlow", "Emitting $result for $date")
                // Emit the unavailable sentinel instead of null so the UI leaves the
                // loading spinner and shows an actionable state.
                emit(result ?: weatherUnavailable)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData(
            emoji = "⌛",
            temperature = "--°C",
            location = "Loading...",
            description = "Loading",
            code = -1
        ))

    // Debounce for Text Inputs
    private val _ideasUpdates = MutableStateFlow<String?>(null)
    private val _notesUpdates = MutableStateFlow<String?>(null)
    
    private val _saveState = MutableStateFlow(cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.IDLE)
    val saveState: StateFlow<cloud.wafflecommons.pixelbrainreader.ui.components.SaveState> = _saveState.asStateFlow()

    // --- The Unified Reactive State ---
    val uiState: StateFlow<DailyNoteState> = combine(
        _selectedDate,
        _selectedDate.flatMapLatest { dashboardRepository.getDashboard(it) },
        _selectedDate.flatMapLatest { dashboardRepository.getLiveTimeline(it) },
        _selectedDate.flatMapLatest { dashboardRepository.getLiveTasks(it) },
        scratchRepository.getActiveScraps(),
        _currentDayMood,
        _healthMetrics,
        _moodTrend,
        _weather,
        _isLoading,
        _userMessage,
        userPrefs.isBriefingExpanded.map { it ?: true },
        _ideasUpdates,
        _notesUpdates
    ) { args ->
        val date = args[0] as LocalDate
        val dashboard = args[1] as? cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyDashboardEntity
        @Suppress("UNCHECKED_CAST") val timeline = args[2] as? List<TimelineEntryEntity> ?: emptyList()
        @Suppress("UNCHECKED_CAST") val tasks = args[3] as? List<DailyTaskEntity> ?: emptyList()
        @Suppress("UNCHECKED_CAST") val scraps = args[4] as? List<cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity> ?: emptyList()
        val moodData = args[5] as? DailyMoodData
        val healthMetrics = args[6] as? cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
        @Suppress("UNCHECKED_CAST") val moodTrendData = args[7] as? List<DailyMoodPoint> ?: emptyList()
        val weatherData = args[8] as? WeatherData
        val isLoading = args[9] as Boolean
        val userMsg = args[10] as? String
        val isExpanded = args[11] as Boolean

        // Shield ideas and notes during active typing. _ideasUpdates/_notesUpdates
        // are combine SOURCES (not bare .value reads) so every keystroke re-emits
        // uiState immediately, instead of the field appearing frozen until the
        // 1500 ms debounce persists to Room and the dashboard flow re-emits.
        val currentIdeasUpdates = args[12] as? String
        val currentNotesUpdates = args[13] as? String
        
        val shieldedIdeas = if (currentIdeasUpdates != null && currentIdeasUpdates != dashboard?.ideasContent) {
            currentIdeasUpdates
        } else {
            dashboard?.ideasContent ?: ""
        }

        val shieldedNotes = if (currentNotesUpdates != null && currentNotesUpdates != dashboard?.notesContent) {
            currentNotesUpdates
        } else {
            dashboard?.notesContent ?: ""
        }

        val dailyTags = moodData?.entries?.flatMap { it.activities ?: emptyList() }
            ?.groupingBy { it }
            ?.eachCount()?.entries?.sortedByDescending { it.value }?.take(5)?.map { it.key } 
            ?: emptyList()

        DailyNoteState(
            date = date,
            moodData = moodData,
            moodTrend = moodTrendData,
            healthMetrics = healthMetrics,
            mantra = dashboard?.dailyMantra ?: "Stay safe my friend, and don't you dare go hollow!",
            ideasContent = shieldedIdeas,
            notesContent = shieldedNotes,
            timelineEvents = timeline,
            dailyTasks = tasks,
            scratchNotes = scraps,
            weather = weatherData ?: cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData("⌛", "--°C", "Loading...", "Loading", -1),
            weatherData = weatherData,
            isLoading = isLoading,
            topDailyTags = dailyTags,
            userMessage = userMsg,
            briefingState = MorningBriefingUiState(
                weather = weatherData,
                moodTrend = moodTrendData,
                isExpanded = isExpanded,
                isLoading = false,
                quote = "Stay safe my friend, and don't you dare go hollow!"
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DailyNoteState())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                syncHealthDataUseCase(LocalDate.now())
            } catch (e: Exception) {
                Log.e("DailyNoteVM", "Silent Health Sync Failed", e)
            }
        }
        refreshWeather()
        
        // Initial Trigger
        loadDailyNote(LocalDate.now())
        setupDebouncers()
    }

    fun loadDailyNote(date: LocalDate) {
        if (date != _selectedDate.value) {
            // Forget commands processed for the date we're leaving.
            processedEventCommands.remove(_selectedDate.value)
        }
        _selectedDate.value = date
        viewModelScope.launch {
            _isLoading.value = true
            // Ensure Buffer is Ready (Ingest if needed)
            if (!dashboardRepository.hasBuffer(date)) {
                ingestFromFile(date)
            }
            _isLoading.value = false
        }
    }
    
    private suspend fun ingestFromFile(date: LocalDate) {
        val path = "10_Journal/${date.format(DateTimeFormatter.ISO_DATE)}.md"
        val content = fileRepository.readFile(path)
        dashboardRepository.ingest(date, content ?: "")
    }

    // --- Interactive Actions ---
    
    fun addTimelineEntry(content: String, time: LocalTime) {
        viewModelScope.launch { dashboardRepository.addTimelineEntry(_selectedDate.value, content, time) }
    }

    fun updateTimelineEntry(entry: TimelineEntryEntity) {
        viewModelScope.launch { dashboardRepository.updateTimelineEntry(entry) }
    }

    fun addTask(label: String, targetDate: LocalDate? = null, scheduledTime: LocalTime? = null) {
        viewModelScope.launch {
            taskRepository.addTask(label, targetDate ?: _selectedDate.value, scheduledTime)
        }
    }

    fun updateTask(task: DailyTaskEntity) {
        viewModelScope.launch { dashboardRepository.updateTask(task) }
    }

    fun toggleTask(taskId: String, isDone: Boolean) {
        viewModelScope.launch { taskRepository.toggleTask(taskId, isDone) }
    }

    fun addGratitude(text: String) {
        viewModelScope.launch { dailyNoteRepository.addGratitude(_selectedDate.value, text) }
    }

    fun saveScrap(content: String, color: Int = 0xFF000000.toInt()) {
        viewModelScope.launch { scratchRepository.saveScrap(content, color) }
    }

    fun deleteScrap(scrap: cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity) {
        viewModelScope.launch { scratchRepository.deleteScrap(scrap) }
    }

    fun promoteScrapToIdeas(scrap: cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity) {
        viewModelScope.launch {
            val currentIdeas = (uiState.value.ideasContent)
            val newIdeas = if (currentIdeas.isBlank()) scrap.content else "$currentIdeas\n\n${scrap.content}"
            dashboardRepository.updateSecondBrain(_selectedDate.value, "IDEAS", newIdeas)
            scratchRepository.updateScrap(scrap.copy(isPromoted = true))
            _userMessage.value = "Scrap promoted to Second Brain"
        }
    }

    fun onIdeasChanged(content: String) {
        _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.UNSAVED
        _ideasUpdates.value = content
    }

    fun onNotesChanged(content: String) {
        _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.UNSAVED
        _notesUpdates.value = content
    }
    
    @OptIn(FlowPreview::class)
    private fun setupDebouncers() {
        _ideasUpdates.debounce(1500L).filterNotNull().distinctUntilChanged().onEach { content ->
            _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVING
            try {
                dashboardRepository.updateSecondBrain(_selectedDate.value, "IDEAS", content)
                scanContentForEventCommands(content, _selectedDate.value)
                _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVED
                delay(2500L)
                if (_saveState.value == cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVED) {
                    _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.IDLE
                }
            } catch (e: Exception) {
                _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.ERROR
            }
        }.launchIn(viewModelScope)

        _notesUpdates.debounce(1500L).filterNotNull().distinctUntilChanged().onEach { content ->
            _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVING
            try {
                dashboardRepository.updateSecondBrain(_selectedDate.value, "NOTES", content)
                scanContentForEventCommands(content, _selectedDate.value)
                _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVED
                delay(2500L)
                if (_saveState.value == cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVED) {
                    _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.IDLE
                }
            } catch (e: Exception) {
                _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.ERROR
            }
        }.launchIn(viewModelScope)
    }

    fun clearUserMessage() { _userMessage.value = null }

    // --- /event command surface ----------------------------------------------

    /**
     * Parses a `/event ...` command line and inserts the entry into today's
     * timeline. Local-only — Google Calendar sync is now import-only.
     */
    fun createEventFromCommand(line: String, date: LocalDate = _selectedDate.value) {
        val parsed = cloud.wafflecommons.pixelbrainreader.data.utils.MarkdownCommandParser
            .parseEvent(line, today = date)
            ?: run {
                _userMessage.value = "Couldn't parse /event command"
                return
            }
        viewModelScope.launch(Dispatchers.IO) {
            dashboardRepository.addTimelineEntry(
                date = parsed.startsAt.toLocalDate(),
                content = parsed.title,
                time = parsed.startsAt.toLocalTime()
            )
            _userMessage.value = "📅 Event added: ${parsed.title}"
        }
    }

    fun deleteTimelineEvent(entry: TimelineEntryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dashboardRepository.deleteTimelineEntry(entry.id)
            _userMessage.value = "Event removed"
        }
    }

    /**
     * Scans saved content for `/event` lines and dispatches the ones we
     * haven't seen yet for this date. Idempotent thanks to
     * [processedEventCommands]: re-saving the same content is a no-op.
     */
    private fun scanContentForEventCommands(content: String, date: LocalDate) {
        val processed = processedEventCommands.getOrPut(date) { mutableSetOf() }
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("/event", ignoreCase = true) && line !in processed) {
                processed += line
                createEventFromCommand(line, date)
            }
        }
    }
    
    fun compileDay() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dashboardRepository.burnToDisk(_selectedDate.value)
                _userMessage.value = "Day exported and closed successfully"
            } catch (e: Exception) {
                _userMessage.value = "Error during export"
            }
        }
    }
    
    fun toggleBriefing() {
        viewModelScope.launch {
            userPrefs.setBriefingExpanded(!(uiState.value.briefingState.isExpanded))
        }
    }
}

