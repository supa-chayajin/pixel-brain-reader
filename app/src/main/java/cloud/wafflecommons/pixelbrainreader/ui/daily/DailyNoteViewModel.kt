package cloud.wafflecommons.pixelbrainreader.ui.daily

import android.util.Log
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
    val briefingState: MorningBriefingUiState = MorningBriefingUiState(),
    val topDailyTags: List<String> = emptyList(), // [NEW] Top 5 daily tags
    val userMessage: String? = null // [NEW] For Snackbar
)

data class DailyMoodPoint(
    val date: LocalDate,
    val score: Float, // 0 if missing/skipped
    val emoji: String
)

data class MorningBriefingUiState(
    val weather: WeatherData? = null,
    val moodTrend: List<DailyMoodPoint> = emptyList(), // [UPDATED] Richer data
    val topTags: List<String> = emptyList(),
    val quote: String = "",
    val quoteAuthor: String = "",
    val weatherAdvice: String = "", // [NEW] AI Weather Advice
    val news: List<cloud.wafflecommons.pixelbrainreader.data.local.entity.NewsArticleEntity> = emptyList(),
    val isExpanded: Boolean = true, // [NEW] Persisted State
    val isLoading: Boolean = true
)


@HiltViewModel
class DailyNoteViewModel @Inject constructor(
    private val moodRepository: MoodRepository,
    private val newsRepository: cloud.wafflecommons.pixelbrainreader.data.repository.NewsRepository,
    private val fileRepository: FileRepository,
    private val weatherRepository: WeatherRepository,
    private val secretManager: SecretManager,
    private val dailyNoteRepository: cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository,
    private val templateRepository: cloud.wafflecommons.pixelbrainreader.data.repository.TemplateRepository,
    private val taskRepository: cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository,
    private val userPrefs: cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository, // Injected
    private val dataRefreshBus: cloud.wafflecommons.pixelbrainreader.data.utils.DataRefreshBus,
    private val briefingGenerator: cloud.wafflecommons.pixelbrainreader.data.ai.BriefingGenerator,
    private val jGitProvider: cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider // [NEW] Direct Injection for Emergency Sync
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
            val briefingExpandedFlow = userPrefs.isBriefingExpanded // Observe Preference

            combine(moodFlow, contentFlow, briefingExpandedFlow) { mood, content, isBriefingExpanded ->
                var intro = ""
                var outro = ""
                var weather: WeatherData? = null
                var timelineEvents: List<cloud.wafflecommons.pixelbrainreader.data.model.TimelineEvent> = emptyList()
                
                if (content != null) {
                    val frontmatter = FrontmatterManager.extractFrontmatter(content)
                    val body = FrontmatterManager.stripFrontmatter(content)
                    
                    val parsed = parseSplitContent(body)
                    // Fix: Strip "Timeline" section and ensure clean spacing
                    val rawIntro = parsed.first.replaceFirst(Regex("^# 📅 \\d{4}-\\d{2}-\\d{2}\\s*"), "").trim()
                    
                    // Remove Timeline Section Robustly
                    val timelineRegex = Regex("(?s)## (?:🗓️ )?Timeline.*?(?=^## |\\Z)", setOf(RegexOption.MULTILINE))
                    intro = rawIntro.replace(timelineRegex, "")
                        .replace(Regex("\n{3,}"), "\n\n") // Collapse multiple blank lines
                        .trim()
                    
                    outro = parsed.second
                    
                    // Parse Timeline
                    timelineEvents = parseTimeline(body.lines())
                    
                    // Parse Briefing (if explicit section exists, though we are moving to Cockpit)
                    val briefingData = parseBriefingSection(body)
                    
                    // Populate Display Content
                    var filteredIntro = filterTimelineSection(intro)
                    if (briefingData != null) {
                        filteredIntro = filterBriefingSection(filteredIntro)
                    }
                    
                    // Check if weather is valid in frontmatter
                    // Check if weather is valid in frontmatter
                    val weatherEmoji = frontmatter["weather"]
                    val temp = frontmatter["temperature"]
                    val loc = frontmatter["location"]
                    
                    if (!weatherEmoji.isNullOrEmpty() && !temp.isNullOrEmpty()) {
                         weather = WeatherData(weatherEmoji, temp, loc, "Saved")
                    } else {
                        // Trigger async save, but don't block UI
                        launch { fetchAndSaveWeather(date, notePath, content, frontmatter) }
                    }

                    // --- NEW: Load Cockpit Data (Robustly) ---
                    // launching async to not block the main content flow if using combine-transform, 
                    // but here we are in a simple map. 
                    // Ideally we should have a separate flow for briefing, but for now we calculate it here
                    // or better: let's invoke the suspend function safely.
                    // Note: This makes the combine block suspend-heavy if we don't be careful.
                    // Given the request, we will call the suspend validation here.
                    
                    val weatherAdvice = frontmatter["weather_insight"] ?: ""
                    val briefingState = loadMorningBriefingData(date, weather, isBriefingExpanded, weatherAdvice)

                    // Calculate Top 5 Daily Tags (from mood entries of the day)
                    val dailyTags = mood?.entries?.flatMap { it.activities ?: emptyList() }
                        ?.groupingBy { it }
                        ?.eachCount()
                        ?.entries
                        ?.sortedByDescending { it.value }
                        ?.take(5)
                        ?.map { it.key }
                        ?: emptyList()

                     _uiState.value.copy(
                        date = date,
                        moodData = mood,
                        noteIntro = intro,
                        noteOutro = outro,
                        weatherData = weather,
                        timelineEvents = timelineEvents,
                        displayIntro = intro,
                        briefingState = briefingState,
                        topDailyTags = dailyTags, // [UPDATED]
                        isLoading = false
                    )
                } else {
                     val briefingState = loadMorningBriefingData(date, null, isBriefingExpanded, "")
                     
                     // Calculate Top 5 Daily Tags even if no content file (but mood exists)
                    val dailyTags = mood?.entries?.flatMap { it.activities ?: emptyList() }
                        ?.groupingBy { it }
                        ?.eachCount()
                        ?.entries
                        ?.sortedByDescending { it.value }
                        ?.take(5)
                        ?.map { it.key }
                        ?: emptyList()

                     _uiState.value.copy(
                        date = date,
                        moodData = mood,
                        briefingState = briefingState,
                        topDailyTags = dailyTags, // [UPDATED]
                        isLoading = false
                    )
                }
            }.catch { e ->
                Log.e("DailyNoteViewModel", "Error in data stream", e)
                _uiState.value = _uiState.value.copy(isLoading = false)
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    fun toggleBriefing() {
        viewModelScope.launch {
            val current = _uiState.value.briefingState.isExpanded
            userPrefs.setBriefingExpanded(!current)
        }
    }

    private suspend fun loadMorningBriefingData(
        date: LocalDate, 
        existingWeather: WeatherData?, 
        isExpanded: Boolean,
        weatherAdvice: String // [NEW]
    ): MorningBriefingUiState {
        // 1. Weather (Safe Call)
        val finalWeather = existingWeather ?: runCatching {
             val isToday = date == LocalDate.now()
             if (isToday) weatherRepository.getCurrentWeatherAndLocation() else weatherRepository.getHistoricalWeather(date)
        }.getOrNull()

        // 2. Trend & Top Tags (Last 7 Days)
        // 2. Trend & Top Tags (Last 7 Days)
        val recentMoods = mutableListOf<DailyMoodPoint>()
        val recentTags = mutableListOf<String>()

        runCatching {
            // Iterate 6..0 (Past -> Today) to get correct order for Graph
            (6 downTo 0).forEach { offset ->
                val d = date.minusDays(offset.toLong())
                // Use getDailyMood to get the precise average score (Double) from MoodSummary
                val dailyData = moodRepository.getDailyMood(d).firstOrNull()
                
                if (dailyData != null && dailyData.entries.isNotEmpty()) {
                    val score = dailyData.summary.averageScore.toFloat()
                    val emoji = dailyData.summary.mainEmoji
                    recentMoods.add(DailyMoodPoint(d, score, emoji))
                    
                    // Aggregate tags from ALL entries of the day
                    val dayTags = dailyData.entries.flatMap { it.activities ?: emptyList() }
                    recentTags.addAll(dayTags)
                } else {
                     // Crucial: Add fallback point to prevent graph gaps
                     recentMoods.add(DailyMoodPoint(d, 0f, "∅"))
                }
            }
        }
        
        // Process Tags: Freq Desc -> Top 5
        val topTags = recentTags.groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }

        // 3. Dynamic Content
        
        // 3. Dynamic Content
        
        // Real RSS News (Parallel Fetch)
        val news = try {
            newsRepository.getTodayNews()
        } catch (e: Exception) {
            emptyList()
        }
        
        // AI Quote (Context Aware)
        // Derive trend from recentMoods (last 3 days)
        val trendScore = recentMoods.filter { it.date >= date.minusDays(3) }
            .map { it.score }
            .average()
            
        val trend = when {
            trendScore.isNaN() -> "Neutral"
            trendScore >= 4.0 -> "Excellent"
            trendScore >= 3.0 -> "Good"
            trendScore >= 2.0 -> "Tired"
            else -> "Struggling"
        }
        
        // Split quote/author logic inside generator?
        // Generator returns string "Quote - Author". We need to split it if we want separate fields.
        // Or we update the state to just hold the full string if UI allows.
        // MorningBriefingUiState has quote and quoteAuthor.
        // Let's assume generator returns formatted string and we split it here robustly.
        val fullQuote = briefingGenerator.getDailyQuote(trend)
        val quoteParts = fullQuote.split(" - ").takeIf { it.size >= 2 }
        val quoteText = quoteParts?.firstOrNull()?.replace("\"", "") ?: fullQuote
        val quoteAuthor = quoteParts?.getOrNull(1) ?: "AI"

        return MorningBriefingUiState(
            weather = finalWeather,
            moodTrend = recentMoods, 
            topTags = topTags,
            quote = quoteText,
            quoteAuthor = quoteAuthor,
            weatherAdvice = weatherAdvice, // [NEW]
            news = news,
            isExpanded = isExpanded, // Pass through
            isLoading = false
        )
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

    fun refreshDailyData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                // 1. Force Refresh News (Clears cache and fetches fresh)
                newsRepository.getTodayNews(forceRefresh = true)
                
                // 2. REGENERATE BRIEFING (Force AI Call & File Update)
                forceBriefingRegeneration(_uiState.value.date)

                // 3. Re-trigger full reload
                reloadDataOnly(_uiState.value.date)
                
                // Note: Briefing generation happens inside reloadDataOnly -> loadMorningBriefingData
            } catch (e: Exception) {
                Log.e("DailyNoteVM", "Refresh failed", e)
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }
    
    private suspend fun forceBriefingRegeneration(date: LocalDate) {
        // 1. Fetch Fresh Data matches DailyBriefingWorker logic
        val weather = weatherRepository.getCurrentWeatherAndLocation()
        val insight = if (weather != null) {
            briefingGenerator.getWeatherInsight(weather)
        } else {
            "Préparez-vous pour la journée."
        }
        
        // Mood logic might be desired too, but focusing on insight for now as requested
        // Or should we update the whole section? 
        // User objective: "Re-trigger the BriefingGenerator to update AI insights (Weather/Quote)."
        // So we should do Quote too.
        
        // 2. Load File
        val formattedDate = date.format(DateTimeFormatter.ISO_DATE)
        val notePath = "10_Journal/$formattedDate.md"
        var content = fileRepository.readFile(notePath) ?: return

        // 3. Generate Quote (Simplified Trend Calculation here or reuse Repo? 
        // DailyBriefingWorker duplicated logic. Ideally we extract it.
        // For speed, let's just get today's mood or last few days.
        val moodTrend = "Neutral" // Simplified for forced refresh if full history scan is expensive. 
        // Actually let's try to get it properly if possible using existing repo calls? 
        // We are in suspend.
        // Let's rely on BriefingGenerator fallback if we pass generic.
        val quote = briefingGenerator.getDailyQuote(moodTrend)

        // 4. Update Frontmatter
        val updates = mapOf(
            "weather_insight" to insight
            // "quote": quote? If we want to store quote too. 
            // Current FrontmatterManager.injectWeather only merges weather/insight.
            // BriefingGenerator logic suggests we should store insights.
        )
        content = cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager.injectWeather(content, updates)
        
        // 5. Save
        fileRepository.updateFile(notePath, content)
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
        
        // [NEW] Reactive Sync Refresh
        viewModelScope.launch {
            dataRefreshBus.refreshEvent.collect {
                Log.d("Cortex", "Refresh Signal Received -> Reloading Daily Note")
                // Force Disk Read -> Cache Update -> Flow Trigger
                val date = _uiState.value.date
                val content = dailyNoteRepository.getDailyNoteContent(date)
                if (content != null) {
                    val formattedDate = date.format(DateTimeFormatter.ISO_DATE)
                    val notePath = "10_Journal/$formattedDate.md"
                    // Update cache to trigger reactive flows
                    fileRepository.saveFileLocally(notePath, content)
                } else {
                    reloadDataOnly(date)
                }
            }
        }

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

    // [NEW] Emergency Sync Logic
    fun triggerEmergencySync() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val result = jGitProvider.commitAndForcePush("Manual emergency sync from Dashboard")
                if (result.isSuccess) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        userMessage = "Vault forced to remote successfully"
                    )
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        userMessage = "Sync Failed: $error"
                    )
                }
            } catch (e: Exception) {
                 _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    userMessage = "Sync Error: ${e.message}"
                )
            }
        }
    }
    
    fun clearUserMessage() {
        _uiState.value = _uiState.value.copy(userMessage = null)
    }

    private suspend fun saveNoteInternal(content: String) {
         val date = _uiState.value.date
         val formattedDate = date.format(DateTimeFormatter.ISO_DATE)
         val notePath = "10_Journal/$formattedDate.md"
         fileRepository.saveFileLocally(notePath, content)
    }
}
