package cloud.wafflecommons.pixelbrainreader.ui.daily

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyDashboardEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.GratitudeEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.MoodEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyMoodData
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HealthMetricsRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.ScratchRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncState
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.utils.MarkdownCommandParser
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import cloud.wafflecommons.pixelbrainreader.ui.components.SaveState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

const val DEFAULT_MANTRA = "Stay safe my friend, and don't you dare go hollow!"

/** Placeholder while the first weather reading loads (the card spins on "--°C"). */
val WEATHER_LOADING = WeatherData(emoji = "⌛", temperature = "--°C", location = "Loading...", description = "Loading", code = -1)

/** Shown when no reading could be fetched and none is cached (code -2 → the card's "unavailable" state). */
val WEATHER_UNAVAILABLE = WeatherData(
    emoji = "⚠️",
    temperature = "—",
    location = "Location or network unavailable",
    description = "Unavailable",
    code = -2
)

data class DailyNoteState(
    val date: LocalDate = LocalDate.now(),
    val moodData: DailyMoodData? = null,
    val healthMetrics: DailyHealthMetrics? = null,
    val mantra: String = DEFAULT_MANTRA,
    val timelineEvents: List<TimelineEntryEntity> = emptyList(),
    val dailyTasks: List<DailyTaskEntity> = emptyList(),
    val weather: WeatherData = WEATHER_LOADING,
    val isLoading: Boolean = true,
    val topDailyTags: List<String> = emptyList(),
    val scratchNotes: List<ScratchNoteEntity> = emptyList(),
    val userMessage: String? = null,
    val moodTrend: List<DailyMoodPoint> = emptyList()
)

data class DailyMoodPoint(
    val date: LocalDate,
    val score: Float,
    val emoji: String,
    val avgBpm: Int = 0
)

@HiltViewModel
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class DailyNoteViewModel @Inject constructor(
    private val moodRepository: MoodRepository,
    private val fileRepository: FileRepository,
    private val weatherRepository: WeatherRepository,
    private val dashboardRepository: DailyDashboardRepository,
    private val scratchRepository: ScratchRepository,
    private val dailyNoteRepository: DailyNoteRepository,
    private val taskRepository: TaskRepository,
    private val syncOrchestrator: SyncOrchestrator,
    private val healthMetricsRepository: HealthMetricsRepository,
    appClock: AppClock,
    @param:ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    /** A not-yet-persisted Second Brain edit, bound to the day it was typed on. */
    private data class PendingEdit(val date: LocalDate, val content: String)

    /** One weather (re)load. [id] makes every request distinct; [force] bypasses the reading cache. */
    private data class WeatherRequest(val force: Boolean, val id: Long)

    /**
     * Tracks `/event` command lines already dispatched, keyed by date, to keep the debouncer's
     * auto-scan idempotent: re-saving a note that still contains a previously-processed command
     * must not create a duplicate. Days left behind keep their entry, so a save of the previous day
     * that lands after midnight doesn't dispatch its commands again. Only touched on the main thread.
     */
    private val processedEventCommands = mutableMapOf<LocalDate, MutableSet<String>>()

    // The board always shows the real current day: the Daily screen has no date picker and this
    // ViewModel lives as long as the activity (days, on a device that rarely kills the app).
    private val _selectedDate = MutableStateFlow(appClock.today.value)
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    private val _isLoading = MutableStateFlow(false)
    private val _weatherRequests = MutableStateFlow(WeatherRequest(force = false, id = 0))

    /** Last forced weather request already served — a re-subscription must not force the network again. */
    private var servedForcedWeatherRequest = -1L

    private val _saveState = MutableStateFlow(SaveState.IDLE)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()
    private var savedBadgeJob: Job? = null

    /**
     * Second Brain text, held as Compose snapshot state and updated synchronously on every
     * keystroke. It used to round-trip through a 14-way `combine` StateFlow, whose asynchronous
     * echo could lag a keystroke behind and make the text field drop characters or jump the caret.
     */
    var ideasText by mutableStateOf("")
        private set
    var notesText by mutableStateOf("")
        private set

    // Unsaved edits. Non-null only until persisted: while set, they shield the text field from
    // stored values; once saved they are cleared, so later changes from elsewhere show up again.
    private val _ideasEdits = MutableStateFlow<PendingEdit?>(null)
    private val _notesEdits = MutableStateFlow<PendingEdit?>(null)

    // Global Sync State for PullToRefresh
    val isSyncing: StateFlow<SyncState> = syncOrchestrator.syncState

    // RFC-009: Gratitude Express Flow
    val gratitudes: StateFlow<List<GratitudeEntity>> = _selectedDate.flatMapLatest { date ->
        dailyNoteRepository.getGratitudesStream(date)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _currentDayMood: Flow<DailyMoodData?> = _selectedDate
        .flatMapLatest { date -> moodRepository.getDailyMood(date) }

    // Health metrics are one-shot vault reads: re-read whenever they are rewritten (foreground
    // refresh, sync) instead of on every sync-state tick.
    private val _healthMetrics: Flow<DailyHealthMetrics?> =
        combine(_selectedDate, healthMetricsRepository.changes) { date, _ -> date }
            .flatMapLatest { date -> healthMetricsRepository.getMetricsFlow(date) }

    private val _moodTrend: Flow<List<DailyMoodPoint>> =
        combine(_selectedDate, healthMetricsRepository.changes) { date, _ -> date }
            .flatMapLatest { date ->
                combine(
                    moodRepository.getMoodsBetween(date.minusDays(6), date),
                    healthMetricsRepository.getMetricsHistoryFlow(date, 7)
                ) { moods, metrics -> buildMoodTrend(date, moods, metrics) }
            }

    private val _weather: StateFlow<WeatherData> =
        combine(_selectedDate, _weatherRequests) { date, request -> date to request }
            .flatMapLatest { (date, request) ->
                val force = request.force && request.id != servedForcedWeatherRequest
                if (force) servedForcedWeatherRequest = request.id
                weatherUpdates(date, force)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WEATHER_LOADING)

    private data class Board(
        val dashboard: DailyDashboardEntity?,
        val timeline: List<TimelineEntryEntity>,
        val tasks: List<DailyTaskEntity>,
        val scraps: List<ScratchNoteEntity>
    )

    private data class Vitals(
        val mood: DailyMoodData?,
        val health: DailyHealthMetrics?,
        val trend: List<DailyMoodPoint>,
        val weather: WeatherData
    )

    private val _board: Flow<Board> = combine(
        _selectedDate.flatMapLatest { dashboardRepository.getDashboard(it) },
        _selectedDate.flatMapLatest { dashboardRepository.getLiveTimeline(it) },
        _selectedDate.flatMapLatest { dashboardRepository.getLiveTasks(it) },
        scratchRepository.getActiveScraps()
    ) { dashboard, timeline, tasks, scraps -> Board(dashboard, timeline, tasks, scraps) }

    private val _vitals: Flow<Vitals> = combine(_currentDayMood, _healthMetrics, _moodTrend, _weather) { mood, health, trend, weather ->
        Vitals(mood, health, trend, weather)
    }

    // --- The Unified Reactive State ---
    val uiState: StateFlow<DailyNoteState> = combine(
        _selectedDate, _board, _vitals, _isLoading, _userMessage
    ) { date, board, vitals, isLoading, userMessage ->
        DailyNoteState(
            date = date,
            moodData = vitals.mood,
            healthMetrics = vitals.health,
            moodTrend = vitals.trend,
            mantra = board.dashboard?.dailyMantra?.takeIf { it.isNotBlank() } ?: DEFAULT_MANTRA,
            timelineEvents = board.timeline,
            dailyTasks = board.tasks,
            scratchNotes = board.scraps,
            weather = vitals.weather,
            isLoading = isLoading,
            topDailyTags = topTags(vitals.mood),
            userMessage = userMessage
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailyNoteState(date = appClock.today.value))

    init {
        loadDailyNote(_selectedDate.value)
        setupSecondBrain()
        // Follow the real day (midnight, clock/timezone changes, app returning after midnight).
        appClock.today
            .onEach { today -> if (today != _selectedDate.value) loadDailyNote(today) }
            .launchIn(viewModelScope)
        // Re-check the weather whenever the app returns to the foreground (served from the reading
        // cache while recent). Health numbers are refreshed app-wide by ForegroundRefresher.
        appClock.foregroundEvents
            .onEach { refreshWeather() }
            .launchIn(viewModelScope)
    }

    /**
     * Re-requests the weather. A plain refresh reuses a recent cached reading (quick app switches
     * cost nothing); [force] always hits the network (pull-to-refresh, permission just granted).
     */
    fun refreshWeather(force: Boolean = false) {
        _weatherRequests.update { WeatherRequest(force = force, id = it.id + 1) }
    }

    /**
     * Pull-to-refresh: a full Git→Health→Git sync cycle. User-initiated, so it bypasses the cooldown
     * and runs in the application scope (it survives leaving the screen).
     */
    fun triggerSync() {
        syncOrchestrator.requestSync(force = true)
        refreshWeather(force = true)
    }

    private fun loadDailyNote(date: LocalDate) {
        if (date != _selectedDate.value) {
            // Save the day being left right away: an edit typed on the new day within the typing
            // debounce would otherwise replace its pending edit before it was written.
            _ideasEdits.value?.let { edit -> viewModelScope.launch { persistSecondBrain(edit, "IDEAS", _ideasEdits) } }
            _notesEdits.value?.let { edit -> viewModelScope.launch { persistSecondBrain(edit, "NOTES", _notesEdits) } }
            ideasText = ""
            notesText = ""
        }
        _selectedDate.value = date
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Ensure Buffer is Ready (Ingest if needed)
                if (!dashboardRepository.hasBuffer(date)) {
                    ingestFromFile(date)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load the board of $date", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun ingestFromFile(date: LocalDate) {
        val path = "10_Journal/${date.format(DateTimeFormatter.ISO_DATE)}.md"
        val content = fileRepository.readFile(path)
        dashboardRepository.ingest(date, content ?: "")
    }

    private fun weatherUpdates(date: LocalDate, force: Boolean): Flow<WeatherData> = flow {
        // 1. The last good reading, immediately — no spinner on resume or cold start.
        val cached = weatherRepository.getCachedWeather(date)
        if (cached != null) emit(cached)
        // 2. Refresh, retrying: right after the app resumes the network often isn't ready yet.
        repeat(WEATHER_MAX_ATTEMPTS) { attempt ->
            val fresh = try {
                weatherRepository.getWeather(date, forceRefresh = force && attempt == 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Weather fetch failed for $date", e)
                null
            }
            if (fresh != null) {
                emit(fresh)
                return@flow
            }
            if (attempt == 0 && cached == null) emit(WEATHER_UNAVAILABLE)
            if (attempt < WEATHER_MAX_ATTEMPTS - 1) delay(WEATHER_RETRY_DELAY_MS * (attempt + 1))
        }
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

    fun deleteScrap(scrap: ScratchNoteEntity) {
        viewModelScope.launch { scratchRepository.deleteScrap(scrap) }
    }

    fun promoteScrapToIdeas(scrap: ScratchNoteEntity) {
        val date = _selectedDate.value
        val newIdeas = if (ideasText.isBlank()) scrap.content else "$ideasText\n\n${scrap.content}"
        ideasText = newIdeas
        val edit = PendingEdit(date, newIdeas)
        _ideasEdits.value = edit
        viewModelScope.launch {
            if (persistSecondBrain(edit, "IDEAS", _ideasEdits)) {
                scratchRepository.updateScrap(scrap.copy(isPromoted = true))
                _userMessage.value = "Scrap promoted to Second Brain"
            }
        }
    }

    fun onIdeasChanged(content: String) {
        ideasText = content
        _saveState.value = SaveState.UNSAVED
        _ideasEdits.value = PendingEdit(_selectedDate.value, content)
    }

    fun onNotesChanged(content: String) {
        notesText = content
        _saveState.value = SaveState.UNSAVED
        _notesEdits.value = PendingEdit(_selectedDate.value, content)
    }

    private fun setupSecondBrain() {
        // Adopt the stored text for the day (first load, promoted scrap, sync ingest) — but never
        // overwrite text the user typed that hasn't been persisted yet.
        _selectedDate
            .flatMapLatest { date -> dashboardRepository.getDashboard(date).map { date to it } }
            .onEach { (date, dashboard) ->
                val storedIdeas = dashboard?.ideasContent.orEmpty()
                val storedNotes = dashboard?.notesContent.orEmpty()
                val pendingIdeas = _ideasEdits.value?.takeIf { it.date == date }?.content
                val pendingNotes = _notesEdits.value?.takeIf { it.date == date }?.content
                if (pendingIdeas == null || pendingIdeas == storedIdeas) ideasText = storedIdeas
                if (pendingNotes == null || pendingNotes == storedNotes) notesText = storedNotes
            }
            .launchIn(viewModelScope)

        // Debounced persistence. Each edit carries its own date, so an edit typed just before
        // midnight is saved to THAT day, never to the day that is on screen when the debounce fires.
        // (debounce before filterNotNull: an edit saved and cleared in the meantime is skipped.)
        _ideasEdits.debounce(TYPING_DEBOUNCE_MS).filterNotNull()
            .onEach { edit -> persistSecondBrain(edit, "IDEAS", _ideasEdits) }
            .launchIn(viewModelScope)
        _notesEdits.debounce(TYPING_DEBOUNCE_MS).filterNotNull()
            .onEach { edit -> persistSecondBrain(edit, "NOTES", _notesEdits) }
            .launchIn(viewModelScope)
    }

    /** Saves [edit]; returns false (and shows the error state) when it could not be written. */
    private suspend fun persistSecondBrain(
        edit: PendingEdit,
        type: String,
        pending: MutableStateFlow<PendingEdit?>
    ): Boolean {
        _saveState.value = SaveState.SAVING
        try {
            dashboardRepository.updateSecondBrain(edit.date, type, edit.content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save $type for ${edit.date}", e)
            _saveState.value = SaveState.ERROR
            return false
        }
        // Saved: stop shielding this text (unless the user kept typing meanwhile).
        pending.compareAndSet(edit, null)
        scanContentForEventCommands(edit.content, edit.date)

        if (_ideasEdits.value != null || _notesEdits.value != null) {
            _saveState.value = SaveState.UNSAVED
            return true
        }
        _saveState.value = SaveState.SAVED
        savedBadgeJob?.cancel()
        savedBadgeJob = viewModelScope.launch {
            delay(SAVED_BADGE_MS)
            if (_saveState.value == SaveState.SAVED) _saveState.value = SaveState.IDLE
        }
        return true
    }

    fun clearUserMessage() { _userMessage.value = null }

    /**
     * viewModelScope is already cancelled here, and with it the typing debounce: an edit made right
     * before leaving the app was lost. What is still pending is saved from the application scope.
     */
    override fun onCleared() {
        _ideasEdits.value?.let { saveInBackground(it, "IDEAS") }
        _notesEdits.value?.let { saveInBackground(it, "NOTES") }
    }

    private fun saveInBackground(edit: PendingEdit, type: String) {
        appScope.launch {
            try {
                dashboardRepository.updateSecondBrain(edit.date, type, edit.content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Final save of $type for ${edit.date} failed", e)
            }
        }
    }

    // --- /event command surface ----------------------------------------------

    /**
     * Parses a `/event ...` command line and inserts the entry into the timeline.
     * Local-only — Google Calendar sync is import-only.
     */
    fun createEventFromCommand(line: String, date: LocalDate = _selectedDate.value) {
        val parsed = MarkdownCommandParser.parseEvent(line, today = date)
            ?: run {
                _userMessage.value = "Couldn't parse /event command"
                return
            }
        val eventDate = parsed.startsAt.toLocalDate()
        val eventTime = parsed.startsAt.toLocalTime()
        viewModelScope.launch {
            // The command line stays in the note, so it is scanned again after a restart: an event
            // that is already on the timeline is not added a second time.
            val alreadyThere = dashboardRepository.getLiveTimeline(eventDate).first()
                .any { it.time == eventTime && it.content == parsed.title }
            if (alreadyThere) return@launch
            dashboardRepository.addTimelineEntry(date = eventDate, content = parsed.title, time = eventTime)
            _userMessage.value = "📅 Event added: ${parsed.title}"
        }
    }

    fun deleteTimelineEvent(entry: TimelineEntryEntity) {
        viewModelScope.launch {
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
        viewModelScope.launch {
            try {
                dashboardRepository.burnToDisk(_selectedDate.value)
                _userMessage.value = "Day exported and closed successfully"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Day export failed", e)
                _userMessage.value = "Error during export"
            }
        }
    }

    private fun topTags(mood: DailyMoodData?): List<String> =
        mood?.entries.orEmpty()
            .flatMap { it.activities }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }

    companion object {
        private const val TAG = "DailyNoteVM"
        private const val TYPING_DEBOUNCE_MS = 1_500L
        private const val SAVED_BADGE_MS = 2_500L
        private const val WEATHER_MAX_ATTEMPTS = 3
        private const val WEATHER_RETRY_DELAY_MS = 15_000L

        /** Seven daily points ending at [endDate]: average mood score/emoji and average heart rate. */
        internal fun buildMoodTrend(
            endDate: LocalDate,
            moods: List<MoodEntity>,
            metrics: List<DailyHealthMetrics>
        ): List<DailyMoodPoint> {
            val moodsByDate = moods.groupBy { it.date }
            val bpmByDate = metrics.associate { it.date to it.averageHeartRate }
            return (6 downTo 0).map { offset ->
                val day = endDate.minusDays(offset.toLong())
                val key = day.toString()
                val dayMoods = moodsByDate[key].orEmpty()
                val bpm = bpmByDate[key] ?: 0
                if (dayMoods.isEmpty()) {
                    DailyMoodPoint(day, 0f, "∅", bpm)
                } else {
                    val avg = dayMoods.map { it.score }.average()
                    DailyMoodPoint(day, avg.toFloat(), moodEmojiFor(avg), bpm)
                }
            }
        }

        private fun moodEmojiFor(avg: Double): String = when {
            avg < 1.8 -> "😫"
            avg.isNaN() -> "😐"
            avg < 2.6 -> "😞"
            avg < 3.4 -> "😐"
            avg < 4.2 -> "🙂"
            else -> "🤩"
        }
    }
}
