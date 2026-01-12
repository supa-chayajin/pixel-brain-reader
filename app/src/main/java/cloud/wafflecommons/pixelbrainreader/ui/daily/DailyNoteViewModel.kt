package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyMoodData
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

    // ... imports
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherRepository
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.model.Task
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach


data class DailyNoteState(
    val date: LocalDate = LocalDate.now(),
    val moodData: DailyMoodData? = null,
    val noteIntro: String = "",
    val noteOutro: String = "",
    val metadata: Map<String, String> = emptyMap(),
     // Deprecating single noteContent in favor of split
    val weatherData: WeatherData? = null,
    val timelineEvents: List<cloud.wafflecommons.pixelbrainreader.data.model.TimelineEvent> = emptyList(),
    val displayIntro: String = "",
    val briefing: cloud.wafflecommons.pixelbrainreader.data.model.BriefingData? = null,
    val isLoading: Boolean = true,
    // Morning Briefing 2.0 (Cockpit)
    val briefingState: MorningBriefingUiState = MorningBriefingUiState()
)

data class MorningBriefingUiState(
    val weather: WeatherData? = null,
    val moodTrend: List<Float> = emptyList(), // 0.0 to 1.0 Normalized Score
    val quote: String = "",
    val news: List<String> = emptyList(),
    val isLoading: Boolean = true
)


@HiltViewModel
class DailyNoteViewModel @Inject constructor(
    private val moodRepository: MoodRepository,
    private val fileRepository: FileRepository,
    private val weatherRepository: WeatherRepository,
    private val secretManager: SecretManager,
    private val dailyNoteRepository: cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository,
    private val templateRepository: cloud.wafflecommons.pixelbrainreader.data.repository.TemplateRepository,
    private val taskRepository: cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DailyNoteState())
    val uiState: StateFlow<DailyNoteState> = _uiState.asStateFlow()

    // init moved to bottom to accommodate debounce setup


    private fun observeUpdates() {
        viewModelScope.launch {
            fileRepository.fileUpdates.collect { path: String ->
                val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
                val notePath = "10_Journal/$today.md"
                // Relaxed check: Is it a json file (Mood) or likely the daily note?
                if (path == notePath || path.endsWith(".json")) {
                   // Only reload data part, don't re-trigger creation logic
                   reloadDataOnly()
                }
            }
        }
    }

    /**
     * Critical Function: Orchestrates the Daily Note loading.
     */
    fun loadDailyNote(date: LocalDate) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            // STEP 1: SYNC FIRST (Blocking)
            val (owner, repo) = secretManager.getRepoInfo()
            if (owner != null && repo != null) {
                try {
                    fileRepository.syncRepository(owner, repo)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // STEP 2: CHECK EXISTENCE
            val exists = dailyNoteRepository.hasNote(date)

            // STEP 3: CREATE ONLY IF TRULY MISSING
            if (!exists) {
                try {
                    val templatePath = "99_System/Templates/T_Daily_Journal.md"
                    var rawTemplate = templateRepository.getTemplateContent(templatePath)
                    
                    if (rawTemplate.isNullOrBlank()) {
                         rawTemplate = """
                             # Journal {{date}}
                             
                             ## 📝 Journal
                             
                             ## 🧠 Idées / Second Cerveau
                         """.trimIndent()
                    }

                    val title = date.format(DateTimeFormatter.ISO_DATE)
                    val processedContent = cloud.wafflecommons.pixelbrainreader.data.utils.TemplateEngine.apply(rawTemplate, title)

                    dailyNoteRepository.createDailyNote(date, processedContent, owner, repo)

                } catch (e: Exception) {
                    android.util.Log.e("DailyNoteVM", "Failed to create note", e)
                }
            }

            // STEP 4: LOAD FINAL CONTENT
            reloadDataOnly(date)
        }
    }
    
    private fun reloadDataOnly(date: LocalDate = LocalDate.now()) {
        viewModelScope.launch {
            val formattedDate = date.format(DateTimeFormatter.ISO_DATE)
            val notePath = "10_Journal/$formattedDate.md"

            // Parallel Fetch
            val moodFlow = moodRepository.getDailyMood(date)
            val contentFlow = fileRepository.getFileContentFlow(notePath)

            combine(moodFlow, contentFlow) { mood, content ->
                var intro = ""
                var outro = ""
                var weather: WeatherData? = null
                var timelineEvents: List<cloud.wafflecommons.pixelbrainreader.data.model.TimelineEvent> = emptyList()
                
                if (content != null) {
                    val frontmatter = FrontmatterManager.extractFrontmatter(content)
                    val body = FrontmatterManager.stripFrontmatter(content)
                    
                    val parsed = parseSplitContent(body)
                    // Fix: Strip "Timeline" section from intro to prevent double rendering
                    // Timeline is rendered via UI component, so we remove raw markdown
                    val rawIntro = parsed.first.replaceFirst(Regex("^# 📅 \\d{4}-\\d{2}-\\d{2}\\s*"), "").trim()
                    intro = rawIntro.replace(Regex("(?s)## (?:🗓️ )?Timeline.*?(?=^## |\\Z)", setOf(RegexOption.MULTILINE)), "").trim()
                    
                    outro = parsed.second
                    
                    // Parse Timeline
                    timelineEvents = parseTimeline(body.lines())
                    
                    // Parse Briefing
                    val briefingData = parseBriefingSection(body)
                    
                    // Populate Display Content (Filter out Timeline AND Briefing)
                    // The timeline/briefing are usually in the "intro" section (before Tasks)
                    var filteredIntro = filterTimelineSection(intro)
                    if (briefingData != null) {
                        filteredIntro = filterBriefingSection(filteredIntro)
                    }
                    
                    val weatherEmoji = frontmatter["weather"]
                    val temp = frontmatter["temperature"]
                    val loc = frontmatter["location"]
                    
                    if (!weatherEmoji.isNullOrEmpty() && !temp.isNullOrEmpty()) {
                        weather = WeatherData(weatherEmoji, temp, loc, "Saved")
                    } else {
                        // Auto-fetch weather if missing and it's today
                        launch { fetchAndSaveWeather(date, notePath, content, frontmatter) }
                    }

                    // Update State
                 val mockQuote = "The best way to predict the future is to create it."
                 val mockNews = listOf(
                     "AI Model Breakthrough: Gemini 2.0 Released",
                     "Pixel 10 Rumors: Holographic Display?",
                     "Global Markets Rally on Tech Earnings"
                 )
                               // Mock Trend for now (should fetch from repo)
                 val mockTrend = listOf(0.6f, 0.7f, 0.5f, 0.8f, 0.9f, 0.7f, 0.8f)

                 _uiState.value.copy(
                    date = date,
                    moodData = mood,
                    noteIntro = intro,
                    noteOutro = outro,
                    // metadata = frontmatter, // Simplified
                    weatherData = weather,
                    timelineEvents = timelineEvents,
                    displayIntro = intro,
                    briefingState = MorningBriefingUiState(
                        weather = weather,
                        moodTrend = mockTrend,
                        quote = mockQuote,
                        news = mockNews,
                        isLoading = false
                    ),
                    isLoading = false
                )
                } else {
                     _uiState.value.copy(
                        date = date,
                        moodData = mood,
                        isLoading = false
                    )
                }
            }.catch { 
                _uiState.value = _uiState.value.copy(isLoading = false)
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    private fun parseBriefingSection(content: String): cloud.wafflecommons.pixelbrainreader.data.model.BriefingData? {
         val regex = Regex("(?s)## (?:🌅 )?Morning Briefing.*?(?=^## |\\Z)", setOf(RegexOption.MULTILINE))
         val match = regex.find(content) ?: return null
         val block = match.value
         
         val lines = block.lines()
         var weather = ""
         var advice = ""
         var mood = ""
         var quote = ""
         val news = mutableListOf<cloud.wafflecommons.pixelbrainreader.data.model.MarkdownLink>()
         
         lines.forEach { line ->
             if (line.contains("**Météo :**")) {
                 val raw = line.substringAfter("**Météo :**").trim()
                 // Split by dash if possible: "🌤️ 15°C - *Advice*"
                 val parts = raw.split(" - ")
                 weather = parts.getOrElse(0) { "" }.trim()
                 advice = parts.getOrElse(1) { "" }.replace("*", "").trim()
             }
             if (line.contains("**Mood 7j :**")) {
                 mood = line.substringAfter("**Mood 7j :**").trim()
             }
             if (line.contains("**Mindset :**")) {
                 quote = line.substringAfter("**Mindset :**").trim()
             }
             // News links usually nested under "Veille" or just list items with links
             if (line.trim().startsWith("* [") || line.trim().startsWith("- [")) {
                 val linkRegex = Regex("\\[(.*?)\\]\\((.*?)\\)")
                 val linkMatch = linkRegex.find(line)
                 if (linkMatch != null) {
                     val (title, url) = linkMatch.destructured
                     news.add(cloud.wafflecommons.pixelbrainreader.data.model.MarkdownLink(title, url))
                 }
             }
         }
         
         return if (weather.isNotEmpty()) {
             cloud.wafflecommons.pixelbrainreader.data.model.BriefingData(weather, advice, mood, quote, news)
         } else null
    }

    private fun filterBriefingSection(content: String): String {
        val regex = Regex("(?s)## (?:🌅 )?Morning Briefing.*?(?=^## |\\Z)", setOf(RegexOption.MULTILINE))
        return regex.replace(content, "").trim()
    }

    private fun filterTimelineSection(content: String): String {
        // Regex: matches "## 🗓️ Timeline" or "## Timeline" until next header or end.
        // (?s) dot matches new line
        // (?m) multiline anchor
        val regex = Regex("(?s)## (?:🗓️ )?Timeline.*?(?=^## |\\Z)", setOf(RegexOption.MULTILINE))
        return regex.replace(content, "").trim()
    }

    private fun parseTimeline(lines: List<String>): List<cloud.wafflecommons.pixelbrainreader.data.model.TimelineEvent> {
        val startHeader = "## \uD83D\uDDD3\uFE0F Timeline" // 🗓️ Timeline (escaped just in case, but literal often works better in Kotlin strings if utf8)
        // Trying literal for safety if source encoding is robust, but user prompt used emoji.
        // Let's use simple string match with standard normalization if possible.
        // The prompt says "## 🗓️ Timeline".
        // 🗓️ is \uD83D\uDDD3\uFE0F
        
        val startIndex = lines.indexOfFirst { it.contains("🗓️ Timeline") || it.contains("Timeline") } 
        // fallback to just Timeline to be safe? prompt specific. strict to prompt:
        
        if (startIndex == -1) return emptyList()
        
        val events = mutableListOf<cloud.wafflecommons.pixelbrainreader.data.model.TimelineEvent>()
        
        for (i in (startIndex + 1) until lines.size) {
            val line = lines[i]
            if (line.trim().startsWith("##")) break // Stop at next header
            if (line.isBlank()) continue

            // Regex: ^\s*-\s+(\d{1,2}:\d{2})\s+(.*)
            val regex = Regex("^\\s*-\\s+(\\d{1,2}:\\d{2})\\s+(.*)")
            val match = regex.find(line)
            
            if (match != null) {
                val (timeStr, content) = match.destructured
                try {
                    // Try parsing with flexible formatter
                    val formatter = DateTimeFormatter.ofPattern("H:mm")
                    val time = java.time.LocalTime.parse(timeStr, formatter)
                    
                    events.add(
                        cloud.wafflecommons.pixelbrainreader.data.model.TimelineEvent(
                            time = time,
                            content = content.trim(),
                            originalLine = line
                        )
                    )
                } catch (e: Exception) {
                    // Skip invalid time formats
                }
            }
        }
        return events
    }

    private fun parseSplitContent(content: String): Pair<String, String> {
        val startHeader = "## \uD83D\uDCDD Journal"
        val endHeader = "## \uD83E\uDDE0 Idées / Second Cerveau"
        
        val lines = content.lines()
        val startIndex = lines.indexOfFirst { it.trim().startsWith(startHeader) }
        val endIndex = lines.indexOfFirst { it.trim().startsWith(endHeader) }
        
        if (startIndex == -1) {
             return content to ""
        }
        
        val introText = lines.subList(0, startIndex).joinToString("\n")
        
        // Outro = Lines AFTER EndHeader (inclusive of EndHeader? usually headers stay with content)
        val outroText = if (endIndex != -1) {
             lines.subList(endIndex, lines.size).joinToString("\n")
        } else {
             ""
        }
        
        return introText to outroText
    }

    private suspend fun fetchAndSaveWeather(date: LocalDate, path: String, currentContent: String, currentFrontmatter: Map<String, String>) {
         val isToday = date == LocalDate.now()
         val result = if (isToday) {
             weatherRepository.getCurrentWeatherAndLocation()
         } else {
             weatherRepository.getHistoricalWeather(date)
         }
         
         if (result != null) {
             val updates = mapOf(
                 "weather" to result.emoji,
                 "temperature" to result.temperature,
                 "location" to (result.location ?: "")
             )
             
             val updatedContent = FrontmatterManager.injectWeather(currentContent, updates)
             
             if (updatedContent != currentContent) {
                 fileRepository.saveFileLocally(path, updatedContent)
                 val (owner, repo) = secretManager.getRepoInfo()
                 if (owner != null && repo != null) {
                     try {
                         fileRepository.pushDirtyFiles(owner, repo, "docs(journal): add weather data for ${date}")
                     } catch (e: Exception) {}
                 }
             }
         }
    }

    fun refresh() {
        // Full reload including sync/check
        loadDailyNote(LocalDate.now())
    }

    /**
     * Fix Race Condition:
     * Toggle Task -> Wait for Disk Write -> Immediately Reload.
     * This ensures the UI reflects the file state and prevents stale writes.
     */
    fun toggleTask(task: Task) {
        viewModelScope.launch {
            // 1. Write to Disk (Suspend until done)
            taskRepository.toggleTask(_uiState.value.date, task)
            
            // 2. Immediately Refetch content from disk
            // We use reloadDataOnly to be fast
            reloadDataOnly(_uiState.value.date)
        }
    }

    // Debounce Save Logic
    private val _contentUpdates = MutableStateFlow<String?>(null)
    
    @OptIn(FlowPreview::class)
    private fun setupDebounce() {
        _contentUpdates
            .debounce(1000L) // 1 second debounce
            .distinctUntilChanged()
            .onEach { content ->
                content?.let {
                    // Check if we are currently reloading to prevent overwrite?
                    if (!_uiState.value.isLoading) {
                        saveNoteInternal(it)
                    } else {
                        android.util.Log.w("DailyNoteVM", "Skipping save: Reload in progress")
                    }
                }
            }
            .launchIn(viewModelScope)
    }
    
    init {
        setupDebounce()
        // On init, we load today's note implicitly
        loadDailyNote(LocalDate.now())
        observeUpdates()
    }

    /**
     * Entry point for Editor to trigger save
     */
    fun onContentChanged(newContent: String) {
        _contentUpdates.value = newContent
    }

    private suspend fun saveNoteInternal(content: String) {
         val date = _uiState.value.date
         val formattedDate = date.format(DateTimeFormatter.ISO_DATE)
         val notePath = "10_Journal/$formattedDate.md"
         fileRepository.saveFileLocally(notePath, content)
    }
}
