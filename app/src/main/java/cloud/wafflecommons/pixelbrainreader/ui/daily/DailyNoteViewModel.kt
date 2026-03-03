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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
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
    val mantra: String = "Stay safe friend, and don't your dare go hollow!",
    val ideasContent: String = "",
    val notesContent: String = "",
    
    val noteIntro: String = "", // Kept for compatibility
    val noteOutro: String = "", // Kept for compatibility
    val metadata: Map<String, String> = emptyMap(),
    val weatherData: WeatherData? = null,
    
    // Room-Backed Live Data
    val timelineEvents: List<TimelineEntryEntity> = emptyList(),
    val dailyTasks: List<DailyTaskEntity> = emptyList(),
    
    val briefing: cloud.wafflecommons.pixelbrainreader.data.model.BriefingData? = null, // Kept for compatibility
    val isLoading: Boolean = true,
    val briefingState: MorningBriefingUiState = MorningBriefingUiState(),
    val topDailyTags: List<String> = emptyList(),
    val scratchNotes: List<cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity> = emptyList(),
    val userMessage: String? = null
)

data class DailyMoodPoint(
    val date: LocalDate,
    val score: Float,
    val emoji: String
)

data class MorningBriefingUiState(
    val weather: WeatherData? = null,
    val moodTrend: List<DailyMoodPoint> = emptyList(),
    val topTags: List<String> = emptyList(),
    val quote: String = "",
    val quoteAuthor: String = "",
    val weatherAdvice: String = "",
    val news: List<cloud.wafflecommons.pixelbrainreader.data.local.entity.NewsArticleEntity> = emptyList(),
    val oracleInsight: String? = null,
    val isExpanded: Boolean = true,
    val isLoading: Boolean = true
)

@HiltViewModel
@OptIn(FlowPreview::class)
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
    private val dailyBriefingRepository: DailyBriefingRepository, // [NEW] Cache-First Repo
    private val jGitProvider: cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider,
    private val gamificationRepository: cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository,
    private val syncHealthDataUseCase: SyncHealthDataUseCase,
    private val dailyNoteRepository: cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository, // [NEW] For Gratitude
    private val taskRepository: cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository // [NEW] Database-First Tasks
) : ViewModel() {

    private val _uiState = MutableStateFlow(DailyNoteState())
    val uiState: StateFlow<DailyNoteState> = _uiState.asStateFlow()
    
    val gamificationState = gamificationRepository.gamificationState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
        
    // [NEW] Reactive Date Selection for Cache
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    
    // [NEW] Cached Briefing/Oracle Flow
    private val _dailyBriefingData = _selectedDate.flatMapLatest { date ->
        flow { emit(dailyBriefingRepository.getBriefingForDate(date)) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // Expose Oracle Insight derived from Cache
    val oracleInsight: StateFlow<String?> = _dailyBriefingData
        .map { it?.oracleInsight }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // RFC-009: Gratitude Express Flow
    val gratitudes: StateFlow<List<GratitudeEntity>> = _selectedDate.flatMapLatest { date ->
        dailyNoteRepository.getGratitudesStream(date)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // [NEW] Persisted UI State
    val isOracleExpanded = userPrefs.isOracleExpanded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private var currentDate: LocalDate = LocalDate.now()
    
    // Debounce for Text Inputs
    private val _ideasUpdates = MutableStateFlow<String?>(null)
    private val _notesUpdates = MutableStateFlow<String?>(null)
    
    private val _saveState = MutableStateFlow(cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.IDLE)
    val saveState: StateFlow<cloud.wafflecommons.pixelbrainreader.ui.components.SaveState> = _saveState.asStateFlow()

    init {
        // [NEW] Fire-and-Forget Health Sync (Parallel)
        // Does not block UI or Briefing.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Ensure permissions are handled in UseCase or silently fail if not ready (UseCase handles checks)
                syncHealthDataUseCase(LocalDate.now())
            } catch (e: Exception) {
                // Swallow sync errors to not crash Dashboard
                Log.e("DailyNoteVM", "Silent Health Sync Failed", e)
            }
        }

        // Initial Load
        currentDate = LocalDate.now()
        loadDailyNote(currentDate)
        
        // The UI now strictly reacts to Room Database flows. 
        // Sync operations are expected to push data to Room, automatically reflecting here.
        viewModelScope.launch {
            _dailyBriefingData.collect { model ->
                if (model != null) {
                    _uiState.update { current ->
                         current.copy(
                             briefingState = current.briefingState.copy(
                                 weatherAdvice = model.briefing,
                                 oracleInsight = model.oracleInsight,
                                 isLoading = false
                             )
                         )
                    }
                }
            }
        }
        
        setupDebouncers()
    }


    fun loadDailyNote(date: LocalDate) {
        currentDate = date
        _selectedDate.value = date // Trigger Cache Load
        viewModelScope.launch {
            _uiState.update { it.copy(date = date, isLoading = true) }

            // 1. Ensure Buffer is Ready (Ingest if needed)
            val bufferExists = dashboardRepository.hasBuffer(date)
            if (!bufferExists) {
                Log.d("DailyNoteVM", "Buffer for $date not found, ingesting from file.")
                ingestFromFile(date)
            } else {
                Log.d("DailyNoteVM", "Buffer for $date found, proceeding.")
            }

            // 2. Start Observing Room Data
            observeRoomData(date)
            
            // 3. Load Stats & Briefing (Async)
            launch { loadAuxiliaryData(date) }
        }
    }
    
    // Ingests ONLY if called. Caller is responsible for "Sync Shield" checks.
    private suspend fun ingestFromFile(date: LocalDate) {
        val path = "10_Journal/${date.format(DateTimeFormatter.ISO_DATE)}.md"
        val content = fileRepository.readFile(path)
        if (content != null) {
            // Logic Pivot: Repository.ingest will overwrite. 
            // We trust the caller has verified we WANT to overwrite (e.g. init empty).
            dashboardRepository.ingest(date, content)
        } else {
            // If file doesn't exist, create an empty buffer for the day
            dashboardRepository.ingest(date, "")
        }
    }

    private fun observeRoomData(date: LocalDate) {
        val dashboardFlow = dashboardRepository.getDashboard(date)
        val timelineFlow = dashboardRepository.getLiveTimeline(date)
        val tasksFlow = dashboardRepository.getLiveTasks(date)
        val scratchFlow = scratchRepository.getActiveScraps()
        
        combine(dashboardFlow, timelineFlow, tasksFlow, scratchFlow) { dashboard, timeline, tasks, scraps ->
            java.util.concurrent.atomic.AtomicReference(java.util.concurrent.atomic.AtomicReference(Triple(dashboard, timeline, tasks)) to scraps) // Dummy to handle 4 flows combine logic if needed or use combine extension
            // Shield ideas and notes from being overwritten by delayed disk emissions
            // if the user is actively typing (uncommitted changes exist in the buffer).
            val currentIdeasUpdates = _ideasUpdates.value
            val currentNotesUpdates = _notesUpdates.value
            
            val shieldedIdeas = if (currentIdeasUpdates != null && currentIdeasUpdates != dashboard?.ideasContent) {
                currentIdeasUpdates // Memory is ahead
            } else {
                dashboard?.ideasContent ?: "" // Disk caught up
            }

            val shieldedNotes = if (currentNotesUpdates != null && currentNotesUpdates != dashboard?.notesContent) {
                currentNotesUpdates
            } else {
                dashboard?.notesContent ?: ""
            }

            _uiState.update { 
                it.copy(
                    mantra = dashboard?.dailyMantra ?: "",
                    ideasContent = shieldedIdeas,
                    notesContent = shieldedNotes,
                    timelineEvents = timeline,
                    dailyTasks = tasks,
                    scratchNotes = scraps,
                    isLoading = false
                )
            }
        }.launchIn(viewModelScope)
    }

    private suspend fun loadAuxiliaryData(date: LocalDate) {
        // Mood
        val mood = moodRepository.getDailyMood(date).firstOrNull()
        
        // Oracle Insight (Async)

        
        // Weather & Briefing Logic
        val isExpanded = userPrefs.isBriefingExpanded.firstOrNull() ?: true
        
        // [NEW] Briefing loaded via Flow separately. 
        // We still load "Non-AI" briefing data here (Mood/News).
        val briefingState = loadMorningBriefingData(date, null, isExpanded)

        // Tags
        val dailyTags = mood?.entries?.flatMap { it.activities ?: emptyList() }
            ?.groupingBy { it }
            ?.eachCount()?.entries?.sortedByDescending { it.value }?.take(5)?.map { it.key } 
            ?: emptyList()

        // Health Metrics Load 
        val metricsFile = File(context.filesDir, "10_Journal/data/health/metrics/$date.json")
        val healthMetrics = if (metricsFile.exists()) {
            try {
                com.google.gson.Gson().fromJson(metricsFile.readText(), cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics::class.java)
            } catch (e: Exception) {
                null
            }
        } else null

        _uiState.update {
            it.copy(
                moodData = mood,
                healthMetrics = healthMetrics,
                briefingState = briefingState, 
                topDailyTags = dailyTags
            )
        }
    }

    // --- Interactive Actions (Room-First) ---
    
    fun addTimelineEntry(content: String, time: LocalTime) {
        viewModelScope.launch {
            dashboardRepository.addTimelineEntry(currentDate, content, time)
        }
    }

    fun addTask(label: String, targetDate: LocalDate = currentDate, scheduledTime: LocalTime? = null) {
        viewModelScope.launch {
            // Use Database-First Repository
            taskRepository.addTask(label, targetDate, scheduledTime)
        }
    }

    fun toggleTask(taskId: String, isDone: Boolean) {
        viewModelScope.launch {
            taskRepository.toggleTask(taskId, isDone)
        }
    }

    // RFC-009: Gratitude Express Action
    fun addGratitude(text: String) {
        viewModelScope.launch {
            dailyNoteRepository.addGratitude(currentDate, text)
        }
    }

    // --- Scratchpad Actions ---
    fun saveScrap(content: String, color: Int = 0xFF000000.toInt()) {
        viewModelScope.launch {
            scratchRepository.saveScrap(content, color)
        }
    }

    fun deleteScrap(scrap: cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity) {
        viewModelScope.launch {
            scratchRepository.deleteScrap(scrap)
        }
    }

    fun promoteScrapToIdeas(scrap: cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity) {
        viewModelScope.launch {
            val currentIdeas = _uiState.value.ideasContent
            val newIdeas = if (currentIdeas.isBlank()) scrap.content else "$currentIdeas\n\n${scrap.content}"
            
            // 1. Update Ideas in Dashboard
            dashboardRepository.updateSecondBrain(currentDate, "IDEAS", newIdeas)
            
            // 2. Mark Scrap as Promoted (or Delete based on user preference? User RFC says "archived")
            // For now, let's delete or mark as promoted. ScratchDao filters isPromoted = 0.
            scratchRepository.updateScrap(scrap.copy(isPromoted = true))
            
            _uiState.update { it.copy(userMessage = "Scrap promoted to Second Brain") }
        }
    }

    // Second Brain (Debounced)
    fun onIdeasChanged(content: String) {
        // Optimistic Update
        _uiState.update { it.copy(ideasContent = content) }
        _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.UNSAVED
        _ideasUpdates.value = content
    }

    fun onNotesChanged(content: String) {
        _uiState.update { it.copy(notesContent = content) }
        _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.UNSAVED
        _notesUpdates.value = content
    }
    
    @OptIn(FlowPreview::class)
    private fun setupDebouncers() {
        _ideasUpdates.debounce(1500L).filterNotNull().distinctUntilChanged().onEach { 
            _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVING
            try {
                dashboardRepository.updateSecondBrain(currentDate, "IDEAS", it)
                _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVED
                viewModelScope.launch {
                    kotlinx.coroutines.delay(2500L)
                    if (_saveState.value == cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVED) {
                        _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.IDLE
                    }
                }
            } catch (e: Exception) {
                _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.ERROR
            }
        }.launchIn(viewModelScope)

        _notesUpdates.debounce(1500L).filterNotNull().distinctUntilChanged().onEach { 
            _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVING
            try {
                dashboardRepository.updateSecondBrain(currentDate, "NOTES", it)
                _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVED
                viewModelScope.launch {
                    kotlinx.coroutines.delay(2500L)
                    if (_saveState.value == cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVED) {
                        _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.IDLE
                    }
                }
            } catch (e: Exception) {
                _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.ERROR
            }
        }.launchIn(viewModelScope)
    }

    fun triggerEmergencySync() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, userMessage = "Burning & Syncing...") }
            try {
                // 1. Burn Room -> Disk
                dashboardRepository.burnToDisk(currentDate)
                
                // 2. Force Push
                val result = jGitProvider.commitAndForcePush("Manual emergency sync from Dashboard")
                
                if (result.isSuccess) {
                    _uiState.update { it.copy(isLoading = false, userMessage = "Vault forced to remote successfully.") }
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown"
                    _uiState.update { it.copy(isLoading = false, userMessage = "Sync Failed: $error") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, userMessage = "Error: ${e.message}") }
            }
        }
    }

    /**
     * Immediately pushes pending updates to the Room database.
     * Hooks natively into Android lifecycle events (onPause/onStop).
     */
    fun forceSaveImmediate() {
        _ideasUpdates.value?.let { 
            viewModelScope.launch { dashboardRepository.updateSecondBrain(currentDate, "IDEAS", it) }
        }
        _notesUpdates.value?.let {
            viewModelScope.launch { dashboardRepository.updateSecondBrain(currentDate, "NOTES", it) }
        }
    }

    fun refreshDailyData() {
        // Re-ingest from file? Or just refresh stats?
        // Usually User wants to re-load from disk if they edited externally.
        viewModelScope.launch {
             _uiState.update { it.copy(isLoading = true) }
             ingestFromFile(currentDate)
             loadAuxiliaryData(currentDate)
             _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun refresh() {
        refreshDailyData()
    }
    
    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
    
    fun toggleBriefing() {
        viewModelScope.launch {
            userPrefs.setBriefingExpanded(!_uiState.value.briefingState.isExpanded)
            // Update local state immediately for responsiveness
            _uiState.update { 
                it.copy(briefingState = it.briefingState.copy(isExpanded = !it.briefingState.isExpanded)) 
            }
        }
    }

    fun toggleOracleExpanded() {
        viewModelScope.launch {
            val current = isOracleExpanded.value
            userPrefs.setOracleExpanded(!current)
        }
    }

    // --- Briefing Helpers (Simplified for brevity, logic preserved) ---
    // --- Briefing Helpers ---
    private suspend fun loadMorningBriefingData(
        date: LocalDate, 
        existingWeather: WeatherData?, 
        isExpanded: Boolean
    ): MorningBriefingUiState {
        val weather = weatherRepository.getCurrentWeatherAndLocation()
        val news = try { newsRepository.getTodayNews() } catch (e: Exception) { emptyList() }
        
        // Mood Trends (Calculated here)
        val moodTrend = loadMoodTrend(date)

        // Quote is still generated on fly? Or cache it? 
        // User requested caching AI. For now, let's keep quote lightweight or remove if blocking.
        // Assuming Quote is fast/light or acceptable to load dynamically.
        // To be strictly caching, we should have added it to DB. 
        // For now, returning empty string for quote to speed up, or generate if needed.
        // Let's keep it but handle failure gracefully.
        
        return MorningBriefingUiState(
            weather = weather,
            // weatherAdvice handled by Flow from Repository
            moodTrend = moodTrend,
            news = news,
            quote = "Stay safe my friend, and don't you dare go hollow!", // Placeholder as BriefingGenerator removed from VM
            isExpanded = isExpanded,
            isLoading = false
        )
    }

    private suspend fun loadMoodTrend(date: LocalDate): List<DailyMoodPoint> {
        val recentMoods = mutableListOf<DailyMoodPoint>()
        // Last 7 Days (Today + 6 past days)
        (6 downTo 0).forEach { offset ->
            val d = date.minusDays(offset.toLong())
            val dailyData = moodRepository.getDailyMood(d).firstOrNull()
            if (dailyData != null && dailyData.entries.isNotEmpty()) {
                recentMoods.add(DailyMoodPoint(d, dailyData.summary.averageScore.toFloat(), dailyData.summary.mainEmoji))
            } else {
                recentMoods.add(DailyMoodPoint(d, 0f, "∅"))
            }
        }
        return recentMoods
    }
}
