package cloud.wafflecommons.pixelbrainreader.ui.daily

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyMoodData
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.NewsRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.ScratchRepository
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class DailyNoteState(
    val date: LocalDate = LocalDate.now(),
    val moodData: DailyMoodData? = null,
    
    // Core Dashboard Content (Room)
    val mantra: String = "",
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
    private val dashboardRepository: DailyDashboardRepository, // [NEW] The Engine
    private val scratchRepository: ScratchRepository,
    private val userPrefs: cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository,
    private val dataRefreshBus: cloud.wafflecommons.pixelbrainreader.data.utils.DataRefreshBus,
    private val briefingGenerator: cloud.wafflecommons.pixelbrainreader.data.ai.BriefingGenerator,
    private val jGitProvider: cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(DailyNoteState())
    val uiState: StateFlow<DailyNoteState> = _uiState.asStateFlow()

    private var currentDate: LocalDate = LocalDate.now()
    
    // Debounce for Text Inputs
    private val _ideasUpdates = MutableStateFlow<String?>(null)
    private val _notesUpdates = MutableStateFlow<String?>(null)

    init {
        // Initial Load
        currentDate = LocalDate.now()
        loadDailyNote(currentDate)
        
        setupDebouncers()
        
        // Listen for global refresh
        viewModelScope.launch {
            dataRefreshBus.refreshEvent.collect {
                Log.d("DailyNoteVM", "Data refresh event received.")
                // SYNC SHIELD: On Git Pull, DO NOT overwrite if we are working on the current day's buffer.
                // We only ingest if the buffer is MISSING.
                val bufferExists = dashboardRepository.hasBuffer(currentDate)
                if (!bufferExists) {
                     Log.d("DailyNoteVM", "Buffer missing, ingesting from file safely.")
                     ingestFromFile(currentDate)
                } else {
                     Log.d("DailyNoteVM", "Buffer exists. SYNC SHIELD ACTIVE. Ignoring external file changes for today to protect local work.")
                     // Optional: notification to user? "External changes detected but ignored to protect your work."
                }
            }
        }
    }

    fun loadDailyNote(date: LocalDate) {
        currentDate = date
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
            // combine for 4 flows:
            _uiState.update { 
                it.copy(
                    mantra = dashboard?.dailyMantra ?: "",
                    ideasContent = dashboard?.ideasContent ?: "",
                    notesContent = dashboard?.notesContent ?: "",
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
        
        // Weather & Briefing Logic
        val isExpanded = userPrefs.isBriefingExpanded.firstOrNull() ?: true
        
        // AI Policy Integration
        val (weatherBriefing, quote) = dashboardRepository.getOrGenerateBriefing(date)
        
        // Briefing loading logic...
        val briefingState = loadMorningBriefingData(date, null, isExpanded, weatherBriefing)

        // Tags
        val dailyTags = mood?.entries?.flatMap { it.activities ?: emptyList() }
            ?.groupingBy { it }
            ?.eachCount()?.entries?.sortedByDescending { it.value }?.take(5)?.map { it.key } 
            ?: emptyList()

        _uiState.update {
            it.copy(
                moodData = mood,
                briefingState = briefingState, // Make sure quote is passed?
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

    fun addTask(label: String, scheduledTime: LocalTime? = null) {
        viewModelScope.launch {
            dashboardRepository.addTask(currentDate, label, scheduledTime)
        }
    }

    fun toggleTask(taskId: String, isDone: Boolean) {
        viewModelScope.launch {
            dashboardRepository.toggleTask(taskId, isDone) // Date needed? No, ID is PK.
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
        _ideasUpdates.value = content
    }

    fun onNotesChanged(content: String) {
        _uiState.update { it.copy(notesContent = content) }
        _notesUpdates.value = content
    }
    
    @OptIn(FlowPreview::class)
    private fun setupDebouncers() {
        _ideasUpdates.debounce(300L).filterNotNull().onEach { 
            dashboardRepository.updateSecondBrain(currentDate, "IDEAS", it)
        }.launchIn(viewModelScope)

        _notesUpdates.debounce(300L).filterNotNull().onEach { 
            dashboardRepository.updateSecondBrain(currentDate, "NOTES", it)
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

    // --- Briefing Helpers (Simplified for brevity, logic preserved) ---
    // --- Briefing Helpers ---
    private suspend fun loadMorningBriefingData(
        date: LocalDate, 
        existingWeather: WeatherData?, 
        isExpanded: Boolean,
        weatherAdvice: String
    ): MorningBriefingUiState {
        val weather = if (date == LocalDate.now()) weatherRepository.getCurrentWeatherAndLocation() else null
        val news = try { newsRepository.getTodayNews() } catch (e: Exception) { emptyList() }
        
        // Mood Trends (Calculated here)
        val moodTrend = loadMoodTrend(date)

        val quote = briefingGenerator.getDailyQuote("Neutral") 
        
        return MorningBriefingUiState(
            weather = weather,
            weatherAdvice = weatherAdvice,
            moodTrend = moodTrend,
            news = news,
            quote = quote,
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
